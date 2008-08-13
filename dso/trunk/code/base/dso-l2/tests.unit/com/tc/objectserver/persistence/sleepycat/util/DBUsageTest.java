/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat.util;

import com.tc.objectserver.persistence.sleepycat.AbstractDBUtilsTestBase;
import com.tc.objectserver.persistence.sleepycat.SleepycatPersistor;
import com.tc.objectserver.persistence.sleepycat.util.DBUsage;

import java.io.File;

public class DBUsageTest extends AbstractDBUtilsTestBase {

  public void testSleepycatDBUsageTest() throws Exception {

    File databaseDir = new File(getTempDirectory().toString() + File.separator + "db-data-test1");
    databaseDir.mkdirs();

    SleepycatPersistor sleepycatPersistor = getSleepycatPersistor(databaseDir);
    populateSleepycatDB(sleepycatPersistor);
    sleepycatPersistor.close();

    DBUsage sleepycatDBUsage_test1 = new DBUsage(databaseDir);
    sleepycatDBUsage_test1.report();

    assertTrue(sleepycatDBUsage_test1.getTotalCount() > 0);
    assertTrue(sleepycatDBUsage_test1.getKeyTotal() > 0);
    assertTrue(sleepycatDBUsage_test1.getValuesTotal() > 0);
    assertTrue(sleepycatDBUsage_test1.getGrandTotal() > 0);

    databaseDir = new File(getTempDirectory().toString() + File.separator + "db-data-test2");
    databaseDir.mkdirs();
    sleepycatPersistor = getSleepycatPersistor(databaseDir);
    sleepycatPersistor.close();

    // db is not populated
    DBUsage sleepycatDBUsage_test2 = new DBUsage(databaseDir);
    sleepycatDBUsage_test2.report();

    assertTrue(sleepycatDBUsage_test2.getTotalCount() > 0);
    assertTrue(sleepycatDBUsage_test2.getKeyTotal() > 0);
    assertTrue(sleepycatDBUsage_test2.getValuesTotal() > 0);
    assertTrue(sleepycatDBUsage_test2.getGrandTotal() > 0);

    assertTrue(sleepycatDBUsage_test1.getGrandTotal() >= sleepycatDBUsage_test2.getGrandTotal());
    assertTrue(sleepycatDBUsage_test1.getKeyTotal() >= sleepycatDBUsage_test2.getKeyTotal());
    assertTrue(sleepycatDBUsage_test1.getTotalCount() >= sleepycatDBUsage_test2.getTotalCount());
    assertTrue(sleepycatDBUsage_test1.getValuesTotal() >= sleepycatDBUsage_test2.getValuesTotal());

  }

}
