/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package org.terracotta.ui.session;

import org.apache.commons.io.CopyUtils;
import org.apache.commons.io.IOUtils;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.terracotta.ui.session.pattern.PatternHelper;
import org.terracotta.ui.session.servers.ServerSelection;

import com.tc.config.Loader;
import com.tc.config.schema.migrate.ConfigUpdateException;
import com.tc.object.tools.BootJarSignature;
import com.terracottatech.config.AdditionalBootJarClasses;
import com.terracottatech.config.Application;
import com.terracottatech.config.DsoApplication;
import com.terracottatech.config.Include;
import com.terracottatech.config.InstrumentedClasses;
import com.terracottatech.config.Server;
import com.terracottatech.config.Servers;
import com.terracottatech.config.TcConfigDocument;
import com.terracottatech.config.TransientFields;
import com.terracottatech.config.WebApplication;
import com.terracottatech.config.WebApplications;
import com.terracottatech.config.TcConfigDocument.TcConfig;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

/*
 * TODO: factor out a BaseConfigurationHelper that is shared with the Eclipse plugin's ConfigurationHelper and anyone
 * else that needs basic logic about the config, such as isTransient(String fieldName).
 */

public class ConfigHelper {
  private Loader                configLoader;
  private XmlOptions            xmlOptions;
  private File                  configFile;
  private TcConfig              config;
  private PropertyChangeSupport propertyChangeSupport;

  private static final String   TC_INSTALL_DIR       = SessionIntegratorFrame.getTCInstallDir();

  private static final String   TOMCAT_SANDBOX       = SessionIntegratorFrame.getSandBoxRoot();

  private static final String   FS                   = System.getProperty("file.separator");

  private static final String   CUSTOM_BOOT_JAR_PATH = TC_INSTALL_DIR + FS + "lib" + FS + "dso-boot" + FS
                                                       + getBootJarNameForThisVM();

  private static final int      DEFAULT_JMX_PORT     = 9520;

  public static final String    PROP_CONFIG          = "Config";

  public static final TcConfig  BAD_CONFIG           = TcConfig.Factory.newInstance();

  public ConfigHelper() {
    super();
    init();
  }

  public ConfigHelper(ServerSelection selection) {
    super();
    init();
    configLoader = new Loader();
    xmlOptions = createXmlOptions();
    configFile = new File(TOMCAT_SANDBOX + FS + selection.getSelectedServer().getName() + FS + "tc-config.xml");
    testUpdateConfig();
  }

  private void init() {
    propertyChangeSupport = new PropertyChangeSupport(this);
  }

  public synchronized void addPropertyChangeListener(PropertyChangeListener listener) {
    if (listener == null || propertyChangeSupport == null) return;
    propertyChangeSupport.removePropertyChangeListener(listener);
    propertyChangeSupport.addPropertyChangeListener(listener);
  }

