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

import static com.yugabyte.yw.forms.PlatformResults.YBPSuccess.withMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.controllers.handlers.UniverseYbDbAdminHandler;
import com.yugabyte.yw.forms.DatabaseSecurityFormData;
import com.yugabyte.yw.forms.DatabaseUserFormData;
import com.yugabyte.yw.forms.RunQueryFormData;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPError;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.libs.Json;
import play.mvc.Result;

@Api(
    value = "Universe database management",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class UniverseYbDbAdminController extends AuthenticatedController {
  private static final Logger LOG = LoggerFactory.getLogger(UniverseYbDbAdminController.class);

  @Inject private UniverseYbDbAdminHandler universeYbDbAdminHandler;

  @ApiOperation(
      value = "Set a universe's database credentials",
      nickname = "setDatabaseCredentials",
      response = YBPSuccess.class)
  public Result setDatabaseCredentials(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    universeYbDbAdminHandler.setDatabaseCredentials(
        customer,
        universe,
        formFactory.getFormDataOrBadRequest(DatabaseSecurityFormData.class).get());

    // TODO: Missing Audit
    return withMessage("Updated security in DB.");
  }

  @ApiOperation(
      value = "Create a database user for a universe",
      nickname = "createUserInDB",
      response = YBPSuccess.class)
  public Result createUserInDB(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);

    DatabaseUserFormData data =
        formFactory.getFormDataOrBadRequest(DatabaseUserFormData.class).get();

    universeYbDbAdminHandler.createUserInDB(customer, universe, data);

    // TODO: Missing Audit
    return withMessage("Created user in DB.");
  }

  @VisibleForTesting static final String DEPRECATED = "Deprecated.";

  // TODO: should this be tagged as deprecated?
  // @Deprecated
  @ApiOperation(
      value = "Run a shell command",
      notes = "This operation is no longer supported, for security reasons.",
      response = YBPError.class)
  public Result runInShell(UUID customerUUID, UUID universeUUID) {
    throw new PlatformServiceException(BAD_REQUEST, DEPRECATED);
  }

  @ApiOperation(
      value = "Run a YSQL query in a universe",
      notes = "Runs a YSQL query. Only valid when the platform is running in `OSS` mode.",
      nickname = "runYsqlQueryUniverse",
      response = Object.class)
  public Result runQuery(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.getOrBadRequest(customerUUID);
    Universe universe = Universe.getValidUniverseOrBadRequest(universeUUID, customer);
    Form<RunQueryFormData> formData = formFactory.getFormDataOrBadRequest(RunQueryFormData.class);

    JsonNode queryResult =
        universeYbDbAdminHandler.validateRequestAndExecuteQuery(universe, formData.get());
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return PlatformResults.withRawData(queryResult);
  }
}
