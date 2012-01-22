/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */

/* Generated By:JJTree: Do not edit this line. ASTWithinCode.java */

package com.tc.aspectwerkz.expression.ast;

public class ASTWithinCode extends SimpleNode {
  private boolean m_staticInitializer = false;

  public ASTWithinCode(int id) {
    super(id);
  }

  public ASTWithinCode(ExpressionParser p, int id) {
    super(p, id);
  }

  /**
   * Accept the visitor. *
   */
  public Object jjtAccept(ExpressionParserVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }

  /**
   * @return Returns the staticinitializer.
   */
  public boolean isStaticInitializer() {
    return m_staticInitializer;
  }

  /**
   * @param staticinitializer The staticinitializer to set.
   */
  public void setStaticInitializer(boolean staticinitializer) {
    m_staticInitializer = staticinitializer;
  }
}
