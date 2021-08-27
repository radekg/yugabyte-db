/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.SubTaskGroup;
import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails.SubTaskGroupType;
import com.yugabyte.yw.commissioner.tasks.subtasks.DeleteCertificate;
import com.yugabyte.yw.commissioner.tasks.subtasks.RemoveUniverseEntry;
import com.yugabyte.yw.common.DnsManager;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseTaskParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Universe;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DestroyUniverse extends UniverseTaskBase {

  @Inject
  public DestroyUniverse(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  public static class Params extends UniverseTaskParams {
    public UUID customerUUID;
    public Boolean isForceDelete;
    public Boolean isDeleteBackups;
    public Boolean isDeleteAssociatedCerts;
  }

  public Params params() {
    return (Params) taskParams;
  }

  @Override
  public void run() {
    try {
      // Create the task list sequence.
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      Universe universe;
      if (params().isForceDelete) {
        universe = forceLockUniverseForUpdate(-1, true);
      } else {
        universe = lockUniverseForUpdate(-1, true);
      }

      if (params().isDeleteBackups) {
        List<Backup> backupList =
            Backup.fetchByUniverseUUID(params().customerUUID, universe.universeUUID);
        createDeleteBackupTasks(backupList, params().customerUUID)
            .setSubTaskGroupType(SubTaskGroupType.DeletingBackup);
      }

      // Cleanup the kms_history table
      createDestroyEncryptionAtRestTask()
          .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      if (!universe.getUniverseDetails().isImportedUniverse()) {
        // Update the DNS entry for primary cluster to mirror creation.
        Cluster primaryCluster = universe.getUniverseDetails().getPrimaryCluster();
        createDnsManipulationTask(
                DnsManager.DnsCommandType.Delete, params().isForceDelete, primaryCluster.userIntent)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

        if (primaryCluster.userIntent.providerType.equals(CloudType.onprem)) {
          // Stop master and tservers.
          createStopServerTasks(universe.getNodes(), "master", params().isForceDelete)
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
          createStopServerTasks(universe.getNodes(), "tserver", params().isForceDelete)
              .setSubTaskGroupType(SubTaskGroupType.StoppingNodeProcesses);
        }

        // Create tasks to destroy the existing nodes.
        createDestroyServerTasks(
                universe.getNodes(), params().isForceDelete, true /* delete node */)
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
      }

      // Create tasks to remove the universe entry from the Universe table.
      createRemoveUniverseEntryTask().setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);

      // Update the swamper target file.
      createSwamperTargetUpdateTask(true /* removeFile */);

      if (params().isDeleteAssociatedCerts) {
        createDeleteCertificatesTaskGroup(universe.getUniverseDetails())
            .setSubTaskGroupType(SubTaskGroupType.RemovingUnusedServers);
      }

      // Run all the tasks.
      subTaskGroupQueue.run();
    } catch (Throwable t) {
      // If for any reason destroy fails we would just unlock the universe for update
      try {
        unlockUniverseForUpdate();
      } catch (Throwable t1) {
        // Ignore the error
      }
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);
      throw t;
    }
    log.info("Finished {} task.", getName());
  }

  public SubTaskGroup createRemoveUniverseEntryTask() {
    SubTaskGroup subTaskGroup = new SubTaskGroup("RemoveUniverseEntry", executor);
    Params params = new Params();
    // Add the universe uuid.
    params.universeUUID = taskParams().universeUUID;
    params.customerUUID = params().customerUUID;
    params.isForceDelete = params().isForceDelete;

    // Create the Ansible task to destroy the server.
    RemoveUniverseEntry task = createTask(RemoveUniverseEntry.class);
    task.initialize(params);
    // Add it to the task list.
    subTaskGroup.addTask(task);
    subTaskGroupQueue.add(subTaskGroup);
    return subTaskGroup;
  }

  public SubTaskGroup createDeleteCertificatesTaskGroup(
      UniverseDefinitionTaskParams universeDetails) {
    SubTaskGroup subTaskGroup = new SubTaskGroup("DeleteCertificates", executor);

    // Create the task to delete rootCerts.
    DeleteCertificate rootCertDeletiontask =
        createDeleteCertificateTask(params().customerUUID, universeDetails.rootCA);
    // Add it to the task list.
    if (rootCertDeletiontask != null) {
      subTaskGroup.addTask(rootCertDeletiontask);
    }

    if (!universeDetails.rootAndClientRootCASame) {
      // Create the task to delete clientRootCerts.
      DeleteCertificate clientRootCertDeletiontask =
          createDeleteCertificateTask(params().customerUUID, universeDetails.clientRootCA);
      // Add it to the task list.
      if (clientRootCertDeletiontask != null) {
        subTaskGroup.addTask(clientRootCertDeletiontask);
      }
    }

    if (subTaskGroup.getNumTasks() > 0) {
      subTaskGroupQueue.add(subTaskGroup);
    }
    return subTaskGroup;
  }

  public DeleteCertificate createDeleteCertificateTask(UUID customerUUID, UUID certUUID) {
    DeleteCertificate.Params params = new DeleteCertificate.Params();
    params.customerUUID = customerUUID;
    params.certUUID = certUUID;
    DeleteCertificate task = createTask(DeleteCertificate.class);
    task.initialize(params);
    return task;
  }
}
