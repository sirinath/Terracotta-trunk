/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

import org.apache.commons.io.IOUtils;

import com.tc.exception.TCRuntimeException;
import com.tc.io.serializer.TCObjectInputStream;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.objectserver.managedobject.ManagedObjectStateFactory;
import com.tc.objectserver.managedobject.NullManagedObjectChangeListenerProvider;
import com.tc.objectserver.managedobject.bytecode.PhysicalStateClassLoader;
import com.tc.objectserver.persistence.api.ClassPersistor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;

public class ReviveClassFiles {

  private static final TCLogger    logger     = TCLogging.getLogger(ReviveClassFiles.class);
  private ClassPersistor           persistor;
  private int                      sequenceId = 0;
  private PhysicalStateClassLoader loader;

  public ReviveClassFiles(File sourceDir, File destDir) throws Exception {
    DBEnvironment env = new DBEnvironment(true, sourceDir);
    SerializationAdapterFactory serializationAdapterFactory = new CustomSerializationAdapterFactory();
    final NullManagedObjectChangeListenerProvider managedObjectChangeListenerProvider = new NullManagedObjectChangeListenerProvider();
    SleepycatPersistor sleepyCatPersistor = new SleepycatPersistor(logger, env, serializationAdapterFactory);
    ManagedObjectStateFactory.createInstance(managedObjectChangeListenerProvider, sleepyCatPersistor);
    persistor = sleepyCatPersistor.getClassPersistor();
    loader = new PhysicalStateClassLoader();
  }

  private void reviveClassesFiles(File destDir) {
    SortedMap map = new TreeMap(persistor.retrieveAllClasses());
    for (Iterator i = map.entrySet().iterator(); i.hasNext();) {
      Map.Entry e = (Entry) i.next();
      Integer clazzId = (Integer) e.getKey();
      byte clazzBytes[] = (byte[]) e.getValue();
      int cid = clazzId.intValue();
      if (sequenceId < cid) {
        sequenceId = cid;
      }
      loadFromBytes(cid, clazzBytes, destDir);
    }

  }

  private void loadFromBytes(int classId, byte[] clazzBytes, File destDir) {
    try {
      ByteArrayInputStream bai = new ByteArrayInputStream(clazzBytes);
      TCObjectInputStream tci = new TCObjectInputStream(bai);

      @SuppressWarnings("unused")
      String classIdentifier = tci.readString();

      String genClassName = tci.readString();

      File file = new File(destDir.getPath() + File.separator + genClassName + ".class");
      file.createNewFile();
      ByteArrayInputStream bais = new ByteArrayInputStream(clazzBytes);
      FileOutputStream fos = new FileOutputStream(file);
      IOUtils.copy(bais, fos);
      IOUtils.closeQuietly(fos);
      IOUtils.closeQuietly(bais);
      verify(genClassName, classId, destDir);

    } catch (Exception ex) {
      throw new TCRuntimeException(ex);
    }
  }

  private void verify(String genClassName, int classId, File destDir) {
    byte[] loadedClassBytes = loadClassData(genClassName, destDir);
    ByteArrayInputStream bai = new ByteArrayInputStream(loadedClassBytes);
    TCObjectInputStream tci = new TCObjectInputStream(bai);

    try {
      @SuppressWarnings("unused")
      String cl = tci.readString();
      @SuppressWarnings("unused")
      String g = tci.readString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Class clazz = loader.defineClassFromBytes(genClassName, classId, loadedClassBytes, loadedClassBytes.length
                                                                                       - bai.available(), bai.available());
    
    if (clazz != null && genClassName.equals(clazz.getName())) {
      log("successfully loaded class [ " + genClassName + " ]  from generated class file");
    } else {
      log("could not load class [ " + genClassName + " ] from generated class file");
    }
  }

  private byte[] loadClassData(String name, File classDir) {
    File classFile = new File(classDir.getPath() + File.separator + name + ".class");
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(classFile);

      byte[] b = IOUtils.toByteArray(fis);
      return b;
    } catch (IOException e) {
      return null;
    } finally {
      if (fis != null) {
        IOUtils.closeQuietly(fis);
      }
    }
  }

  public static void main(String[] args) {
    if (args == null || args.length < 2) {
      usage();
      System.exit(1);
    }

    try {
      File sourceDir = new File(args[0]);
      validateDir(sourceDir);
      File destDir = new File(args[1]);
      validateDir(destDir);

      ReviveClassFiles reviver = new ReviveClassFiles(sourceDir, destDir);
      reviver.reviveClassesFiles(destDir);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(2);
    }
  }

  private static void validateDir(File dir) {
    if (!dir.exists() || !dir.isDirectory()) { throw new RuntimeException("Not a valid directory : " + dir); }
  }

  private static void usage() {
    log("Usage: ReviveClassFiles <environment home directory> <class files destination directory>");
  }

  private static void log(String message) {
    System.out.println(message);
  }

}
