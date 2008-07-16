/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin.common;

import org.dijon.DefaultTableCellRenderer;
import org.dijon.Table;
import org.dijon.TableResource;

import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.Format;
import java.text.NumberFormat;
import java.util.Date;
import java.util.prefs.Preferences;

import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.ChangeEvent;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;

public class XTable extends Table {
  protected XPopupListener    m_popupHelper;
  protected Timer             m_columnPrefsTimer;

  private static final String COLUMNS_PREF_KEY = "Columns";

  public XTable() {
    super();
    init();
  }

  public XTable(TableModel model) {
    super(model);
    init();
  }

  private void init() {
    setDefaultRenderer(Integer.class, new IntegerRenderer());
    setDefaultRenderer(Long.class, new LongRenderer());
    setDefaultRenderer(Date.class, new DateRenderer());
    setDefaultRenderer(Float.class, new FloatRenderer());
    setDefaultRenderer(Double.class, new FloatRenderer());

    m_popupHelper = new XPopupListener(this);

    m_columnPrefsTimer = new Timer(1000, new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        storeColumnPrefs();
      }
    });
    m_columnPrefsTimer.setRepeats(false);

    getTableHeader().setReorderingAllowed(false);
  }

  public void load(TableResource tableRes) {
    super.load(tableRes);
    ToolTipManager.sharedInstance().registerComponent(this);
  }

  public void addNotify() {
    super.addNotify();

    TableColumnModel colModel = getColumnModel();
    if (colModel != null && colModel.getColumnCount() > 0) {
      loadColumnPrefs();
    }
  }

  public static class BaseRenderer extends DefaultTableCellRenderer {
    Format formatter;

    public BaseRenderer(String format) {
      super();
      formatter = new DecimalFormat(format);
    }

    public BaseRenderer(Format formatter) {
      super();
      this.formatter = formatter;
    }

    public void setValue(Object value) {
      String text = "";

      try {
        text = (value == null) ? "" : formatter.format(value);
      } catch (Exception nfe) {
        System.out.println(value.toString());
      }

      setText(text);
    }
  }

  public static class IntegerRenderer extends BaseRenderer {
    public IntegerRenderer() {
      super("#,##0;(#,##0)");
      setHorizontalAlignment(SwingConstants.RIGHT);
    }
  }

  public static class PortNumberRenderer extends BaseRenderer {
    public PortNumberRenderer() {
      super("###0;(###0)");
      setHorizontalAlignment(SwingConstants.RIGHT);
    }
  }

  public static class LongRenderer extends BaseRenderer {
    public LongRenderer() {
      super("#,##0;(#,##0)");
      setHorizontalAlignment(SwingConstants.RIGHT);
    }
  }

  public static class DateRenderer extends BaseRenderer {
    public DateRenderer() {
      super(DateFormat.getDateTimeInstance());
    }
  }

  public static class FloatRenderer extends BaseRenderer {
    public FloatRenderer() {
      super("#,##0.00####;(#,##0.00####)");
      setHorizontalAlignment(SwingConstants.RIGHT);
    }
  }

  public static class PercentRenderer extends BaseRenderer {
    public PercentRenderer() {
      super(NumberFormat.getPercentInstance());
    }
  }

  public void setPopupMenu(JPopupMenu popupMenu) {
    m_popupHelper.setPopupMenu(popupMenu);
  }

  public JPopupMenu getPopupMenu() {
    return m_popupHelper.getPopupMenu();
  }

  public void setModel(TableModel model) {
    super.setModel(model);

    TableColumnModel colModel = getColumnModel();
    if (colModel != null && colModel.getColumnCount() > 0) {
      loadColumnPrefs();
    }
  }

  protected void loadColumnPrefs() {
    if (getClass().equals(XTable.class)) { return; }

    PrefsHelper helper = PrefsHelper.getHelper();
    Preferences prefs = helper.userNodeForClass(getClass());
    TableColumnModel colModel = getColumnModel();
    String s = prefs.get(COLUMNS_PREF_KEY, null);
    int width;

    if (s != null) {
      String[] split = s.split(",");

      for (int i = 0; i < colModel.getColumnCount(); i++) {
        if (i < split.length && split[i] != null) {
          try {
            width = Integer.parseInt(split[i]);
            colModel.getColumn(i).setPreferredWidth(width);
          } catch (Exception e) {/**/
          }
        }
      }
    }
  }

  protected void storeColumnPrefs() {
    if (getClass().equals(XTable.class)) { return; }

    PrefsHelper helper = PrefsHelper.getHelper();
    Preferences prefs = helper.userNodeForClass(getClass());
    StringBuffer sb = new StringBuffer();
    TableColumnModel colModel = getColumnModel();
    int width;

    for (int i = 0; i < colModel.getColumnCount(); i++) {
      width = colModel.getColumn(i).getWidth();
      sb.append(width);
      sb.append(",");
    }

    String s = sb.substring(0, sb.length() - 1);
    prefs.put(COLUMNS_PREF_KEY, s);
    helper.flush(prefs);
  }

  public void columnMarginChanged(ChangeEvent e) {
    boolean isValid = isValid();

    if (isValid) {
      m_columnPrefsTimer.stop();
    }
    super.columnMarginChanged(e);
    if (isValid) {
      m_columnPrefsTimer.start();
    }
  }

  public Dimension getPreferredSize() {
    int rowCount = getRowCount();

    if (rowCount > 0) {
      int columnCount = getColumnCount();
      TableCellRenderer renderer;
      java.awt.Component comp;
      Dimension prefSize;
      int height = 0;

      for (int row = 0; row < rowCount; row++) {
        for (int col = 0; col < columnCount; col++) {
          if ((renderer = getCellRenderer(row, col)) != null) {
            comp = renderer.getTableCellRendererComponent(this, getValueAt(row, col), true, true, row, col);

            prefSize = comp.getPreferredSize();
            height = Math.max(height, prefSize.height);
          }
        }
      }

      if (height > 10) {
        setRowHeight(height);
      }
    }

    return super.getPreferredSize();
  }

  public void setSelectedRows(int[] rows) {
    int rowCount = getRowCount();

    for (int i = 0; i < rows.length; i++) {
      if (rows[i] < rowCount) {
        setRowSelectionInterval(rows[i], rows[i]);
      }
    }
  }
}
