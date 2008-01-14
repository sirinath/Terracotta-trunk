/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.dso.locks;

import com.tc.management.lock.stats.LockSpec;
import com.tc.management.lock.stats.LockStats;
import com.tc.management.lock.stats.LockTraceElement;

import java.util.ArrayList;
import java.util.Iterator;

public class LockSpecNode extends BasicLockNode {
  LockSpec   fLockSpec;
  LockNode[] fChildren;
  String     fLabel;
  
  LockSpecNode(LockSpec spec) {
    fLockSpec = spec;

    ArrayList<LockTraceElementNode> list = new ArrayList<LockTraceElementNode>();
    Iterator<LockTraceElement> traceElementIter = spec.children().iterator();
    while (traceElementIter.hasNext()) {
      list.add(new LockTraceElementNode(traceElementIter.next()));
    }
    fChildren = list.toArray(new LockTraceElementNode[0]);

    fLabel = fLockSpec.getLockID().asString();
    String objectType = fLockSpec.getObjectType();
    if(objectType != null && objectType.length() > 0) {
      fLabel += " (" + objectType + ")";
    }
  }

  public LockSpec getSpec() {
    return fLockSpec;
  }

  public String getName() {
    return fLabel;
  }

  public LockStats getStats() {
    return fLockSpec.getClientStats();
  }

  public LockNode[] children() {
    return fChildren;
  }

  public String toString() {
    return fLabel;
  }
}
