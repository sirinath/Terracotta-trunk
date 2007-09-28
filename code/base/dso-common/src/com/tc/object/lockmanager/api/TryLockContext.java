/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.lockmanager.api;

import com.tc.net.groups.ClientID;
import com.tc.object.tx.WaitInvocation;

public class TryLockContext extends WaitContext {
  public TryLockContext() {
    return;
  }

  public TryLockContext(LockID lockID, ClientID clientID, ThreadID threadID, int lockLevel,
                        WaitInvocation waitInvocation) {
    super(lockID, clientID, threadID, lockLevel, waitInvocation);
  }
}
