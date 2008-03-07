/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.statistics.store.exceptions;

import com.tc.statistics.StatisticData;

public class TCStatisticsStoreStatisticStorageErrorException extends TCStatisticsStoreException {
  private final Long id;
  private final StatisticData data;

  public TCStatisticsStoreStatisticStorageErrorException(final long id, final StatisticData data, final Throwable cause) {
    super("Unexpected error while storing the statistic with id '" + id + "' and data " + data + ".", cause);
    this.id = new Long(id);
    this.data = data;
  }

  public TCStatisticsStoreStatisticStorageErrorException(final StatisticData data, final Throwable cause) {
    super("Unexpected error while storing the statistic data " + data + ".", cause);
    this.id = null;
    this.data = data;
  }

  public Long getId() {
    return id;
  }

  public StatisticData getData() {
    return data;
  }
}
