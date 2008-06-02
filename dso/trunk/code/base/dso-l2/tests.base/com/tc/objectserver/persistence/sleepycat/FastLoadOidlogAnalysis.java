/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.objectserver.persistence.sleepycat;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.tc.util.Conversion;
import com.tc.util.OidLongArray;

import java.io.File;
import java.util.Iterator;
import java.util.List;

public class FastLoadOidlogAnalysis {

  private static final int  LEFT   = 1;
  private static final int  RIGHT  = 2;
  private static final int  CENTER = 3;

  private EnvironmentConfig enc;
  private Environment       env;
  private DatabaseConfig    dbc;

  public FastLoadOidlogAnalysis(File dir) throws Exception {
    enc = new EnvironmentConfig();
    enc.setReadOnly(true);
    env = new Environment(dir, enc);
    dbc = new DatabaseConfig();
    dbc.setReadOnly(true);
  }

  private OidlogsStats analyzeOidLogs(String dbName, Database db) throws DatabaseException {
    CursorConfig config = new CursorConfig();
    Cursor c = db.openCursor(null, config);
    OidlogsStats stats = new OidlogsStats(dbName);
    DatabaseEntry key = new DatabaseEntry();
    DatabaseEntry value = new DatabaseEntry();
    while (OperationStatus.SUCCESS.equals(c.getNext(key, value, LockMode.DEFAULT))) {
      stats.record(key.getData(), value.getData());
    }
    c.close();
    return (stats);
  }

  private void reportOidlog(OidlogsStats stats) {
    log(" ");
    log("\nAnalysis of Oidlogs databases :\n================================\n");
    log("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
    log("DBName", "# ADD", "# DEL", "Start Sequence", "End Sequence");
    log("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
    log(stats.getDatabaseName(), String.valueOf(stats.getAddCount()), String.valueOf(stats.getDeleteCount()), String
        .valueOf(stats.getStartSeqence()), String.valueOf(stats.getEndSequence()));
    log("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
  }

  public void report() throws DatabaseException {
    List dbs = env.getDatabaseNames();

    for (Iterator i = dbs.iterator(); i.hasNext();) {
      String dbName = (String) i.next();
      if (dbName.equals("oidLogs")) {
        Database db = env.openDatabase(null, dbName, dbc);
        OidlogsStats stats = analyzeOidLogs(dbName, db);
        db.close();
        reportOidlog(stats);
      }
    }
  }

  private static void log(String nameHeader, String countHeader, String keyHeader, String valueHeader, String sizeHeader) {
    log(format(nameHeader, 20, LEFT) + format(countHeader, 10, RIGHT) + format(keyHeader, 30, CENTER)
        + format(valueHeader, 30, CENTER) + format(sizeHeader, 15, RIGHT));
  }

  private static String format(String s, int size, int justification) {
    if (s == null || s.length() >= size) { return s; }
    int diff = size - s.length();
    if (justification == LEFT) {
      return s + createSpaces(diff);
    } else if (justification == RIGHT) {
      return createSpaces(diff) + s;
    } else {
      return createSpaces(diff / 2) + s + createSpaces(diff - (diff / 2));
    }
  }

  private static String createSpaces(int i) {
    StringBuffer sb = new StringBuffer();
    while (i-- > 0) {
      sb.append(' ');
    }
    return sb.toString();
  }

  public static void main(String[] args) {
    if (args == null || args.length < 1) {
      usage();
      System.exit(1);
    }

    try {
      File dir = new File(args[0]);
      validateDir(dir);
      // db usage
      SleepycatDBUsage reporter = new SleepycatDBUsage(dir);
      reporter.report();
      // OidLogs analysis
      FastLoadOidlogAnalysis analysis = new FastLoadOidlogAnalysis(dir);
      analysis.report();
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(2);
    }
  }

  private static void validateDir(File dir) {
    if (!dir.exists() || !dir.isDirectory()) { throw new RuntimeException("Not a valid directory : " + dir); }
  }

  private static void usage() {
    log("Usage: FastLoadOidlogAnalysis <environment home directory>");
  }

  private static void log(String message) {
    System.out.println(message);
  }

  private static final class OidlogsStats {
    private long         addCount;
    private long         deleteCount;
    private long         startSequence;
    private long         endSequence;
    private final String databaseName;

    private boolean      hasStartSeq = false;

    public OidlogsStats(String databaseName) {
      this.databaseName = databaseName;
    }

    public String getDatabaseName() {
      return databaseName;
    }

    public long getAddCount() {
      return addCount;
    }

    public long getDeleteCount() {
      return deleteCount;
    }

    public long getStartSeqence() {
      return startSequence;
    }

    public long getEndSequence() {
      return endSequence;
    }

    public void record(byte[] key, byte[] values) {

      if (isAddOper(key)) {
        ++addCount;
      } else {
        ++deleteCount;
      }

      if (!hasStartSeq) {
        startSequence = Conversion.bytes2Long(key);
        hasStartSeq = true;
      } else {
        endSequence = Conversion.bytes2Long(key);
      }

    }

    private boolean isAddOper(byte[] key) {
      return (key[OidLongArray.BYTES_PER_LONG] == 0);
    }

  }

}
