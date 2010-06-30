/**
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema.setup;

import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

import com.tc.util.Assert;
import com.terracottatech.config.Application;
import com.terracottatech.config.BindPort;
import com.terracottatech.config.Client;
import com.terracottatech.config.Ha;
import com.terracottatech.config.HaMode;
import com.terracottatech.config.Members;
import com.terracottatech.config.MirrorGroup;
import com.terracottatech.config.MirrorGroups;
import com.terracottatech.config.Server;
import com.terracottatech.config.Servers;
import com.terracottatech.config.System;
import com.terracottatech.config.TcProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Holds on to a set of beans that can be modified; this is used by the
 * {@link com.tc.config.schema.setup.TestTVSConfigurationSetupManagerFactory}.
 */
public class TestConfigBeanSet {

  public static final String DEFAULT_HOST        = "localhost";
  public static final String DEFAULT_SERVER_NAME = "default";

  private final Client       rootClientBean;
  private final Servers      rootServersBean;
  private final System       rootSystemBean;
  private final TcProperties tcPropertiesBean;
  private final Map          rootApplicationBeans;

  public TestConfigBeanSet() {
    
    this.rootClientBean = Client.Factory.newInstance();
    this.tcPropertiesBean = TcProperties.Factory.newInstance();

    this.rootServersBean = Servers.Factory.newInstance();
    Server initialServer = this.rootServersBean.addNewServer();
    initialServer.setHost(DEFAULT_HOST);
    initialServer.setName(DEFAULT_SERVER_NAME);
    
    BindPort dsoPort = initialServer.addNewDsoPort();
    dsoPort.setIntValue(0);
    dsoPort.setBind("0.0.0.0");
    
    BindPort jmxPort = initialServer.addNewJmxPort();
    jmxPort.setIntValue(0);
    jmxPort.setBind("0.0.0.0");

    BindPort groupPort = initialServer.addNewL2GroupPort();
    groupPort.setIntValue(0);
    groupPort.setBind("0.0.0.0");
    
    Ha commonHa = this.rootServersBean.addNewHa();
    commonHa.setMode(HaMode.DISK_BASED_ACTIVE_PASSIVE);
    commonHa.addNewNetworkedActivePassive();
    MirrorGroups groups = this.rootServersBean.addNewMirrorGroups();
    MirrorGroup group = groups.addNewMirrorGroup();
    group.setHa(commonHa);
    Members members = group.addNewMembers();
    members.addMember(DEFAULT_SERVER_NAME);

    this.rootSystemBean = System.Factory.newInstance();

    this.rootApplicationBeans = new HashMap();
    this.rootApplicationBeans.put(TVSConfigurationSetupManagerFactory.DEFAULT_APPLICATION_NAME, createNewApplication());

    checkValidates(this.rootClientBean);
    checkValidates(this.rootServersBean);
    checkValidates(this.rootSystemBean);

    Iterator iter = this.rootApplicationBeans.values().iterator();
    while (iter.hasNext()) {
      checkValidates((XmlObject) iter.next());
    }
  }

  private void checkValidates(XmlObject object) {
    List errors = new ArrayList();
    XmlOptions options = new XmlOptions().setErrorListener(errors);

    boolean result = object.validate(options);
    if ((!result) || (errors.size() > 0)) {
      // formatting
      throw Assert
          .failure("Object " + object + " of " + object.getClass() + " didn't validate; errors were: " + errors);
    }
  }

  public Client clientBean() {
    return this.rootClientBean;
  }
  
  public TcProperties tcPropertiesBean() {
    return this.tcPropertiesBean;
  }


  public Servers serversBean() {
    return this.rootServersBean;
  }

  public System systemBean() {
    return this.rootSystemBean;
  }

  public String[] applicationNames() {
    return (String[]) this.rootApplicationBeans.keySet().toArray(new String[this.rootApplicationBeans.size()]);
  }

  public Application applicationBeanFor(String applicationName) {
    Application out = (Application) this.rootApplicationBeans.get(applicationName);
    if (out == null) {
      out = createNewApplication();
      this.rootApplicationBeans.put(applicationName, out);
    }
    return out;
  }

  private Application createNewApplication() {
    Application out = Application.Factory.newInstance();
    out.addNewDso().addNewInstrumentedClasses();
    return out;
  }

}
