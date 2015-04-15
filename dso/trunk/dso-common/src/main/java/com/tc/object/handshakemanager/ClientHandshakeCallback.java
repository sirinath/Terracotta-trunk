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
package com.tc.object.handshakemanager;

import com.tc.net.NodeID;
import com.tc.object.ClearableCallback;
import com.tc.object.msg.ClientHandshakeMessage;

public interface ClientHandshakeCallback extends ClearableCallback {

  /**
   * Pauses this callback, should be UnInterruptable.
   */
  public void pause(NodeID remoteNode, int disconnected);

  public void unpause(NodeID remoteNode, int disconnected);

  public void initializeHandshake(NodeID thisNode, NodeID remoteNode, ClientHandshakeMessage handshakeMessage);

  public void shutdown(boolean fromShutdownHook);

}
