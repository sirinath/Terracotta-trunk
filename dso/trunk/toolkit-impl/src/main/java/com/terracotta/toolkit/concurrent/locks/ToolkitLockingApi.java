/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.concurrent.locks;

import org.terracotta.toolkit.internal.concurrent.locks.ToolkitLockTypeInternal;

import com.tc.object.ObjectID;
import com.tc.object.bytecode.PlatformService;
import com.terracotta.toolkit.object.ToolkitObjectType;

import java.util.concurrent.TimeUnit;

public class ToolkitLockingApi {

  private static final String STRING_LOCK_ID_PREFIX      = "string-lock-prefix";
  private static final String TOOLKIT_OBJECT_LOCK_PREFIX = "toolkit-object-lock-prefix";
  private static final String DELIMITER                  = "|";

  private ToolkitLockingApi() {
    // private
  }

  // used just for txn boundaries
  public static UnnamedToolkitLock createConcurrentTransactionLock(String name, PlatformService service) {
    return new UnnamedToolkitLock(service, name, ToolkitLockTypeInternal.CONCURRENT);
  }

  // locks created for name based toolkitObjectTypes
  public static UnnamedToolkitLock createUnnamedLocked(ToolkitObjectType toolkitObjectType, String stringLockId,
                                                       ToolkitLockTypeInternal lockType, PlatformService service) {
    return new UnnamedToolkitLock(service, generateStringLockId(toolkitObjectType, stringLockId), lockType);
  }

  // RWLsocks created for toolkitLockedObjects
  public static UnnamedToolkitReadWriteLock createUnnamedReadWriteLock(ToolkitObjectType type, ObjectID oid,
                                                                       PlatformService service) {
    return new UnnamedToolkitReadWriteLock(service, generateStringLockId(type, String.valueOf(oid.toLong())));
  }

  // RWLsocks created for toolkitObject - used for ToolkitReadWriteLock
  public static UnnamedToolkitReadWriteLock createUnnamedReadWriteLock(ToolkitObjectType type, String name,
                                                                       PlatformService service) {
    return new UnnamedToolkitReadWriteLock(service, generateStringLockId(type, name));
  }

  // used for servermap keys
  public static UnnamedToolkitReadWriteLock createUnnamedReadWriteLock(long longLockId, PlatformService service) {
    return new UnnamedToolkitReadWriteLock(service, longLockId);
  }

  public static void lock(ToolkitLockDetail lockDetail, PlatformService service) {
    service.beginLock(lockDetail.getLockId(), lockDetail.getLockLevel());
  }

  public static void unlock(ToolkitLockDetail lockDetail, PlatformService service) {
    service.commitLock(lockDetail.getLockId(), lockDetail.getLockLevel());
  }

  public static void lockInterruptibly(ToolkitLockDetail lockDetail, PlatformService service)
      throws InterruptedException {
    service.beginLockInterruptibly(lockDetail.getLockId(), lockDetail.getLockLevel());
  }

  public static boolean tryLock(ToolkitLockDetail lockDetail, PlatformService service) {
    return service.tryBeginLock(lockDetail.getLockId(), lockDetail.getLockLevel());
  }

  public static boolean tryLock(ToolkitLockDetail lockDetail, long time, TimeUnit unit, PlatformService service)
      throws InterruptedException {
    return service.tryBeginLock(lockDetail.getLockId(), lockDetail.getLockLevel(), time, unit);
  }

  public static boolean isHeldByCurrentThread(ToolkitLockDetail lockDetail, PlatformService service) {
    return service.isHeldByCurrentThread(lockDetail.getLockId(), lockDetail.getLockLevel());
  }

  public static void lockIdWait(ToolkitLockDetail lockDetail, PlatformService service) throws InterruptedException {
    // 0 timeout means infinite wait
    service.lockIDWait(lockDetail.getLockId(), 0, TimeUnit.MILLISECONDS);
  }

  public static void lockIdWait(ToolkitLockDetail lockDetail, long time, TimeUnit unit, PlatformService service)
      throws InterruptedException {
    // 0 timeout means infinite wait
    service.lockIDWait(lockDetail.getLockId(), time, unit);
  }

  public static void lockIdNotify(ToolkitLockDetail lockDetail, PlatformService service) {
    service.lockIDNotify(lockDetail.getLockId());
  }

  public static void lockIdNotifyAll(ToolkitLockDetail lockDetail, PlatformService service) {
    service.lockIDNotifyAll(lockDetail.getLockId());
  }

  public static void lock(long longLockId, ToolkitLockTypeInternal lockType, PlatformService service) {
    doBeginLock(longLockId, lockType, service);
  }

  public static void unlock(long longLockId, ToolkitLockTypeInternal lockType, PlatformService service) {
    doCommitLock(longLockId, lockType, service);
  }

  public static void lock(String stringLockName, ToolkitLockTypeInternal lockType, PlatformService service) {
    doBeginLock(generateStringLockId(stringLockName), lockType, service);
  }

  public static void unlock(String stringLockName, ToolkitLockTypeInternal lockType, PlatformService service) {
    doCommitLock(generateStringLockId(stringLockName), lockType, service);
  }

  public static void lock(ToolkitObjectType toolkitObjectType, String toolkitObjectName,
                          ToolkitLockTypeInternal lockType, PlatformService service) {
    doBeginLock(generateStringLockId(toolkitObjectType, toolkitObjectName), lockType, service);
  }

  public static void unlock(ToolkitObjectType toolkitObjectType, String toolkitObjectName,
                            ToolkitLockTypeInternal lockType, PlatformService service) {
    doCommitLock(generateStringLockId(toolkitObjectType, toolkitObjectName), lockType, service);
  }

  private static void doBeginLock(Object lockId, ToolkitLockTypeInternal lockType, PlatformService service) {
    service.beginLock(lockId, LockingUtils.translate(lockType));
  }

  private static void doCommitLock(Object lockId, ToolkitLockTypeInternal lockType, PlatformService service) {
    service.commitLock(lockId, LockingUtils.translate(lockType));
  }

  private static String generateStringLockId(String stringLockName) {
    return STRING_LOCK_ID_PREFIX + DELIMITER + stringLockName;
  }

  private static String generateStringLockId(ToolkitObjectType toolkitObjectType, String toolkitObjectName) {
    return TOOLKIT_OBJECT_LOCK_PREFIX + DELIMITER + toolkitObjectType.name() + DELIMITER + toolkitObjectName;
  }

}
