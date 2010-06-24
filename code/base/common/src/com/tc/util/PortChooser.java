/**
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.util;

import com.tc.net.EphemeralPorts;
import com.tc.net.EphemeralPorts.Range;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public final class PortChooser {
  public static final int     MAX          = 65535;

  private static final Object VM_WIDE_LOCK = (PortChooser.class.getName() + "LOCK").intern();
  private static final Set    chosen       = new HashSet();
  private static final Random random       = new Random();
  private static final Range  exclude      = EphemeralPorts.getRange();

  public int chooseRandomPort() {
    synchronized (VM_WIDE_LOCK) {
      return choose();
    }
  }

  public int chooseRandom2Port() {
    int port;
    synchronized (VM_WIDE_LOCK) {
      do {
        port = choose();
        if (port + 1 >= MAX) continue;
        if (!isPortUsed(port + 1)) {
          chosen.add(new Integer(port + 1));
          break;
        }
      } while (true);
    }
    return port;
  }

  public int chooseRandomPorts(int numOfPorts) {
    Assert.assertTrue(numOfPorts > 0);
    int port = 0;
    synchronized (VM_WIDE_LOCK) {
      do {
        port = choose();
        boolean isChosen = true;
        for (int i = 1; i < numOfPorts; i++) {
          if (isPortUsed(port + i)) {
            isChosen = false;
            break;
          }
        }
        if (isChosen && (port + numOfPorts <= MAX)) {
          break;
        }
      } while (true);

      for (int i = 1; i < numOfPorts; i++) {
        chosen.add(new Integer(port + i));
      }
    }
    return port;
  }

  public boolean isPortUsed(int portNum) {
    final Integer port = new Integer(portNum);
    if (chosen.contains(port)) return true;
    return !canBind(portNum);
  }

  private boolean canBind(int portNum) {
    ServerSocket ss = null;
    boolean isFree = false;
    try {
      ss = new ServerSocket(portNum);
      isFree = true;
    } catch (BindException be) {
      isFree = false; // port in use,
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (ss != null) {
        while (!ss.isClosed()) {
          try {
            ss.close();
          } catch (IOException e) {
            // ignore
          }
        }
      }
    }
    return isFree;
  }

  private synchronized int choose() {
    while (true) {
      final Integer attempt = new Integer(getNonEphemeralPort());
      boolean added = chosen.add(attempt);
      if (!added) {
        continue; // already picked at some point, try again
      }
      if (canBind(attempt.intValue())) return (attempt.intValue());
    }
  }

  private static int getNonEphemeralPort() {
    while (true) {
      int p = random.nextInt(MAX - 1024) + 1024;
      if (p < exclude.getLower() || p > exclude.getUpper()) { return p; }
    }
  }

}
