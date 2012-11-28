package com.tc.config.test.schema;

import org.apache.commons.io.IOUtils;

import com.tc.test.config.model.GroupConfig;
import com.tc.test.config.model.L2Config;
import com.tc.test.config.model.TestConfig;
import com.tc.test.setup.GroupsData;
import com.tc.util.PortChooser;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Map.Entry;

import junit.framework.Assert;

public class ConfigHelper {
  public static final String  HOST        = "localhost";
  private static final String SERVER_NAME = "testserver";
  private static final String GROUP_NAME  = "testGroup";

  private final PortChooser   portChooser;
  private final TestConfig    testConfig;
  private final int           numOfGroups;
  private final int           numOfServersPerGroup;
  private final File          tcConfigFile;
  private final File          tempDir;
  private final GroupsData[]  groupData;

  public ConfigHelper(final PortChooser portChooser, final TestConfig testConfig, final File tcConfigFile, File tempDir) {
    this.portChooser = portChooser;
    this.testConfig = testConfig;
    numOfGroups = testConfig.getNumOfGroups();
    numOfServersPerGroup = testConfig.getGroupConfig().getMemberCount();
    groupData = new GroupsData[numOfGroups];
    this.tcConfigFile = tcConfigFile;
    this.tempDir = tempDir;
    setServerPorts();
  }

  /**
   * If the test Wants manually specify ports or any other parameters in group data. This constructor can be used to
   * pass group data.
   */
  public ConfigHelper(GroupsData[] groupData, final TestConfig testConfig, final File tcConfigFile, File tempDir) {
    this.groupData = groupData;
    this.testConfig = testConfig;
    numOfGroups = testConfig.getNumOfGroups();
    numOfServersPerGroup = testConfig.getGroupConfig().getMemberCount();
    this.tcConfigFile = tcConfigFile;
    this.tempDir = tempDir;
    portChooser = new PortChooser();
  }

  public synchronized void writeConfigFile() {
    try {
      TerracottaConfigBuilder builder = createConfig();
      // System.out.println("******** Writing tc-config file");
      // System.out.println(builder.toString());
      FileOutputStream out = new FileOutputStream(tcConfigFile);
      IOUtils.write(builder.toString(), out);
      out.close();
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail("Can't create config file " + tcConfigFile.getAbsolutePath() + e.getMessage());
    }
  }

  private TerracottaConfigBuilder createConfig() {
    TerracottaConfigBuilder tcConfigBuilder = new TerracottaConfigBuilder();

    // set tc properties
    tcConfigBuilder.setTcProperties(getTcProperties());

    // set servers
    tcConfigBuilder.setServers(getL2sConfig());

    return tcConfigBuilder;
  }

  private TcPropertiesBuilder getTcProperties() {
    // Build tc properties
    Map<String, String> tcPropertiesMap = testConfig.getTcPropertiesMap();
    TcPropertyBuilder[] tcPropertyBuilders = new TcPropertyBuilder[tcPropertiesMap.size()];
    int index = 0;
    for (Entry<String, String> tcProperty : tcPropertiesMap.entrySet()) {
      tcPropertyBuilders[index++] = new TcPropertyBuilder(tcProperty.getKey(), tcProperty.getValue());
    }
    TcPropertiesBuilder tcPropertiesBuilder = new TcPropertiesBuilder();
    tcPropertiesBuilder.setTcProperties(tcPropertyBuilders);
    return tcPropertiesBuilder;
  }

