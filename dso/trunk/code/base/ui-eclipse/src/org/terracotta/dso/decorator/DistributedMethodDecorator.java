/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package org.terracotta.dso.decorator;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;

import org.terracotta.dso.ConfigurationHelper;
import org.terracotta.dso.TcPlugin;

/**
 * Adorns Java methods that are invoked on all instances within a shared
 * root hierarchy.
 * 
 * The adornment appears in the Package Explorer amd Outline view.
 * 
 * @see org.eclipse.jface.viewers.LabelProvider
 * @see org.terracotta.dso.ConfigurationHelper.isDistributed
 */

public class DistributedMethodDecorator extends LabelProvider
  implements ILightweightLabelDecorator
{
  private static final ImageDescriptor
    m_imageDesc =
      ImageDescriptor.createFromURL(
        DistributedMethodDecorator.class.getResource(
          "/com/tc/admin/icons/owned_ovr.gif"));

  public static final String
    DECORATOR_ID = "org.terracotta.dso.distributedMethodDecorator";

  public void decorate(Object element, IDecoration decoration) {
    TcPlugin plugin  = TcPlugin.getDefault();
    IMethod  method  = (IMethod)element;
    IProject project = method.getJavaProject().getProject();    
  
    if(plugin.hasTerracottaNature(project)) {
      ConfigurationHelper config = plugin.getConfigurationHelper(project);

      if(config != null && config.isDistributedMethod(method)){
        decoration.addOverlay(m_imageDesc);
      }
    }
  }
  
  public static void updateDecorators() {
    TcPlugin.getDefault().updateDecorator(DECORATOR_ID);
  }
}
