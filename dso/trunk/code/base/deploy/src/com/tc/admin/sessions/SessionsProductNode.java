/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.admin.sessions;

import com.tc.admin.AdminClient;
import com.tc.admin.ConnectionContext;
import com.tc.admin.common.ComponentNode;
import com.tc.admin.common.XAbstractAction;
import com.tc.admin.dso.ClassesHelper;
import com.tc.management.exposed.SessionsProductMBean;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultTreeModel;

public class SessionsProductNode extends ComponentNode implements NotificationListener {
  private ConnectionContext m_cc;
  private ObjectName        m_beanName;
  private JPopupMenu        m_popupMenu;
  private RefreshAction     m_refreshAction;

  private static final String REFRESH_ACTION = "RefreshAction";
  
  public SessionsProductNode(ConnectionContext cc, SessionsProductMBean bean, ObjectName beanName) {
    super();
    
    m_cc = cc;
    m_beanName = beanName;
        
    setLabel(beanName.getKeyProperty("node"));
    setComponent(new SessionsProductPanel(bean));

    initMenu();
    startListening();
  }

  private ObjectName getMBeanServerDelegate() {
    try {
      return m_cc.queryName("JMImplementation:type=MBeanServerDelegate");
    } catch(Exception ioe) {
      return null;
    }
  }
  
  private void startListening() {
    ObjectName mbsd = getMBeanServerDelegate();
    
    if(mbsd != null) {
      try {
        m_cc.addNotificationListener(mbsd, this);
      } catch(Exception e) {/**/}
    }
  }
  
  private void stopListening() {
    ObjectName mbsd = getMBeanServerDelegate();
      
    if(mbsd != null) {
      try {
        m_cc.removeNotificationListener(mbsd, this);
      } catch(Exception e) {/**/}
    }
  }
  
  public void handleNotification(Notification notification, Object handback) {
    if(notification instanceof MBeanServerNotification) {
      MBeanServerNotification mbsn = (MBeanServerNotification)notification;
      String                  type = notification.getType();
      ObjectName              name = mbsn.getMBeanName();
      
      if(type.equals(MBeanServerNotification.UNREGISTRATION_NOTIFICATION)) {
        if(name.equals(m_beanName)) {
          stopListening();
          ((DefaultTreeModel)getModel()).removeNodeFromParent(this);
        }
      }
    }
  }
  
  private void initMenu() {
    m_refreshAction = new RefreshAction();

    m_popupMenu = new JPopupMenu("SessionMonitor Actions");
    m_popupMenu.add(m_refreshAction);

    addActionBinding(REFRESH_ACTION, m_refreshAction);
  }

  public JPopupMenu getPopupMenu() {
    return m_popupMenu;
  }

  public void refresh() {
    ((SessionsProductPanel)getComponent()).refresh();
  }

  private class RefreshAction extends XAbstractAction {
    private RefreshAction() {
      super();

      setName(AdminClient.getContext().getMessage("refresh.name"));
      setSmallIcon(ClassesHelper.getHelper().getRefreshIcon());
      setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0, true));
    }

    public void actionPerformed(ActionEvent ae) {
      refresh();
    }
  }

  public void nodeClicked(MouseEvent me) {
    m_refreshAction.actionPerformed(null);
  }
}
