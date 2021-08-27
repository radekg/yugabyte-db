/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.params.KMSConfigTaskParams;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.kms.EncryptionAtRestManager;
import com.yugabyte.yw.common.kms.services.SmartKeyEARService;
import com.yugabyte.yw.common.kms.util.EncryptionAtRestUtil;
import com.yugabyte.yw.common.kms.util.KeyProvider;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.forms.PlatformResults.YBPTask;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.KmsConfig;
import com.yugabyte.yw.models.KmsHistory;
import com.yugabyte.yw.models.KmsHistoryId;
import com.yugabyte.yw.models.helpers.CommonUtils;
import com.yugabyte.yw.models.helpers.TaskType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Result;

@Api(
    value = "Encryption at rest",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class EncryptionAtRestController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(EncryptionAtRestController.class);

  private static Set<String> API_URL =
      ImmutableSet.of("api.amer.smartkey.io", "api.eu.smartkey.io", "api.uk.smartkey.io");

  public static final String AWS_ACCESS_KEY_ID_FIELDNAME = "AWS_ACCESS_KEY_ID";
  public static final String AWS_SECRET_ACCESS_KEY_FIELDNAME = "AWS_SECRET_ACCESS_KEY";
  public static final String AWS_REGION_FIELDNAME = "AWS_REGION";

  @Inject EncryptionAtRestManager keyManager;

  @Inject Commissioner commissioner;

  @Inject CloudAPI.Factory cloudAPIFactory;

  private void validateKMSProviderConfigFormData(ObjectNode formData, String keyProvider) {
    if (keyProvider.toUpperCase().equals(KeyProvider.AWS.toString())
        && (formData.get(AWS_ACCESS_KEY_ID_FIELDNAME) != null
            || formData.get(AWS_SECRET_ACCESS_KEY_FIELDNAME) != null)) {
      CloudAPI cloudAPI = cloudAPIFactory.get(KeyProvider.AWS.toString().toLowerCase());
      Map<String, String> config = new HashMap<>();
      config.put(
          AWS_ACCESS_KEY_ID_FIELDNAME, formData.get(AWS_ACCESS_KEY_ID_FIELDNAME).textValue());
      config.put(
          AWS_SECRET_ACCESS_KEY_FIELDNAME,
          formData.get(AWS_SECRET_ACCESS_KEY_FIELDNAME).textValue());
      if (cloudAPI != null
          && !cloudAPI.isValidCreds(config, formData.get(AWS_REGION_FIELDNAME).textValue())) {
        throw new PlatformServiceException(BAD_REQUEST, "Invalid AWS Credentials.");
      }
    }
    if (keyProvider.toUpperCase().equals(KeyProvider.SMARTKEY.toString())) {
      if (formData.get("base_url") == null
          || !EncryptionAtRestController.API_URL.contains(formData.get("base_url").textValue())) {
        throw new PlatformServiceException(BAD_REQUEST, "Invalid API URL.");
      }
      if (formData.get("api_key") != null) {
        try {
          Function<ObjectNode, String> token =
              new SmartKeyEARService()::retrieveSessionAuthorization;
          token.apply(formData);
        } catch (Exception e) {
          throw new PlatformServiceException(BAD_REQUEST, "Invalid API Key.");
        }
      }
    }
  }

  @ApiOperation(value = "Create a KMS configuration", response = YBPTask.class)
  @ApiImplicitParams({
    @ApiImplicitParam(
        name = "KMS config",
        value = "KMS config to be created",
        required = true,
        dataType = "Object",
        paramType = "body")
  })
  public Result createKMSConfig(UUID customerUUID, String keyProvider) {
    LOG.info(
        String.format(
            "Creating KMS configuration for customer %s with %s",
            customerUUID.toString(), keyProvider));
    Customer customer = Customer.getOrBadRequest(customerUUID);
    try {
      TaskType taskType = TaskType.CreateKMSConfig;
      ObjectNode formData = (ObjectNode) request().body().asJson();
      // Validating the KMS Provider config details.
      validateKMSProviderConfigFormData(formData, keyProvider);
      KMSConfigTaskParams taskParams = new KMSConfigTaskParams();
      taskParams.kmsProvider = Enum.valueOf(KeyProvider.class, keyProvider);
      taskParams.providerConfig = formData;
      taskParams.customerUUID = customerUUID;
      taskParams.kmsConfigName = formData.get("name").asText();
      formData.remove("name");
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info("Submitted create KMS config for {}, task uuid = {}.", customerUUID, taskUUID);
      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          customerUUID,
          taskUUID,
          CustomerTask.TargetType.KMSConfiguration,
          CustomerTask.TaskType.Create,
          taskParams.getName());
      LOG.info(
          "Saved task uuid " + taskUUID + " in customer tasks table for customer: " + customerUUID);

      auditService().createAuditEntry(ctx(), request(), formData);
      return new YBPTask(taskUUID).asResult();
    } catch (Exception e) {
      throw new PlatformServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  @ApiOperation(
      value = "Get details of a KMS configuration",
      response = Object.class,
      responseContainer = "Map")
  public Result getKMSConfig(UUID customerUUID, UUID configUUID) {
    LOG.info(String.format("Retrieving KMS configuration %s", configUUID.toString()));
    KmsConfig config = KmsConfig.get(configUUID);
    ObjectNode kmsConfig =
        keyManager.getServiceInstance(config.keyProvider.name()).getAuthConfig(configUUID);
    if (kmsConfig == null) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          String.format("No KMS configuration found for config %s", configUUID.toString()));
    }
    return PlatformResults.withRawData(kmsConfig);
  }

  // TODO: Cleanup raw json
  @ApiOperation(
      value = "List KMS configurations",
      response = Object.class,
      responseContainer = "List")
  public Result listKMSConfigs(UUID customerUUID) {
    LOG.info(String.format("Listing KMS configurations for customer %s", customerUUID.toString()));
    List<JsonNode> kmsConfigs =
        KmsConfig.listKMSConfigs(customerUUID)
            .stream()
            .map(
                configModel -> {
                  ObjectNode result = null;
                  ObjectNode credentials =
                      keyManager
                          .getServiceInstance(configModel.keyProvider.name())
                          .getAuthConfig(configModel.configUUID);
                  if (credentials != null) {
                    result = Json.newObject();
                    ObjectNode metadata = Json.newObject();
                    metadata.put("configUUID", configModel.configUUID.toString());
                    metadata.put("provider", configModel.keyProvider.name());
                    metadata.put(
                        "in_use", EncryptionAtRestUtil.configInUse(configModel.configUUID));
                    metadata.put(
                        "universeDetails",
                        Json.toJson(EncryptionAtRestUtil.getUniverses(configModel.configUUID)));
                    metadata.put("name", configModel.name);
                    result.put("credentials", CommonUtils.maskConfig(credentials));
                    result.put("metadata", metadata);
                  }
                  return result;
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return PlatformResults.withData(kmsConfigs);
  }

  @ApiOperation(value = "Delete a KMS configuration", response = YBPTask.class)
  public Result deleteKMSConfig(UUID customerUUID, UUID configUUID) {
    LOG.info(
        String.format(
            "Deleting KMS configuration %s for customer %s",
            configUUID.toString(), customerUUID.toString()));
    Customer customer = Customer.getOrBadRequest(customerUUID);
    try {
      KmsConfig config = KmsConfig.get(configUUID);
      TaskType taskType = TaskType.DeleteKMSConfig;
      KMSConfigTaskParams taskParams = new KMSConfigTaskParams();
      taskParams.kmsProvider = config.keyProvider;
      taskParams.customerUUID = customerUUID;
      taskParams.configUUID = configUUID;
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      LOG.info("Submitted delete KMS config for {}, task uuid = {}.", customerUUID, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(
          customer,
          customerUUID,
          taskUUID,
          CustomerTask.TargetType.KMSConfiguration,
          CustomerTask.TaskType.Delete,
          taskParams.getName());
      LOG.info(
          "Saved task uuid " + taskUUID + " in customer tasks table for customer: " + customerUUID);
      auditService().createAuditEntry(ctx(), request());
      return new YBPTask(taskUUID).asResult();
    } catch (Exception e) {
      throw new PlatformServiceException(BAD_REQUEST, e.getMessage());
    }
  }

  @ApiOperation(
      value = "Retrive a universe's KMS key",
      response = Object.class,
      responseContainer = "Map")
  public Result retrieveKey(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving universe key for universe %s",
            customerUUID.toString(), universeUUID.toString()));
    ObjectNode formData = (ObjectNode) request().body().asJson();
    byte[] keyRef = Base64.getDecoder().decode(formData.get("reference").asText());
    UUID configUUID = UUID.fromString(formData.get("configUUID").asText());
    byte[] recoveredKey = getRecoveredKeyOrBadRequest(universeUUID, configUUID, keyRef);
    ObjectNode result =
        Json.newObject()
            .put("reference", keyRef)
            .put("value", Base64.getEncoder().encodeToString(recoveredKey));
    auditService().createAuditEntry(ctx(), request(), formData);
    return PlatformResults.withRawData(result);
  }

  public byte[] getRecoveredKeyOrBadRequest(UUID universeUUID, UUID configUUID, byte[] keyRef) {
    byte[] recoveredKey = keyManager.getUniverseKey(universeUUID, configUUID, keyRef);
    if (recoveredKey == null || recoveredKey.length == 0) {
      final String errMsg =
          String.format("No universe key found for universe %s", universeUUID.toString());
      throw new PlatformServiceException(BAD_REQUEST, errMsg);
    }
    return recoveredKey;
  }

  @ApiOperation(
      value = "Get a universe's key reference history",
      response = Object.class,
      responseContainer = "List")
  public Result getKeyRefHistory(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving key ref history for customer %s and universe %s",
            customerUUID.toString(), universeUUID.toString()));
    return PlatformResults.withData(
        KmsHistory.getAllTargetKeyRefs(universeUUID, KmsHistoryId.TargetType.UNIVERSE_KEY)
            .stream()
            .map(
                history -> {
                  return Json.newObject()
                      .put("reference", history.uuid.keyRef)
                      .put("configUUID", history.configUuid.toString())
                      .put("timestamp", history.timestamp.toString());
                })
            .collect(Collectors.toList()));
  }

  @ApiOperation(value = "Remove a universe's key reference history", response = YBPSuccess.class)
  public Result removeKeyRefHistory(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Removing key ref for customer %s with universe %s",
            customerUUID.toString(), universeUUID.toString()));
    keyManager.cleanupEncryptionAtRest(customerUUID, universeUUID);
    auditService().createAuditEntry(ctx(), request());
    return YBPSuccess.withMessage("Key ref was successfully removed");
  }

  @ApiOperation(
      value = "Get a universe's key reference",
      response = Object.class,
      responseContainer = "Map")
  public Result getCurrentKeyRef(UUID customerUUID, UUID universeUUID) {
    LOG.info(
        String.format(
            "Retrieving key ref for customer %s and universe %s",
            customerUUID.toString(), universeUUID.toString()));
    KmsHistory activeKey = EncryptionAtRestUtil.getActiveKeyOrBadRequest(universeUUID);
    String keyRef = activeKey.uuid.keyRef;
    if (keyRef == null || keyRef.length() == 0) {
      throw new PlatformServiceException(
          BAD_REQUEST,
          String.format(
              "Could not retrieve key service for customer %s and universe %s",
              customerUUID.toString(), universeUUID.toString()));
    }
    return PlatformResults.withRawData(Json.newObject().put("reference", keyRef));
  }
}
