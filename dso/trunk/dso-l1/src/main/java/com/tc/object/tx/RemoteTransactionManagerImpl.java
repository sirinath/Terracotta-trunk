/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.tx;

import com.tc.abortable.AbortableOperationManager;
import com.tc.abortable.AbortedOperationException;
import com.tc.exception.PlatformRejoinException;
import com.tc.exception.TCNotRunningException;
import com.tc.logging.LossyTCLogger;
import com.tc.logging.LossyTCLogger.LossyTCLoggerType;
import com.tc.logging.TCLogger;
import com.tc.net.GroupID;
import com.tc.net.NodeID;
import com.tc.object.locks.LockFlushCallback;
import com.tc.object.locks.LockID;
import com.tc.object.msg.ClientHandshakeMessage;
import com.tc.object.msg.CompletedTransactionLowWaterMarkMessage;
import com.tc.object.net.DSOClientMessageChannel;
import com.tc.object.session.SessionID;
import com.tc.object.session.SessionManager;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.stats.counter.Counter;
import com.tc.stats.counter.sampled.derived.SampledRateCounter;
import com.tc.text.PrettyPrintable;
import com.tc.text.PrettyPrinter;
import com.tc.util.Assert;
import com.tc.util.SequenceID;
import com.tc.util.State;
import com.tc.util.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Sends off committed transactions
 */
public class RemoteTransactionManagerImpl implements RemoteTransactionManager, PrettyPrintable {

  private static final long                              FLUSH_WAIT_INTERVAL         = 15 * 1000;

  private static final int                               MAX_OUTSTANDING_BATCHES     = TCPropertiesImpl
                                                                                         .getProperties()
                                                                                         .getInt(TCPropertiesConsts.L1_TRANSACTIONMANAGER_MAXOUTSTANDING_BATCHSIZE);
  private static final long                              COMPLETED_ACK_FLUSH_TIMEOUT = TCPropertiesImpl
                                                                                         .getProperties()
                                                                                         .getLong(TCPropertiesConsts.L1_TRANSACTIONMANAGER_COMPLETED_ACK_FLUSH_TIMEOUT);

  private static final State                             RUNNING                     = new State("RUNNING");
  private static final State                             PAUSED                      = new State("PAUSED");
  private static final State                             STARTING                    = new State("STARTING");
  private static final State                             REJOIN_IN_PROGRESS          = new State("REJOIN_IN_PROGRESS");
  private static final State                             STOP_INITIATED              = new State("STOP-INITIATED");
  private static final State                             STOPPED                     = new State("STOPPED");

  private final Object                                   lock                        = new Object();
  private final Map                                      incompleteBatches           = new HashMap();
  private final HashMap<LockID, List<LockFlushCallback>> lockFlushCallbacks          = new HashMap<LockID, List<LockFlushCallback>>();

  private final Counter                                  outstandingBatchesCounter;
  private TransactionBatchAccounting                     batchAccounting             = new TransactionBatchAccounting();
  private final LockAccounting                           lockAccounting;
  private final TCLogger                                 logger;
  private final long                                     ackOnExitTimeout;

  private int                                            outStandingBatches          = 0;
  private State                                          status;
  private final SessionManager                           sessionManager;
  private final TransactionSequencer                     sequencer;
  private final DSOClientMessageChannel                  channel;
  private final Timer                                    timer                       = new Timer(
                                                                                                 "RemoteTransactionManager Flusher",
                                                                                                 true);
  private final RemoteTransactionManagerTimerTask        remoteTxManagerTimerTask;

  private final GroupID                                  groupID;
  private volatile boolean                               isShutdown                  = false;
  private final AbortableOperationManager                abortableOperationManager;

