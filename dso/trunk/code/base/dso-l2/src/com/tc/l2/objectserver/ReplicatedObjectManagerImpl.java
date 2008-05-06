/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.objectserver;

import com.tc.async.api.Sink;
import com.tc.l2.context.SyncObjectsRequest;
import com.tc.l2.ha.L2HAZapNodeRequestProcessor;
import com.tc.l2.msg.GCResultMessage;
import com.tc.l2.msg.GCResultMessageFactory;
import com.tc.l2.msg.ObjectListSyncMessage;
import com.tc.l2.msg.ObjectListSyncMessageFactory;
import com.tc.l2.msg.ObjectSyncCompleteMessage;
import com.tc.l2.msg.ObjectSyncCompleteMessageFactory;
import com.tc.l2.state.StateManager;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import com.tc.net.groups.GroupMessage;
import com.tc.net.groups.GroupMessageListener;
import com.tc.net.groups.GroupResponse;
import com.tc.net.groups.NodeID;
import com.tc.objectserver.api.GCStats;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.api.ObjectManagerEventListener;
import com.tc.objectserver.tx.ServerTransactionManager;
import com.tc.objectserver.tx.TxnsInSystemCompletionLister;
import com.tc.util.Assert;
import com.tc.util.sequence.SequenceGenerator;
import com.tc.util.sequence.SequenceGenerator.SequenceGeneratorException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;

