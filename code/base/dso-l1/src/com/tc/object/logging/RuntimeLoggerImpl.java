/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.logging;

import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.TCObject;
import com.tc.object.bytecode.ByteCodeUtil;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.lockmanager.api.LockLevel;
import com.tc.object.tx.WaitInvocation;
import com.tc.util.Util;

public class RuntimeLoggerImpl implements RuntimeLogger {
  private static final TCLogger internalLogger = TCLogging.getLogger(RuntimeLoggerImpl.class);

  private final TCLogger        logger;

  private final boolean         lockDebug;
  private final boolean         fieldChangeDebug;
  private final boolean         arrayChangeDebug;
  private final boolean         newManagedObjectDebug;
  private final boolean         distributedMethodDebug;
  private final boolean         nonPortableDump;
  private final boolean         waitNotifyDebug;

  private final boolean         fullStack;
  private final boolean         caller;
  private final boolean         autoLockDetails;

  public RuntimeLoggerImpl(DSOClientConfigHelper configHelper) {
    this.logger = CustomerLogging.getDSORuntimeLogger();

    // runtime logging items
    this.lockDebug = configHelper.runtimeLoggingOptions().logLockDebug().getBoolean();
    this.fieldChangeDebug = configHelper.runtimeLoggingOptions().logFieldChangeDebug().getBoolean();
    this.arrayChangeDebug = fieldChangeDebug;
    this.newManagedObjectDebug = configHelper.runtimeLoggingOptions().logNewObjectDebug().getBoolean();
    this.distributedMethodDebug = configHelper.runtimeLoggingOptions().logDistributedMethodDebug().getBoolean();
    this.nonPortableDump = configHelper.runtimeLoggingOptions().logNonPortableDump().getBoolean();
    this.waitNotifyDebug = configHelper.runtimeLoggingOptions().logWaitNotifyDebug().getBoolean();

    // runtime logging options
    this.caller = configHelper.runtimeOutputOptions().doCaller().getBoolean();
    this.fullStack = configHelper.runtimeOutputOptions().doFullStack().getBoolean();
    this.autoLockDetails = configHelper.runtimeOutputOptions().doAutoLockDetails().getBoolean();
  }

  public boolean lockDebug() {
    return this.lockDebug;
  }

  public boolean fieldChangeDebug() {
    return this.fieldChangeDebug;
  }

  public boolean arrayChangeDebug() {
    return this.arrayChangeDebug;
  }

  public boolean newManagedObjectDebug() {
    return this.newManagedObjectDebug;
  }

  public boolean waitNotifyDebug() {
    return this.waitNotifyDebug;
  }

  public boolean distributedMethodDebug() {
    return this.distributedMethodDebug;
  }

  public boolean nonPortableDump() {
    return this.nonPortableDump;
  }

  public void lockAcquired(String lockName, int level, Object instance, TCObject tcObject) {
    boolean isAutoLock = ByteCodeUtil.isAutolockName(lockName);

    if (isAutoLock) {
      autoLockAcquired(lockName, level, instance, tcObject);
    } else {
      namedLockAcquired(lockName, level);
    }
  }

  private void namedLockAcquired(String lockName, int level) {
    StringBuffer message = new StringBuffer("Named lock [").append(lockName).append("] acquired with level ")
        .append(LockLevel.toString(level));
    appendCall(message);
    logger.info(message);
  }

  private void autoLockAcquired(String lockName, int level, Object instance, TCObject tcObject) {
    StringBuffer message = new StringBuffer("Autolock [").append(lockName).append("] acquired with level ")
        .append(LockLevel.toString(level));

    if (autoLockDetails && (instance != null)) {
      message.append("\n  type: ").append(instance.getClass().getName());
      message.append(", identityHashCode: 0x").append(Integer.toHexString(System.identityHashCode(instance)));
    }

    appendCall(message);

    logger.info(message);
  }

  private void appendCall(StringBuffer message) {
    if (fullStack || caller) {
      StackTraceElement[] stack = getTrimmedStack();
      if (stack != null) {
        message.append("\n");
        if (fullStack) {
          for (int i = 0; i < stack.length; i++) {
            message.append("  at ").append(stack[i].toString());

            if (i < (stack.length - 1)) {
              message.append("\n");
            }
          }
        } else {
          message.append("  call: ").append(stack[0].toString());
        }
      }
    }

  }

