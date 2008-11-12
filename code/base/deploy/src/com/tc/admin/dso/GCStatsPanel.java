/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.dso;

import org.dijon.Button;
import org.dijon.ContainerResource;
import org.dijon.Label;
import org.dijon.PopupMenu;

import com.tc.admin.AdminClient;
import com.tc.admin.AdminClientContext;
import com.tc.admin.common.BasicWorker;
import com.tc.admin.common.BrowserLauncher;
import com.tc.admin.common.ExceptionHelper;
import com.tc.admin.common.XAbstractAction;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XObjectTable;
import com.tc.admin.model.DGCListener;
import com.tc.admin.model.IClusterModel;
import com.tc.objectserver.api.GCStats;
import com.tc.util.ProductInfo;

import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.Callable;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class GCStatsPanel extends XContainer implements DGCListener, PropertyChangeListener {
  private AdminClientContext m_acc;
  private GCStatsNode        m_gcStatsNode;
  private XObjectTable       m_table;
  private Label              m_overviewLabel;
  private PopupMenu          m_popupMenu;
  private RunGCAction        m_gcAction;

  public GCStatsPanel(GCStatsNode gcStatsNode) {
    super();

    m_acc = AdminClient.getContext();
    load((ContainerResource) m_acc.getComponent("GCStatsPanel"));

    m_gcStatsNode = gcStatsNode;
    m_table = (XObjectTable) findComponent("GCStatsTable");
    m_table.setModel(new GCStatsTableModel());

    m_gcAction = new RunGCAction();
    Button runDGCButton = (Button) findComponent("RunGCButton");
    runDGCButton.setAction(m_gcAction);

    m_overviewLabel = (Label) findComponent("OverviewLabel");
    m_overviewLabel.setText(m_acc.getString("dso.gcstats.overview.pending"));

    ((Button) findComponent("HelpButton")).addActionListener(new HelpButtonHandler());

    m_popupMenu = new PopupMenu("DGC");
    m_popupMenu.add(m_gcAction);
    m_table.add(m_popupMenu);
    m_table.addMouseListener(new TableMouseHandler());

    m_acc.execute(new InitWorker());
    gcStatsNode.getClusterModel().addDGCListener(this);
    gcStatsNode.getClusterModel().addPropertyChangeListener(this);

    m_acc.submit(new InitOverviewTextWorker());
  }

  private class InitOverviewTextWorker extends BasicWorker<String> {
    private InitOverviewTextWorker() {
      super(new Callable<String>() {
        public String call() {
          IClusterModel clusterModel = m_gcStatsNode.getClusterModel();
          if (clusterModel.isGarbageCollectionEnabled()) {
            int seconds = clusterModel.getGarbageCollectionInterval();
            float minutes = seconds / 60f;
            return m_acc.format("dso.gcstats.overview.enabled", seconds, minutes);
          } else {
            return m_acc.getString("dso.gcstats.overview.enabled");
          }
        }
      });
    }

    protected void finished() {
      Exception e = getException();
      if (e == null) {
        m_overviewLabel.setText(getResult());
      }
    }
  }

  private class HelpButtonHandler implements ActionListener {
    private String getKitID() {
      String kitID = ProductInfo.getInstance().kitID();
      if (ProductInfo.UNKNOWN_VALUE.equals(kitID)) {
        kitID = System.getProperty("com.tc.kitID", "42.0");
      }
      return kitID;
    }

    public void actionPerformed(ActionEvent e) {
      String kitID = getKitID();
      String loc = AdminClient.getContext().format("console.guide.url", kitID)
                   + "#AdminConsoleGuide-DistributedGarbageCollection";
      BrowserLauncher.openURL(loc);
    }
  }

  public void propertyChange(PropertyChangeEvent evt) {
    if (IClusterModel.PROP_ACTIVE_SERVER.equals(evt.getPropertyName())) {
      if (((IClusterModel) evt.getSource()).getActiveServer() != null) {
        SwingUtilities.invokeLater(new Runnable() {
          public void run() {
            if (m_table != null) {
              GCStatsTableModel model = (GCStatsTableModel) m_table.getModel();
              model.clear();
              model.fireTableDataChanged();
            }
          }
        });
      }
    }
  }

  private class InitWorker extends BasicWorker<GCStats[]> {
    private InitWorker() {
      super(new Callable<GCStats[]>() {
        public GCStats[] call() throws Exception {
          return m_gcStatsNode.getClusterModel().getGCStats();
        }
      });
    }

    protected void finished() {
      Exception e = getException();
      if (e != null) {
        m_acc.log(e);
      } else {
        GCStatsTableModel model = (GCStatsTableModel) m_table.getModel();
        model.setGCStats(getResult());
      }
      m_gcAction.setEnabled(true);
    }
  }

  class TableMouseHandler extends MouseAdapter {
    public void mousePressed(MouseEvent e) {
      testPopup(e);
    }

    public void mouseReleased(MouseEvent e) {
      testPopup(e);
    }

    public void testPopup(MouseEvent e) {
      if (e.isPopupTrigger()) {
        m_popupMenu.show(m_table, e.getX(), e.getY());
      }
    }
  }

  class RunGCAction extends XAbstractAction {
    RunGCAction() {
      super("Run DGC");
    }

    public void actionPerformed(ActionEvent ae) {
      runGC();
    }
  }

  public void statusUpdate(GCStats gcStats) {
    SwingUtilities.invokeLater(new ModelUpdater(gcStats));
  }

  private class ModelUpdater implements Runnable {
    private GCStats m_gcStats;

    private ModelUpdater(GCStats gcStats) {
      m_gcStats = gcStats;
    }

    public void run() {
      m_gcAction.setEnabled(m_gcStats.getElapsedTime() != -1);
      ((GCStatsTableModel) m_table.getModel()).addGCStats(m_gcStats);
    }
  }

  private class RunGCWorker extends BasicWorker<Void> {
    private RunGCWorker() {
      super(new Callable<Void>() {
        public Void call() {
          m_gcStatsNode.getClusterModel().runGC();
          return null;
        }
      });
    }

    protected void finished() {
      Exception e = getException();
      if (e != null) {
        Frame frame = (Frame) getAncestorOfClass(Frame.class);
        Throwable cause = ExceptionHelper.getRootCause(e);
        String msg = cause.getMessage();
        String title = frame.getTitle();

        JOptionPane.showMessageDialog(frame, msg, title, JOptionPane.INFORMATION_MESSAGE);
      }
    }
  }

  private void runGC() {
    m_acc.execute(new RunGCWorker());
  }

  public void tearDown() {
    m_gcStatsNode.getClusterModel().removeDGCListener(this);
    m_gcStatsNode.getClusterModel().removePropertyChangeListener(this);

    super.tearDown();

    m_acc = null;
    m_gcStatsNode = null;
    m_table = null;
    m_popupMenu = null;
    m_gcAction = null;
  }
}
