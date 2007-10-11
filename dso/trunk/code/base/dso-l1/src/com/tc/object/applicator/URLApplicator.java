/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.applicator;

import com.tc.object.ClientObjectManager;
import com.tc.object.ObjectID;
import com.tc.object.SerializationUtil;
import com.tc.object.TCObject;
import com.tc.object.TraversedReferences;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAEncoding;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.dna.api.LogicalAction;
import com.tc.object.tx.optimistic.OptimisticTransactionManager;
import com.tc.util.Assert;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

/**
 * ChangeApplicator for URLs.
 */
public class URLApplicator extends BaseApplicator {

  public URLApplicator(DNAEncoding encoding) {
    super(encoding);
  }

  public void hydrate(ClientObjectManager objectManager, TCObject tcObject, DNA dna, Object pojo) throws IOException, ClassNotFoundException {
    TCURL url = (TCURL)pojo;
    DNACursor cursor = dna.getCursor();

    while (cursor.next(encoding)) {
      LogicalAction action = cursor.getLogicalAction();
      int method = action.getMethod();
      Object[] params = action.getParameters();
      switch (method) {
        case SerializationUtil.URL_SET:
          Assert.assertNotNull(params[0]);
          Assert.assertNotNull(params[1]);
          Assert.assertNotNull(params[2]);
          Assert.assertNotNull(params[3]);
          Assert.assertNotNull(params[4]);
          Assert.assertNotNull(params[5]);
          Assert.assertNotNull(params[6]);
          Assert.assertNotNull(params[7]);
          
          String protocol = null;
          String host = null;
          int port = -1;
          String authority = null;
          String userInfo = null;
          String path = null;
          String query = null;
          String ref = null;
          if (!ObjectID.NULL_ID.equals(params[0])) {
            protocol = params[0].toString();
          }
          if (!ObjectID.NULL_ID.equals(params[1])) {
            host = params[1].toString();
          }
          if (!ObjectID.NULL_ID.equals(params[2])) {
            port = ((Integer)params[2]).intValue();
          }
          if (!ObjectID.NULL_ID.equals(params[3])) {
            authority = params[3].toString();
          }
          if (!ObjectID.NULL_ID.equals(params[4])) {
            userInfo = params[4].toString();
          }
          if (!ObjectID.NULL_ID.equals(params[5])) {
            path = params[5].toString();
          }
          if (!ObjectID.NULL_ID.equals(params[6])) {
            query = params[6].toString();
          }
          if (!ObjectID.NULL_ID.equals(params[7])) {
            ref = params[7].toString();
          }

          url.__tc_set_logical(protocol, host, port, authority, userInfo, path, query, ref);
          break;
        default:
          throw new AssertionError("invalid action:" + method);
      }
    }
  }

  public void dehydrate(ClientObjectManager objectManager, TCObject tcObject, DNAWriter writer, Object pojo) {
    URL url = (URL)pojo;
    writer.addLogicalAction(SerializationUtil.URL_SET, new Object[] { url.getProtocol(), url.getHost(), new Integer(url.getPort()), url.getAuthority(), url.getUserInfo(), url.getPath(), url.getQuery(), url.getRef() });
  }

  public TraversedReferences getPortableObjects(Object pojo, TraversedReferences addTo) {
    addTo.addAnonymousReference(pojo);
    return addTo;
  }

  public Object getNewInstance(ClientObjectManager objectManager, DNA dna) {
    throw new UnsupportedOperationException();
  }

  public Map connectedCopy(Object source, Object dest, Map visited, ClientObjectManager objectManager,
                           OptimisticTransactionManager txManager) {
    throw new UnsupportedOperationException();
  }
}