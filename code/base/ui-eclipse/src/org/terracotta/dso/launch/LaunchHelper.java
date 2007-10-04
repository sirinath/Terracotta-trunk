/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package org.terracotta.dso.launch;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.variables.IStringVariableManager;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.terracotta.dso.BootJarHelper;
import org.terracotta.dso.ClasspathProvider;
import org.terracotta.dso.ConfigurationHelper;
import org.terracotta.dso.ServerTracker;
import org.terracotta.dso.TcPlugin;
import org.terracotta.dso.actions.BuildBootJarAction;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Launcher helper for standard DSO applications, Eclipse applications, and JUnit tests. 
 */

public class LaunchHelper implements IDSOLaunchConfigurationConstants {
  private IDSOLaunchDelegate fLaunchDelegate;
  
  LaunchHelper(IDSOLaunchDelegate launchDelegate) {
    fLaunchDelegate = launchDelegate;
  }
  
  public ILaunchConfigurationWorkingCopy setup(
    ILaunchConfiguration config,
    String               mode,
    ILaunch              launch,
    IProgressMonitor     monitor) throws CoreException
  {
    try {
      Display.getDefault().syncExec(new Runnable() {
        public void run() {
          IWorkbench workbench = PlatformUI.getWorkbench();
          if(workbench != null) {
            workbench.saveAllEditors(true);
          }
        }
      });
  
      ILaunchConfigurationWorkingCopy wc          = config.getWorkingCopy();
      final IJavaProject              javaProject = fLaunchDelegate.getJavaProject(wc);
      final IProject                  project     = javaProject.getProject();
      
      if(wc.getAttribute(ATTR_PROJECT_NAME, (String)null) == null) {
        wc.setAttribute(ATTR_PROJECT_NAME, project.getName());
      }
      
      final TcPlugin plugin     = TcPlugin.getDefault();
      String         vmArgs     = wc.getAttribute(ATTR_VM_ARGUMENTS, "");
      IPath          libDirPath = plugin.getLibDirPath();
      
      if (!plugin.continueWithConfigProblems(project)) { return null; }
  
      String serverProp = wc.getAttribute(ID_SERVER_SPEC, (String)null);
      if(serverProp == null || (serverProp = serverProp.trim()) == null || serverProp.length() == 0) {
        String configServerSpec = wc.getAttribute(ID_CONFIG_SERVER_SPEC, (String)null);
        if(configServerSpec == null) {
          ServerTracker tracker = ServerTracker.getDefault();
          boolean autoStartServer = plugin.getAutoStartServerOption(project);
          if(!tracker.anyRunning(javaProject) && (autoStartServer || queryStartServer(project, monitor))) {
            tracker.startServer(javaProject, plugin.getAnyServerName(project), monitor);
          }
          if(monitor.isCanceled()) {
            return null;
          }
        }
      } else {
        vmArgs += "-Dtc.server=" + serverProp;
      }
      
      String configProp = " -Dtc.config=\"" + getConfigSpec(wc) + "\"";
      
      IVMInstall vmInstall = fLaunchDelegate.getVMInstall(wc);
      String vmType = vmInstall.getVMInstallType().getId();
      String vmName = vmInstall.getName();
      String portablePath = ATTR_JRE_CONTAINER_PATH + "/" + vmType + "/" + vmName;
      String jreContainerPath = wc.getAttribute(ATTR_JRE_CONTAINER_PATH, portablePath);
      String bootJarName = BootJarHelper.getHelper().getBootJarName(jreContainerPath);
      
      if(bootJarName == null || bootJarName.length() == 0) {
        throw new RuntimeException("Can't determine BootJar name for runtime '"+vmName+"'");
      }
      
      IFile localBootJar = project.getFile(bootJarName);
      IPath bootPath;
      
      testEnsureBootJar(plugin, javaProject, localBootJar, jreContainerPath);
      
      if(localBootJar.exists()) {
        bootPath = localBootJar.getLocation();
      }
      else {
        bootPath = BootJarHelper.getHelper().getBootJarPath(bootJarName);
      }
      
      String bootProp = " -Xbootclasspath/p:\"" + toOSString(bootPath) + "\"";
  
      if(!bootPath.toFile().exists()) {
        String path = bootPath.toOSString();
        plugin.openError("System bootjar '" + path +
                         "' not found",
                         new RuntimeException("bootjar not found: " + path));
      }
      
      String cpProp;
      if(libDirPath.append("tc.jar").toFile().exists()) {
        cpProp = " -Dtc.install-root=\"" + toOSString(plugin.getLocation()) + "\"";
      }
      else {
        cpProp = " -Dtc.classpath=\"" + ClasspathProvider.makeDevClasspath() + "\" -Dtc.install-root=\""
            + System.getProperty("tc.install-root") + "\"";
      }
      
      String projectNameProp = "-Dproject.name=\"" + project.getName() + "\"";
      
      wc.setAttribute(ATTR_VM_ARGUMENTS,
        cpProp + configProp + bootProp + " " + projectNameProp + " " + vmArgs);

      plugin.ensureRuntimeDirectory(project, monitor);

      return wc;
    } catch(Throwable t) {
      String msg = "Unable to launch '"+config.getName()+"'\n\n"+t.getLocalizedMessage();
      Status status = new Status(IStatus.ERROR, TcPlugin.getPluginId(), 1, msg, t);
      throw new CoreException(status);
    }
  }
  
