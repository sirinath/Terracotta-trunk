/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.object;

import com.tc.exception.ImplementMe;
import com.tc.object.dna.api.DNA;
import com.tc.object.dna.api.DNAWriter;
import com.tc.object.field.TCField;
import com.tc.object.loaders.LoaderDescription;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

public class MockTCClass implements TCClass {
  private final String  name = MockTCClass.class.getName();
  private final boolean isIndexed;
  private final boolean isLogical;

  public MockTCClass(boolean isIndexed, boolean isLogical) {
    this.isIndexed = isIndexed;
    this.isLogical = isLogical;
  }

  public TCField[] getPortableFields() {
    throw new ImplementMe();
  }

  public TraversedReferences getPortableObjects(Object pojo, TraversedReferences addTo) {
    throw new ImplementMe();
  }

  public Constructor getConstructor() {
    throw new ImplementMe();
  }

  public String getName() {
    return this.name;
  }

  public Class getComponentType() {
    throw new ImplementMe();
  }

  public TCClass getSuperclass() {
    throw new ImplementMe();
  }

  public boolean isLogical() {
    return this.isLogical;
  }

  public TCField getField(String fieldName) {
    throw new ImplementMe();
  }

  public boolean isIndexed() {
    return this.isIndexed;
  }

  public void hydrate(TCObject tcObject, DNA dna, Object pojo, boolean force) {
    throw new ImplementMe();
  }

  public void dehydrate(TCObject tcObject, DNAWriter writer, Object pojo) {
    throw new ImplementMe();
  }

  public Field getParentField() {
    throw new ImplementMe();
  }

  public String getParentFieldName() {
    return name + ".this$0";
  }

  public LoaderDescription getDefiningLoaderDescription() {
    return new LoaderDescription(null, "mock");
  }

  public boolean isNonStaticInner() {
    throw new ImplementMe();
  }

  public TCObject createTCObject(ObjectID id, Object peer, boolean isNew) {
    throw new ImplementMe();
  }

  public boolean hasOnLoadExecuteScript() {
    return false;
  }

  public String getOnLoadExecuteScript() {

    return null;
  }

  public boolean hasOnLoadMethod() {
    return false;
  }

  public String getOnLoadMethod() {
    return null;
  }

  public boolean isUseNonDefaultConstructor() {
    return false;
  }

  public Object getNewInstanceFromNonDefaultConstructor(DNA dna) {
    throw new ImplementMe();
  }

  public Class getPeerClass() {
    return getClass();
  }

  public String getFieldNameByOffset(long fieldOffset) {
    throw new ImplementMe();
  }

  public ClientObjectManager getObjectManager() {
    throw new ImplementMe();
  }

  public boolean isProxyClass() {
    return false;
  }

  public String getExtendingClassName() {
    return getName();
  }

  public boolean isEnum() {
    return false;
  }

  public boolean isPortableField(long fieldOffset) {
    throw new ImplementMe();
  }

  public boolean useResolveLockWhileClearing() {
    return true;
  }

}
