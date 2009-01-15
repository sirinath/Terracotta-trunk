/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package org.terracotta.ui.session;

import com.terracottatech.config.Include;
import com.terracottatech.config.OnLoad;

public class IncludeRule extends Rule {
  public IncludeRule(Include include) {
    super(include);
  }
  
  public Include getInclude() {
    return (Include)getXmlObject();
  }

  public String getExpression() {
    return getInclude().getClassExpression();
  }
  
  public void setExpression(String expr) {
    getInclude().setClassExpression(expr);
  }

  public boolean hasHonorTransient() {
    return getInclude().getHonorTransient();
  }
  
  public void setHonorTransient(boolean honor) {
    getInclude().setHonorTransient(honor);
    if(!honor) {
      getInclude().unsetHonorTransient();
    }
  }
  
  public OnLoad getOnLoad() {
    return getInclude().getOnLoad();
  }
  
  public void setDetails(RuleDetail details) {/**/}
  
  public RuleDetail getDetails() {
    return null;
  }
}
