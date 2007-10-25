/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.exception.ImplementMe;
import com.tc.exception.TCNonPortableObjectError;
import com.tc.object.tx.UnlockedSharedObjectException;
import com.tc.object.util.ReadOnlyException;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tctest.runner.AbstractErrorCatchingTransparentApp;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public abstract class GenericLocalStateTestApp extends AbstractErrorCatchingTransparentApp {

  public GenericLocalStateTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
  }

  protected void runTest() throws Throwable {
    throw new ImplementMe();
  }

  protected void testMutate(Wrapper wrapper, LockMode lockMode, Mutator mutator) throws Throwable {
    int oldSize = wrapper.size();
    LockMode curr_lockMode = wrapper.getHandler().getLockMode();
    boolean gotExpectedException = false;
    Throwable throwable = null;

    if (await() == 0) {
      System.out.println("Mutating: " + wrapper.getObject().getClass().getSimpleName() +
                         " with " + mutator.getClass().getSimpleName() + " with lock " + lockMode);
      wrapper.getHandler().setLockMode(lockMode);
      try {
        mutator.doMutate(wrapper.getProxy());
      } catch (UnlockedSharedObjectException usoe) {
        gotExpectedException = lockMode == LockMode.NONE;
      } catch (ReadOnlyException roe) {
        gotExpectedException = lockMode == LockMode.READ;
      } catch (TCNonPortableObjectError ne) {
        gotExpectedException = lockMode == LockMode.WRITE;
      } catch (Throwable t) {
        throwable = t;
      }
    }

    System.out.println("Waiting for mutation to finished...");
    await();
    wrapper.getHandler().setLockMode(curr_lockMode);

    if (gotExpectedException) {
      System.out.println("... validating...");
      validate(oldSize, wrapper, lockMode, mutator);
    }

    if (throwable != null) {
      System.err.println(" ---- ERROR DETECTED --- ");
      throw throwable;
    }
  }

  protected abstract int await();

  protected abstract void validate(int oldSize, Wrapper wrapper, LockMode lockMode, Mutator mutator) throws Throwable;

  static enum LockMode {
    NONE, READ, WRITE
  }

  static class Handler implements InvocationHandler {
    private final Object o;
    private LockMode     lockMode = LockMode.NONE;

    public Handler(Object o) {
      this.o = o;
    }

    public LockMode getLockMode() {
      return lockMode;
    }

    public void setLockMode(LockMode mode) {
      synchronized (this) {
        lockMode = mode;
      }
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      try {
        switch (lockMode) {
          case NONE:
            return method.invoke(o, args);
          case READ:
            return invokeWithReadLock(method, args);
          case WRITE:
            return invokeWithWriteLock(method, args);
          default:
            throw new RuntimeException("Should'n happen");
        }
      } catch (InvocationTargetException e) {
        throw e.getTargetException();
      }
    }

    private Object invokeWithReadLock(Method method, Object[] args) throws Throwable {
      synchronized (o) {
        return method.invoke(o, args);
      }
    }

    private Object invokeWithWriteLock(Method method, Object[] args) throws Throwable {
      synchronized (o) {
        return method.invoke(o, args);
      }
    }
  }

  static interface Wrapper {
    public Object getObject();

    public Object getProxy();

    public Handler getHandler();

    public int size();
  }

  static interface Mutator {
    public void doMutate(Object o);
  }

  static class CollectionWrapper implements Wrapper {
    private Object  object;
    private Object  proxy;
    private Handler handler;

    public CollectionWrapper(Class objectClass, Class interfaceClass) throws Exception {
      object = objectClass.newInstance();
      handler = new Handler(object);
      proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] { interfaceClass }, handler);
    }

    public Handler getHandler() {
      return handler;
    }

    public Object getObject() {
      return object;
    }

    public Object getProxy() {
      return proxy;
    }

    public int size() {
      try {
        Method method = object.getClass().getMethod("size");
        return (Integer) method.invoke(object);
      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    }
  }
}
