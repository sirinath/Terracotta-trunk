/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.dgc.api;

import com.tc.io.TCByteBufferInput;
import com.tc.io.TCByteBufferOutput;
import com.tc.io.TCSerializable;
import com.tc.objectserver.core.impl.GarbageCollectionID;

import java.io.IOException;

public class GarbageCollectionInfo implements Cloneable, TCSerializable {

  protected static final long               NOT_INITIALIZED       = -1L;
  protected static final long               NULL_INITIALIZED      = -2;
  private GarbageCollectionID               gcID                  = GarbageCollectionID.NULL_ID;
  private boolean                           fullGC;
  private long                              startTime             = NOT_INITIALIZED;
  private long                              beginObjectCount      = NOT_INITIALIZED;
  private long                              markStageTime         = NOT_INITIALIZED;
  private long                              pauseStageTime        = NOT_INITIALIZED;
  private long                              deleteStageTime       = NOT_INITIALIZED;
  private long                              elapsedTime           = NOT_INITIALIZED;
  private long                              totalMarkCycleTime    = NOT_INITIALIZED;
  private long                              candidateGarbageCount = NOT_INITIALIZED;
  private long                              actualGarbageCount    = NOT_INITIALIZED;
  private long                              preRescueCount        = NOT_INITIALIZED;
  private long                              rescue1Count          = NOT_INITIALIZED;
  private long                              rescue1Time           = NOT_INITIALIZED;
  private long                              rescue2Time           = NOT_INITIALIZED;

  public static final GarbageCollectionInfo NULL_INFO             = new GarbageCollectionInfo(
                                                                                              new GarbageCollectionID(
                                                                                                                      NULL_INITIALIZED,
                                                                                                                      "NULL INITIALIZED"),
                                                                                              false);

  public GarbageCollectionInfo() {
    // for serialization
  }

  public GarbageCollectionInfo(GarbageCollectionID id, boolean fullGC) {
    this.gcID = id;
    this.fullGC = fullGC;
  }

  public void setCandidateGarbageCount(long candidateGarbageCount) {
    this.candidateGarbageCount = candidateGarbageCount;
  }

  public void setActualGarbageCount(long actualGarbageCount) {
    this.actualGarbageCount = actualGarbageCount;
  }

  public long getRescue1Count() {
    return this.rescue1Count;
  }

  public void setRescue1Count(long count) {
    this.rescue1Count = count;
  }

  public long getPreRescueCount() {
    return this.preRescueCount;
  }

  public void setPreRescueCount(long count) {
    this.preRescueCount = count;
  }

  // TODO: see if we can remove this.
  public int getIteration() {
    return (int) this.gcID.toLong();
  }

  public boolean isFullGC() {
    return this.fullGC;
  }

  public void setStartTime(long time) {
    this.startTime = time;
  }

  public long getStartTime() {
    return this.startTime;
  }

  public void setBeginObjectCount(long count) {
    this.beginObjectCount = count;
  }

  public long getBeginObjectCount() {
    return this.beginObjectCount;
  }

  public void setMarkStageTime(long time) {
    this.markStageTime = time;
  }

  public long getMarkStageTime() {
    return this.markStageTime;
  }

  public void setPausedStageTime(long time) {
    this.pauseStageTime = time;
  }

  public long getPausedStageTime() {
    return this.pauseStageTime;
  }

  public void setDeleteStageTime(long time) {
    this.deleteStageTime = time;
  }

  public long getDeleteStageTime() {
    return this.deleteStageTime;
  }

  public void setElapsedTime(long time) {
    this.elapsedTime = time;
  }

  public long getElapsedTime() {
    return this.elapsedTime;
  }

  public void setTotalMarkCycleTime(long time) {
    this.totalMarkCycleTime = time;
  }

  public long getTotalMarkCycleTime() {
    return this.totalMarkCycleTime;
  }

  public void setCandidateGarbageCount(int count) {
    this.candidateGarbageCount = count;
  }

  public long getCandidateGarbageCount() {
    return this.candidateGarbageCount;
  }

  public long getActualGarbageCount() {
    return this.actualGarbageCount;
  }