  public RemoteTransactionManagerImpl(final GroupID groupID, final TCLogger logger,
                                      final TransactionBatchFactory batchFactory,
                                      final TransactionIDGenerator transactionIDGenerator,
                                      final SessionManager sessionManager, final DSOClientMessageChannel channel,
                                      final Counter outstandingBatchesCounter, final Counter pendingBatchesSize,
                                      final SampledRateCounter transactionSizeCounter,
                                      final SampledRateCounter transactionsPerBatchCounter,
                                      final long ackOnExitTimeoutMs, AbortableOperationManager abortableOperationManager) {
    this.groupID = groupID;
    this.logger = logger;
    this.sessionManager = sessionManager;
    this.channel = channel;
    this.status = RUNNING;
    this.ackOnExitTimeout = ackOnExitTimeoutMs;
    this.lockAccounting = new LockAccounting(abortableOperationManager);
    this.sequencer = new TransactionSequencer(groupID, transactionIDGenerator, batchFactory, this.lockAccounting,
                                              pendingBatchesSize, transactionSizeCounter, transactionsPerBatchCounter,
                                              abortableOperationManager);
    this.remoteTxManagerTimerTask = new RemoteTransactionManagerTimerTask();
    this.timer.schedule(this.remoteTxManagerTimerTask, COMPLETED_ACK_FLUSH_TIMEOUT, COMPLETED_ACK_FLUSH_TIMEOUT);
    this.outstandingBatchesCounter = outstandingBatchesCounter;
    this.abortableOperationManager = abortableOperationManager;
  }

  @Override
  public void cleanup() {
    synchronized (this.lock) {
      checkAndSetstate();
      incompleteBatches.clear();
      lockFlushCallbacks.clear();
      outStandingBatches = 0;
      outstandingBatchesCounter.setValue(0);
      batchAccounting = new TransactionBatchAccounting();
      lockAccounting.cleanup();
      sequencer.cleanup();
    }
  }

  private void checkAndSetstate() {
    throwExceptionIfNecessary(false);
    status = REJOIN_IN_PROGRESS;
    this.lock.notifyAll();
  }

  private void throwExceptionIfNecessary(boolean throwExp) {
    String message = "cleanup unexpected state: expexted " + PAUSED + " but found " + status;
    if (throwExp) {
      if (status != PAUSED) { throw new IllegalStateException(message); }
    } else {
      logger.info(message);
    }
  }

  @Override
  public void shutdown() {
    this.lockAccounting.shutdown();
    this.isShutdown = true;
    this.timer.cancel();
    synchronized (lock) {
      this.lock.notifyAll();
    }
  }

  @Override
  public void pause(final NodeID remote, final int disconnected) {
    if (this.isShutdown) { return; }
    synchronized (this.lock) {
      this.remoteTxManagerTimerTask.reset();
      if (isStoppingOrStopped()) { return; }
      if (this.status == PAUSED) { throw new AssertionError("Attempt to pause while already paused state."); }
      this.status = PAUSED;
    }
  }

  @Override
  public void unpause(final NodeID remote, final int disconnected) {
    if (this.isShutdown) { return; }
    synchronized (this.lock) {
      if (isStoppingOrStopped()) { return; }
      if (this.status == RUNNING) { throw new AssertionError("Attempt to unpause while in running state."); }
      resendOutstanding();
      this.status = RUNNING;
      this.lock.notifyAll();
    }
  }

  @Override
  public void initializeHandshake(final NodeID thisNode, final NodeID remoteNode,
                                  final ClientHandshakeMessage handshakeMessage) {
    if (this.isShutdown) { return; }
    synchronized (this.lock) {
      State current = this.status;
      if (!(current == PAUSED || current == REJOIN_IN_PROGRESS)) { throw new AssertionError(
                                                                                            "At from "
                                                                                                + remoteNode
                                                                                                + " to "
                                                                                                + thisNode
                                                                                                + " . "
                                                                                                + "Attempting to handshake while "
                                                                                                + current); }
      this.status = STARTING;
      handshakeMessage.addTransactionSequenceIDs(getTransactionSequenceIDs());
      handshakeMessage.addResentTransactionIDs(getResentTransactionIDs());
    }
  }

