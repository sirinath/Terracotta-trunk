/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import com.tc.admin.common.ApplicationContext;
import com.tc.admin.common.ComponentNode;
import com.tc.admin.model.IClusterModel;
import com.tc.admin.model.IClusterStatsListener;
import com.tc.admin.model.IServer;

import java.awt.Component;
import java.util.Map;

public class StatsRecorderNode extends ComponentNode implements IClusterStatsListener {
  protected ApplicationContext appContext;
  private IClusterModel        clusterModel;
  private StatsRecorderPanel   statsRecorderPanel;
  private String               baseLabel;
  private String               recordingSuffix;

  public StatsRecorderNode(ApplicationContext appContext, IClusterModel clusterModel) {
    super();

    this.appContext = appContext;
    this.clusterModel = clusterModel;

    setLabel(baseLabel = appContext.getMessage("stats.recorder.node.label"));
    recordingSuffix = appContext.getMessage("stats.recording.suffix");
    setIcon(ServerHelper.getHelper().getStatsRecorderIcon());

    clusterModel.addPropertyChangeListener(new ClusterListener(clusterModel));
    if (clusterModel.isReady()) {
      IServer activeCoord = getActiveCoordinator();
      if (activeCoord != null) {
        activeCoord.addClusterStatsListener(this);
      }
    }
  }

  public Component getComponent() {
    if (statsRecorderPanel == null) {
      statsRecorderPanel = createStatsRecorderPanel(appContext, clusterModel);
      statsRecorderPanel.setNode(this);
    }
    return statsRecorderPanel;
  }

  protected StatsRecorderPanel createStatsRecorderPanel(ApplicationContext theAppContext, IClusterModel theClusterModel) {
    return new StatsRecorderPanel(theAppContext, theClusterModel);
  }

  synchronized IClusterModel getClusterModel() {
    return clusterModel;
  }

  synchronized IServer getActiveCoordinator() {
    IClusterModel theClusterModel = getClusterModel();
    return theClusterModel != null ? theClusterModel.getActiveCoordinator() : null;
  }

  private class ClusterListener extends AbstractClusterListener {
    private ClusterListener(IClusterModel clusterModel) {
      super(clusterModel);
    }

    public void handleActiveCoordinator(IServer oldActive, IServer newActive) {
      if (oldActive != null) {
        oldActive.removeClusterStatsListener(StatsRecorderNode.this);
      }
      if (newActive != null) {
        if (newActive.isClusterStatsSupported()) {
          newActive.addClusterStatsListener(StatsRecorderNode.this);
        }
      }
    }
  }

  String[] getConnectionCredentials() {
    return clusterModel.getConnectionCredentials();
  }

  Map<String, Object> getConnectionEnvironment() {
    return clusterModel.getConnectionEnvironment();
  }

  void showRecording(boolean recording) {
    setLabel(baseLabel + (recording ? recordingSuffix : ""));
    nodeChanged();
  }

  public void tearDown() {
    super.tearDown();

    synchronized (this) {
      appContext = null;
      clusterModel = null;
      statsRecorderPanel = null;
    }
  }

  /*
   * IClusterStatsListener implementation
   */
  
  public void allSessionsCleared() {
    /**/
  }

  public void connected() {
    /**/
  }

  public void disconnected() {
    /**/
  }

  public void reinitialized() {
    /**/
  }

  public void sessionCleared(String sessionId) {
    /**/
  }

  public void sessionCreated(String sessionId) {
    /**/
  }

  public void sessionStarted(String sessionId) {
    showRecording(true);
  }

  public void sessionStopped(String sessionId) {
    showRecording(false);
  }
}
