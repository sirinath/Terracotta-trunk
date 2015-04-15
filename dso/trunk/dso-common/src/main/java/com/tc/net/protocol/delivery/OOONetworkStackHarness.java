/* 
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at 
 *
 *      http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Platform.
 *
 * The Initial Developer of the Covered Software is 
 *      Terracotta, Inc., a Software AG company
 */
package com.tc.net.protocol.delivery;

import com.tc.net.protocol.AbstractNetworkStackHarness;
import com.tc.net.protocol.tcm.MessageChannelInternal;
import com.tc.net.protocol.tcm.ServerMessageChannelFactory;
import com.tc.net.protocol.transport.ClientConnectionEstablisher;
import com.tc.net.protocol.transport.ClientMessageTransport;
import com.tc.net.protocol.transport.MessageTransport;
import com.tc.net.protocol.transport.MessageTransportFactory;
import com.tc.properties.ReconnectConfig;

public class OOONetworkStackHarness extends AbstractNetworkStackHarness {

  private final OnceAndOnlyOnceProtocolNetworkLayerFactory factory;
  private OnceAndOnlyOnceProtocolNetworkLayer              oooLayer;
  private final boolean                                    isClient;
  private final ReconnectConfig                            reconnectConfig;

  OOONetworkStackHarness(ServerMessageChannelFactory channelFactory, MessageTransport transport,
                         OnceAndOnlyOnceProtocolNetworkLayerFactory factory, ReconnectConfig reconnectConfig) {
    super(channelFactory, transport);
    this.isClient = false;
    this.factory = factory;
    this.reconnectConfig = reconnectConfig;
  }

  OOONetworkStackHarness(MessageTransportFactory transportFactory, MessageChannelInternal channel,
                         OnceAndOnlyOnceProtocolNetworkLayerFactory factory, ReconnectConfig reconnectConfig) {
    super(transportFactory, channel);
    this.isClient = true;
    this.factory = factory;
    this.reconnectConfig = reconnectConfig;
  }

  @Override
  protected void connectStack() {
    channel.setSendLayer(oooLayer);
    oooLayer.setReceiveLayer(channel);
    oooLayer.addTransportListener(channel);

    transport.setAllowConnectionReplace(true);

    oooLayer.setSendLayer(transport);
    transport.setReceiveLayer(oooLayer);

    long timeout = 0;
    if (reconnectConfig.getReconnectEnabled()) timeout = reconnectConfig.getReconnectTimeout();
    // XXX: this is super ugly, but...
    if (transport instanceof ClientMessageTransport) {
      ClientMessageTransport cmt = (ClientMessageTransport) transport;
      ClientConnectionEstablisher cce = cmt.getConnectionEstablisher();
      OOOConnectionWatcher cw = new OOOConnectionWatcher(cmt, cce, oooLayer, timeout);
      cmt.addTransportListener(cw);
    } else {
      OOOReconnectionTimeout ort = new OOOReconnectionTimeout(oooLayer, timeout);
      transport.addTransportListener(ort);
    }
  }

  @Override
  protected void createIntermediateLayers() {
    oooLayer = (isClient) ? factory.createNewClientInstance(reconnectConfig) : factory
        .createNewServerInstance(reconnectConfig);
  }
}