  private L2SConfigBuilder getL2sConfig() {
    L2ConfigBuilder[] l2s = new L2ConfigBuilder[numOfGroups * numOfServersPerGroup];
    GroupsConfigBuilder groups = new GroupsConfigBuilder();
    int i = 0;
    for (int groupIndex = 0; groupIndex < numOfGroups; groupIndex++) {

      L2ConfigBuilder[] l2Builders = getServersForGroup(groupIndex);

      for (L2ConfigBuilder l2 : l2Builders) {
        l2s[i] = l2;
        i++;
      }

      MembersConfigBuilder members = new MembersConfigBuilder();
      for (L2ConfigBuilder l2 : l2Builders) {
        members.addMember(l2.getName());
      }
      GroupConfigBuilder group = new GroupConfigBuilder(getGroupName(groupIndex));
      group.setMembers(members);
      groups.addGroupConfigBuilder(group);
    }

    L2SConfigBuilder l2sConfig = new L2SConfigBuilder();
    l2sConfig.setL2s(l2s);
    l2sConfig.setGroups(groups);

    HaConfigBuilder ha = new HaConfigBuilder(3);
    GroupConfig gc = testConfig.getGroupConfig();
    ha.setMode(HaConfigBuilder.HA_MODE_NETWORKED_ACTIVE_PASSIVE);
    ha.setElectionTime(String.valueOf(gc.getElectionTime()));
    l2sConfig.setHa(ha);

    return l2sConfig;
  }

  private L2ConfigBuilder[] getServersForGroup(int groupIndex) {
    L2ConfigBuilder[] l2ConfigBuilders = new L2ConfigBuilder[this.numOfServersPerGroup];
    for (int serverIndex = 0; serverIndex < l2ConfigBuilders.length; serverIndex++) {
      l2ConfigBuilders[serverIndex] = new L2ConfigBuilder();
      // set host and name
      l2ConfigBuilders[serverIndex].setHost(HOST);
      l2ConfigBuilders[serverIndex].setName(getServerName(groupIndex, serverIndex));

      // set ports
      l2ConfigBuilders[serverIndex].setDSOPort(getDsoPort(groupIndex, serverIndex));
      l2ConfigBuilders[serverIndex].setJMXPort(getJmxPort(groupIndex, serverIndex));
      l2ConfigBuilders[serverIndex].setL2GroupPort(getL2GroupPort(groupIndex, serverIndex));

      // set logs
      l2ConfigBuilders[serverIndex].setLogs(getLogDirectoryPath(groupIndex, serverIndex));
      l2ConfigBuilders[serverIndex].setData(getDataDirectoryPath(groupIndex, serverIndex));
      l2ConfigBuilders[serverIndex].setServerDbBackup(getBackupDirectoryPath(groupIndex, serverIndex));
      l2ConfigBuilders[serverIndex].setStatistics(getStatisticsDirectoryPath(groupIndex, serverIndex));

      // set test level things
      L2Config l2Config = testConfig.getL2Config();
      // set client reconnect window
      l2ConfigBuilders[serverIndex].setReconnectWindowForPrevConnectedClients(l2Config.getClientReconnectWindow());

      // set persistence
      l2ConfigBuilders[serverIndex].setRestartable(l2Config.getRestartable());

      // set DGC props
      l2ConfigBuilders[serverIndex].setGCEnabled(l2Config.isDgcEnabled());
      l2ConfigBuilders[serverIndex].setGCInterval(l2Config.getDgcIntervalInSec());

      // set offheap props
      l2ConfigBuilders[serverIndex].setOffHeapEnabled(l2Config.isOffHeapEnabled());
      l2ConfigBuilders[serverIndex].setOffHeapMaxDataSize(l2Config.getMaxOffHeapDataSize() + "m");
    }

    return l2ConfigBuilders;

  }

