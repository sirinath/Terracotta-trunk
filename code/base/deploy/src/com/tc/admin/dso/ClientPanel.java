/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.dso;

import org.dijon.CheckBox;
import org.dijon.ContainerResource;
import org.dijon.TextArea;

import com.tc.admin.AdminClient;
import com.tc.admin.AdminClientContext;
import com.tc.admin.common.BasicWorker;
import com.tc.admin.common.PropertyTable;
import com.tc.admin.common.PropertyTableModel;
import com.tc.admin.common.XContainer;
import com.tc.management.beans.l1.L1InfoMBean;
import com.tc.management.beans.logging.InstrumentationLoggingMBean;
import com.tc.management.beans.logging.RuntimeLoggingMBean;
import com.tc.management.beans.logging.RuntimeOutputOptionsMBean;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.Callable;

import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;

public class ClientPanel extends XContainer implements NotificationListener {
  private AdminClientContext        m_acc;
  protected DSOClient               m_client;
  private L1InfoMBean               m_l1InfoBean;

  private PropertyTable             m_propertyTable;

  private TextArea                  m_environmentTextArea;
  private TextArea                  m_configTextArea;

  private CheckBox                  m_classCheckBox;
  private CheckBox                  m_locksCheckBox;
  private CheckBox                  m_transientRootCheckBox;
  private CheckBox                  m_rootsCheckBox;
  private CheckBox                  m_distributedMethodsCheckBox;

  private CheckBox                  m_nonPortableDumpCheckBox;
  private CheckBox                  m_lockDebugCheckBox;
  private CheckBox                  m_fieldChangeDebugCheckBox;
  private CheckBox                  m_waitNotifyDebugCheckBox;
  private CheckBox                  m_distributedMethodDebugCheckBox;
  private CheckBox                  m_newObjectDebugCheckBox;

  private CheckBox                  m_autoLockDetailsCheckBox;
  private CheckBox                  m_callerCheckBox;
  private CheckBox                  m_fullStackCheckBox;

  private ActionListener            m_loggingChangeHandler;
  private HashMap<String, CheckBox> m_loggingControlMap;

  public ClientPanel(DSOClient client) {
    super();

    m_acc = AdminClient.getContext();

    load((ContainerResource) m_acc.topRes.getComponent("ClientPanel"));

    m_propertyTable = (PropertyTable) findComponent("ClientInfoTable");
    DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
    m_propertyTable.setDefaultRenderer(Long.class, renderer);
    m_propertyTable.setDefaultRenderer(Integer.class, renderer);

    m_environmentTextArea = (TextArea) findComponent("EnvironmentTextArea");
    m_configTextArea = (TextArea) findComponent("ConfigTextArea");

    m_classCheckBox = (CheckBox) findComponent("Class1");
    m_locksCheckBox = (CheckBox) findComponent("Locks");
    m_transientRootCheckBox = (CheckBox) findComponent("TransientRoot");
    m_rootsCheckBox = (CheckBox) findComponent("Roots");
    m_distributedMethodsCheckBox = (CheckBox) findComponent("DistributedMethods");

    m_nonPortableDumpCheckBox = (CheckBox) findComponent("NonPortableDump");
    m_lockDebugCheckBox = (CheckBox) findComponent("LockDebug");
    m_fieldChangeDebugCheckBox = (CheckBox) findComponent("FieldChangeDebug");
    m_waitNotifyDebugCheckBox = (CheckBox) findComponent("WaitNotifyDebug");
    m_distributedMethodDebugCheckBox = (CheckBox) findComponent("DistributedMethodDebug");
    m_newObjectDebugCheckBox = (CheckBox) findComponent("NewObjectDebug");

    m_autoLockDetailsCheckBox = (CheckBox) findComponent("AutoLockDetails");
    m_callerCheckBox = (CheckBox) findComponent("Caller");
    m_fullStackCheckBox = (CheckBox) findComponent("FullStack");

    m_loggingControlMap = new HashMap<String, CheckBox>();

    setClient(client);
  }

