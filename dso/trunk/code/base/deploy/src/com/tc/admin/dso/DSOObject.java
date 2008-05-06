/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.admin.dso;

import com.tc.admin.ConnectionContext;
import com.tc.object.ObjectID;
import com.tc.objectserver.mgmt.ManagedObjectFacade;
import com.tc.objectserver.mgmt.MapEntryFacade;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

public abstract class DSOObject {
  protected ConnectionContext     m_cc;
  protected DSOObject             m_parent;
  protected PropertyChangeSupport m_changeHelper;
  protected int                   m_batchSize;

  public DSOObject(ConnectionContext cc) {
    m_cc = cc;
    m_changeHelper = new PropertyChangeSupport(this);
    m_batchSize = ConnectionContext.DSO_SMALL_BATCH_SIZE;
  }

  public DSOObject(ConnectionContext cc, DSOObject parent) {
    this(cc);
    m_parent = parent;
  }

  public abstract Object getFacade();

  public abstract void accept(DSOObjectVisitor visitor);

  public DSOObject getParent() {
    return m_parent;
  }

  public void setBatchSize(int batchSize) {
    m_batchSize = batchSize;
  }

  public abstract String getName();

  public DSORoot getRoot() {
    DSOObject obj = this;

    while (obj != null) {
      if (obj.getParent() == null) { return (DSORoot) obj; }
      obj = obj.getParent();
    }

    return null;
  }

  protected DSOObject createField(String fieldName, Object value, String type) throws Exception {
    DSOObject result = null;

    if (value instanceof MapEntryFacade) {
      MapEntryFacade mef = (MapEntryFacade) value;
      result = new DSOMapEntryField(m_cc, fieldName, mef, this);
    } else if (value instanceof ObjectID) {
      ObjectID id = (ObjectID) value;

      if (!id.isNull()) {
        value = DSOHelper.getHelper().lookupFacade(m_cc, id, m_batchSize);
        type = ((ManagedObjectFacade) value).getClassName();
      } else {
        value = null;
        type = null;
      }

      result = new DSOField(m_cc, fieldName, false, convertTypeName(type), value, this);
    } else {
      result = new DSOField(m_cc, fieldName, true, convertTypeName(type), value, this);
    }

    return result;
  }

  private static final char C_ARRAY = '[';

  public static int getArrayCount(char[] typeSignature) throws IllegalArgumentException {
    try {
      int count = 0;
      while (typeSignature[count] == C_ARRAY) {
        ++count;
      }
      return count;
    } catch (ArrayIndexOutOfBoundsException e) { // signature is syntactically incorrect if last character is C_ARRAY
      throw new IllegalArgumentException();
    }
  }

  static String convertTypeName(String typeName) {
    if (typeName != null && typeName.length() > 0) {
      if (typeName.charAt(0) == C_ARRAY) {
        try {
          int arrayCount = getArrayCount(typeName.toCharArray());
          typeName = typeName.substring(arrayCount);
          if (typeName.charAt(0) == 'L') {
            int pos = 1;
            while (typeName.charAt(pos) != ';')
              pos++;
            typeName = typeName.substring(1, pos);
          } else {
            typeName = nativeTypeFor(typeName.charAt(0));
          }
          StringBuffer sb = new StringBuffer(typeName);
          for (int i = 0; i < arrayCount; i++) {
            sb.append("[]");
          }
          typeName = sb.toString();
        } catch (IllegalArgumentException iae) {/**/
        }
      } else if(typeName.length() == 1) {
        typeName = nativeTypeFor(typeName.charAt(0));
      }
    }
    return typeName;
  }

  private static String nativeTypeFor(char c) {
    switch(c) {
      case 'Z': return "boolean";
      case 'I': return "int";
      case 'F': return "float";
      case 'C': return "char";
      case 'D': return "double";
      case 'B': return "byte";
    }
    return String.valueOf(c);
  }
  
  public void addPropertyChangeListener(PropertyChangeListener listener) {
    m_changeHelper.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(PropertyChangeListener listener) {
    m_changeHelper.removePropertyChangeListener(listener);
  }
}
