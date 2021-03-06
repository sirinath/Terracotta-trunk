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
package com.terracotta.management.service.impl;

import org.terracotta.management.ServiceExecutionException;

import com.terracotta.management.resource.BackupEntity;
import com.terracotta.management.service.BackupService;

import java.util.Collection;
import java.util.Set;

/**
 * @author Ludovic Orban
 */
public class BackupServiceImpl implements BackupService {

  private final ServerManagementService serverManagementService;

  public BackupServiceImpl(ServerManagementService serverManagementService) {
    this.serverManagementService = serverManagementService;
  }

  @Override
  public Collection<BackupEntity> getBackupStatus(Set<String> serverNames) throws ServiceExecutionException {
    return serverManagementService.getBackupsStatus(serverNames);
  }

  @Override
  public Collection<BackupEntity> backup(Set<String> serverNames, String backupName) throws ServiceExecutionException {
    return serverManagementService.backup(serverNames, backupName);
  }
}