  private void setServerPorts() {
    for (int groupIndex = 0; groupIndex < numOfGroups; groupIndex++) {
      String groupName = getGroupName(groupIndex);
      int[] dsoPorts = new int[numOfServersPerGroup];
      int[] jmxPorts = new int[numOfServersPerGroup];
      int[] l2GroupPorts = new int[numOfServersPerGroup];
      String[] serverNames = new String[numOfServersPerGroup];
      String[] dataDirectoryPath = new String[numOfServersPerGroup];
      String[] logDirectoryPath = new String[numOfServersPerGroup];
      String[] backupDirectoryPath = new String[numOfServersPerGroup];
      int[] proxyL2GroupPorts = null;
      int[] proxyDsoPorts = null;
      for (int serverIndex = 0; serverIndex < numOfServersPerGroup; serverIndex++) {
        final int portNum = portChooser.chooseRandomPorts(3);
        jmxPorts[serverIndex] = portNum;
        dsoPorts[serverIndex] = portNum + 1;
        l2GroupPorts[serverIndex] = portNum + 2;
        String serverName = SERVER_NAME + (groupIndex * numOfServersPerGroup + serverIndex);
        serverNames[serverIndex] = serverName;
        backupDirectoryPath[serverIndex] = new File(tempDir, serverName + File.separator + "backup").getAbsolutePath();
        dataDirectoryPath[serverIndex] = new File(tempDir, serverName + File.separator + "data").getAbsolutePath();
        logDirectoryPath[serverIndex] = new File(tempDir, serverName).getAbsolutePath();
      }
      if (isProxyDsoPort()) {
        proxyDsoPorts = new int[numOfServersPerGroup];
        for (int serverIndex = 0; serverIndex < numOfServersPerGroup; serverIndex++) {
          proxyDsoPorts[serverIndex] = portChooser.chooseRandomPort();
        }
      }
      if (isProxyL2GroupPort()) {
        proxyL2GroupPorts = new int[numOfServersPerGroup];
        for (int serverIndex = 0; serverIndex < numOfServersPerGroup; serverIndex++) {
          proxyL2GroupPorts[serverIndex] = portChooser.chooseRandomPort();
        }
      }

      groupData[groupIndex] = new GroupsData(groupName, dsoPorts, jmxPorts, l2GroupPorts, serverNames, proxyDsoPorts,
                                             proxyL2GroupPorts, dataDirectoryPath, logDirectoryPath, backupDirectoryPath);
    }
  }

  public File getTcConfigFile() {
    return this.tcConfigFile;
  }

  public int getDsoPort(final int groupIndex, final int serverIndex) {
    validateIndexes(groupIndex, serverIndex);

    return groupData[groupIndex].getDsoPort(serverIndex);

  }

  private void validateIndexes(final int groupIndex, final int serverIndex) {
    Assert.assertTrue("groupIndex: " + groupIndex + " numOfGroups: " + this.numOfGroups,
                      groupIndex >= 0 && groupIndex < this.numOfGroups);
    Assert.assertTrue("serverIndex: " + serverIndex + " serverIndex: " + this.numOfServersPerGroup,
                      serverIndex >= 0 && serverIndex < this.numOfServersPerGroup);
  }

  public int getJmxPort(final int groupIndex, final int serverIndex) {
    validateIndexes(groupIndex, serverIndex);

    return groupData[groupIndex].getJmxPort(serverIndex);

  }

  public int getL2GroupPort(final int groupIndex, final int serverIndex) {
    validateIndexes(groupIndex, serverIndex);

    return groupData[groupIndex].getL2GroupPort(serverIndex);

  }

  public String getGroupName(final int groupIndex) {
    return GROUP_NAME + groupIndex;
  }

  public String getServerName(final int groupIndex, final int serverIndex) {
    return groupData[groupIndex].getServerNames()[serverIndex];
  }

  public GroupsData getGroupData(final int groupIndex) {
    return groupData[groupIndex];
  }

  private boolean isProxyL2GroupPort() {
    return testConfig.getL2Config().isProxyL2groupPorts();
  }

  private boolean isProxyDsoPort() {
    return testConfig.getL2Config().isProxyDsoPorts();
  }

  protected String getDataDirectoryPath(final int groupIndex, final int serverIndex) {
    return groupData[groupIndex].getDataDirectoryPath(serverIndex);
  }

  protected String getLogDirectoryPath(final int groupIndex, final int serverIndex) {
    return groupData[groupIndex].getLogDirectoryPath(serverIndex);
  }

  protected String getBackupDirectoryPath(final int groupIndex, final int serverIndex) {
    return groupData[groupIndex].getBackupDirectoryPath(serverIndex);
  }

  private String getStatisticsDirectoryPath(int groupIndex, int serverIndex) {
    return new File(tempDir, getServerName(groupIndex, serverIndex) + File.separator + "statistics").getAbsolutePath();
  }

}
