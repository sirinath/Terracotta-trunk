/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tctest;

import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tc.util.Assert;
import com.tc.util.TIMUtil;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Stack;
import java.util.Vector;
import java.util.concurrent.CyclicBarrier;

/**
 * Test to make sure local object state is preserved when TC throws:
 * 
 * UnlockedSharedObjectException ReadOnlyException TCNonPortableObjectError
 * 
 * List version
 * 
 * INT-186
 * 
 * @author hhuynh
 */
public class ListLocalStateTestApp extends GenericLocalStateTestApp {
  private List<Wrapper> root        = new ArrayList<Wrapper>();
  private CyclicBarrier barrier;
  private Class[]       listClasses = new Class[] { ArrayList.class, Vector.class, LinkedList.class, Stack.class };

  public ListLocalStateTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
    barrier = new CyclicBarrier(cfg.getGlobalParticipantCount());
  }

  protected void runTest() throws Throwable {
    if (await() == 0) {
      createLists();
    }
    await();

    for (LockMode lockMode : LockMode.values()) {
      for (Wrapper w : root) {
        testMutate(w, lockMode, new AddMutator());
        testMutate(w, lockMode, new AddAllMutator());
        testMutate(w, lockMode, new RemoveMutator());
        testMutate(w, lockMode, new ClearMutator());
        testMutate(w, lockMode, new RemoveAllMutator());
        testMutate(w, lockMode, new RetainAllMutator());
        testMutate(w, lockMode, new IteratorRemoveMutator());
        testMutate(w, lockMode, new IteratorAddMutator());
        testMutate(w, lockMode, new ListIteratorRemoveMutator());
        // failing - DEV-844
        // testMutate(w, lockMode, new AddAllNonPortableMutator());
      }
    }
  }

  protected void validate(int oldSize, Wrapper wrapper, LockMode lockMode, Mutator mutator) throws Throwable {
    int newSize = wrapper.size();
    switch (lockMode) {
      case NONE:
      case READ:
        Assert.assertEquals("Type: " + wrapper.getObject().getClass() + ", lock: " + lockMode, oldSize, newSize);
        break;
      case WRITE:
        // nothing yet
      default:
        throw new RuntimeException("Shouldn't happen");
    }

    if (mutator instanceof AddAllNonPortableMutator) {
      for (Iterator it = ((List) wrapper.getObject()).iterator(); it.hasNext();) {
        Object o = it.next();
        Assert.assertFalse("Type: " + wrapper.getObject().getClass() + ", lock: " + lockMode + ", " + o.getClass(),
                           o instanceof Socket);
      }
    }
  }

  private void createLists() throws Exception {
    List data = new ArrayList();
    data.add("v1");
    data.add("v2");
    data.add("v3");

    synchronized (root) {
      for (Class k : listClasses) {
        Wrapper cw = new CollectionWrapper(k, List.class);
        ((List) cw.getObject()).addAll(data);
        root.add(cw);
      }
    }
  }

  protected int await() {
    try {
      return barrier.await();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void visitL1DSOConfig(ConfigVisitor visitor, DSOClientConfigHelper config) {
    config.addNewModule(TIMUtil.COMMONS_COLLECTIONS_3_1, TIMUtil.getVersion(TIMUtil.COMMONS_COLLECTIONS_3_1));

    String testClass = ListLocalStateTestApp.class.getName();
    TransparencyClassSpec spec = config.getOrCreateSpec(testClass);

    config.addIncludePattern(testClass + "$*");
    config.addIncludePattern(GenericLocalStateTestApp.class.getName() + "$*");

    config.addWriteAutolock("* " + testClass + "*.createLists()");
    config.addWriteAutolock("* " + testClass + "*.validate()");
    config.addReadAutolock("* " + testClass + "*.runTest()");

    spec.addRoot("root", "root");
    spec.addRoot("barrier", "barrier");

    config.addReadAutolock("* " + Handler.class.getName() + "*.invokeWithReadLock(..)");
    config.addWriteAutolock("* " + Handler.class.getName() + "*.invokeWithWriteLock(..)");
    config.addWriteAutolock("* " + Handler.class.getName() + "*.setLockMode(..)");
  }

  private static class AddMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      l.add("v4");
    }
  }

  private static class AddAllMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      List anotherList = new ArrayList();
      anotherList.add("v");
      l.addAll(anotherList);
    }
  }

  private static class RemoveMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      l.remove("v1");
    }
  }

  private static class ClearMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      l.clear();
    }
  }

  private static class RemoveAllMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      List a = new ArrayList();
      a.add("v1");
      a.add("v2");
      l.removeAll(a);
    }
  }

  private static class RetainAllMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      List a = new ArrayList();
      a.add("v1");
      a.add("v2");
      l.retainAll(a);
    }
  }

  private static class IteratorRemoveMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      for (Iterator it = l.iterator(); it.hasNext();) {
        it.next();
        it.remove();
      }
    }
  }

  private static class ListIteratorRemoveMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      for (ListIterator it = l.listIterator(); it.hasNext();) {
        it.next();
        it.remove();
      }
    }
  }

  private static class IteratorAddMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      ListIterator it = l.listIterator();
      it.add("v");
    }
  }

  private static class AddAllNonPortableMutator implements Mutator {
    public void doMutate(Object o) {
      List l = (List) o;
      List anotherList = new ArrayList();
      anotherList.add("v4");
      anotherList.add("v5");
      anotherList.add(new Socket());
      anotherList.add("v6");
      l.addAll(anotherList);
    }
  }
}
