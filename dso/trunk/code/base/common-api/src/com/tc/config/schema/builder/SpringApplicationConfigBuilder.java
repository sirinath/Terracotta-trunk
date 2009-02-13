/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.config.schema.builder;


public interface SpringApplicationConfigBuilder {

  public String toString();

  public SpringApplicationContextConfigBuilder[] getApplicationContexts();

  public void setName(String name);

  public void setApplicationContexts(SpringApplicationContextConfigBuilder[] builders);

}