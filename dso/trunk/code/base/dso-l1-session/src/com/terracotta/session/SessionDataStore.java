/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.terracotta.session;

import com.tc.object.bytecode.Manageable;
import com.tc.object.bytecode.ManagerUtil;
import com.terracotta.session.util.Assert;
import com.terracotta.session.util.ContextMgr;
import com.terracotta.session.util.LifecycleEventMgr;
import com.terracotta.session.util.Lock;
import com.terracotta.session.util.Timestamp;

import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

public class SessionDataStore {

  private final Map               store;                // <SessionData>
  private final Map               dtmStore;             // <Timestamp>
  private final int               maxIdleTimeoutSeconds;
  private final ContextMgr        ctxMgr;
  private final LifecycleEventMgr lifecycleEventMgr;
  private final SessionManager    sessionManager;

  public SessionDataStore(String appName, int maxIdleTimeoutSeconds, LifecycleEventMgr lifecycleEventMgr,
                          ContextMgr ctxMgr, SessionManager sessionManager) {
    this.sessionManager = sessionManager;
    Assert.pre(appName != null && appName.length() > 0);

    this.maxIdleTimeoutSeconds = maxIdleTimeoutSeconds;
    this.lifecycleEventMgr = lifecycleEventMgr;
    this.ctxMgr = ctxMgr;

    final String sessionRootName = "tc:session_" + appName;
    final String dtmRootName = "@tc:session_timestamp_" + appName;
    final Lock lock = new Lock(sessionRootName);
    lock.getWriteLock();
    try {
      this.store = (Hashtable) ManagerUtil.lookupOrCreateRootNoDepth(sessionRootName, new Hashtable());
      ((Manageable) store).__tc_managed().disableAutoLocking();
      this.dtmStore = (Hashtable) ManagerUtil.lookupOrCreateRootNoDepth(dtmRootName, new Hashtable());
      ((Manageable) dtmStore).__tc_managed().disableAutoLocking();
    } finally {
      lock.commitLock();
    }
    Assert.post(store != null);
  }

  /**
   * <ol>
   * <li>get WRITE_LOCK for sessId (and READ_LOCK for sessionInvalidatorLock)
   * <li>creates session data
   * <li>put newly-created SessionData into the global Map
   * <li>if session-locking is false, unlock sessId
   * <li>returns newly-created SessionData
   * </ol>
   */
  public SessionData createSessionData(final SessionId sessId) {
    Assert.pre(sessId != null);
    SessionData rv = null;
    sessId.getSessionInvalidatorReadLock();
    sessId.getWriteLock();
    try {
      rv = new SessionData(maxIdleTimeoutSeconds);
      rv.associate(sessId, lifecycleEventMgr, ctxMgr, sessionManager);
      store.put(sessId.getKey(), rv);
      if (sessionManager.isApplicationSessionLocked()) {
        ((Manageable) rv).__tc_managed().disableAutoLocking();
      }
      dtmStore.put(sessId.getKey(), rv.getTimestamp());
      rv.startRequest();
    } finally {
      if (!sessionManager.isApplicationSessionLocked()) {
        sessId.commitLock();
      }
      Assert
          .post(sessionManager.isApplicationSessionLocked() == ((Manageable) rv).__tc_managed().autoLockingDisabled());
    }
    return rv;
  }

  /**
   * <ol>
   * <li>get WRITE_LOCK for sessId (and READ_LOCK for sessionInvalidatorLock)
   * <li>look up SessionData for sessId.getKey() in the global Map
   * <li>if SessionData is invalid, unlock sessId and return null (invalidator will take care of killing this session)
   * <li>if SessionData is invalid, unlock sessionInvalidatorLock
   * <li>if session-locking is false, unlock sessId
   * <li>return valid SessionData
   */
  public SessionData find(final SessionId sessId) {
    Assert.pre(sessId != null);

    SessionData rv = null;
    sessId.getSessionInvalidatorReadLock();
    sessId.getWriteLock();
    try {
      rv = (SessionData) store.get(sessId.getKey());
      if (rv != null) {
        if (sessionManager.isApplicationSessionLocked()) {
          ((Manageable) rv).__tc_managed().disableAutoLocking();
        }
        rv.associate(sessId, lifecycleEventMgr, ctxMgr, sessionManager);
        rv.startRequest();
        if (!rv.isValid()) rv = null;
        else {
          updateTimestampIfNeeded(rv);
        }
      }
    } finally {
      if (rv == null) {
        sessId.commitLock();
        sessId.commitSessionInvalidatorLock();
      } else {
        if (!sessionManager.isApplicationSessionLocked()) {
          sessId.commitLock();
        }
        Assert.post(sessionManager.isApplicationSessionLocked() == ((Manageable) rv).__tc_managed()
            .autoLockingDisabled());
      }
    }
    return rv;
  }

  void updateTimestampIfNeeded(SessionData sd) {
    Assert.pre(sd != null);

    if (sd.neverExpires()) { return; }

    final long now = System.currentTimeMillis();
    final Timestamp t = sd.getTimestamp();
    final long diff = t.getMillis() - now;

    if (diff < (sd.getMaxInactiveMillis() / 2) || diff > (sd.getMaxInactiveMillis())) {
      t.setMillis(now + sd.getMaxInactiveMillis());
    }
  }

  public void remove(final SessionId id) {
    Assert.pre(id != null);
    id.getWriteLock();
    try {
      store.remove(id.getKey());
      dtmStore.remove(id.getKey());
    } finally {
      id.commitLock();
    }
  }

  public String[] getAllKeys() {
    String[] rv;
    synchronized (store) {
      Set keys = store.keySet();
      rv = (String[]) keys.toArray(new String[keys.size()]);
    }
    Assert.post(rv != null);
    return rv;
  }

  Timestamp findTimestampUnlocked(final SessionId sessId) {
    return (Timestamp) dtmStore.get(sessId.getKey());
  }

  SessionData findSessionDataUnlocked(final SessionId sessId) {
    final SessionData rv = (SessionData) store.get(sessId.getKey());
    if (rv != null) {
      if (sessionManager.isApplicationSessionLocked()) {
        ((Manageable) rv).__tc_managed().disableAutoLocking();
      }
      rv.associate(sessId, lifecycleEventMgr, ctxMgr, sessionManager);
    }
    Assert.post(sessionManager.isApplicationSessionLocked() == ((Manageable) rv).__tc_managed().autoLockingDisabled());
    return rv;
  }

}