  /**
   * This is for testing only.
   */
  void clear() {
    synchronized (this.lock) {
      this.sequencer.clear();
      this.incompleteBatches.clear();
    }
  }

  /**
   * This is for testing only.
   */
  public int getMaxOutStandingBatches() {
    return MAX_OUTSTANDING_BATCHES;
  }

  @Override
  public void stopProcessing() {
    this.sequencer.shutdown();
    this.channel.close();
  }

  @Override
  public void stop() {
    final long start = System.currentTimeMillis();
    this.logger.debug("stop() is called on " + System.identityHashCode(this));
    synchronized (this.lock) {
      this.status = STOP_INITIATED;

      sendBatches(true, "stop()");

      final long pollInteval = (this.ackOnExitTimeout > 0) ? (this.ackOnExitTimeout / 10) : (30 * 1000);
      final long t0 = System.currentTimeMillis();
      if (this.incompleteBatches.size() != 0) {
        try {
          int incompleteBatchesCount = 0;
          final LossyTCLogger lossyLogger = new LossyTCLogger(this.logger, 5, LossyTCLoggerType.COUNT_BASED);
          while ((this.status != STOPPED)
                 && ((this.ackOnExitTimeout <= 0) || (t0 + this.ackOnExitTimeout) > System.currentTimeMillis())) {
            if (incompleteBatchesCount != this.incompleteBatches.size()) {
              lossyLogger.info("stop(): incompleteBatches.size() = "
                               + (incompleteBatchesCount = this.incompleteBatches.size()));
            }
            this.lock.wait(pollInteval);
          }
        } catch (final InterruptedException e) {
          this.logger.warn("stop(): Interrupted " + e);
          Thread.currentThread().interrupt();
        }
        if (this.status != STOPPED) {
          this.logger.error("stop() : There are still UNACKed Transactions! incompleteBatches.size() = "
                            + this.incompleteBatches.size());
        }
      }
      this.status = STOPPED;
    }
    this.logger.info("stop(): took " + (System.currentTimeMillis() - start) + " millis to complete");
  }

  @Override
  public void flush(final LockID lockID) throws AbortedOperationException {
    final long start = System.currentTimeMillis();
    long lastPrinted = 0;
    boolean isInterrupted = false;
    try {
      Collection c;
      synchronized (this.lock) {
        while ((!(c = this.lockAccounting.getTransactionsFor(lockID)).isEmpty())) {
          try {
            this.lock.wait(FLUSH_WAIT_INTERVAL);
            final long now = System.currentTimeMillis();
            if ((now - start) > FLUSH_WAIT_INTERVAL && (now - lastPrinted) > FLUSH_WAIT_INTERVAL / 3) {
              this.logger.info("Flush for " + lockID + " took longer than: " + (FLUSH_WAIT_INTERVAL / 1000)
                               + " sec. Took : " + (now - start) + " ms. # Transactions not yet Acked = "
                               + (c.size() + (c.size() < 50 ? (". " + c) : "")) + "\n");
              lastPrinted = now;
            }
          } catch (final InterruptedException e) {
            handleInterruptedException();
            isInterrupted = true;
          }
        }
      }
    } finally {
      Util.selfInterruptIfNeeded(isInterrupted);
    }
  }

  @Override
  public void waitForServerToReceiveTxnsForThisLock(final LockID lockId) throws AbortedOperationException {
    // wait for transactions to get acked here from the server
    final long start = System.currentTimeMillis();
    long lastPrinted = 0;
    boolean isInterrupted = false;
    try {
      synchronized (this.lock) {
        while (!this.lockAccounting.areTransactionsReceivedForThisLockID(lockId)) {
          try {
            this.lock.wait(FLUSH_WAIT_INTERVAL);
            final long now = System.currentTimeMillis();
            if ((now - start) > FLUSH_WAIT_INTERVAL && (now - lastPrinted) > FLUSH_WAIT_INTERVAL / 3) {
              this.logger.info("Sync Write for " + lockId + " took longer than: " + (FLUSH_WAIT_INTERVAL / 1000)
                               + " sec. Took : " + (now - start) + " ms.\n");
              lastPrinted = now;
            }
          } catch (final InterruptedException e) {
            handleInterruptedException();
            isInterrupted = true;
          }
        }
      }
    } finally {
      Util.selfInterruptIfNeeded(isInterrupted);
    }
  }

