/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.spring.bean;

public class BarServiceImpl implements FooService {

  private transient BarServiceHelper serviceHelper;

  public BarServiceImpl(BarServiceHelper serviceHelper) {
    this.serviceHelper = serviceHelper;
  }

  public String serviceMethod() {
    return serviceHelper.serviceMethod();

  }

}
