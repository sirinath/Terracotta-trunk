/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema.test;

/**
 * Allows you to build valid config for an L2. This class <strong>MUST NOT</strong> invoke the actual XML beans to do
 * its work; one of its purposes is, in fact, to test that those beans are set up correctly.
 */
public class L2ConfigBuilder extends BaseConfigBuilder {

  private String name;
  private String host;

  public L2ConfigBuilder() {
    super(3, ALL_PROPERTIES);
  }

  public void setName(String name) {
    this.name = name;
  }

  String getName() {
    return this.name;
  }

  public void setHost(String host) {
    this.host = host;
  }

  String getHost() {
    return this.host;
  }

  public void setData(String data) {
    setProperty("data", data);
  }

  public void setLogs(String logs) {
    setProperty("logs", logs);
  }

  public void setDSOPort(int data) {
    setProperty("dso-port", data);
  }

  public void setJMXPort(int data) {
    setProperty("jmx-port", data);
  }

  public void setJMXPort(String data) {
    setProperty("jmx-port", data);
  }

  public void setL2GroupPort(int data) {
    setProperty("l2-group-port", data);
  }

  public void setPasswordFile(String data) {
    setProperty("password-file", data);
  }

  public void setAccessFile(String data) {
    setProperty("access-file", data);
  }

  public static final String PERSISTENCE_MODE_TEMPORARY_SWAP_ONLY = "temporary-swap-only";
  public static final String PERSISTENCE_MODE_PERMANENT_STORE     = "permanent-store";

  public void setPersistenceMode(String data) {
    setProperty("mode", data);
  }

  public void setGCEnabled(boolean data) {
    setProperty("enabled", data);
  }

  public void setGCEnabled(String data) {
    setProperty("enabled", data);
  }

  public void setGCVerbose(boolean data) {
    setProperty("verbose", data);
  }

  public void setGCVerbose(String data) {
    setProperty("verbose", data);
  }

  public void setGCInterval(int data) {
    setProperty("interval", data);
  }

  public void setGCInterval(String data) {
    setProperty("interval", data);
  }

  private static final String[] L2              = new String[] { "data", "logs", "dso-port", "jmx-port",
      "l2-group-port"                          };

  private static final String[] DSO_PERSISTENCE = new String[] { "mode" };
  private static final String[] DSO_GC          = new String[] { "enabled", "verbose", "interval" };
  private static final String[] AUTHENTICATION  = new String[] { "password-file", "access-file" };
  private static final String[] DSO             = concat(new Object[] { DSO_PERSISTENCE, DSO_GC });

  private static final String[] ALL_PROPERTIES  = concat(new Object[] { L2, AUTHENTICATION, DSO });

  public String toString() {
    String out = "";

    out += indent() + "<server" + (this.name != null ? " name=\"" + this.name + "\"" : "")
           + (this.host != null ? " host=\"" + this.host + "\"" : "") + ">\n";

    out += elements(L2) + elementGroup("authentication", AUTHENTICATION) + openElement("dso", DSO)
        + elementGroup("persistence", DSO_PERSISTENCE) + elementGroup("garbage-collection", DSO_GC)
        + closeElement("dso", DSO);

    out += closeElement("server");

    return out;
  }

  public static L2ConfigBuilder newMinimalInstance() {
    return new L2ConfigBuilder();
  }

}
