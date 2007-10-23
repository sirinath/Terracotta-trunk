/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.bytecode;

import com.tc.asm.Label;
import com.tc.asm.MethodVisitor;
import com.tc.asm.Opcodes;
import com.tc.asm.Type;
import com.tc.asm.commons.AdviceAdapter;
import com.tc.aspectwerkz.reflect.ClassInfo;
import com.tc.aspectwerkz.reflect.FieldInfo;
import com.tc.aspectwerkz.reflect.MemberInfo;
import com.tc.aspectwerkz.reflect.impl.asm.AsmClassInfo;
import com.tc.exception.TCInternalError;
import com.tc.object.config.LockDefinition;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.object.config.TransparencyCodeSpec;
import com.tc.object.lockmanager.api.LockLevel;

import java.util.AbstractMap;

/**
 * @author steve
 */
public class TransparencyCodeAdapter extends AdviceAdapter implements Opcodes {
  private final boolean              isAutolock;
  private final int                  autoLockType;
  private final ManagerHelper        mgrHelper;
  private final InstrumentationSpec  spec;
  private final MemberInfo           memberInfo;
  private boolean                    isConstructor;

  private final TransparencyCodeSpec codeSpec;
  private final Label                labelZero = new Label();

  private boolean                    visitInit = false;
  private boolean                    logicalInitVisited = false;

  public TransparencyCodeAdapter(InstrumentationSpec spec, boolean isAutolock, int autoLockType, MethodVisitor mv,
                                 MemberInfo memberInfo, String originalName) {
    super(mv, memberInfo.getModifiers(), originalName, memberInfo.getSignature());
    this.spec = spec;
    this.isAutolock = isAutolock;
    this.autoLockType = autoLockType;
    this.memberInfo = memberInfo;

    this.mgrHelper = spec.getManagerHelper();
    this.codeSpec = spec.getTransparencyClassSpec().getCodeSpec(memberInfo.getName(), //
                                                                memberInfo.getSignature(), isAutolock);

    isConstructor = "<init>".equals(originalName);
    if (!isConstructor) {
      visitInit = true;
    }
  }

  private int[] storeStackValuesToLocalVariables(String methodInsnDesc) {
    Type[] types = Type.getArgumentTypes(methodInsnDesc);
    int[] localVariablesForMethodCall = new int[types.length];
    for (int i = 0; i < types.length; i++) {
      localVariablesForMethodCall[i] = newLocal(types[i]);
    }
    for (int i = types.length - 1; i >= 0; i--) {
      super.visitVarInsn(types[i].getOpcode(ISTORE), localVariablesForMethodCall[i]);
    }
    return localVariablesForMethodCall;
  }

  private void loadLocalVariables(String methodInsnDesc, int[] localVariablesForMethodCall) {
    Type[] types = Type.getArgumentTypes(methodInsnDesc);
    for (int i = 0; i < types.length; i++) {
      super.visitVarInsn(types[i].getOpcode(ILOAD), localVariablesForMethodCall[i]);
    }
  }
  
  public void visitMethodInsn(int opcode, String owner, String name, String desc) {
    if (spec.hasDelegatedToLogicalClass() && isConstructor) {
      logicalInitVisitMethodInsn(opcode, owner, name, desc);
    } else {
      basicVisitMethodInsn(opcode, owner, name, desc);
    }
  }
  
  private void logicalInitVisitMethodInsn(int opcode, String owner, String name, String desc) {
    String superClassNameSlashes = spec.getSuperClassNameSlashes();
    if (!logicalInitVisited && INVOKESPECIAL == opcode && owner.equals(superClassNameSlashes) && "<init>".equals(name)) {
      logicalInitVisited = true;
      int[] localVariablesForMethodCall = storeStackValuesToLocalVariables(desc);
      loadLocalVariables(desc, localVariablesForMethodCall);
      super.visitMethodInsn(opcode, owner, name, desc);
      super.visitVarInsn(ALOAD, 0);
      super.visitTypeInsn(NEW, spec.getSuperClassNameSlashes());
      super.visitInsn(DUP);
      loadLocalVariables(desc, localVariablesForMethodCall);

      String delegateFieldName = ClassAdapterBase.getDelegateFieldName(superClassNameSlashes);
      super.visitMethodInsn(INVOKESPECIAL, superClassNameSlashes, "<init>", desc);
      super.visitMethodInsn(INVOKESPECIAL, spec.getClassNameSlashes(), ByteCodeUtil
          .fieldSetterMethod(delegateFieldName), "(L" + superClassNameSlashes + ";)V");

    } else {
      basicVisitMethodInsn(opcode, owner, name, desc);
    }
  }