  private static String toOSString(IPath path) {
    return path != null ? path.makeAbsolute().toOSString() : "";
  }
  
  private void testEnsureBootJar(
    final TcPlugin     plugin,
    final IJavaProject javaProject,
    final IFile        bootJar,
    final String       jreContainerPath)
  {
    IProject            project                 = javaProject.getProject();
    ConfigurationHelper configHelper            = plugin.getConfigurationHelper(project);
    IFile               configFile              = plugin.getConfigurationFile(project);
    boolean             stdBootJarExists        = false;
    boolean             configHasBootJarClasses = configHelper.hasBootJarClasses();
    boolean             configHasModules        = configHelper.hasModules();
    
    try {
      stdBootJarExists = BootJarHelper.getHelper().getBootJarFile().exists();
    } catch(CoreException ce) {/**/}
    
    if(!stdBootJarExists || (configFile != null && configHasBootJarClasses)) {
      try {
        bootJar.refreshLocal(IResource.DEPTH_ZERO, null);
      } catch(CoreException ce) {/**/}
      
      long bootStamp = bootJar.getLocation().toFile().lastModified();
      long confStamp = configFile.getLocation().toFile().lastModified();
      
      if(!bootJar.exists() || ((configHasModules || configHasBootJarClasses) && bootStamp < confStamp)) {
        Display.getDefault().syncExec(new Runnable() {
          public void run() {
            BuildBootJarAction bbja = new BuildBootJarAction(javaProject);
            bbja.setJREContainerPath(jreContainerPath);
            bbja.run((IAction)null);
          }
        });
      }
    }
  }
  
  private String getConfigSpec(ILaunchConfigurationWorkingCopy wc) throws CoreException {
    IJavaProject javaProject = fLaunchDelegate.getJavaProject(wc);
    IProject project = javaProject.getProject();
    IFile configFile = TcPlugin.getDefault().getConfigurationFile(project);
    IPath configPath = configFile != null ? configFile.getLocation() : null;
    String configSpec;
    
    if((configSpec = wc.getAttribute(ID_CONFIG_SERVER_SPEC, (String)null)) == null) {
      configSpec = wc.getAttribute(ID_CONFIG_FILE_SPEC, toOSString(configPath));
      IStringVariableManager variableManager = VariablesPlugin.getDefault().getStringVariableManager();
      configSpec = variableManager.performStringSubstitution(configSpec);
    }
    
    return configSpec;
  }
  
  class QueryStartServerDialog extends MessageDialog {
    Label msgLabel;
    final AtomicBoolean quitAsking = new AtomicBoolean();
    
    QueryStartServerDialog(String title, String msg) {
      super(new Shell(Display.getDefault()), title, null, msg,
        MessageDialog.QUESTION, new String[] {
          IDialogConstants.YES_LABEL,
          IDialogConstants.NO_LABEL,
          IDialogConstants.CANCEL_LABEL }, 0);
    }
    
    protected Control createCustomArea(Composite parent) {
      final Button quitAskingButton = new Button(parent, SWT.CHECK);
      quitAskingButton.setText("Remember this setting");
      quitAskingButton.addSelectionListener(new SelectionAdapter() {
        public void widgetSelected(SelectionEvent e) {
          quitAsking.set(quitAskingButton.getSelection());
        }
      });
      
      return quitAskingButton;
    }
    
    boolean quitAsking() {
      return quitAsking.get();
    }
  }
  
  public static final QualifiedName QUERY_START_SERVER = new QualifiedName(LaunchHelper.class.getName(), "QueryStartServer");

  private boolean queryStartServer(final IProject project, final IProgressMonitor monitor) throws CoreException {
    if(project.getSessionProperty(QUERY_START_SERVER) != null) { return false; }

    final AtomicBoolean startServer = new AtomicBoolean();
    final AtomicReference errorRef = new AtomicReference();
    Display.getDefault().syncExec(new Runnable() {
      public void run() {
        String title = "Terracotta";
        String msg = "Start a local Terracotta Server?";
        QueryStartServerDialog dialog = new QueryStartServerDialog(title, msg);
        int result = dialog.open();

        if(result == 2) {
          monitor.setCanceled(true);
        } else {
          boolean start = (result==0);
          startServer.set(start);
          if(dialog.quitAsking()) {
            TcPlugin.getDefault().setAutoStartServerOption(project, start);
            try {
              project.setSessionProperty(QUERY_START_SERVER, "true");
            } catch(CoreException ce) {
              errorRef.set(ce);
            }
          }
        }
      }
    });

    CoreException ce = (CoreException)errorRef.get();
    if(ce != null) { throw ce; }
    
    return startServer.get();
  }
}
