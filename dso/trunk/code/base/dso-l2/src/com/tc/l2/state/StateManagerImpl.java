/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.l2.state;

import EDU.oswego.cs.dl.util.concurrent.CopyOnWriteArrayList;

import com.tc.async.api.Sink;
import com.tc.l2.context.StateChangedEvent;
import com.tc.l2.ha.L2HAZapNodeRequestProcessor;
import com.tc.l2.ha.WeightGeneratorFactory;
import com.tc.l2.msg.L2StateMessage;
import com.tc.l2.msg.L2StateMessageFactory;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.net.groups.GroupException;
import com.tc.net.groups.GroupManager;
import com.tc.net.groups.GroupMessage;
import com.tc.net.groups.NodeID;
import com.tc.net.groups.NodeIDImpl;
import com.tc.util.Assert;
import com.tc.util.State;

import java.util.Iterator;

public class StateManagerImpl implements StateManager {

  private static final TCLogger        logger             = TCLogging.getLogger(StateManagerImpl.class);

  private final TCLogger               consoleLogger;
  private final GroupManager           groupManager;
  private final ElectionManager        electionMgr;
  private final Sink                   stateChangeSink;
  private final WeightGeneratorFactory weightsFactory;

  private final CopyOnWriteArrayList   listeners          = new CopyOnWriteArrayList();
  private final Object                 electionLock       = new Object();

  private NodeID                       activeNode         = NodeIDImpl.NULL_ID;
  private volatile State               state              = START_STATE;
  private boolean                      electionInProgress = false;

  public StateManagerImpl(TCLogger consoleLogger, GroupManager groupManager, Sink stateChangeSink,
                          StateManagerConfig stateManagerConfig, WeightGeneratorFactory weightFactory) {
    this.consoleLogger = consoleLogger;
    this.groupManager = groupManager;
    this.stateChangeSink = stateChangeSink;
    this.weightsFactory = weightFactory;
    this.electionMgr = new ElectionManagerImpl(groupManager, stateManagerConfig);
  }

  /*
   * XXX:: If ACTIVE went dead before any passive moved to STANDBY state, then the cluster is hung and there is no going
   * around it. If ACTIVE in persistent mode, it can come back and recover the cluster
   */
  public void startElection() {
    synchronized (electionLock) {
      if (electionInProgress) return;
      electionInProgress = true;
    }
    try {
      if (state == START_STATE) {
        runElection(true);
      } else if (state == PASSIVE_STANDBY) {
        runElection(false);
      } else {
        info("Ignoring Election request since not in right state");
      }
    } finally {
      synchronized (electionLock) {
        electionInProgress = false;
      }
    }
  }

  private void runElection(boolean isNew) {
    NodeID myNodeID = getLocalNodeID();
    NodeID winner = electionMgr.runElection(myNodeID, isNew, weightsFactory);
    if (winner == myNodeID) {
      moveToActiveState();
    } else {
      electionMgr.reset(null);
    }
  }

  private NodeID getLocalNodeID() {
    try {
      return groupManager.getLocalNodeID();
    } catch (GroupException e) {
      throw new AssertionError(e);
    }
  }

  public void registerForStateChangeEvents(StateChangeListener listener) {
    listeners.add(listener);
  }

  public void fireStateChangedEvent(StateChangedEvent sce) {
    for (Iterator i = listeners.iterator(); i.hasNext();) {
      StateChangeListener listener = (StateChangeListener) i.next();
      listener.l2StateChanged(sce);
    }
  }

  private synchronized void moveToPassiveState(Enrollment winningEnrollment) {
    electionMgr.reset(winningEnrollment);
    if (state == START_STATE) {
      state = winningEnrollment.isANewCandidate() ? PASSIVE_STANDBY : PASSIVE_UNINTIALIZED;
      info("Moved to " + state, true);
      stateChangeSink.add(new StateChangedEvent(START_STATE, state));
    } else if (state == ACTIVE_COORDINATOR) {
      // TODO:: Support this later
      throw new AssertionError("Cant move to " + PASSIVE_UNINTIALIZED + " from " + ACTIVE_COORDINATOR
                               + " at least for now");
    }
  }

  public synchronized void moveToPassiveStandbyState() {
    if (state == ACTIVE_COORDINATOR) {
      // TODO:: Support this later
      throw new AssertionError("Cant move to " + PASSIVE_STANDBY + " from " + ACTIVE_COORDINATOR + " at least for now");
    } else if (state != PASSIVE_STANDBY) {
      stateChangeSink.add(new StateChangedEvent(state, PASSIVE_STANDBY));
      state = PASSIVE_STANDBY;
      info("Moved to " + state, true);
    } else {
      info("Already in " + state);
    }
  }

