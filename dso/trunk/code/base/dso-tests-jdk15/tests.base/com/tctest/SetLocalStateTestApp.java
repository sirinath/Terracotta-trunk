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

import gnu.trove.THashSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;

/**
 * Test to make sure local object state is preserved when TC throws:
 * 
 * UnlockedSharedObjectException ReadOnlyException TCNonPortableObjectError
 * 
 * Set version
 * 
 * INT-186
 * 
 * @author hhuynh
 */
public class SetLocalStateTestApp extends GenericLocalStateTestApp {
  private List<Wrapper> root       = new ArrayList<Wrapper>();
  private CyclicBarrier barrier;
  private Class[]       setClasses = new Class[] { HashSet.class, TreeSet.class, LinkedHashSet.class, THashSet.class };

  public SetLocalStateTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
    barrier = new CyclicBarrier(cfg.getGlobalParticipantCount());
  }

  protected void runTest() throws Throwable {
    if (await() == 0) {
      createSets();
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
        break;
      default:
        throw new RuntimeException("Shouldn't happen");
    }

    if (mutator instanceof AddAllNonPortableMutator) {
      for (Iterator it = ((Set) wrapper.getObject()).iterator(); it.hasNext();) {
        Object o = it.next();
        Assert.assertFalse("Type: " + wrapper.getObject().getClass() + ", lock: " + lockMode + ", " + o.getClass(),
                           o instanceof NonPortable);
      }
    }
  }

  private void createSets() throws Exception {
    Set data = new HashSet();
    data.add("v1");
    data.add("v2");
    data.add("v3");

    synchronized (root) {
      for (Class k : setClasses) {
        Wrapper cw = new CollectionWrapper(k, Set.class);
        ((Set) cw.getObject()).addAll(data);
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

    String testClass = SetLocalStateTestApp.class.getName();
    TransparencyClassSpec spec = config.getOrCreateSpec(testClass);

    config.addIncludePattern(testClass + "$*");
    config.addExcludePattern(testClass + "$NonPortable");
    config.addIncludePattern(GenericLocalStateTestApp.class.getName() + "$*");

    config.addWriteAutolock("* " + testClass + "*.createSets()");
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
      Set s = (Set) o;
      s.add("v4");
    }
  }

  private static class AddAllMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      Set anotherSet = new HashSet();
      anotherSet.add("v");
      s.addAll(anotherSet);
    }
  }

  private static class AddAllNonPortableMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      Set anotherSet = new HashSet();
      anotherSet.add("v4");
      anotherSet.add("v5");
      anotherSet.add("v6");
      anotherSet.add(new NonPortable());
      anotherSet.add("v7");
      s.addAll(anotherSet);
    }
  }

  private static class RemoveMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      s.remove("v1");
    }
  }

  private static class ClearMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      s.clear();
    }
  }

  private static class RemoveAllMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      Set a = new HashSet();
      a.add("v1");
      a.add("v2");
      s.removeAll(a);
    }
  }

  private static class RetainAllMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      Set a = new HashSet();
      a.add("v1");
      a.add("v2");
      s.retainAll(a);
    }
  }

  private static class IteratorRemoveMutator implements Mutator {
    public void doMutate(Object o) {
      Set s = (Set) o;
      for (Iterator it = s.iterator(); it.hasNext();) {
        it.next();
        it.remove();
      }
    }
  }

  private static class NonPortable implements Comparable {
    public int compareTo(Object o) {
      return 1;
    }
  }
}
