package org.terracotta.express.tests.rejoin;

import org.terracotta.express.tests.util.TCInt;
import org.terracotta.toolkit.cluster.ClusterNode;
import org.terracotta.toolkit.events.ToolkitNotificationEvent;
import org.terracotta.toolkit.events.ToolkitNotificationListener;

import java.util.concurrent.CountDownLatch;

import junit.framework.Assert;

public class MyNotificationListener implements ToolkitNotificationListener<TCInt> {
  private final CountDownLatch latch;
  private volatile ClusterNode remoteNode;
  private volatile TCInt       msgRecvd;

  public MyNotificationListener(CountDownLatch latch) {
    this.latch = latch;
  }

  public ClusterNode getRemoteNode() {
    return remoteNode;
  }

  public TCInt getMsgRecvd() {
    return msgRecvd;
  }

  @Override
  public void onNotification(ToolkitNotificationEvent event) {
    // Assert that the notification is done is Executor Thread not in Stage thread.
    System.err.println("Got notified " + event + " Thread : " + Thread.currentThread().getName());
    Assert.assertTrue(Thread.currentThread().getName().contains("ToolkitNotifier"));
    msgRecvd = (TCInt) event.getMessage();
    remoteNode = event.getRemoteNode();
    latch.countDown();
  }
}

