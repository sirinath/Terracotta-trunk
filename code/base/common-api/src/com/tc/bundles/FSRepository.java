/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.bundles;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AndFileFilter;
import org.apache.commons.io.filefilter.PrefixFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.knopflerfish.framework.VersionRange;

import com.tc.logging.TCLogger;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FSRepository implements Repository {

  private final File     repoFile;
  private final TCLogger logger;

  public FSRepository(File repoFile, TCLogger logger) {
    this.repoFile = repoFile;
    this.logger = logger;
  }

  public Collection<URL> search(String groupId, String name, String version) {
    Collection<URL> paths = new ArrayList<URL>();
    String root = ResolverUtils.canonicalize(repoFile);

    VersionRange range = new VersionRange(version.replace('-', '.'));

    for (File dir : getDirs(new File(OSGiToMaven.makeBundlePathnamePrefix(root, groupId, name)))) {
      final Version ver;
      try {
        ver = Version.parse(dir.getName());
      } catch (Exception e) {
        logger.warn("Skipping " + dir.getAbsolutePath() + " in search", e);
        continue;
      }

      if (range.withinRange(ver)) {
        addIfValid(paths, OSGiToMaven.makeBundlePathname(root, groupId, name, dir.getName()));
      }
    }

    // Allow for flat path modules layout
    // NOTE: version range checking not done here (it should happen in the caller)
    for (File file : new File(root).listFiles()) {
      if (file.getName().startsWith(name)) {
        addIfValid(paths, file.getAbsolutePath());
      }
    }

    return paths;
  }

  private Set<File> getDirs(File root) {
    if (!root.isDirectory()) { return Collections.emptySet(); }

    Set<File> rv = new HashSet<File>();
    for (File dirEntry : root.listFiles()) {
      if (dirEntry.isDirectory()) {
        rv.add(dirEntry);
      }
    }

    return rv;
  }

  private void addIfValid(Collection<URL> paths, String bundle) {
    File f = new File(bundle);
    if (isValid(f)) {
      paths.add(toURL(f));
    }
  }

  private static boolean isValid(File bundle) {
    return bundle.exists() && bundle.isFile();
  }

  private static URL toURL(File file) {
    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  public Collection<URL> search(String groupId, String name) {
    String root = ResolverUtils.canonicalize(repoFile);

    logger.info("root = " + root);
    File repoRoot = new File(root);
    if (!repoRoot.exists() || !repoRoot.isDirectory()) {
      logger.info("root directory does not exist");
      return Collections.EMPTY_LIST;
    }

    logger.info("looking for possible flat files");
    // Flat repo - search for jars at root with name as prefix (name-<version>.jar)
    Collection<File> possibles = FileUtils.listFiles(repoRoot, new AndFileFilter(new PrefixFileFilter(name + "-"),
                                                                                 new SuffixFileFilter(".jar")), null);
    logger.info("found flat file possibles = " + possibles.toString());

    // Hierarchical repo - search for jars at groupId/name subdir
    File nameRoot = new File(OSGiToMaven.makeBundlePathnamePrefix(root, groupId, name));
    logger.info("checking hierarchical repo root: " + nameRoot.getAbsolutePath());
    if (nameRoot.exists() && nameRoot.isDirectory()) {
      possibles.addAll(FileUtils.listFiles(nameRoot, new SuffixFileFilter(".jar"), TrueFileFilter.INSTANCE));
      logger.info("found all possibles = " + possibles.toString());
    }

    Collection<URL> rv = new ArrayList<URL>();
    for (File file : possibles) {
      rv.add(toURL(file));
    }

    return rv;
  }

  public String describe() {
    return repoFile.getAbsolutePath();
  }
}