  public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
    if (listener == null || propertyChangeSupport == null) return;
    propertyChangeSupport.removePropertyChangeListener(listener);
  }

  public void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
    if (propertyChangeSupport != null) {
      propertyChangeSupport.firePropertyChange(propertyName, oldValue, newValue);
    }
  }

  private void testUpdateConfig() {
    try {
      if (configFile.exists()) {
        if (configLoader.testIsOld(configFile)) {
          configLoader.updateToCurrent(configFile);
        }
      }
    } catch (ConfigUpdateException cue) {
      // TODO: we need to handle this
    } catch (Exception e) {/**/
    }
  }

  public String getConfigFilePath() {
    return configFile.getAbsolutePath();
  }

  public File getConfigFile() {
    return configFile;
  }

  public TcConfig getConfig() {
    return config;
  }

  public void setConfig(TcConfig newConfig) {
    TcConfig oldConfig = config;
    firePropertyChange(PROP_CONFIG, oldConfig, config = newConfig);
  }

  public TcConfig ensureConfig() {
    TcConfig theConfig = getConfig();
    if (theConfig == null) {
      try {
        theConfig = load();
      } catch (Exception e) {
        theConfig = BAD_CONFIG;
      }
      setConfig(theConfig);
    }
    return theConfig;
  }

  public int getJmxPort() {
    TcConfig theConfig = ensureConfig();
    Servers servers = theConfig.getServers();
    if (servers == null) {
      servers = theConfig.addNewServers();
    }
    if (servers.sizeOfServerArray() == 0) {
      servers.addNewServer();
      save();
    }
    Server server = servers.getServerArray(0);
    int port = server.isSetJmxPort() ? server.getJmxPort() : DEFAULT_JMX_PORT;
    return port;
  }

  public DsoApplication getDsoApplication() {
    TcConfig theConfig = getConfig();
    if (theConfig != null) {
      Application app = theConfig.getApplication();
      return app != null ? app.getDso() : null;
    }
    return null;
  }

  public DsoApplication ensureDsoApplication() {
    DsoApplication dsoApp = null;
    TcConfig theConfig = ensureConfig();
    if (theConfig != null) {
      Application app = theConfig.getApplication();
      if (app == null) {
        app = theConfig.addNewApplication();
      }
      if ((dsoApp = app.getDso()) == null) {
        dsoApp = app.addNewDso();
        dsoApp.addNewInstrumentedClasses();
      }
    }
    return dsoApp;
  }

  private TcConfig load() throws Exception {
    File theConfigFile = getConfigFile();
    TcConfigDocument configDoc = configLoader.parse(theConfigFile, xmlOptions);
    return configDoc.getTcConfig();
  }

  /**
   * @see ConfigTextPane.hasErrors
   */
  public List validate(String xmlText) {
    TcConfigDocument configDoc = null;
    TcConfig theConfig = null;
    List errors = new ArrayList();

    try {
      configDoc = configLoader.parse(xmlText, xmlOptions);
      theConfig = configDoc.getTcConfig();
    } catch (XmlException e) {
      errors.addAll(e.getErrors());
    } catch (IOException ioe) {
      /**/
    }

    if (theConfig != null) {
      xmlOptions.setErrorListener(errors);
      configDoc.validate(xmlOptions);
      xmlOptions.setErrorListener(null);
    } else {
      theConfig = BAD_CONFIG;
    }
    setConfig(theConfig);

    return errors;
  }

  public String configDocumentAsString(TcConfigDocument configDoc) {
    Reader reader = configDoc.newReader(getXmlOptions());
    StringWriter writer = new StringWriter();
    try {
      CopyUtils.copy(reader, writer);
    } catch (IOException ioe) {/**/
    }
    return writer.toString();
  }

  public void save() {
    TcConfigDocument configDoc = TcConfigDocument.Factory.newInstance();
    Reader reader = null;
    Writer writer = null;

    try {
      if (config != null) {
        configDoc.setTcConfig(config);
        reader = configDoc.newReader(getXmlOptions());
        writer = new FileWriter(configFile);
        CopyUtils.copy(reader, writer);
      }
    } catch (Exception e) {
      openError("Error saving '" + configFile.getName() + "'", e);
    } finally {
      IOUtils.closeQuietly(reader);
      IOUtils.closeQuietly(writer);
    }
  }

  public String getConfigText() {
    TcConfig theConfig = getConfig();
    Reader reader = null;
    String text = null;

    try {
      if (theConfig != null) {
        TcConfigDocument configDoc = TcConfigDocument.Factory.newInstance();
        configDoc.setTcConfig(theConfig);
        text = configDocumentAsString(configDoc);
      }
    } catch (Exception e) {
      openError("Error getting config text", e);
    } finally {
      IOUtils.closeQuietly(reader);
    }

    return text;
  }

  public void save(String xmlText) {
    TcConfigDocument configDoc = null;
    Reader reader = null;
    Writer writer = null;
    TcConfig theConfig = BAD_CONFIG;

    try {
      configDoc = configLoader.parse(xmlText, xmlOptions);
      theConfig = configDoc.getTcConfig();
      if (theConfig != null) {
        reader = configDoc.newReader(getXmlOptions());
        writer = new FileWriter(configFile);
        CopyUtils.copy(reader, writer);
      }
    } catch (Exception e) {
      openError("Error saving '" + configFile.getName() + "'", e);
    } finally {
      IOUtils.closeQuietly(reader);
      IOUtils.closeQuietly(writer);
    }
    setConfig(theConfig);
  }

  public void saveAs(File file, String xmlText) {
    TcConfigDocument configDoc = null;
    Reader reader = null;
    Writer writer = null;
    TcConfig theConfig = BAD_CONFIG;

    try {
      configDoc = configLoader.parse(xmlText, xmlOptions);
      theConfig = configDoc.getTcConfig();

      if (theConfig != null) {
        reader = configDoc.newReader(getXmlOptions());
        writer = new FileWriter(file);
        CopyUtils.copy(reader, writer);
      }
    } catch (Exception e) {
      openError("Error saving '" + file.getName() + "'", e);
    } finally {
      IOUtils.closeQuietly(reader);
      IOUtils.closeQuietly(writer);
    }

    setConfig(theConfig);
  }

  public void openError(final String msg, final Throwable t) {
    if (SwingUtilities.isEventDispatchThread()) {
      ErrorDialog d = new ErrorDialog(msg, t);
      d.setVisible(true);
    } else {
      SwingUtilities.invokeLater(new Runnable() {
        public void run() {
          ErrorDialog d = new ErrorDialog(msg, t);
          d.setVisible(true);
        }
      });
    }
  }

  public static String getCustomBootJarPath() {
    return CUSTOM_BOOT_JAR_PATH;
  }

  public static File getCustomBootJarFile() {
    return new File(getCustomBootJarPath());
  }

  public boolean ensureWebApplication(String name) {
    DsoApplication dsoApp = ensureDsoApplication();

    if (dsoApp != null) {
      WebApplications apps = dsoApp.getWebApplications();

      if (apps == null) {
        apps = dsoApp.addNewWebApplications();
      }

      WebApplication[] webApps = apps.getWebApplicationArray();
      for (int i = 0; i < webApps.length; i++) {
        if (webApps[i].getStringValue().equals(name)) { return false; }
      }

      WebApplication webApp = apps.addNewWebApplication();
      webApp.setStringValue(name);

      return true;
    }

    return false;
  }

  public boolean removeWebApplication(String name) {
    DsoApplication dsoApp = getDsoApplication();

    if (dsoApp != null) {
      WebApplications apps = dsoApp.getWebApplications();

      if (apps != null) {
        WebApplication[] appNames = apps.getWebApplicationArray();

        for (int i = 0; i < appNames.length; i++) {
          if (appNames[i].getStringValue().equals(name)) {
            apps.removeWebApplication(i);
            if (apps.sizeOfWebApplicationArray() == 0) {
              dsoApp.unsetWebApplications();
            }
            return true;
          }
        }
      }
    }

    return false;
  }

  public InstrumentedClasses ensureInstrumentedClasses() {
    DsoApplication dsoApp = ensureDsoApplication();
    if (!dsoApp.isSetInstrumentedClasses()) {
      dsoApp.addNewInstrumentedClasses();
    }
    return dsoApp.getInstrumentedClasses();
  }

  public boolean isAdaptable(String classExpr) {
    InstrumentedClasses classes = ensureInstrumentedClasses();
    int size = classes.sizeOfIncludeArray();
    Include include;
    String expr;

    for (int i = 0; i < size; i++) {
      include = classes.getIncludeArray(i);
      expr = include.getClassExpression();

      if (PatternHelper.getHelper().matchesClass(expr, classExpr)) { return true; }
    }

    return false;
  }

  public void ensureAdaptable(String classExpr) {
    if (!isAdaptable(classExpr)) {
      ensureInstrumentedClasses().addNewInclude().setClassExpression(classExpr);
    }
  }

  public Include includeRuleFor(String classExpr) {
    TcConfig theConfig = getConfig();

    if (theConfig != null) {
      InstrumentedClasses classes = ensureInstrumentedClasses();

      if (classes != null) {
        XmlObject[] objects = classes.selectPath("*");
        if (objects != null && objects.length > 0) {
          for (int i = objects.length - 1; i >= 0; i--) {
            XmlObject object = objects[i];
            if (object instanceof Include) {
              String expr = ((Include) object).getClassExpression();
              if (expr != null && expr.equals(classExpr)) { return (Include) object; }
            }
          }
        }
      }
    }

    return null;
  }

  public Include ensureIncludeRuleFor(String classExpr) {
    Include include = includeRuleFor(classExpr);

    if (include == null) {
      include = ensureInstrumentedClasses().addNewInclude();
      include.setClassExpression(classExpr);
    }

    return include;
  }

  public void ensureNotAdaptable(String classExpr) {
    InstrumentedClasses classes = ensureInstrumentedClasses();
    int size = classes.sizeOfIncludeArray();
    Include include;
    String expr;

    for (int i = size - 1; i >= 0; i--) {
      include = classes.getIncludeArray(i);
      expr = include.getClassExpression();

      if (PatternHelper.getHelper().matchesClass(expr, classExpr)) {
        classes.removeInclude(i);
      }
    }

    if (classes.sizeOfExcludeArray() == 0 && classes.sizeOfIncludeArray() == 0) {
      getDsoApplication().unsetInstrumentedClasses();
    }
  }

  public TransientFields ensureTransientFields() {
    DsoApplication dsoApp = ensureDsoApplication();
    if (!dsoApp.isSetTransientFields()) {
      dsoApp.addNewTransientFields();
    }
    return dsoApp.getTransientFields();
  }

  private TransientFields getTransientFields() {
    DsoApplication dsoApp = getDsoApplication();
    return dsoApp != null ? dsoApp.getTransientFields() : null;
  }

  public boolean isTransient(String fieldName) {
    TcConfig theConfig = getConfig();

    if (theConfig != null) {
      TransientFields transients = getTransientFields();
      if (transients != null) {
        for (int i = 0; i < transients.sizeOfFieldNameArray(); i++) {
          if (fieldName.equals(transients.getFieldNameArray(i))) { return true; }
        }
      }
    }

    return false;
  }

  public void ensureTransient(String fieldName) {
    if (!isTransient(fieldName)) {
      ensureTransientFields().addFieldName(fieldName);
    }
  }

  public void ensureNotTransient(String fieldName) {
    TransientFields transients = ensureTransientFields();
    int count = transients.sizeOfFieldNameArray();

    for (int i = 0; i < count; i++) {
      if (fieldName.equals(transients.getFieldNameArray(i))) {
        transients.removeFieldName(i);
        if (transients.sizeOfFieldNameArray() == 0) {
          getDsoApplication().unsetTransientFields();
        }
        return;
      }
    }
  }

  public AdditionalBootJarClasses ensureAdditionalBootJarClasses() {
    DsoApplication dsoApp = ensureDsoApplication();
    if (!dsoApp.isSetAdditionalBootJarClasses()) {
      dsoApp.addNewAdditionalBootJarClasses();
    }
    return dsoApp.getAdditionalBootJarClasses();
  }

  private AdditionalBootJarClasses getAdditionalBootJarClasses() {
    DsoApplication dsoApp = getDsoApplication();
    return dsoApp != null ? dsoApp.getAdditionalBootJarClasses() : null;
  }

  public boolean isBootJarClass(String className) {
    AdditionalBootJarClasses classes = getAdditionalBootJarClasses();

    if (classes != null) {
      String[] includes = classes.getIncludeArray();

      for (int i = 0; i < includes.length; i++) {
        if (PatternHelper.getHelper().matchesClass(includes[i], className)) { return true; }
      }
    }

    return false;
  }

  public void ensureBootJarClass(String typeName) {
    if (!isBootJarClass(typeName)) {
      ensureAdditionalBootJarClasses().addInclude(typeName);
    }
  }

  public void ensureNotBootJarClass(String typeName) {
    AdditionalBootJarClasses bootClasses = getAdditionalBootJarClasses();

    if (bootClasses != null) {
      int count = bootClasses.sizeOfIncludeArray();

      for (int i = 0; i < count; i++) {
        if (typeName.equals(bootClasses.getIncludeArray(i))) {
          bootClasses.removeInclude(i);
          if (bootClasses.sizeOfIncludeArray() == 0) {
            getDsoApplication().unsetAdditionalBootJarClasses();
          }
          return;
        }
      }
    }
  }

  private XmlOptions createXmlOptions() {
    XmlOptions opts = new XmlOptions();

    opts.setLoadLineNumbers();
    opts.setSavePrettyPrint();
    opts.setSavePrettyPrintIndent(2);
    // opts.remove(XmlOptions.LOAD_STRIP_WHITESPACE);
    opts.remove(XmlOptions.LOAD_STRIP_COMMENTS);

    return opts;
  }

  public XmlOptions getXmlOptions() {
    return xmlOptions;
  }

  private static String getBootJarNameForThisVM() {
    try {
      return BootJarSignature.getBootJarNameForThisVM();
    } catch (Exception e) {
      return "dso-boot.jar";
    }
  }
}
