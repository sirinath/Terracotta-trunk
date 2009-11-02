/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.tx;

import com.tc.exception.TCClassNotFoundException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.NodeID;
import com.tc.object.ClientIDProvider;
import com.tc.object.ClientObjectManager;
import com.tc.object.LiteralValues;
import com.tc.object.ObjectID;
import com.tc.object.TCObject;
import com.tc.object.appevent.NonPortableEventContextFactory;
import com.tc.object.appevent.ReadOnlyObjectEvent;
import com.tc.object.appevent.ReadOnlyObjectEventContext;
import com.tc.object.appevent.UnlockedSharedObjectEvent;
import com.tc.object.appevent.UnlockedSharedObjectEventContext;
import com.tc.object.dmi.DmiDescriptor;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNAException;
import com.tc.object.locks.ClientLockManager;
import com.tc.object.locks.LockID;
import com.tc.object.locks.LockLevel;
import com.tc.object.locks.Notify;
import com.tc.object.logging.RuntimeLogger;
import com.tc.object.session.SessionID;
import com.tc.object.util.ReadOnlyException;
import com.tc.stats.counter.sampled.SampledCounter;
import com.tc.text.Banner;
import com.tc.text.PrettyPrinter;
import com.tc.text.PrettyPrinterImpl;
import com.tc.util.Assert;
import com.tc.util.ClassUtils;
import com.tc.util.StringUtil;
import com.tc.util.Util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ClientTransactionManagerImpl implements ClientTransactionManager {
  private static final TCLogger                logger          = TCLogging
                                                                   .getLogger(ClientTransactionManagerImpl.class);

  private final ThreadLocal                    transaction     = new ThreadLocal() {
                                                                 @Override
                                                                 protected Object initialValue() {
                                                                   return new ThreadTransactionContext();
                                                                 }
                                                               };

  // We need to remove initialValue() here because read auto locking now calls Manager.isDsoMonitored() which will
  // checks if isTransactionLogging is disabled. If it runs in the context of class loading, it will try to load
  // the class ThreadTransactionContext and thus throws a LinkageError.
  private final ThreadLocal                    txnLogging      = new ThreadLocal();

  private final ClientTransactionFactory       txFactory;
  private final RemoteTransactionManager       remoteTxManager;
  private final ClientObjectManager            objectManager;
  private final ClientLockManager              lockManager;
  private final NonPortableEventContextFactory appEventContextFactory;

  private final ClientIDProvider               cidProvider;

  private final SampledCounter                 txCounter;

  private final boolean                        sendErrors      = System.getProperty("project.name") != null;

  public ClientTransactionManagerImpl(final ClientIDProvider cidProvider, final ClientObjectManager objectManager,
                                      final ClientTransactionFactory txFactory, final ClientLockManager lockManager,
                                      final RemoteTransactionManager remoteTxManager,
                                      final RuntimeLogger runtimeLogger,
                                      SampledCounter txCounter) {
    this.cidProvider = cidProvider;
    this.txFactory = txFactory;
    this.lockManager = lockManager;
    this.remoteTxManager = remoteTxManager;
    this.objectManager = objectManager;
    this.objectManager.setTransactionManager(this);
    this.txCounter = txCounter;
    this.appEventContextFactory = new NonPortableEventContextFactory(cidProvider);
  }

  public void begin(final LockID lock, final LockLevel lockLevel) {
    logBegin0(lock, lockLevel);

    if (isTransactionLoggingDisabled() || this.objectManager.isCreationInProgress()) { return; }

    TxnType transactionType = getTxnTypeFromLockLevel(lockLevel);
    if (transactionType == null) {
      return;
    }
    
    TransactionContext context = getThreadTransactionContext().peekContext(lock);
    if ((context != null) && context.getLockType().isConcurrent()) {
      // NOTE: when removing this restriction, there are other places to clean up:
      // 1) ClientTransactionManagerImpl.apply()
      // 2) DNAFactory.flushDNAFor(LockID)
      // 3) RemoteTransactionManagerImpl.commit()
      // 4) ClientLock.removeCurrent()
      throw new UnsupportedOperationException("Don't currently support nested concurrent write transactions");
    }
    
    ClientTransaction currentTransaction = getTransactionOrNull();

    
    pushTxContext(currentTransaction, lock, transactionType);

    if (currentTransaction == null) {
      createTxAndInitContext();
    } else {
      currentTransaction.setTransactionContext(this.peekContext());
    }
  }

  private TxnType getTxnTypeFromLockLevel(final LockLevel lockLevel) {
    switch (lockLevel) {
      case CONCURRENT:
        return TxnType.CONCURRENT;
      case WRITE:
      case SYNCHRONOUS_WRITE:
        return TxnType.NORMAL;
      default:
        return null;
    }
  }

  public void notify(Notify notify) throws UnlockedSharedObjectException {
    final ClientTransaction currentTxn = getTransactionOrNull();

    if (currentTxn == null || currentTxn.getEffectiveType() != TxnType.NORMAL) { throw new IllegalMonitorStateException(getIllegalMonitorStateExceptionMessage()); }

    currentTxn.addNotify(notify);
  }

  private String getIllegalMonitorStateExceptionMessage() {
    StringBuffer errorMsg = new StringBuffer(
                                             "An IllegalMonitorStateException is usually caused by one of the following:");
    errorMsg.append(StringUtil.LINE_SEPARATOR);
    errorMsg.append("1) No synchronization");
    errorMsg.append(StringUtil.LINE_SEPARATOR);
    errorMsg.append("2) The object synchronized is not the same as the object waited/notified");
    errorMsg.append(StringUtil.LINE_SEPARATOR);
    errorMsg
        .append("3) The object being waited/notified on is a Terracotta distributed object, but no Terracotta auto-lock has been specified.");
    errorMsg.append(StringUtil.LINE_SEPARATOR);
    errorMsg.append("4) Read-level or named locks are being used where write-level locks or autolocks are necessary.");
    errorMsg.append(StringUtil.LINE_SEPARATOR);
    errorMsg.append("5) A lock has been specified but was applied to an object before that object was shared.");
    errorMsg.append(StringUtil.LINE_SEPARATOR).append(StringUtil.LINE_SEPARATOR);
    errorMsg.append("For more information on this issue, please visit our Troubleshooting Guide at:");
    errorMsg.append(StringUtil.LINE_SEPARATOR);
    errorMsg.append("http://terracotta.org/kit/troubleshooting");

    return Util.getFormattedMessage(errorMsg.toString());
  }

  private void logBegin0(final LockID lock, final LockLevel level) {
    if (logger.isDebugEnabled()) {
      logger.debug("begin(): lock =" + lock + ", level = " + level);
    }
  }

  private ClientTransaction getTransactionOrNull() {
    ThreadTransactionContext tx = getThreadTransactionContext();
    return tx.getCurrentTransaction();
  }

  private ThreadTransactionContext getThreadTransactionContext() {
    return (ThreadTransactionContext) this.transaction.get();
  }

  private ClientTransaction getTransaction() throws UnlockedSharedObjectException {
    return getTransaction(null);
  }

  private ClientTransaction getTransaction(final Object context) throws UnlockedSharedObjectException {
    ClientTransaction tx = getTransactionOrNull();
    if (tx == null) {

      String type = context == null ? null : context.getClass().getName();
      String errorMsg = "Attempt to access a shared object outside the scope of a shared lock.\n"
                        + "All access to shared objects must be within the scope of one or more\n"
                        + "shared locks defined in your Terracotta configuration.";
      String details = "";
      if (type != null) {
        details += "Shared Object Type: " + type;
      }
      details += "\n\nThe cause may be one or more of the following:\n"
                 + " * Terracotta locking was not configured for the shared code.\n"
                 + " * The code itself does not have synchronization that Terracotta\n" + "   can use as a boundary.\n"
                 + " * The class doing the locking must be included for instrumentation.\n"
                 + " * The object was first locked, then shared.\n\n"
                 + "For more information on how to solve this issue, see:\n" + "http://www.terracotta.org/usoe";

      throw new UnlockedSharedObjectException(errorMsg, Thread.currentThread().getName(), this.cidProvider
          .getClientID().toLong(), details);
    }
    return tx;
  }

  public void checkWriteAccess(final Object context) {
    if (isTransactionLoggingDisabled()) { return; }

    try {
      // First check if we have any TXN context at all (else exception thrown)
      getTransaction(context);
    } catch (UnlockedSharedObjectException usoe) {
      throw checkAndReportUnlockedSharedObjectException(usoe, null, context);
    }
  }

  /**
   * In order to support ReentrantLock, the TransactionContext that is going to be removed when doing a commit may not
   * always be at the top of a stack because an reentrant lock could issue a lock within a synchronized block (although
   * it is highly not recommended). Therefore, when a commit is issued, we need to search back the stack from the top of
   * the stack to find the appropriate TransactionContext to be removed. Most likely, the TransactionContext to be
   * removed will be on the top of the stack. Therefore, the performance should be make must difference. Only in some
   * weird situations where reentrantLock is mixed with synchronized block will the TransactionContext to be removed be
   * found otherwise.
   */
  public void commit(LockID lock, LockLevel level) throws UnlockedSharedObjectException {
    logCommit0();
    if (isTransactionLoggingDisabled() || this.objectManager.isCreationInProgress()) { return; }

    TxnType transactionType = getTxnTypeFromLockLevel(level);
    if (transactionType == null) {
      return;
    }
    
    ClientTransaction tx = getTransaction();
    boolean hasCommitted = commit(lock, tx, false);

    popTransaction(lock);

    if (peekContext() != null) {
      if (hasCommitted) {
        createTxAndInitContext();
      } else {
        // If the current transaction has not committed, we will reuse the current transaction
        // so that the current changes will have a chance to commit at the next commit point.
        tx.setTransactionContext(peekContext());
        setTransaction(tx);
      }
    }
  }

  private void createTxAndInitContext() {
    ClientTransaction ctx = this.txFactory.newInstance();
    ctx.setTransactionContext(peekContext());
    setTransaction(ctx);
  }

  private ClientTransaction popTransaction(final LockID lockID) {
    ThreadTransactionContext ttc = getThreadTransactionContext();
    return ttc.popCurrentTransaction(lockID);
  }

  private TransactionContext peekContext() {
    ThreadTransactionContext ttc = getThreadTransactionContext();
    return ttc.peekContext();
  }

  public boolean isLockOnTopStack(final LockID lock) {
    TransactionContext tc = peekContext();
    if (tc == null) { return false; }
    return (tc.getLockID().equals(lock));
  }

  private void pushTxContext(final ClientTransaction currentTransaction, final LockID lockID, final TxnType type) {
    ThreadTransactionContext ttc = getThreadTransactionContext();
    ttc.pushContext(lockID, type, type);
  }

  private void logCommit0() {
    if (logger.isDebugEnabled()) {
      logger.debug("commit()");
    }
  }

  private boolean commit(final LockID lockID, final ClientTransaction currentTransaction, final boolean isWaitContext) {
    try {
      return commitInternal(lockID, currentTransaction, isWaitContext);
    } catch (Throwable t) {
      this.remoteTxManager.stopProcessing();
      Banner.errorBanner("Terracotta client shutting down due to error " + t);
      logger.error(t);
      if (t instanceof Error) { throw (Error) t; }
      if (t instanceof RuntimeException) { throw (RuntimeException) t; }
      throw new RuntimeException(t);
    }
  }

  private boolean commitInternal(final LockID lockID, final ClientTransaction currentTransaction,
                                 final boolean isWaitContext) {
    Assert.assertNotNull("transaction", currentTransaction);

    try {
      disableTransactionLogging();

      boolean hasPendingCreateObjects = this.objectManager.hasPendingCreateObjects();
      if (hasPendingCreateObjects) {
        this.objectManager.addPendingCreateObjectsToTransaction();
      }

      currentTransaction.setAlreadyCommitted();

      if (currentTransaction.hasChangesOrNotifies() || hasPendingCreateObjects) {
        this.txCounter.increment();
        this.remoteTxManager.commit(currentTransaction);
      }
      return true;
    } finally {
      enableTransactionLogging();
    }
  }

  private void basicApply(final Collection objectChanges, final Map newRoots, final boolean force) throws DNAException {
    List l = new LinkedList();

    for (Iterator i = objectChanges.iterator(); i.hasNext();) {
      DNA dna = (DNA) i.next();
      TCObject tcobj = null;
      Assert.assertTrue(dna.isDelta());

      try {
        tcobj = this.objectManager.lookup(dna.getObjectID());
      } catch (ClassNotFoundException cnfe) {
        logger.warn("Could not apply change because class not local: " + dna.getTypeName());
        continue;
      }
      // Important to have a hard reference to the object while we apply
      // changes so that it doesn't get gc'd on us
      Object obj = tcobj == null ? null : tcobj.getPeerObject();
      l.add(obj);
      if (obj != null) {
        try {
          tcobj.hydrate(dna, force);
        } catch (ClassNotFoundException cnfe) {
          logger.warn("Could not apply change because class not local: " + cnfe.getMessage());
          throw new TCClassNotFoundException(cnfe);
        }
      }
    }

    for (Iterator i = newRoots.entrySet().iterator(); i.hasNext();) {
      Entry entry = (Entry) i.next();
      String rootName = (String) entry.getKey();
      ObjectID newRootID = (ObjectID) entry.getValue();
      this.objectManager.replaceRootIDIfNecessary(rootName, newRootID);
    }
  }

  public void receivedAcknowledgement(final SessionID sessionID, final TransactionID transactionID, final NodeID nodeID) {
    this.remoteTxManager.receivedAcknowledgement(sessionID, transactionID, nodeID);
  }

  public void receivedBatchAcknowledgement(final TxnBatchID batchID, final NodeID nodeID) {
    this.remoteTxManager.receivedBatchAcknowledgement(batchID, nodeID);
  }

  public void apply(final TxnType txType, final List lockIDs, final Collection objectChanges, final Map newRoots) {
    try {
      disableTransactionLogging();
      basicApply(objectChanges, newRoots, false);
    } finally {
      enableTransactionLogging();
    }
  }

  public void literalValueChanged(final TCObject source, final Object newValue, final Object oldValue) {
    if (isTransactionLoggingDisabled()) { return; }

    try {
      disableTransactionLogging();

      Object pojo = source.getPeerObject();
      ClientTransaction tx;

      try {
        tx = getTransaction(pojo);
      } catch (UnlockedSharedObjectException usoe) {
        throw checkAndReportUnlockedSharedObjectException(usoe, "Failed To Change Value in:  " + newValue.getClass().getName(), pojo);
      }

      tx.literalValueChanged(source, newValue, oldValue);

    } finally {
      enableTransactionLogging();
    }

  }

  public void fieldChanged(final TCObject source, final String classname, final String fieldname,
                           final Object newValue, final int index) {
    if (isTransactionLoggingDisabled()) { return; }

    try {
      disableTransactionLogging();

      Object pojo = source.getPeerObject();

      ClientTransaction tx;
      try {
        tx = getTransaction(pojo);
      } catch (UnlockedSharedObjectException usoe) {
        throw checkAndReportUnlockedSharedObjectException(usoe, "Failed To Modify Field:  " + fieldname + " in " + classname, pojo, classname, fieldname);
      }

      logFieldChanged0(source, classname, fieldname, newValue, tx);

      if (newValue != null && LiteralValues.isLiteralInstance(newValue)) {
        tx.fieldChanged(source, classname, fieldname, newValue, index);
      } else {
        if (newValue != null) {
          this.objectManager.checkPortabilityOfField(newValue, fieldname, pojo);
        }

        TCObject tco = this.objectManager.lookupOrCreate(newValue);

        tx.fieldChanged(source, classname, fieldname, tco.getObjectID(), index);

        // record the reference in this transaction -- This is to solve the race condition of transactions
        // that reference objects newly "created" in other transactions that may not commit before us
        if (newValue != null) {
          tx.createObject(tco);
        }
      }
    } finally {
      enableTransactionLogging();
    }
  }

  public void arrayChanged(final TCObject source, final int startPos, final Object array, final int length) {
    if (isTransactionLoggingDisabled()) { return; }
    try {
      disableTransactionLogging();
      Object pojo = source.getPeerObject();
      ClientTransaction tx;

      try {
        tx = getTransaction(pojo);
      } catch (UnlockedSharedObjectException usoe) {
        throw checkAndReportUnlockedSharedObjectException(usoe, "Failed To Modify Array: " + pojo.getClass().getName(), pojo);        
      }

      if (!ClassUtils.isPrimitiveArray(array)) {
        Object[] objArray = (Object[]) array;
        for (int i = 0; i < length; i++) {

          Object element = objArray[i];
          if (!LiteralValues.isLiteralInstance(element)) {
            if (element != null) {
              this.objectManager.checkPortabilityOfField(element, String.valueOf(i), pojo);
            }

            TCObject tco = this.objectManager.lookupOrCreate(element);
            objArray[i] = tco.getObjectID();
            // record the reference in this transaction -- This is to solve the race condition of transactions
            // that reference objects newly "created" in other transactions that may not commit before us
            if (element != null) {
              tx.createObject(tco);
            }
          }
        }
      }

      tx.arrayChanged(source, startPos, array, length);

    } finally {
      enableTransactionLogging();
    }
  }

  private void logFieldChanged0(final TCObject source, final String classname, final String fieldname,
                                final Object newValue, final ClientTransaction tx) {
    if (logger.isDebugEnabled()) {
      logger.debug("fieldChanged(source=" + source + ", classname=" + classname + ", fieldname=" + fieldname
                   + ", newValue=" + newValue + ", tx=" + tx);
    }
  }

  public void logicalInvoke(final TCObject source, final int method, final String methodName, final Object[] parameters) {
    if (isTransactionLoggingDisabled()) { return; }

    try {
      disableTransactionLogging();

      Object pojo = source.getPeerObject();
      ClientTransaction tx;

      try {
        tx = getTransaction(pojo);
      } catch (UnlockedSharedObjectException usoe) {
        throw checkAndReportUnlockedSharedObjectException(usoe, "Failed Method Call: " + methodName, pojo, methodName, parameters);
      }

      for (int i = 0; i < parameters.length; i++) {
        Object p = parameters[i];
        boolean isLiteral = LiteralValues.isLiteralInstance(p);
        if (!isLiteral) {
          if (p != null) {
            this.objectManager.checkPortabilityOfLogicalAction(parameters, i, methodName, pojo);
          }

          TCObject tco = this.objectManager.lookupOrCreate(p);
          tco.markAccessed();
          parameters[i] = tco.getObjectID();
          if (p != null) {
            // record the reference in this transaction -- This is to solve the race condition of transactions
            // that reference objects newly "created" in other transactions that may not commit before us
            tx.createObject(tco);
          }
        }
      }

      tx.logicalInvoke(source, method, parameters, methodName);
    } finally {
      enableTransactionLogging();
    }
  }

  private RuntimeException checkAndReportUnlockedSharedObjectException(UnlockedSharedObjectException usoe, String details, Object context) {
    if (lockManager.isLockedByCurrentThread(LockLevel.READ)) {
      ReadOnlyException roe = makeReadOnlyException(details);
      if (sendErrors) {
        ReadOnlyObjectEventContext eventContext = appEventContextFactory.createReadOnlyObjectEventContext(context, roe);
        objectManager.sendApplicationEvent(context, new ReadOnlyObjectEvent(eventContext));
      }
      return roe;
    } else {
      if (sendErrors) {
        UnlockedSharedObjectEventContext eventContext = appEventContextFactory.createUnlockedSharedObjectEventContext(context, usoe);
        objectManager.sendApplicationEvent(context, new UnlockedSharedObjectEvent(eventContext));
      }
      return usoe;
    }
  }
  
  private RuntimeException checkAndReportUnlockedSharedObjectException(UnlockedSharedObjectException usoe, String details, Object context, String classname, String fieldname) {
    if (lockManager.isLockedByCurrentThread(LockLevel.READ)) {
      ReadOnlyException roe = makeReadOnlyException(details);
      if (sendErrors) {
        ReadOnlyObjectEventContext eventContext = appEventContextFactory.createReadOnlyObjectEventContext(context, classname, fieldname, roe);
        objectManager.sendApplicationEvent(context, new ReadOnlyObjectEvent(eventContext));
      }
      return roe;
    } else {
      if (sendErrors) {
        UnlockedSharedObjectEventContext eventContext = appEventContextFactory.createUnlockedSharedObjectEventContext(context, classname, fieldname, usoe);
        objectManager.sendApplicationEvent(context, new UnlockedSharedObjectEvent(eventContext));
      }
      return usoe;
    }
  }

  private RuntimeException checkAndReportUnlockedSharedObjectException(UnlockedSharedObjectException usoe, String details, Object context, String methodName, Object[] parameters) {
    if (lockManager.isLockedByCurrentThread(LockLevel.READ)) {
      ReadOnlyException roe = makeReadOnlyException(details);
      if (sendErrors) {
        ReadOnlyObjectEventContext eventContext = appEventContextFactory.createReadOnlyObjectEventContext(context, roe);
        context = objectManager.cloneAndInvokeLogicalOperation(context, methodName, parameters);
        objectManager.sendApplicationEvent(context, new ReadOnlyObjectEvent(eventContext));
      }
      return roe;
    } else {
      if (sendErrors) {
        UnlockedSharedObjectEventContext eventContext = appEventContextFactory.createUnlockedSharedObjectEventContext(context, usoe);
        context = objectManager.cloneAndInvokeLogicalOperation(context, methodName, parameters);
        objectManager.sendApplicationEvent(context, new UnlockedSharedObjectEvent(eventContext));
      }
      return usoe;
    }
  }
  
  private ReadOnlyException makeReadOnlyException(final String details) {
    long vmId = this.cidProvider.getClientID().toLong();

    final ReadOnlyException roe;

    if (details != null) {
      roe = new ReadOnlyException(READ_ONLY_TEXT, Thread.currentThread().getName(), vmId, details);
    } else {
      roe = new ReadOnlyException(READ_ONLY_TEXT, Thread.currentThread().getName(), vmId);
    }
    System.err.println(roe.getMessage());
    return roe;
  }

  private void setTransaction(final ClientTransaction tx) {
    getThreadTransactionContext().setCurrentTransaction(tx);
  }

  public void createObject(final TCObject source) {
    getTransaction().createObject(source);
  }

  public void createRoot(final String name, final ObjectID rootID) {
    getTransaction().createRoot(name, rootID);
  }

  public ClientTransaction getCurrentTransaction() {
    return getTransactionOrNull();
  }

  public void addReference(final TCObject tco) {
    ClientTransaction txn = getTransactionOrNull();
    if (txn != null) {
      txn.createObject(tco);
    }
  }

  public ClientIDProvider getClientIDProvider() {
    return this.cidProvider;
  }

  public void disableTransactionLogging() {
    ThreadTransactionLoggingStack txnStack = (ThreadTransactionLoggingStack) this.txnLogging.get();
    if (txnStack == null) {
      txnStack = new ThreadTransactionLoggingStack();
      this.txnLogging.set(txnStack);
    }
    txnStack.increment();
  }

  public void enableTransactionLogging() {
    ThreadTransactionLoggingStack txnStack = (ThreadTransactionLoggingStack) this.txnLogging.get();
    Assert.assertNotNull(txnStack);
    final int size = txnStack.decrement();

    if (size < 0) { throw new AssertionError("size=" + size); }
  }

  public boolean isTransactionLoggingDisabled() {
    Object txnStack = this.txnLogging.get();
    return (txnStack != null) && (((ThreadTransactionLoggingStack) txnStack).get() > 0);
  }
  
  public boolean isObjectCreationInProgress() {
    return objectManager.isCreationInProgress();
  }

  public static class ThreadTransactionLoggingStack {
    int callCount = 0;

    public int increment() {
      return ++this.callCount;
    }

    public int decrement() {
      return --this.callCount;
    }

    public int get() {
      return this.callCount;
    }
  }

  public void addDmiDescriptor(final DmiDescriptor dd) {
    getTransaction().addDmiDescritor(dd);
  }

  public String dump() {
    StringWriter writer = new StringWriter();
    PrintWriter pw = new PrintWriter(writer);
    new PrettyPrinterImpl(pw).visit(this);
    writer.flush();
    return writer.toString();
  }

  public void dumpToLogger() {
    logger.info(dump());
  }

  public synchronized PrettyPrinter prettyPrint(final PrettyPrinter out) {

    out.println(getClass().getName());
    return out;

  }

  private static final String READ_ONLY_TEXT = "Attempt to write to a shared object inside the scope of a lock declared as a"
                                               + "\nread lock. All writes to shared objects must be within the scope of one or"
                                               + "\nmore shared locks with write access defined in your Terracotta configuration."
                                               + "\n\nPlease alter the locks section of your Terracotta configuration so that this"
                                               + "\naccess is auto-locked or protected by a named lock with write access."
                                               + "\n\nFor more information on this issue, please visit our Troubleshooting Guide at:"
                                               + "\nhttp://terracotta.org/kit/troubleshooting ";

}
