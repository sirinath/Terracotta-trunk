/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management;

import com.tc.management.beans.MBeanNames;
import com.tc.net.TCSocketAddress;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.Query;
import javax.management.QueryExp;

public abstract class TerracottaManagement {

  private static final ManagementResources MANAGEMENT_RESOURCES = new ManagementResources();

  public static class Type {

    private static final Map typesByName = Collections.synchronizedMap(new HashMap());
    public static final Type DsoClient   = new Type(MANAGEMENT_RESOURCES.getDsoClientType());
    public static final Type Sessions    = new Type(MANAGEMENT_RESOURCES.getSessionsType());
    public static final Type Server      = new Type(MANAGEMENT_RESOURCES.getTerracottaServerType());
    public static final Type Cluster     = new Type(MANAGEMENT_RESOURCES.getTerracottaClusterType());
    public static final Type Agent       = new Type(MANAGEMENT_RESOURCES.getTerracottaAgentType());

    private final String     type;

    private Type(final String type) {
      this.type = type;
      typesByName.put(type, this);
    }

    public String toString() {
      return type;
    }

    static Type getType(String name) {
      return (Type) typesByName.get(name);
    }
  }

  public static class Subsystem {

    private static final Map      subsystemByName  = Collections.synchronizedMap(new HashMap());
    public static final Subsystem Tx               = new Subsystem(MANAGEMENT_RESOURCES.getTransactionSubsystem());
    public static final Subsystem Locking          = new Subsystem(MANAGEMENT_RESOURCES.getLockingSubsystem());
    public static final Subsystem ObjectManagement = new Subsystem(MANAGEMENT_RESOURCES.getObjectManagementSubsystem());
    public static final Subsystem Logging          = new Subsystem(MANAGEMENT_RESOURCES.getLoggingSubsystem());
    public static final Subsystem Statistics       = new Subsystem(MANAGEMENT_RESOURCES.getStatisticsSubsystem());
    public static final Subsystem None             = new Subsystem(MANAGEMENT_RESOURCES.getNoneSubsystem());

    private final String          subsystem;

    private Subsystem(final String subsystem) {
      this.subsystem = subsystem;
      subsystemByName.put(subsystem, this);
    }

    public String toString() {
      return subsystem;
    }

    static Subsystem getSubsystem(String name) {
      return (Subsystem) subsystemByName.get(name);
    }
  }

  public static interface MBeanKeys {
    public static final String TYPE            = "type";
    public static final String MBEAN_NODE      = "node";
    public static final String MBEAN_NODE_NAME = "node-name";
    public static final String SUBSYSTEM       = "subsystem";
    public static final String NAME            = "name";
  }

  public static final String  PUBLIC_DOMAIN   = MANAGEMENT_RESOURCES.getPublicMBeanDomain();
  public static final String  INTERNAL_DOMAIN = MANAGEMENT_RESOURCES.getInternalMBeanDomain();

  private static final String COMMA           = ",";
  private static final String COLON           = ":";
  private static final String EQUALS          = "=";
  private static final String SLASH           = "/";

  private static final String NODE_PREFIX_KEY = "clients";
  private static final String NODE_PREFIX     = NODE_PREFIX_KEY + EQUALS + "Clients";

  private static final String NODE_NAME       = System.getProperty(MANAGEMENT_RESOURCES.getNodeNameSystemProperty());

  public static ObjectName createObjectName(final Type type, final Subsystem subsystem,
                                            final TCSocketAddress remoteBeanHome, final String uiFriendlyName,
                                            final boolean isPublic) throws MalformedObjectNameException {
    final StringBuffer objName = new StringBuffer(isPublic ? PUBLIC_DOMAIN : INTERNAL_DOMAIN);
    objName.append(COLON);
    if (NODE_NAME != null || remoteBeanHome != null) {
      objName.append(NODE_PREFIX);
      if (NODE_NAME != null) {
        objName.append(COMMA).append(MBeanKeys.MBEAN_NODE_NAME).append(EQUALS).append(NODE_NAME);
      }
      if (remoteBeanHome != null) {
        addNodeInfo(objName, remoteBeanHome);
      }
      objName.append(COMMA);
    }
    objName.append(MBeanKeys.TYPE).append(EQUALS).append(type);
    if (subsystem != Subsystem.None) {
      objName.append(COMMA).append(MBeanKeys.SUBSYSTEM).append(EQUALS).append(subsystem);
    }
    objName.append(COMMA).append(MBeanKeys.NAME).append(EQUALS).append(uiFriendlyName);
    return new ObjectName(objName.toString());
  }

  private static void addNodeInfo(StringBuffer objName, TCSocketAddress addr) {
    String remoteHost = addr.getAddress().getCanonicalHostName();
    int remotePort = addr.getPort();
    objName.append(COMMA).append(MBeanKeys.MBEAN_NODE).append(EQUALS).append(remoteHost).append(SLASH)
        .append(remotePort);
  }

  public static ObjectName addNodeInfo(ObjectName objName, TCSocketAddress addr) throws MalformedObjectNameException {
    if (objName.getKeyProperty(MBeanKeys.MBEAN_NODE) != null) { return objName; }
    StringBuffer sb = new StringBuffer(objName.getCanonicalName());
    if (objName.getKeyProperty(NODE_PREFIX_KEY) == null) {
      sb.append(COMMA).append(NODE_PREFIX);
    }
    addNodeInfo(sb, addr);
    return new ObjectName(sb.toString());
  }

  public abstract Object findMBean(final ObjectName objectName, final Class mBeanInterface) throws Exception;

  public static final Object findMBean(final ObjectName objectName, final Class mBeanInterface,
                                       MBeanServerConnection mBeanServer) throws IOException {
    final Set matchingBeans = mBeanServer.queryMBeans(objectName, null);
    final Iterator beanPos = matchingBeans.iterator();
    if (beanPos.hasNext()) { return MBeanServerInvocationHandler.newProxyInstance(mBeanServer, objectName,
                                                                                  mBeanInterface, false); }
    return null;
  }

  public static final QueryExp matchAllTerracottaMBeans() {
    try {
      return Query.or(new ObjectName(PUBLIC_DOMAIN + ":*"), new ObjectName(INTERNAL_DOMAIN + ":*"));
    } catch (MalformedObjectNameException e) {
      throw new RuntimeException(e);
    }
  }

  public static final String quoteIfNecessary(String objectNamePart) {
    if (objectNamePart.matches("[,=:*?\"']")) { return ObjectName.quote(objectNamePart); }
    return objectNamePart;
  }

  public static final Set getAllL1DumperMBeans(MBeanServerConnection mbs) throws MalformedObjectNameException,
      NullPointerException, IOException {
    return mbs.queryNames(new ObjectName(MBeanNames.L1DUMPER_INTERNAL.getCanonicalName() + ",*"), null);
  }
}
