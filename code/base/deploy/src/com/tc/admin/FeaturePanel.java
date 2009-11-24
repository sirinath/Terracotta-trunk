/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.admin;

import org.terracotta.modules.configuration.Presentation;
import org.terracotta.modules.configuration.PresentationContext;
import org.terracotta.modules.configuration.PresentationFactory;

import com.tc.admin.common.XContainer;
import com.tc.admin.common.XLabel;
import com.tc.admin.common.XScrollPane;
import com.tc.admin.common.XTextArea;
import com.tc.admin.model.IClusterModel;
import com.tc.util.concurrent.ThreadUtil;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.swing.BorderFactory;
import javax.swing.JProgressBar;

public class FeaturePanel extends XContainer {
  protected Feature             feature;
  protected IAdminClientContext adminClientContext;
  protected IClusterModel       clusterModel;
  protected Presentation        presentation;

  public FeaturePanel(final Feature feature, IAdminClientContext adminClientContext, IClusterModel clusterModel) {
    super(new BorderLayout());

    this.feature = feature;
    this.adminClientContext = adminClientContext;
    this.clusterModel = clusterModel;

    XContainer panel = new XContainer();
    panel.setBorder(BorderFactory.createTitledBorder(feature.getDisplayName()));
    add(panel);

    if (feature.isReady()) {
      createPresentation();
    } else {
      panel.setLayout(new GridBagLayout());
      GridBagConstraints gbc = new GridBagConstraints();
      gbc.gridx = gbc.gridy = 0;
      panel.add(new XLabel("Please wait while the feature '" + feature.getDisplayName() + "' is loaded."), gbc);
      gbc.gridy++;
      JProgressBar progressBar = new JProgressBar();
      panel.add(progressBar, gbc);
      progressBar.setIndeterminate(true);
      adminClientContext.execute(new Runnable() {
        public void run() {
          while (true) {
            if (feature.isReady()) {
              createPresentation();
              return;
            } else if (feature.hasError()) {
              showError(feature.getError());
              return;
            }
            ThreadUtil.reallySleep(500);
          }
        }
      });
    }
  }

  private void createPresentation() {
    try {
      PresentationFactory factory;
      if ((factory = feature.getPresentationFactory()) != null) {
        presentation = factory.create(PresentationContext.DEV);
        if (presentation != null) {
          removeAll();
          add(presentation);
          presentation.setup(adminClientContext, clusterModel);
          revalidate();
          repaint();
        } else {
          System.err.println("Failed to instantiate instance of '" + factory + "'");
        }
      } else {
        System.err.println("Failed to load PresentationFactory for feature '" + feature + "'");
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void showError(Throwable error) {
    removeAll();
    setLayout(new BorderLayout());
    XLabel label = new XLabel("There was a problem loading feature '" + feature + "'");
    add(label, BorderLayout.NORTH);
    label.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
    XTextArea textArea = new XTextArea();
    textArea.setEditable(false);
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    error.printStackTrace(pw);
    textArea.setText(sw.toString());
    add(new XScrollPane(textArea), BorderLayout.CENTER);
    revalidate();
    repaint();
  }

  @Override
  public void tearDown() {
    if (presentation != null) {
      presentation.tearDown();
    }

    synchronized (this) {
      feature = null;
      adminClientContext = null;
      clusterModel = null;
      presentation = null;
    }

    super.tearDown();
  }
}
