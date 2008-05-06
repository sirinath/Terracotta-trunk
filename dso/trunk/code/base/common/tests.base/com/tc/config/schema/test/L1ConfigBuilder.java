/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.config.schema.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Allows you to build valid config for the L1. This class <strong>MUST NOT</strong> invoke the actual XML beans to do
 * its work; one of its purposes is, in fact, to test that those beans are set up correctly.
 */
public class L1ConfigBuilder extends BaseConfigBuilder {

  private List modules = new ArrayList();
  private List repos = new ArrayList();

  public L1ConfigBuilder() {
    super(1, ALL_PROPERTIES);
  }

  public void setILClass(boolean use) {
    setProperty("class", use);
  }

  public void setILClass(String use) {
    setProperty("class", use);
  }

  public void setILHierarchy(boolean use) {
    setProperty("hierarchy", use);
  }

  public void setILHierarchy(String use) {
    setProperty("hierarchy", use);
  }

  public void setILLocks(boolean use) {
    setProperty("locks", use);
  }

  public void setILLocks(String use) {
    setProperty("locks", use);
  }

  public void setILTransientRoot(boolean use) {
    setProperty("transient-root", use);
  }

  public void setILTransientRoot(String use) {
    setProperty("transient-root", use);
  }

  public void setILRoots(boolean use) {
    setProperty("roots", use);
  }

  public void setILRoots(String use) {
    setProperty("roots", use);
  }

  public void setILDistributedMethods(boolean use) {
    setProperty("distributed-methods", use);
  }

  public void setILDistributedMethods(String use) {
    setProperty("distributed-methods", use);
  }

  public void setRLLockDebug(boolean use) {
    setProperty("lock-debug", use);
  }

  public void setRLLockDebug(String use) {
    setProperty("lock-debug", use);
  }

  public void setRLPartialInstrumentation(boolean use) {
    setProperty("partial-instrumentation", use);
  }

  public void setRLPartialInstrumentation(String use) {
    setProperty("partial-instrumentation", use);
  }

  public void setRLNonPortableWarning(boolean use) {
    setProperty("non-portable-warning", use);
  }

  public void setRLNonPortableWarning(String use) {
    setProperty("non-portable-warning", use);
  }

  public void setRLWaitNotifyDebug(boolean use) {
    setProperty("wait-notify-debug", use);
  }

  public void setRLWaitNotifyDebug(String use) {
    setProperty("wait-notify-debug", use);
  }

  public void setRLDistributedMethodDebug(boolean use) {
    setProperty("distributed-method-debug", use);
  }

  public void setRLDistributedMethodDebug(String use) {
    setProperty("distributed-method-debug", use);
  }

  public void setRLNewObjectDebug(boolean use) {
    setProperty("new-object-debug", use);
  }

  public void setRLNewObjectDebug(String use) {
    setProperty("new-object-debug", use);
  }

  public void setROOAutoLockDetails(boolean use) {
    setProperty("auto-lock-details", use);
  }

  public void setROOAutoLockDetails(String use) {
    setProperty("auto-lock-details", use);
  }

  public void setROOCaller(boolean use) {
    setProperty("caller", use);
  }

  public void setROOCaller(String use) {
    setProperty("caller", use);
  }

  public void setROOFullStack(boolean use) {
    setProperty("full-stack", use);
  }

  public void setROOFullStack(String use) {
    setProperty("full-stack", use);
  }

  public void setMaxInMemoryObjectCount(int value) {
    setProperty("max-in-memory-object-count", value);
  }

  public void setMaxInMemoryObjectCount(String value) {
    setProperty("max-in-memory-object-count", value);
  }

  public void setLogs(String value) {
    setProperty("logs", value);
  }

  public void addRepository(String location) {
    repos.add(location);
  }
  
  public void addModule(String name, String version) {
    addModule(name, "org.terracotta.modules", version);
  }
  
  public void addModule(String name, String groupId, String version) {
    modules.add(new Module(name, groupId, version));
  }

  private String addModuleElement() {
    StringBuffer moduleElement = new StringBuffer();

    if (modules.size() > 0 || repos.size() > 0) {
      moduleElement.append(openElement("modules"));

      for (Iterator it = repos.iterator(); it.hasNext();) {
        String loc = (String) it.next();
        moduleElement.append(openElement("repository"));
        moduleElement.append(loc);
        moduleElement.append(closeElement("repository"));
      }
  
      for (Iterator it = modules.iterator(); it.hasNext();) {
        Module m = (Module) it.next();
        moduleElement.append(selfCloseElement("module", m.asAttribute()));
      }
  
      moduleElement.append(closeElement("modules"));
    }
    
    return moduleElement.toString();
  }

  private static final String[] DSO_INSTRUMENTATION_LOGGING = new String[] { "class", "hierarchy", "locks",
      "transient-root", "roots", "distributed-methods"     };
  private static final String[] DSO_RUNTIME_LOGGING         = new String[] { "lock-debug", "partial-instrumentation",
      "non-portable-warning", "wait-notify-debug", "distributed-method-debug", "new-object-debug" };
  private static final String[] DSO_RUNTIME_OUTPUT_OPTIONS  = new String[] { "auto-lock-details", "caller",
      "full-stack"                                         };

  private static final String[] DSO_DEBUGGING               = concat(new Object[] { DSO_INSTRUMENTATION_LOGGING,
      DSO_RUNTIME_LOGGING, DSO_RUNTIME_OUTPUT_OPTIONS      });
  private static final String[] DSO                         = concat(new Object[] { "max-in-memory-object-count",
      DSO_DEBUGGING                                        });
  private static final String[] MODULE_ATTRIBUTES           = new String[] { "name", "group-id", "version" };
  private static final String[] ALL_PROPERTIES              = concat(new Object[] { "modules", "logs", DSO });

  public String toString() {
    return  addModuleElement()
        + element("logs") + openElement("dso", DSO) + element("max-in-memory-object-count")
        + openElement("debugging", DSO_DEBUGGING)
        + elementGroup("instrumentation-logging", DSO_INSTRUMENTATION_LOGGING)
        + elementGroup("runtime-logging", DSO_RUNTIME_LOGGING)
        + elementGroup("runtime-output-options", DSO_RUNTIME_OUTPUT_OPTIONS) + closeElement("debugging", DSO_DEBUGGING)
        + closeElement("dso", DSO);
  }

  public static L1ConfigBuilder newMinimalInstance() {
    return new L1ConfigBuilder();
  }

  private static class Module {
    private String name;
    private String groupId;
    private String version;

    public Module(String name, String groupId, String version) {
      this.name = name;
      this.groupId = groupId;
      this.version = version;
    }

    public Map asAttribute() {
      Map attr = new HashMap();
      attr.put(MODULE_ATTRIBUTES[0], name);
      attr.put(MODULE_ATTRIBUTES[1], groupId);
      attr.put(MODULE_ATTRIBUTES[2], version);
      return attr;
    }
  }

  public static void main(String[] args) {
    L1ConfigBuilder builder = new L1ConfigBuilder();
    System.err.println(builder);

    builder.setROOCaller(true);
    builder.setROOFullStack(false);
    builder.setLogs("funk");
    builder.addModule("testmo", "org.mycompany.modules", "1.2");
    System.err.println(builder);
  }

}