  public long getRescue1Time() {
    return rescue1Time;
  }

  public void setRescue1Time(long rescue1Time) {
    this.rescue1Time = rescue1Time;
  }

  public long getRescue2Time() {
    return rescue2Time;
  }

  public void setRescue2Time(long rescue2Time) {
    this.rescue2Time = rescue2Time;
  }

  public GarbageCollectionID getGarbageCollectionID() {
    return gcID;
  }

  @Override
  public String toString() {
    return "GarbageCollectionInfo [ Iteration = " + this.gcID.toLong() + " ] = " + " type  = "
           + (this.fullGC ? " full, " : " young, ") + " startTime = " + this.startTime + " begin object count = "
           + this.beginObjectCount + " markStageTime = " + this.markStageTime + " pauseStageTime = "
           + this.pauseStageTime + " deleteStageTime = " + this.deleteStageTime + " elapsedTime = " + this.elapsedTime
           + " totalMarkCycletime  = " + this.totalMarkCycleTime + " candiate garabage  count = "
           + this.candidateGarbageCount + " actual garbage count  = " + this.actualGarbageCount
           + " pre rescue count = " + this.preRescueCount + " rescue1Time = " + rescue1Time + " rescue 1 count = "
           + this.rescue1Count + " rescue2Time = " + rescue2Time;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof GarbageCollectionInfo) {
      GarbageCollectionInfo other = (GarbageCollectionInfo) obj;
      return (this.gcID.equals(other.gcID) && this.fullGC == other.fullGC && this.startTime == other.startTime
              && this.beginObjectCount == other.beginObjectCount && this.markStageTime == other.markStageTime
              && this.pauseStageTime == other.pauseStageTime && this.deleteStageTime == other.deleteStageTime
              && this.elapsedTime == other.elapsedTime && this.totalMarkCycleTime == other.totalMarkCycleTime
              && this.candidateGarbageCount == other.candidateGarbageCount
              && this.preRescueCount == other.preRescueCount && this.rescue1Count == other.rescue1Count
              && this.rescue1Time == other.rescue1Time && this.rescue2Time == other.rescue2Time);
    }
    return false;
  }

  public Object deserializeFrom(TCByteBufferInput serialInput) throws IOException {

    long iterationCount = serialInput.readLong();
    String uuidString = serialInput.readString();
    this.gcID = new GarbageCollectionID(iterationCount, uuidString);
    this.fullGC = serialInput.readBoolean();
    this.startTime = serialInput.readLong();
    this.beginObjectCount = serialInput.readLong();
    this.markStageTime = serialInput.readLong();
    this.pauseStageTime = serialInput.readLong();
    this.deleteStageTime = serialInput.readLong();
    this.elapsedTime = serialInput.readLong();
    this.totalMarkCycleTime = serialInput.readLong();
    this.candidateGarbageCount = serialInput.readLong();
    this.actualGarbageCount = serialInput.readLong();
    this.preRescueCount = serialInput.readLong();
    this.rescue1Count = serialInput.readLong();
    this.rescue1Time = serialInput.readLong();
    this.rescue2Time = serialInput.readLong();
    return this;
  }

  public void serializeTo(TCByteBufferOutput serialOutput) {
    serialOutput.writeLong(this.gcID.toLong());
    serialOutput.writeString(this.gcID.getUUID());
    serialOutput.writeBoolean(this.fullGC);
    serialOutput.writeLong(this.startTime);
    serialOutput.writeLong(this.beginObjectCount);
    serialOutput.writeLong(this.markStageTime);
    serialOutput.writeLong(this.pauseStageTime);
    serialOutput.writeLong(this.deleteStageTime);
    serialOutput.writeLong(this.elapsedTime);
    serialOutput.writeLong(this.totalMarkCycleTime);
    serialOutput.writeLong(this.candidateGarbageCount);
    serialOutput.writeLong(this.actualGarbageCount);
    serialOutput.writeLong(this.preRescueCount);
    serialOutput.writeLong(this.rescue1Count);
    serialOutput.writeLong(this.rescue1Time);
    serialOutput.writeLong(this.rescue2Time);
  }

}