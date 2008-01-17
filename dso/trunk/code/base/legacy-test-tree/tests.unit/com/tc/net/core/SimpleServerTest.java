/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.net.core;

import EDU.oswego.cs.dl.util.concurrent.LinkedQueue;
import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;
import EDU.oswego.cs.dl.util.concurrent.SynchronizedRef;

import com.tc.net.TCSocketAddress;
import com.tc.net.protocol.EchoSink;
import com.tc.net.protocol.GenericNetworkHeader;
import com.tc.net.proxy.TCPProxy;
import com.tc.test.TCTestCase;
import com.tc.util.Assert;

import java.io.File;

public class SimpleServerTest extends TCTestCase {
  private final boolean         useProxy  = false;
  private TCPProxy              proxy     = null;
  private int                   proxyPort = -1;
  private TCConnectionManager   connMgr;
  private SimpleServer          server;
  private final SynchronizedRef error     = new SynchronizedRef(null);

  protected void setUp(int serverThreadCount) throws Exception {
    connMgr = new TCConnectionManagerJDK14();
    server = new SimpleServer(new EchoSink(true, new EchoSink.ErrorListener() {
      public void error(Throwable t) {
        setError(t);
      }
    }), 0 /* PORT */, serverThreadCount);
    server.start();

    if (useProxy) {
      int serverPort = server.getServerAddr().getPort();
      proxyPort = serverPort + 1;
      proxy = new TCPProxy(proxyPort, server.getServerAddr().getAddress(), serverPort, 0, true, new File(System
          .getProperty("java.io.tmpdir")));
      proxy.start();
    }
  }

  protected void setUp() throws Exception {
    //
  }

  private void setError(Throwable t) {
    t.printStackTrace();
    error.set(t);
  }

  protected void tearDown() throws Exception {
    if (error.get() != null) {
      fail(error.get().toString());
    }
    if (proxy != null) {
      proxy.stop();
    }
    connMgr.shutdown();
    server.stop();
  }

  public void testLargeMessages() throws Exception {
    setUp(0);
    System.out.println("LARGE MESSAGES");
    runMultiClient(15, 5, 256, 2, 100, 150);
  }

  public void testLargeMsgsOnMultiThSrv() throws Exception {
    System.out.println("LARGE MESSAGES : on Multi Threaded Server : 4 threads and 15 clients");
    runMultiClientOnMultiThSrv(4, 15, 5, 256, 2, 100, 150);
  }

  public void testLargeMsgsOnMultiThSrv2() throws Exception {
    System.out.println("LARGE MESSAGES : on Multi Threaded Server : 5 threads and 15 clients");
    runMultiClientOnMultiThSrv(5, 15, 5, 256, 2, 100, 150);
  }

  public void testLargeMsgsOnMultiThSrv3() throws Exception {
    System.out.println("LARGE MESSAGES : on Multi Threaded Server : 4 threads and 2 clients");
    runMultiClientOnMultiThSrv(4, 2, 5, 256, 2, 100, 150);
  }

  public void testSmallMessages() throws Exception {
    setUp(0);
    // these messages only take one buffer
    System.out.println("SMALLEST MESSAGES");
    runMultiClient(250, 20, 0, 100, 3, 5);
  }

  public void testSmallMsgsOnMultiThSrv() throws Exception {
    System.out.println("SMALLEST MESSAGES : on Multi Threaded Server : 4 threads and 250 clients");
    runMultiClientOnMultiThSrv(4, 250, 20, 0, 100, 3, 5);
  }

  public void testSmallMsgsOnMultiThSrv2() throws Exception {
    System.out.println("SMALLEST MESSAGES : on Multi Threaded Server : 3 threads and 250 clients");
    runMultiClientOnMultiThSrv(3, 250, 20, 0, 100, 3, 5);
  }

  public void testKindaSmallMessages() throws Exception {
    setUp(0);
    // these messages span at least two byte buffers
    System.out.println("KINDA SMALL MESSAGES");
    runMultiClient(75, 10, 1, 100, 3, 7);
  }

  public void testKindaSmallMsgsOnMultiThSrv() throws Exception {
    // these messages span at least two byte buffers
    System.out
        .println("KINDA SMALLEST MESSAGES : on Multi Threaded Server : 0 threads (but 1 main listener) and 75 clients");
    runMultiClientOnMultiThSrv(0, 75, 10, 1, 100, 3, 7);
  }

