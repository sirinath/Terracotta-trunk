/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest.spring.integrationtests.load;

import com.tc.test.server.appserver.deployment.Deployment;
import com.tc.test.server.appserver.deployment.ServerTestSetup;
import com.tc.test.server.appserver.deployment.TestCallback;
import com.tc.test.server.appserver.deployment.WebApplicationServer;
import com.tctest.spring.bean.EventManager;
import com.tctest.spring.integrationtests.SpringDeploymentTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import junit.framework.Test;

public class DistributedEventsLoadTest extends SpringDeploymentTest {
  private static final boolean DEBUG                         = true;
  private static final int     NUM_ITERATION                 = 500;

  private static final String  REMOTE_SERVICE_NAME           = "EventManager";
  private static final String  BEAN_DEFINITION_FILE_FOR_TEST = "classpath:/com/tctest/spring/distributedevents.xml";
  private static final String  CONFIG_FILE_FOR_TEST          = "/tc-config-files/event-tc-config.xml";
  private static final String  CONTEXT                       = "distributed-events";
  private static final String  BEAN_NAME                     = "eventManager";

  private Deployment           deployment;

  public DistributedEventsLoadTest() {
    //
  }

  public static Test suite() {
    return new ServerTestSetup(DistributedEventsLoadTest.class);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    if (deployment == null) {
      deployment = makeWAR();
    }
  }

  public void testLoad() throws Throwable {
    publishDistributedEvents(2);
  }

  private void publishDistributedEvents(final int nodeCount) throws Throwable {
    List servers = new ArrayList();
    final List eventManagers = new ArrayList();

    try {
      for (int i = 0; i < nodeCount; i++) {
        WebApplicationServer server = makeWebApplicationServer(CONFIG_FILE_FOR_TEST);
        server.addWarDeployment(deployment, CONTEXT);
        server.start();
        servers.add(server);
        EventManager em = (EventManager) server.getProxy(EventManager.class, REMOTE_SERVICE_NAME);
        em.size();
        eventManagers.add(em);
      }

      debugPrintln("publishDistributedEvents():  num_iteration per node = " + NUM_ITERATION);

      long startTime = System.currentTimeMillis();

      debugPrintln("publishDistributedEvents():  startTime = " + startTime);

      for (int i = 0; i < NUM_ITERATION; i++) {
        for (int node = 0; node < nodeCount; node++) {
          debugPrintln("publishDistributedEvents():  node:" + node + " iteration:" + i);
          ((EventManager) eventManagers.get(node)).publishEvents("foo" + node, "bar" + i, 2);
        }
      }

      waitForSuccess(8 * 60, new TestCallback() {
        public void check() {
          for (Iterator iter = eventManagers.iterator(); iter.hasNext();) {
            EventManager em = (EventManager) iter.next();
            assertEquals(NUM_ITERATION * nodeCount * 2, em.size());
          }
        }
      });

      long endTime = 0;
      for (Iterator iter = eventManagers.iterator(); iter.hasNext();) {
        EventManager em = (EventManager) iter.next();
        Date date = em.getLastEventTime();

        debugPrintln("eventManager = " + em.toString() + " lastEventTime = " + date.getTime());

        if (date.getTime() > endTime) {
          endTime = date.getTime();
        }
      }

      long totalTime = endTime - startTime;

      printData(nodeCount, totalTime);

    } finally {
      for (Iterator it = servers.iterator(); it.hasNext();) {
        ((WebApplicationServer) it.next()).stopIgnoringExceptions();
      }
    }
  }

  private Deployment makeWAR() throws Exception {
    return makeDeploymentBuilder(CONTEXT + ".war").addBeanDefinitionFile(BEAN_DEFINITION_FILE_FOR_TEST)
        .addRemoteService(REMOTE_SERVICE_NAME, BEAN_NAME, EventManager.class)
        .addDirectoryOrJARContainingClass(EventManager.class).addDirectoryContainingResource(CONFIG_FILE_FOR_TEST)
        .makeDeployment();
  }

  private void printData(int nodeCount, long totalTime) {
    System.out.println("**%% TERRACOTTA TEST STATISTICS %%**: nodes=" + nodeCount + " iteration="
                       + (NUM_ITERATION * nodeCount) + " time=" + totalTime + " nanoseconds  avg="
                       + (totalTime / (NUM_ITERATION * nodeCount)) + " nanoseconds/iteration");
  }

  private void debugPrintln(String s) {
    if (DEBUG) {
      System.out.println("XXXXX " + s);
    }
  }

}
