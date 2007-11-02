/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.welcome;

import org.dijon.ApplicationManager;
import org.dijon.DictionaryResource;
import org.dijon.Frame;
import org.dijon.Image;
import org.dijon.Label;
import org.dijon.Menu;
import org.dijon.MenuBar;
import org.dijon.Separator;

import com.tc.admin.common.ContactTerracottaAction;
import com.tc.admin.common.XAbstractAction;
import com.tc.object.tools.BootJarSignature;
import com.tc.object.tools.UnsupportedVMException;
import com.tc.util.ResourceBundleHelper;
import com.tc.util.ProductInfo;
import com.tc.util.runtime.Os;

import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;

import javax.swing.JOptionPane;
import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkListener;

public abstract class HyperlinkFrame extends Frame implements HyperlinkListener {
  private static ResourceBundleHelper m_bundleHelper = new ResourceBundleHelper(HyperlinkFrame.class);
  
  private File m_installRoot;
  private File m_bootPath;
  private File m_javaCmd;
  private File m_tcLib;
  private File m_samplesDir;
    
  public HyperlinkFrame() {
    super();
    
    MenuBar menubar = new MenuBar();
    Menu    menu;
    
    setJMenuBar(menubar);
    menubar.add(menu = new Menu(getBundleString("file.menu.title")));
    initFileMenu(menu);
    menubar.add(menu = new Menu(getBundleString("help.menu.title")));
    menu.add(new ContactTerracottaAction(getBundleString("visit.forums.title"),
                                         getBundleString("forums.url")));
    menu.add(new ContactTerracottaAction(getBundleString("contact.support.title"),
                                         getBundleString("support.url")));
    menu.add(new Separator());
    menu.add(new AboutAction());
    
    URL    url;
    String iconPath = "/com/tc/admin/icons/logo_small.gif";
    
    if((url = getClass().getResource(iconPath)) != null) {
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
  
  protected void quit() {
    System.exit(0);
  }
  
  protected File getInstallRoot() {
    if(m_installRoot == null) {
      m_installRoot = new File(System.getProperty("tc.install-root").trim());
    }
    return m_installRoot;
  }
  
  protected String getBootPath() throws UnsupportedVMException {
    if(m_bootPath == null) {
      File bootPath = new File(getInstallRoot(), "lib");
      bootPath = new File(bootPath, "dso-boot");
      bootPath = new File(bootPath, BootJarSignature.getBootJarNameForThisVM());
      m_bootPath = bootPath;
    }

    return m_bootPath.getAbsolutePath();
  }

  protected static String getenv(String key) {
    try {
      Method m = System.class.getMethod("getenv", new Class[] {String.class});
    
      if(m != null) {
        return (String)m.invoke(null, new Object[]{key});
      }
    } catch(Throwable t) {/**/}

    return null;
  }
  

  static File staticGetJavaCmd() {
    File javaBin = new File(System.getProperty("java.home"), "bin");
    return new File(javaBin, "java" + (Os.isWindows() ? ".exe" : ""));
  }
  
  protected File getJavaCmd() {
    if(m_javaCmd == null) {
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
    if(m_tcLib == null) {
      m_tcLib = staticGetTCLib();
    }
    return m_tcLib;
  }
  
  protected File getSamplesDir() {
    if(m_samplesDir == null) {
      m_samplesDir = new File(getInstallRoot(), "samples");
    }
    return m_samplesDir;
  }

  protected Process exec(String[] cmdarray, String[] envp, File dir) {
    try {
      return Runtime.getRuntime().exec(cmdarray, envp, dir);
    } catch(IOException ioe) {
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
    org.dijon.Container m_aboutPanel;
    
    AboutAction() {
      super(getBundleString("about.title.prefix")+HyperlinkFrame.this.getTitle());
    }

    DictionaryResource loadTopRes() {
      InputStream        is     = getClass().getResourceAsStream("Welcome.xml");
      DictionaryResource topRes = null;
      
      try {
        topRes = ApplicationManager.loadResource(is);
      } catch(Exception e) {
        e.printStackTrace();
      }
      
      return topRes;
    }
    
    public void actionPerformed(ActionEvent ae) {
      if(m_aboutPanel == null) {
        DictionaryResource topRes = loadTopRes();
        
        if(topRes != null) {
          m_aboutPanel = (org.dijon.Container)topRes.resolve("AboutPanel");
          
          ProductInfo prodInfo = ProductInfo.getInstance();
          Label       label    = (Label)m_aboutPanel.findComponent("TitleLabel");
          
          label.setText(prodInfo.toShortString());

          label = (Label)m_aboutPanel.findComponent("CopyrightLabel");
          label.setText(prodInfo.copyright());
        }
      }

      JOptionPane.showConfirmDialog(HyperlinkFrame.this,
                                    m_aboutPanel,
                                    getName(),
                                    JOptionPane.DEFAULT_OPTION,
                                    JOptionPane.PLAIN_MESSAGE);
    }
  }
}
