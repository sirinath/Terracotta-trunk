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
package com.tctest.jdk15;

import org.terracotta.test.util.WaitUtil;

import com.tc.config.schema.setup.ConfigurationSetupException;
import com.tc.config.schema.setup.L2ConfigurationSetupManager;
import com.tc.config.schema.setup.TestConfigurationSetupManagerFactory;
import com.tc.exception.TCRuntimeException;
import com.tc.lang.StartupHelper;
import com.tc.lang.StartupHelper.StartupAction;
import com.tc.lang.TCThreadGroup;
import com.tc.lang.ThrowableHandlerImpl;
import com.tc.logging.TCLogging;
import com.tc.object.BaseDSOTestCase;
import com.tc.objectserver.impl.DistributedObjectServer;
import com.tc.objectserver.managedobject.ManagedObjectStateFactory;
import com.tc.properties.TCPropertiesConsts;
import com.tc.properties.TCPropertiesImpl;
import com.tc.server.TCServerImpl;
import com.tc.test.config.builder.ClusterManager;
import com.tc.util.Assert;
import com.tc.util.PortChooser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;

/**
 * Test for DEV-1060
 *
 * @author Manoj
 */
public class DSOServerBindAddressTest extends BaseDSOTestCase {
  private final TCThreadGroup   group     = new TCThreadGroup(
                                                              new ThrowableHandlerImpl(TCLogging
                                                                  .getLogger(DistributedObjectServer.class)));
  private static final String[] bindAddrs = { "0.0.0.0", "127.0.0.1", localAddr() };
  private TCServerImpl          server;

  static String localAddr() {
    try {
      String rv = InetAddress.getLocalHost().getHostAddress();
      if (rv.startsWith("127.")) { throw new RuntimeException("Wrong local address " + rv); }
      return rv;
    } catch (UnknownHostException uhe) {
      throw new TCRuntimeException("Host resolve error:" + uhe);
    }
  }

  private class StartAction implements StartupAction {
    private final int    tsaPort;
    private final int    jmxPort;
    private final String bindAddr;
    private final int    tsaGroupPort;
    private final int    managementPort;

    public StartAction(String bindAddr, int tsaPort, int jmxPort, int tsaGroupPort, int mangementPort) {
      this.bindAddr = bindAddr;
      this.tsaPort = tsaPort;
      this.jmxPort = jmxPort;
      this.tsaGroupPort = tsaGroupPort;
      this.managementPort = mangementPort;
    }

    @Override
    public void execute() throws Throwable {
      TCPropertiesImpl.getProperties().setProperty(TCPropertiesConsts.L2_OBJECTMANAGER_DGC_INLINE_ENABLED, "false");
      TCPropertiesImpl.getProperties().setProperty(TCPropertiesConsts.L2_OFFHEAP_SKIP_JVMARG_CHECK, "true");
      server = new TCServerImpl(createL2Manager(bindAddr, tsaPort, jmxPort, tsaGroupPort, managementPort));
      server.start();
    }

  }

  public void testDSOServerAndJMXBindAddress() throws Exception {
    System.setProperty("com.tc.management.war", ClusterManager.findWarLocation("org.terracotta", "management-tsa-war",
        ClusterManager.guessMavenArtifactVersion()));
    PortChooser pc = new PortChooser();

    ManagedObjectStateFactory.disableSingleton(true);

    for (int i = 0; i < bindAddrs.length; i++) {
      String bind = bindAddrs[i];
      int tsaPort = pc.chooseRandomPort();
      int jmxPort = pc.chooseRandomPort();
      int tsaGroupPort = pc.chooseRandomPort();
      int managementPort = pc.chooseRandomPort();

      new StartupHelper(group, new StartAction(bind, tsaPort, jmxPort, tsaGroupPort, managementPort)).startUp();

      final DistributedObjectServer dsoServer = server.getDSOServer();
      WaitUtil.waitUntilCallableReturnsTrue(new Callable<Boolean>() {
        @Override
        public Boolean call() throws Exception {
          try {
            dsoServer.getListenAddr();
            return true;
          } catch (IllegalStateException ise) {
            //
          }
          return false;
        }
      });

      if (i == 0) {
        Assert.eval(dsoServer.getListenAddr().isAnyLocalAddress());
      } else {
        assertEquals(dsoServer.getListenAddr().getHostAddress(), bind);
      }
      Assert.assertNotNull(dsoServer.getJMXConnServer());
      assertEquals(dsoServer.getJMXConnServer().getAddress().getHost(), bind);

      testSocketConnect(bind, new int[] { tsaPort, jmxPort, tsaGroupPort, managementPort }, true);

      server.stop();
      Thread.sleep(3000);
    }
  }

  private void testSocketConnect(final String host, int[] ports, boolean testNegative) throws Exception {
    InetAddress addr = InetAddress.getByName(host);
    if (addr.isAnyLocalAddress()) {
      // should be able to connect on both localhost and local IP
      testSocketConnect("127.0.0.1", ports, false);
      testSocketConnect(localAddr(), ports, false);
    } else {
      // positive case
      for (final int port : ports) {
        WaitUtil.waitUntilCallableReturnsTrue(new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            try {
              testSocket(host, port, false);
              return true;
            } catch (IOException e) {
              return false;
            }
          }
        });
      }

      if (testNegative) {
        // negative case
        for (int port : ports) {
          if (addr.isLoopbackAddress()) {
            testSocket(localAddr(), port, true);
          } else if (InetAddress.getByName(localAddr()).equals(addr)) {
            testSocket("127.0.0.1", port, true);
          } else {
            throw new AssertionError(addr);
          }
        }
      }
    }
  }

  private static void testSocket(String host, int port, boolean expectFailure) throws IOException {
    System.err.print("testing connect on " + host + ":" + port + " ");
    Socket s = null;
    try {
      s = new Socket(host, port);
      if (expectFailure) {
        System.err.println("[FAIL]");
        throw new AssertionError("should not connect");
      }
    } catch (IOException ioe) {
      if (!expectFailure) {
        System.err.println("[FAIL]");
        throw ioe;
      }
    } finally {
      closeQuietly(s);
    }

    System.err.println("[OK]");
  }

  private static void closeQuietly(Socket s) {
    if (s == null) return;
    try {
      s.close();
    } catch (IOException ioe) {
      // ignore
    }
  }

  public L2ConfigurationSetupManager createL2Manager(String bindAddress, int tsaPort, int jmxPort, int tsaGroupPort,
                                                     int managementPort)
      throws ConfigurationSetupException {
    TestConfigurationSetupManagerFactory factory = super.configFactory();
    L2ConfigurationSetupManager manager = factory.createL2TVSConfigurationSetupManager(null, true);
    
    manager.dsoL2Config().getDataStorage().setSize("64m");
    manager.dsoL2Config().getOffheap().setSize("64m");

    manager.dsoL2Config().tsaPort().setIntValue(tsaPort);
    manager.dsoL2Config().tsaPort().setBind(bindAddress);

    manager.commonl2Config().jmxPort().setIntValue(jmxPort);
    manager.commonl2Config().jmxPort().setBind(bindAddress);

    manager.dsoL2Config().tsaGroupPort().setIntValue(tsaGroupPort);
    manager.dsoL2Config().tsaGroupPort().setBind(bindAddress);

    manager.commonl2Config().managementPort().setIntValue(managementPort);
    manager.commonl2Config().managementPort().setBind(bindAddress);

    manager.dsoL2Config().setJmxEnabled(true);

    return manager;
  }
}
