/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.nonstop;

import org.terracotta.toolkit.builder.NonStopConfigurationBuilder;
import org.terracotta.toolkit.nonstop.NonStopConfiguration;
import org.terracotta.toolkit.nonstop.NonStopConfigurationFields;
import org.terracotta.toolkit.nonstop.NonStopConfigurationFields.NonStopReadTimeoutBehavior;
import org.terracotta.toolkit.nonstop.NonStopConfigurationFields.NonStopWriteTimeoutBehavior;

import junit.framework.Assert;
import junit.framework.TestCase;

public class NonStopConfigBuilderTest extends TestCase {

  public void testBuild() throws Throwable {
    boolean immediateTimeout = true;
    boolean enabled = true;
    NonStopReadTimeoutBehavior readBehavior = NonStopReadTimeoutBehavior.LOCAL_READS;
    NonStopWriteTimeoutBehavior writeBehavior = NonStopWriteTimeoutBehavior.NO_OP;
    long timeout = 100;

    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();
    builder.immediateTimeout(immediateTimeout);
    builder.enable(enabled);
    builder.nonStopReadTimeoutBehavior(readBehavior);
    builder.nonStopWriteTimeoutBehavior(writeBehavior);
    builder.timeoutMillis(timeout);
    NonStopConfiguration configuration = builder.build();

    Assert.assertEquals(immediateTimeout, configuration.isImmediateTimeoutEnabled());
    Assert.assertEquals(enabled, configuration.isEnabled());
    Assert.assertEquals(readBehavior, configuration.getReadOpNonStopTimeoutBehavior());
    Assert.assertEquals(writeBehavior, configuration.getWriteOpNonStopTimeoutBehavior());
    Assert.assertEquals(timeout, configuration.getTimeoutMillis());
  }

  public void testDefault() {
    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();
    NonStopConfiguration configuration = builder.build();

    Assert.assertEquals(NonStopConfigurationFields.DEFAULT_NON_STOP_IMMEDIATE_TIMEOUT_ENABLED,
                        configuration.isImmediateTimeoutEnabled());
    Assert.assertEquals(NonStopConfigurationFields.DEFAULT_NON_STOP_ENABLED, configuration.isEnabled());
    Assert.assertEquals(NonStopConfigurationFields.DEFAULT_NON_STOP_READ_TIMEOUT_BEHAVIOR,
                        configuration.getReadOpNonStopTimeoutBehavior());
    Assert.assertEquals(NonStopConfigurationFields.DEFAULT_NON_STOP_WRITE_TIMEOUT_BEHAVIOR,
                        configuration.getWriteOpNonStopTimeoutBehavior());
    Assert.assertEquals(NonStopConfigurationFields.DEFAULT_TIMEOUT_MILLIS, configuration.getTimeoutMillis());
  }

  public void testImmediateTimeout() {
    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();
    NonStopConfiguration configuration = builder.immediateTimeout(true).build();

    Assert.assertEquals(true, configuration.isImmediateTimeoutEnabled());

    configuration = builder.immediateTimeout(false).build();
    Assert.assertEquals(false, configuration.isImmediateTimeoutEnabled());
  }

  public void testEnabled() {
    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();
    NonStopConfiguration configuration = builder.enable(true).build();

    Assert.assertEquals(true, configuration.isEnabled());

    configuration = builder.enable(false).build();
    Assert.assertEquals(false, configuration.isEnabled());
  }

  public void testTimeout() {
    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();
    NonStopConfiguration configuration = builder.timeoutMillis(100).build();

    Assert.assertEquals(100, configuration.getTimeoutMillis());

    boolean exception = false;
    try {
      builder.timeoutMillis(-1).build();
      Assert.fail();
    } catch (IllegalArgumentException e) {
      exception = true;
    }

    Assert.assertTrue(exception);
  }

  public void testReadTimeoutBehavior() {
    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();

    NonStopConfiguration configuration = null;
    for (NonStopReadTimeoutBehavior value : NonStopReadTimeoutBehavior.values()) {
      configuration = builder.nonStopReadTimeoutBehavior(value).build();
      Assert.assertEquals(value, configuration.getReadOpNonStopTimeoutBehavior());
    }
  }

  public void testWriteTimeoutBehavior() {
    NonStopConfigurationBuilder builder = new NonStopConfigurationBuilder();

    NonStopConfiguration configuration = null;
    for (NonStopWriteTimeoutBehavior value : NonStopWriteTimeoutBehavior.values()) {
      configuration = builder.nonStopWriteTimeoutBehavior(value).build();
      Assert.assertEquals(value, configuration.getWriteOpNonStopTimeoutBehavior());
    }
  }

}
