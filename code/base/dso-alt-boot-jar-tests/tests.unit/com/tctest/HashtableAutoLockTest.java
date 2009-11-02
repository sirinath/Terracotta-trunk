/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import org.apache.xmlbeans.XmlObject;

import com.tc.config.schema.dynamic.BooleanConfigItem;
import com.tc.config.schema.dynamic.ConfigItem;
import com.tc.config.schema.dynamic.ConfigItemListener;
import com.tc.exception.ImplementMe;
import com.tc.object.PortabilityImpl;
import com.tc.object.TestClientObjectManager;
import com.tc.object.bytecode.Clearable;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.schema.DSORuntimeLoggingOptions;
import com.tc.object.config.schema.DSORuntimeOutputOptions;
import com.tc.object.loaders.IsolationClassLoader;
import com.tc.object.locks.MockClientLockManager;
import com.tc.object.tx.MockTransactionManager;
import com.tc.test.TCTestCase;
import com.tc.util.Assert;
import com.tc.util.runtime.Vm;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

public class HashtableAutoLockTest extends TCTestCase {
  private ClassLoader             origThreadContextClassLoader;
  private TestClientObjectManager testClientObjectManager;
  private MockTransactionManager  testTransactionManager;
  private MockClientLockManager   testClientLockManager;

  @Override
  protected void setUp() throws Exception {
    ClassLoader loader = getClass().getClassLoader();
    InvocationHandler handler = new InvocationHandler() {
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("getNewCommonL1Config".equals(name) || "getInstrumentationLoggingOptions".equals(name)
            || "instrumentationLoggingOptions".equals(name) || "getLogicalExtendingClassName".equals(name)
            || "createDsoClassAdapterFor".equals(name) || "getModulesForInitialization".equals(name)
            || "verifyBootJarContents".equals(name) || "validateSessionConfig".equals(name)) {
          return null;
        } else if ("shouldBeAdapted".equals(name)) {
          return Boolean.FALSE;
        } else if ("isNeverAdaptable".equals(name)) {
          return Boolean.TRUE;
        } else if ("isLogical".equals(name)) {
          return Boolean.TRUE;
        } else if ("getAppGroup".equals(name)) {
          return null;
        } else if ("hasBootJar".equals(name)) {
          return Boolean.TRUE;
        } else if ("getAspectModules".equals(name)) {
          return new HashMap();
        } else if ("getPortability".equals(name)) {
          return new PortabilityImpl((DSOClientConfigHelper) proxy);
        } else if ("runtimeLoggingOptions".equals(name)) {
          return new MockRuntimeOptions();
        } else if ("runtimeOutputOptions".equals(name)) {
          return new MockOutputOptions();
        } else if (Vm.isIBM() && "isRoot".equals(name)
                   && ("java.lang.reflect.Method".equals(args[0]) || "java.lang.reflect.Constructor".equals(args[0]))) {
          // the implementation of java.lang.Class in the IBM JDK is different and caches
          // fields of the Method and Constructor classes, which it retrieves afterwards by
          // calling the Field.get method. This gets into the AccessibleObject changes for
          // DSO, which checks if the returned value is a root
          return Boolean.FALSE;
        }

        throw new ImplementMe();
      }
    };
    Object proxy = Proxy.newProxyInstance(loader, new Class[] { DSOClientConfigHelper.class }, handler);

    testClientObjectManager = new TestClientObjectManager();
    testTransactionManager = new MockTransactionManager();
    testClientLockManager = new MockClientLockManager();
    IsolationClassLoader classLoader = new IsolationClassLoader((DSOClientConfigHelper) proxy, testClientObjectManager,
                                                                testTransactionManager, testClientLockManager);
    classLoader.init();

    this.origThreadContextClassLoader = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(classLoader);
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();

    Thread.currentThread().setContextClassLoader(this.origThreadContextClassLoader);
  }

  public void testClearReferences() throws Exception {
    testClientObjectManager.setIsManaged(true);
    Hashtable ht = (Hashtable) createMap("java.util.Hashtable");
    testClientObjectManager.lookupOrCreate(ht);
    ((Clearable) ht).__tc_clearReferences(100);
    System.err.println("# of begins: " + testTransactionManager.getBegins().size());
    Assert.assertEquals(0, testTransactionManager.getBegins().size());
  }

  private Map createMap(String className) throws ClassNotFoundException, SecurityException, NoSuchMethodException,
      IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
    Class c = Class.forName(className);
    Constructor constructor = c.getConstructor(new Class[0]);
    return (Map) constructor.newInstance(new Object[0]);
  }

  private static class BooleanItem implements BooleanConfigItem {

    public boolean getBoolean() {
      return false;
    }

    public void addListener(ConfigItemListener changeListener) {
      //
    }

    public Object getObject() {
      return null;
    }

    public void removeListener(ConfigItemListener changeListener) {
      //
    }

  }

  private static class MockRuntimeOptions implements DSORuntimeLoggingOptions {

    public BooleanConfigItem logDistributedMethodDebug() {
      return new BooleanItem();
    }

    public BooleanConfigItem logFieldChangeDebug() {
      return new BooleanItem();
    }

    public BooleanConfigItem logLockDebug() {
      return new BooleanItem();
    }

    public BooleanConfigItem logNamedLoaderDebug() {
      return new BooleanItem();
    }

    public BooleanConfigItem logNewObjectDebug() {
      return new BooleanItem();
    }

    public BooleanConfigItem logNonPortableDump() {
      return new BooleanItem();
    }

    public BooleanConfigItem logWaitNotifyDebug() {
      return new BooleanItem();
    }

    public void changesInItemForbidden(ConfigItem item) {
      //
    }

    public void changesInItemIgnored(ConfigItem item) {
      //
    }

    public XmlObject getBean() {
      return null;
    }

  }

  private static class MockOutputOptions implements DSORuntimeOutputOptions {

    public BooleanConfigItem doAutoLockDetails() {
      return new BooleanItem();
    }

    public BooleanConfigItem doCaller() {
      return new BooleanItem();
    }

    public BooleanConfigItem doFullStack() {
      return new BooleanItem();
    }

    public void changesInItemForbidden(ConfigItem item) {
      //
    }

    public void changesInItemIgnored(ConfigItem item) {
      //
    }

    public XmlObject getBean() {
      return null;
    }

  }
}
