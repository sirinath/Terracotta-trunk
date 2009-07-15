/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package org.terracotta.modules.tool;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.terracotta.modules.tool.DocumentToAttributes.DependencyType;
import org.terracotta.modules.tool.InstallListener.InstallNotification;
import org.w3c.dom.Element;

import com.google.inject.Inject;
import com.tc.util.version.VersionMatcher;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Module extends AbstractModule implements Installable {

  private final Modules             modules;
  private final Map<String, Object> attributes;
  private final AttributesHelper    attributesHelper;
  private final URI                 relativeUrlBase;

  public Module(Modules modules, Element module, URI relativeUrlBase) {
    this(modules, DocumentToAttributes.transform(module), relativeUrlBase);
  }

  @Inject
  Module(Modules modules, Map<String, Object> attributes, URI relativeUrlBase) {
    this.modules = modules;
    this.attributes = attributes;
    this.relativeUrlBase = relativeUrlBase;
    this.attributesHelper = new AttributesHelper(this.attributes, this.relativeUrlBase);

    groupId = attributesHelper.getAttrValueAsString("groupId", StringUtils.EMPTY);
    artifactId = attributesHelper.getAttrValueAsString("artifactId", StringUtils.EMPTY);
    version = attributesHelper.getAttrValueAsString("version", StringUtils.EMPTY);
  }

  protected Modules owner() {
    return modules;
  }

  public String filename() {
    return attributesHelper.filename();
  }

  public File installPath() {
    return attributesHelper.installPath();
  }

  public URL repoUrl() {
    return attributesHelper.repoUrl();
  }

  public String category() {
    return attributesHelper.getAttrValueAsString("category", StringUtils.EMPTY);
  }

  public String contactAddress() {
    return attributesHelper.getAttrValueAsString("contactAddress", StringUtils.EMPTY);
  }

  public String copyright() {
    return attributesHelper.getAttrValueAsString("copyright", StringUtils.EMPTY);
  }

  public List<AbstractModule> dependencies() {
    List<AbstractModule> list = new ArrayList<AbstractModule>();
    if (attributes.containsKey("dependencies")) {
      List<Map<String, Object>> dependencies = (List<Map<String, Object>>) attributes.get("dependencies");
      for (Map<String, Object> dependencyAttributes : dependencies) {
        DependencyType type = (DependencyType) dependencyAttributes.get("_dependencyType");
        if (DependencyType.INSTANCE.equals(type)) {
          list.add(new BasicModule(this, dependencyAttributes, this.relativeUrlBase));
          continue;
        } else if (DependencyType.REFERENCE.equals(type)) {
          list.add(new Reference(this, dependencyAttributes));
          continue;
        }
        // XXX dependencyType eval'd as DependencyType.UNKNOWN
        // it means bad data if we should ever get here - we should just have schema to make
        // sure that the data being read is valid - instead of trying to catch a bad state here
        throw new IllegalStateException();
      }
    }
    Collections.sort(list);
    return list;
  }

  /**
   * Descriptive text about the module.
   * 
   * @return A String
   */
  public String description() {
    return attributesHelper.getAttrValueAsString("description", StringUtils.EMPTY);
  }

  /**
   * The URL pointing to the documentation for this module
   * 
   * @return An URL. Will return this module's website URL if none was defined.
   */
  public URL docUrl() {
    return attributesHelper.getAttrValueAsUrl("docURL", website());
  }

  public boolean isInstalled() {
    return isInstalled(modules.repository());
  }

  public boolean isInstalled(File repository) {
    return attributesHelper.isInstalled(repository);
  }

  /**
   * Retrieve the latest version available for this module. If this module has no siblings, then it returns itself.
   */
  public Module latest() {
    List<Module> siblings = siblings();
    return siblings.isEmpty() ? this : siblings.get(siblings.size() - 1);
  }

  public boolean isLatest() {
    List<Module> siblings = siblings();
    if (siblings.isEmpty()) return true;

    Module youngest = siblings.get(siblings.size() - 1);
    return youngest.isOlder(this);
  }

  /**
   * Retrieve the siblings of this module. The list returned is sorted in ascending-order, ie: oldest version first. The
   * listed returned DOES NOT include the module itself.
   * 
   * @return a List of Module.
   */
  public List<Module> siblings() {
    return modules.getSiblings(this);
  }

  public String tcProjectStatus() {
    return attributesHelper.getAttrValueAsString("tc-projectStatus", StringUtils.EMPTY);
  }

  public String tcVersion() {
    return attributesHelper.getAttrValueAsString("tc-version", null);
  }

  public String apiVersion() {
    if (attributes.containsKey("api-version")) {
      return attributesHelper.getAttrValueAsString("api-version", null);
    } else {
      return VersionMatcher.ANY_VERSION;
    }
  }

  public String vendor() {
    return attributesHelper.getAttrValueAsString("vendor", StringUtils.EMPTY);
  }

  public boolean tcInternalTIM() {
    return Boolean.valueOf(attributesHelper.getAttrValueAsString("tc-internalTIM", "false"));
  }

  /**
   * Retrieve the list of versions of this module. The list returned is sorted in ascending-order, ie: oldest version
   * first. The list returned DOES NOT include the version of this module.
   */
  public List<String> versions() {
    List<String> list = new ArrayList<String>();
    for (Module module : siblings()) {
      list.add(module.version());
    }
    return list;
  }

  /**
   * The website URL of this module.
   * 
   * @return An URL. Will return a the URL point to the TC Forge if none was defined.
   */
  public URL website() {
    URL alturl = null;
    try {
      alturl = new URL("http://forge.terracotta.org/");
    } catch (MalformedURLException e) {
      //
    }
    return attributesHelper.getAttrValueAsUrl("website", alturl);
  }
  
  /**
   * Currently we are determining this based on whether the tc-downloadPath exists.  This value
   * is usually set to install a download outside the modules directory.
   */
  public boolean installsAsModule() {
    String downloadPath = attributesHelper.getAttrValueAsString("tc-downloadPath", null);
    return downloadPath == null || downloadPath.length() == 0;
  }

  protected List<AbstractModule> manifest() {
    List<AbstractModule> manifest = new ArrayList<AbstractModule>();
    // manifest, at a minimum, includes this module
    manifest.add(this);
    for (AbstractModule dependency : dependencies()) {
      if (dependency instanceof Reference) {
        Module module = modules.get(dependency.groupId(), dependency.artifactId(), dependency.version());
        // XXX bad data happened - maybe we should be more forgiving here
        if (module == null) throw new IllegalStateException("No listing found for dependency: " + dependency);

        // instance of reference located, include entries from its install manifest into the install manifest
        for (AbstractModule entry : module.manifest()) {
          if (!manifest.contains(entry)) manifest.add(entry);
        }
        continue;
      }
      manifest.add(dependency);
    }
    return manifest;
  }

  private void notifyListener(InstallListener listener, AbstractModule source, InstallNotification type, String message) {
    if (listener == null) return;
    listener.notify(source, type, message);
  }

  /**
   * Install this module.
   */
  public void install(InstallListener listener, InstallOption... options) {
    install(listener, Arrays.asList(options));
  }

  public void install(InstallListener listener, Collection<InstallOption> options) {
    InstallOptionsHelper installOptions = new InstallOptionsHelper(options);
    List<AbstractModule> manifest = null;

    notifyListener(listener, this, InstallNotification.STARTING, StringUtils.EMPTY);
    try {
      manifest = manifest();
    } catch (IllegalStateException e) {
      String message = "Unable to compute manifest for installation: " + e.getMessage();
      handleInstallFailure(e, installOptions, listener, this, InstallNotification.ABORTED, message);
      return;
    }

    for (AbstractModule entry : manifest) {
      Installable module = (Installable) entry;
      File destdir = new File(modules.repository(), module.installPath().toString());
      File destfile = new File(destdir, module.filename());
      if (module.isInstalled(modules.repository()) && !installOptions.overwrite()) {
        notifyListener(listener, entry, InstallNotification.SKIPPED, "Already installed");
        continue;
      }

      if (!installOptions.pretend()) {
        File srcfile = null;

        try {
          srcfile = modules.download(module, installOptions.verify(), installOptions.inspect());
        } catch (IOException e) {
          String message = "Attempt to download TIM file at " + module.repoUrl() + " failed - " + e.getMessage();
          handleInstallFailure(e, installOptions, listener, entry, InstallNotification.DOWNLOAD_FAILED, message);
          continue;
        }

        try {
          FileUtils.forceMkdir(destdir);
          FileUtils.copyFile(srcfile, destfile);
        } catch (IOException e) {
          String message = destfile + " (" + e.getMessage() + ")";
          handleInstallFailure(e, installOptions, listener, entry, InstallNotification.INSTALL_FAILED, message);
          continue;
        }
      }

      notifyListener(listener, entry, InstallNotification.INSTALLED, "Ok");
    }
  }

  private void handleInstallFailure(Throwable e, InstallOptionsHelper installOptions, InstallListener listener,
                                    AbstractModule source, InstallNotification type, String message) {
    if (installOptions.failFast()) {
      throw new RuntimeException(e);
    } else {
      notifyListener(listener, source, type, message);
    }
  }
}
