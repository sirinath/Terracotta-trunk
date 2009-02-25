/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.loaders;

import org.apache.commons.io.IOUtils;

import com.tc.asm.ClassReader;
import com.tc.asm.ClassVisitor;
import com.tc.asm.ClassWriter;
import com.tc.object.ClientObjectManager;
import com.tc.object.bytecode.Manager;
import com.tc.object.bytecode.ManagerImpl;
import com.tc.object.bytecode.hook.impl.ClassProcessorHelper;
import com.tc.object.bytecode.hook.impl.DSOContextImpl;
import com.tc.object.bytecode.hook.impl.PreparedComponentsFromL2Connection;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.tx.ClientTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

/**
 * DSO Class loader for internal testing. The main purpose of this loader is to force test classes to be be defined
 * specifically in this loader (and consequently within an isolated DSO context)
 */
public class IsolationClassLoader extends URLClassLoader implements NamedClassLoader {
  private static final ClassLoader    SYSTEM_LOADER = ClassLoader.getSystemClassLoader();

  private final Manager               manager;
  private final DSOClientConfigHelper config;
  private final Map                   onLoadErrors;
  private final Map                   adapters      = new HashMap();

  public IsolationClassLoader(DSOClientConfigHelper config, PreparedComponentsFromL2Connection connectionComponents) {
    this(config, true, null, null, connectionComponents);
  }

  public IsolationClassLoader(DSOClientConfigHelper config, ClientObjectManager objectManager,
                              ClientTransactionManager txManager) {
    this(config, false, objectManager, txManager, null);
  }

  private IsolationClassLoader(DSOClientConfigHelper config, boolean startClient, ClientObjectManager objectManager,
                               ClientTransactionManager txManager,
                               PreparedComponentsFromL2Connection connectionComponents) {
    super(getSystemURLS(), null);
    this.config = config;
    this.manager = createManager(startClient, objectManager, txManager, config, connectionComponents);
    this.onLoadErrors = new HashMap();
  }

  public void init() {
    manager.initForTests();
    ClassProcessorHelper.setContext(this, DSOContextImpl.createContext(config, manager));
  }

  private static URL[] getSystemURLS() {
    return ((URLClassLoader) SYSTEM_LOADER).getURLs();
  }

  private Manager createManager(boolean startClient, ClientObjectManager objectManager,
                                ClientTransactionManager txManager, DSOClientConfigHelper theConfig,
                                PreparedComponentsFromL2Connection connectionComponents) {
    Manager rv = new ManagerImpl(startClient, objectManager, txManager, theConfig, connectionComponents, false);
    rv.registerNamedLoader(this, null);
    return rv;
  }

  public void stop() {
    this.manager.stop();
  }

  public Class loadClass(String name) throws ClassNotFoundException {
    throwIfNeeded(name);

    Class c = findLoadedClass(name);
    if (c != null) { return c; }

    // "com.tc." classes are delegated to the system loader so that test classes can catch the same exception types as
    // the DSO runtime (which is in the system loader). "org.apache.commons.logging" classes are also delegated so that
    // the stupid checks in commons logging about multiple versions don't go off
    if (name.startsWith("com.tc.") || name.startsWith("org.apache.commons.logging.")) {
      return SYSTEM_LOADER.loadClass(name);
    } else {
      if (adapters.containsKey(name)) {
        System.out.println("***** using adapter!  name=[" + name + "]");
        return adaptClass(name);
      }

      return super.loadClass(name);
    }
  }

  private Class adaptClass(String name) throws ClassNotFoundException {
    byte[] orig = getBytes(name);

    ClassReader cr = new ClassReader(orig);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);

    Class adapterClass = (Class) adapters.get(name);

    try {
      Constructor cstr = adapterClass.getConstructor(new Class[] { ClassVisitor.class });
      cstr.setAccessible(true);
      ClassVisitor cv = (ClassVisitor) cstr.newInstance(new Object[] { cw });
      cr.accept(cv, ClassReader.SKIP_FRAMES);

      byte[] adapted = cw.toByteArray();

      return defineClass(name, adapted, 0, adapted.length);
    } catch (Exception e) {
      throw new ClassNotFoundException(name, e);
    }
  }

  private byte[] getBytes(String name) throws ClassNotFoundException {
    InputStream is = super.getResourceAsStream(name.replace('.', '/').concat(".class"));
    if (is == null) { throw new ClassNotFoundException(name); }

    try {
      return IOUtils.toByteArray(is);
    } catch (IOException ioe) {
      throw new ClassNotFoundException(name, ioe);
    } finally {
      IOUtils.closeQuietly(is);
    }
  }

  private void throwIfNeeded(String name) throws ClassNotFoundException {
    // throw exception if one is registered
    final String t = (String) onLoadErrors.get(name);
    if (t != null) {
      ClassNotFoundException rv = new ClassNotFoundException(t);
      throw rv;
    }
  }

  public String __tc_getClassLoaderName() {
    return loaderName();
  }

  public static String loaderName() {
    return Namespace.getIsolationLoaderName();
  }

  public void __tc_setClassLoaderName(String name) {
    throw new AssertionError();
  }

  /**
   * a ClassNotFoundException or NoClassDefFoundError with errorMessage will to be thrown referencing class className
   */
  public void throwOnLoad(String className, String errorMessage) {
    onLoadErrors.put(className, errorMessage);
  }

  protected Class findClass(String name) throws ClassNotFoundException {
    throwIfNeeded(name);
    return super.findClass(name);
  }

  public void addAdapter(String name, Class adapterClass) {
    Object prev = adapters.put(name, adapterClass);
    if (prev != null) { throw new AssertionError("adapter already exists for " + name); }
  }
}
