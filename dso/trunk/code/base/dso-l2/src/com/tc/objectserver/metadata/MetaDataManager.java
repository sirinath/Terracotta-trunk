/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.metadata;

import com.tc.object.dna.api.MetaDataReader;
import com.tc.object.tx.ServerTransactionID;
import com.tc.objectserver.tx.ServerTransaction;

/**
 * Manager to process Metadata from a DNA
 * 
 * @author Nabib El-Rahman
 */
public interface MetaDataManager {

  /**
   * Process metadata.
   * 
   * @param ServerTransaction transaction associated with metadata reader.
   * @param MetaDataReader metadata reader associated with a DNA.
   * @return true if any meta data will be processed
   */
  public boolean processMetaDatas(ServerTransaction txn, MetaDataReader[] readers);

  /**
   * Notify MetaDataMaanger that a Meta has been processed for TransactionID.
   * 
   * @return boolean return true of all metadatas associated with @{link TrasnactionID}
   *         has been processed.
   * @param ServerTransactionID id, transaction id metadata belongs to.
   */
  public boolean metaDataProcessingCompleted(ServerTransactionID id);

}
