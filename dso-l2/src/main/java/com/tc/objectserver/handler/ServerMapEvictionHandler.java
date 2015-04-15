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
package com.tc.objectserver.handler;

import com.tc.async.api.AbstractEventHandler;
import com.tc.async.api.EventContext;
import com.tc.objectserver.api.ServerMapEvictionManager;
import com.tc.objectserver.context.ServerMapEvictionContext;

public class ServerMapEvictionHandler extends AbstractEventHandler {

  private final ServerMapEvictionManager serverMapEvictor;

  public ServerMapEvictionHandler(final ServerMapEvictionManager serverMapEvictor) {
    this.serverMapEvictor = serverMapEvictor;
  }

  @Override
  public void handleEvent(final EventContext context) {
    final ServerMapEvictionContext smec = (ServerMapEvictionContext) context;
    this.serverMapEvictor.evict(smec.getOid(), smec.getRandomSamples(), smec.getClassName(), smec.getCacheName());
  }

}
