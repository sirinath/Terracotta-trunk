/* 
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at 
 *
 *      http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Platform.
 *
 * The Initial Developer of the Covered Software is 
 *      Terracotta, Inc., a Software AG company
 */
package com.tc.objectserver.impl;

import com.tc.objectserver.api.ObjectManagerStats;
import com.tc.objectserver.api.ObjectManagerStatsListener;
import com.tc.stats.counter.sampled.SampledCounter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Implements the object manager stats
 */
public class ObjectManagerStatsImpl implements ObjectManagerStatsListener, ObjectManagerStats {

  private final AtomicLong     objectsCreated = new AtomicLong();
  private final SampledCounter newObjectCounter;

  public ObjectManagerStatsImpl(SampledCounter newObjectCounter) {
    this.newObjectCounter = newObjectCounter;
  }

  @Override
  public void newObjectCreated() {
    this.objectsCreated.incrementAndGet();
    this.newObjectCounter.increment();
  }

  @Override
  public long getTotalObjectsCreated() {
    return this.objectsCreated.get();
  }

}
