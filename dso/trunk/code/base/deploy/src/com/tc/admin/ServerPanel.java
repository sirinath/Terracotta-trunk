/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import org.dijon.ContainerResource;
import org.dijon.Item;
import org.dijon.ScrollPane;
import org.dijon.TabbedPane;

import com.tc.admin.common.BasicWorker;
import com.tc.admin.common.PropertyTable;
import com.tc.admin.common.PropertyTableModel;
import com.tc.admin.common.StatusView;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XTextArea;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.table.DefaultTableCellRenderer;

public class ServerPanel extends XContainer {
  private AdminClientContext m_acc;
  private ServerNode         m_serverNode;
  private PropertyTable      m_propertyTable;
  private StatusView         m_statusView;
  private TabbedPane         m_tabbedPane;
  private XTextArea          m_environmentTextArea;
  private XTextArea          m_configTextArea;

  public ServerPanel(ServerNode serverNode) {
    super(serverNode);

    m_serverNode = serverNode;
    m_acc = AdminClient.getContext();

    load((ContainerResource) m_acc.getTopRes().getComponent("ServerPanel"));

    m_tabbedPane = (TabbedPane) findComponent("TabbedPane");

    m_propertyTable = (PropertyTable) findComponent("ServerInfoTable");
    DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
    m_propertyTable.setDefaultRenderer(Long.class, renderer);
    m_propertyTable.setDefaultRenderer(Integer.class, renderer);

    m_statusView = (StatusView) findComponent("StatusIndicator");
    m_statusView.setLabel("Not connected");

    m_environmentTextArea = (XTextArea) findComponent("EnvironmentTextArea");
    ((SearchPanel) findComponent("EnvironmentSearchPanel")).setTextComponent(m_environmentTextArea);

    m_configTextArea = (XTextArea) findComponent("ConfigTextArea");
    ((SearchPanel) findComponent("ConfigSearchPanel")).setTextComponent(m_configTextArea);

    hideProductInfo();
  }

  protected void storePreferences() {
    m_acc.storePrefs();
  }

  private static class ServerState {
    private Date    fStartDate;
    private Date    fActivateDate;
    private String  fVersion;
    private String  fPatchLevel;
    private String  fCopyright;
    private String  fPersistenceMode;
    private String  fEnvironment;
    private String  fConfig;
    private Integer fDSOListenPort;

    ServerState(Date startDate, Date activateDate, String version, String patchLevel, String copyright,
                String persistenceMode, String environment, String config, Integer dsoListenPort) {
      fStartDate = startDate;
      fActivateDate = activateDate;
      fVersion = version;
      fPatchLevel = patchLevel;
      fCopyright = copyright;
      fPersistenceMode = persistenceMode;
      fEnvironment = environment;
      fConfig = config;
      fDSOListenPort = dsoListenPort;
    }

    Date getStartDate() {
      return fStartDate;
    }

    Date getActivateDate() {
      return fActivateDate;
    }

    String getVersion() {
      return fVersion;
    }

    String getPatchLevel() {
      return fPatchLevel;
    }

    String getCopyright() {
      return fCopyright;
    }

    String getPersistenceMode() {
      return fPersistenceMode;
    }

    String getEnvironment() {
      return fEnvironment;
    }

    String getConfig() {
      return fConfig;
    }

    Integer getDSOListenPort() {
      return fDSOListenPort;
    }
  }

  /**
   * TODO: grab all of these in one-shot.
   */
  private class ServerStateWorker extends BasicWorker<ServerState> {
    private ServerStateWorker() {
      super(new Callable<ServerState>() {
        public ServerState call() throws Exception {
          Date startDate = new Date(m_serverNode.getStartTime());
          Date activateDate = new Date(m_serverNode.getActivateTime());
          String version = m_serverNode.getProductVersion();
          String patchLevel = m_serverNode.getProductPatchLevel();
          String copyright = m_serverNode.getProductCopyright();
          String persistenceMode = m_serverNode.getPersistenceMode();
          String environment = m_serverNode.getEnvironment();
          String config = m_serverNode.getConfig();
          Integer dsoListenPort = m_serverNode.getDSOListenPort();

          return new ServerState(startDate, activateDate, version, patchLevel, copyright, persistenceMode, environment,
                                 config, dsoListenPort);
        }
      });
    }

    protected void finished() {
      Exception e = getException();
      if (e != null) {
        m_acc.log(e);
      } else {
        ServerState serverState = getResult();
        testSetupPersistenceModeItem();
        showProductInfo(serverState.getVersion(), serverState.getPatchLevel(), serverState.getCopyright());
        m_environmentTextArea.setText(serverState.getEnvironment());
        m_configTextArea.setText(serverState.getConfig());
      }
    }

    private void testSetupPersistenceModeItem() {
      Item infoItem = (Item) findComponent("RestartabilityInfoItem");

      if (!m_serverNode.getPersistenceMode().equals("permanent-store")) {
        String warning = m_acc.getString("server.non-restartable.warning");
        PersistenceModeWarningPanel restartabilityInfoPanel = new PersistenceModeWarningPanel(warning);
        infoItem.setChild(restartabilityInfoPanel);
      } else {
        infoItem.setChild(null);
      }
    }
  }