  /**
   * This method will be called when the server receives a batch. This should ideally be called only when a batch
   * contains a sync write transaction.
   */
  @Override
  public void batchReceived(final TxnBatchID batchId, final Set<TransactionID> syncTxnSet, final NodeID nid) {
    // This batch id was received by the server
    // so notify the locks waiting for this transaction
    this.lockAccounting.transactionRecvdByServer(syncTxnSet);

    synchronized (this.lock) {
      this.lock.notifyAll();
    }
  }

  /* This does not block unlike flush() */
  @Override
  public boolean asyncFlush(final LockID lockID, final LockFlushCallback callback) {
    synchronized (this.lock) {

      if ((this.lockAccounting.getTransactionsFor(lockID)).isEmpty()) {
        // All transactions are flushed !
        return true;
      } else {
        // register for call back
        if (callback != null) {
          List<LockFlushCallback> lockFlushCallbacksList = this.lockFlushCallbacks.get(lockID);
          if (lockFlushCallbacksList == null) {
            lockFlushCallbacksList = new ArrayList<LockFlushCallback>();
            this.lockFlushCallbacks.put(lockID, lockFlushCallbacksList);
          }
          lockFlushCallbacksList.add(callback);
        }
        return false;
      }
    }
  }

  @Override
  public void commit(final ClientTransaction txn) throws AbortedOperationException {
    throttleIfNecessary();
    commitWithoutThrottling(txn);
  }

  void throttleIfNecessary() throws AbortedOperationException {
    this.sequencer.throttleIfNecesary();
  }

  void commitWithoutThrottling(final ClientTransaction txn) {
    if (!txn.hasChangesOrNotifies() && txn.getDmiDescriptors().isEmpty() && txn.getNewRoots().isEmpty()) {
      //
      throw new AssertionError("Attempt to commit an empty transaction.");
    }
    if (!txn.getTransactionID().isNull()) { throw new AssertionError(
                                                                     "Transaction already committed as TransactionID is already assigned"); }
    final long start = System.currentTimeMillis();

    this.sequencer.addTransaction(txn);

    final long diff = System.currentTimeMillis() - start;
    if (diff > 1000) {
      this.logger.info(txn.getTransactionID() + " : Took more than 1000ms to add to sequencer  : " + diff + " ms");
    }

    synchronized (this.lock) {
      if (isStoppingOrStopped()) {
        // Send now if stop is requested
        sendBatches(true, "commit() : Stop initiated.");
      } else {
        try {
          waitUntilRunningAbortable();
        } catch (AbortedOperationException e) {
          logger
              .debug("Ignoring Aborted Operation Exception since the transaction is already written to the sequencer.");
          return;
        }
        sendBatches(false);
      }
    }
  }

  private void sendBatches(final boolean ignoreMax) {
    sendBatches(ignoreMax, null);
  }

  private void sendBatches(final boolean ignoreMax, final String message) {
    ClientTransactionBatch batch;
    while ((ignoreMax || canSendBatch()) && (batch = this.sequencer.getNextBatch()) != null) {
      if (message != null) {
        this.logger.debug(message + " : Sending batch containing " + batch.numberOfTxnsBeforeFolding() + " txns");
      }
      sendBatch(batch, true);
    }
  }

  private boolean canSendBatch() {
    return (this.outStandingBatches < MAX_OUTSTANDING_BATCHES);
  }

