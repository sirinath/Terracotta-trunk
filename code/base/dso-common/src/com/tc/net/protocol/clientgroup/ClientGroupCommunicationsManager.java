/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.protocol.clientgroup;

import com.tc.net.core.ConnectionAddressProvider;
import com.tc.net.protocol.tcm.CommunicationsManager;
import com.tc.object.session.SessionProvider;

public interface ClientGroupCommunicationsManager extends CommunicationsManager {

  public ClientGroupMessageChannel createClientGroupChannel(final SessionProvider sessionProvider,
                                                            final int maxReconnectTries,
                                                            final int socketConnectTimeout,
                                                            ConnectionAddressProvider[] addressProviders);

}
