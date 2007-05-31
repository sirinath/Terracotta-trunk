/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.bytecode;

import com.tc.asm.ClassVisitor;
import com.tc.asm.FieldVisitor;
import com.tc.asm.Label;
import com.tc.asm.MethodVisitor;
import com.tc.asm.Type;
import com.tc.aspectwerkz.exception.DefinitionException;
import com.tc.aspectwerkz.reflect.ClassInfo;
import com.tc.aspectwerkz.reflect.MemberInfo;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.object.Portability;
import com.tc.object.config.ConfigLockLevel;
import com.tc.object.config.LockDefinition;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.object.lockmanager.api.LockLevel;
import com.tc.object.logging.InstrumentationLogger;
import com.tc.text.Banner;
import com.tc.util.Assert;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * @author steve
 */
public class TransparencyClassAdapter extends ClassAdapterBase {
  private static final TCLogger            logger          = TCLogging.getLogger(TransparencyClassAdapter.class);

  private final Set                        doNotInstrument = new HashSet();
  private final PhysicalClassAdapterLogger physicalClassLogger;
  private final InstrumentationLogger      instrumentationLogger;

  public TransparencyClassAdapter(ClassInfo classInfo, TransparencyClassSpec spec, final ClassVisitor cv,
                                  ManagerHelper mgrHelper, InstrumentationLogger instrumentationLogger,
                                  ClassLoader caller, Portability portability) {
    super(classInfo, spec, cv, mgrHelper, caller, portability);
    this.instrumentationLogger = instrumentationLogger;
    this.physicalClassLogger = new PhysicalClassAdapterLogger(logger);
  }

