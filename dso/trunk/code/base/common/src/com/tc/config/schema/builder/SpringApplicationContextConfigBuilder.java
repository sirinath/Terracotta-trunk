/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.config.schema.builder;


public interface SpringApplicationContextConfigBuilder {

  public void setPaths(String[] paths);

  public SpringBeanConfigBuilder addBean(String beanName);

}