public class ReplicatedObjectManagerImpl implements ReplicatedObjectManager, GroupMessageListener,
    L2ObjectStateListener {

  private static final TCLogger              logger        = TCLogging.getLogger(ReplicatedObjectManagerImpl.class);

  private final ObjectManager                objectManager;
  private final GroupManager                 groupManager;
  private final StateManager                 stateManager;
  private final L2ObjectStateManager         l2ObjectStateManager;
  private final ReplicatedTransactionManager rTxnManager;
  private final ServerTransactionManager     transactionManager;
  private final Sink                         objectsSyncRequestSink;
  private final SequenceGenerator            sequenceGenerator;
  private final GCMonitor                    gcMonitor;
  private final AtomicLong                   gcIdGenerator = new AtomicLong();

  public ReplicatedObjectManagerImpl(GroupManager groupManager, StateManager stateManager,
                                     L2ObjectStateManager l2ObjectStateManager,
                                     ReplicatedTransactionManager txnManager, ObjectManager objectManager,
                                     ServerTransactionManager transactionManager, Sink objectsSyncRequestSink,
                                     SequenceGenerator sequenceGenerator) {
    this.groupManager = groupManager;
    this.stateManager = stateManager;
    this.rTxnManager = txnManager;
    this.objectManager = objectManager;
    this.transactionManager = transactionManager;
    this.objectsSyncRequestSink = objectsSyncRequestSink;
    this.l2ObjectStateManager = l2ObjectStateManager;
    this.sequenceGenerator = sequenceGenerator;
    this.gcMonitor = new GCMonitor();
    this.objectManager.getGarbageCollector().addListener(gcMonitor);
    l2ObjectStateManager.registerForL2ObjectStateChangeEvents(this);
    this.groupManager.registerForMessages(ObjectListSyncMessage.class, this);
  }

  /**
   * This method is used to sync up all ObjectIDs from the remote ObjectManagers. It is synchronous and after when it
   * returns nobody is allowed to join the cluster with exisiting objects.
   */
  public void sync() {
    try {
      GroupResponse gr = groupManager.sendAllAndWaitForResponse(ObjectListSyncMessageFactory
          .createObjectListSyncRequestMessage());
      Map nodeID2ObjectIDs = new LinkedHashMap();
      for (Iterator i = gr.getResponses().iterator(); i.hasNext();) {
        ObjectListSyncMessage msg = (ObjectListSyncMessage) i.next();
        if (msg.getType() == ObjectListSyncMessage.RESPONSE) {
          nodeID2ObjectIDs.put(msg.messageFrom(), msg.getObjectIDs());
        } else {
          logger.error("Received wrong response for ObjectListSyncMessage Request  from " + msg.messageFrom()
                       + " : msg : " + msg);
          groupManager.zapNode(msg.messageFrom(), L2HAZapNodeRequestProcessor.PROGRAM_ERROR,
                               "Recd wrong response from : " + msg.messageFrom() + " for ObjectListSyncMessage Request"
                                   + L2HAZapNodeRequestProcessor.getErrorString(new Throwable()));
        }
      }
      if (!nodeID2ObjectIDs.isEmpty()) {
        gcMonitor.add2L2StateManagerWhenGCDisabled(nodeID2ObjectIDs);
      }
    } catch (GroupException e) {
      logger.error(e);
      throw new AssertionError(e);
    }
  }

  // Query current state of the other L2
  public void query(NodeID nodeID) throws GroupException {
    groupManager.sendTo(nodeID, ObjectListSyncMessageFactory.createObjectListSyncRequestMessage());
  }

  public void clear(NodeID nodeID) {
    l2ObjectStateManager.removeL2(nodeID);
    gcMonitor.clear(nodeID);
  }

  public void messageReceived(NodeID fromNode, GroupMessage msg) {
    if (msg instanceof ObjectListSyncMessage) {
      ObjectListSyncMessage clusterMsg = (ObjectListSyncMessage) msg;
      handleClusterObjectMessage(fromNode, clusterMsg);
    } else {
      throw new AssertionError("ReplicatedObjectManagerImpl : Received wrong message type :" + msg.getClass().getName()
                               + " : " + msg);

    }
  }

  public void handleGCResult(GCResultMessage gcMsg) {
    Set gcedOids = gcMsg.getGCedObjectIDs();
    if (stateManager.isActiveCoordinator()) {
      logger.warn("Received GC Result from " + gcMsg.messageFrom() + " While this node is ACTIVE. Ignoring result : "
                  + gcedOids.size());
      return;
    }
    objectManager.notifyGCComplete(gcedOids);
    logger.info("Removed " + gcedOids.size() + " objects from passive ObjectManager from last GC from Active");
  }

  private void handleClusterObjectMessage(NodeID nodeID, ObjectListSyncMessage clusterMsg) {
    try {
      switch (clusterMsg.getType()) {
        case ObjectListSyncMessage.REQUEST:
          handleObjectListRequest(nodeID, clusterMsg);
          break;
        case ObjectListSyncMessage.RESPONSE:
          handleObjectListResponse(nodeID, clusterMsg);
          break;
        case ObjectListSyncMessage.FAILED_RESPONSE:
          handleObjectListFailedResponse(nodeID, clusterMsg);
          break;
        default:
          throw new AssertionError("This message shouldn't have been routed here : " + clusterMsg);
      }
    } catch (GroupException e) {
      logger.error("Error handling message : " + clusterMsg, e);
      throw new AssertionError(e);
    }
  }

  private void handleObjectListFailedResponse(NodeID nodeID, ObjectListSyncMessage clusterMsg) {
    String error = "Received wrong response from " + nodeID + " for Object List Query : " + clusterMsg;
    logger.error(error + " Forcing node to Quit !!");
    groupManager.zapNode(nodeID, L2HAZapNodeRequestProcessor.PROGRAM_ERROR, error
                                                                            + L2HAZapNodeRequestProcessor
                                                                                .getErrorString(new Throwable()));
  }

  private void handleObjectListResponse(NodeID nodeID, ObjectListSyncMessage clusterMsg) {
    Assert.assertTrue(stateManager.isActiveCoordinator());
    Set oids = clusterMsg.getObjectIDs();
    if (!oids.isEmpty()) {
      String error = "Nodes joining the cluster after startup shouldnt have any Objects. " + nodeID + " contains "
                     + oids.size() + " Objects !!!";
      logger.error(error + " Forcing node to Quit !!");
      groupManager.zapNode(nodeID, L2HAZapNodeRequestProcessor.NODE_JOINED_WITH_DIRTY_DB,
                           error + L2HAZapNodeRequestProcessor.getErrorString(new Throwable()));
    } else {
      gcMonitor.add2L2StateManagerWhenGCDisabled(nodeID, oids);
    }
  }

  private boolean add2L2StateManager(NodeID nodeID, Set oids) {
    return l2ObjectStateManager.addL2(nodeID, oids);
  }

  public void missingObjectsFor(NodeID nodeID, int missingObjects) {
    if (missingObjects == 0) {
      stateManager.moveNodeToPassiveStandby(nodeID);
      gcMonitor.syncCompleteFor(nodeID);
    } else {
      objectsSyncRequestSink.add(new SyncObjectsRequest(nodeID));
    }
  }

  public void objectSyncCompleteFor(NodeID nodeID) {
    try {
      gcMonitor.syncCompleteFor(nodeID);
      ObjectSyncCompleteMessage msg = ObjectSyncCompleteMessageFactory
          .createObjectSyncCompleteMessageFor(nodeID, sequenceGenerator.getNextSequence(nodeID));
      groupManager.sendTo(nodeID, msg);
    } catch (GroupException e) {
      logger.error("Error Sending Object Sync complete message  to : " + nodeID, e);
      groupManager.zapNode(nodeID, L2HAZapNodeRequestProcessor.COMMUNICATION_ERROR,
                           "Error sending Object Sync complete message "
                               + L2HAZapNodeRequestProcessor.getErrorString(e));
    } catch (SequenceGeneratorException e) {
      logger.error("Error Sending Object Sync complete message  to : " + nodeID, e);
    }
  }

  /**
   * ACTIVE queries PASSIVES for the list of known object ids and this response is the one that opens up the
   * transactions from ACTIVE to PASSIVE. So the replicated transaction manager is initialized here.
   */
  private void handleObjectListRequest(NodeID nodeID, ObjectListSyncMessage clusterMsg) throws GroupException {
    if (!stateManager.isActiveCoordinator()) {
      Set knownIDs = objectManager.getAllObjectIDs();
      rTxnManager.init(knownIDs);
      logger.info("Send response to Active's query : known id lists = " + knownIDs.size());
      groupManager.sendTo(nodeID, ObjectListSyncMessageFactory
          .createObjectListSyncResponseMessage(clusterMsg, knownIDs));
    } else {
      logger.error("Recd. ObjectListRequest when in ACTIVE state from " + nodeID + ". Zapping node ...");
      groupManager.sendTo(nodeID, ObjectListSyncMessageFactory.createObjectListSyncFailedResponseMessage(clusterMsg));
      // Now ZAP the node
      groupManager.zapNode(nodeID, L2HAZapNodeRequestProcessor.SPLIT_BRAIN, "Recd ObjectListRequest from : "
                                                                            + nodeID
                                                                            + " while in ACTIVE-COORDINATOR state"
                                                                            + L2HAZapNodeRequestProcessor
                                                                                .getErrorString(new Throwable()));
    }
  }

  public boolean relayTransactions() {
    return l2ObjectStateManager.getL2Count() > 0;
  }

  private static final Object ADDED = new Object();

  private final class GCMonitor implements ObjectManagerEventListener {

    boolean disabled        = false;
    Map     syncingPassives = new HashMap();

    public void garbageCollectionComplete(GCStats stats, Set deleted) {
      Map toAdd = null;
      notifyGCResultToPassives(deleted);
      synchronized (this) {
        if (syncingPassives.isEmpty()) return;
        toAdd = new LinkedHashMap();
        for (Iterator i = syncingPassives.entrySet().iterator(); i.hasNext();) {
          Entry e = (Entry) i.next();
          if (e.getValue() != ADDED) {
            NodeID nodeID = (NodeID) e.getKey();
            logger.info("GC Completed : Starting scheduled passive sync for " + nodeID);
            disableGCIfNecessary();
            // Shouldn't happen as this is in GC call back after GC completion
            assertGCDisabled();
            toAdd.put(nodeID, e.getValue());
            e.setValue(ADDED);
          }
        }
      }
      add2L2StateManager(toAdd);
    }

    private void notifyGCResultToPassives(final Set deleted) {
      if (deleted.isEmpty()) return;
      final GCResultMessage msg = GCResultMessageFactory.createGCResultMessage(deleted);
      final long id = gcIdGenerator.incrementAndGet();
      transactionManager.callBackOnTxnsInSystemCompletion(new TxnsInSystemCompletionLister() {
        public void onCompletion() {
          try {
            groupManager.sendAll(msg);
          } catch (GroupException e) {
            logger.error("Error sending gc results : ", e);
          }
        }

        public String toString() {
          return "com.tc.l2.objectserver.ReplicatedObjectManagerImpl.GCMonitor ( " + id + " ) : GC result size = "
                 + deleted.size();
        }
      });
    }

    private void add2L2StateManager(Map toAdd) {
      for (Iterator i = toAdd.entrySet().iterator(); i.hasNext();) {
        Entry e = (Entry) i.next();
        NodeID nodeID = (NodeID) e.getKey();
        if (!ReplicatedObjectManagerImpl.this.add2L2StateManager(nodeID, (Set) e.getValue())) {
          logger.warn(nodeID + " is already added to L2StateManager, clearing our internal data structures.");
          syncCompleteFor(nodeID);
        }
      }
    }

    private void disableGCIfNecessary() {
      if (!disabled) {
        disabled = objectManager.getGarbageCollector().disableGC();
        logger.info((disabled ? "GC is disabled." : "GC is is not disabled."));
      }
    }

    private void assertGCDisabled() {
      if (!disabled) { throw new AssertionError("Cant disable GC"); }
    }

    public void add2L2StateManagerWhenGCDisabled(Map nodeID2ObjectIDs) {
      if (nodeID2ObjectIDs.size() > 0 && !disabled) {
        logger.info("Disabling GC since " + nodeID2ObjectIDs.size() + " passive(s) [ " + nodeID2ObjectIDs.keySet()
                    + " ] needs to be synced up");
      }
      for (Iterator i = nodeID2ObjectIDs.entrySet().iterator(); i.hasNext();) {
        Entry e = (Entry) i.next();
        NodeID nodeID = (NodeID) e.getKey();
        add2L2StateManagerWhenGCDisabled(nodeID, (Set) e.getValue());
      }
    }

    public void add2L2StateManagerWhenGCDisabled(NodeID nodeID, Set oids) {
      boolean toAdd = false;
      synchronized (this) {
        disableGCIfNecessary();
        if (syncingPassives.containsKey(nodeID)) {
          logger.warn("Not adding " + nodeID + " since it is already present in syncingPassives : "
                      + syncingPassives.keySet());
          return;
        }
        if (disabled) {
          syncingPassives.put(nodeID, ADDED);
          toAdd = true;
        } else {
          logger
              .info("Couldnt disable GC, probably because GC is currently running. So scheduling passive sync up for later after GC completion");
          syncingPassives.put(nodeID, oids);
        }
      }
      if (toAdd) {
        if (!ReplicatedObjectManagerImpl.this.add2L2StateManager(nodeID, oids)) {
          logger.warn(nodeID + " is already added to L2StateManager, clearing our internal data structures.");
          syncCompleteFor(nodeID);
        }
      }
    }

    public synchronized void clear(NodeID nodeID) {
      Object val = syncingPassives.remove(nodeID);
      if (val != null) {
        enableGCIfNecessary();
      }
    }

    private void enableGCIfNecessary() {
      if (syncingPassives.isEmpty() && disabled) {
        logger.info("Reenabling GC as all passive are synced up");
        objectManager.getGarbageCollector().enableGC();
        disabled = false;
      }
    }

    public synchronized void syncCompleteFor(NodeID nodeID) {
      Object val = syncingPassives.remove(nodeID);
      // val could be null if the node disconnects before fully synching up.
      Assert.assertTrue(val == ADDED || val == null);
      if (val != null) {
        Assert.assertTrue(disabled);
        enableGCIfNecessary();
      }
    }

  }
}
