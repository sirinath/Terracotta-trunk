/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.net.protocol.transport;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;

/**
 * ECHO HealthChecker Context. On receiving a PING probe, it sends back the PING_REPLY.
 * 
 * @author Manoj
 */
public class ConnectionHealthCheckerContextEchoImpl implements ConnectionHealthCheckerContext {
  private final MessageTransportBase             transport;
  private final HealthCheckerProbeMessageFactory messageFactory;
  private final TCLogger                         logger = TCLogging.getLogger(ConnectionHealthCheckerImpl.class);

  public ConnectionHealthCheckerContextEchoImpl(MessageTransportBase mtb) {
    this.transport = mtb;
    this.messageFactory = new TransportMessageFactoryImpl();
  }

  @Override
  public boolean receiveProbe(HealthCheckerProbeMessage message) {
    if (message.isPing()) {
      HealthCheckerProbeMessage pingReplyMessage = this.messageFactory.createPingReply(transport.getConnectionId(),
                                                                                       transport.getConnection());
      this.transport.send(pingReplyMessage);
      return true;
    }
    logger.info(message.toString());
    throw new AssertionError("Echo HealthChecker");
  }

  @Override
  public boolean probeIfAlive() {
    throw new AssertionError("Echo HealthChecker");
  }

  @Override
  public void refresh() {
    throw new AssertionError("Echo HealthChecker");
  }

}