  private synchronized void moveToActiveState() {
    if (state == START_STATE || state == PASSIVE_STANDBY) {
      // TODO :: If state == START_STATE publish cluster ID
      StateChangedEvent event = new StateChangedEvent(state, ACTIVE_COORDINATOR);
      state = ACTIVE_COORDINATOR;
      this.activeNode = getLocalNodeID();
      info("Becoming " + state, true);
      electionMgr.declareWinner(this.activeNode);
      stateChangeSink.add(event);
    } else {
      throw new AssertionError("Cant move to " + ACTIVE_COORDINATOR + " from " + state);
    }
  }

  public synchronized NodeID getActiveNodeID() {
    return activeNode;
  }

  public boolean isActiveCoordinator() {
    return (state == ACTIVE_COORDINATOR);
  }

  public void moveNodeToPassiveStandby(NodeID nodeID) {
    Assert.assertTrue(isActiveCoordinator());
    logger.info("Requesting node " + nodeID + " to move to " + PASSIVE_STANDBY);
    GroupMessage msg = L2StateMessageFactory.createMoveToPassiveStandbyMessage(EnrollmentFactory
        .createTrumpEnrollment(getLocalNodeID(), weightsFactory));
    try {
      this.groupManager.sendTo(nodeID, msg);
    } catch (GroupException e) {
      logger.error(e);
    }
  }

  public void handleClusterStateMessage(L2StateMessage clusterMsg) {
    try {
      switch (clusterMsg.getType()) {
        case L2StateMessage.START_ELECTION:
          handleStartElectionRequest(clusterMsg);
          break;
        case L2StateMessage.ABORT_ELECTION:
          handleElectionAbort(clusterMsg);
          break;
        case L2StateMessage.ELECTION_RESULT:
          handleElectionResultMessage(clusterMsg);
          break;
        case L2StateMessage.ELECTION_WON:
        case L2StateMessage.ELECTION_WON_ALREADY:
          handleElectionWonMessage(clusterMsg);
          break;
        case L2StateMessage.MOVE_TO_PASSIVE_STANDBY:
          handleMoveToPassiveStandbyMessage(clusterMsg);
          break;
        default:
          throw new AssertionError("This message shouldn't have been routed here : " + clusterMsg);
      }
    } catch (GroupException ge) {
      logger.error("Zapping Node : Caught Exception while handling Message : " + clusterMsg, ge);
      groupManager.zapNode(clusterMsg.messageFrom(), L2HAZapNodeRequestProcessor.COMMUNICATION_ERROR,
                           "Error handling Election Message " + L2HAZapNodeRequestProcessor.getErrorString(ge));
    }
  }

  private void handleMoveToPassiveStandbyMessage(L2StateMessage clusterMsg) {
    moveToPassiveStandbyState();
  }

  private synchronized void handleElectionWonMessage(L2StateMessage clusterMsg) {
    Enrollment winningEnrollment = clusterMsg.getEnrollment();
    if (state == ACTIVE_COORDINATOR) {
      // Can't get Election Won from another node : Split brain
      String error = state + " Received Election Won Msg : " + clusterMsg + ". Possible split brain detected";
      logger.error(error);
      if (clusterMsg.getType() == L2StateMessage.ELECTION_WON_ALREADY) {
        sendNGResponse(clusterMsg.messageFrom(), clusterMsg);
      }
      groupManager.zapNode(winningEnrollment.getNodeID(), L2HAZapNodeRequestProcessor.SPLIT_BRAIN, error);
    } else if (activeNode.isNull() || activeNode.equals(winningEnrollment.getNodeID())
               || clusterMsg.getType() == L2StateMessage.ELECTION_WON) {
      // There is no active server for this node or the other node just detected a failure of ACTIVE server and ran an
      // election and is sending the results. This can happen if this node for some reason is not able to detect that
      // the active is down but the other node did. Go with the new active.
      this.activeNode = winningEnrollment.getNodeID();
      moveToPassiveState(winningEnrollment);
      if (clusterMsg.getType() == L2StateMessage.ELECTION_WON_ALREADY) {
        sendOKResponse(clusterMsg.messageFrom(), clusterMsg);
      }
    } else {
      // This is done to solve DEV-1532. Node sent ELECTION_WON_ALREADY message but our ACTIVE is intact.
      logger.warn("Conflicting Election Won  Msg : " + clusterMsg + " since I already have a ACTIVE Node : "
                  + activeNode + ". Sending NG response");
      // The reason we send a response for ELECTION_WON_ALREADY message is that if we don't agree we don't want the
      // other server to send us cluster state messages.
      sendNGResponse(clusterMsg.messageFrom(), clusterMsg);
    }
  }

