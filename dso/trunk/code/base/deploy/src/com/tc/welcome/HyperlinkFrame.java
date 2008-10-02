/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.welcome;

import org.dijon.Frame;
import org.dijon.Image;
import org.dijon.Menu;
import org.dijon.MenuBar;
import org.dijon.Separator;

import com.tc.admin.common.AboutDialog;
import com.tc.admin.common.ContactTerracottaAction;
import com.tc.admin.common.XAbstractAction;
import com.tc.object.tools.BootJarSignature;
import com.tc.object.tools.UnsupportedVMException;
import com.tc.util.ProductInfo;
import com.tc.util.ResourceBundleHelper;
import com.tc.util.runtime.Os;

import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.text.MessageFormat;

import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkListener;

public abstract class HyperlinkFrame extends Frame implements HyperlinkListener {
  private static ResourceBundleHelper m_bundleHelper = new ResourceBundleHelper(HyperlinkFrame.class);

  private File                        m_installRoot;
  private File                        m_bootPath;
  private File                        m_javaCmd;
  private File                        m_tcLib;
  private File                        m_samplesDir;

  public HyperlinkFrame(String title) {
    super(title);

    MenuBar menubar = new MenuBar();
    Menu menu;

    setJMenuBar(menubar);
    menubar.add(menu = new Menu(getBundleString("file.menu.title")));
    initFileMenu(menu);
    menubar.add(menu = new Menu(getBundleString("help.menu.title")));

    String kitID = ProductInfo.getInstance().kitID();
    menu.add(new ContactTerracottaAction(getBundleString("visit.forums.title"), formatBundleString("forums.url", kitID)));
    menu.add(new ContactTerracottaAction(getBundleString("contact.support.title"), formatBundleString("support.url", kitID)));
    menu.add(new Separator());
    menu.add(new AboutAction());

    URL url;
    String iconPath = "/com/tc/admin/icons/logo_small.png";

    if ((url = HyperlinkFrame.class.getResource(iconPath)) != null) {
      setIconImage(new Image(url));
    }

    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent we) {
        quit();
      }
    });
  }

  protected void initFileMenu(Menu fileMenu) {
    fileMenu.add(new QuitAction());
  }

  private String getBundleString(String key) {
    return m_bundleHelper.getString(key);
  }

  private String formatBundleString(String key, Object... args) {
    return MessageFormat.format(getBundleString(key), args);
  }
  
  protected void quit() {
    Runtime.getRuntime().exit(0);
  }

  protected File getInstallRoot() {
    if (m_installRoot == null) {
      m_installRoot = new File(System.getProperty("tc.install-root").trim());
    }
    return m_installRoot;
  }

  protected String getBootPath() throws UnsupportedVMException {
    if (m_bootPath == null) {
      File bootPath = new File(getInstallRoot(), "lib");
      bootPath = new File(bootPath, "dso-boot");
      bootPath = new File(bootPath, BootJarSignature.getBootJarNameForThisVM());
      m_bootPath = bootPath;
    }

    return m_bootPath.getAbsolutePath();
  }

  protected static String getenv(String key) {
    try {
      Method m = System.class.getMethod("getenv", new Class[] { String.class });

      if (m != null) { return (String) m.invoke(null, new Object[] { key }); }
    } catch (Throwable t) {/**/
    }

    return null;
  }

  static File staticGetJavaCmd() {
    File javaBin = new File(System.getProperty("java.home"), "bin");
    return new File(javaBin, "java" + (Os.isWindows() ? ".exe" : ""));
  }

  protected File getJavaCmd() {
    if (m_javaCmd == null) {
      m_javaCmd = staticGetJavaCmd();
    }

    return m_javaCmd;
  }

  static File staticGetTCLib() {
    File file = new File(System.getProperty("tc.install-root").trim());
    file = new File(file, "lib");
    return new File(file, "tc.jar");
  }

  protected File getTCLib() {
    if (m_tcLib == null) {
      m_tcLib = staticGetTCLib();
    }
    return m_tcLib;
  }

  protected File getSamplesDir() {
    if (m_samplesDir == null) {
      m_samplesDir = new File(getInstallRoot(), "samples");
    }
    return m_samplesDir;
  }

  protected Process exec(String[] cmdarray, String[] envp, File dir) {
    try {
      return Runtime.getRuntime().exec(cmdarray, envp, dir);
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }

    return null;
  }

  class QuitAction extends XAbstractAction {
    QuitAction() {
      super(getBundleString("quit.action.name"));
    }

    public void actionPerformed(ActionEvent ae) {
      quit();
    }
  }

  class AboutAction extends XAbstractAction {
    AboutDialog m_aboutDialog;

    AboutAction() {
      super(getBundleString("about.title.prefix") + getTitle());
    }

    public void actionPerformed(ActionEvent ae) {
      if (m_aboutDialog == null) {
        m_aboutDialog = new AboutDialog(HyperlinkFrame.this);
      }

      m_aboutDialog.pack();
      m_aboutDialog.center(HyperlinkFrame.this);
      m_aboutDialog.setVisible(true);
    }
  }
}
