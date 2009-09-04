/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.objectserver.managedobject;

import com.tc.object.ObjectID;
import com.tc.object.SerializationUtil;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.dna.api.LogicalAction;
import com.tc.objectserver.mgmt.ManagedObjectFacade;
import com.tc.objectserver.mgmt.PhysicalManagedObjectFacade;
import com.tc.util.Assert;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ManagedObjectState for dates.
 */
public class DateManagedObjectState extends LogicalManagedObjectState {

  private long referenceTime;
  private int  referenceNanoTime;

  public DateManagedObjectState(long classID) {
    super(classID);
  }

  protected DateManagedObjectState(ObjectInput in) throws IOException {
    super(in);
  }

  public void apply(ObjectID objectID, DNACursor cursor, BackReferences includeIDs) throws IOException {
    while (cursor.next()) {
      LogicalAction action = cursor.getLogicalAction();
      int method = action.getMethod();
      Object[] params = action.getParameters();

      switch (method) {
        case SerializationUtil.SET_TIME:
          Assert.assertNotNull(params[0]);
          referenceTime = ((Long) params[0]).longValue();
          break;
        case SerializationUtil.SET_NANOS:
          Assert.assertNotNull(params[0]);
          referenceNanoTime = ((Integer) params[0]).intValue();
          break;
        default:
          throw new AssertionError("Invalid action:" + method);
      }
    }
  }
  
  /**
   * This method returns whether this ManagedObjectState can have references or not.
   * @ return true : The Managed object represented by this state object will never have any reference to other objects.
   *         false : The Managed object represented by this state object can have references to other objects. 
   */
  @Override
  public boolean hasNoReferences() {
    return true;
  }

  public void dehydrate(ObjectID objectID, DNAWriter writer) {
    writer.addLogicalAction(SerializationUtil.SET_TIME, new Object[] { new Long(referenceTime) });
    writer.addLogicalAction(SerializationUtil.SET_NANOS, new Object[] { new Integer(referenceNanoTime) });
  }

  protected void addAllObjectReferencesTo(Set refs) {
    return;
  }

  public ManagedObjectFacade createFacade(ObjectID objectID, String className, int limit) {
    Map dataCopy = new HashMap();
    dataCopy.put("date", new Date(referenceTime));

    return new PhysicalManagedObjectFacade(objectID, ObjectID.NULL_ID, className, dataCopy, false, DNA.NULL_ARRAY_SIZE,
                                           false);
  }

  protected void basicWriteTo(ObjectOutput o) throws IOException {
    o.writeLong(referenceTime);
    o.writeInt(referenceNanoTime);
  }

  static DateManagedObjectState readFrom(ObjectInput in) throws IOException {
    DateManagedObjectState state = new DateManagedObjectState(in);
    state.referenceTime = in.readLong();
    state.referenceNanoTime = in.readInt();
    return state;
  }

  protected boolean basicEquals(LogicalManagedObjectState o) {
    DateManagedObjectState dms = (DateManagedObjectState) o;
    return dms.referenceTime == referenceTime && dms.referenceNanoTime == referenceNanoTime;
  }

  public byte getType() {
    return DATE_TYPE;
  }
}
