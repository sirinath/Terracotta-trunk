/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

import com.sleepycat.je.DatabaseException;
import com.tc.exception.TCException;

public class TCDatabaseException extends TCException {
  public TCDatabaseException(DatabaseException cause) {
    super(cause);
  }

  public TCDatabaseException(String message) {
    super(message);
  }
}
