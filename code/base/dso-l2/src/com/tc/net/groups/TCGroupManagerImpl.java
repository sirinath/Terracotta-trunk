/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.groups;

import com.tc.async.api.Sink;
import com.tc.async.api.Stage;
import com.tc.async.api.StageManager;
import com.tc.config.schema.setup.L2TVSConfigurationSetupManager;
import com.tc.exception.TCRuntimeException;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.MaxConnectionsExceededException;
import com.tc.net.NodeID;
import com.tc.net.ServerID;
import com.tc.net.TCSocketAddress;
import com.tc.net.core.ConnectionAddressProvider;
import com.tc.net.core.ConnectionInfo;
import com.tc.net.protocol.NetworkStackHarnessFactory;
import com.tc.net.protocol.PlainNetworkStackHarnessFactory;
import com.tc.net.protocol.delivery.L2ReconnectConfigImpl;
import com.tc.net.protocol.delivery.OOOEventHandler;
import com.tc.net.protocol.delivery.OOONetworkStackHarnessFactory;
import com.tc.net.protocol.delivery.OnceAndOnlyOnceProtocolNetworkLayerFactoryImpl;
import com.tc.net.protocol.tcm.ChannelEvent;
import com.tc.net.protocol.tcm.ChannelEventListener;
import com.tc.net.protocol.tcm.ChannelEventType;
import com.tc.net.protocol.tcm.ChannelManagerEventListener;
import com.tc.net.protocol.tcm.ClientMessageChannel;
import com.tc.net.protocol.tcm.CommunicationsManager;
import com.tc.net.protocol.tcm.CommunicationsManagerImpl;
import com.tc.net.protocol.tcm.HydrateHandler;
import com.tc.net.protocol.tcm.MessageChannel;
import com.tc.net.protocol.tcm.NetworkListener;
import com.tc.net.protocol.tcm.NullMessageMonitor;
import com.tc.net.protocol.tcm.TCMessageType;
import com.tc.net.protocol.transport.ConnectionPolicy;
import com.tc.net.protocol.transport.DefaultConnectionIdFactory;
import com.tc.net.protocol.transport.HealthCheckerConfigImpl;
import com.tc.net.protocol.transport.NullConnectionPolicy;
import com.tc.object.config.schema.NewL2DSOConfig;
import com.tc.object.session.NullSessionManager;
import com.tc.object.session.SessionManagerImpl;
import com.tc.object.session.SessionProvider;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.handler.ReceiveGroupMessageHandler;
import com.tc.objectserver.handler.TCGroupHandshakeMessageHandler;
import com.tc.objectserver.handler.TCGroupMemberDiscoveryHandler;
import com.tc.properties.ReconnectConfig;
import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.tc.util.TCTimeoutException;
import com.tc.util.UUID;
import com.tc.util.sequence.Sequence;
import com.tc.util.sequence.SimpleSequence;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class TCGroupManagerImpl implements GroupManager, ChannelManagerEventListener {
  private static final TCLogger                             logger                      = TCLogging
                                                                                            .getLogger(TCGroupManagerImpl.class);
  public static final String                                HANDSHAKE_STATE_MACHINE_TAG = "TcGroupCommHandshake";
  private final ReconnectConfig                             l2ReconnectConfig;

  private final ServerID                                    thisNodeID;
  private final int                                         groupPort;
  private final ConnectionPolicy                            connectionPolicy;
  private final CopyOnWriteArrayList<GroupEventsListener>   groupListeners              = new CopyOnWriteArrayList<GroupEventsListener>();
  private final Map<String, GroupMessageListener>           messageListeners            = new ConcurrentHashMap<String, GroupMessageListener>();
  private final Map<MessageID, GroupResponse>               pendingRequests             = new ConcurrentHashMap<MessageID, GroupResponse>();
  private final AtomicBoolean                               isStopped                   = new AtomicBoolean(false);
  private final ConcurrentHashMap<MessageChannel, ServerID> channelToNodeID             = new ConcurrentHashMap<MessageChannel, ServerID>();
  private final ConcurrentHashMap<ServerID, TCGroupMember>  members                     = new ConcurrentHashMap<ServerID, TCGroupMember>();
  private final ConcurrentHashMap<String, TCGroupMember>    nodenameToMembers           = new ConcurrentHashMap<String, TCGroupMember>();
  private final Timer                                       handshakeTimer              = new Timer(true);
  private final Set<NodeID>                                 zappedSet                   = Collections
                                                                                            .synchronizedSet(new HashSet<NodeID>());
  private final StageManager                                stageManager;
  private final boolean                                     isUseOOOLayer;

  private CommunicationsManager                             communicationsManager;
  private NetworkListener                                   groupListener;
  private TCGroupMemberDiscovery                            discover;
  private ZapNodeRequestProcessor                           zapNodeRequestProcessor     = new DefaultZapNodeRequestProcessor(
                                                                                                                             logger);
  private Stage                                             hydrateStage;
  private Stage                                             receiveGroupMessageStage;
  private Stage                                             handshakeMessageStage;
  private Stage                                             discoveryStage;
  private TCProperties                                      l2Properties;

  /*
   * Setup a communication manager which can establish channel from either sides.
   */
  public TCGroupManagerImpl(L2TVSConfigurationSetupManager configSetupManager, StageManager stageManager,
                            ServerID thisNodeID) {
    this(configSetupManager, new NullConnectionPolicy(), stageManager, thisNodeID);
  }

  public TCGroupManagerImpl(L2TVSConfigurationSetupManager configSetupManager, ConnectionPolicy connectionPolicy,
                            StageManager stageManager, ServerID thisNodeID) {
    this.connectionPolicy = connectionPolicy;
    this.stageManager = stageManager;
    this.thisNodeID = thisNodeID;
    l2ReconnectConfig = new L2ReconnectConfigImpl();
    this.isUseOOOLayer = l2ReconnectConfig.getReconnectEnabled();

    configSetupManager.commonl2Config().changesInItemIgnored(configSetupManager.commonl2Config().dataPath());
    NewL2DSOConfig l2DSOConfig = configSetupManager.dsoL2Config();

    l2DSOConfig.changesInItemIgnored(l2DSOConfig.l2GroupPort());
    this.groupPort = l2DSOConfig.l2GroupPort().getInt();

    TCSocketAddress socketAddress;
    try {
      int groupConnectPort = groupPort;

      // proxy group port. use a different group port from tc.properties (if exist) than the one on tc-config
      // currently used by L2Reconnect proxy test.
      groupConnectPort = TCPropertiesImpl.getProperties()
          .getInt(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_RECONNECT_L2PROXY_TO_PORT, groupPort);

      socketAddress = new TCSocketAddress(l2DSOConfig.bind().getString(), groupConnectPort);
    } catch (UnknownHostException e) {
      throw new TCRuntimeException(e);
    }
    init(socketAddress);
    Assert.assertNotNull(thisNodeID);
    setDiscover(new TCGroupMemberDiscoveryStatic(this));
  }

  /*
   * for testing purpose only. Tester needs to do setDiscover().
   */
  TCGroupManagerImpl(ConnectionPolicy connectionPolicy, String hostname, int port, int groupPort,
                     StageManager stageManager) {
    this.connectionPolicy = connectionPolicy;
    this.stageManager = stageManager;
    l2ReconnectConfig = new L2ReconnectConfigImpl();
    this.isUseOOOLayer = l2ReconnectConfig.getReconnectEnabled();
    this.groupPort = groupPort;
    thisNodeID = new ServerID(new Node(hostname, port).getServerNodeName(), UUID.getUUID().toString().getBytes());
    logger.info("Creating server nodeID: " + thisNodeID);
    init(new TCSocketAddress(TCSocketAddress.WILDCARD_ADDR, groupPort));
  }

  private void init(TCSocketAddress socketAddress) {
    int maxStageSize = 5000;
    hydrateStage = stageManager.createStage(ServerConfigurationContext.GROUP_HYDRATE_MESSAGE_STAGE,
                                            new HydrateHandler(), 1, maxStageSize);
    receiveGroupMessageStage = stageManager.createStage(ServerConfigurationContext.RECEIVE_GROUP_MESSAGE_STAGE,
                                                        new ReceiveGroupMessageHandler(this), 1, maxStageSize);
    handshakeMessageStage = stageManager.createStage(ServerConfigurationContext.GROUP_HANDSHAKE_MESSAGE_STAGE,
                                                     new TCGroupHandshakeMessageHandler(this), 1, maxStageSize);
    discoveryStage = stageManager.createStage(ServerConfigurationContext.GROUP_DISCOVERY_STAGE,
                                              new TCGroupMemberDiscoveryHandler(this), 4, maxStageSize);

    final NetworkStackHarnessFactory networkStackHarnessFactory;
    if (isUseOOOLayer) {
      final Stage oooSendStage = stageManager.createStage(ServerConfigurationContext.L2_OOO_NET_SEND_STAGE,
                                                          new OOOEventHandler(), 1, maxStageSize);
      final Stage oooReceiveStage = stageManager.createStage(ServerConfigurationContext.L2_OOO_NET_RECEIVE_STAGE,
                                                             new OOOEventHandler(), 1, maxStageSize);
      networkStackHarnessFactory = new OOONetworkStackHarnessFactory(
                                                                     new OnceAndOnlyOnceProtocolNetworkLayerFactoryImpl(),
                                                                     oooSendStage.getSink(), oooReceiveStage.getSink(),
                                                                     l2ReconnectConfig);
    } else {
      networkStackHarnessFactory = new PlainNetworkStackHarnessFactory();
    }

    l2Properties = TCPropertiesImpl.getProperties().getPropertiesFor("l2");
    communicationsManager = new CommunicationsManagerImpl(new NullMessageMonitor(), networkStackHarnessFactory,
                                                          this.connectionPolicy, 0,
                                                          new HealthCheckerConfigImpl(l2Properties
                                                              .getPropertiesFor("healthcheck.l2"), "TCGroupManager"),
                                                          thisNodeID);

    groupListener = communicationsManager.createListener(new NullSessionManager(), socketAddress, true,
                                                         new DefaultConnectionIdFactory());
    // Listen to channel creation/removal
    groupListener.getChannelManager().addEventListener(this);

    groupListener.addClassMapping(TCMessageType.GROUP_WRAPPER_MESSAGE, TCGroupMessageWrapper.class);
    groupListener.routeMessageType(TCMessageType.GROUP_WRAPPER_MESSAGE, receiveGroupMessageStage.getSink(),
                                   hydrateStage.getSink());
    groupListener.addClassMapping(TCMessageType.GROUP_HANDSHAKE_MESSAGE, TCGroupHandshakeMessage.class);
    groupListener.routeMessageType(TCMessageType.GROUP_HANDSHAKE_MESSAGE, handshakeMessageStage.getSink(), hydrateStage
        .getSink());

    registerForMessages(GroupZapNodeMessage.class, new ZapNodeRequestRouter());
  }

  /*
   * getDiscoveryHandlerSink -- sink for discovery to enqueue tasks for open channel.
   */
  protected Sink getDiscoveryHandlerSink() {
    return discoveryStage.getSink();
  }

  /*
   * Once connected, both send NodeID to each other.
   */
  private void handshake(final MessageChannel channel) {
    getOrCreateHandshakeStateMachine(channel);
  }

  public void receivedHandshake(TCGroupHandshakeMessage msg) {
    if (logger.isDebugEnabled()) logger.debug("Received group handshake message from " + msg.getChannel());

    MessageChannel channel = msg.getChannel();
    Assert.assertNotNull(channel);
    TCGroupHandshakeStateMachine stateMachine = getOrCreateHandshakeStateMachine(channel);
    stateMachine.execute(msg);
  }

  public NodeID getLocalNodeID() {
    return getNodeID();
  }

  private ServerID getNodeID() {
    return thisNodeID;
  }

  private void membersClear() {
    members.clear();
    nodenameToMembers.clear();
  }

  private void membersAdd(TCGroupMember member) {
    ServerID nodeID = member.getPeerNodeID();
    members.put(nodeID, member);
    nodenameToMembers.put(nodeID.getName(), member);
  }

  private void membersRemove(TCGroupMember member) {
    ServerID nodeID = member.getPeerNodeID();
    members.remove(nodeID);
    nodenameToMembers.remove(nodeID.getName());
  }

  private void removeIfMemberReconnecting(ServerID newNodeID) {
    TCGroupMember oldMember = nodenameToMembers.get(newNodeID.getName());
    if ((oldMember != null) && (oldMember.getPeerNodeID() != newNodeID)) {
      MessageChannel channel = oldMember.getChannel();
      if (!channel.isConnected()) { // channel may be reconnecting
        channel.close();
        logger.warn("Remove not connected member " + oldMember);
      }
    }
  }

  public void stop(long timeout) throws TCTimeoutException {
    isStopped.set(true);
    stageManager.stopAll();
    discover.stop(timeout);
    groupListener.stop(timeout);
    communicationsManager.shutdown();
    for (TCGroupMember m : members.values()) {
      notifyAnyPendingRequests(m);
    }
    membersClear();
    channelToNodeID.clear();
  }

  public boolean isStopped() {
    return (isStopped.get());
  }

  public void registerForGroupEvents(GroupEventsListener listener) {
    groupListeners.add(listener);
  }

  private void fireNodeEvent(TCGroupMember member, boolean joined) {
    ServerID newNode = member.getPeerNodeID();
    member.setReady(joined);
    if (logger.isDebugEnabled()) logger.debug("fireNodeEvent: joined = " + joined + ", node = " + newNode);
    for (GroupEventsListener listener : groupListeners) {
      if (joined) {
        listener.nodeJoined(newNode);
      } else {
        listener.nodeLeft(newNode);
      }
    }
  }

  private boolean tryAddMember(TCGroupMember member) {
    if (isStopped.get()) { return false; }

    synchronized (members) {
      if (null != members.get(member.getPeerNodeID())) {
        // there is one exist already
        return false;
      }
      member.setTCGroupManager(this);
      membersAdd(member);
    }
    logger.debug(getNodeID() + " added " + member);
    return true;
  }

  public NodeID join(Node thisNode, Node[] allNodes) throws GroupException {

    // discover must be started before listener thread to avoid missing nodeJoined group events.
    discover.setupNodes(thisNode, allNodes);
    discover.start();
    try {
      groupListener.start(new HashSet());
    } catch (IOException e) {
      throw new GroupException(e);
    }
    return (getNodeID());
  }

  public void memberDisappeared(TCGroupMember member, boolean byDisconnectEvent) {
    Assert.assertNotNull(member);
    if (isStopped.get()) return;
    member.setTCGroupManager(null);
    TCGroupMember m = members.get(member.getPeerNodeID());
    if ((m != null) && (m.getChannel() == member.getChannel())) {
      membersRemove(member);
      if (member.isJoinedEventFired()) fireNodeEvent(member, false);
      member.setJoinedEventFired(false);
      notifyAnyPendingRequests(member);
    }
    closeMember(member, false, byDisconnectEvent);
    logger.debug(getNodeID() + " removed " + member);
  }

  private void closeMember(TCGroupMember member, boolean isAdded, boolean byDisconnectEvent) {
    member.setReady(false);
    channelToNodeID.remove(member.getChannel());
    if (isAdded) membersRemove(member);
    if (!byDisconnectEvent) member.close();
  }

  private void notifyAnyPendingRequests(TCGroupMember member) {
    synchronized (pendingRequests) {
      for (Iterator<GroupResponse> i = pendingRequests.values().iterator(); i.hasNext();) {
        GroupResponseImpl response = (GroupResponseImpl) i.next();
        response.notifyMemberDead(member);
      }
    }
  }

  public void sendAll(GroupMessage msg) {
    sendAll(msg, members.keySet());
  }

  public void sendAll(GroupMessage msg, Set nodeIDs) {
    for (TCGroupMember m : members.values()) {
      if (!nodeIDs.contains(m.getPeerNodeID())) continue;
      if (m.isReady()) {
        m.sendIgnoreNotReady(msg);
      } else {
        logger.warn("Send to a not ready member " + m);
      }
    }
  }

  public void sendTo(NodeID node, GroupMessage msg) throws GroupException {
    TCGroupMember member = getMember(node);
    if (member != null && member.isReady()) {
      member.send(msg);
    } else {
      throw new GroupException("Send to " + ((member == null) ? "non-exist" : "not ready") + " member of " + node);
    }
  }

  public GroupMessage sendToAndWaitForResponse(NodeID nodeID, GroupMessage msg) throws GroupException {
    if (logger.isDebugEnabled()) logger.debug(getNodeID() + " : Sending to " + nodeID + " and Waiting for Response : "
                                              + msg.getMessageID());
    GroupResponseImpl groupResponse = new GroupResponseImpl(this);
    MessageID msgID = msg.getMessageID();
    TCGroupMember m = getMember(nodeID);
    if ((m != null) && m.isReady()) {
      GroupResponse old = pendingRequests.put(msgID, groupResponse);
      Assert.assertNull(old);
      groupResponse.sendTo(m, msg);
      groupResponse.waitForResponses(getNodeID());
      pendingRequests.remove(msgID);
    } else {
      String errorMsg = "Node " + nodeID + " not present in the group. Ignoring Message : " + msg;
      logger.error(errorMsg);
      throw new GroupException(errorMsg);
    }
    return groupResponse.getResponse(nodeID);

  }

  public GroupResponse sendAllAndWaitForResponse(GroupMessage msg) throws GroupException {
    return sendAllAndWaitForResponse(msg, members.keySet());
  }

  public GroupResponse sendAllAndWaitForResponse(GroupMessage msg, Set nodeIDs) throws GroupException {
    if (logger.isDebugEnabled()) logger.debug(getNodeID() + " : Sending to ALL and Waiting for Response : "
                                              + msg.getMessageID());
    GroupResponseImpl groupResponse = new GroupResponseImpl(this);
    MessageID msgID = msg.getMessageID();
    GroupResponse old = pendingRequests.put(msgID, groupResponse);
    Assert.assertNull(old);
    groupResponse.sendAll(msg, nodeIDs);
    groupResponse.waitForResponses(getNodeID());
    pendingRequests.remove(msgID);
    return groupResponse;
  }

  private void openChannel(ConnectionAddressProvider addrProvider, ChannelEventListener listener)
      throws TCTimeoutException, UnknownHostException, MaxConnectionsExceededException, IOException {

    if (isStopped.get()) return;

    int maxReconnectTries = isUseOOOLayer ? -1 : 0;
    SessionProvider sessionProvider = new SessionManagerImpl(new SessionManagerImpl.SequenceFactory() {
      public Sequence newSequence() {
        return new SimpleSequence();
      }
    });
    ClientMessageChannel channel = communicationsManager.createClientChannel(sessionProvider, maxReconnectTries, null,
                                                                             -1, 10000, addrProvider, groupPort);

    channel.addClassMapping(TCMessageType.GROUP_WRAPPER_MESSAGE, TCGroupMessageWrapper.class);
    channel.routeMessageType(TCMessageType.GROUP_WRAPPER_MESSAGE, receiveGroupMessageStage.getSink(), hydrateStage
        .getSink());
    channel.addClassMapping(TCMessageType.GROUP_HANDSHAKE_MESSAGE, TCGroupHandshakeMessage.class);
    channel.routeMessageType(TCMessageType.GROUP_HANDSHAKE_MESSAGE, handshakeMessageStage.getSink(), hydrateStage
        .getSink());

    channel.addListener(listener);
    channel.open();

    handshake(channel);
    return;
  }

  public void openChannel(String hostname, int port, ChannelEventListener listener) throws TCTimeoutException,
      UnknownHostException, MaxConnectionsExceededException, IOException {
    openChannel(new ConnectionAddressProvider(new ConnectionInfo[] { new ConnectionInfo(hostname, port) }), listener);
  }

  /*
   * Event notification when a new connection setup by channelManager channel opened from dst to src
   */
  public void channelCreated(MessageChannel aChannel) {
    if (isStopped.get()) {
      aChannel.close();
      return;
    }
    handshake(aChannel);
  }

  /*
   * Event notification when a connection removed by DSOChannelManager
   */
  public void channelRemoved(MessageChannel channel) {
    TCGroupHandshakeStateMachine stateMachine = getHandshakeStateMachine(channel);
    if (stateMachine != null) {
      stateMachine.disconnected();
    }
  }

  /*
   * receivedNodeID -- Store NodeID of connected channels
   */
  void receivedNodeID(MessageChannel channel, ServerID nodeID) {
    channelToNodeID.put(channel, nodeID);
  }

  private TCGroupMember getMember(MessageChannel channel) {
    ServerID nodeID = channelToNodeID.get(channel);
    if (nodeID == null) return null;
    TCGroupMember m = members.get(nodeID);
    return ((m != null) && (m.getChannel() == channel)) ? m : null;
  }

  private TCGroupMember getMember(NodeID nodeID) {
    return (members.get(nodeID));
  }

  public Collection<TCGroupMember> getMembers() {
    return Collections.unmodifiableCollection(members.values());
  }

  public void setDiscover(TCGroupMemberDiscovery discover) {
    this.discover = discover;
  }

  public TCGroupMemberDiscovery getDiscover() {
    return discover;
  }

  public Timer getHandshakeTimer() {
    return (handshakeTimer);
  }

  public void shutdown() {
    try {
      stop(1000);
    } catch (TCTimeoutException e) {
      logger.warn("Timeout at shutting down " + e);
    }
  }

  /*
   * for testing only
   */
  int size() {
    return members.size();
  }

  public void messageReceived(GroupMessage message, MessageChannel channel) {

    if (isStopped.get()) return;

    if (logger.isDebugEnabled()) logger.debug(getNodeID() + " recd msg " + message.getMessageID() + " From " + channel
                                              + " Msg : " + message);

    TCGroupMember m = getMember(channel);

    if (channel.isClosed()) {
      logger
          .warn(getNodeID() + " recd msg " + message.getMessageID() + " From closed " + channel + " Msg : " + message);
      return;
    }

    if (m == null) {
      String errInfo = "Received message for non-exist member from " + channel.getRemoteAddress() + " to "
                       + channel.getLocalAddress() + " Node: " + channelToNodeID.get(channel) + " msg: " + message;
      TCGroupHandshakeStateMachine stateMachine = getHandshakeStateMachine(channel);
      if (stateMachine != null && stateMachine.isFailureState()) {
        // message received after node left
        logger.warn(errInfo);
        return;
      } else {
        throw new RuntimeException(errInfo);
      }
    }

    ServerID from = m.getPeerNodeID();
    MessageID requestID = message.inResponseTo();

    message.setMessageOrginator(from);
    if (requestID.isNull() || !notifyPendingRequests(requestID, message, from)) {
      fireMessageReceivedEvent(from, message);
    }
  }

  private boolean notifyPendingRequests(MessageID requestID, GroupMessage gmsg, ServerID nodeID) {
    GroupResponseImpl response = (GroupResponseImpl) pendingRequests.get(requestID);
    if (response != null) {
      response.addResponseFrom(nodeID, gmsg);
      return true;
    }
    return false;
  }

  private static void validateExternalizableClass(Class<AbstractGroupMessage> clazz) {
    String name = clazz.getName();
    try {
      Constructor<AbstractGroupMessage> cons = clazz.getDeclaredConstructor(new Class[0]);
      if ((cons.getModifiers() & Modifier.PUBLIC) == 0) { throw new AssertionError(
                                                                                   name
                                                                                       + " : public no arg constructor not found"); }
    } catch (NoSuchMethodException ex) {
      throw new AssertionError(name + " : public no arg constructor not found");
    }
  }

  public void registerForMessages(Class msgClass, GroupMessageListener listener) {
    validateExternalizableClass(msgClass);
    GroupMessageListener prev = messageListeners.put(msgClass.getName(), listener);
    if (prev != null) {
      logger.warn("Previous listener removed : " + prev);
    }
  }

  public void routeMessages(Class msgClass, Sink sink) {
    registerForMessages(msgClass, new RouteGroupMessagesToSink(msgClass.getName(), sink));
  }

  private void fireMessageReceivedEvent(ServerID from, GroupMessage msg) {
    GroupMessageListener listener = messageListeners.get(msg.getClass().getName());
    if (listener != null) {
      listener.messageReceived(from, msg);
    } else {
      String errorMsg = "No Route for " + msg + " from " + from;
      logger.error(errorMsg);
      throw new AssertionError(errorMsg);
    }
  }

  public void setZapNodeRequestProcessor(ZapNodeRequestProcessor processor) {
    this.zapNodeRequestProcessor = processor;
  }

  public void zapNode(NodeID nodeID, int type, String reason) {
    zappedSet.add(nodeID);
    TCGroupMember m = getMember(nodeID);
    if (m == null) {
      logger.warn("Ignoring Zap node request since Member is null");
    } else if (!zapNodeRequestProcessor.acceptOutgoingZapNodeRequest(nodeID, type, reason)) {
      logger.warn("Ignoreing Zap node request since " + zapNodeRequestProcessor + " asked us to : " + nodeID
                  + " type = " + type + " reason = " + reason);
    } else {
      long weights[] = zapNodeRequestProcessor.getCurrentNodeWeights();
      logger.warn("Zapping node : " + nodeID + " type = " + type + " reason = " + reason + " my weight = "
                  + Arrays.toString(weights));
      GroupMessage msg = GroupZapNodeMessageFactory.createGroupZapNodeMessage(type, reason, weights);
      try {
        sendTo(nodeID, msg);
      } catch (GroupException e) {
        logger.error("Error sending ZapNode Request to " + nodeID + " msg = " + msg);
      }
    }
  }

  private boolean isZappedNode(NodeID nodeID) {
    return (zappedSet.contains(nodeID));
  }

  private static class GroupResponseImpl implements GroupResponse {

    private final Set<ServerID>      waitFor   = new HashSet<ServerID>();
    private final List<GroupMessage> responses = new ArrayList<GroupMessage>();
    private final TCGroupManagerImpl manager;

    GroupResponseImpl(TCGroupManagerImpl manager) {
      this.manager = manager;
    }

    public synchronized List<GroupMessage> getResponses() {
      Assert.assertTrue(waitFor.isEmpty());
      return responses;
    }

    public synchronized GroupMessage getResponse(NodeID nodeID) {
      Assert.assertTrue(waitFor.isEmpty());
      for (Iterator<GroupMessage> i = responses.iterator(); i.hasNext();) {
        GroupMessage msg = i.next();
        if (nodeID.equals(msg.messageFrom())) return msg;
      }
      logger.warn("Missing response message from " + nodeID);
      return null;
    }

    public synchronized void sendTo(TCGroupMember member, GroupMessage msg) throws GroupException {
      if (member.isReady()) {
        Assert.assertNotNull(member.getPeerNodeID());
        waitFor.add(member.getPeerNodeID());
        member.send(msg);
      } else {
        throw new GroupException("Send to a not ready member " + member);
      }
    }

    public synchronized void sendAll(GroupMessage msg) {
      sendAll(msg, manager.members.keySet());
    }

    public synchronized void sendAll(GroupMessage msg, Set nodeIDs) {
      for (TCGroupMember m : manager.getMembers()) {
        if (!nodeIDs.contains(m.getPeerNodeID())) continue;
        if (m.isReady()) {
          Assert.assertNotNull(m.getPeerNodeID());
          waitFor.add(m.getPeerNodeID());
          m.sendIgnoreNotReady(msg);
        } else {
          logger.warn("SendAllAndWait to a not ready member " + m);
        }
      }
    }

    public synchronized void addResponseFrom(ServerID nodeID, GroupMessage gmsg) {
      if (!waitFor.remove(nodeID)) {
        String message = "Recd response from a member not in list : " + nodeID + " : waiting For : " + waitFor
                         + " msg : " + gmsg;
        logger.error(message);
        throw new AssertionError(message);
      }
      responses.add(gmsg);
      notifyAll();
    }

    public synchronized void notifyMemberDead(TCGroupMember member) {
      logger.warn("Remove dead member from waitFor response list, dead member: " + member.getPeerNodeID());
      waitFor.remove(member.getPeerNodeID());
      notifyAll();
    }

    public synchronized void waitForResponses(ServerID sender) throws GroupException {
      long start = System.currentTimeMillis();
      while (!waitFor.isEmpty() && !manager.isStopped()) {
        try {
          this.wait(5000);
          long end = System.currentTimeMillis();
          if (!waitFor.isEmpty() && (end - start) > 5000) {
            logger.warn(sender + " Still waiting for response from " + waitFor + ". Waited for " + (end - start)
                        + " ms");
          }
        } catch (InterruptedException e) {
          throw new GroupException(e);
        }
      }
    }
  }

  private final class ZapNodeRequestRouter implements GroupMessageListener {

    public void messageReceived(NodeID fromNode, GroupMessage msg) {
      GroupZapNodeMessage zapMsg = (GroupZapNodeMessage) msg;
      zapNodeRequestProcessor.incomingZapNodeRequest(msg.messageFrom(), zapMsg.getZapNodeType(), zapMsg.getReason(),
                                                     zapMsg.getWeights());
    }
  }

  private synchronized TCGroupHandshakeStateMachine getOrCreateHandshakeStateMachine(MessageChannel channel) {
    TCGroupHandshakeStateMachine stateMachine = (TCGroupHandshakeStateMachine) channel
        .getAttachment(HANDSHAKE_STATE_MACHINE_TAG);
    if (stateMachine == null) {
      stateMachine = new TCGroupHandshakeStateMachine(this, channel, getNodeID());
      channel.addAttachment(HANDSHAKE_STATE_MACHINE_TAG, stateMachine, false);
      channel.addListener(new HandshakeChannelEventListener(stateMachine));
      stateMachine.start();
    }
    Assert.assertNotNull(stateMachine);
    return stateMachine;
  }

  private synchronized TCGroupHandshakeStateMachine getHandshakeStateMachine(MessageChannel channel) {
    TCGroupHandshakeStateMachine stateMachine = (TCGroupHandshakeStateMachine) channel
        .getAttachment(HANDSHAKE_STATE_MACHINE_TAG);
    return stateMachine;
  }

  /*
   * monitor channel events while doing group member handshaking
   */
  private static class HandshakeChannelEventListener implements ChannelEventListener {
    final private TCGroupHandshakeStateMachine stateMachine;

    HandshakeChannelEventListener(TCGroupHandshakeStateMachine stateMachine) {
      this.stateMachine = stateMachine;
    }

    public void notifyChannelEvent(ChannelEvent event) {
      if (event.getChannel() == stateMachine.getChannel()) {
        if ((event.getType() == ChannelEventType.TRANSPORT_DISCONNECTED_EVENT)
            || (event.getType() == ChannelEventType.CHANNEL_CLOSED_EVENT)) {
          stateMachine.disconnected();
        }
      }
    }
  }

  /*
   * TCGroupHandshakeStateMachine -- State machine for group handshaking
   */
  private static class TCGroupHandshakeStateMachine {
    private final HandshakeState     STATE_NODEID         = new NodeIDState();
    private final HandshakeState     STATE_TRY_ADD_MEMBER = new TryAddMemberState();
    private final HandshakeState     STATE_SUCCESS        = new SuccessState();
    private final HandshakeState     STATE_FAILURE        = new FailureState();

    private final static long        HANDSHAKE_TIMEOUT;
    static {
      HANDSHAKE_TIMEOUT = TCPropertiesImpl.getProperties()
          .getLong(TCPropertiesConsts.L2_NHA_TCGROUPCOMM_HANDSHAKE_TIMEOUT);
    }

    private final TCGroupManagerImpl manager;
    private final MessageChannel     channel;
    private final ServerID           localNodeID;

    private HandshakeState           current;
    private ServerID                 peerNodeID;
    private TimerTask                timerTask;
    private TCGroupMember            member;
    private boolean                  disconnectEventNotified;
    private boolean                  stateTransitionInProgress;

    public TCGroupHandshakeStateMachine(TCGroupManagerImpl manager, MessageChannel channel, ServerID localNodeID) {
      this.manager = manager;
      this.channel = channel;
      this.localNodeID = localNodeID;
      this.disconnectEventNotified = false;
      this.stateTransitionInProgress = false;
    }

    public final void start() {
      switchToState(initialState());
    }

    public synchronized boolean isFailureState() {
      return (current == STATE_FAILURE);
    }

    public void execute(TCGroupHandshakeMessage msg) {
      current.execute(msg);
    }

    protected HandshakeState initialState() {
      return (STATE_NODEID);
    }

    private String stateInfo(HandshakeState state) {
      String info = " at state: " + state + " channel: " + channel;
      if (member != null) return (member.toString() + info);
      if (peerNodeID == null) return (localNodeID.toString() + info);
      else return (peerNodeID.toString() + " -> " + localNodeID.toString() + info);
    }

    protected void switchToState(HandshakeState state) {
      Assert.assertNotNull(state);
      synchronized (this) {
        if (current == STATE_FAILURE) {
          if (logger.isDebugEnabled()) logger.warn("Ignore switching to " + state + ", " + stateInfo(state));
          return;
        }
        this.current = state;
        waitForStateTransitionToComplete();
        stateTransitionInProgress = true;
      }
      state.enter();
      notifyStateTransitionComplete();
    }

    private synchronized void notifyStateTransitionComplete() {
      stateTransitionInProgress = false;
      notifyAll();
    }

    private void waitForStateTransitionToComplete() {
      while (stateTransitionInProgress) {
        try {
          wait();
        } catch (InterruptedException e) {
          throw new AssertionError(e);
        }
      }
    }

    MessageChannel getChannel() {
      return channel;
    }

    private synchronized void setTimerTask(long timeout) {
      TimerTask task = new TimerTask() {
        public void run() {
          handshakeTimeout();
        }
      };
      timerTask = task;
      Timer timer = manager.getHandshakeTimer();
      timer.purge();
      timer.schedule(task, timeout);
    }

    private synchronized void cancelTimerTask() {
      if (timerTask != null) {
        this.timerTask.cancel();
        timerTask = null;
      }
    }

    synchronized void handshakeTimeout() {
      cancelTimerTask();
      if (current == STATE_SUCCESS) {
        if (logger.isDebugEnabled()) logger.debug("Handshake successed. Ignore timeout " + stateInfo(current));
        return;
      }
      logger.warn("Group member handshake timeouted. " + stateInfo(current));
      switchToState(STATE_FAILURE);
    }

    synchronized void disconnected() {
      if (logger.isDebugEnabled()) logger.warn("Group member handshake disconnected. " + stateInfo(current));
      disconnectEventNotified = true;
      switchToState(STATE_FAILURE);
    }

    /*
     * HandshakeState -- base class for handshaking states
     */
    private abstract class HandshakeState {
      private final String name;

      public HandshakeState(String name) {
        this.name = name;
      }

      public void enter() {
        // override me if you want
      }

      public void execute(TCGroupHandshakeMessage handshakeMessage) {
        // override me if you want
      }

      public String toString() {
        return name;
      }
    }

    /*
     * NodeIDState -- Send NodeID to peer and expecting NodeID from peer.
     */
    private class NodeIDState extends HandshakeState {
      public NodeIDState() {
        super("Read-Peer-NodeID");
      }

      public void enter() {
        setTimerTask(HANDSHAKE_TIMEOUT);
        writeNodeIDMessage();
      }

      public void execute(TCGroupHandshakeMessage msg) {
        setPeerNodeID(msg);
        if (!manager.getDiscover().isValidClusterNode(peerNodeID)) {
          logger.warn("Drop connection from non-member node " + peerNodeID);
          switchToState(STATE_FAILURE);
        }
        if (!manager.isZappedNode(peerNodeID)) {
          // remove the old member which is doing reconnecting from same node.
          manager.removeIfMemberReconnecting(peerNodeID);
          switchToState(STATE_TRY_ADD_MEMBER);
        } else {
          logger.warn("Abort connecting to zapped node. " + stateInfo(current));
          switchToState(STATE_FAILURE);
        }
      }

      void setPeerNodeID(TCGroupHandshakeMessage msg) {
        peerNodeID = msg.getNodeID();
        manager.receivedNodeID(channel, peerNodeID);
      }

      void writeNodeIDMessage() {
        TCGroupHandshakeMessage msg = (TCGroupHandshakeMessage) channel
            .createMessage(TCMessageType.GROUP_HANDSHAKE_MESSAGE);
        msg.initializeNodeID(localNodeID);
        msg.send();
        if (logger.isDebugEnabled()) logger.debug("Send group nodeID message to " + channel);
      }
    }

    /*
     * TryAddMemberState -- Try to add member to group. Trying by high-priority-node first, low-priority-node adds to
     * group only after high-priority-node added. The priority is valued by NodeID's uuid.
     */
    private class TryAddMemberState extends HandshakeState {
      public TryAddMemberState() {
        super("Try-Add-Member");
      }

      public void enter() {
        createMember();
        if (member.isHighPriorityNode()) {
          boolean isAdded = manager.tryAddMember(member);
          if (isAdded) member.eventFiringInProcess();
          signalToJoin(isAdded);
        }
      }

      public void execute(TCGroupHandshakeMessage msg) {
        boolean isOkToJoin = msg.isOkMessage();
        if (!member.isHighPriorityNode()) {
          if (isOkToJoin) {
            isOkToJoin = manager.tryAddMember(member);
            if (isOkToJoin) {
              member.eventFiringInProcess();
            } else {
              logger.warn("Unexpected bad handshake, abort connection.");
            }
          }
          signalToJoin(isOkToJoin);
        }
        if (isOkToJoin) switchToState(STATE_SUCCESS);
        else switchToState(STATE_FAILURE);
      }

      private void createMember() {
        Assert.assertNotNull(localNodeID);
        Assert.assertNotNull(peerNodeID);
        member = new TCGroupMemberImpl(localNodeID, peerNodeID, channel);
      }

      private void signalToJoin(boolean ok) {
        Assert.assertNotNull(member);
        TCGroupHandshakeMessage msg = (TCGroupHandshakeMessage) channel
            .createMessage(TCMessageType.GROUP_HANDSHAKE_MESSAGE);
        if (ok) {
          if (logger.isDebugEnabled()) logger.debug("Send ok message to " + member);
          msg.initializeOk();
        } else {
          if (logger.isDebugEnabled()) logger.debug("Send deny message to " + member);
          msg.initializeDeny();
        }
        msg.send();
      }

    }

    /*
     * SucessState -- Both added to group. Fire nodeJoined event.
     */
    private class SuccessState extends HandshakeState {
      public SuccessState() {
        super("Success");
      }

      public void enter() {
        cancelTimerTask();
        manager.fireNodeEvent(member, true);
        member.setJoinedEventFired(true);
        member.notifyEventFired();
      }
    }

    /*
     * FailureState -- Unsuccessful handshaking or member disappeared. Fire nodeLeft event if member is in group.
     */
    private class FailureState extends HandshakeState {
      public FailureState() {
        super("Failure");
      }

      public void enter() {
        cancelTimerTask();
        if (member != null) {
          member.abortEventFiring();
          manager.memberDisappeared(member, disconnectEventNotified);
        } else {
          channel.close();
        }
      }
    }

  }

  /*
   * for testing purpose only
   */
  void addZappedNode(NodeID nodeID) {
    zappedSet.add(nodeID);
  }

}
