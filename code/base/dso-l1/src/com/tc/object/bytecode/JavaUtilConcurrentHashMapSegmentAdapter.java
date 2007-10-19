/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.bytecode;

import com.tc.asm.ClassAdapter;
import com.tc.asm.ClassVisitor;
import com.tc.asm.Label;
import com.tc.asm.MethodAdapter;
import com.tc.asm.MethodVisitor;
import com.tc.asm.Opcodes;
import com.tc.asm.Type;
import com.tc.object.SerializationUtil;

public class JavaUtilConcurrentHashMapSegmentAdapter extends ClassAdapter implements Opcodes {
  private final static String PARENT_CONCURRENT_HASH_MAP_FIELD_TYPE = "Ljava/util/concurrent/ConcurrentHashMap;";

  private final static String PARENT_CONCURRENT_HASH_MAP_FIELD_NAME = "parentMap";

  public final static String TC_PUT_METHOD_NAME                     = ByteCodeUtil.TC_METHOD_PREFIX + "put";
  public final static String TC_PUT_METHOD_DESC                     = "(Ljava/lang/Object;ILjava/lang/Object;Z)Ljava/lang/Object;";

  public final static String TC_ORIG_PUT_METHOD_NAME                = ByteCodeUtil.TC_METHOD_PREFIX + "origPut";
  public final static String TC_ORIG_PUT_METHOD_DESC                = "(Ljava/lang/Object;ILjava/lang/Object;Z)Ljava/lang/Object;";

  public final static String TC_ORIG_REMOVE_METHOD_NAME             = ByteCodeUtil.TC_METHOD_PREFIX + "origRemove";
  public final static String TC_ORIG_REMOVE_METHOD_DESC             = "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;";

  public final static String TC_NULLOLDVALUE_REMOVE_METHOD_NAME     = ByteCodeUtil.TC_METHOD_PREFIX + "nulloldvalueRemove";
  public final static String TC_NULLOLDVALUE_REMOVE_METHOD_DESC     = "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;";

  private final static String TC_CLEAR_METHOD_NAME                  = ByteCodeUtil.TC_METHOD_PREFIX + "clear";
  private final static String TC_CLEAR_METHOD_DESC                  = "()V";

  public final static String TC_READLOCK_METHOD_NAME                = ByteCodeUtil.TC_METHOD_PREFIX + "readLock";
  public final static String TC_READLOCK_METHOD_DESC                = "()V";

  public final static String TC_READUNLOCK_METHOD_NAME              = ByteCodeUtil.TC_METHOD_PREFIX + "readUnlock";
  public final static String TC_READUNLOCK_METHOD_DESC              = "()V";

  public final static String INITIAL_TABLE_METHOD_NAME              = "initTable";
  public final static String INITIAL_TABLE_METHOD_DESC              = "(I)V";

  public final static String CONCURRENT_HASH_MAP_SEGMENT_SLASH      = "java/util/concurrent/ConcurrentHashMap$Segment";
  public final static String INIT_DESC                              = "(" + PARENT_CONCURRENT_HASH_MAP_FIELD_TYPE
                                                                      + "IF)V";