  void resendOutstanding() {
    synchronized (this.lock) {
      this.logger.debug("resendOutstanding()...");
      this.outStandingBatches = 0;
      this.outstandingBatchesCounter.setValue(0);
      final List toSend = this.batchAccounting.addIncompleteBatchIDsTo(new ArrayList());
      if (toSend.size() == 0) {
        sendBatches(false, " resendOutstanding()");
      } else {
        for (final Iterator i = toSend.iterator(); i.hasNext();) {
          final TxnBatchID id = (TxnBatchID) i.next();
          final ClientTransactionBatch batch = (ClientTransactionBatch) this.incompleteBatches.get(id);
          if (batch == null) { throw new AssertionError("Unknown batch: " + id); }
          this.logger.debug("Resending outstanding batch: " + id + ", "
                            + batch.addTransactionIDsTo(new LinkedHashSet()));
          sendBatch(batch, false);
        }
      }
    }
  }

  List getTransactionSequenceIDs() {
    synchronized (this.lock) {
      final ArrayList sequenceIDs = new ArrayList();
      // Add list of SequenceIDs that are going to be resent
      final List toSend = this.batchAccounting.addIncompleteBatchIDsTo(new ArrayList());
      for (final Iterator i = toSend.iterator(); i.hasNext();) {
        final TxnBatchID id = (TxnBatchID) i.next();
        final ClientTransactionBatch batch = (ClientTransactionBatch) this.incompleteBatches.get(id);
        if (batch == null) { throw new AssertionError("Unknown batch: " + id); }
        batch.addTransactionSequenceIDsTo(sequenceIDs);
      }
      // Add Last next
      final SequenceID currentBatchMinSeq = this.sequencer.getNextSequenceID();
      Assert.assertFalse(SequenceID.NULL_ID.equals(currentBatchMinSeq));
      sequenceIDs.add(currentBatchMinSeq);
      return sequenceIDs;
    }
  }

  List getResentTransactionIDs() {
    synchronized (this.lock) {
      final ArrayList txIDs = new ArrayList();
      // Add list of TransactionIDs that are going to be resent
      final List toSend = this.batchAccounting.addIncompleteBatchIDsTo(new ArrayList());
      for (final Iterator i = toSend.iterator(); i.hasNext();) {
        final TxnBatchID id = (TxnBatchID) i.next();
        final ClientTransactionBatch batch = (ClientTransactionBatch) this.incompleteBatches.get(id);
        if (batch == null) { throw new AssertionError("Unknown batch: " + id); }
        batch.addTransactionIDsTo(txIDs);
      }
      return txIDs;
    }
  }

  private boolean isStoppingOrStopped() {
    return this.status == STOP_INITIATED || this.status == STOPPED;
  }

  private void sendBatch(final ClientTransactionBatch batchToSend, final boolean account) {
    synchronized (this.lock) {
      if (account) {
        if (this.incompleteBatches.put(batchToSend.getTransactionBatchID(), batchToSend) != null) {
          // formatting
          throw new AssertionError("Batch has already been sent!");
        }
        final Collection txnIds = batchToSend.addTransactionIDsTo(new HashSet());
        this.batchAccounting.addBatch(batchToSend.getTransactionBatchID(), txnIds);

      }
      batchToSend.send();
      this.outStandingBatches++;
      this.outstandingBatchesCounter.increment();
    }
  }

  // XXX:: Currently server always sends NULL BatchID
  @Override
  public void receivedBatchAcknowledgement(final TxnBatchID txnBatchID, final NodeID remoteNode) {
    synchronized (this.lock) {
      if (isStoppingOrStopped()) {
        this.logger.warn(this.status + " : Received ACK for batch = " + txnBatchID);
        this.lock.notifyAll();
        return;
      }

      waitUntilRunning();
      this.outStandingBatches--;
      this.outstandingBatchesCounter.decrement();
      sendBatches(false);
      this.lock.notifyAll();
    }
  }

