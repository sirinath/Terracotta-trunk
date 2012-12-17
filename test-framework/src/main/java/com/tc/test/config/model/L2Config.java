package com.tc.test.config.model;

import java.util.ArrayList;

/**
 * The config for each L2 <br>
 * Default: <br>
 * dgc enabled: false <br>
 * dgc interval : 3600 sec <br>
 * off heap enabled : false <br>
 * max off heap data size: 128M <br>
 * persistence : temporary swap <br>
 * client reconnect window : 25 secs
 * 
 * @author rsingh
 */
public class L2Config {

  private boolean                 dgcEnabled            = false;
  private int                     dgcIntervalInSec      = 3600;
  private boolean                 offHeapEnabled        = false;
  private boolean                 restartable           = false;
  private int                     clientReconnectWindow = 15;
  private int                     maxOffHeapDataSize    = 128;
  private final ArrayList<String> extraServerJvmArgs;
  private boolean                 isProxyL2groupPorts   = false;
  private boolean                 isProxyDsoPorts       = false;
  private int                     minHeap               = 256;
  private int                     maxHeap               = 256;
  private int                     directMemorySize      = -1;
  private int                     proxyWaitTime         = 20 * 1000;
  private int                     proxyDownTime         = 100;
  private final BytemanConfig     bytemanConfig         = new BytemanConfig();
  private boolean                 autoOffHeapEnable     = true;

  /**
   * Creates a l2 config with these defaults <br>
   * dgc enabled: false <br>
   * dgc interval : 3600 sec <br>
   * off heap enabled : false <br>
   * max off heap data size: 128M <br>
   * persistence : temporary swap <br>
   * client reconnect window : 120 secs
   */
  public L2Config() {
    extraServerJvmArgs = new ArrayList<String>();
  }

  /**
   * Is DGC enabled
   * 
   * @return true if dgc is enabled
   */
  public boolean isDgcEnabled() {
    return dgcEnabled;
  }

  /**
   * enable/disable dgc
   * 
   * @param dgcEnabled true if dgc to be enabled. false otherwise
   */
  public void setDgcEnabled(boolean dgcEnabled) {
    this.dgcEnabled = dgcEnabled;
  }

  /**
   * @return dgc interveal in seconds
   */
  public int getDgcIntervalInSec() {
    return dgcIntervalInSec;
  }

  /**
   * sets the dgc interval in seconds
   */
  public void setDgcIntervalInSec(int dgcIntervalInSec) {
    this.dgcIntervalInSec = dgcIntervalInSec;
  }

  /**
   * Is off heap enabled
   * 
   * @return : true if off heap is enabled
   */
  public boolean isOffHeapEnabled() {
    return offHeapEnabled;
  }

  /**
   * Enabled/Disable off heap
   * 
   * @param offHeapEnabled : true if the off heap is to be enabled, false otherwise
   */
  public void setOffHeapEnabled(boolean offHeapEnabled) {
    this.offHeapEnabled = offHeapEnabled;
  }

  /**
   * Persistence mode for the L2
   */
  public boolean getRestartable() {
    return restartable;
  }

  /**
   * Sets whether the L2 should be restartable
   * 
   * @param restartable true to enable restartable
   */
  public void setRestartable(boolean restartable) {
    this.restartable = restartable;
  }

  /**
   * client reconnect window in secs
   */
  public int getClientReconnectWindow() {
    return clientReconnectWindow;
  }

  /**
   * sets client reconnect window in seconds
   */
  public void setClientReconnectWindow(int clientReconnectWindow) {
    this.clientReconnectWindow = clientReconnectWindow;
  }

  /**
   * max off heap data size in MBs
   * 
   * @return
   */
  public int getMaxOffHeapDataSize() {
    return maxOffHeapDataSize;
  }

  /**
   * Sets max off heap data size
   * 
   * @param maxOffHeapDataSize offheap data size in MB
   */
  public void setMaxOffHeapDataSize(int maxOffHeapDataSize) {
    this.maxOffHeapDataSize = maxOffHeapDataSize;
  }

  /**
   * @return List of jvm arguments for each server
   */
  public ArrayList<String> getExtraServerJvmArgs() {
    return extraServerJvmArgs;
  }

  /**
   * Adds a jvm argumnet for each server
   * 
   * @param arg jvm argument
   */
  public void addExtraServerJvmArg(String arg) {
    extraServerJvmArgs.add(arg);
  }

  /**
   * @return true if proxy is enabled between two mirror groups communication
   */
  public boolean isProxyL2groupPorts() {
    return isProxyL2groupPorts;
  }

  /**
   * Enable/Disable l2 group proxy between two mirror groups
   * 
   * @param isProxyL2groupPorts
   */
  public void setProxyL2groupPorts(boolean isProxyL2groupPorts) {
    this.isProxyL2groupPorts = isProxyL2groupPorts;
  }

  /**
   * is L2 started with a proxy port in bertween the server and client
   * 
   * @return
   */
  public boolean isProxyDsoPorts() {
    return isProxyDsoPorts;
  }

  /**
   * Enable/Disable l2 proxy for dso port
   * 
   * @param isProxyDsoPorts
   */
  public void setProxyDsoPorts(boolean isProxyDsoPorts) {
    this.isProxyDsoPorts = isProxyDsoPorts;
  }

  /**
   * Get the -Xms size to pass to L2s
   * 
   * @return Minimum heap size
   */
  public int getMinHeap() {
    return minHeap;
  }

  /**
   * Set the min heap size
   * 
   * @param minHeap minimum heap size
   */
  public void setMinHeap(int minHeap) {
    this.minHeap = minHeap;
  }

  /**
   * Get the -Xmx size to pass to L2s
   * 
   * @return Maximum heap size
   */
  public int getMaxHeap() {
    return maxHeap;
  }

  /**
   * Set the max heap size
   * 
   * @param maxHeap maximum heap size
   */
  public void setMaxHeap(int maxHeap) {
    this.maxHeap = maxHeap;
  }

  /**
   * Gets the "-XX:MaxDirectMemorySize" to pass to the server
   * 
   * @return -XX:MaxDirectMemorySize
   */
  public int getDirectMemorySize() {
    return directMemorySize;
  }

  /**
   * Sets "-XX:MaxDirectMemorySize"
   * 
   * @param -XX:MaxDirectMemorySize in MB
   */
  public void setDirectMemorySize(int directMemorySize) {
    this.directMemorySize = directMemorySize;
  }

  public boolean isAutoOffHeapEnable() {
    return autoOffHeapEnable;
  }

  public void setAutoOffHeapEnable(boolean autoOffHeapEnable) {
    this.autoOffHeapEnable = autoOffHeapEnable;
  }

  public int getProxyWaitTime() {
    return proxyWaitTime;
  }

  public int getProxyDownTime() {
    return proxyDownTime;
  }

  public void setProxyWaitTime(int proxyWaitTime) {
    this.proxyWaitTime = proxyWaitTime;
  }

  public void setProxyDownTime(int proxyDownTime) {
    this.proxyDownTime = proxyDownTime;
  }

  public BytemanConfig getBytemanConfig() {
    return bytemanConfig;
  }
}
