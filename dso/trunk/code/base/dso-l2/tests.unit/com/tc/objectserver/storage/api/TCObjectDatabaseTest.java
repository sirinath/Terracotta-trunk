/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.objectserver.storage.api;

import org.apache.commons.io.FileUtils;

import com.tc.object.config.schema.L2DSOConfig;
import com.tc.objectserver.storage.api.TCDatabaseReturnConstants.Status;
import com.tc.test.TCTestCase;
import com.tc.util.Assert;

import java.io.File;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;

public class TCObjectDatabaseTest extends TCTestCase {
  private final Random                   random = new Random();
  private File                           dbHome;
  private DBEnvironment                  dbenv;
  private PersistenceTransactionProvider ptp;

  private TCObjectDatabase               database;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    File dataPath = getTempDirectory();

    dbHome = new File(dataPath.getAbsolutePath(), L2DSOConfig.OBJECTDB_DIRNAME);
    dbHome.mkdir();

    dbenv = new DBFactoryForDBUnitTests(new Properties()).createEnvironment(true, dbHome, null, false);
    dbenv.open();

    ptp = dbenv.getPersistenceTransactionProvider();
    database = dbenv.getObjectDatabase();
  }

  public void testInsertUpdateGet() {
    long objectId1 = 1;
    byte[] value1 = getRandomlyFilledByteArray();
    byte[] value2 = getRandomlyFilledByteArray();

    PersistenceTransaction tx = ptp.getOrCreateNewTransaction();
    Status status = database.insert(objectId1, value1, tx);
    tx.commit();
    Assert.assertEquals(Status.SUCCESS, status);

    tx = ptp.getOrCreateNewTransaction();
    byte[] valueFetched = database.get(objectId1, tx);
    tx.commit();
    Assert.assertTrue(Arrays.equals(value1, valueFetched));

    value1 = getRandomlyFilledByteArray();
    tx = ptp.getOrCreateNewTransaction();
    status = database.update(objectId1, value2, tx);
    tx.commit();

    Assert.assertEquals(Status.SUCCESS, status);

    tx = ptp.getOrCreateNewTransaction();
    valueFetched = database.get(objectId1, tx);
    tx.commit();
    Assert.assertTrue(Arrays.equals(value2, valueFetched));
  }

  public void testDelete() {
    long objectId1 = 1;
    byte[] value1 = getRandomlyFilledByteArray();

    PersistenceTransaction tx = ptp.getOrCreateNewTransaction();
    Status status = database.insert(objectId1, value1, tx);
    tx.commit();
    Assert.assertEquals(Status.SUCCESS, status);

    tx = ptp.getOrCreateNewTransaction();
    status = database.delete(objectId1, tx);
    tx.commit();
    Assert.assertEquals(Status.SUCCESS, status);

    tx = ptp.getOrCreateNewTransaction();
    status = database.delete(objectId1, tx);
    tx.commit();
    Assert.assertEquals(Status.NOT_FOUND, status);
  }

  private byte[] getRandomlyFilledByteArray() {
    byte[] array = new byte[100];
    random.nextBytes(array);
    return array;
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    try {
      dbenv.close();
      FileUtils.cleanDirectory(dbHome);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