  private void basicVisitMethodInsn(int opcode, String classname, String theMethodName, String desc) {
    if (handleSubclassOfLogicalClassMethodInsn(opcode, classname, theMethodName, desc)) { return; }
    if (codeSpec.isArraycopyInstrumentationReq(classname, theMethodName)) {
      rewriteArraycopy();
    } else if (classname.equals("java/lang/Object")) {
      handleJavaLangObjectMethodCall(opcode, classname, theMethodName, desc);
    } else {
      super.visitMethodInsn(opcode, classname, theMethodName, desc);
    }
  }

  private boolean handleSubclassOfLogicalClassMethodInsn(int opcode, String classname, String theMethodName, String desc) {
    if (!spec.hasDelegatedToLogicalClass()) { return false; }
    String logicalExtendingClassName = spec.getSuperClassNameSlashes();
    if (INVOKESPECIAL == opcode && !spec.getClassNameSlashes().equals(classname) && !"<init>".equals(theMethodName)) {
      spec.shouldProceedInstrumentation(memberInfo.getModifiers(), theMethodName, desc);
      int[] localVariablesForMethodCall = storeStackValuesToLocalVariables(desc);
      super.visitMethodInsn(INVOKESPECIAL, spec.getClassNameSlashes(), ByteCodeUtil.fieldGetterMethod(ClassAdapterBase
          .getDelegateFieldName(logicalExtendingClassName)), "()L" + logicalExtendingClassName + ";");
      loadLocalVariables(desc, localVariablesForMethodCall);
      super.visitMethodInsn(INVOKEVIRTUAL, logicalExtendingClassName, theMethodName, desc);
      return true;
    }
    return false;
  }

  private TransparencyClassSpec getTransparencyClassSpec() {
    return spec.getTransparencyClassSpec();
  }

  private void rewriteArraycopy() {
    callArrayManagerMethod("arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V");
  }

  private void handleJavaLangObjectMethodCall(int opcode, String classname, String theMethodName, String desc) {
    if (handleJavaLangObjectWaitNotifyCalls(opcode, classname, theMethodName, desc)) {
      return;
    } else if (handleJavaLangObjectCloneCall(opcode, classname, theMethodName, desc)) {
      return;
    } else {
      super.visitMethodInsn(opcode, classname, theMethodName, desc);
    }
  }

