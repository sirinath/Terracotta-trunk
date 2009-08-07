/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object.dna.impl;

import com.tc.object.loaders.ClassProvider;
import com.tc.object.loaders.LoaderDescription;
import com.tc.object.loaders.NamedClassLoader;

public class StorageDNAEncodingImpl extends BaseDNAEncodingImpl {

  private static final ClassProvider FAILURE_PROVIDER = new FailureClassProvider();

  public StorageDNAEncodingImpl() {
    super(FAILURE_PROVIDER);
  }

  @Override
  protected boolean useStringEnumRead(byte type) {
    return false;
  }

  @Override
  protected boolean useClassProvider(byte type, byte typeToCheck) {
    return false;
  }

  @Override
  protected boolean useUTF8String(byte type) {
    return false;
  }

  private static class FailureClassProvider implements ClassProvider {

    public LoaderDescription getLoaderDescriptionFor(Class clazz) {
      throw new AssertionError();
    }

    public ClassLoader getClassLoader(LoaderDescription loaderDesc) {
      throw new AssertionError();
    }

    public LoaderDescription getLoaderDescriptionFor(ClassLoader loader) {
      throw new AssertionError();
    }

    public Class getClassFor(String className, LoaderDescription desc) throws ClassNotFoundException {
      throw new ClassNotFoundException();
    }

    public void registerNamedLoader(NamedClassLoader loader, String appGroup) {
      throw new AssertionError();
    }
  }

}
