/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.dna.impl;

import com.tc.io.TCByteBufferOutputStream;
import com.tc.object.ObjectID;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.dna.api.DNAEncoding;

public class ObjectDNAImplTest extends DNAImplTest {

  private long version = 69;

  public void testParentID() throws Exception {
    super.serializeDeserialize(true, false);
    assertEquals(version, dna.getVersion());
  }

  public void testArrayLength() throws Exception {
    super.serializeDeserialize(false, false);
    assertEquals(version, dna.getVersion());
  }

  protected DNAImpl createDNAImpl(ObjectStringSerializer serializer, boolean b) {
    return new ObjectDNAImpl(serializer, b);
  }

  protected DNAWriter createDNAWriter(TCByteBufferOutputStream out, ObjectID id, String type,
                                      ObjectStringSerializer serializer, DNAEncoding encoding, boolean isDelta) {
    return new ObjectDNAWriterImpl(out, id, type, serializer, encoding, "loader description", version, isDelta);
  }

}
