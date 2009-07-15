/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.admin;

import org.terracotta.modules.configuration.PresentationFactory;

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

public class FeatureClassLoader extends URLClassLoader {
  private final Map<ObjectName, TIMByteProviderMBean> byteProviderMap;
  private final Feature                               feature;
  private URL                                         moduleLocation;

  FeatureClassLoader(Feature feature) {
    super(new URL[] {}, FeatureClassLoader.class.getClassLoader());
    this.byteProviderMap = new LinkedHashMap<ObjectName, TIMByteProviderMBean>();
    this.feature = feature;
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
    return super.loadClass(name, resolve);
  }

  protected byte[] loadModuleBytes() throws IOException {
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
      throw new IOException("Bytes for '" + feature.getSymbolicName() + "' not found by any ByteProvider");
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
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          loadingModule = false;
        }
      }
    }.start();
  }

  private class ModuleLoaderFuture implements Callable<URL> {
    public URL call() throws Exception {
      byte[] bytes = loadModuleBytes();
      File f = createLocalResourceFile(feature.getSymbolicName(), bytes);
      return f.toURL();
    }
  }
}