  private synchronized void handleElectionResultMessage(L2StateMessage msg) throws GroupException {
    if (activeNode.equals(msg.getEnrollment().getNodeID())) {
      Assert.assertFalse(NodeIDImpl.NULL_ID.equals(activeNode));
      // This wouldnt normally happen, but we agree - so ack
      GroupMessage resultAgreed = L2StateMessageFactory.createResultAgreedMessage(msg, msg.getEnrollment());
      logger.info("Agreed with Election Result from " + msg.messageFrom() + " : " + resultAgreed);
      groupManager.sendTo(msg.messageFrom(), resultAgreed);
    } else if (state == ACTIVE_COORDINATOR || !activeNode.isNull()
               || (msg.getEnrollment().isANewCandidate() && state != START_STATE)) {
      // Condition 1 :
      // Obviously an issue.
      // Condition 2 :
      // This shouldn't happen normally, but is possible when there is some weird network error where A sees B,
      // B sees A/C and C sees B and A is active and C is trying to run election
      // Force other node to rerun election so that we can abort
      // Condition 3 :
      // We dont want new L2s to win an election when there are old L2s in PASSIVE states.
      GroupMessage resultConflict = L2StateMessageFactory.createResultConflictMessage(msg, EnrollmentFactory
          .createTrumpEnrollment(getLocalNodeID(), weightsFactory));
      warn("WARNING :: Active Node = " + activeNode + " , " + state
           + " received ELECTION_RESULT message from another node : " + msg + " : Forcing re-election "
           + resultConflict);
      groupManager.sendTo(msg.messageFrom(), resultConflict);
    } else {
      electionMgr.handleElectionResultMessage(msg);
    }
  }

  private void handleElectionAbort(L2StateMessage clusterMsg) {
    if (state == ACTIVE_COORDINATOR) {
      // Cant get Abort back to ACTIVE, if so then there is a split brain
      String error = state + " Received Abort Election  Msg : Possible split brain detected ";
      logger.error(error);
      groupManager.zapNode(clusterMsg.messageFrom(), L2HAZapNodeRequestProcessor.SPLIT_BRAIN, error);
    } else {
      electionMgr.handleElectionAbort(clusterMsg);
    }
  }

  private void handleStartElectionRequest(L2StateMessage msg) throws GroupException {
    if (state == ACTIVE_COORDINATOR) {
      // This is either a new L2 joining a cluster or a renegade L2. Force it to abort
      GroupMessage abortMsg = L2StateMessageFactory.createAbortElectionMessage(msg, EnrollmentFactory
          .createTrumpEnrollment(getLocalNodeID(), weightsFactory));
      info("Forcing Abort Election for " + msg + " with " + abortMsg);
      groupManager.sendTo(msg.messageFrom(), abortMsg);
    } else if (!electionMgr.handleStartElectionRequest(msg)) {
      // TODO::FIXME:: Commenting so that stage thread is not held up doing election.
      // startElectionIfNecessary(NodeID.NULL_ID);
      logger.warn("Not starting election as it was commented out");
    }
  }

  // notify new node
  public void publishActiveState(NodeID nodeID) throws GroupException {
    Assert.assertTrue(isActiveCoordinator());
    GroupMessage msg = L2StateMessageFactory.createElectionWonAlreadyMessage(EnrollmentFactory
        .createTrumpEnrollment(getLocalNodeID(), weightsFactory));
    L2StateMessage response = (L2StateMessage) groupManager.sendToAndWaitForResponse(nodeID, msg);
    validateResponse(nodeID, response);
  }

  private void validateResponse(NodeID nodeID, L2StateMessage response) throws GroupException {
    if (response == null || response.getType() != L2StateMessage.RESULT_AGREED) {
      String error = "Recd wrong response from : " + nodeID + " : msg = " + response + " while publishing Active State";
      logger.error(error);
      // throwing this exception will initiate a zap elsewhere
      throw new GroupException(error);
    }
  }

  public void startElectionIfNecessary(NodeID disconnectedNode) {
    Assert.assertFalse(disconnectedNode.equals(getLocalNodeID()));
    boolean elect = false;
    synchronized (this) {
      if (activeNode.equals(disconnectedNode)) {
        // ACTIVE Node is gone
        activeNode = NodeIDImpl.NULL_ID;
      }
      if (state != PASSIVE_UNINTIALIZED && state != ACTIVE_COORDINATOR && activeNode.isNull()) {
        elect = true;
      }
    }
    if (elect) {
      info("Starting Election to determine cluser wide ACTIVE L2");
      startElection();
    }
  }

  private void sendOKResponse(NodeID fromNode, L2StateMessage msg) {
    try {
      groupManager.sendTo(fromNode, L2StateMessageFactory.createResultAgreedMessage(msg, msg.getEnrollment()));
    } catch (GroupException e) {
      logger.error("Error handling message : " + msg, e);
    }
  }

  private void sendNGResponse(NodeID fromNode, L2StateMessage msg) {
    try {
      groupManager.sendTo(fromNode, L2StateMessageFactory.createResultConflictMessage(msg, msg.getEnrollment()));
    } catch (GroupException e) {
      logger.error("Error handling message : " + msg, e);
    }
  }

  private void info(String message) {
    info(message, false);
  }

  private void info(String message, boolean console) {
    logger.info(message);
    if (console) {
      consoleLogger.info(message);
    }
  }

  private void warn(String message) {
    warn(message, false);
  }

  private void warn(String message, boolean console) {
    logger.warn(message);
    if (console) {
      consoleLogger.warn(message);
    }
  }
}
