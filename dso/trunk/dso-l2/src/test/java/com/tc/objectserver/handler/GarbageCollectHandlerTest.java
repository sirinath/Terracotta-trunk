/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.handler;

import com.tc.async.api.Stage;
import com.tc.async.impl.MockSink;
import com.tc.async.impl.MockStage;
import com.tc.object.ObjectID;
import com.tc.objectserver.api.GarbageCollectionManager;
import com.tc.objectserver.api.ObjectManager;
import com.tc.objectserver.api.Transaction;
import com.tc.objectserver.context.GarbageCollectContext;
import com.tc.objectserver.context.InlineGCContext;
import com.tc.objectserver.context.PeriodicGarbageCollectContext;
import com.tc.objectserver.core.api.ServerConfigurationContext;
import com.tc.objectserver.dgc.api.GarbageCollector;
import com.tc.objectserver.dgc.api.GarbageCollector.GCType;
import com.tc.objectserver.impl.ObjectManagerConfig;
import com.tc.objectserver.persistence.PersistenceTransactionProvider;
import com.tc.objectserver.tx.TestServerTransactionManager;
import com.tc.objectserver.tx.TxnsInSystemCompletionListener;
import com.tc.test.TCTestCase;
import com.tc.util.ObjectIDSet;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GarbageCollectHandlerTest extends TCTestCase {
  private GarbageCollectHandler    handler;
  private ObjectManager            objectManager;
  private GarbageCollector         gc;
  private GarbageCollectionManager gcManager;
  private MockSink                 gcSink;
  private PersistenceTransactionProvider persistenceTransactionProvider;
  private Transaction transaction;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    persistenceTransactionProvider = mock(PersistenceTransactionProvider.class);
    transaction = mock(Transaction.class);
    when(persistenceTransactionProvider.newTransaction()).thenReturn(transaction);
    handler = new GarbageCollectHandler(new ObjectManagerConfig(10, true, true, false),
        persistenceTransactionProvider);
    gc = mock(GarbageCollector.class);
    objectManager = mock(ObjectManager.class);
    when(objectManager.getGarbageCollector()).thenReturn(gc);
    Stage gcStage = new MockStage(ServerConfigurationContext.GARBAGE_COLLECT_STAGE);
    gcSink = (MockSink) gcStage.getSink();
    gcManager = mock(GarbageCollectionManager.class);
    ServerConfigurationContext scc = mock(ServerConfigurationContext.class);
    when(scc.getObjectManager()).thenReturn(objectManager);
    when(scc.getStage(ServerConfigurationContext.GARBAGE_COLLECT_STAGE)).thenReturn(gcStage);
    when(scc.getGarbageCollectionManager()).thenReturn(gcManager);
    when(scc.getTransactionManager()).thenReturn(new MyServerTransactionManager());
    handler.initialize(scc);
    gcManager.initializeContext(scc);
  }

  public void testScheduleInline() throws Exception {
    ObjectIDSet oids = new ObjectIDSet(Collections.singleton(new ObjectID(1)));
    when(gcManager.nextObjectsToDelete()).thenReturn(oids);
    when(objectManager.deleteObjects(oids)).thenReturn(oids);

    handler.handleEvent(new InlineGCContext());
    verify(gcManager).nextObjectsToDelete();
    verify(persistenceTransactionProvider).newTransaction();
    verify(objectManager).deleteObjects(oids);
    verify(transaction).commit();
    verify(gcManager).missingObjectsToDelete(oids);
    verify(gcManager).scheduleInlineGarbageCollectionIfNecessary();
  }

  public void testSchedulePeriodicDGC() throws Exception {
    handler.handleEvent(new PeriodicGarbageCollectContext(GCType.FULL_GC, 1));
    PeriodicGarbageCollectContext context = (PeriodicGarbageCollectContext)gcSink.take();
    handler.handleEvent(context);
    verify(gc).doGC(GCType.FULL_GC);
    context = (PeriodicGarbageCollectContext)gcSink.take();
    assertThat(context.getInterval(), is(1L));
  }

  public void testScheduleOneOffDGC() throws Exception {
    handler.handleEvent(new GarbageCollectContext(GCType.FULL_GC));
    verify(gc).doGC(GCType.FULL_GC);
  }

  private class MyServerTransactionManager extends TestServerTransactionManager {
    @Override
    public void callBackOnResentTxnsInSystemCompletion(TxnsInSystemCompletionListener l) {
      l.onCompletion();
    }
  }
}