  private class StartedWorker extends ServerStateWorker {
    protected void finished() {
      super.finished();
      if (getException() == null) {
        ServerState serverState = getResult();
        String startTime = serverState.getStartDate().toString();
        setStatusLabel(m_acc.format("server.started.label", startTime));
        m_acc.setStatus(m_acc.format("server.started.status", m_serverNode, startTime));
      } else {
        m_acc.log(getException());
      }
    }
  }

  private class ActivatedWorker extends ServerStateWorker {
    protected void finished() {
      super.finished();
      if (getException() == null) {
        ServerState serverState = getResult();
        String activateTime = serverState.getActivateDate().toString();
        setStatusLabel(m_acc.format("server.activated.label", activateTime));
        m_acc.setStatus(m_acc.format("server.activated.status", m_serverNode, activateTime));
      } else {
        m_acc.log(getException());
      }
    }
  }

  private class PassiveUninitializedWorker extends ServerStateWorker {
    protected void finished() {
      super.finished();
      if (getException() == null) {
        String startTime = new Date().toString();
        setStatusLabel(m_acc.format("server.initializing.label", startTime));
        m_acc.setStatus(m_acc.format("server.initializing.status", m_serverNode, startTime));
      }
    }
  }

  private class PassiveStandbyWorker extends ServerStateWorker {
    protected void finished() {
      super.finished();
      if (getException() == null) {
        String startTime = new Date().toString();
        setStatusLabel(m_acc.format("server.standingby.label", startTime));
        m_acc.setStatus(m_acc.format("server.standingby.status", m_serverNode, startTime));
      }
    }
  }

  /**
   * The only differences between activated() and started() is the status message and the serverlog is only added in
   * activated() under the presumption that a non-active server won't be saying anything.
   */
  void started() {
    m_acc.execute(new StartedWorker());
  }

  void activated() {
    m_acc.execute(new ActivatedWorker());
  }

  void passiveUninitialized() {
    m_acc.execute(new PassiveUninitializedWorker());
  }

  void passiveStandby() {
    m_acc.execute(new PassiveStandbyWorker());
  }

  void disconnected() {
    String startTime = new Date().toString();

    setStatusLabel(m_acc.format("server.disconnected.label", startTime));
    hideProductInfo();
    m_acc.setStatus(m_acc.format("server.disconnected.status", m_serverNode, startTime));
  }

  private void setTabbedPaneEnabled(boolean enabled) {
    m_tabbedPane.setEnabled(enabled);
    int tabCount = m_tabbedPane.getTabCount();
    for (int i = 0; i < tabCount; i++) {
      m_tabbedPane.setEnabledAt(i, enabled);
    }
    m_tabbedPane.setSelectedIndex(0);
  }

  void setConnectExceptionMessage(String msg) {
    setStatusLabel(msg);
    setTabbedPaneEnabled(false);
  }

  void setStatusLabel(String msg) {
    m_statusView.setLabel(msg);
    m_statusView.setIndicator(m_serverNode.getServerStatusColor());
  }

  /**
   * The fields listed below are on ProductNode. If those methods change, so will these fields need to change.
   * PropertyTable uses reflection to access values to display. TODO: i18n
   */
  private void showProductInfo(String version, String patch, String copyright) {
    String[] fields = { "CanonicalHostName", "HostAddress", "Port", "DSOListenPort", "ProductVersion",
        "ProductBuildID", "ProductLicense", "PersistenceMode", "FailoverMode" };
    String[] headings = { "Host", "Address", "JMX port", "DSO port", "Version", "Build", "License", "Persistence mode",
        "Failover mode" };
    List<String> fieldList = new ArrayList(Arrays.asList(fields));
    List<String> headingList = new ArrayList(Arrays.asList(headings));
    if (patch != null && patch.length() > 0) {
      fieldList.add(fieldList.indexOf("ProductLicense"), "ProductPatchVersion");
      headingList.add(headingList.indexOf("License"), "Patch");
    }
    fields = fieldList.toArray(new String[fieldList.size()]);
    headings = headingList.toArray(new String[headingList.size()]);
    m_propertyTable.setModel(new PropertyTableModel(m_serverNode, fields, headings));
    m_propertyTable.getAncestorOfClass(ScrollPane.class).setVisible(true);

    setTabbedPaneEnabled(true);

    revalidate();
    repaint();
  }

  private void hideProductInfo() {
    m_propertyTable.setModel(new PropertyTableModel());
    m_propertyTable.getAncestorOfClass(ScrollPane.class).setVisible(false);
    m_tabbedPane.setSelectedIndex(0);
    m_tabbedPane.setEnabled(false);

    revalidate();
    repaint();
  }

  public void tearDown() {
    m_statusView.tearDown();

    super.tearDown();

    m_acc = null;
    m_serverNode = null;
    m_propertyTable = null;
    m_statusView = null;
    m_tabbedPane = null;
    m_environmentTextArea = null;
    m_configTextArea = null;
  }
}