  @Override
  public TransactionBuffer receivedAcknowledgement(final SessionID sessionID, final TransactionID txID,
                                                   final NodeID remoteNode) {
    TransactionBuffer tb = null;
    Map callbacks;
    synchronized (this.lock) {
      // waitUntilRunning();
      if (!this.sessionManager.isCurrentSession(remoteNode, sessionID)) {
        this.logger.warn("Ignoring Transaction ACK for " + txID + " from previous session = " + sessionID);
        return tb;
      }

      final Set completedLocks = this.lockAccounting.acknowledge(txID);

      final TxnBatchID container = this.batchAccounting.getBatchByTransactionID(txID);
      if (!container.isNull()) {
        final ClientTransactionBatch containingBatch = (ClientTransactionBatch) this.incompleteBatches.get(container);
        tb = containingBatch.removeTransaction(txID);
        callBackTxnCompleteListeners(tb.getFoldedTransactionID(), tb.getTransactionCompleteListeners());
        final TxnBatchID completed = this.batchAccounting.acknowledge(txID);
        if (!completed.isNull()) {
          this.incompleteBatches.remove(completed);
          if (this.status == STOP_INITIATED && this.incompleteBatches.size() == 0) {
            this.logger.debug("Received ACK for the last Transaction. Moving to STOPPED state.");
            this.status = STOPPED;
          }
        }
      } else {
        this.logger.fatal("No batch found for acknowledgement: " + txID + " The batch accounting is "
                          + this.batchAccounting);
        throw new AssertionError("No batch found for acknowledgement: " + txID);
      }
      this.lock.notifyAll();
      callbacks = getLockFlushCallbacks(completedLocks);
    }
    fireLockFlushCallbacks(callbacks);
    return tb;
  }

  private void callBackTxnCompleteListeners(TransactionID txnID,
                                            List<TransactionCompleteListener> transactionCompleteListeners) {
    if (transactionCompleteListeners.isEmpty()) return;
    for (TransactionCompleteListener l : transactionCompleteListeners) {
      l.transactionComplete(txnID);
    }
  }

  @Override
  public void waitForAllCurrentTransactionsToComplete() throws AbortedOperationException {
    this.lockAccounting.waitAllCurrentTxnCompleted();
  }

  private TransactionID getCompletedTransactionIDLowWaterMark() {
    synchronized (this.lock) {
      waitUntilRunning();
      return this.batchAccounting.getLowWaterMark();
    }
  }

  /*
   * Never fire callbacks while holding lock
   */
  private void fireLockFlushCallbacks(final Map<LockID, List<LockFlushCallback>> callbacks) {
    if (callbacks.isEmpty()) { return; }
    for (Entry<LockID, List<LockFlushCallback>> element : callbacks.entrySet()) {
      final LockID lid = element.getKey();
      final List<LockFlushCallback> callbacksList = element.getValue();
      for (LockFlushCallback callback : callbacksList) {
        callback.transactionsForLockFlushed(lid);
      }
    }
  }

  private Map<LockID, List<LockFlushCallback>> getLockFlushCallbacks(final Set completedLocks) {
    Map<LockID, List<LockFlushCallback>> callbacks = Collections.EMPTY_MAP;
    if (!completedLocks.isEmpty() && !this.lockFlushCallbacks.isEmpty()) {
      for (final Iterator<LockID> i = completedLocks.iterator(); i.hasNext();) {
        final LockID lid = i.next();
        final List<LockFlushCallback> lockFlushCallbacksList = this.lockFlushCallbacks.remove(lid);
        if (lockFlushCallbacksList != null) {
          if (callbacks == Collections.EMPTY_MAP) {
            callbacks = new HashMap();
          }
          callbacks.put(lid, lockFlushCallbacksList);
        }
      }
    }
    return callbacks;
  }

