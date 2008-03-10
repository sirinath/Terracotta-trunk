/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.statistics;

import com.tc.statistics.StatisticData;
import com.tc.statistics.gatherer.StatisticsGatherer;
import com.tc.statistics.gatherer.StatisticsGathererListener;
import com.tc.statistics.gatherer.impl.StatisticsGathererImpl;
import com.tc.statistics.retrieval.actions.SRAShutdownTimestamp;
import com.tc.statistics.retrieval.actions.SRAStartupTimestamp;
import com.tc.statistics.store.StatisticDataUser;
import com.tc.statistics.store.StatisticsRetrievalCriteria;
import com.tc.statistics.store.StatisticsStore;
import com.tc.statistics.store.h2.H2StatisticsStoreImpl;
import com.tc.util.UUID;
import com.tctest.TransparentTestBase;
import com.tctest.TransparentTestIface;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StatisticsGathererTest extends TransparentTestBase implements StatisticsGathererListener {
  private volatile String listenerConnected = null;
  private volatile boolean listenerDisconnected = false;
  private volatile boolean listenerInitialized = false;
  private volatile String listenerCapturingStarted = null;
  private volatile String listenerCapturingStopped = null;
  private volatile String listenerSessionCreated = null;
  private volatile String listenerSessionClosed = null;

  public void connected(String managerHostName, int managerPort) {
    listenerConnected = managerHostName+":"+managerPort;
  }

  public void disconnected() {
    listenerDisconnected = true;
  }

  public void reinitialized() {
    listenerInitialized = true;
  }

  public void capturingStarted(String sessionId) {
    listenerCapturingStarted = sessionId;
  }

  public void capturingStopped(String sessionId) {
    listenerCapturingStopped = sessionId;
  }

  public void sessionCreated(String sessionId) {
    listenerSessionCreated = sessionId;
  }

  public void sessionClosed(String sessionId) {
    listenerSessionClosed = sessionId;
  }

  protected void duringRunningCluster() throws Exception {
    File tmp_dir = makeTmpDir(getClass());
    
    StatisticsStore store = new H2StatisticsStoreImpl(tmp_dir);
    StatisticsGatherer gatherer = new StatisticsGathererImpl(store);
    gatherer.addListener(this);

    assertNull(gatherer.getActiveSessionId());

    assertNull(listenerConnected);
    gatherer.connect("localhost", getAdminPort());
    assertEquals("localhost:"+getAdminPort(), listenerConnected);

    String[] statistics = gatherer.getSupportedStatistics();

    String sessionid1 = UUID.getUUID().toString();
    assertNull(listenerSessionCreated);
    gatherer.createSession(sessionid1);
    assertEquals(sessionid1, listenerSessionCreated);

    assertFalse(listenerInitialized);
    gatherer.reinitialize();
    assertTrue(listenerInitialized);
    assertEquals(sessionid1, listenerSessionClosed);
    assertEquals(sessionid1, listenerCapturingStopped);

    listenerSessionCreated = null;
    assertNull(listenerSessionCreated);
    gatherer.createSession(sessionid1);
    assertEquals(sessionid1, listenerSessionCreated);

    listenerSessionClosed = null;
    listenerCapturingStopped = null;

    String sessionid2 = UUID.getUUID().toString();
    assertNull(listenerCapturingStopped);
    assertNull(listenerSessionClosed);
    gatherer.createSession(sessionid2);
    assertEquals(sessionid1, listenerSessionClosed);
    assertEquals(sessionid1, listenerCapturingStopped);
    assertEquals(sessionid2, listenerSessionCreated);

    assertEquals(sessionid2, gatherer.getActiveSessionId());

    gatherer.enableStatistics(statistics);

    assertNull(listenerCapturingStarted);
    gatherer.startCapturing();
    assertEquals(sessionid2, listenerCapturingStarted);
    Thread.sleep(10000);
    listenerCapturingStopped = null;
    assertNull(listenerCapturingStopped);
    gatherer.stopCapturing();
    assertEquals(sessionid2, listenerCapturingStopped);

    Thread.sleep(5000);

    final List<StatisticData> data_list = new ArrayList<StatisticData>();
    store.retrieveStatistics(new StatisticsRetrievalCriteria(), new StatisticDataUser() {
      public boolean useStatisticData(StatisticData data) {
        data_list.add(data);
        return true;
      }
    });

    listenerSessionClosed = null;
    assertNull(listenerSessionClosed);
    assertFalse(listenerDisconnected);
    gatherer.disconnect();
    assertEquals(sessionid2, listenerSessionClosed);
    assertTrue(listenerDisconnected);

    assertNull(gatherer.getActiveSessionId());

    // check the data
    assertTrue(data_list.size() > 2);
    assertEquals(SRAStartupTimestamp.ACTION_NAME, data_list.get(0).getName());
    assertEquals(SRAShutdownTimestamp.ACTION_NAME, data_list.get(data_list.size() - 1).getName());
    Set<String> received_data_names = new HashSet<String>();
    for (int i = 1; i < data_list.size() - 1; i++) {
      StatisticData stat_data = data_list.get(i);
      received_data_names.add(stat_data.getName());
    }

    // check that there's at least one data element name per registered statistic
    assertTrue(received_data_names.size() > statistics.length);
  }

  protected Class getApplicationClass() {
    return StatisticsGathererTestApp.class;
  }

  public void doSetUp(TransparentTestIface t) throws Exception {
    t.getTransparentAppConfig().setClientCount(StatisticsGathererConfigSampleRateTestApp.NODE_COUNT);
    t.initializeTestRunner();
  }
}