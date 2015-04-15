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
package com.tc.objectserver.api;

import java.io.IOException;
import java.util.Map;

/**
 * @author tim
 */
public interface BackupManager {
  enum BackupStatus {
    UNKNOWN, INIT, RUNNING, COMPLETE, FAILED
  }

  BackupStatus getBackupStatus(String name) throws IOException;

  String getBackupFailureReason(String name) throws IOException;

  Map<String, BackupStatus> getBackupStatuses() throws IOException;

  String getRunningBackup();

  void backup(String name) throws IOException;
}
