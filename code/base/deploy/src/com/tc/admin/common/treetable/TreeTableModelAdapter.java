package com.tc.admin.common.treetable;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.TreePath;

/**
 * This is a wrapper class takes a TreeTableModel and implements the table model interface. The implementation is
 * trivial, with all of the event dispatching support provided by the superclass: the AbstractTableModel.
 */

public class TreeTableModelAdapter extends AbstractTableModel {
  JTree          tree;
  TreeTableModel treeTableModel;

  public TreeTableModelAdapter(TreeTableModel treeTableModel, JTree tree) {
    this.tree = tree;
    this.treeTableModel = treeTableModel;

    tree.addTreeExpansionListener(new TreeExpansionListener() {
      // Don't use fireTableRowsInserted() here;
      // the selection model would get updated twice.
      public void treeExpanded(TreeExpansionEvent event) {
        _fireTableDataChanged();
      }

      public void treeCollapsed(TreeExpansionEvent event) {
        _fireTableDataChanged();
      }
    });

    treeTableModel.addTreeModelListener(new TreeModelListener() {
      public void treeNodesChanged(TreeModelEvent e) {
        delayedFireTableDataChanged();
      }

      public void treeNodesInserted(TreeModelEvent e) {
        delayedFireTableDataChanged();
      }

      public void treeNodesRemoved(TreeModelEvent e) {
        delayedFireTableDataChanged();
      }

      public void treeStructureChanged(TreeModelEvent e) {
        delayedFireTableDataChanged();
      }
    });
  }

  private void _fireTableDataChanged() {
    TreePath path = tree.getSelectionPath();
    fireTableDataChanged();
    tree.setSelectionPath(path);
  }

  // Wrappers, implementing TableModel interface.

  public int getColumnCount() {
    return treeTableModel.getColumnCount();
  }

  public String getColumnName(int column) {
    return treeTableModel.getColumnName(column);
  }

  public Class getColumnClass(int column) {
    return treeTableModel.getColumnClass(column);
  }

  public int getRowCount() {
    return tree.getRowCount();
  }

  protected Object nodeForRow(int row) {
    TreePath treePath = tree.getPathForRow(row);
    return treePath.getLastPathComponent();
  }

  public Object getValueAt(int row, int column) {
    return treeTableModel.getValueAt(nodeForRow(row), column);
  }

  public boolean isCellEditable(int row, int column) {
    return treeTableModel.isCellEditable(nodeForRow(row), column);
  }

  public void setValueAt(Object value, int row, int column) {
    treeTableModel.setValueAt(value, nodeForRow(row), column);
  }

  /**
   * Invokes fireTableDataChanged after all the pending events have been processed. SwingUtilities.invokeLater is used
   * to handle this.
   */
  protected void delayedFireTableDataChanged() {
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        fireTableDataChanged();
      }
    });
  }
}