  protected void basicVisit(final int version, final int access, final String name, String signature,
                            final String superClassName, final String[] interfaces) {

    try {
      logger.debug("ADAPTING CLASS: " + name);
      super.basicVisit(version, access, name, signature, superClassName, interfaces);
      getTransparencyClassSpec().createClassSupportMethods(cv);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
      throw e;
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void handleInstrumentationException(Throwable e) {
    logger.fatal(e);
    logger.fatal("Calling System.exit(1)");
    System.exit(1);
  }

  private boolean isRoot(int access, String fieldName) {
    try {
      boolean isRoot = getTransparencyClassSpec().isRootInThisClass(fieldName);
      boolean isTransient = getTransparencyClassSpec().isTransient(access, spec.getClassInfo(), fieldName);
      if (isTransient && isRoot) {
        if (instrumentationLogger.transientRootWarning()) {
          instrumentationLogger.transientRootWarning(this.spec.getClassNameDots(), fieldName);
        }
      }
      return isRoot;
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
      throw e;
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private String rootNameFor(String className, String fieldName) {
    try {
      return getTransparencyClassSpec().rootNameFor(fieldName);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
      throw e;
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  protected FieldVisitor basicVisitField(final int access, final String name, final String desc, String signature,
                                         final Object value) {

    FieldVisitor fieldVisitor = null;
    try {

      if ((spec.isClassPortable() && spec.isPhysical() && !ByteCodeUtil.isTCSynthetic(name))
          || (spec.isClassAdaptable() && isRoot(access, name))) {
        // include the field, but remove final modifier for *most* fields
        if (Modifier.isFinal(access) && !isMagicSerializationField(access, name, desc)) {
          fieldVisitor = cv.visitField(~Modifier.FINAL & access, name, desc, signature, value);
        } else {
          fieldVisitor = cv.visitField(access, name, desc, signature, value);
        }
        generateGettersSetters(access, name, desc, Modifier.isStatic(access));
      } else {
        fieldVisitor = cv.visitField(access, name, desc, signature, value);
      }
    } catch (RuntimeException e) {
      e.printStackTrace();
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
    return fieldVisitor;
  }

  private static boolean isStatic(int access) {
    return Modifier.isStatic(access);
  }

  private static boolean isFinal(int access) {
    return Modifier.isFinal(access);
  }

  private static boolean isPrivate(int access) {
    return Modifier.isPrivate(access);
  }

  private boolean isMagicSerializationField(int access, String fieldName, String fieldDesc) {
    // this method tests if the given field is the one the magic fields used by java serialization. If it is, we should
    // not change any details about this field

    boolean isStatic = isStatic(access);
    boolean isFinal = isFinal(access);
    boolean isPrivate = isPrivate(access);

    if (isStatic && isFinal) {
      if ("J".equals(fieldDesc) && "serialVersionUID".equals(fieldName)) { return true; }
      if (isPrivate && "serialPersistentFields".equals(fieldName) && "[Ljava/io/ObjectStreamField;".equals(fieldDesc)) { return true; }
    }

    return false;
  }

  private void generateGettersSetters(final int fieldAccess, final String name, final String desc, boolean isStatic) {
    boolean isTransient = getTransparencyClassSpec().isTransient(fieldAccess, spec.getClassInfo(), name);
    // Plain getter and setters are generated for transient fields as other instrumented classes might call them.
    boolean createPlainAccessors = isTransient && !isStatic;
    boolean createInstrumentedAccessors = !isTransient && !isStatic;
    boolean createRootAccessors = isRoot(fieldAccess, name);

    int methodAccess = fieldAccess & (~ACC_TRANSIENT);
    methodAccess &= (~ACC_FINAL); // remove final modifier since variable might be shadowed
    methodAccess &= (~ACC_VOLATILE);
    methodAccess |= ACC_SYNTHETIC;

    if (createRootAccessors) {
      createRootGetter(methodAccess, name, desc);
    } else if (createInstrumentedAccessors) {
      if (!ByteCodeUtil.isPrimitive(Type.getType(desc))) {
        createInstrumentedGetter(methodAccess, fieldAccess, name, desc);
      } else {
        createPlainGetter(methodAccess, fieldAccess, name, desc);
      }
    } else if (createPlainAccessors) {
      createPlainGetter(methodAccess, fieldAccess, name, desc);
    }

    if (createInstrumentedAccessors || createRootAccessors) {
      createInstrumentedSetter(methodAccess, fieldAccess, name, desc);
    } else if (createPlainAccessors) {
      createPlainSetter(methodAccess, fieldAccess, name, desc);
    }
  }

  private boolean isPrimitive(Type t) {
    return ByteCodeUtil.isPrimitive(t);
  }

  protected MethodVisitor basicVisitMethod(int access, String name, final String desc, String signature,
                                           final String[] exceptions) {
    String originalName = name;

    try {
      physicalClassLogger.logVisitMethodBegin(access, name, desc, signature, exceptions);

      MemberInfo memberInfo = getInstrumentationSpec().getMethodInfo(access, name, desc);

      if (name.startsWith(ByteCodeUtil.TC_METHOD_PREFIX) || doNotInstrument.contains(name + desc)
          || getTransparencyClassSpec().doNotInstrument(name)) {
        if (!getTransparencyClassSpec().hasCustomMethodAdapter(memberInfo)) {
          physicalClassLogger.logVisitMethodIgnoring(name, desc);
          return cv.visitMethod(access, name, desc, signature, exceptions);
        }
      }

      LockDefinition[] locks = getTransparencyClassSpec().lockDefinitionsFor(memberInfo);
      LockDefinition ld = getTransparencyClassSpec().getAutolockDefinition(locks);
      boolean isAutolock = (ld != null);
      int lockLevel = -1;
      if (isAutolock) {
        lockLevel = ld.getLockLevelAsInt();
        if (instrumentationLogger.lockInsertion()) {
          instrumentationLogger.autolockInserted(this.spec.getClassNameDots(), name, desc, ld);
        }
      }
      
      if (isAutoSynchronized(ld) && !"<init>".equals(name)) {
        access |= ACC_SYNCHRONIZED;
      }
      
      boolean isLockMethod = isAutolock && Modifier.isSynchronized(access) && !Modifier.isStatic(access);
      physicalClassLogger.logVisitMethodCheckIsLockMethod();

      if (!isLockMethod || spec.isClassAdaptable()) {
        // LockDefinition methodLD = getTransparencyClassSpec().getLockMethodLockDefinition(access, locks);
        LockDefinition namedLockDefinition = getTransparencyClassSpec().getNonAutoLockDefinition(locks);
        isLockMethod = (namedLockDefinition != null);
      }

      // if (methodLD != null && (spec.isClassPortable() || (spec.isClassAdaptable() && !methodLD.isAutolock()))
      // && !name.equals("<init>")) {
      if (isLockMethod && !"<init>".equals(name)) {
        physicalClassLogger.logVisitMethodCreateLockMethod(name);
        // This method is a lock method.
        Assert.assertNotNull(locks);
        Assert.eval(locks.length > 0 || isLockMethod);
        createLockMethod(access, name, desc, signature, exceptions, locks);

        logCustomerLockMethod(name, desc, locks);
        name = ByteCodeUtil.METHOD_RENAME_PREFIX + name;
        access |= ACC_PRIVATE;
        access &= (~ACC_PUBLIC);
        access &= (~ACC_PROTECTED);
      } else {
        physicalClassLogger.logVisitMethodNotALockMethod(access, this.spec.getClassNameDots(), name, desc, exceptions);
      }
      MethodVisitor mv = null;

      boolean hasCustomMethodAdapter = getTransparencyClassSpec().hasCustomMethodAdapter(memberInfo);
      if (hasCustomMethodAdapter) {
        MethodAdapter ma = getTransparencyClassSpec().customMethodAdapterFor(spec.getManagerHelper(), access, name,
                                                                             originalName, desc, signature, exceptions,
                                                                             instrumentationLogger, memberInfo);
        mv = ma.adapt(cv);

        if (!ma.doesOriginalNeedAdapting()) return mv;
      }

      if (mv == null) {
        mv = cv.visitMethod(access, name, desc, signature, exceptions);
      }

      return mv == null ? null : new TransparencyCodeAdapter(spec, isAutolock, lockLevel, mv, memberInfo, originalName);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
      throw e;
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }
  
  private boolean isAutoSynchronized(LockDefinition ld) {
    if (ld == null) { return false; }
    
    ConfigLockLevel lockLevel = ld.getLockLevel();
    return ConfigLockLevel.AUTO_SYNCHRONIZED_READ.equals(lockLevel) || ConfigLockLevel.AUTO_SYNCHRONIZED_WRITE.equals(lockLevel);
  }

  // protected void basicVisitEnd() {
  // // if adaptee has DMI
  // boolean hasCustomMethodAdapter = getTransparencyClassSpec().hasCustomMethodAdapter(access, originalName, desc,
  // exceptions);
  // super.basicVisitEnd();
  // }

  private void logCustomerLockMethod(String name, final String desc, LockDefinition[] locks) {
    if (instrumentationLogger.lockInsertion()) {
      instrumentationLogger.lockInserted(this.spec.getClassNameDots(), name, desc, locks);
    }
  }

  private void createLockMethod(int access, String name, String desc, String signature, final String[] exceptions,
                                LockDefinition[] locks) {
    try {
      physicalClassLogger.logCreateLockMethodBegin(access, name, desc, signature, exceptions, locks);
      doNotInstrument.add(name + desc);
      Type returnType = Type.getReturnType(desc);
      if (returnType.getSort() == Type.VOID) {
        createLockMethodVoid(access, name, desc, signature, exceptions, locks);
      } else {
        createLockMethodReturn(access, name, desc, signature, exceptions, locks, returnType);
      }
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
      throw e;
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  /**
   * Creates a tc lock method for the given method that returns void.
   */
  private void createLockMethodVoid(int access, String name, String desc, String signature, final String[] exceptions,
                                    LockDefinition[] locks) {
    try {
      int localVariableOffset = ByteCodeUtil.getLocalVariableOffset(access);

      physicalClassLogger.logCreateLockMethodVoidBegin(access, name, desc, signature, exceptions, locks);
      MethodVisitor c = cv.visitMethod(access & (~Modifier.SYNCHRONIZED), name, desc, signature, exceptions);
      callTCBeginWithLocks(access, name, desc, locks, c);
      Label l0 = new Label();
      c.visitLabel(l0);
      callRenamedMethod(access, name, desc, c);
      // This label creation has something to do with try/finally
      Label l1 = new Label();
      c.visitJumpInsn(GOTO, l1);
      Label l2 = new Label();
      c.visitLabel(l2);
      c.visitVarInsn(ASTORE, 1 + localVariableOffset);
      Label l3 = new Label();
      c.visitJumpInsn(JSR, l3);
      c.visitVarInsn(ALOAD, 1 + localVariableOffset);
      c.visitInsn(ATHROW);
      c.visitLabel(l3);
      c.visitVarInsn(ASTORE, 0 + localVariableOffset);
      callTCCommit(access, name, desc, locks, c);
      c.visitVarInsn(RET, 0 + localVariableOffset);
      c.visitLabel(l1);
      c.visitJumpInsn(JSR, l3);
      Label l4 = new Label();
      c.visitLabel(l4);
      c.visitInsn(RETURN);
      c.visitTryCatchBlock(l0, l2, l2, null);
      c.visitTryCatchBlock(l1, l4, l2, null);
      c.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void handleInstrumentationException(RuntimeException e) {
    // XXX: Yucky.
    if (e instanceof DefinitionException) {
      logger.fatal(e.getLocalizedMessage());
    } else {
      logger.fatal(e);
    }

    e.printStackTrace(System.err);
    System.err.flush();
    String msg = "Error detected -- Calling System.exit(1)";
    Banner.errorBanner(msg);

    logger.fatal(msg);
    System.exit(1);
  }

  private void callRenamedMethod(int callingMethodModifier, String name, String desc, MethodVisitor c) {
    // Call the renamed original method.
    ByteCodeUtil.prepareStackForMethodCall(callingMethodModifier, desc, c);
    if (Modifier.isStatic(callingMethodModifier)) {
      c.visitMethodInsn(INVOKESTATIC, spec.getClassNameSlashes(), ByteCodeUtil.METHOD_RENAME_PREFIX + name, desc);
    } else {
      c.visitMethodInsn(INVOKESPECIAL, spec.getClassNameSlashes(), ByteCodeUtil.METHOD_RENAME_PREFIX + name, desc);
    }
  }

  /**
   * Creates a tc lock method for the given method that returns a value (doesn't return void).
   */
  private void createLockMethodReturn(int access, String name, String desc, String signature,
                                      final String[] exceptions, LockDefinition[] locks, Type returnType) {
    try {
      physicalClassLogger.logCreateLockMethodReturnBegin(access, name, desc, signature, exceptions, locks);
      int localVariableOffset = ByteCodeUtil.getLocalVariableOffset(access);
      MethodVisitor c = cv.visitMethod(access & (~Modifier.SYNCHRONIZED), name, desc, signature, exceptions);

      callTCBeginWithLocks(access, name, desc, locks, c);
      Label l0 = new Label();
      c.visitLabel(l0);
      callRenamedMethod(access, name, desc, c);
      c.visitVarInsn(returnType.getOpcode(ISTORE), 2 + localVariableOffset);
      Label l1 = new Label();
      c.visitJumpInsn(JSR, l1);
      Label l2 = new Label();
      c.visitLabel(l2);
      c.visitVarInsn(returnType.getOpcode(ILOAD), 2 + localVariableOffset);
      c.visitInsn(returnType.getOpcode(IRETURN));
      Label l3 = new Label();
      c.visitLabel(l3);
      c.visitVarInsn(ASTORE, 1 + localVariableOffset);
      c.visitJumpInsn(JSR, l1);
      c.visitVarInsn(ALOAD, 1 + localVariableOffset);
      c.visitInsn(ATHROW);
      c.visitLabel(l1);
      c.visitVarInsn(ASTORE, 0 + localVariableOffset);
      callTCCommit(access, name, desc, locks, c);
      c.visitVarInsn(RET, 0 + localVariableOffset);
      c.visitTryCatchBlock(l0, l2, l3, null);
      c.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }

  }

  private void callTCBeginWithLocks(int access, String name, String desc, LockDefinition[] locks, MethodVisitor c) {
    physicalClassLogger.logCallTCBeginWithLocksStart(access, name, desc, locks, c);
    for (int i = 0; i < locks.length; i++) {
      LockDefinition lock = locks[i];
      if (lock.isAutolock() && spec.isClassPortable()) {
        physicalClassLogger.logCallTCBeginWithLocksAutolock();
        if (Modifier.isSynchronized(access) && !Modifier.isStatic(access)) {
          physicalClassLogger.logCallTCBeginWithLocksAutolockSynchronized(name, desc);
          callTCMonitorEnter(access, locks[i], c);
        } else {
          physicalClassLogger.logCallTCBeginWithLocksAutolockNotSynchronized(name, desc);
        }
      } else if (!lock.isAutolock()) {
        physicalClassLogger.logCallTCBeginWithLocksNoAutolock(lock);
        callTCBeginWithLock(lock, c);
      }
    }
  }

  private void callTCCommit(int access, String name, String desc, LockDefinition[] locks, MethodVisitor c) {
    physicalClassLogger.logCallTCCommitBegin(access, name, desc, locks, c);
    for (int i = 0; i < locks.length; i++) {
      LockDefinition lock = locks[i];
      if (lock.isAutolock() && spec.isClassPortable()) {
        if (Modifier.isSynchronized(access) && !Modifier.isStatic(access)) {
          callTCMonitorExit(access, c);
        }
      } else if (!lock.isAutolock()) {
        c.visitLdcInsn(ByteCodeUtil.generateNamedLockName(lock.getLockName()));
        spec.getManagerHelper().callManagerMethod("commitLock", c);
      }
    }
  }

  private void callTCCommitWithLockName(String lockName, MethodVisitor mv) {
    mv.visitLdcInsn(lockName);
    spec.getManagerHelper().callManagerMethod("commitLock", mv);
  }

  private void callTCBeginWithLock(LockDefinition lock, MethodVisitor c) {
    c.visitLdcInsn(ByteCodeUtil.generateNamedLockName(lock.getLockName()));
    c.visitLdcInsn(new Integer(lock.getLockLevelAsInt()));
    spec.getManagerHelper().callManagerMethod("beginLock", c);
  }

  private void callTCBeginWithLockName(String lockName, int lockLevel, MethodVisitor mv) {
    mv.visitLdcInsn(lockName);
    mv.visitLdcInsn(new Integer(lockLevel));
    spec.getManagerHelper().callManagerMethod("beginLock", mv);
  }

  private void callVolatileBegin(String fieldName, int lockLevel, MethodVisitor mv) {
    getManaged(mv);
    mv.visitLdcInsn(fieldName);
    mv.visitIntInsn(BIPUSH, lockLevel);
    spec.getManagerHelper().callManagerMethod("beginVolatile", mv);
  }

  private void callVolatileCommit(String fieldName, MethodVisitor mv) {
    getManaged(mv);
    mv.visitLdcInsn(fieldName);
    spec.getManagerHelper().callManagerMethod("commitVolatile", mv);
  }

  private void createPlainGetter(int methodAccess, int fieldAccess, String name, String desc) {
    boolean isVolatile = isVolatile(fieldAccess, name);

    String gDesc = "()" + desc;
    MethodVisitor gv = this.visitMethod(methodAccess, ByteCodeUtil.fieldGetterMethod(name), gDesc, null, null);
    Type t = Type.getType(desc);

    Label l4 = new Label();

    if (isVolatile) {
      getManaged(gv);
      gv.visitInsn(DUP);
      gv.visitVarInsn(ASTORE, 2);
      gv.visitJumpInsn(IFNULL, l4);

      Label l0 = new Label();
      Label l1 = new Label();
      Label l2 = new Label();
      gv.visitTryCatchBlock(l0, l1, l2, null);
      gv.visitLabel(l0);

      callVolatileBegin(spec.getClassNameDots() + "." + name, LockLevel.READ, gv);

      Label l6 = new Label();
      gv.visitJumpInsn(JSR, l6);
      gv.visitLabel(l1);
      ByteCodeUtil.pushThis(gv);
      gv.visitFieldInsn(GETFIELD, spec.getClassNameSlashes(), name, desc);
      gv.visitInsn(t.getOpcode(IRETURN));
      gv.visitLabel(l2);
      gv.visitVarInsn(ASTORE, 2);
      gv.visitJumpInsn(JSR, l6);
      gv.visitVarInsn(ALOAD, 2);
      gv.visitInsn(ATHROW);
      gv.visitLabel(l6);
      gv.visitVarInsn(ASTORE, 1);

      callVolatileCommit(spec.getClassNameDots() + "." + name, gv);
      gv.visitVarInsn(RET, 1);
    }

    gv.visitLabel(l4);
    ByteCodeUtil.pushThis(gv);
    gv.visitFieldInsn(GETFIELD, spec.getClassNameSlashes(), name, desc);
    gv.visitInsn(t.getOpcode(IRETURN));

    gv.visitMaxs(0, 0);
  }

  private void checkReturnObjectType(String fieldName, String rootName, String targetType, int loadVariableNumber,
                                     Label matchLabel, MethodVisitor mv) {
    mv.visitVarInsn(ALOAD, loadVariableNumber);
    mv.visitTypeInsn(INSTANCEOF, targetType);
    mv.visitJumpInsn(IFNE, matchLabel);
    mv.visitTypeInsn(NEW, "java/lang/ClassCastException");
    mv.visitInsn(DUP);
    mv.visitTypeInsn(NEW, "java/lang/StringBuffer");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("The field '");
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuffer", "<init>", "(Ljava/lang/String;)V");
    mv.visitLdcInsn(fieldName);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn("' with root name '");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn(rootName);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn("' cannot be assigned to a variable of type ");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn(targetType);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn(". This root has a type ");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitVarInsn(ALOAD, loadVariableNumber);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn(". ");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitLdcInsn("Perhaps you have the same root name assigned more than once to variables of different types.");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "append",
                       "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuffer", "toString", "()Ljava/lang/String;");
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/ClassCastException", "<init>", "(Ljava/lang/String;)V");
    mv.visitInsn(ATHROW);

  }

  private void createRootGetter(int methodAccess, String name, String desc) {
    Type t = Type.getType(desc);
    boolean isPrimitive = isPrimitive(t);
    boolean isDSOFinal = isRootDSOFinal(name, isPrimitive);

    String rootName = rootNameFor(spec.getClassNameSlashes(), name);
    String targetType = isPrimitive ? ByteCodeUtil.sortToWrapperName(t.getSort()) : convertToCheckCastDesc(desc);

    boolean isStatic = Modifier.isStatic(methodAccess);

    Label l1 = new Label();
    Label l3 = new Label();
    Label l5 = new Label();
    Label l6 = new Label();
    Label l7 = new Label();
    Label l8 = new Label();

    try {
      MethodVisitor mv = cv.visitMethod(methodAccess, ByteCodeUtil.fieldGetterMethod(name), "()" + desc, null, null);

      if (isDSOFinal) {
        callGetFieldInsn(isStatic, name, desc, mv);
        if (isPrimitive) {
          addPrimitiveTypeZeroCompare(mv, t, l1);
        } else {
          mv.visitJumpInsn(IFNONNULL, l1);
        }
      }

      callTCBeginWithLockName(rootName, LockLevel.WRITE, mv);

      mv.visitLabel(l3);
      mv.visitLdcInsn(rootName);
      spec.getManagerHelper().callManagerMethod("lookupRoot", mv);
      mv.visitVarInsn(ASTORE, 1);

      mv.visitVarInsn(ALOAD, 1);
      mv.visitJumpInsn(IFNULL, l5);

      checkReturnObjectType(name, rootName, targetType, 1, l6, mv);

      mv.visitLabel(l6);
      callPutFieldInsn(isStatic, t, 1, name, desc, mv);
      mv.visitJumpInsn(GOTO, l5);

      mv.visitLabel(l7);
      mv.visitVarInsn(ASTORE, 3);
      mv.visitJumpInsn(JSR, l8);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitInsn(ATHROW);

      mv.visitLabel(l8);
      mv.visitVarInsn(ASTORE, 2);
      callTCCommitWithLockName(rootName, mv);
      mv.visitVarInsn(RET, 2);

      mv.visitLabel(l5);
      mv.visitJumpInsn(JSR, l8);

      mv.visitLabel(l1);
      callGetFieldInsn(isStatic, name, desc, mv);
      mv.visitInsn(t.getOpcode(IRETURN));
      mv.visitTryCatchBlock(l3, l7, l7, null);
      mv.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private boolean isVolatile(int access, String fieldName) {
    return getTransparencyClassSpec().isVolatile(access, spec.getClassInfo(), fieldName);
  }

  private void createInstrumentedGetter(int methodAccess, int fieldAccess, String name, String desc) {
    try {
      Assert.eval(!getTransparencyClassSpec().isLogical());
      boolean isVolatile = isVolatile(fieldAccess, name);

      String gDesc = "()" + desc;
      MethodVisitor gv = this.visitMethod(methodAccess, ByteCodeUtil.fieldGetterMethod(name), gDesc, null, null);
      Type t = Type.getType(desc);

      getManaged(gv);
      gv.visitInsn(DUP);
      gv.visitVarInsn(ASTORE, 3);

      Label l0 = new Label();
      gv.visitJumpInsn(IFNULL, l0);

      if (isVolatile) {
        callVolatileBegin(spec.getClassNameDots() + '.' + name, LockLevel.READ, gv);
      }

      gv.visitVarInsn(ALOAD, 3);
      gv.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "getResolveLock", "()Ljava/lang/Object;");
      gv.visitInsn(DUP);
      gv.visitVarInsn(ASTORE, 2);
      gv.visitInsn(MONITORENTER);

      Label l1 = new Label();
      gv.visitLabel(l1);

      gv.visitVarInsn(ALOAD, 0);
      gv.visitFieldInsn(GETFIELD, spec.getClassNameSlashes(), name, desc);
      Label l5 = new Label();
      gv.visitJumpInsn(IFNONNULL, l5);
      gv.visitVarInsn(ALOAD, 3);
      gv.visitLdcInsn(spec.getClassNameDots() + '.' + name);
      gv.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "resolveReference", "(Ljava/lang/String;)V");
      gv.visitLabel(l5);

      Label l2 = new Label();

      gv.visitVarInsn(ALOAD, 0);
      gv.visitFieldInsn(GETFIELD, spec.getClassNameSlashes(), name, desc);
      gv.visitVarInsn(ALOAD, 2);

      gv.visitInsn(MONITOREXIT);
      if (isVolatile) {
        callVolatileCommit(spec.getClassNameDots() + "." + name, gv);
      }
      gv.visitInsn(ARETURN);
      gv.visitJumpInsn(GOTO, l0);

      gv.visitLabel(l2);
      gv.visitVarInsn(ALOAD, 2);

      gv.visitInsn(MONITOREXIT);
      if (isVolatile) {
        callVolatileCommit(spec.getClassNameDots() + "." + name, gv);
      }
      gv.visitInsn(ATHROW);

      gv.visitLabel(l0);
      gv.visitVarInsn(ALOAD, 0);
      gv.visitFieldInsn(GETFIELD, spec.getClassNameSlashes(), name, desc);
      gv.visitInsn(t.getOpcode(IRETURN));

      gv.visitTryCatchBlock(l1, l2, l2, null);

      gv.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void getManaged(MethodVisitor mv) {
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, spec.getClassNameSlashes(), MANAGED_METHOD, "()" + MANAGED_FIELD_TYPE);
  }

  private void createPlainSetter(int methodAccess, int fieldAccess, String name, String desc) {
    boolean isVolatile = isVolatile(fieldAccess, name);

    String sDesc = "(" + desc + ")V";
    MethodVisitor scv = cv.visitMethod(methodAccess, ByteCodeUtil.fieldSetterMethod(name), sDesc, null, null);
    Type t = Type.getType(desc);

    Label l4 = new Label();

    if (isVolatile) {
      getManaged(scv);
      scv.visitInsn(DUP);
      scv.visitVarInsn(ASTORE, 2);
      scv.visitJumpInsn(IFNULL, l4);

      Label l0 = new Label();
      Label l1 = new Label();
      Label l2 = new Label();
      scv.visitTryCatchBlock(l0, l1, l2, null);
      scv.visitLabel(l0);

      callVolatileBegin(spec.getClassNameDots() + "." + name, LockLevel.WRITE, scv);

      Label l6 = new Label();
      scv.visitJumpInsn(JSR, l6);
      scv.visitLabel(l1);
      ByteCodeUtil.pushThis(scv);
      scv.visitVarInsn(t.getOpcode(ILOAD), 1);

      scv.visitFieldInsn(PUTFIELD, spec.getClassNameSlashes(), name, desc);
      scv.visitInsn(RETURN);
      scv.visitLabel(l2);
      scv.visitVarInsn(ASTORE, 2);
      scv.visitJumpInsn(JSR, l6);
      scv.visitVarInsn(ALOAD, 2);
      scv.visitInsn(ATHROW);
      scv.visitLabel(l6);
      scv.visitVarInsn(ASTORE, 1);
      callVolatileCommit(spec.getClassNameDots() + "." + name, scv);

      scv.visitVarInsn(RET, 1);
    }

    scv.visitLabel(l4);
    ByteCodeUtil.pushThis(scv);
    scv.visitVarInsn(t.getOpcode(ILOAD), 1);

    scv.visitFieldInsn(PUTFIELD, spec.getClassNameSlashes(), name, desc);
    scv.visitInsn(RETURN);
    scv.visitMaxs(0, 0);
  }

  private void createInstrumentedSetter(int methodAccess, int fieldAccess, String name, String desc) {
    try {
      Type t = Type.getType(desc);
      if (isRoot(methodAccess, name)) {
        createObjectSetter(methodAccess, fieldAccess, name, desc);
      }
      // if (((t.getSort() == Type.OBJECT) || (t.getSort() == Type.ARRAY)) && !isLiteral(desc)) {
      else if (((t.getSort() == Type.OBJECT) || (t.getSort() == Type.ARRAY)) && !isPrimitive(t)) {
        createObjectSetter(methodAccess, fieldAccess, name, desc);
      } else {
        createLiteralSetter(methodAccess, fieldAccess, name, desc);
      }
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void createObjectSetter(int methodAccess, int fieldAccess, String name, String desc) {
    try {
      if (isRoot(methodAccess, name)) {
        boolean isStaticRoot = Modifier.isStatic(methodAccess);
        if (instrumentationLogger.rootInsertion()) {
          instrumentationLogger.rootInserted(spec.getClassNameDots(), name, desc, isStaticRoot);
        }

        createRootSetter(methodAccess, name, desc, isStaticRoot);
      } else {
        createObjectFieldSetter(methodAccess, fieldAccess, name, desc);
      }
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private boolean isRootDSOFinal(String name, boolean isPrimitive) {
    return spec.getTransparencyClassSpec().isRootDSOFinal(name, isPrimitive);
  }

  private void createRootSetter(int methodAccess, String name, String desc, boolean isStatic) {
    Type t = Type.getType(desc);
    boolean isPrimitive = isPrimitive(t);
    boolean isDSOFinal = isRootDSOFinal(name, isPrimitive);

    try {
      String sDesc = "(" + desc + ")V";
      String targetType = isPrimitive ? ByteCodeUtil.sortToWrapperName(t.getSort()) : convertToCheckCastDesc(desc);
      MethodVisitor scv = cv.visitMethod(methodAccess, ByteCodeUtil.fieldSetterMethod(name), sDesc, null, null);

      Label tryStart = new Label();
      Label end = new Label();
      Label normalExit = new Label();
      Label finallyStart = new Label();
      Label exceptionHandler = new Label();

      final int rootInstance = isStatic ? 0 : 1;

      if (!isPrimitive) {
        scv.visitVarInsn(ALOAD, rootInstance);
        scv.visitJumpInsn(IFNULL, end); // Always ignore request to set roots to null
      }

      String rootName = rootNameFor(spec.getClassNameSlashes(), name);
      callTCBeginWithLockName(rootName, LockLevel.WRITE, scv);

      scv.visitLabel(tryStart);

      scv.visitLdcInsn(rootName);
      if (isPrimitive) {
        ByteCodeUtil.addTypeSpecificParameterLoad(scv, t, rootInstance);
      } else {
        scv.visitVarInsn(ALOAD, rootInstance);
      }
      if (isDSOFinal) {
        spec.getManagerHelper().callManagerMethod("lookupOrCreateRoot", scv);
      } else {
        spec.getManagerHelper().callManagerMethod("createOrReplaceRoot", scv);
      }

      int localVar = rootInstance + 1;
      scv.visitVarInsn(ASTORE, localVar);

      Label l0 = new Label();
      checkReturnObjectType(name, rootName, targetType, localVar, l0, scv);

      scv.visitLabel(l0);
      callPutFieldInsn(isStatic, t, localVar, name, desc, scv);
      scv.visitJumpInsn(GOTO, normalExit);

      scv.visitLabel(exceptionHandler);
      scv.visitVarInsn(ASTORE, 3);
      scv.visitJumpInsn(JSR, finallyStart);
      scv.visitVarInsn(ALOAD, 3);
      scv.visitInsn(ATHROW);

      scv.visitLabel(finallyStart);
      scv.visitVarInsn(ASTORE, 2);
      callTCCommitWithLockName(rootName, scv);
      scv.visitVarInsn(RET, 2);

      scv.visitLabel(normalExit);
      scv.visitJumpInsn(JSR, finallyStart);
      scv.visitLabel(end);
      scv.visitInsn(RETURN);
      scv.visitTryCatchBlock(tryStart, exceptionHandler, exceptionHandler, null);
      scv.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void callGetFieldInsn(boolean isStatic, String name, String desc, MethodVisitor mv) {
    int getInsn = isStatic ? GETSTATIC : GETFIELD;

    if (!isStatic) ByteCodeUtil.pushThis(mv);
    mv.visitFieldInsn(getInsn, spec.getClassNameSlashes(), name, desc);
  }

  private void callPutFieldInsn(boolean isStatic, Type targetType, int localVar, String name, String desc,
                                MethodVisitor mv) {
    int putInsn = isStatic ? PUTSTATIC : PUTFIELD;

    if (!isStatic) ByteCodeUtil.pushThis(mv);
    mv.visitVarInsn(ALOAD, localVar);

    if (isPrimitive(targetType)) {
      mv.visitTypeInsn(CHECKCAST, ByteCodeUtil.sortToWrapperName(targetType.getSort()));
      mv.visitMethodInsn(INVOKEVIRTUAL, ByteCodeUtil.sortToWrapperName(targetType.getSort()), ByteCodeUtil
          .sortToPrimitiveMethodName(targetType.getSort()), "()" + desc);
    } else {
      mv.visitTypeInsn(CHECKCAST, convertToCheckCastDesc(desc));
    }

    mv.visitFieldInsn(putInsn, spec.getClassNameSlashes(), name, desc);
  }

  private void generateCodeForVolatileTransactionBegin(Label l1, Label l2, Label l3, Label l4, String fieldName,
                                                       int lockLevel, MethodVisitor scv) {
    scv.visitTryCatchBlock(l4, l1, l1, null);
    scv.visitTryCatchBlock(l2, l3, l1, null);
    scv.visitLabel(l4);
    callVolatileBegin(fieldName, lockLevel, scv);
  }

  private void generateCodeForVolativeTransactionCommit(Label l1, Label l2, MethodVisitor scv, int newVar1,
                                                        int newVar2, String fieldName) {
    scv.visitJumpInsn(GOTO, l2);
    scv.visitLabel(l1);
    scv.visitVarInsn(ASTORE, newVar2);
    Label l5 = new Label();
    scv.visitJumpInsn(JSR, l5);
    scv.visitVarInsn(ALOAD, newVar2);
    scv.visitInsn(ATHROW);
    scv.visitLabel(l5);
    scv.visitVarInsn(ASTORE, newVar1);
    callVolatileCommit(fieldName, scv);
    scv.visitVarInsn(RET, newVar1);
    scv.visitLabel(l2);
    scv.visitJumpInsn(JSR, l5);
  }

  private void createObjectFieldSetter(int methodAccess, int fieldAccess, String name, String desc) {
    try {
      boolean isVolatile = isVolatile(fieldAccess, name);
      Label l1 = new Label();
      Label l2 = new Label();
      Label l4 = new Label();

      // generates setter method
      String sDesc = "(" + desc + ")V";
      MethodVisitor scv = cv.visitMethod(methodAccess, ByteCodeUtil.fieldSetterMethod(name), sDesc, null, null);
      getManaged(scv);
      scv.visitInsn(DUP);
      scv.visitVarInsn(ASTORE, 2);
      Label l0 = new Label();
      scv.visitJumpInsn(IFNULL, l0);

      if (isVolatile) {
        generateCodeForVolatileTransactionBegin(l1, l2, l0, l4, spec.getClassNameDots() + "." + name, LockLevel.WRITE,
                                                scv);
      }

      scv.visitVarInsn(ALOAD, 2);
      scv.visitLdcInsn(spec.getClassNameDots());
      scv.visitLdcInsn(spec.getClassNameDots() + "." + name);
      scv.visitVarInsn(ALOAD, 1);
      scv.visitInsn(ICONST_M1);
      scv.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", "objectFieldChanged",
                          "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;I)V");

      if (isVolatile) {
        generateCodeForVolativeTransactionCommit(l1, l2, scv, 3, 4, spec.getClassNameDots() + "." + name);
      }

      scv.visitLabel(l0);
      scv.visitVarInsn(ALOAD, 0);
      scv.visitVarInsn(ALOAD, 1);
      scv.visitFieldInsn(PUTFIELD, spec.getClassNameSlashes(), name, desc);
      scv.visitInsn(RETURN);
      scv.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void createLiteralSetter(int methodAccess, int fieldAccess, String name, String desc) {
    try {
      // generates setter method
      boolean isVolatile = isVolatile(fieldAccess, name);

      Label l1 = new Label();
      Label l2 = new Label();
      Label l4 = new Label();

      String sDesc = "(" + desc + ")V";
      Type t = Type.getType(desc);

      MethodVisitor mv = cv.visitMethod(methodAccess, ByteCodeUtil.fieldSetterMethod(name), sDesc, null, null);
      getManaged(mv);
      mv.visitInsn(DUP);
      mv.visitVarInsn(ASTORE, 1 + t.getSize());
      Label l0 = new Label();
      mv.visitJumpInsn(IFNULL, l0);

      if (isVolatile) {
        generateCodeForVolatileTransactionBegin(l1, l2, l0, l4, spec.getClassNameDots() + "." + name, LockLevel.WRITE,
                                                mv);
      }

      mv.visitVarInsn(ALOAD, 1 + t.getSize());
      mv.visitLdcInsn(spec.getClassNameDots());
      mv.visitLdcInsn(spec.getClassNameDots() + "." + name);
      mv.visitVarInsn(t.getOpcode(ILOAD), 1);
      mv.visitInsn(ICONST_M1);
      String method = ByteCodeUtil.codeToName(desc) + "FieldChanged";
      mv.visitMethodInsn(INVOKEINTERFACE, "com/tc/object/TCObject", method, "(Ljava/lang/String;Ljava/lang/String;"
                                                                            + desc + "I)V");

      if (isVolatile) {
        generateCodeForVolativeTransactionCommit(l1, l2, mv, 2 + t.getSize(), 3 + t.getSize(), spec.getClassNameDots()
                                                                                               + "." + name);
      }

      mv.visitLabel(l0);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(t.getOpcode(ILOAD), 1);
      mv.visitFieldInsn(PUTFIELD, spec.getClassNameSlashes(), name, desc);
      mv.visitInsn(RETURN);
      mv.visitMaxs(0, 0);
    } catch (RuntimeException e) {
      handleInstrumentationException(e);
    } catch (Error e) {
      handleInstrumentationException(e);
      throw e;
    }
  }

  private void callTCMonitorExit(int callingMethodModifier, MethodVisitor c) {
    Assert.eval("Can't call tc monitorenter from a static method.", !Modifier.isStatic(callingMethodModifier));
    ByteCodeUtil.pushThis(c);
    spec.getManagerHelper().callManagerMethod("monitorExit", c);
  }

  private void callTCMonitorEnter(int callingMethodModifier, LockDefinition def, MethodVisitor c) {
    Assert.eval("Can't call tc monitorexit from a static method.", !Modifier.isStatic(callingMethodModifier));
    ByteCodeUtil.pushThis(c);
    c.visitLdcInsn(new Integer(def.getLockLevelAsInt()));
    spec.getManagerHelper().callManagerMethod("monitorEnter", c);
  }

  private void addPrimitiveTypeZeroCompare(MethodVisitor mv, Type type, Label notZeroLabel) {
    switch (type.getSort()) {
      case Type.LONG:
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFNE, notZeroLabel);
        break;
      case Type.DOUBLE:
        mv.visitInsn(DCONST_0);
        mv.visitInsn(DCMPL);
        mv.visitJumpInsn(IFNE, notZeroLabel);
        break;
      case Type.FLOAT:
        mv.visitInsn(FCONST_0);
        mv.visitInsn(FCMPL);
        mv.visitJumpInsn(IFNE, notZeroLabel);
        break;
      default:
        mv.visitJumpInsn(IFNE, notZeroLabel);
    }
  }
}