  public void testKindaSmallMsgsOnMultiThSrv2() throws Exception {
    // these messages span at least two byte buffers
    System.out.println("KINDA SMALLEST MESSAGES : on Multi Threaded Server : 1 thread and 75 clients");
    runMultiClientOnMultiThSrv(1, 75, 10, 1, 100, 3, 7);
  }

  public void testKindaSmallMsgsOnMultiThSrv3() throws Exception {
    // these messages span at least two byte buffers
    System.out.println("KINDA SMALLEST MESSAGES : on Multi Threaded Server : 5 threads and 3 clients");
    runMultiClientOnMultiThSrv(5, 3, 10, 1, 100, 3, 7);
  }

  private void runMultiClientOnMultiThSrv(int numWorkerComms, int numClients, int maxConcurrent, final int dataSize,
                                          final int numToSend, final int minDelay, final int maxDelay) throws Exception {
    int HEADER = new GenericNetworkHeader().getHeaderByteLength();

    setUp(numWorkerComms);

    runMultiClient(numClients, maxConcurrent, dataSize, false, numToSend, minDelay, maxDelay);
    TCConnectionManager svrConnMgr = server.getConnectionManager();
    TCCommJDK14 svrComm = (TCCommJDK14) svrConnMgr.getTcComm();

    // Verifications

    for (int workerCommId = 0; workerCommId < numWorkerComms; workerCommId++) {
      // 1. Verify nun of clients assigned per worker comm thread
      int expNumClientPerWorkerComm = ((numClients / numWorkerComms) + (((numClients % numWorkerComms) / (workerCommId + 1)) >= 1 ? 1
          : 0));

      int actual = svrComm.getClientCountForWorkerComm(workerCommId);
      System.out.println("Wroker Comm Thread " + workerCommId + ": " + actual + " Clients (exp:"
                         + expNumClientPerWorkerComm + ")");
      Assert.assertEquals("thread " + workerCommId, expNumClientPerWorkerComm, actual);

      // 2. verify total num of bytes read per worker comm thread
      long expTotalBytesReadPerWorkerComm = (((4096 * dataSize) + (HEADER)) * numToSend) * expNumClientPerWorkerComm;
      System.out.println("Wroker Comm Thread " + workerCommId + ": " + expTotalBytesReadPerWorkerComm
                         + " BytesRead (exp:" + svrComm.getTotalbytesReadByWorkerComm(workerCommId) + ")");
      Assert.eval(svrComm.getTotalbytesReadByWorkerComm(workerCommId) == expTotalBytesReadPerWorkerComm);
    }
  }

  private void runMultiClient(int numClients, int maxConcurrent, final int dataSize, final int numToSend,
                              final int minDelay, final int maxDelay) throws Exception {
    runMultiClient(numClients, maxConcurrent, dataSize, true, numToSend, minDelay, maxDelay);
  }

  private void runMultiClient(int numClients, int maxConcurrent, final int dataSize, boolean addExtra,
                              final int numToSend, final int minDelay, final int maxDelay) throws Exception {
    long start = System.currentTimeMillis();

    try {
      final int numConcurrent = Math.min(maxConcurrent, numClients);
      PooledExecutor pool = new PooledExecutor(new LinkedQueue(), numConcurrent);
      pool.setKeepAliveTime(1000);
      pool.setMinimumPoolSize(numConcurrent);

      final TCSocketAddress addr;
      if (proxy != null) {
        addr = new TCSocketAddress(proxyPort);
      } else {
        addr = new TCSocketAddress(server.getServerAddr().getPort());
      }

      for (int i = 0; i < numClients; i++) {
        pool.execute(new ClientTask(i, new VerifierClient(connMgr, addr, dataSize, addExtra, numToSend, minDelay,
                                                          maxDelay)));
      }

      pool.shutdownAfterProcessingCurrentlyQueuedTasks();
      pool.awaitTerminationAfterShutdown();
    } finally {
      System.err.println("Took " + (System.currentTimeMillis() - start) + " millis for test: " + getName());
    }
  }

  private class ClientTask implements Runnable {
    private final VerifierClient client;
    private final int            num;

    ClientTask(int num, VerifierClient client) {
      this.num = num;
      this.client = client;
    }

    public void run() {
      try {
        client.run();
      } catch (Throwable t) {
        setError(t);
      } finally {
        System.err.println(System.currentTimeMillis() + ": client " + num + " finished");
      }
    }

  }

}