  public void literalValueChanged(TCObject source, Object newValue) {
    StringBuffer message = new StringBuffer("DSO object literal value changed\n");
    if (newValue != null) {
      message.append("\n  newValue type: ").append(newValue.getClass().getName());
      message.append(", identityHashCode: 0x").append(Integer.toHexString(System.identityHashCode(newValue)));
    } else {
      message.append("\n  newValue: null");
    }

    logger.info(message.toString());
  }

  public void fieldChanged(TCObject source, String classname, String fieldName, Object newValue, int index) {
    StringBuffer message = new StringBuffer("DSO object field changed\n");
    message.append("  class: ").append(classname).append(", field: ").append(fieldName);
    if (index >= 0) {
      message.append(", index: ").append(index);
    }
    if (newValue != null) {
      message.append("\n  newValue type: ").append(newValue.getClass().getName());
      message.append(", identityHashCode: 0x").append(Integer.toHexString(System.identityHashCode(newValue)));
    } else {
      message.append("\n  newValue: null");
    }

    logger.info(message.toString());
  }

  public void arrayChanged(TCObject source, int startPos, Object array) {
    StringBuffer message = new StringBuffer("DSO array changed\n");
    message.append("\n startPos: ").append(startPos);
    message.append("\n subset component types: \n").append(array.getClass().getComponentType());

    logger.info(message.toString());
  }

  public void newManagedObject(TCObject object) {
    StringBuffer message = new StringBuffer("New DSO Object instance created\n");
    message.append("  instance: ").append(baseToString(object.getPeerObject())).append("\n");
    message.append("  object ID: ").append(object.getObjectID());
    appendCall(message);
    logger.info(message.toString());
  }

  public void objectNotify(boolean all, Object obj, TCObject tcObject) {
    StringBuffer message = new StringBuffer("notify").append(all ? "All()" : "()");
    message.append(" called on ").append(baseToString(obj)).append(", ObjectID: ").append(
                                                                                          tcObject.getObjectID()
                                                                                              .toLong());
    logger.info(message.toString());
  }

  public void objectWait(WaitInvocation call, Object obj, TCObject tcObject) {
    StringBuffer message = new StringBuffer(call.toString()).append(" called on ");
    message.append(baseToString(obj)).append(", ObjectID: ").append(tcObject.getObjectID().toLong());
    logger.info(message.toString());
  }

  public void distributedMethodCall(String receiverName, String methodName, String params) {
    StringBuffer message = new StringBuffer("Distributed method invoked\n");
    message.append("  receiver class: ").append(receiverName).append("\n");
    message.append("  methodName: ").append(methodName).append("\n");
    message.append("  params: ").append(params).append("\n");

    appendCall(message);
    logger.info(message.toString());
  }

  public void distributedMethodCallError(String receiverClassName, String methodName, String params, Throwable error) {
    StringBuffer message = new StringBuffer("Unhandled execption occurred in distributed method call\n");
    message.append(" receiver class: ").append(receiverClassName).append("\n");
    message.append(" methodName: ").append(methodName).append("\n");
    message.append(" params: ").append(params).append("\n");

    logger.warn(message.toString(), error);
  }

  private static String baseToString(Object obj) {
    if (obj == null) { return null; }
    return obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
  }

  private static StackTraceElement[] getTrimmedStack() {
    StackTraceElement[] stack = new Throwable().getStackTrace();
    if ((stack == null) || (stack.length <= 1)) {
      internalLogger.warn("funny stack returned: " + Util.enumerateArray(stack));
      return null;
    }

    int index = 0;
    while (index < stack.length) {
      String className = stack[index].getClassName();

      if (className.equals("com.tc.object.bytecode.ManagerUtil")) {
        break;
      }

      index++;
    }

    // advance to the calling frame
    index++;

    if (index < (stack.length - 1)) {
      StackTraceElement[] rv = new StackTraceElement[stack.length - index];
      System.arraycopy(stack, index, rv, 0, rv.length);
      return rv;
    } else {
      internalLogger.warn("could not find proper stack frame: " + Util.enumerateArray(stack));
      return null;
    }

    // unreachable
  }

}
