/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.loaders;

import com.tc.aspectwerkz.transform.inlining.AsmHelper;
import com.tc.object.logging.RuntimeLogger;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Standard ClassProvider, using named classloaders and aware of boot, extension, and system classloaders.
 */
public class StandardClassProvider implements ClassProvider {

  private static final String BOOT    = Namespace.getStandardBootstrapLoaderName();
  private static final String EXT     = Namespace.getStandardExtensionsLoaderName();
  private static final String SYSTEM  = Namespace.getStandardSystemLoaderName();

  private final Map           loaders = new HashMap();
  private final RuntimeLogger runtimeLogger;

  public StandardClassProvider(RuntimeLogger runtimeLogger) {
    this.runtimeLogger = runtimeLogger;
  }

  public ClassLoader getClassLoader(String desc) {
    if (isStandardLoader(desc)) { return SystemLoaderHolder.loader; }

    ClassLoader rv = lookupLoader(desc);
    if (rv == null) { throw new AssertionError("No registered loader for description: " + desc); }
    return rv;
  }

  public Class getClassFor(final String className, String desc) throws ClassNotFoundException {
    final ClassLoader loader;

    if (isStandardLoader(desc)) {
      loader = SystemLoaderHolder.loader;
    } else {
      loader = lookupLoader(desc);
      if (loader == null) { throw new ClassNotFoundException("No registered loader for description: " + desc
                                                             + ", trying to load " + className); }
    }

    try {
      return Class.forName(className, false, loader);
    } catch (ClassNotFoundException e) {
      if (loader instanceof BytecodeProvider) {
        BytecodeProvider provider = (BytecodeProvider) loader;
        byte[] bytes = provider.__tc_getBytecodeForClass(className);
        if (bytes != null && bytes.length != 0) { return AsmHelper.defineClass(loader, bytes, className); }
      }
      throw e;
    }
  }

  public void registerNamedLoader(NamedClassLoader loader) {
    final String name = getName(loader);
    final WeakReference ref;

    synchronized (loaders) {
      ref = (WeakReference) loaders.put(name, new WeakReference(loader));
    }

    NamedClassLoader prev = ref == null ? null : (NamedClassLoader) ref.get();

    if (runtimeLogger.getNamedLoaderDebug()) {
      runtimeLogger.namedLoaderRegistered(loader, name, prev);
    }
  }

  private static String getName(NamedClassLoader loader) {
    String name = loader.__tc_getClassLoaderName();
    if (name == null || name.length() == 0) { throw new AssertionError("Invalid name [" + name + "] from loader "
                                                                       + loader); }
    return name;
  }

  public String getLoaderDescriptionFor(Class clazz) {
    return getLoaderDescriptionFor(clazz.getClassLoader());
  }

  public String getLoaderDescriptionFor(ClassLoader loader) {
    if (loader == null) { return BOOT; }
    if (loader instanceof NamedClassLoader) { return getName((NamedClassLoader) loader); }
    throw handleMissingLoader(loader);
  }

  private RuntimeException handleMissingLoader(ClassLoader loader) {
    if ("org.apache.jasper.servlet.JasperLoader".equals(loader.getClass().getName())) {
      // try to guve a better error message if you're trying to share a JSP
      return new RuntimeException("JSP instances (and inner classes there of) cannot be distributed, loader = "
                                  + loader);
    }
    return new RuntimeException("No loader description for " + loader);
  }

  private boolean isStandardLoader(String desc) {
    if (BOOT.equals(desc) || EXT.equals(desc) || SYSTEM.equals(desc)) { return true; }
    return false;
  }

  private ClassLoader lookupLoader(String desc) {
    final ClassLoader rv;
    synchronized (loaders) {
      WeakReference ref = (WeakReference) loaders.get(desc);
      if (ref != null) {
        rv = (ClassLoader) ref.get();
        if (rv == null) {
          loaders.remove(desc);
        }
      } else {
        rv = null;
      }
    }
    return rv;
  }

  public static class SystemLoaderHolder {
    final static ClassLoader loader = ClassLoader.getSystemClassLoader();
  }

}
