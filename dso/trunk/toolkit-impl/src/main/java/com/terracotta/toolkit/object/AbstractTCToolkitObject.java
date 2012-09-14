/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.object;

import com.tc.net.GroupID;
import com.tc.object.LiteralValues;
import com.tc.object.SerializationUtil;
import com.tc.object.TCObject;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.bytecode.PlatformService;
import com.terracotta.toolkit.TerracottaToolkit;
import com.terracotta.toolkit.object.serialization.SerializationStrategy;
import com.terracotta.toolkit.object.serialization.SerializedClusterObject;
import com.terracotta.toolkit.object.serialization.SerializedClusterObjectFactory;
import com.terracotta.toolkit.object.serialization.SerializedClusterObjectFactoryImpl;

import java.util.ArrayList;
import java.util.Collection;

public abstract class AbstractTCToolkitObject implements TCToolkitObject {

  protected final SerializedClusterObjectFactory serializedClusterObjectFactory;
  protected final SerializationStrategy          strategy;

  protected volatile GroupID                     gid;
  protected volatile TCObject                    tcObject;
  private volatile DestroyApplicator             applyDestroyCallback;
  private volatile boolean                       destroyed = false;
  protected final PlatformService                platformService;

  public AbstractTCToolkitObject() {
    platformService = ManagerUtil
        .lookupRegisteredObjectByName(TerracottaToolkit.PLATFORM_SERVICE_REGISTRATION_NAME, PlatformService.class);
    SerializationStrategy registeredSerializer = platformService
        .lookupRegisteredObjectByName(TerracottaToolkit.TOOLKIT_SERIALIZER_REGISTRATION_NAME,
                                      SerializationStrategy.class);
    if (registeredSerializer == null) {
      //
      throw new AssertionError("No SerializationStrategy registered in L1");
    }
    this.strategy = registeredSerializer;
    this.serializedClusterObjectFactory = new SerializedClusterObjectFactoryImpl(platformService, strategy);
  }

  @Override
  public void __tc_managed(TCObject t) {
    this.tcObject = t;
    this.gid = new GroupID(t.getObjectID().getGroupID());
  }

  @Override
  public TCObject __tc_managed() {
    return tcObject;
  }

  @Override
  public boolean __tc_isManaged() {
    return tcObject != null;
  }

  protected void doLogicalDestroy() {
    platformService.logicalInvoke(this, SerializationUtil.DESTROY_SIGNATURE, new Object[] {});
  }

  @Override
  public final void destroy() {
    doLogicalDestroy();
    applyDestroy();
  }

  @Override
  public final void applyDestroy() {
    destroyed = true;
    cleanupOnDestroy();
    if (applyDestroyCallback != null) {
      applyDestroyCallback.applyDestroy();
    }
  }

  @Override
  public void setApplyDestroyCallback(DestroyApplicator callback) {
    this.applyDestroyCallback = callback;
  }

  @Override
  public final boolean isDestroyed() {
    return destroyed;
  }

  protected Object createTCCompatibleObject(Object e) {
    boolean isLiteral = LiteralValues.isLiteralInstance(e);
    if (isLiteral) { return e; }

    return createSerializedClusterObject(e);
  }

  protected Collection createTCCompatiableCollection(Collection c) {
    Collection serCollection = new ArrayList(c.size());
    for (Object e : c) {
      serCollection.add(createTCCompatibleObject(e));
    }
    return serCollection;
  }

  private SerializedClusterObject createSerializedClusterObject(Object obj) {
    return serializedClusterObjectFactory.createSerializedClusterObject(obj, gid);
  }

}
