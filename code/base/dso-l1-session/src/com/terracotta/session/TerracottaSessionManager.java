/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.terracotta.session;

import com.tc.logging.TCLogger;
import com.tc.management.beans.sessions.SessionMonitorMBean;
import com.tc.management.beans.sessions.SessionMonitorMBean.SessionsComptroller;
import com.tc.object.bytecode.ManagerUtil;
import com.tc.object.bytecode.hook.impl.ClassProcessorHelper;
import com.terracotta.session.util.Assert;
import com.terracotta.session.util.ConfigProperties;
import com.terracotta.session.util.ContextMgr;
import com.terracotta.session.util.DefaultContextMgr;
import com.terracotta.session.util.LifecycleEventMgr;
import com.terracotta.session.util.Lock;
import com.terracotta.session.util.SessionCookieWriter;
import com.terracotta.session.util.SessionIdGenerator;
import com.terracotta.session.util.Timestamp;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TerracottaSessionManager implements SessionManager {

  private final SessionMonitorMBean    mBean;
  private final SessionIdGenerator     idGenerator;
  private final SessionCookieWriter    cookieWriter;
  private final SessionDataStore       store;
  private final LifecycleEventMgr      eventMgr;
  private final ContextMgr             contextMgr;
  private final boolean                reqeustLogEnabled;
  private final boolean                invalidatorLogEnabled;
  private final TCLogger               logger;
  private final RequestResponseFactory factory;
  private final RequestTracker         tracker;
  private final boolean                debugServerHops;
  private final int                    debugServerHopsInterval;
  private final boolean                debugInvalidate;
  private final boolean                debugSessions;
  private int                          serverHopsDetected = 0;

  private static final Set             excludedVHosts     = loadExcludedVHosts();

  public TerracottaSessionManager(SessionIdGenerator sig, SessionCookieWriter scw, LifecycleEventMgr eventMgr,
                                  ContextMgr contextMgr, RequestResponseFactory factory, ConfigProperties cp) {

    Assert.pre(sig != null);
    Assert.pre(scw != null);
    Assert.pre(eventMgr != null);
    Assert.pre(contextMgr != null);

    this.idGenerator = sig;
    this.cookieWriter = scw;
    this.eventMgr = eventMgr;
    this.contextMgr = contextMgr;
    this.factory = factory;
    this.store = new SessionDataStore(contextMgr.getAppName(), cp.getSessionTimeoutSeconds(), eventMgr, contextMgr,
                                      this);
    this.logger = ManagerUtil.getLogger("com.tc.tcsession." + contextMgr.getAppName());
    this.reqeustLogEnabled = cp.getRequestLogBenchEnabled();
    this.invalidatorLogEnabled = cp.getInvalidatorLogBenchEnabled();

    // XXX: If reasonable, we should move this out of the constructor -- leaking a reference to "this" to another thread
    // within a constructor is a bad practice (note: although "this" isn't explicitly based as arg, it is available and
    // accessed by the non-static inner class)
    Thread invalidator = new Thread(new SessionInvalidator(cp.getInvalidatorSleepSeconds()), "SessionInvalidator - "
                                                                                             + contextMgr.getAppName());
    invalidator.setDaemon(true);
    invalidator.start();
    Assert.post(invalidator.isAlive());

    // This is disgusting, but right now we have to do this because we don't have an event
    // management infrastructure to boot stuff up
    mBean = ManagerUtil.getSessionMonitorMBean();

    mBean.registerSessionsController(new SessionsComptroller() {
      public boolean killSession(final String browserSessionId) {
        SessionId id = idGenerator.makeInstanceFromBrowserId(browserSessionId);
        if (id == null) {
          // that, potentially, was not *browser* id, try to recover...
          id = idGenerator.makeInstanceFromInternalKey(browserSessionId);
        }
        expire(id);
        return true;
      }
    });

    if (cp.isRequestTrackingEnabled()) {
      tracker = new StuckRequestTracker(cp.getRequestTrackerSleepMillis(), cp.getRequestTrackerStuckThresholdMillis(),
                                        cp.isDumpThreadsOnStuckRequests());
      ((StuckRequestTracker) tracker).start();
    } else {
      tracker = new NullRequestTracker();
    }

    this.debugServerHops = cp.isDebugServerHops();
    this.debugServerHopsInterval = cp.getDebugServerHopsInterval();
    this.debugInvalidate = cp.isDebugSessionInvalidate();
    this.debugSessions = cp.isDebugSessions();
  }

  private static Set loadExcludedVHosts() {
    String list = ManagerUtil.getTCProperties().getProperty("session.vhosts.excluded", true);
    list = (list == null) ? "" : list.replaceAll("\\s", "");

    Set set = new TreeSet();
    String[] vhosts = list.split(",");
    for (int i = 0; i < vhosts.length; i++) {
      String vhost = vhosts[i];
      if (vhost != null && vhost.length() > 0) {
        set.add(vhost);
      }
    }

    if (set.size() > 0) {
      ManagerUtil.getLogger("com.tc.TerracottaSessionManager").warn("Excluded vhosts for sessions: " + set);
    }

    return Collections.unmodifiableSet(set);
  }

  public TerracottaRequest preprocess(HttpServletRequest req, HttpServletResponse res) {
    tracker.begin(req);
    TerracottaRequest terracottaRequest = basicPreprocess(req, res);
    tracker.recordSessionId(terracottaRequest);
    return terracottaRequest;
  }

  private TerracottaRequest basicPreprocess(HttpServletRequest req, HttpServletResponse res) {
    Assert.pre(req != null);
    Assert.pre(res != null);

    SessionId sessionId = findSessionId(req);

    if (debugServerHops) {
      if (sessionId != null && sessionId.isServerHop()) {
        synchronized (this) {
          serverHopsDetected++;
          if ((serverHopsDetected % debugServerHopsInterval) == 0) {
            logger.info(serverHopsDetected + " server hops detected");
          }
        }
      }
    }

    TerracottaRequest rw = wrapRequest(sessionId, req, res);

    Assert.post(rw != null);
    return rw;
  }

  public TerracottaResponse createResponse(TerracottaRequest req, HttpServletResponse res) {
    return factory.createResponse(req, res);
  }

  public void postprocess(TerracottaRequest req) {
    try {
      basicPostprocess(req);
    } finally {
      tracker.end();
    }
  }

  private void basicPostprocess(TerracottaRequest req) {
    Assert.pre(req != null);

    // don't do anything for forwarded requests
    if (req.isForwarded()) return;

    Assert.inv(!req.isForwarded());

    mBean.requestProcessed();

    try {
      if (req.isSessionOwner()) postprocessSession(req);
    } finally {
      if (reqeustLogEnabled) {
        logRequestBench(req);
      }
    }
  }

  private void logRequestBench(TerracottaRequest req) {
    final String msgPrefix = "REQUEST BENCH: url=[" + req.getRequestURL() + "]";
    String sessionInfo = "";
    if (req.isSessionOwner()) {
      final SessionId id = req.getTerracottaSession(false).getSessionId();
      sessionInfo = " sid=[" + id.getKey() + "]";
    }
    final String msg = msgPrefix + sessionInfo + " -> " + (System.currentTimeMillis() - req.getRequestStartMillis());
    logger.info(msg);
  }

  private void postprocessSession(TerracottaRequest req) {
    Assert.pre(req != null);
    Assert.pre(!req.isForwarded());
    Assert.pre(req.isSessionOwner());
    final Session session = req.getTerracottaSession(false);
    session.clearRequest();
    Assert.inv(session != null);
    final SessionId id = session.getSessionId();
    final SessionData sd = session.getSessionData();
    try {
      if (!session.isValid()) store.remove(id);
      else {
        sd.finishRequest();
        store.updateTimestampIfNeeded(sd);
      }
    } finally {
      id.commitLock();
    }
  }

  /**
   * The only use for this method [currently] is by Struts' Include Tag, which can generate a nested request. In this
   * case we have to release session lock, so that nested request (running, potentially, in another JVM) can acquire it.
   * {@link TerracottaSessionManager#resumeRequest(Session)} method will re-aquire the lock.
   */
  public static void pauseRequest(final Session sess) {
    Assert.pre(sess != null);
    final SessionId id = sess.getSessionId();
    final SessionData sd = sess.getSessionData();
    sd.finishRequest();
    id.commitLock();
  }

  /**
   * See {@link TerracottaSessionManager#resumeRequest(Session)} for details
   */
  public static void resumeRequest(final Session sess) {
    Assert.pre(sess != null);
    final SessionId id = sess.getSessionId();
    final SessionData sd = sess.getSessionData();
    id.getWriteLock();
    sd.startRequest();
  }

  private TerracottaRequest wrapRequest(SessionId sessionId, HttpServletRequest req, HttpServletResponse res) {
    return factory.createRequest(sessionId, req, res, this);
  }

  /**
   * This method always returns a valid session. If data for the requestedSessionId found and is valid, it is returned.
   * Otherwise, we must create a new session id, a new session data, a new session, and cookie the response.
   */
  public Session getSession(final SessionId requestedSessionId, final HttpServletRequest req,
                            final HttpServletResponse res) {
    Assert.pre(req != null);
    Assert.pre(res != null);
    Session rv = doGetSession(requestedSessionId, req, res);
    Assert.post(rv != null);
    return rv;
  }

  public Session getSessionIfExists(SessionId requestedSessionId, HttpServletRequest req, HttpServletResponse res) {
    if (debugSessions) {
      logger.info("getSessionIfExists called for " + requestedSessionId);
    }

    if (requestedSessionId == null) return null;
    SessionData sd = store.find(requestedSessionId);
    if (sd == null) {
      if (debugSessions) {
        logger.info("No session found in store for " + requestedSessionId);
      }
      return null;
    }

    Assert.inv(sd.isValid());
    writeCookieIfHop(req, res, requestedSessionId);

    return sd;
  }

  private void writeCookieIfHop(HttpServletRequest req, HttpServletResponse res, SessionId id) {
    if (id.isServerHop()) {
      Cookie cookie = cookieWriter.writeCookie(req, res, id);
      if (debugSessions) {
        logger.info("writing new cookie for hopped request: " + getCookieDetails(cookie));
      }
    }
  }

  private String getCookieDetails(Cookie c) {
    if (c == null) { return "<null cookie>"; }

    StringBuffer buf = new StringBuffer("Cookie(");
    buf.append(c.getName()).append("=").append(c.getValue());
    buf.append(", path=").append(c.getPath()).append(", maxAge=").append(c.getMaxAge()).append(", domain=")
        .append(c.getDomain());
    buf.append(", secure=").append(c.getSecure()).append(", comment=").append(c.getComment()).append(", version=")
        .append(c.getVersion());

    buf.append(")");
    return buf.toString();
  }

  public SessionCookieWriter getCookieWriter() {
    return this.cookieWriter;
  }

  private void expire(SessionId id) {
    SessionData sd = null;
    try {
      sd = store.find(id);
      if (sd != null) {
        expire(id, sd);
      }
    } finally {
      if (sd != null) id.commitLock();
    }
  }

  public void remove(Session data, boolean unlock) {
    if (debugInvalidate) {
      logger.info("Session id: " + data.getSessionId().getKey() + " being removed, unlock: " + unlock);
    }

    store.remove(data.getSessionId());
    mBean.sessionDestroyed();

    if (unlock) {
      data.getSessionId().commitLock();
    }
  }

  private void expire(SessionId id, SessionData sd) {
    try {
      sd.invalidateIfNecessary();
    } catch (Throwable t) {
      logger.error("unhandled exception during invalidate() for session " + id.getKey(), t);
    }
  }

  private Session doGetSession(final SessionId requestedSessionId, final HttpServletRequest req,
                               final HttpServletResponse res) {
    Assert.pre(req != null);
    Assert.pre(res != null);

    if (requestedSessionId == null) {
      if (debugSessions) {
        logger.info("creating new session since requested id is null");
      }
      return createNewSession(req, res);
    }
    final SessionData sd = store.find(requestedSessionId);

    if (sd == null) {
      if (debugSessions) {
        logger.info("creating new session since requested id is not in store: " + requestedSessionId);
      }
      return createNewSession(req, res);
    }

    if (debugSessions) {
      logger.info("requested id found in store: " + requestedSessionId);
    }

    Assert.inv(sd.isValid());

    writeCookieIfHop(req, res, requestedSessionId);

    return sd;
  }

  private Session createNewSession(HttpServletRequest req, HttpServletResponse res) {
    Assert.pre(req != null);
    Assert.pre(res != null);

    SessionId id = idGenerator.generateNewId();
    SessionData sd = store.createSessionData(id);
    Cookie cookie = cookieWriter.writeCookie(req, res, id);
    eventMgr.fireSessionCreatedEvent(sd);
    mBean.sessionCreated();
    Assert.post(sd != null);

    if (debugSessions) {
      logger.info("new session created: " + id + " with cookie " + getCookieDetails(cookie));
    }

    return sd;
  }

  private SessionId findSessionId(HttpServletRequest httpRequest) {
    Assert.pre(httpRequest != null);

    String requestedSessionId = httpRequest.getRequestedSessionId();

    if (debugSessions) {
      logger.info("requested session ID from http request: " + requestedSessionId);
    }

    if (requestedSessionId == null) { return null; }

    SessionId rv = idGenerator.makeInstanceFromBrowserId(requestedSessionId);
    if (debugSessions) {
      logger.info("session ID generator returned " + rv);
    }

    return rv;
  }

  private class SessionInvalidator implements Runnable {

    private final long sleepMillis;

    public SessionInvalidator(final long sleepSeconds) {
      this.sleepMillis = sleepSeconds * 1000L;
    }

    public void run() {
      final String invalidatorLock = "tc:session_invalidator_lock_" + contextMgr.getAppName();

      while (true) {
        sleep(sleepMillis);
        if (Thread.interrupted()) {
          break;
        } else {
          try {
            final Lock lock = new Lock(invalidatorLock);
            lock.tryWriteLock();
            if (!lock.isLocked()) continue;
            try {
              invalidateSessions();
            } finally {
              lock.commitLock();
            }
          } catch (Throwable t) {
            logger.error("Unhandled exception occurred during session invalidation", t);
          }
        }
      }
    }

    private void invalidateSessions() {
      final long startMillis = System.currentTimeMillis();
      final String keys[] = store.getAllKeys();
      int totalCnt = 0;
      int invalCnt = 0;
      int evaled = 0;
      int notEvaled = 0;
      int errors = 0;

      if (invalidatorLogEnabled) {
        logger.info("SESSION INVALIDATOR: started");
      }

      for (int i = 0, n = keys.length; i < n; i++) {
        final String key = keys[i];
        try {
          final SessionId id = idGenerator.makeInstanceFromInternalKey(key);
          final Timestamp dtm = store.findTimestampUnlocked(id);
          if (dtm == null) continue;
          totalCnt++;
          if (dtm.getMillis() < System.currentTimeMillis()) {
            evaled++;
            if (evaluateSession(dtm, id)) invalCnt++;
          } else {
            notEvaled++;
          }
        } catch (Throwable t) {
          errors++;
          logger.error("Unhandled exception inspecting session " + key + " for invalidation", t);
        }
      }
      if (invalidatorLogEnabled) {
        final String msg = "SESSION INVALIDATOR BENCH: " + " -> total=" + totalCnt + ", evaled=" + evaled
                           + ", notEvaled=" + notEvaled + ", errors=" + errors + ", invalidated=" + invalCnt
                           + " -> elapsed=" + (System.currentTimeMillis() - startMillis);
        logger.info(msg);
      }
    }

    private boolean evaluateSession(final Timestamp dtm, final SessionId id) {
      Assert.pre(id != null);

      boolean rv = false;

      if (!id.tryWriteLock()) { return rv; }

      try {
        final SessionData sd = store.findSessionDataUnlocked(id);
        if (sd == null) return rv;
        if (!sd.isValid()) {
          expire(id, sd);
          rv = true;
        } else {
          store.updateTimestampIfNeeded(sd);
        }
      } finally {
        id.commitLock();
      }
      return rv;
    }

    private void sleep(long l) {
      try {
        Thread.sleep(l);
      } catch (InterruptedException ignore) {
        // nothing to do
      }
    }
  }

  // XXX: move this method?
  public static boolean isDsoSessionApp(HttpServletRequest request) {
    Assert.pre(request != null);

    if (excludedVHosts.contains(request.getServerName())) { return false; }

    String hostHeader = request.getHeader("Host");
    if (hostHeader != null && excludedVHosts.contains(hostHeader)) { return false; }

    final String appName = DefaultContextMgr.computeAppName(request);
    return ClassProcessorHelper.isDSOSessions(appName);
  }

  private static final class NullRequestTracker implements RequestTracker {

    public final boolean end() {
      return true;
    }

    public final void begin(HttpServletRequest req) {
      //
    }

    public final void recordSessionId(TerracottaRequest terracottaRequest) {
      //
    }

  }

}
