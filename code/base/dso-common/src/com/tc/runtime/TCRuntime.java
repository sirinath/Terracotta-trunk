/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.runtime;

import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesImpl;
import com.tc.properties.TCPropertiesConsts;
import com.tc.util.Assert;
import com.tc.util.runtime.Vm;

import java.lang.reflect.Constructor;

public class TCRuntime {

  private static JVMMemoryManager memoryManager;

  static {
    init();
  }

  public static final JVMMemoryManager getJVMMemoryManager() {
    Assert.assertNotNull(memoryManager);
    return memoryManager;
  }

  private static void init() {
    TCProperties props = TCPropertiesImpl.getProperties();

    if (Vm.isJDK15Compliant()) {
      if (props.getBoolean(TCPropertiesConsts.MEMORY_MONITOR_FORCEBASIC)) {
        memoryManager = getMemoryManagerJdk15Basic();
      } else {
        memoryManager = getMemoryManagerJdk15PoolMonitor();
      }
    } else {
      memoryManager = new TCMemoryManagerJdk14();
    }
  }

  private static JVMMemoryManager getMemoryManagerJdk15PoolMonitor() {
    return getMemoryManagerJdk15("com.tc.runtime.TCMemoryManagerJdk15PoolMonitor");
  }

  private static JVMMemoryManager getMemoryManagerJdk15Basic() {
    return getMemoryManagerJdk15("com.tc.runtime.TCMemoryManagerJdk15Basic");
  }

  /*
   * XXX::This method is intentionally written using Reflection so that we dont have any 1.5 dependences.
   * TODO:: Figure a better way to do this.
   */
  private static JVMMemoryManager getMemoryManagerJdk15(String className) {
    try {
      Class c =  Class.forName(className);
      Constructor constructor = c.getConstructor(new Class[0]);
      return (JVMMemoryManager) constructor.newInstance(new Object[0]);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
