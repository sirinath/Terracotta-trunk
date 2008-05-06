/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.jboss;

import com.tc.object.bytecode.hook.impl.ClassProcessorHelper;
import com.tc.object.loaders.NamedClassLoader;
import com.tc.object.loaders.Namespace;
import com.tc.util.runtime.Os;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;

public class JBossLoaderNaming {

  private static final String UCL           = "org.jboss.mx.loading.UnifiedClassLoader";
  private static final String UCL3          = "org.jboss.mx.loading.UnifiedClassLoader3";
  private static final String CACHE_LOADER  = "org.jboss.mx.loading.HeirarchicalLoaderRepository3$CacheClassLoader";

  private static String       serverHomeDir = null;
  private static String       serverBaseDir = null;
  private static String       serverTempDir = null;

  private static boolean      initialized   = false;

  public static synchronized void initialize(ClassLoader bootLoader, File homeDir, File baseDir, File tempDir)
      throws Exception {
    if (initialized) { throw new IllegalStateException("already initialized"); }
    initialized = true;

    NamedClassLoader ncl = (NamedClassLoader) bootLoader;
    ncl.__tc_setClassLoaderName(Namespace.createLoaderName(Namespace.JBOSS_NAMESPACE, "boot"));
    ClassProcessorHelper.registerGlobalLoader(ncl);

    serverHomeDir = fixUpUrl(homeDir.getAbsoluteFile().toURL()).toExternalForm();
    serverBaseDir = fixUpUrl(baseDir.getAbsoluteFile().toURL()).toExternalForm();
    serverTempDir = fixUpUrl(tempDir.getAbsoluteFile().toURL()).toExternalForm();

  }

  private static URL fixUpUrl(URL url) throws MalformedURLException {
    if (Os.isWindows()) {
      // make sure the drive letter is always uppercase
      String file = url.getFile();

      if (file.matches("^/[A-Za-z]:/.+$")) {
        char drive = Character.toUpperCase(file.charAt(1));
        file = "/" + drive + file.substring(2);
      }

      return new URL(url.getProtocol(), url.getHost(), file);
    }

    return url;
  }

  public synchronized static String getLoaderName(ClassLoader loader) throws Exception {
    if (loader == null) { throw new NullPointerException("null loader"); }
    if (!initialized) { throw new IllegalStateException("not yet initialized"); }

    Class type = loader.getClass();
    String className = type.getName();

    if (UCL3.equals(className)) {
      return makeUCLName(loader, true);
    } else if (UCL.equals(className)) { return makeUCLName(loader, false); }

    if (CACHE_LOADER.equals(className)) { return null; }

    throw new UnsupportedOperationException("Support missing for loader of type: " + className);
  }

  private static String makeUCLName(ClassLoader loader, boolean methodIsOnSuper) throws Exception {
    final Class clazz = loader.getClass();

    Class lookup = clazz;
    if (methodIsOnSuper) {
      lookup = clazz.getSuperclass();
    }

    Method getUrl = lookup.getDeclaredMethod("getURL", new Class[] {});
    URL url = (URL) getUrl.invoke(loader, new Object[] {});

    Method getOrigUrl = lookup.getDeclaredMethod("getOrigURL", new Class[] {});
    URL origUrl = (URL) getOrigUrl.invoke(loader, new Object[] {});

    if (url == null && origUrl == null) { return null; }

    final URL u;
    if ((url == null) || url.toExternalForm().startsWith(serverTempDir)) {
      u = origUrl;
    } else {
      u = url;
    }

    String urlString = fixUpUrl(u).toExternalForm();

    if (urlString.startsWith(serverHomeDir)) {
      urlString = urlString.substring(serverHomeDir.length());
    } else if (urlString.startsWith(serverBaseDir)) {
      urlString = urlString.substring(serverBaseDir.length());
    }

    return Namespace.createLoaderName(Namespace.JBOSS_NAMESPACE, getShortName(clazz) + ":" + urlString);
  }

  private static String getShortName(Class clazz) {
    String s = clazz.getName();
    return s.substring(s.lastIndexOf('.') + 1);
  }

}
