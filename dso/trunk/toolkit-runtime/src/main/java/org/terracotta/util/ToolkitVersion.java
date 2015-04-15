/* 
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at 
 *
 *      http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Terracotta Platform.
 *
 * The Initial Developer of the Covered Software is 
 *      Terracotta, Inc., a Software AG company
 */
package org.terracotta.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ToolkitVersion {

  public static void main(String[] args) {
    InputStream in = ToolkitVersion.class.getResourceAsStream("/Version.info");
    Properties props = new Properties();

    try {
      props.load(in);
    } catch (IOException e) {
      System.err.println("Unable to load Version.info");
    }
    System.out.println("Supported Toolkit api version" + props.getProperty("toolkit-api-version"));
  }
}