  public void setClient(DSOClient client) {
    m_client = client;

    String[] fields = { "Host", "Port", "ChannelID" };
    m_propertyTable.setModel(new PropertyTableModel(client, fields, fields));

    try {
      m_l1InfoBean = client.getL1InfoMBean(this);
      if (m_l1InfoBean != null) {
        m_l1InfoBean.addNotificationListener(this, null, null);
        m_environmentTextArea.setText(m_l1InfoBean.getEnvironment());
        m_configTextArea.setText(m_l1InfoBean.getConfig());
      }
    } catch (Exception e) {
      m_acc.log(e);
    }

    m_loggingChangeHandler = new LoggingChangeHandler();

    try {
      InstrumentationLoggingMBean instrumentationLoggingBean = client.getInstrumentationLoggingMBean(this);
      if (instrumentationLoggingBean != null) {
        setupInstrumentationLogging(instrumentationLoggingBean);
        instrumentationLoggingBean.addNotificationListener(this, null, null);
      }
    } catch (Exception e) {
      m_acc.log(e);
    }

    try {
      RuntimeLoggingMBean runtimeLoggingBean = client.getRuntimeLoggingMBean(this);
      if (runtimeLoggingBean != null) {
        setupRuntimeLogging(runtimeLoggingBean);
        runtimeLoggingBean.addNotificationListener(this, null, null);
      }
    } catch (Exception e) {
      m_acc.log(e);
    }

    try {
      RuntimeOutputOptionsMBean runtimeOutputOptionsBean = client.getRuntimeOutputOptionsMBean(this);
      if (runtimeOutputOptionsBean != null) {
        setupRuntimeOutputOptions(runtimeOutputOptionsBean);
        runtimeOutputOptionsBean.addNotificationListener(this, null, null);
      }
    } catch (Exception e) {
      m_acc.log(e);
    }
  }

  public DSOClient getClient() {
    return m_client;
  }

  private void setupInstrumentationLogging(InstrumentationLoggingMBean instrumentationLoggingBean) {
    setupLoggingControl(m_classCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_locksCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_transientRootCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_rootsCheckBox, instrumentationLoggingBean);
    setupLoggingControl(m_distributedMethodsCheckBox, instrumentationLoggingBean);
  }

  private void setupRuntimeLogging(RuntimeLoggingMBean runtimeLoggingBean) {
    setupLoggingControl(m_nonPortableDumpCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_lockDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_fieldChangeDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_waitNotifyDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_distributedMethodDebugCheckBox, runtimeLoggingBean);
    setupLoggingControl(m_newObjectDebugCheckBox, runtimeLoggingBean);
  }

  private void setupRuntimeOutputOptions(RuntimeOutputOptionsMBean runtimeOutputOptionsBean) {
    setupLoggingControl(m_autoLockDetailsCheckBox, runtimeOutputOptionsBean);
    setupLoggingControl(m_callerCheckBox, runtimeOutputOptionsBean);
    setupLoggingControl(m_fullStackCheckBox, runtimeOutputOptionsBean);
  }

  private void setupLoggingControl(CheckBox checkBox, Object bean) {
    setLoggingControl(checkBox, bean);
    checkBox.putClientProperty(checkBox.getName(), bean);
    checkBox.addActionListener(m_loggingChangeHandler);
    m_loggingControlMap.put(checkBox.getName(), checkBox);
  }

  private void setLoggingControl(CheckBox checkBox, Object bean) {
    try {
      Class beanClass = bean.getClass();
      Method setter = beanClass.getMethod("get" + checkBox.getName(), new Class[0]);
      Boolean value = (Boolean) setter.invoke(bean, new Object[0]);
      checkBox.setSelected(value.booleanValue());
    } catch (Exception e) {
      m_acc.log(e);
    }
  }

