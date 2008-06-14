/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.delivery;

import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;

import com.tc.net.protocol.TCNetworkMessage;
import com.tc.net.protocol.tcm.NullMessageMonitor;
import com.tc.net.protocol.tcm.msgs.PingMessage;
import com.tc.objectserver.api.TestSink;
import com.tc.properties.L1ReconnectConfigImpl;
import com.tc.properties.ReconnectConfig;

import junit.framework.TestCase;

/**
 * 
 */
public class GuaranteedDeliveryProtocolTest extends TestCase {
  public void tests() throws Exception {
    LinkedQueue receiveQueue = new LinkedQueue();
    TestProtocolMessageDelivery delivery = new TestProtocolMessageDelivery(receiveQueue);
    TestSink workSink = new TestSink();
    final short sessionId = 124;

    final ReconnectConfig reconnectConfig = new L1ReconnectConfigImpl();
    GuaranteedDeliveryProtocol gdp = new GuaranteedDeliveryProtocol(delivery, workSink, reconnectConfig, true);
    gdp.start();
    gdp.resume();

    // hand shake state
    // send AckRequest to receiver
    TestProtocolMessage msg = new TestProtocolMessage();
    msg.isHandshake = true;
    msg.setSessionId(sessionId);
    gdp.receive(msg);
    runWorkSink(workSink);
    // reply ack=-1 from receiver
    msg = new TestProtocolMessage(null, 0, -1);
    msg.isAck = true;
    msg.setSessionId(sessionId);
    gdp.receive(msg);
    runWorkSink(workSink);

    TCNetworkMessage tcMessage = new PingMessage(new NullMessageMonitor());
    assertTrue(workSink.size() == 0);
    gdp.send(tcMessage);
    assertTrue(workSink.size() == 1);
    runWorkSink(workSink);
    assertTrue(delivery.created);
    assertTrue(delivery.tcMessage == tcMessage);
    TestProtocolMessage pm = (TestProtocolMessage) delivery.msg;
    pm.setSessionId(sessionId);
    delivery.clear();
    pm.isSend = true;
    gdp.receive(pm);
    assertTrue(workSink.size() == 1);
    runWorkSink(workSink);

    assertTrue(receiveQueue.take() == tcMessage);
    assertTrue(delivery.sentAck);
    assertTrue(delivery.ackCount == 0);

    delivery.clear();
    TestProtocolMessage ackMessage = new TestProtocolMessage();
    ackMessage.setSessionId(sessionId);
    ackMessage.ack = 0;
    ackMessage.isAck = true;
    gdp.receive(ackMessage);
    assertTrue(workSink.size() == 1);
    runWorkSink(workSink);
    delivery.clear();

    gdp.send(tcMessage);
    gdp.send(tcMessage);
    assertTrue(workSink.size() == 1);
    runWorkSink(workSink);
    assertTrue(workSink.size() == 1);
  }

  private void runWorkSink(TestSink sink) {
    StateMachineRunner smr = (StateMachineRunner) sink.getInternalQueue().remove(0);
    smr.run();
  }
}
