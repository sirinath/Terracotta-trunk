/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest;

import EDU.oswego.cs.dl.util.concurrent.CyclicBarrier;

import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tc.util.Assert;
import com.tctest.runner.AbstractTransparentApp;

import java.util.Hashtable;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Vector;

public class LinkedHashMapClassAdapterTestApp extends AbstractTransparentApp {

  private final CyclicBarrier barrier;  
  private final Map linkedHashMap;

  public LinkedHashMapClassAdapterTestApp(String appId, ApplicationConfig cfg, ListenerProvider listenerProvider) {
    super(appId, cfg, listenerProvider);
    barrier = new CyclicBarrier(getParticipantCount());
    linkedHashMap = new CustomLinkedHashMap();
  }

  public static void visitL1DSOConfig(final ConfigVisitor visitor, final DSOClientConfigHelper config) {
    config.addWriteAutolock("* *..*.*(..)");

    config.getOrCreateSpec(Element.class.getName());
    config.addWriteAutolock("* " + Element.class.getName() + "*.*(..)");
    
    config.getOrCreateSpec(CyclicBarrier.class.getName());
    config.addWriteAutolock("* " + CyclicBarrier.class.getName() + "*.*(..)");
    config.addWriteAutolock("* " + Hashtable.class.getName() + "*.*(..)");
    config.addWriteAutolock("* " + Vector.class.getName() + "*.*(..)");

    config.getOrCreateSpec(CustomLinkedHashMap.class.getName());
    config.addWriteAutolock("* " + CustomLinkedHashMap.class.getName() + "*.*(..)");
    
    final String testClass = LinkedHashMapClassAdapterTestApp.class.getName();
    final TransparencyClassSpec spec = config.getOrCreateSpec(testClass);
    spec.addRoot("barrier", "barrier");
    spec.addRoot("linkedHashMap", "linkedHashMap");
  }

  public void run() {
    try {
      putTesting();
      getTesting();
      removeTesting();
      clearTesting();
      barrier.barrier();
    } catch (Throwable t) {
      notifyError(t);
    }
  }
 
  private void putTesting() throws Exception {
    Element elem1 = new Element("key1", "value1"); 
    Element elem2 = new Element("key2", "value2"); 
    Element elem3 = new Element("key3", "value3"); 
    put(elem1);
    put(elem2);
    put(elem3);
    Assert.assertTrue(linkedHashMap.containsKey("key1"));
    Assert.assertTrue(linkedHashMap.containsValue(elem1));
    Assert.assertTrue(linkedHashMap.containsKey("key2"));
    Assert.assertTrue(linkedHashMap.containsValue(elem2));
    Assert.assertTrue(linkedHashMap.containsKey("key3"));
    Assert.assertTrue(linkedHashMap.containsValue(elem3));
    barrier.barrier();
  }

  private void getTesting() throws Exception {
    String expected = "value1"; 
    Element actual = get("key1");
    Assert.assertEquals(expected, actual.getValue());
    barrier.barrier();
  }

  private void removeTesting() throws Exception {
    synchronized(linkedHashMap) {
      linkedHashMap.remove("key1");
      Element elem = new Element("key1", "value1"); 
      Assert.assertFalse(linkedHashMap.containsKey(elem.getKey()));
      Assert.assertFalse(linkedHashMap.containsValue(elem));
    }
    barrier.barrier();
  }

  private void clearTesting() throws Exception {
    synchronized(linkedHashMap) {
      linkedHashMap.clear();
      Assert.assertTrue(linkedHashMap.isEmpty());
    }
    barrier.barrier();
  }

  private void put(final Element element) {
    synchronized(linkedHashMap) {
      linkedHashMap.put(element.getKey(), element);
    }
  }
  
  private Element get(final String key) {
    synchronized(linkedHashMap) {
      return (Element)linkedHashMap.get(key);
    }
  }
  
  private static final class CustomLinkedHashMap extends LinkedHashMap {
    //private static final int INITIAL_CAPACITY = 100;
    //private static final float GROWTH_FACTOR = .75F;

    public CustomLinkedHashMap() {
        super(100, .75F, true);
    }
    
    protected final boolean removeEldestEntry(Map.Entry eldest) {
      Element element = (Element)eldest.getValue();
      return (element.getValue() == null);
    }
  }
  
  private final class Element {
    private final String key;
    private final Object value;
    
    public Element(final String key, final Object value) {
      this.key = key;
      this.value = value;
    }
    
    public String getKey() {
      return this.key;
    }
    
    public Object getValue() {
      return this.value;
    }
  }
}