  /**
   * waits until the Transaction manager is in running state.
   */
  private void waitUntilRunning() {
    boolean isInterrupted = false;
    try {
      while (this.status != RUNNING) {
        if (isShutdown) { throw new TCNotRunningException(); }
        if (this.status == REJOIN_IN_PROGRESS) { throw new PlatformRejoinException(); }
        try {
          this.lock.wait();
        } catch (final InterruptedException e) {
          isInterrupted = true;
        }
      }
    } finally {
      Util.selfInterruptIfNeeded(isInterrupted);
    }
  }

  /**
   * waits until the Transaction manager is in running state.
   * 
   * @throws AbortedOperationException If the Operation is aborted.
   */
  private void waitUntilRunningAbortable() throws AbortedOperationException {
    boolean isInterrupted = false;
    try {
      while (this.status != RUNNING) {
        if (isShutdown) { throw new TCNotRunningException(); }
        if (this.status == REJOIN_IN_PROGRESS) { throw new PlatformRejoinException(); }
        try {
          this.lock.wait();
        } catch (final InterruptedException e) {
          isInterrupted = true;
        }
      }
    } finally {
      Util.selfInterruptIfNeeded(isInterrupted);
    }
  }

  /*
   * For Tests
   */
  TransactionBatchAccounting getBatchAccounting() {
    return this.batchAccounting;
  }

  private class RemoteTransactionManagerTimerTask extends TimerTask {

    private volatile TransactionID currentLWM = TransactionID.NULL_ID;

    @Override
    public void run() {
      synchronized (RemoteTransactionManagerImpl.this.lock) {
        if (status != RUNNING) {
          RemoteTransactionManagerImpl.this.logger.info("Ignoring RemoteTransactionManagerTimerTask because status "
                                                        + status);
          return;
        }
      }
      try {
        final TransactionID lwm = getCompletedTransactionIDLowWaterMark();
        if (lwm.isNull()) { return; }
        if (this.currentLWM.toLong() > lwm.toLong()) { throw new AssertionError(
                                                                                "Transaction Low watermark moved down from "
                                                                                    + this.currentLWM + " to " + lwm); }
        if (this.currentLWM.toLong() == lwm.toLong()) { return; }
        this.currentLWM = lwm;
        final CompletedTransactionLowWaterMarkMessage ctm = RemoteTransactionManagerImpl.this.channel
            .getCompletedTransactionLowWaterMarkMessageFactory()
            .newCompletedTransactionLowWaterMarkMessage(RemoteTransactionManagerImpl.this.groupID);
        ctm.initialize(lwm);
        ctm.send();
      } catch (final TCNotRunningException e) {
        RemoteTransactionManagerImpl.this.logger.info("Ignoring TCNotRunningException while sending Low water mark : ");
        this.cancel();
      } catch (final PlatformRejoinException e) {
        RemoteTransactionManagerImpl.this.logger.info("Ignoring " + e.getClass().getSimpleName()
                                                      + " while sending Low water mark : ");
      } catch (final Exception e) {
        RemoteTransactionManagerImpl.this.logger.error("Error sending Low water mark : ", e);
        throw new AssertionError(e);
      }
    }

    public void reset() {
      this.currentLWM = TransactionID.NULL_ID;
    }
  }

  @Override
  public PrettyPrinter prettyPrint(final PrettyPrinter out) {
    synchronized (this.lock) {
      out.indent().print("incompleteBatches count: ").print(Integer.valueOf(this.incompleteBatches.size())).flush();
      out.indent().print("batchAccounting: ").print(this.batchAccounting).flush();
      out.indent().print("lockAccounting: ").print(this.lockAccounting).flush();
    }
    return out;

  }

  // for testing
  public boolean isShutdown() {
    return this.isShutdown;
  }

  private void handleInterruptedException() throws AbortedOperationException {
    if (abortableOperationManager.isAborted()) {
      throw new AbortedOperationException();
    } else {
      checkIfShutDownOnInterruptedException();
    }
  }

  private void checkIfShutDownOnInterruptedException() {
    // TODO: to be handled during rejoin
  }
}
