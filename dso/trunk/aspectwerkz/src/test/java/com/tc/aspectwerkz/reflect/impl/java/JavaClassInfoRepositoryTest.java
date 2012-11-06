/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.aspectwerkz.reflect.impl.java;

import com.tc.aspectwerkz.reflect.ClassInfo;
import com.tc.util.concurrent.ThreadUtil;

import java.lang.ref.WeakReference;
import java.net.URLClassLoader;

import junit.framework.TestCase;

public class JavaClassInfoRepositoryTest extends TestCase {

  public void testBasic() {
    Loader loader = new Loader();

    for (int i = 0; i < 3; i++) {
      assertTrue(JavaClassInfoRepository.getRepository(loader) == JavaClassInfoRepository.getRepository(loader));
      ClassInfo ci1 = JavaClassInfoRepository.getRepository(loader).getClassInfo(JavaClassInfoRepository.class
                                                                                     .getName());
      System.gc();
      ThreadUtil.reallySleep(1000L);

      ClassInfo ci2 = JavaClassInfoRepository.getRepository(loader).getClassInfo(JavaClassInfoRepository.class
          .getName());
      assertTrue(ci1 == ci2);
    }

    WeakReference<JavaClassInfoRepository> repoRef = new WeakReference<JavaClassInfoRepository>(
                                                                                                JavaClassInfoRepository
                                                                                                    .getRepository(loader));
    loader = null;
    for (int i = 0; i < 3; i++) {
      // we need to exercise the JavaClassInfoRepository's s_repositories guava weak keys map -> make it add a new element
      JavaClassInfoRepository.getRepository(getClass().getClassLoader());

      System.gc();
      ThreadUtil.reallySleep(3000L);
    }

    assertNull(repoRef.get());
    assertEquals(1, JavaClassInfoRepository.repositoriesSize());
  }

  private static class Loader extends URLClassLoader {
    public Loader() {
      super(((URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs(), null);
    }
  }

}
