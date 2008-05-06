/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.spring.integrationtests.tests;

import com.tc.test.server.appserver.deployment.AbstractTwoServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tctest.spring.bean.ISingleton;
import com.tctest.spring.integrationtests.SpringTwoServerTestSetup;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import junit.framework.Test;

/**
 * Test TC-Spring working with Spring JMX support.
 */
public class JMXSupportTest extends AbstractTwoServerDeploymentTest {

  public JMXSupportTest() {
    this.disableForJavaVersion("1.4.2_(\\d)+");
  }

  private static final String REMOTE_SERVICE_NAME           = "Singleton";
  private static final String BEAN_DEFINITION_FILE_FOR_TEST = "classpath:/com/tctest/spring/beanfactory-jmx.xml";
  private static final String CONFIG_FILE_FOR_TEST          = "/tc-config-files/singleton-tc-config.xml";

  private static ISingleton   singleton1;
  private static ISingleton   singleton2;
  private static MBeanServerConnection mbeanServerConn1;
  private static MBeanServerConnection mbeanServerConn2;

  public void testJMXSupport() throws Exception {
    logger.debug("testing JMX Support");
    singleton1.incrementCounter();
    singleton2.incrementCounter();
    
    Integer counter1 = (Integer)mbeanServerConn1.getAttribute(
        new ObjectName("bean:name=singleton"), "Counter");
    Integer counter2 = (Integer)mbeanServerConn2.getAttribute(
        new ObjectName("bean:name=singleton"), "Counter");
    
    assertEquals("Expecting multiple increments in singleton", 2, singleton1.getCounter());
    assertEquals("Expecting multiple increments in singleton", 2, singleton2.getCounter());
    assertEquals("Expecting multiple increments in mbean", 2, counter1.intValue());
    assertEquals("Expecting multiple increments in mbean", 2, counter2.intValue());

    logger.debug("!!!! Asserts passed !!!");
  }

  private static class JMXSupportTestSetup extends SpringTwoServerTestSetup {
    private JMXSupportTestSetup() {
      super(JMXSupportTest.class, CONFIG_FILE_FOR_TEST, "test-singleton");
    }

    protected void setUp() throws Exception {
      super.setUp();
      
      if(shouldDisable()) return;
      
      try {
        singleton1 = (ISingleton) server0.getProxy(ISingleton.class, REMOTE_SERVICE_NAME);
        singleton2 = (ISingleton) server1.getProxy(ISingleton.class, REMOTE_SERVICE_NAME);
        mbeanServerConn1 = server0.getMBeanServerConnection();
        mbeanServerConn2 = server1.getMBeanServerConnection();
      } catch (Exception e) {
        e.printStackTrace(); 
        throw e;
      }      
    }
    
    protected void configureWar(DeploymentBuilder builder) {
      builder.addBeanDefinitionFile(BEAN_DEFINITION_FILE_FOR_TEST);
      builder.addRemoteService(REMOTE_SERVICE_NAME, "singleton", ISingleton.class);
    }
  }

  public static Test suite() {
    return new JMXSupportTestSetup();
  }

}
