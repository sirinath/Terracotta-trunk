/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */

/* Generated By:JJTree: Do not edit this line. ASTHasMethod.java */

package com.tc.aspectwerkz.expression.ast;

public class ASTHasMethod extends SimpleNode {
  public ASTHasMethod(int id) {
    super(id);
  }

  public ASTHasMethod(ExpressionParser p, int id) {
    super(p, id);
  }


  /**
   * Accept the visitor. *
   */
  public Object jjtAccept(ExpressionParserVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }
}
