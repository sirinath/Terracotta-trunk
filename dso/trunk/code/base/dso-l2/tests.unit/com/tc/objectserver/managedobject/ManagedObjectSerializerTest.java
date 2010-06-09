/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.managedobject;

import com.tc.io.serializer.TCObjectInputStream;
import com.tc.io.serializer.TCObjectOutputStream;
import com.tc.object.ObjectID;
import com.tc.object.TestDNACursor;
import com.tc.object.tx.TransactionID;
import com.tc.objectserver.api.ObjectInstanceMonitor;
import com.tc.objectserver.core.api.ManagedObject;
import com.tc.objectserver.core.api.TestDNA;
import com.tc.objectserver.impl.ObjectInstanceMonitorImpl;
import com.tc.objectserver.persistence.impl.InMemoryPersistor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import junit.framework.TestCase;

public class ManagedObjectSerializerTest extends TestCase {

  private ObjectID                     id;
  private ManagedObjectStateSerializer stateSerializer;

  public void test() throws Exception {
    ManagedObjectStateFactory.disableSingleton(true);
    ManagedObjectStateFactory.createInstance(new NullManagedObjectChangeListenerProvider(), new InMemoryPersistor());

    this.stateSerializer = new ManagedObjectStateSerializer();
    this.id = new ObjectID(1);

    final ManagedObjectSerializer mos = new ManagedObjectSerializer(this.stateSerializer);
    final ManagedObjectImpl mo = new ManagedObjectImpl(this.id);
    assertTrue(mo.isDirty());
    assertTrue(mo.isNew());
    final TestDNA dna = newDNA(1);
    final ObjectInstanceMonitor imo = new ObjectInstanceMonitorImpl();
    mo.apply(dna, new TransactionID(1), new BackReferences(), imo, false);

    final ByteArrayOutputStream baout = new ByteArrayOutputStream();
    final TCObjectOutputStream out = new TCObjectOutputStream(baout);
    mos.serializeTo(mo, out);
    out.flush();
    final ManagedObject mo2 = (ManagedObject) mos
        .deserializeFrom(new TCObjectInputStream(new ByteArrayInputStream(baout.toByteArray())));

    assertFalse(mo2.isDirty());
    mo.setIsDirty(false);
    assertNotSame(mo, mo2);
    assertTrue(mo.isEqual(mo2));
  }

  private TestDNA newDNA(final int fieldSetCount) {
    final TestDNACursor cursor = new TestDNACursor();
    for (int i = 0; i < fieldSetCount; i++) {
      cursor.addPhysicalAction("refField" + i, new ObjectID(1), true);
      cursor.addPhysicalAction("booleanField" + i, new Boolean(true), true);
      cursor.addPhysicalAction("byteField" + i, new Byte((byte) 1), true);
      cursor.addPhysicalAction("characterField" + i, new Character('c'), true);
      cursor.addPhysicalAction("doubleField" + i, new Double(100.001d), true);
      cursor.addPhysicalAction("floatField" + i, new Float(100.001f), true);
      cursor.addPhysicalAction("integerField" + i, new Integer(100), true);
      cursor.addPhysicalAction("longField" + i, new Long(100), true);
      cursor.addPhysicalAction("stringField" + i, "Some nice string field" + i, true);
      cursor.addPhysicalAction("shortField" + i, new Short((short) 1), true);
    }
    final TestDNA dna = new TestDNA(cursor);
    return dna;
  }
}