  /**
   * The assumption here is that the compiler wouldn't call invokevirtual on a classname other than java.lang.Object
   * when there is no implementation of clone() defined in that classes' hierarchy. If it does, it a bug in the compiler
   * ;-) This adaption is needed for both PORTABLE and ADAPTABLE classes as we can have instance where Logical subclass
   * of ADAPTABLE class calls clone() to make a copy of itself.
   *
   * The resolveLock needs to be held for the duration of the clone() call if the reference is to a shared object
   *
   * <pre>
   * Object refToBeCloned;
   * Object rv;
   *
   * TCObject tco = (refToBeCloned instanceof Manageable) ? ((Manageable) refToBeCloned).__tc_managed() : null;
   * if (tco != null) {
   *   synchronized (tco.getResolveLock()) {
   *     tco.resolveAllReferences();
   *     rv = Util.fixTCObjectReferenceOfClonedObject(refToBeCloned, refToBeCloned.clone());
   *   }
   * } else {
   *   rv = refToBeCloned.clone();
   * }
   * </pre>
   *
   * @see AbstractMap and HashMap
   */
  private boolean handleJavaLangObjectCloneCall(int opcode, String classname, String theMethodName, String desc) {
    if ("clone".equals(theMethodName) && "()Ljava/lang/Object;".equals(desc)) {

      Type objectType = Type.getObjectType("java/lang/Object");

      int refToBeCloned = newLocal(objectType);
      int ref1 = newLocal(objectType);
      int ref2 = newLocal(objectType);
      int ref3 = newLocal(objectType);

      super.visitVarInsn(ASTORE, refToBeCloned);
      Label l0 = new Label();
      Label l1 = new Label();
      Label l2 = new Label();
      super.visitTryCatchBlock(l0, l1, l2, null);
      Label l3 = new Label();
      super.visitTryCatchBlock(l2, l3, l2, null);
      super.visitVarInsn(ALOAD, refToBeCloned);
      super.visitTypeInsn(INSTANCEOF, "com/tc/object/bytecode/Manageable");
      Label l5 = new Label();
      super.visitJumpInsn(IFEQ, l5);
      super.visitVarInsn(ALOAD, refToBeCloned);
      super.visitTypeInsn(CHECKCAST, "com/tc/object/bytecode/Manageable");
      super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/bytecode/Manageable", "__tc_managed",
                            "()Lcom/tc/object/TCObject;");
      Label l6 = new Label();
      super.visitJumpInsn(GOTO, l6);
      super.visitLabel(l5);
      super.visitInsn(ACONST_NULL);
      super.visitLabel(l6);
      super.visitVarInsn(ASTORE, ref2);
      super.visitVarInsn(ALOAD, ref2);
      Label l8 = new Label();
      super.visitJumpInsn(IFNULL, l8);
      super.visitVarInsn(ALOAD, ref2);
      super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "getResolveLock", "()Ljava/lang/Object;");
      super.visitInsn(DUP);
      super.visitVarInsn(ASTORE, ref3);
      super.visitInsn(MONITORENTER);
      super.visitLabel(l0);
      super.visitVarInsn(ALOAD, ref2);
      super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "resolveAllReferences", "()V");
      super.visitVarInsn(ALOAD, refToBeCloned);
      super.visitVarInsn(ALOAD, refToBeCloned);
      super.visitMethodInsn(opcode, classname, theMethodName, desc);
      super.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/hook/impl/Util",
                            "fixTCObjectReferenceOfClonedObject",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
      super.visitVarInsn(ASTORE, ref1);
      super.visitVarInsn(ALOAD, ref3);
      super.visitInsn(MONITOREXIT);
      super.visitLabel(l1);
      Label l12 = new Label();
      super.visitJumpInsn(GOTO, l12);
      super.visitLabel(l2);
      super.visitVarInsn(ALOAD, ref3);
      super.visitInsn(MONITOREXIT);
      super.visitLabel(l3);
      super.visitInsn(ATHROW);
      super.visitLabel(l8);
      super.visitVarInsn(ALOAD, refToBeCloned);
      super.visitMethodInsn(opcode, classname, theMethodName, desc);
      super.visitVarInsn(ASTORE, ref1);
      super.visitLabel(l12);
      super.visitVarInsn(ALOAD, ref1);
      return true;
    }
    return false;
  }

  private boolean handleJavaLangObjectWaitNotifyCalls(int opcode, String classname, String theMethodName, String desc) {
    if (spec.isLogical() || !codeSpec.isWaitNotifyInstrumentationReq()) { return false; }

    Type[] args = Type.getArgumentTypes(desc);

    if (theMethodName.equals("notify") || theMethodName.equals("notifyAll")) {
      if (args.length == 0) {
        if (theMethodName.endsWith("All")) {
          mgrHelper.callManagerMethod("objectNotifyAll", this);
        } else {
          mgrHelper.callManagerMethod("objectNotify", this);
        }
        return true;
      }
      throw new TCInternalError("Unexpected java.lang.Object method signature: " + theMethodName + " + " + desc);
    } else if (theMethodName.equals("wait")) {

      switch (args.length) {
        case 0: {
          mgrHelper.callManagerMethod("objectWait0", this);
          return true;
        }
        case 1: {
          if (args[0].equals(Type.LONG_TYPE)) {
            mgrHelper.callManagerMethod("objectWait1", this);
            return true;
          }
          throw new TCInternalError("Unexpected java.lang.Object method signature: " + theMethodName + " + " + desc);
        }
        case 2: {
          if ((args[0].equals(Type.LONG_TYPE)) && (args[1].equals(Type.INT_TYPE))) {
            mgrHelper.callManagerMethod("objectWait2", this);
            return true;
          }
          throw new TCInternalError("Unexpected java.lang.Object method signature: " + theMethodName + " + " + desc);
        }
        default: {
          throw new TCInternalError("Unexpected java.lang.Object method signature: " + theMethodName + " + " + desc);
        }
      }
    } else { // neither wait(...) nor notify[All]()
      return false;
    }

    // should be unreachable
  }

  private void callTCBeginWithLocks(MethodVisitor c) {
    c.visitLabel(new Label());
    LockDefinition[] defs = getTransparencyClassSpec().lockDefinitionsFor(memberInfo);
    for (int i = 0; i < defs.length; i++) {
      if (!defs[i].isAutolock()) {
        callTCBeginWithLock(defs[i], c);
      }
    }
  }

  private void callTCBeginWithLock(LockDefinition lock, MethodVisitor c) {
    c.visitLdcInsn(ByteCodeUtil.generateNamedLockName(lock.getLockName()));
    c.visitLdcInsn(new Integer(lock.getLockLevelAsInt()));
    mgrHelper.callManagerMethod("beginLock", c);
  }

  private void callTCCommit(MethodVisitor c) {
    LockDefinition[] locks = getTransparencyClassSpec().lockDefinitionsFor(memberInfo);
    for (int i = 0; i < locks.length; i++) {
      if (!locks[i].isAutolock()) {
        c.visitLdcInsn(ByteCodeUtil.generateNamedLockName(locks[i].getLockName()));
        mgrHelper.callManagerMethod("commitLock", c);
      }
    }
  }
  
  private void visitInsnForReadLock(int opCode) {
    switch(opCode) {
      case MONITORENTER:
        super.visitInsn(DUP);
        super.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isDsoMonitored", "(Ljava/lang/Object;)Z");
        Label l1 = new Label();
        super.visitJumpInsn(IFEQ, l1);
        super.visitLdcInsn(new Integer(autoLockType));
        mgrHelper.callManagerMethod("monitorEnter", this);
        Label l2 = new Label();
        super.visitJumpInsn(GOTO, l2);
        super.visitLabel(l1);
        super.visitInsn(opCode);
        super.visitLabel(l2);
        return;
      case MONITOREXIT:
        super.visitInsn(DUP);
        super.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isDsoMonitorEntered", "(Ljava/lang/Object;)Z");
        Label l3 = new Label();
        super.visitJumpInsn(IFEQ, l3);
        mgrHelper.callManagerMethod("monitorExit", this);
        Label l4 = new Label();
        super.visitJumpInsn(GOTO, l4);
        super.visitLabel(l3);
        super.visitInsn(opCode);
        super.visitLabel(l4);
        return;
    }
  }

  public void visitInsn(int opCode) {
    if (isMonitorInstrumentationReq(opCode)) {
      switch (opCode) {
        case MONITORENTER:
          if (this.isAutolock) {
            if (autoLockType == LockLevel.READ) {
              visitInsnForReadLock(opCode);
              return;
            }
            super.visitInsn(DUP);
            super.visitLdcInsn(new Integer(autoLockType));
            mgrHelper.callManagerMethod("monitorEnter", this);
            super.visitInsn(opCode);
          } else {
            super.visitInsn(opCode);
          }
          return;
        case MONITOREXIT:
          if (this.isAutolock) {
            if (autoLockType == LockLevel.READ) {
              visitInsnForReadLock(opCode);
              return;
            }
            super.visitInsn(DUP);
            super.visitInsn(opCode);
            mgrHelper.callManagerMethod("monitorExit", this);
          } else {
            super.visitInsn(opCode);
          }
          return;
      }
    }
    if (isArrayOperatorInstrumentationReq(opCode)) {
      switch (opCode) {
        case AALOAD:
          Label end = new Label();
          Label notManaged = new Label();
          Label noIndexException = new Label();
          super.visitInsn(DUP2);
          super.visitInsn(POP);
          callArrayManagerMethod("getObject", "(Ljava/lang/Object;)Lcom/tc/object/TCObject;");
          super.visitInsn(DUP);
          super.visitJumpInsn(IFNULL, notManaged);
          super.visitInsn(DUP2);
          super.visitInsn(SWAP);
          super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "checkArrayIndex",
                                "(I)Ljava/lang/ArrayIndexOutOfBoundsException;");
          super.visitInsn(DUP);
          super.visitJumpInsn(IFNULL, noIndexException);
          super.visitInsn(SWAP);
          super.visitInsn(POP);
          super.visitInsn(SWAP);
          super.visitInsn(POP);
          super.visitInsn(SWAP);
          super.visitInsn(POP);
          super.visitInsn(ATHROW);
          super.visitLabel(noIndexException);
          super.visitInsn(POP);
          super.visitInsn(DUP_X2);
          super.visitInsn(DUP);
          super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "getResolveLock", "()Ljava/lang/Object;");
          super.visitInsn(MONITORENTER);
          super.visitInsn(DUP2);
          super.visitInsn(SWAP);
          super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "resolveArrayReference", "(I)V");
          super.visitInsn(POP);
          super.visitInsn(opCode);
          super.visitInsn(SWAP);
          super.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "getResolveLock", "()Ljava/lang/Object;");
          super.visitInsn(MONITOREXIT);
          super.visitJumpInsn(GOTO, end);
          super.visitLabel(notManaged);
          super.visitInsn(POP);
          super.visitInsn(opCode);
          super.visitLabel(end);
          return;
        case AASTORE:
          callArrayManagerMethod("objectArrayChanged", "([Ljava/lang/Object;ILjava/lang/Object;)V");
          return;
        case LASTORE:
          callArrayManagerMethod("longArrayChanged", "([JIJ)V");
          return;
        case SASTORE:
          callArrayManagerMethod("shortArrayChanged", "([SIS)V");
          return;
        case IASTORE:
          callArrayManagerMethod("intArrayChanged", "([III)V");
          return;
        case DASTORE:
          callArrayManagerMethod("doubleArrayChanged", "([DID)V");
          return;
        case FASTORE:
          callArrayManagerMethod("floatArrayChanged", "([FIF)V");
          return;
        case BASTORE:
          callArrayManagerMethod("byteOrBooleanArrayChanged", "(Ljava/lang/Object;IB)V");
          return;
        case CASTORE:
          callArrayManagerMethod("charArrayChanged", "([CIC)V");
          return;
      }
    }
    super.visitInsn(opCode);
  }

  private boolean isArrayOperatorInstrumentationReq(int opCode) {
    return ((opCode == AALOAD || opCode == AASTORE || opCode == LASTORE || opCode == SASTORE || opCode == IASTORE
             || opCode == DASTORE || opCode == FASTORE || opCode == BASTORE || opCode == CASTORE) && codeSpec
        .isArrayOperatorInstrumentationReq());
  }

  private boolean isMonitorInstrumentationReq(int opCode) {
    return ((opCode == MONITORENTER || opCode == MONITOREXIT) && codeSpec.isMonitorInstrumentationReq());
  }

  private void callArrayManagerMethod(String name, String desc) {
    super.visitMethodInsn(INVOKESTATIC, ManagerUtil.CLASS, name, desc);
  }

  public void visitMaxs(int stack, int vars) {
    super.visitMaxs(stack, vars + 1);
  }

  public void visitFieldInsn(final int opcode, final String classname, final String fieldName, final String desc) {
    spec.shouldProceedInstrumentation(fieldName, desc);

    if (!spec.needInstrumentFieldInsn() || !visitInit || !codeSpec.isFieldInstrumentationReq(fieldName)) {
      super.visitFieldInsn(opcode, classname, fieldName, desc);
      return;
    }

    if (spec.isPhysical()) {
      // if (opcode == GETFIELD && (isRoot(classname, fieldName) || !isPrimitive(Type.getType(desc)))) {
      if (opcode == GETFIELD) {
        visitGetFieldInsn(classname, fieldName, desc);
        return;
      } else if (opcode == PUTFIELD) {
        visitSetFieldInsn(classname, fieldName, desc);
        return;
      } else if (opcode == PUTSTATIC && isRoot(classname, fieldName)) {
        String sDesc = "(" + desc + ")V";
        visitMethodInsn(INVOKESTATIC, classname, ByteCodeUtil.fieldSetterMethod(fieldName), sDesc);
        return;
      } else if (opcode == GETSTATIC && isRoot(classname, fieldName)) {
        String gDesc = "()" + desc;
        visitMethodInsn(INVOKESTATIC, classname, ByteCodeUtil.fieldGetterMethod(fieldName), gDesc);
        return;
      }
      super.visitFieldInsn(opcode, classname, fieldName, desc);
    } else {
      super.visitFieldInsn(opcode, classname, fieldName, desc);
    }
  }

  private void visitSetFieldInsn(String classname, String fieldName, String desc) {
    boolean inClassHierarchy = spec.isInClassHierarchy(classname);
    if ((spec.isClassPortable() && inClassHierarchy) || isRoot(classname, fieldName)) {
      // If the field is a root, we assume that the class is instrumented automatically.
      // If it is not then bad things are gonna happen anyway.
      visitUncheckedSetFieldInsn(classname, fieldName, desc);
    } else if (spec.isClassAdaptable() && inClassHierarchy) {
      visitSetFieldInsnOriginal(classname, fieldName, desc);
    } else {
      visitCheckedSetFieldInsn(classname, fieldName, desc);
    }
  }

  private void visitSetFieldInsnOriginal(String classname, String fieldName, String desc) {
    // System.err.println("Original :: My class : " + spec.getClassNameSlashes() + " set on : " + classname + " field :
    // " + fieldName);
    super.visitFieldInsn(PUTFIELD, classname, fieldName, desc);
  }

  private void visitUncheckedSetFieldInsn(String classname, String fieldName, String desc) {
    // System.err.println("Unchecked :: My class : " + spec.getClassNameSlashes() + " set on : " + classname + " field :
    // " + fieldName);
    String sDesc = "(" + desc + ")V";
    visitMethodInsn(INVOKEVIRTUAL, classname, ByteCodeUtil.fieldSetterMethod(fieldName), sDesc);
  }

  /**
   * This method assumes that we dont have anyinfo on the class that we are setting the field to. so we take the
   * conservative approach.
   */
  private void visitCheckedSetFieldInsn(String classname, String fieldName, String desc) {
    // System.err.println("Checked :: My class : " + spec.getClassNameSlashes() + " set on : " + classname + " field : "
    // + fieldName);
    Type fieldType = Type.getType(desc);
    Type reference = Type.getType("Ljava/lang/Object;");
    String sDesc = "(" + desc + ")V";

    swap(reference, fieldType);
    super.visitInsn(DUP);
    Label l1 = new Label();
    super.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
    mgrHelper.callManagerMethod("isPhysicallyInstrumented", this);
    super.visitJumpInsn(IFEQ, l1);
    swap(fieldType, reference);
    visitMethodInsn(INVOKEVIRTUAL, classname, ByteCodeUtil.fieldSetterMethod(fieldName), sDesc);
    Label l2 = new Label();
    super.visitJumpInsn(GOTO, l2);
    super.visitLabel(l1);
    swap(fieldType, reference);
    super.visitFieldInsn(PUTFIELD, classname, fieldName, desc);
    super.visitLabel(l2);
  }

  private void visitGetFieldInsn(String classname, String fieldName, String desc) {
    boolean inClassHierarchy = spec.isInClassHierarchy(classname);
    if ((spec.isClassPortable() && inClassHierarchy) || isRoot(classname, fieldName)) {
      // If the field is a root, we assume that the class is instrumented automatically.
      // If it is not then bad things are gonna happen anyway.
      visitUncheckedGetFieldInsn(classname, fieldName, desc);
    } else if (spec.isClassAdaptable() && inClassHierarchy) {
      visitGetFieldInsnOriginal(classname, fieldName, desc);
    } else {
      visitCheckedGetFieldInsn(classname, fieldName, desc);
    }
  }

  private void visitGetFieldInsnOriginal(String classname, String fieldName, String desc) {
    // System.err.println("Original :: My class : " + spec.getClassNameSlashes() + " get on : " + classname + " field :
    // " + fieldName);
    super.visitFieldInsn(GETFIELD, classname, fieldName, desc);
  }

  private void visitUncheckedGetFieldInsn(String classname, String fieldName, String desc) {
    // System.err.println("Unchecked :: My class: " + spec.getClassNameSlashes() + " get on : " + classname + " field :
    // " + fieldName);
    String gDesc = "()" + desc;
    visitMethodInsn(INVOKEVIRTUAL, classname, ByteCodeUtil.fieldGetterMethod(fieldName), gDesc);
  }

  /**
   * This method assumes that we dont have anyinfo on the class that we are setting the field to. so we take the
   * conservative approach.
   */
  private void visitCheckedGetFieldInsn(String classname, String fieldName, String desc) {
    String gDesc = "()" + desc;
    super.visitInsn(DUP);
    Label l1 = new Label();
    super.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
    mgrHelper.callManagerMethod("isPhysicallyInstrumented", this);
    super.visitJumpInsn(IFEQ, l1);
    visitMethodInsn(INVOKEVIRTUAL, classname, ByteCodeUtil.fieldGetterMethod(fieldName), gDesc);
    Label l2 = new Label();
    super.visitJumpInsn(GOTO, l2);
    super.visitLabel(l1);
    super.visitFieldInsn(GETFIELD, classname, fieldName, desc);
    super.visitLabel(l2);
  }

  private boolean isRoot(String classname, String fieldName) {
    ClassInfo classInfo = AsmClassInfo.getClassInfo(classname.replace('/', '.'), spec.getCaller());
    FieldInfo[] fields = classInfo.getFields();
    for (int i = 0; i < fields.length; i++) {
      FieldInfo fieldInfo = fields[i];

      if (fieldName.equals(fieldInfo.getName())) {
        if (getTransparencyClassSpec().isRoot(fieldInfo)) { return true; }
      }
    }

    return false;
  }

  protected void onMethodEnter() {
    if (isConstructor) {
      visitInit = true;
      if (getTransparencyClassSpec().isLockMethod(memberInfo)) {
        callTCBeginWithLocks(this);
        super.visitLabel(labelZero);
      }
    }
  }

  protected void onMethodExit(int opcode) {
    if (isConstructor && getTransparencyClassSpec().isLockMethod(memberInfo)) {

      if (opcode == RETURN) {
        callTCCommit(this);
      } else if (opcode == ATHROW) {
        // nothing special to do here, exception handler for method will do the commit
      } else {
        // <init> should not be returning with any other opcodes
        throw new AssertionError("unexpected exit instruction: " + opcode);
      }
    }
  }

  public void visitEnd() {
    if (isConstructor && getTransparencyClassSpec().isLockMethod(memberInfo)) {
      Label labelEnd = new Label();
      super.visitLabel(labelEnd);
      super.visitTryCatchBlock(labelZero, labelEnd, labelEnd, null);
      int localVar = newLocal(Type.getObjectType("java/lang/Object"));
      super.visitVarInsn(ASTORE, localVar);
      callTCCommit(mv);
      super.visitVarInsn(ALOAD, localVar);
      mv.visitInsn(ATHROW);
    }

    super.visitEnd();
  }

}
