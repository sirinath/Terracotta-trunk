/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.handshakemanager;

import com.tc.async.api.Sink;
import com.tc.cluster.Cluster;
import com.tc.logging.TCLogger;
import com.tc.net.GroupID;
import com.tc.net.NodeID;
import com.tc.net.protocol.tcm.ChannelEvent;
import com.tc.net.protocol.tcm.ChannelEventListener;
import com.tc.net.protocol.tcm.ChannelEventType;
import com.tc.object.ClientIDProvider;
import com.tc.object.context.PauseContext;
import com.tc.object.msg.ClientHandshakeAckMessage;
import com.tc.object.msg.ClientHandshakeMessage;
import com.tc.object.msg.ClientHandshakeMessageFactory;
import com.tc.object.net.DSOClientMessageChannel;
import com.tc.object.session.SessionManager;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.State;
import com.tc.util.Util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ClientHandshakeManagerImpl implements ClientHandshakeManager, ChannelEventListener {
  private static final State                  PAUSED             = new State("PAUSED");
  private static final State                  STARTING           = new State("STARTING");
  private static final State                  RUNNING            = new State("RUNNING");

  private final Collection                    callBacks;
  private final ClientIDProvider              cidp;
  private final ClientHandshakeMessageFactory chmf;
  private final TCLogger                      logger;
  private final Sink                          pauseSink;
  private final SessionManager                sessionManager;
  private final Cluster                       cluster;
  private final String                        clientVersion;
  private final Map                           groupStates        = new HashMap();
  private final GroupID[]                     groupIDs;
  private volatile int                        disconnected;
  private volatile boolean                    serverIsPersistent = false;

  public ClientHandshakeManagerImpl(TCLogger logger, DSOClientMessageChannel channel,
                                    ClientHandshakeMessageFactory chmf, Sink pauseSink, SessionManager sessionManager,
                                    Cluster cluster, String clientVersion, Collection callbacks) {
    this.logger = logger;
    this.cidp = channel.getClientIDProvider();
    this.chmf = chmf;
    this.pauseSink = pauseSink;
    this.sessionManager = sessionManager;
    this.cluster = cluster;
    this.clientVersion = clientVersion;
    this.callBacks = callbacks;
    this.groupIDs = channel.getGroupIDs();
    this.disconnected = groupIDs.length;
    initGroupStates(PAUSED);
    pauseCallbacks(GroupID.ALL_GROUPS, disconnected);
  }

  private synchronized void initGroupStates(State state) {
    for (int i = 0; i < groupIDs.length; i++) {
      groupStates.put(groupIDs[i], state);
    }

  }

  public void initiateHandshake(NodeID remoteNode) {
    logger.debug("Initiating handshake...");
    changeToStarting(remoteNode);

    ClientHandshakeMessage handshakeMessage = chmf.newClientHandshakeMessage(remoteNode);
    handshakeMessage.setClientVersion(clientVersion);

    notifyCallbackOnHandshake(remoteNode, handshakeMessage);

    logger.debug("Sending handshake message...");
    handshakeMessage.send();
  }

  public void notifyChannelEvent(ChannelEvent event) {
    if (GroupID.ALL_GROUPS.equals(event.getChannel().getRemoteNodeID())) { throw new AssertionError(
                                                                                                    "Recd event for Group Channel : "
                                                                                                        + event); }
    if (event.getType() == ChannelEventType.TRANSPORT_DISCONNECTED_EVENT) {
      cluster.thisNodeDisconnected();
      pauseSink.add(new PauseContext(true, event.getChannel().getRemoteNodeID()));
    } else if (event.getType() == ChannelEventType.TRANSPORT_CONNECTED_EVENT) {
      pauseSink.add(new PauseContext(false, event.getChannel().getRemoteNodeID()));
    } else if (event.getType() == ChannelEventType.CHANNEL_CLOSED_EVENT) {
      cluster.thisNodeDisconnected();
    }
  }

  public void disconnected(NodeID remoteNode) {
    logger.info("Disconnected: Pausing from " + getState(remoteNode) + " RemoteNode : " + remoteNode
                + " Disconnected : " + getDisconnectedCount());
    if (getState(remoteNode) == PAUSED) {
      logger.warn("Pause called while already PAUSED for " + remoteNode);
      // ClientMessageChannel moves to next SessionID, need to move to newSession here too.
    } else {
      changeToPaused(remoteNode);
      pauseCallbacks(remoteNode, getDisconnectedCount());
      // all the activities paused then can switch to new session
      sessionManager.newSession(remoteNode);
      logger.info("ClientHandshakeManager moves to " + sessionManager);
    }
  }

  public void connected(NodeID remoteNode) {
    logger.info("Connected: Unpausing from " + getState(remoteNode) + " RemoteNode : " + remoteNode
                + " Disconnected : " + getDisconnectedCount());
    if (getState(remoteNode) != PAUSED) {
      logger.warn("Unpause called while not PAUSED for " + remoteNode);
      return;
    }
    initiateHandshake(remoteNode);
  }

  public void acknowledgeHandshake(ClientHandshakeAckMessage handshakeAck) {
    acknowledgeHandshake(handshakeAck.getSourceNodeID(), handshakeAck.getPersistentServer(), handshakeAck
        .getThisNodeId(), handshakeAck.getAllNodes(), handshakeAck.getServerVersion());
  }

  protected void acknowledgeHandshake(NodeID remoteID, boolean persistentServer, String thisNodeId,
                                      String[] clusterMembers, String serverVersion) {
    logger.info("Received Handshake ack for this node :" + remoteID);
    if (getState(remoteID) != STARTING) {
      logger.warn("Handshake acknowledged while not STARTING: " + getState(remoteID));
      return;
    }

    checkClientServerVersionMatch(serverVersion);
    this.serverIsPersistent = persistentServer;
    cluster.thisNodeConnected(thisNodeId, clusterMembers);
    changeToRunning(remoteID);
    unpauseCallbacks(remoteID, getDisconnectedCount());
  }

  protected void checkClientServerVersionMatch(String serverVersion) {
    final boolean checkVersionMatches = TCPropertiesImpl.getProperties()
        .getBoolean(TCPropertiesConsts.L1_CONNECT_VERSION_MATCH_CHECK);
    if (checkVersionMatches && !clientVersion.equals(serverVersion)) {
      final String msg = "Client/Server Version Mismatch Error: Client Version: " + clientVersion
                         + ", Server Version: " + serverVersion + ".  Terminating client now.";
      throw new RuntimeException(msg);
    }
  }

  private void pauseCallbacks(NodeID remote, int disconnectedCount) {
    for (Iterator i = callBacks.iterator(); i.hasNext();) {
      ClientHandshakeCallback c = (ClientHandshakeCallback) i.next();
      c.pause(remote, disconnectedCount);
    }
  }

  private void notifyCallbackOnHandshake(NodeID remote, ClientHandshakeMessage handshakeMessage) {
    for (Iterator i = callBacks.iterator(); i.hasNext();) {
      ClientHandshakeCallback c = (ClientHandshakeCallback) i.next();
      c.initializeHandshake(cidp.getClientID(), remote, handshakeMessage);
    }
  }

  private void unpauseCallbacks(NodeID remote, int disconnectedCount) {
    for (Iterator i = callBacks.iterator(); i.hasNext();) {
      ClientHandshakeCallback c = (ClientHandshakeCallback) i.next();
      c.unpause(remote, disconnectedCount);
    }
  }

  public boolean serverIsPersistent() {
    return this.serverIsPersistent;
  }

  public synchronized void waitForHandshake() {
    boolean isInterrupted = false;
    while (disconnected != 0) {
      try {
        wait();
      } catch (InterruptedException e) {
        logger.error("Interrupted while waiting for handshake");
        isInterrupted = true;
      }
    }
    Util.selfInterruptIfNeeded(isInterrupted);
  }

  private synchronized void changeToPaused(NodeID node) {
    Object old = groupStates.put(node, PAUSED);
    assert old != PAUSED;
    disconnected++;
    assert disconnected <= groupIDs.length;
    notifyAll();
  }

  private synchronized void changeToStarting(NodeID node) {
    Object old = groupStates.put(node, STARTING);
    assert old == PAUSED;
  }

  private synchronized void changeToRunning(NodeID node) {
    Object old = groupStates.put(node, RUNNING);
    assert old == STARTING;
    disconnected--;
    assert disconnected >= 0;
    notifyAll();
  }

  private synchronized State getState(NodeID node) {
    return (State) groupStates.get(node);
  }

  private int getDisconnectedCount() {
    return disconnected;
  }

}
