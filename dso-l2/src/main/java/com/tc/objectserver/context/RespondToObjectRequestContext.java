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
package com.tc.objectserver.context;

import com.tc.async.api.EventContext;
import com.tc.net.ClientID;
import com.tc.object.ObjectRequestServerContext.LOOKUP_STATE;
import com.tc.util.ObjectIDSet;

import java.util.Collection;

public interface RespondToObjectRequestContext extends EventContext {

  public ClientID getRequestedNodeID();

  public Collection getObjs();

  public ObjectIDSet getRequestedObjectIDs();

  public ObjectIDSet getMissingObjectIDs();

  public LOOKUP_STATE getLookupState();

  public int getRequestDepth();
}
