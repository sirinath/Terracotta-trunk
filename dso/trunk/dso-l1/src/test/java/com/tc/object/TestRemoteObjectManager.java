/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object;

import com.tc.exception.ImplementMe;
import com.tc.exception.TCObjectNotFoundException;
import com.tc.net.GroupID;
import com.tc.net.NodeID;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNACursor;
import com.tc.object.dna.api.DNAException;
import com.tc.object.msg.ClientHandshakeMessage;
import com.tc.object.session.SessionID;
import com.tc.text.PrettyPrinter;
import com.tc.util.ObjectIDSet;
import com.tc.util.concurrent.NoExceptionLinkedQueue;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

public class TestRemoteObjectManager implements RemoteObjectManager {
  private final int               SIZE                  = 10000;

  /**
   * sine LinkedBlockingQueue is instrumented this is a workaround to get this test working TODO: change back to
   * {@link NoExceptionLinkedQueue} when instrumentation is removed
   */
  public final ArrayBlockingQueue retrieveCalls         = new ArrayBlockingQueue(SIZE);
  public final ArrayBlockingQueue retrieveResults       = new ArrayBlockingQueue(SIZE);

  public final ArrayBlockingQueue retrieveRootIDCalls   = new ArrayBlockingQueue(SIZE);
  public final ArrayBlockingQueue retrieveRootIDResults = new ArrayBlockingQueue(SIZE);

  public static final DNA         THROW_NOT_FOUND       = new ThrowNotFound();
  public final ObjectIDSet        removedObjects        = new ObjectIDSet();

  @Override
  public void cleanup() {
    retrieveCalls.clear();
    retrieveResults.clear();
    retrieveRootIDCalls.clear();
    retrieveRootIDResults.clear();
    removedObjects.clear();
  }

  @Override
  public DNA retrieve(final ObjectID id) {
    this.retrieveCalls.add(id);
    DNA dna;
    try {
      dna = (DNA) this.retrieveResults.take();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    if (dna == THROW_NOT_FOUND) { throw new TCObjectNotFoundException("missing ID"); }
    return dna;
  }

  @Override
  public DNA retrieveWithParentContext(final ObjectID id, final ObjectID parentContext) {
    return retrieve(id);
  }

  @Override
  public ObjectID retrieveRootID(final String name, GroupID gid) {
    this.retrieveRootIDCalls.add(name);
    try {
      return (ObjectID) this.retrieveRootIDResults.take();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  @Override
  public void removed(final ObjectID id) {
    removedObjects.add(id);
  }

  @Override
  public DNA retrieve(final ObjectID id, final int depth) {
    throw new ImplementMe();
  }

  @Override
  public void addAllObjects(final SessionID sessionID, final long batchID, final Collection dnas, final NodeID nodeID) {
    throw new ImplementMe();
  }

  @Override
  public void addRoot(final String name, final ObjectID id, final NodeID nodeID) {
    throw new ImplementMe();
  }

  @Override
  public void objectsNotFoundFor(final SessionID sessionID, final long batchID, final Set missingObjectIDs,
                                 final NodeID nodeID) {
    throw new ImplementMe();
  }

  public static class ThrowNotFound implements DNA {

    private ThrowNotFound() {
      //
    }

    @Override
    public int getArraySize() {
      throw new ImplementMe();
    }

    @Override
    public DNACursor getCursor() {
      throw new ImplementMe();
    }

    @Override
    public ObjectID getObjectID() throws DNAException {
      throw new ImplementMe();
    }

    @Override
    public ObjectID getParentObjectID() throws DNAException {
      throw new ImplementMe();
    }

    @Override
    public String getTypeName() {
      throw new ImplementMe();
    }

    @Override
    public long getVersion() {
      throw new ImplementMe();
    }

    @Override
    public boolean hasLength() {
      throw new ImplementMe();
    }

    @Override
    public boolean isDelta() {
      throw new ImplementMe();
    }

    @Override
    public boolean isIgnoreMissing() {
      return false;
    }
  }

  @Override
  public void clear(GroupID gid) {
    throw new ImplementMe();
  }

  @Override
  public boolean isInDNACache(final ObjectID id) {
    throw new ImplementMe();
  }

  @Override
  public void initializeHandshake(final NodeID thisNode, final NodeID remoteNode,
                                  final ClientHandshakeMessage handshakeMessage) {
    throw new ImplementMe();

  }

  @Override
  public void pause(final NodeID remoteNode, final int disconnected) {
    throw new ImplementMe();

  }

  @Override
  public void unpause(final NodeID remoteNode, final int disconnected) {
    throw new ImplementMe();

  }

  @Override
  public void shutdown() {
    // NOP
  }

  @Override
  public void preFetchObject(final ObjectID id) {
    throw new ImplementMe();
  }

  public ObjectID getMappingForKey(final ObjectID oid, final Object portableKey) {
    throw new ImplementMe();
  }

  public void addResponseForKeyValueMapping(final SessionID localSessionID, final ObjectID mapID,
                                            final Object portableKey, final Object portableValue, final NodeID nodeID) {
    throw new ImplementMe();
  }

  @Override
  public PrettyPrinter prettyPrint(PrettyPrinter out) {
    throw new ImplementMe();
  }
}
