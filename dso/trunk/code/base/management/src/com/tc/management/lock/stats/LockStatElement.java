/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management.lock.stats;

import org.apache.commons.lang.builder.HashCodeBuilder;

import com.tc.exception.TCRuntimeException;
import com.tc.io.TCByteBufferInput;
import com.tc.io.TCByteBufferOutput;
import com.tc.io.TCSerializable;
import com.tc.net.groups.NodeID;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.ThreadID;
import com.tc.object.lockmanager.impl.LockHolder;
import com.tc.util.Stack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class LockStatElement implements TCSerializable, Serializable, LockTraceElement {
  private LockID                          lockID;
  private LockStats                       lockStat;
  private int                             hashCode;
  private Map                             nextStat          = new HashMap();        // Map<LockStatElement,
                                                                                    // LockStatElement>
  public final transient LockHolderStats holderStats       = new LockHolderStats(); // TCSerializable and Serializable
  // transient

  private StackTraceElement               stackTraceElement;
  private String                          lockConfigElement = "";

  public LockStatElement(LockID lockID, StackTraceElement stackTraceElement) {
    this.lockID = lockID;
    this.stackTraceElement = stackTraceElement;
    this.lockStat = new LockStats();

    computeHashCode();
  }

  public LockStatElement() {
    //
  }

  public String getConfigElement() {
    return lockConfigElement;
  }

  public LockStats getStats() {
    return lockStat;
  }

  /**
   * @return Collection<LockStatElement>
   */
  public Collection children() {
    return new ArrayList(nextStat.values());
  }

  public StackTraceElement getStackFrame() {
    return stackTraceElement;
  }

  public void recordLockRequested(NodeID nodeID, ThreadID threadID, long requestedTimeInMillis, 
                                  int numberOfPendingRequests, String contextInfo, StackTraceElement[] stackTraces, int startIndex) {
    this.lockStat.recordLockRequested(numberOfPendingRequests);
    this.lockConfigElement = contextInfo;
    LockHolder lockHolder = newLockHolder(lockID, nodeID, threadID, requestedTimeInMillis);
    holderStats.addLockHolder(newLockKey(lockID, nodeID, threadID), lockHolder, stackTraces);

    if (stackTraces == null || startIndex >= stackTraces.length) { return; }

    LockStatElement child = getOrCreateChild(stackTraces[startIndex]);
    child.recordLockRequested(nodeID, threadID, requestedTimeInMillis, numberOfPendingRequests, contextInfo, stackTraces, startIndex + 1);
  }

  public boolean recordLockAwarded(NodeID nodeID, ThreadID threadID, boolean isGreedy, long awardedTimeInMillis,
                                   int nestedLockDepth) {
    LockKey nonGreedyLockKey = newLockKey(lockID, nodeID, threadID);
    LockKey greedyLockKey = null;
    if (isGreedy) {
      greedyLockKey = newLockKey(lockID, nodeID, ThreadID.VM_ID);
    }

    StackTraceElement[] stackTraces = holderStats.getTraces(nonGreedyLockKey);
    return recordLockAwarded(nonGreedyLockKey, greedyLockKey, isGreedy, awardedTimeInMillis, nestedLockDepth,
                             stackTraces, 0);
  }

  private boolean recordLockAwarded(LockKey nonGreedyLockKey, LockKey greedyLockKey, boolean isGreedy,
                                    long awardedTimeInMillis, int nestedLockDepth, StackTraceElement[] stackTraces,
                                    int startIndex) {
    LockKey lockKey = nonGreedyLockKey;
    LockHolder lockHolder = holderStats.getLockHolder(lockKey);
    if (lockHolder == null) { return false; } // a lock holder could be null if jmx is enabled during runtime

    lockHolder.lockAcquired(awardedTimeInMillis);
    if (isGreedy) {
      lockKey = greedyLockKey;
      holderStats.remove(lockKey);
      holderStats.addLockHolder(lockKey, lockHolder, stackTraces);
    }
    holderStats.moveToPendingHeld(lockKey, lockHolder, stackTraces);

    this.lockStat.recordLockAwarded(lockHolder.getWaitTimeInMillis(), nestedLockDepth);
    if (stackTraces != null && startIndex < stackTraces.length) {
      LockStatElement child = getOrCreateChild(stackTraces[startIndex]);
      child.recordLockAwarded(nonGreedyLockKey, greedyLockKey, isGreedy, awardedTimeInMillis, nestedLockDepth,
                              stackTraces, startIndex + 1);
    }

    return true;
  }

  public boolean recordLockReleased(NodeID nodeID, ThreadID threadID) {
    LockKey lockKey = newLockKey(lockID, nodeID, threadID);

    StackTraceElement[] stackTraces = holderStats.getPendingHeldTraces(lockKey);

    return recordLockReleased(lockKey, stackTraces, 0);
  }

  private boolean recordLockReleased(LockKey lockKey, StackTraceElement[] stackTraces, int startIndex) {
    LockHolder lockHolder = holderStats.getPendingHeldLockHolder(lockKey);
    if (lockHolder == null) { return false; }

    lockHolder.lockReleased();
    holderStats.moveToHistory(lockKey, lockHolder);

    long heldTimeInMillis = lockHolder.getHeldTimeInMillis();

    this.lockStat.recordLockReleased(heldTimeInMillis);
    if (stackTraces != null && startIndex < stackTraces.length) {
      LockStatElement child = getOrCreateChild(stackTraces[startIndex]);
      child.recordLockReleased(lockKey, stackTraces, startIndex + 1);
    }

    return true;
  }

  public void recordLockHopped(NodeID nodeID, ThreadID threadID, StackTraceElement[] stackTraces, int startIndex) {
    LockKey lockKey = newLockKey(lockID, nodeID, threadID);
    stackTraces = holderStats.peekTraces(lockKey);

    LockHolder lockHolder = holderStats.getLockHolder(lockKey);
    if (lockHolder == null) { return; }

    this.lockStat.recordLockHopRequested();

    if (stackTraces != null && startIndex < stackTraces.length) {
      LockStatElement child = getOrCreateChild(stackTraces[startIndex]);
      child.recordLockHopped(nodeID, threadID, stackTraces, startIndex + 1);
    }
  }

  public void recordLockHopped() {
    this.lockStat.recordLockHopRequested();
  }

  public void recordLockRejected(NodeID nodeID, ThreadID threadID) {
    LockKey lockKey = newLockKey(lockID, nodeID, threadID);
    StackTraceElement[] stackTraces = holderStats.getTraces(lockKey);

    recordLockRejected(lockKey, stackTraces, 0);
  }

  private void recordLockRejected(LockKey lockKey, StackTraceElement[] stackTraces, int startIndex) {
    LockHolder lockHolder = holderStats.remove(lockKey);
    if (lockHolder == null) { return; }

    this.lockStat.recordLockRejected();
    if (stackTraces == null || startIndex >= stackTraces.length) { return; }

    LockStatElement child = getOrCreateChild(stackTraces[startIndex]);
    child.recordLockRejected(lockKey, stackTraces, startIndex + 1);
  }

  public void aggregateLockHoldersData(LockStats stat, int startIndex) {
    holderStats.aggregateLockHoldersData(stat);

    for (Iterator i = nextStat.keySet().iterator(); i.hasNext();) {
      LockStatElement child = (LockStatElement) i.next();
      child.aggregateLockHoldersData(child.getStats(), startIndex + 1);
    }
  }

  public void aggregateLockStat() {
    lockStat.clear();
    for (Iterator i = nextStat.keySet().iterator(); i.hasNext();) {
      LockStatElement lockStatElement = (LockStatElement) i.next();
      long pendingRequests = lockStatElement.lockStat.getNumOfLockPendingRequested();
      long lockRequested = lockStatElement.lockStat.getNumOfLockRequested();
      long lockHopRequests = lockStatElement.lockStat.getNumOfLockHopRequests();
      long lockAwarded = lockStatElement.lockStat.getNumOfLockAwarded();
      long timeToAwardedInMillis = lockStatElement.lockStat.getTotalWaitTimeToAwardedInMillis();
      long heldTimeInMillis = lockStatElement.lockStat.getTotalRecordedHeldTimeInMillis();
      long numOfReleases = lockStatElement.lockStat.getNumOfLockReleased();
      lockStat.aggregateStatistics(pendingRequests, lockRequested, lockHopRequests, lockAwarded,
                                   timeToAwardedInMillis, heldTimeInMillis, numOfReleases);
    }
  }

  public void setChild(Collection lockStatElements) {
    for (Iterator i = lockStatElements.iterator(); i.hasNext();) {
      LockStatElement lse = (LockStatElement) i.next();
      nextStat.put(lse, lse);
    }
  }

  public void mergeChild(LockStatElement lockStatElement) {
    LockStatElement existLSE = (LockStatElement) nextStat.get(lockStatElement);
    if (existLSE == null) {
      nextStat.put(lockStatElement, lockStatElement);
    } else {
      long pendingRequests = lockStatElement.lockStat.getNumOfLockPendingRequested();
      long lockRequested = lockStatElement.lockStat.getNumOfLockRequested();
      long lockHopRequests = lockStatElement.lockStat.getNumOfLockHopRequests();
      long lockAwarded = lockStatElement.lockStat.getNumOfLockAwarded();
      long timeToAwardedInMillis = lockStatElement.lockStat.getTotalWaitTimeToAwardedInMillis();
      long heldTimeInMillis = lockStatElement.lockStat.getTotalRecordedHeldTimeInMillis();
      long numOfReleases = lockStatElement.lockStat.getNumOfLockReleased();
      existLSE.lockStat.aggregateStatistics(pendingRequests, lockRequested, lockHopRequests,
                                            lockAwarded, timeToAwardedInMillis, heldTimeInMillis, numOfReleases);

      for (Iterator i = lockStatElement.nextStat.values().iterator(); i.hasNext();) {
        LockStatElement child = (LockStatElement) i.next();

        existLSE.mergeChild(child);
      }
    }
  }

  public void clearChild() {
    nextStat.clear();
  }

  public boolean equals(Object obj) {
    if (!(obj instanceof LockStatElement)) { return false; }
    if (this == obj) { return true; }

    LockStatElement ls = (LockStatElement) obj;
    if (stackTraceElement == null && ls.stackTraceElement != null) { return false; }
    if (stackTraceElement != null && ls.stackTraceElement == null) { return false; }

    if (stackTraceElement == null) { return lockID.equals(ls.lockID); }
    return lockID.equals(ls.lockID) && stackTraceElement.equals(ls.stackTraceElement);
  }

  public int hashCode() {
    return hashCode;
  }

  public Object deserializeFrom(TCByteBufferInput serialInput) throws IOException {
    this.lockID = new LockID(serialInput.readString());
    this.lockConfigElement = serialInput.readString();
    this.lockStat = new LockStats();
    lockStat.deserializeFrom(serialInput);
    int numBytes = serialInput.readInt();
    byte[] bytes = new byte[numBytes];
    serialInput.read(bytes);
    ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
    ObjectInputStream is = new ObjectInputStream(bis);
    try {
      stackTraceElement = (StackTraceElement) is.readObject();
    } catch (ClassNotFoundException e) {
      throw new TCRuntimeException(e);
    }
    this.nextStat = new HashMap();
    int numOfLockStatElement = serialInput.readInt();
    for (int i = 0; i < numOfLockStatElement; i++) {
      LockStatElement ls = new LockStatElement();
      ls.deserializeFrom(serialInput);
      nextStat.put(ls, ls);
    }

    computeHashCode();

    return this;
  }

  public void serializeTo(TCByteBufferOutput serialOutput) {
    serialOutput.writeString(lockID.asString());
    serialOutput.writeString(lockConfigElement);
    lockStat.serializeTo(serialOutput);
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutputStream os = new ObjectOutputStream(bos);
      os.writeObject(stackTraceElement);
      byte[] bytes = bos.toByteArray();
      serialOutput.writeInt(bytes.length);
      serialOutput.write(bytes);
    } catch (IOException e) {
      throw new TCRuntimeException(e);
    }

    Collection lockStatElements = nextStat.values();
    serialOutput.writeInt(lockStatElements.size());
    for (Iterator i = lockStatElements.iterator(); i.hasNext();) {
      LockStatElement ls = (LockStatElement) i.next();
      ls.serializeTo(serialOutput);
    }
  }

  public String toString() {
    StringBuffer sb = new StringBuffer();
    if (stackTraceElement == null) {
      sb.append(lockID);
    } else {
      sb.append(stackTraceElement);
    }
    sb.append(" locking context: ");
    sb.append(lockConfigElement);
    sb.append(" -- stats: ");
    sb.append(lockStat);
    sb.append("\n");
    Collection lockStatElements = nextStat.values();
    for (Iterator i = lockStatElements.iterator(); i.hasNext();) {
      LockStatElement ls = (LockStatElement) i.next();
      sb.append("   ");
      sb.append(ls);
      sb.append("\n");
    }
    return sb.toString();
  }

  private void computeHashCode() {
    if (stackTraceElement != null) {
      this.hashCode = new HashCodeBuilder(5503, 6737).append(lockID).append(stackTraceElement).toHashCode();
    } else {
      this.hashCode = new HashCodeBuilder(5503, 6737).append(lockID).toHashCode();
    }
  }

  private LockStatElement getOrCreateChild(StackTraceElement element) {
    LockStatElement ls = new LockStatElement(lockID, element);
    LockStatElement child = (LockStatElement) nextStat.get(ls);
    if (child == null) {
      child = ls;
      nextStat.put(child, child);
    }
    return child;
  }

  private LockKey newLockKey(LockID lockID, NodeID nodeID, ThreadID threadID) {
    return new LockKey(lockID, nodeID, threadID);
  }

  private LockHolder newLockHolder(LockID lockID, NodeID nodeID, ThreadID threadID, long timeStamp) {
    return new LockHolder(lockID, nodeID, threadID, timeStamp);
  }

  /**
   * Inner classes section
   */
  protected static class LockHolderStats {
    private static class LockHolderContext {
      private final LockHolder lockHolder;
      private final Stack      traces;    // stack of StackTraceElement[]

      public LockHolderContext(LockHolder lockHolder) {
        this.lockHolder = lockHolder;
        this.traces = new Stack();
      }

      LockHolder getLockHolder() {
        return this.lockHolder;
      }

      StackTraceElement[] popTraces() {
        if (traces.isEmpty()) { return null; }

        return (StackTraceElement[]) traces.pop();
      }

      StackTraceElement[] peekTraces() {
        if (traces.isEmpty()) { return null; }

        return (StackTraceElement[]) traces.peek();
      }

      boolean isTracesEmpty() {
        return traces.isEmpty();
      }

      void pushTraces(StackTraceElement[] trace) {
        traces.push(trace);
      }
    }

    private static class PendingStat {
      private long numOfHolders;
      private long totalWaitTimeInMillis;
      private long totalHeldTimeInMillis;

      public PendingStat(long waitTimeInMillis, long heldTimeInMillis) {
        addPendingHolderData(waitTimeInMillis, heldTimeInMillis);
      }

      public void addPendingHolderData(long waitTimeInMillis, long heldTimeInMillis) {
        this.numOfHolders++;
        this.totalHeldTimeInMillis += heldTimeInMillis;
        this.totalWaitTimeInMillis += waitTimeInMillis;
      }
    }

    private final static int NO_LIMIT = -1;

    private final Map        pendingData;        // map<LockKey.subKey, map<LockKey, LockHolderContext>>
    private final Map        pendingHeldTimeData; // map<LockKey.subKey, map<LockKey, LockHolderContext>>
    private final int        maxSize;

    public LockHolderStats() {
      this(NO_LIMIT);
    }

    public LockHolderStats(int maxSize) {
      pendingData = new HashMap();
      pendingHeldTimeData = new HashMap();
      this.maxSize = maxSize;
    }

    public void clear() {
      this.pendingData.clear();
      this.pendingHeldTimeData.clear();
    }

    public LockHolder remove(LockKey key) {
      LockKey subKey = key.subKey();
      Map lockHolders = (Map) pendingData.get(subKey);
      if (lockHolders != null) {
        LockHolderContext lhc = (LockHolderContext) lockHolders.remove(key);
        if (lhc != null) { return lhc.getLockHolder(); }
      }
      return null;
    }

    public void addLockHolder(LockKey key, LockHolder value, StackTraceElement[] traces) {
      LockHolderContext lhc = new LockHolderContext(value);
      if (traces != null && traces.length > 0) {
        lhc.pushTraces(traces);
      }
      putInternal(pendingData, key, lhc);
    }

    private void putInternal(Map dataMap, LockKey key, LockHolderContext value) {
      LockKey subKey = key.subKey();
      Map lockHolders = (Map) dataMap.get(subKey);
      if (lockHolders == null) {
        lockHolders = new HashMap();
        dataMap.put(subKey, lockHolders);
      }
      lockHolders.put(key, value);
    }

    public void moveToHistory(LockKey key, Object value) {
      LockKey subKey = key.subKey();
      Map lockHolders = (Map) pendingHeldTimeData.get(subKey);
      LockHolderContext lhc = (LockHolderContext) lockHolders.get(key);
      if (lhc.isTracesEmpty()) {
        lockHolders.remove(key);
      }
    }

    public void moveToPendingHeld(LockKey key, Object value, StackTraceElement[] traces) {
      LockKey subKey = key.subKey();
      Map lockHolders = (Map) pendingData.get(subKey);
      LockHolderContext o = (LockHolderContext) lockHolders.remove(key);
      LockHolderContext existingContext = get(pendingHeldTimeData, key);
      if (existingContext != null) {
        existingContext.pushTraces(traces);
      } else {
        o.pushTraces(traces);
        putInternal(pendingHeldTimeData, key, o);
      }
    }

    private LockHolderContext get(Map dataMap, LockKey key) {
      LockKey subKey = key.subKey();
      Map lockHolders = (Map) dataMap.get(subKey);
      if (lockHolders == null) return null;
      if (lockHolders.size() == 0) return null;

      return (LockHolderContext) lockHolders.get(key);
    }

    public LockHolder getPendingHeldLockHolder(LockKey key) {
      LockHolderContext lhc = get(pendingHeldTimeData, key);
      if (lhc == null) { return null; }

      return lhc.getLockHolder();
    }

    public StackTraceElement[] getPendingHeldTraces(LockKey key) {
      LockHolderContext lhc = get(pendingHeldTimeData, key);
      if (lhc == null) { return null; }

      return lhc.popTraces();
    }

    public LockHolder getLockHolder(LockKey key) {
      LockHolderContext lhc = get(pendingData, key);
      if (lhc == null) { return null; }

      return lhc.getLockHolder();
    }

    public StackTraceElement[] getTraces(LockKey key) {
      LockHolderContext lhc = get(pendingData, key);
      if (lhc == null) { return null; }

      return lhc.popTraces();
    }

    public StackTraceElement[] peekTraces(LockKey key) {
      LockHolderContext lhc = get(pendingData, key);
      if (lhc == null) { return null; }

      return lhc.peekTraces();
    }

    public void aggregateLockHoldersData(LockStats lockStat) {
      PendingStat pendingStat = null;

      Collection val = pendingData.values();
      for (Iterator i = val.iterator(); i.hasNext();) {
        Map lockHolders = (Map) i.next();
        for (Iterator j = lockHolders.values().iterator(); j.hasNext();) {
          LockHolderContext lhc = (LockHolderContext) j.next();
          LockHolder lockHolder = lhc.getLockHolder();
          lockHolder.computeWaitAndHeldTimeInMillis();
          if (pendingStat == null) {
            pendingStat = new PendingStat(lockHolder.getWaitTimeInMillis(), lockHolder.getHeldTimeInMillis());
          } else {
            pendingStat.addPendingHolderData(lockHolder.getWaitTimeInMillis(), lockHolder.getHeldTimeInMillis());
          }
        }
      }
      if (pendingStat != null) {
        lockStat.aggregateAvgWaitTimeInMillis(pendingStat.totalWaitTimeInMillis, pendingStat.numOfHolders);
        lockStat.aggregateAvgHeldTimeInMillis(pendingStat.totalHeldTimeInMillis, pendingStat.numOfHolders);
      }

      for (Iterator i = pendingHeldTimeData.values().iterator(); i.hasNext();) {
        Map lockHolders = (Map) i.next();
        for (Iterator j = lockHolders.values().iterator(); j.hasNext();) {
          LockHolderContext lhc = (LockHolderContext) j.next();
          LockHolder lockHolder = lhc.getLockHolder();
          lockHolder.getAndSetHeldTimeInMillis();
          if (pendingStat == null) {
            pendingStat = new PendingStat(-1, lockHolder.getHeldTimeInMillis());
          } else {
            pendingStat.addPendingHolderData(-1, lockHolder.getHeldTimeInMillis());
          }
        }
      }
      if (pendingStat != null) {
        lockStat.aggregateAvgHeldTimeInMillis(pendingStat.totalHeldTimeInMillis, pendingStat.numOfHolders);
      }
    }

    public void clearAllStatsFor(NodeID nodeID) {
      clearAllStatesFor(pendingData, nodeID);
      clearAllStatesFor(pendingHeldTimeData, nodeID);
    }

    private void clearAllStatesFor(Map dataMap, NodeID nodeID) {
      Set lockKeys = dataMap.keySet();
      for (Iterator i = lockKeys.iterator(); i.hasNext();) {
        LockKey key = (LockKey) i.next();
        if (nodeID.equals(key.getNodeID())) {
          i.remove();
        }
      }
    }

    public String toString() {
      return pendingData.toString();
    }
  }

}
