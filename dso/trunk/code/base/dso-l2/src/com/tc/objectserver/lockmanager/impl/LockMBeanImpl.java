/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.lockmanager.impl;

import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.impl.LockHolder;
import com.tc.objectserver.lockmanager.api.LockMBean;
import com.tc.objectserver.lockmanager.api.ServerLockRequest;
import com.tc.objectserver.lockmanager.api.Waiter;

import java.io.Serializable;

public class LockMBeanImpl implements LockMBean, Serializable {

  private final String              lockName;
  private final LockHolder[]        holders;
  private final ServerLockRequest[] pendingRequests;
  private final Waiter[]            waiters;

  public LockMBeanImpl(LockID lockID, LockHolder[] holders, ServerLockRequest[] requests, Waiter[] waiters) {
    this.lockName = lockID.asString();
    this.holders = holders;
    this.pendingRequests = requests;
    this.waiters = waiters;
  }

  public String getLockName() {
    return this.lockName;
  }

  public LockHolder[] getHolders() {
    return this.holders;
  }

  public ServerLockRequest[] getPendingRequests() {
    return this.pendingRequests;
  }

  public Waiter[] getWaiters() {
    return this.waiters;
  }

  public String toString() {
    return getLockName();
  }

}
