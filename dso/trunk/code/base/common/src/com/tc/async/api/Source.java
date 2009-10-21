/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.async.api;

import com.tc.stats.Monitorable;

/**
 * This is used buy the internals to manage the process of processing EventContexts in the maner that makes sense for
 * each one. Individual Stages SHOULD NOT HAVE TO EITHER USE OR IMPLEMENT THIS INTERFACE
 * 
 * @author steve
 */
public interface Source extends Monitorable {

  public EventContext poll(long period) throws InterruptedException;

  public String getSourceName();

}