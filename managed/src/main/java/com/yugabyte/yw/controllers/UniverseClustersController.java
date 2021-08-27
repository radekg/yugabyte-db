/*
 * Copyright 2021 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.controllers.UniverseControllerRequestBinder.bindFormDataToTaskParams;

import com.google.inject.Inject;
import com.yugabyte.yw.controllers.handlers.UniverseCRUDHandler;
import com.yugabyte.yw.forms.UniverseConfigureTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ClusterType;
import com.yugabyte.yw.forms.UniverseResp;
import com.yugabyte.yw.forms.PlatformResults.YBPTask;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.UUID;
import play.mvc.Result;

@Api(
    value = "UniverseClusterMutations",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class UniverseClustersController extends AuthenticatedController {

  @Inject private UniverseCRUDHandler universeCRUDHandler;

  @ApiOperation(
      value = "Create Universe Clusters",
      notes =
          "This will configure and create universe with (optionally) multiple clusters. "
              + "Just fill in the userIntent for PRIMARY and (optionally) an ASYNC cluster",
      response = YBPTask.class,
      nickname = "createAllClusters")
  public Result createAllClusters(UUID customerUUID) {
    // TODO: add assertions that only expected params are set or bad_request
    // Basically taskParams.clusters[]->userIntent and may be few more things
    Customer customer = Customer.getOrBadRequest(customerUUID);

    UniverseConfigureTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseConfigureTaskParams.class);

    taskParams.clusterOperation = UniverseConfigureTaskParams.ClusterOperationType.CREATE;
    taskParams.currentClusterType = ClusterType.PRIMARY;
    universeCRUDHandler.configure(customer, taskParams);

    if (taskParams
        .clusters
        .stream()
        .anyMatch(cluster -> cluster.clusterType == ClusterType.ASYNC)) {
      taskParams.currentClusterType = ClusterType.ASYNC;
      universeCRUDHandler.configure(customer, taskParams);
    }
    UniverseResp universeResp = universeCRUDHandler.createUniverse(customer, taskParams);
    auditService().createAuditEntryWithReqBody(ctx(), universeResp.taskUUID);

    return new YBPTask(universeResp.taskUUID, universeResp.universeUUID).asResult();
  }

  /** Takes UDTParams and update universe. Just fill in the userIntent for PRIMARY cluster. */
  @ApiOperation(
      value = "Update Primary Cluster",
      notes =
          "This will update primary cluster of existing universe."
              + "Just fill in the userIntent for PRIMARY cluster",
      response = YBPTask.class,
      nickname = "updatePrimaryCluster")
  public Result updatePrimaryCluster(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);
    UniverseConfigureTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseConfigureTaskParams.class);

    taskParams.clusterOperation = UniverseConfigureTaskParams.ClusterOperationType.EDIT;
    taskParams.currentClusterType = ClusterType.PRIMARY;
    universeCRUDHandler.configure(customer, taskParams);

    UUID taskUUID = universeCRUDHandler.update(customer, universe, taskParams);
    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return new YBPTask(taskUUID, universeUUID).asResult();
  }

  /** Takes UDTParams and update universe. Just fill in the userIntent for ASYNC cluster. */
  @ApiOperation(
      value = "Create ReadOnly Cluster",
      notes = "This will add a readonly cluster to existing universe.",
      response = YBPTask.class,
      nickname = "createReadOnlyCluster")
  public Result createReadOnlyCluster(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    UUID taskUUID =
        universeCRUDHandler.createCluster(
            customer,
            universe,
            bindFormDataToTaskParams(request(), UniverseDefinitionTaskParams.class));

    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return new YBPTask(taskUUID, universeUUID).asResult();
  }

  @ApiOperation(
      value = "Delete Readonly Cluster",
      notes = "This will delete readonly cluster of existing universe.",
      response = YBPTask.class,
      nickname = "deleteReadonlyCluster")
  public Result deleteReadonlyCluster(
      UUID customerUUID, UUID universeUUID, UUID clusterUUID, Boolean isForceDelete) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    UUID taskUUID =
        universeCRUDHandler.clusterDelete(customer, universe, clusterUUID, isForceDelete);

    auditService().createAuditEntry(ctx(), request(), taskUUID);
    return new YBPTask(taskUUID, universeUUID).asResult();
  }

  /**
   * Takes UDTParams and update universe. Just fill in the userIntent for either PRIMARY or ASYNC
   * cluster. Only one cluster can be updated at a time.
   */
  @ApiOperation(
      value = "Update Readonly Cluster",
      notes =
          "This will update readonly cluster of existing universe."
              + "Just fill in the userIntent for ASYNC cluster",
      response = YBPTask.class,
      nickname = "updateReadOnlyCluster")
  public Result updateReadOnlyCluster(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);
    UniverseConfigureTaskParams taskParams =
        bindFormDataToTaskParams(request(), UniverseConfigureTaskParams.class);

    taskParams.clusterOperation = UniverseConfigureTaskParams.ClusterOperationType.EDIT;
    taskParams.currentClusterType = ClusterType.ASYNC;
    universeCRUDHandler.configure(customer, taskParams);

    UUID taskUUID = universeCRUDHandler.update(customer, universe, taskParams);
    auditService().createAuditEntryWithReqBody(ctx(), taskUUID);
    return new YBPTask(taskUUID, universeUUID).asResult();
  }
}
