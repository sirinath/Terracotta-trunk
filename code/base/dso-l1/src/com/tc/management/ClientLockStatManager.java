/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.management;

import com.tc.async.api.Sink;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.net.DSOClientMessageChannel;

public interface ClientLockStatManager {
  public final static ClientLockStatManager NULL_CLIENT_LOCK_STAT_MANAGER = new ClientLockStatManager() {

    public void setLockStatisticsConfig(int traceDepth, int gatherInterval) {
      // do nothing
    }

    public boolean isStatEnabled() {
      return false;
    }

    public void setLockStatisticsEnabled(boolean statEnable) {
      // do nothing
    }

    public void start(DSOClientMessageChannel channel, Sink sink) {
      // do nothing
    }

    public void recordLockRequested(LockID lockID, ThreadID threadID, String contextInfo, int numberOfPendingLockRequests) {
      // do nothing
    }

    public void recordLockAwarded(LockID lockID, ThreadID threadID) {
      // do nothing
    }
    
    public void recordLockReleased(LockID lockID, ThreadID threadID) {
      // do nothing
    }
    
    public void recordLockHopped(LockID lockID, ThreadID threadID) {
      // do nothing
    }
    
    public void recordLockRejected(LockID lockID, ThreadID threadID) {
      // do nothing
    }
    
    public void getLockSpecs() {
      // do nothing
    }
  };
  
  public void start(DSOClientMessageChannel channel, Sink sink);
  
  public void recordLockRequested(LockID lockID, ThreadID threadID, String contextInfo, int numberOfPendingLockRequests);
  
  public void recordLockAwarded(LockID lockID, ThreadID threadID);
  
  public void recordLockReleased(LockID lockID, ThreadID threadID);
  
  public void recordLockHopped(LockID lockID, ThreadID threadID);
  
  public void recordLockRejected(LockID lockID, ThreadID threadID);
  
  public void setLockStatisticsConfig(int traceDepth, int gatherInterval);
  
  public void setLockStatisticsEnabled(boolean statEnable);
  
  public boolean isStatEnabled();
  
  public void getLockSpecs();
}
