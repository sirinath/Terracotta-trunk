/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.statistics.config.impl;

import com.tc.statistics.beans.impl.StatisticsEmitterMBeanImpl;
import com.tc.statistics.config.StatisticsConfig;
import com.tc.statistics.config.impl.StatisticsConfigImpl;
import com.tc.statistics.retrieval.StatisticsRetriever;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import junit.framework.TestCase;

public class StatisticsConfigImplTest extends TestCase {
  public void testInstantiation() throws Exception {
    StatisticsConfig config = new StatisticsConfigImpl();
    assertNull(config.getParent());
  }

  public void testValuesOfInvalidKeys() throws Exception {
    StatisticsConfig config = new StatisticsConfigImpl();
    assertNull(config.getParam("invalid"));
    assertEquals(0, config.getParamLong("invalid"));
    assertEquals(null, config.getParamString("invalid"));
  }

  public void testDefaultValues() throws Exception {
    StatisticsConfig config = new StatisticsConfigImpl();
    assertEquals(StatisticsRetriever.DEFAULT_GLOBAL_FREQUENCY.longValue(), config.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));
    assertEquals(StatisticsEmitterMBeanImpl.DEFAULT_FREQUENCY.longValue(), config.getParamLong(StatisticsConfig.KEY_EMITTER_SCHEDULE_INTERVAL));

    config.setParam(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL, new Long(Long.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, config.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));
    config.removeParam(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL);
    assertEquals(StatisticsRetriever.DEFAULT_GLOBAL_FREQUENCY.longValue(), config.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));

    config.setParam(StatisticsConfig.KEY_EMITTER_SCHEDULE_INTERVAL, new Long(Long.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, config.getParamLong(StatisticsConfig.KEY_EMITTER_SCHEDULE_INTERVAL));
    config.removeParam(StatisticsConfig.KEY_EMITTER_SCHEDULE_INTERVAL);
    assertEquals(StatisticsEmitterMBeanImpl.DEFAULT_FREQUENCY.longValue(), config.getParamLong(StatisticsConfig.KEY_EMITTER_SCHEDULE_INTERVAL));
  }

  public void testMappingsForAllDefaultValueKeys() throws Exception {
    StatisticsConfig config = new StatisticsConfigImpl();

    Field[] fields = StatisticsConfig.class.getDeclaredFields();
    for (int i = 0; i < fields.length; i++) {
      int modifiers = fields[i].getModifiers();
      if (Modifier.isStatic(modifiers)
          && Modifier.isPublic(modifiers)
          && Modifier.isFinal(modifiers)
          && fields[i].getType() == String.class) {
        String key = (String)fields[i].get(null);
        assertNotNull("Not all the default parameter keys have values assigned to them, please look at all the constants fields in "
                      +StatisticsConfig.class.getName()
                      +" and make sure that the corresponding default values are properly setup in the constuctor of "
                      +StatisticsConfigImpl.class.getName()+".",
          config.getParam(key));
      }
    }
  }

  public void testSetParam() throws Exception {
    StatisticsConfig config = new StatisticsConfigImpl();
    assertNull(config.getParam("param1"));
    config.setParam("param1", "stringvalue");
    assertEquals("stringvalue", config.getParam("param1"));
    assertEquals("stringvalue", config.getParamString("param1"));
    try {
      config.getParamLong("param1");
      fail("exception expected");
    } catch (ClassCastException e) {
      // expected
    }

    assertNull(config.getParam("param2"));
    config.setParam("param2", new Long(Long.MAX_VALUE));
    assertEquals(new Long(Long.MAX_VALUE), config.getParam("param2"));
    assertEquals(String.valueOf(Long.MAX_VALUE), config.getParamString("param2"));
    assertEquals(Long.MAX_VALUE, config.getParamLong("param2"));
  }

  public void testRemoveParam() throws Exception {
    StatisticsConfig config = new StatisticsConfigImpl();
    config.setParam("param1", "value1");
    assertEquals("value1", config.getParam("param1"));
    config.removeParam("param1");
    assertNull(config.getParam("param1"));
  }

  public void testChildren() throws Exception {
    StatisticsConfig config1 = new StatisticsConfigImpl();
    StatisticsConfig config2a = config1.createChild();
    StatisticsConfig config2b = config1.createChild();
    StatisticsConfig config3a = config2a.createChild();

    // check the parents
    assertNull(config1.getParent());
    assertSame(config1, config2a.getParent());
    assertSame(config1, config2b.getParent());
    assertSame(config2a, config3a.getParent());

    // regular params
    config1.setParam("param1", "val1");
    config2a.setParam("param1", "val2a");
    config2b.setParam("param1", "val2b");
    config3a.setParam("param1", "val3a");

    assertEquals("val1", config1.getParam("param1"));
    assertEquals("val2a", config2a.getParam("param1"));
    assertEquals("val2b", config2b.getParam("param1"));
    assertEquals("val3a", config3a.getParam("param1"));

    config3a.removeParam("param1");
    assertEquals("val2a", config3a.getParam("param1"));

    config2a.removeParam("param1");
    assertEquals("val1", config2a.getParam("param1"));

    config2b.removeParam("param1");
    assertEquals("val1", config2b.getParam("param1"));

    // default params
    assertEquals(StatisticsRetriever.DEFAULT_GLOBAL_FREQUENCY.longValue(), config3a.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));
    config2a.setParam(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL, new Long(Long.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, config3a.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));
    config2a.removeParam(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL);
    assertEquals(StatisticsRetriever.DEFAULT_GLOBAL_FREQUENCY.longValue(), config3a.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));
    config2b.setParam(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL, new Long(Long.MAX_VALUE));
    assertEquals(StatisticsRetriever.DEFAULT_GLOBAL_FREQUENCY.longValue(), config3a.getParamLong(StatisticsConfig.KEY_RETRIEVER_SCHEDULE_INTERVAL));
  }
}