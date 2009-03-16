/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.lockmanager.api;

import com.tc.net.ClientID;
import com.tc.object.lockmanager.api.LockContext;
import com.tc.object.lockmanager.api.LockID;
import com.tc.object.lockmanager.api.ThreadID;

import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;

public class NotifiedWaitersTest extends TestCase {

  public void testBasics() throws Exception {
    ClientID clientID1 = new ClientID(1);
    ClientID clientID2 = new ClientID(2);

    Set forChannel1 = new HashSet();
    Set forChannel2 = new HashSet();

    LockID lockID = new LockID("me");
    ThreadID txID1 = new ThreadID(1);
    ThreadID txID2 = new ThreadID(2);
    ThreadID txID3 = new ThreadID(3);

    NotifiedWaiters ns = new NotifiedWaiters();

    LockContext lr1 = new LockContext(lockID, clientID1, txID1, 0, String.class.getName());
    forChannel1.add(lr1);
    ns.addNotification(lr1);

    LockContext lr2 = new LockContext(lockID, clientID1, txID2, 0, String.class.getName());
    forChannel1.add(lr2);
    ns.addNotification(lr2);

    LockContext lr3 = new LockContext(lockID, clientID2, txID3, 0, String.class.getName());
    forChannel2.add(lr3);
    ns.addNotification(lr3);

    assertEquals(forChannel1, ns.getNotifiedFor(clientID1));
    assertEquals(forChannel2, ns.getNotifiedFor(clientID2));

    ns = new NotifiedWaiters();
    assertTrue(ns.isEmpty());
    ns.getNotifiedFor(new ClientID(1));
    assertTrue(ns.isEmpty());
  }

}
