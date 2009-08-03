/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tctest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;

public class InvalidClassBytesTestAgent implements ClassFileTransformer {

  public static final byte[] MAGIC = new byte[] { 6, 6, 6 };
  public static final byte[] REAL  = getRealBytes();

  public static void premain(String agentArgs, Instrumentation inst) {
    inst.addTransformer(new InvalidClassBytesTestAgent());
  }

  private static byte[] getRealBytes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    InputStream in = InvalidClassBytesTestAgent.class.getClassLoader()
        .getResourceAsStream(InvalidClassBytesTestAgent.class.getName().replace('.', '/').concat(".class"));
    try {
      int read;
      while ((read = in.read()) >= 0) {
        out.write(read);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return out.toByteArray();
  }

  public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                          ProtectionDomain protectionDomain, byte[] classfileBuffer) {
    // an attempt to fix a deadlock seen in the monkey (MNK-1259)
    if (loader == null) return null;

    if (Arrays.equals(MAGIC, classfileBuffer)) {
      System.err.println("\nMagic found!\n");
      return REAL;
    }

    return null;
  }
}