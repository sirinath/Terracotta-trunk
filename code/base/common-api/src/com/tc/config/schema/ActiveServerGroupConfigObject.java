/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema;

import org.apache.xmlbeans.XmlObject;

import com.tc.config.schema.context.ConfigContext;
import com.tc.config.schema.defaults.DefaultValueProvider;
import com.tc.config.schema.repository.ChildBeanFetcher;
import com.tc.config.schema.repository.ChildBeanRepository;
import com.tc.config.schema.repository.MutableBeanRepository;
import com.tc.config.schema.setup.ConfigurationSetupException;
import com.tc.config.schema.setup.StandardL2TVSConfigurationSetupManager;
import com.tc.net.GroupID;
import com.terracottatech.config.Ha;
import com.terracottatech.config.Members;
import com.terracottatech.config.MirrorGroup;
import com.terracottatech.config.Server;
import com.terracottatech.config.Servers;

public class ActiveServerGroupConfigObject extends BaseNewConfigObject implements ActiveServerGroupConfig {

  // TODO: the defaultValueProvider is not implemented to fetch default values
  // for attributes... possibly fix this and
  // use the commented code to set defaultId:
  // int defaultId = ((XmlInteger)
  // defaultValueProvider.defaultFor(serversBeanRepository.rootBeanSchemaType(),
  // "active-server-groups/active-server-group[@id]")).getBigIntegerValue().intValue();
  public static final int     defaultGroupId = 0;

  private GroupID             groupId;
  private String              grpName;
  private final NewHaConfig   haConfig;
  private final MembersConfig membersConfig;

  public ActiveServerGroupConfigObject(ConfigContext context, StandardL2TVSConfigurationSetupManager setupManager) {
    super(context);
    context.ensureRepositoryProvides(MirrorGroup.class);
    MirrorGroup group = (MirrorGroup) context.bean();

    String groupName = group.getGroupName();
    this.grpName = groupName;

    membersConfig = new MembersConfigObject(createContext(setupManager, true, group));
    haConfig = new NewHaConfigObject(createContext(setupManager, false, group));
  }

  public void setGroupId(GroupID groupId) {
    this.groupId = groupId;
  }

  public NewHaConfig getHa() {
    return this.haConfig;
  }
  
  public void setGroupName(String groupName) {
    this.grpName = groupName;
  }

  public String getGroupName() {
    return grpName;
  }

  public MembersConfig getMembers() {
    return this.membersConfig;
  }

  public GroupID getGroupId() {
    return this.groupId;
  }

  private final ConfigContext createContext(StandardL2TVSConfigurationSetupManager setupManager, boolean isMembers,
                                            final MirrorGroup group) {
    if (isMembers) {
      ChildBeanRepository beanRepository = new ChildBeanRepository(setupManager.serversBeanRepository(), Members.class,
                                                                   new ChildBeanFetcher() {
                                                                     public XmlObject getChild(XmlObject parent) {
                                                                       return group.getMembers();
                                                                     }
                                                                   });
      return setupManager.createContext(beanRepository, setupManager.getConfigFilePath());
    } else {
      ChildBeanRepository beanRepository = new ChildBeanRepository(setupManager.serversBeanRepository(), Ha.class,
                                                                   new ChildBeanFetcher() {
                                                                     public XmlObject getChild(XmlObject parent) {
                                                                       return group.getHa();
                                                                     }
                                                                   });
      return setupManager.createContext(beanRepository, setupManager.getConfigFilePath());
    }
  }

  public static MirrorGroup getDefaultActiveServerGroup(DefaultValueProvider defaultValueProvider,
                                                        MutableBeanRepository serversBeanRepository, Ha commonHa)
      throws ConfigurationSetupException {
    MirrorGroup asg = MirrorGroup.Factory.newInstance();
    asg.setHa(commonHa);
    Members members = asg.addNewMembers();
    Server[] serverArray = ((Servers) serversBeanRepository.bean()).getServerArray();

    for (int i = 0; i < serverArray.length; i++) {
      // name for each server should exist
      String name = serverArray[i].getName();
      if (name == null || name.equals("")) { throw new ConfigurationSetupException(
                                                                                   "server's name not defined... name=["
                                                                                       + name + "] serverDsoPort=["
                                                                                       + serverArray[i].getDsoPort()
                                                                                       + "]"); }
      members.insertMember(i, serverArray[i].getName());
    }

    return asg;
  }

  public boolean isMember(String l2Name) {
    String[] members = getMembers().getMemberArray();
    for (int i = 0; i < members.length; i++) {
      if (members[i].equals(l2Name)) { return true; }
    }
    return false;
  }
}
