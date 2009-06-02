/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.admin;

import org.apache.commons.lang.StringUtils;
import org.terracotta.modules.configuration.PresentationFactory;

import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.beans.TIMByteProviderMBean;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.management.ObjectName;

public class Feature extends URLClassLoader {
  private static TCLogger                             logger = TCLogging.getLogger(Feature.class);

  private String                                      symbolicName;
  private String                                      displayName;
  private final Map<ObjectName, TIMByteProviderMBean> byteProviderMap;
  private int                                         tab    = -1;
  // private final HashMap<String, File> resourceTable = new HashMap<String, File>();
  private URL                                         moduleLocation;

  protected Feature() {
    super(new URL[] {}, Feature.class.getClassLoader());
    byteProviderMap = new LinkedHashMap<ObjectName, TIMByteProviderMBean>();
  }

  public Feature(String symbolicName, String displayName) {
    this();

    this.symbolicName = symbolicName;
    this.displayName = displayName;
  }

  public String getSymbolicName() {
    return symbolicName;
  }

  public String getDisplayName() {
    return displayName != null ? displayName : symbolicName;
  }

  private boolean loadingModule = false;

  public void addTIMByteProvider(ObjectName objName, TIMByteProviderMBean byteProvider) {
    byteProviderMap.put(objName, byteProvider);
    synchronized (this) {
      if (moduleLocation == null && !loadingModule) {
        loadingModule = true;
        loadModule();
      }
    }
  }

  public boolean removeTIMByteProvider(ObjectName objName) {
    return byteProviderMap.remove(objName) != null;
  }

  public Iterator<TIMByteProviderMBean> byteProviders() {
    return byteProviderMap.values().iterator();
  }

  public int getTIMByteProviderCount() {
    return byteProviderMap.size();
  }

  public String getManifestEntry(String key) {
    Iterator<TIMByteProviderMBean> iter = byteProviders();
    String presentationFactory;
    while (iter.hasNext()) {
      TIMByteProviderMBean byteProvider = iter.next();
      if ((presentationFactory = byteProvider.getManifestEntry(key)) != null) { return presentationFactory; }
    }
    return null;
  }

  public PresentationFactory getPresentationFactory() throws ClassNotFoundException, IllegalAccessException,
      InstantiationException {
    String factoryName = getManifestEntry("Presentation-Factory");
    if (factoryName != null) {
      Class c = loadClass(factoryName);
      if (c != null) { return (PresentationFactory) c.newInstance(); }
    }
    return null;
  }

  // @Override
  // protected Class findClass(String className) throws ClassNotFoundException, ClassFormatError {
  // byte[] classBytes = null;
  // Class classClass = null;
  //
  // try {
  // classBytes = loadFromByteProviders(className.replace('.', '/') + ".class");
  // } catch (IOException ioe) {
  // throw new ClassNotFoundException(className, ioe);
  // }
  // classClass = defineClass(className, classBytes, 0, classBytes.length);
  // if (classClass == null) { throw new ClassFormatError(className); }
  // logger.debug(className + " loaded from the ByteProvider");
  // return classClass;
  // }
  //
  // @Override
  // public URL findResource(String name) {
  // try {
  // File localResourceFile = resourceTable.get(name);
  // if (localResourceFile == null) {
  // logger.debug("findResource: " + name + " at the ByteProvider.");
  // byte[] resourceBytes = loadFromByteProviders(name);
  // if (resourceBytes == null) {
  // logger.debug("Resource " + name + " not found by ByteProvider.");
  // return null;
  // }
  // localResourceFile = createLocalResourceFile(name, resourceBytes);
  // resourceTable.put(name, localResourceFile);
  // logger.debug("stored locally: " + localResourceFile);
  // }
  // return getLocalResourceURL(localResourceFile);
  // } catch (Exception e) {
  // logger.debug("Exception " + e);
  // }
  // return super.findResource(name);
  // }
  //
  // protected URL getLocalResourceURL(File file) throws MalformedURLException {
  // return file.toURL();
  // }

  protected File createLocalResourceFile(String name, byte[] bytes) throws MalformedURLException,
      FileNotFoundException, IOException {
    File resFile = File.createTempFile("__temp_res_", "_" + createLocalResourceName(name));
    resFile.deleteOnExit();
    FileOutputStream fostream = new FileOutputStream(resFile);
    fostream.write(bytes, 0, bytes.length);
    fostream.close();
    return resFile;
  }

  protected String createLocalResourceName(String name) {
    return name.replace('/', '_');
  }

  @Override
  public synchronized Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
    ++tab;
    logger.debug(StringUtils.leftPad("Loading class " + name, tab, ' '));
    Class result = super.loadClass(name, resolve);
    --tab;
    return result;
  }

  public byte[] loadModuleBytes() throws IOException {
    byte[] result = null;
    Iterator<TIMByteProviderMBean> iter = byteProviders();
    while (iter.hasNext()) {
      try {
        if ((result = iter.next().getModuleBytes()) != null) {
          break;
        }
      } catch (IOException ioe) {
        /**/
      }
    }
    if (result == null) {
      throw new IOException("Bytes for '" + symbolicName + "' not found by any ByteProvider");
    } else {
      return result;
    }
  }

  protected byte[] loadFromByteProviders(String name) throws IOException {
    byte[] result = null;
    Iterator<TIMByteProviderMBean> iter = byteProviders();
    while (iter.hasNext()) {
      try {
        result = iter.next().getResourceAsByteArray(name);
      } catch (IOException ioe) {
        /**/
      }
    }
    if (result == null) {
      throw new IOException("Bytes for '" + name + "' not found by any ByteProvider");
    } else {
      return result;
    }
  }

  private void loadModule() {
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    final Future<URL> future = executorService.submit(new ModuleLoaderFuture());
    new Thread() {
      @Override
      public void run() {
        try {
          addURL(moduleLocation = future.get());
          loadingModule = false;
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    }.start();
  }

  private class ModuleLoaderFuture implements Callable<URL> {
    public URL call() throws Exception {
      byte[] bytes = loadModuleBytes();
      File f = createLocalResourceFile(symbolicName, bytes);
      return f.toURL();
    }
  }

  @Override
  public boolean equals(Object other) {
    if (other == this) return true;
    if (!(other instanceof Feature)) return false;

    Feature otherFeature = (Feature) other;
    return StringUtils.equals(getSymbolicName(), otherFeature.getSymbolicName());
  }

  @Override
  public int hashCode() {
    return symbolicName.hashCode();
  }

  @Override
  public String toString() {
    return symbolicName;
  }
}