  public JavaUtilConcurrentHashMapSegmentAdapter(ClassVisitor cv) {
    super(cv);
  }

  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
    super.visit(version, access, name, signature, "java/util/concurrent/locks/ReentrantReadWriteLock", interfaces);
  }

  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    if ("get".equals(name) && "(Ljava/lang/Object;I)Ljava/lang/Object;".equals(desc)) {
      return new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, addWrapperMethod(access, name, desc, signature, exceptions), true);
    } else if ("containsValue".equals(name) && "(Ljava/lang/Object;)Z".equals(desc)) {
      return new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, super.visitMethod(access, name, desc, signature, exceptions), true);
    } else if ("containsKey".equals(name) && "(Ljava/lang/Object;I)Z".equals(desc)) {
      return new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, addWrapperMethod(access, name, desc, signature, exceptions), false);
    } else {
      String description = desc;
      if ("<init>".equals(name) && "(IF)V".equals(desc)) {
        description = INIT_DESC;
      }
      
      MethodVisitor mv = super.visitMethod(access, name, description, signature, exceptions);
      if ("put".equals(name) && "(Ljava/lang/Object;ILjava/lang/Object;Z)Ljava/lang/Object;".equals(desc)) {
        return new MulticastMethodVisitor(new MethodVisitor[] {
          // rename the original, totally un-instrumented put method so that it can be used by the CHM applicator
          super.visitMethod(ACC_SYNTHETIC, TC_ORIG_PUT_METHOD_NAME, TC_ORIG_PUT_METHOD_DESC, null, null),
          // adapt the put method
          new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, new PutMethodAdapter(mv), false),
          // This creates the identical copy of the put() method of Segments except that it does not lock and does
          // not invoke the instrumented version of the logicalInvoke(). The reason that it does not require
          // locking is because it is called from the __tc_rehash() instrumented method and the lock is grabbed
          // at the __tc_rehash() method already.
          new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, new RemoveLockUnlockMethodAdapter(super.visitMethod(ACC_SYNTHETIC, TC_PUT_METHOD_NAME, TC_PUT_METHOD_DESC, null, null)), false)});
      } else if ("remove".equals(name) && "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
        return new MulticastMethodVisitor(new MethodVisitor[] {
          // rename the original, totally un-instrumented remove method so that it can be used by the CHM applicator
          super.visitMethod(ACC_SYNTHETIC, TC_ORIG_REMOVE_METHOD_NAME, TC_ORIG_REMOVE_METHOD_DESC, null, null),
          // create an adapted remove method that returns nulls for the old values and thus doesn't fault them in
          new RemoveNullOldValueMethodAdapter(new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, new RemoveMethodAdapter(super.visitMethod(ACC_SYNTHETIC, TC_NULLOLDVALUE_REMOVE_METHOD_NAME, TC_NULLOLDVALUE_REMOVE_METHOD_DESC, null, null)), false)),
          // adapt the remove method
          new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, new RemoveMethodAdapter(mv), false)});
      }

      final MethodVisitor visitor;
      if ("clear".equals(name) && "()V".equals(desc)) {
        visitor = new MulticastMethodVisitor(new MethodVisitor[] {
          new ClearMethodAdapter(mv),
          // Again, this method does not require locking as it is called by __tc_rehash() which grabs the lock already.
          new RemoveLockUnlockMethodAdapter(super.visitMethod(ACC_SYNTHETIC, TC_CLEAR_METHOD_NAME, TC_CLEAR_METHOD_DESC, null, null))});
      } else if ("replace".equals(name) && "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
        visitor = new ReplaceMethodAdapter(mv);
      } else if ("replace".equals(name) && "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;)Z".equals(desc)) {
        visitor = new ReplaceIfValueEqualMethodAdapter(mv);
      } else if ("<init>".equals(name) && "(IF)V".equals(desc)) {
        visitor = new InitMethodAdapter(mv);
      } else if ("readValueUnderLock".equals(name) && "(Ljava/util/concurrent/ConcurrentHashMap$HashEntry;)Ljava/lang/Object;".equals(desc)) {
        visitor = new ReadValueUnderLockMethodAdapter(mv);
      } else {
        visitor = mv;
      }
      
      return new JavaUtilConcurrentHashMapLazyValuesMethodAdapter(access, desc, visitor, false);
    }
  }
  
  private String getNewName(String methodName) {
    return ByteCodeUtil.TC_METHOD_PREFIX + methodName;
  }
  
  private MethodVisitor addWrapperMethod(int access, String name, String desc, String signature, String[] exceptions) {
    createWrapperMethod(access, name, desc, signature, exceptions);
    return cv.visitMethod(ACC_PRIVATE, getNewName(name), desc, signature, exceptions);
  }
  
  private void createWrapperMethod(int access, String name, String desc, String signature, String[] exceptions) {
    Type[] params = Type.getArgumentTypes(desc);
    Type returnType = Type.getReturnType(desc);

    MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
    mv.visitCode();
    Label l0 = new Label();
    Label l1 = new Label();
    Label l2 = new Label();
    mv.visitTryCatchBlock(l0, l1, l2, null);
    Label l3 = new Label();
    mv.visitLabel(l3);
    mv.visitLineNumber(280, l3);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isManaged", "(Ljava/lang/Object;)Z");
    mv.visitVarInsn(ISTORE, 3);
    Label l4 = new Label();
    mv.visitLabel(l4);
    mv.visitLineNumber(281, l4);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IFEQ, l0);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, TC_READLOCK_METHOD_NAME, TC_READLOCK_METHOD_DESC);
    mv.visitLabel(l0);
    mv.visitLineNumber(283, l0);
    mv.visitVarInsn(ALOAD, 0);
    for (int i = 0; i < params.length; i++) {
      mv.visitVarInsn(params[i].getOpcode(ILOAD), i + 1);
    }
    mv.visitMethodInsn(INVOKESPECIAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, getNewName(name), desc);
    mv.visitVarInsn(returnType.getOpcode(ISTORE), 5);
    mv.visitLabel(l1);
    mv.visitLineNumber(285, l1);
    mv.visitVarInsn(ILOAD, 3);
    Label l5 = new Label();
    mv.visitJumpInsn(IFEQ, l5);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, TC_READUNLOCK_METHOD_NAME, TC_READUNLOCK_METHOD_DESC);
    mv.visitLabel(l5);
    mv.visitLineNumber(283, l5);
    mv.visitVarInsn(returnType.getOpcode(ILOAD), 5);
    mv.visitInsn(returnType.getOpcode(IRETURN));
    mv.visitLabel(l2);
    mv.visitLineNumber(284, l2);
    mv.visitVarInsn(ASTORE, 4);
    Label l6 = new Label();
    mv.visitLabel(l6);
    mv.visitLineNumber(285, l6);
    mv.visitVarInsn(ILOAD, 3);
    Label l7 = new Label();
    mv.visitJumpInsn(IFEQ, l7);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, TC_READUNLOCK_METHOD_NAME, TC_READUNLOCK_METHOD_DESC);
    mv.visitLabel(l7);
    mv.visitLineNumber(286, l7);
    mv.visitVarInsn(ALOAD, 4);
    mv.visitInsn(ATHROW);
    Label l8 = new Label();
    mv.visitLabel(l8);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void visitEnd() {
    createDefaultConstructor();
    createInitTableMethod();
    createLockMethod();
    createUnlockMethod();
    createTCReadLockMethod();
    createTCReadUnlockMethod();
    
    super.visitField(ACC_FINAL + ACC_SYNTHETIC, PARENT_CONCURRENT_HASH_MAP_FIELD_NAME,
                     "Ljava/util/concurrent/ConcurrentHashMap;", null, null);
    super.visitEnd();
  }

  private void createDefaultConstructor() {
    MethodVisitor mv = cv.visitMethod(0, "<init>", "()V", null, null);
    mv.visitCode();
    Label l0 = new Label();
    mv.visitLabel(l0);
    mv.visitLineNumber(232, l0);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/locks/ReentrantReadWriteLock", "<init>", "()V");
    Label l1 = new Label();
    mv.visitLabel(l1);
    mv.visitLineNumber(233, l1);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ICONST_1);
    mv.visitTypeInsn(ANEWARRAY, "java/util/concurrent/ConcurrentHashMap$HashEntry");
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "setTable",
                       "([Ljava/util/concurrent/ConcurrentHashMap$HashEntry;)V");
    Label l2 = new Label();
    mv.visitLabel(l2);
    mv.visitLineNumber(234, l2);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(FCONST_0);
    mv.visitFieldInsn(PUTFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "loadFactor", "F");
    Label l3 = new Label();
    mv.visitLabel(l3);
    mv.visitLineNumber(235, l3);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(ACONST_NULL);
    mv.visitFieldInsn(PUTFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "parentMap",
                      "Ljava/util/concurrent/ConcurrentHashMap;");
    Label l4 = new Label();
    mv.visitLabel(l4);
    mv.visitLineNumber(236, l4);
    mv.visitInsn(RETURN);
    Label l5 = new Label();
    mv.visitLabel(l5);
    mv.visitLocalVariable("this", "Ljava/util/concurrent/ConcurrentHashMap$Segment;",
                          "Ljava/util/concurrent/ConcurrentHashMap<TK;TV;>.Segment<TK;TV;>;", l0, l5, 0);
    mv.visitMaxs(2, 1);
    mv.visitEnd();
  }

  private void createLockMethod() {
    MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "lock", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "writeLock", "()Ljava/util/concurrent/locks/ReentrantReadWriteLock$WriteLock;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/locks/ReentrantReadWriteLock$WriteLock", "lock", "()V");
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }
  
  private void createUnlockMethod() {
    MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, "unlock", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "writeLock", "()Ljava/util/concurrent/locks/ReentrantReadWriteLock$WriteLock;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/locks/ReentrantReadWriteLock$WriteLock", "unlock", "()V");
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }

  private void createTCReadLockMethod() {
    MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, TC_READLOCK_METHOD_NAME, TC_READLOCK_METHOD_DESC, null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "readLock", "()Ljava/util/concurrent/locks/ReentrantReadWriteLock$ReadLock;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/locks/ReentrantReadWriteLock$ReadLock", "lock", "()V");
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }
  
  private void createTCReadUnlockMethod() {
    MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, TC_READUNLOCK_METHOD_NAME, TC_READUNLOCK_METHOD_DESC, null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "readLock", "()Ljava/util/concurrent/locks/ReentrantReadWriteLock$ReadLock;");
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/concurrent/locks/ReentrantReadWriteLock$ReadLock", "unlock", "()V");
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }

  private void createInitTableMethod() {
    MethodVisitor mv = super.visitMethod(ACC_PRIVATE + ACC_FINAL + ACC_SYNTHETIC, INITIAL_TABLE_METHOD_NAME,
                                         INITIAL_TABLE_METHOD_DESC, null, null);
    mv.visitCode();
    ByteCodeUtil.pushThis(mv);
    mv.visitVarInsn(ILOAD, 1);
    mv.visitTypeInsn(ANEWARRAY, "java/util/concurrent/ConcurrentHashMap$HashEntry");
    mv.visitMethodInsn(INVOKEVIRTUAL, CONCURRENT_HASH_MAP_SEGMENT_SLASH, "setTable",
                       "([Ljava/util/concurrent/ConcurrentHashMap$HashEntry;)V");
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
  
  private static class InitMethodAdapter extends MethodAdapter implements Opcodes {
    public InitMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitInsn(int opcode) {
      if (RETURN == opcode) {
        ByteCodeUtil.pushThis(mv);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                          PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");

      }
      super.visitInsn(opcode);
    }

    public void visitVarInsn(int opcode, int var) {
      if (var == 1) {
        var = 2;
      } else if (var == 2) {
        var = 3;
      }
      super.visitVarInsn(opcode, var);
    }

    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
      if (INVOKESPECIAL == opcode
          && "java/util/concurrent/locks/ReentrantLock".equals(owner)
          && "<init>".equals(name) && "()V".equals(desc)) {
        owner = "java/util/concurrent/locks/ReentrantReadWriteLock";
      }

      super.visitMethodInsn(opcode, owner, name, desc);
    }
  }

  private static class PutMethodAdapter extends MethodAdapter implements Opcodes {
    public PutMethodAdapter(MethodVisitor mv) {
      super(mv);
    }
    
    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      super.visitFieldInsn(opcode, owner, name, desc);
      
      if (PUTFIELD == opcode && "java/util/concurrent/ConcurrentHashMap$HashEntry".equals(owner)
          && "value".equals(name) && "Ljava/lang/Object;".equals(desc)) {
        addFoundLogicalInvokePutMethodCall();
      } else if (PUTFIELD == opcode && CONCURRENT_HASH_MAP_SEGMENT_SLASH.equals(owner)
                 && "count".equals(name) && "I".equals(desc)) {
        addNotFoundLogicalInvokePutMethodCall();
      }
    }

    private void addFoundLogicalInvokePutMethodCall() {
      Label notManaged = new Label();
      Label logicalInvokeLabel = new Label();

      mv.visitLabel(logicalInvokeLabel);

      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isManaged", "(Ljava/lang/Object;)Z");
      mv.visitJumpInsn(IFEQ, notManaged);
      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.PUT_SIGNATURE);
      mv.visitInsn(ICONST_2);
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 9);
      mv.visitFieldInsn(GETFIELD, "java/util/concurrent/ConcurrentHashMap$HashEntry", "key", "Ljava/lang/Object;");
      mv.visitInsn(AASTORE);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitInsn(AASTORE);
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
      mv.visitLabel(notManaged);
    }

    private void addNotFoundLogicalInvokePutMethodCall() {
      Label endBlock = new Label();
      Label logicalInvokeLabel = new Label();

      mv.visitLabel(logicalInvokeLabel);

      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isManaged", "(Ljava/lang/Object;)Z");
      mv.visitJumpInsn(IFEQ, endBlock);
      mv.visitVarInsn(ILOAD, 4);
      Label l0 = new Label();
      mv.visitJumpInsn(IFEQ, l0);
      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.PUT_SIGNATURE);
      mv.visitInsn(ICONST_2);
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitInsn(AASTORE);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitInsn(AASTORE);
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
      mv.visitJumpInsn(GOTO, endBlock);
      mv.visitLabel(l0);
      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.PUT_SIGNATURE);
      mv.visitInsn(ICONST_2);
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitInsn(AASTORE);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitInsn(AASTORE);
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
      mv.visitLabel(endBlock);
    }
  }

  private static class RemoveLockUnlockMethodAdapter extends MethodAdapter implements Opcodes {
    public RemoveLockUnlockMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
      if (CONCURRENT_HASH_MAP_SEGMENT_SLASH.equals(owner)
          && ("lock".equals(name) || "unlock".equals(name))
          && "()V".equals(desc)) {
        // insert a POP insertion instead, so that the reference to
        // 'this' is removed from the stack
        mv.visitInsn(POP);
      } else {
        super.visitMethodInsn(opcode, owner, name, desc);
      }
    }
  }

  private static class ReplaceMethodAdapter extends MethodAdapter implements Opcodes {
    public ReplaceMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      super.visitFieldInsn(opcode, owner, name, desc);
      if (PUTFIELD == opcode && "java/util/concurrent/ConcurrentHashMap$HashEntry".equals(owner)
          && "value".equals(name) && "Ljava/lang/Object;".equals(desc)) {
        addLogicalInvokeReplaceMethodCall();
      }
    }

    public void addLogicalInvokeReplaceMethodCall() {
      Label notManaged = new Label();
      ByteCodeUtil.pushThis(this);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isManaged", "(Ljava/lang/Object;)Z");
      mv.visitJumpInsn(IFEQ, notManaged);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.PUT_SIGNATURE);
      mv.visitInsn(ICONST_2);
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitFieldInsn(GETFIELD, "java/util/concurrent/ConcurrentHashMap$HashEntry", "key", "Ljava/lang/Object;");
      mv.visitInsn(AASTORE);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ALOAD, 3);
      mv.visitInsn(AASTORE);
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
      mv.visitLabel(notManaged);
    }
  }

  private static class ReplaceIfValueEqualMethodAdapter extends MethodAdapter implements Opcodes {
    public ReplaceIfValueEqualMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      if (PUTFIELD == opcode && "java/util/concurrent/ConcurrentHashMap$HashEntry".equals(owner)
          && "value".equals(name) && "Ljava/lang/Object;".equals(desc)) {
        addLogicalInvokeReplaceMethodCall();
      }
      super.visitFieldInsn(opcode, owner, name, desc);
    }

    public void addLogicalInvokeReplaceMethodCall() {
      Label notManaged = new Label();
      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isManaged", "(Ljava/lang/Object;)Z");
      mv.visitJumpInsn(IFEQ, notManaged);
      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.PUT_SIGNATURE);
      mv.visitInsn(ICONST_2);
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 5);
      mv.visitFieldInsn(GETFIELD, "java/util/concurrent/ConcurrentHashMap$HashEntry", "key", "Ljava/lang/Object;");
      mv.visitInsn(AASTORE);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitInsn(AASTORE);
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
      mv.visitLabel(notManaged);
    }
  }

  private static class RemoveMethodAdapter extends MethodAdapter implements Opcodes {
    public RemoveMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      super.visitFieldInsn(opcode, owner, name, desc);
      if (PUTFIELD == opcode
          && CONCURRENT_HASH_MAP_SEGMENT_SLASH.equals(owner)
          && "count".equals(name)
          && "I".equals(desc)) {
        addLogicalInvokeRemoveMethodCall();
      }
    }

    public void addLogicalInvokeRemoveMethodCall() {
      Label notManaged = new Label();
      Label logicalInvokeLabel = new Label();

      mv.visitLabel(logicalInvokeLabel);

      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "isManaged", "(Ljava/lang/Object;)Z");
      mv.visitJumpInsn(IFEQ, notManaged);
      mv.visitVarInsn(ALOAD, 0);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.REMOVE_SIGNATURE);
      mv.visitInsn(ICONST_1);
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ALOAD, 8);
      mv.visitFieldInsn(GETFIELD, "java/util/concurrent/ConcurrentHashMap$HashEntry", "key", "Ljava/lang/Object;");
      mv.visitInsn(AASTORE);
      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
      mv.visitLabel(notManaged);
    }
  }

  private static class RemoveNullOldValueMethodAdapter extends MethodAdapter implements Opcodes {
    public RemoveNullOldValueMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
      if (GETFIELD == opcode
          && "java/util/concurrent/ConcurrentHashMap$HashEntry".equals(owner)
          && "value".equals(name)
          && "Ljava/lang/Object;".equals(desc)) {
        super.visitInsn(POP);
        super.visitInsn(ACONST_NULL);
      } else {
        super.visitFieldInsn(opcode, owner, name, desc);
      }
    }
  }

  private static class ClearMethodAdapter extends MethodAdapter implements Opcodes {
    public ClearMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
      super.visitMethodInsn(opcode, owner, name, desc);
      if ("lock".equals(name) && "()V".equals(desc)) {
        addLogicalInvokeMethodCall();
      }
    }

    public void addLogicalInvokeMethodCall() {
      ByteCodeUtil.pushThis(mv);
      mv.visitFieldInsn(GETFIELD, CONCURRENT_HASH_MAP_SEGMENT_SLASH,
                        PARENT_CONCURRENT_HASH_MAP_FIELD_NAME, "Ljava/util/concurrent/ConcurrentHashMap;");
      mv.visitLdcInsn(SerializationUtil.CLEAR_SIGNATURE);

      mv.visitLdcInsn(new Integer(0));
      mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

      mv.visitMethodInsn(INVOKESTATIC, "com/tc/object/bytecode/ManagerUtil", "logicalInvoke",
                         "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V");
    }
  }

  private static class ReadValueUnderLockMethodAdapter extends MethodAdapter implements Opcodes {
    public ReadValueUnderLockMethodAdapter(MethodVisitor mv) {
      super(mv);
    }

    public void visitMethodInsn(int opcode, String owner, String name, String desc) {
      if ("lock".equals(name) && "()V".equals(desc)) {
        name = TC_READLOCK_METHOD_NAME;
      } else if ("unlock".equals(name) && "()V".equals(desc)) {
        name = TC_READUNLOCK_METHOD_NAME;
      }
      super.visitMethodInsn(opcode, owner, name, desc);
    }
  }
}