  private class LoggingChangeWorker extends BasicWorker<Void> {
    private LoggingChangeWorker(final Object loggingBean, final String attrName, final boolean enabled) {
      super(new Callable<Void>() {
        public Void call() throws Exception {
          Class beanClass = loggingBean.getClass();
          Method setter = beanClass.getMethod("set" + attrName, new Class[] { Boolean.TYPE });
          setter.invoke(loggingBean, new Object[] { Boolean.valueOf(enabled) });
          return null;
        }
      });
    }

    protected void finished() {
      Exception e = getException();
      if (e != null) {
        m_acc.log(e);
      }
    }
  }

  class LoggingChangeHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      CheckBox checkBox = (CheckBox) ae.getSource();
      Object loggingBean = checkBox.getClientProperty(checkBox.getName());
      String attrName = checkBox.getName();
      boolean enabled = checkBox.isSelected();
      LoggingChangeWorker worker = new LoggingChangeWorker(loggingBean, attrName, enabled);

      m_acc.executorService.execute(worker);
    }
  }

  public void handleNotification(Notification notification, Object handback) {
    String type = notification.getType();

    if (type.startsWith("tc.logging.")) {
      String name = type.substring(type.lastIndexOf('.') + 1);
      CheckBox checkBox = m_loggingControlMap.get(name);
      if (checkBox != null) {
        checkBox.setSelected(Boolean.valueOf(notification.getMessage()));
      }
      return;
    }

    if (notification instanceof MBeanServerNotification) {
      MBeanServerNotification mbsn = (MBeanServerNotification) notification;

      if (type.equals(MBeanServerNotification.REGISTRATION_NOTIFICATION)) {
        String on = mbsn.getMBeanName().getCanonicalName();

        if (on.equals(m_client.getInstrumentationLoggingObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                InstrumentationLoggingMBean instrumentationLoggingBean = m_client.getInstrumentationLoggingMBean();
                setupInstrumentationLogging(instrumentationLoggingBean);
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        } else if (on.equals(m_client.getRuntimeLoggingObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                RuntimeLoggingMBean runtimeLoggingBean = m_client.getRuntimeLoggingMBean();
                setupRuntimeLogging(runtimeLoggingBean);
                runtimeLoggingBean.addNotificationListener(ClientPanel.this, null, null);
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        } else if (on.equals(m_client.getRuntimeOutputOptionsObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                RuntimeOutputOptionsMBean runtimeOutputOptionsBean = m_client.getRuntimeOutputOptionsMBean();
                setupRuntimeOutputOptions(runtimeOutputOptionsBean);
                runtimeOutputOptionsBean.addNotificationListener(ClientPanel.this, null, null);
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        } else if (on.equals(m_client.getL1InfoObjectName().getCanonicalName())) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              try {
                m_l1InfoBean = m_client.getL1InfoMBean();
                m_l1InfoBean.addNotificationListener(ClientPanel.this, null, null);
                m_environmentTextArea.setText(m_l1InfoBean.getEnvironment());
                m_configTextArea.setText(m_l1InfoBean.getConfig());
              } catch (Exception e) {
                // just wait for disconnect to occur
              }
            }
          });
        }
      }
    }
  }

  L1InfoMBean getL1InfoBean() {
    return m_l1InfoBean;
  }

  public void tearDown() {
    super.tearDown();

    m_acc = null;
    m_client = null;

    m_propertyTable = null;

    m_environmentTextArea = null;
    m_configTextArea = null;

    m_classCheckBox = null;
    m_locksCheckBox = null;
    m_transientRootCheckBox = null;
    m_rootsCheckBox = null;
    m_distributedMethodsCheckBox = null;
    m_nonPortableDumpCheckBox = null;
    m_lockDebugCheckBox = null;
    m_fieldChangeDebugCheckBox = null;
    m_waitNotifyDebugCheckBox = null;
    m_distributedMethodDebugCheckBox = null;
    m_newObjectDebugCheckBox = null;
    m_autoLockDetailsCheckBox = null;
    m_callerCheckBox = null;
    m_fullStackCheckBox = null;

    m_loggingChangeHandler = null;
    m_loggingControlMap.clear();
    m_loggingControlMap = null;
  }
}
