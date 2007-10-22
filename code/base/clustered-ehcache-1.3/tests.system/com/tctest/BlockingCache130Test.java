package com.tctest;

public class BlockingCache130Test extends TransparentTestBase {
  public static final int NODE_COUNT = 3;

  public void doSetUp(final TransparentTestIface tt) throws Exception {
    tt.getTransparentAppConfig().setClientCount(NODE_COUNT);
    tt.initializeTestRunner();
  }

  protected Class getApplicationClass() {
    return BlockingCache130TestApp.class;
  }
}
