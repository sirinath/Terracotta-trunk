/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.context;

import com.tc.async.api.EventContext;
import com.tc.object.ObjectID;
import com.tc.objectserver.api.ObjectManagerLookupResults;

import java.util.Set;

/**
 * Interface for a context that needs ObjectManager look results
 */
public interface ObjectManagerResultsContext extends EventContext {

  public Set getLookupIDs();

  public Set getNewObjectIDs();

  public void setResults(ObjectManagerLookupResults results);

  public void missingObject(ObjectID oid);
}