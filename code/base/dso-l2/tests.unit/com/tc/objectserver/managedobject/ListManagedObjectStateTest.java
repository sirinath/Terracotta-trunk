/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.managedobject;

import com.tc.object.ObjectID;
import com.tc.object.SerializationUtil;
import com.tc.objectserver.core.api.ManagedObjectState;
import com.tc.util.Assert;

public class ListManagedObjectStateTest extends AbstractTestManagedObjectState {
  
  // override due to difference on dehydrate
  protected void basicDehydrate(TestDNACursor cursor, int objCount, ManagedObjectState state) {
    TestDNAWriter dnaWriter = new TestDNAWriter();
    state.dehydrate(objectID, dnaWriter);
    Assert.assertEquals(objCount, dnaWriter.getActionCount());
  }
  
  public void testObjectList1() throws Exception {
    String className = "java.util.ArrayList";
    TestDNACursor cursor = new TestDNACursor();

    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2002) });
    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2003) });
    cursor.addLogicalAction(SerializationUtil.ADD_FIRST, new Object[] { new ObjectID(2004) });

    basicTestUnit(className, ManagedObjectState.LIST_TYPE, cursor, 3);
  }
  
  public void testObjectList2() throws Exception {
    String className = "java.util.ArrayList";
    TestDNACursor cursor = new TestDNACursor();

    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2002) });
    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2003) });
    cursor.addLogicalAction(SerializationUtil.ADD_FIRST, new Object[] { new ObjectID(2004) });
    cursor.addLogicalAction(SerializationUtil.ADD_AT, new Object[] { new Integer(1), new ObjectID(1000) });

    basicTestUnit(className, ManagedObjectState.LIST_TYPE, cursor, 4);
  }

  public void testObjectList3() throws Exception {
    String className = "java.util.ArrayList";
    TestDNACursor cursor = new TestDNACursor();

    for(int i = 0; i < 1000; ++i) {
      cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(1000+i) });
    }
    cursor.addLogicalAction(SerializationUtil.CLEAR, null);

    basicTestUnit(className, ManagedObjectState.LIST_TYPE, cursor, 0);
  }

  public void testObjectList4() throws Exception {
    String className = "java.util.ArrayList";
    TestDNACursor cursor = new TestDNACursor();

    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2002) });
    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2003) });
    cursor.addLogicalAction(SerializationUtil.ADD, new Object[] { new ObjectID(2004) });
    cursor.addLogicalAction(SerializationUtil.REMOVE_FIRST, null);
    cursor.addLogicalAction(SerializationUtil.REMOVE, new Object[] { new ObjectID(2004) });

    basicTestUnit(className, ManagedObjectState.LIST_TYPE, cursor, 1);
  }

}
