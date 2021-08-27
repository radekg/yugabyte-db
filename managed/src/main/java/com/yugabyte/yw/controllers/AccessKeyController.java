// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import static com.yugabyte.yw.commissioner.Common.CloudType.onprem;
import static com.yugabyte.yw.forms.PlatformResults.YBPSuccess.withMessage;

import com.google.inject.Inject;
import com.yugabyte.yw.common.AccessManager;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.TemplateManager;
import com.yugabyte.yw.forms.AccessKeyFormData;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.forms.PlatformResults.YBPSuccess;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Form;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;

@Api(
    value = "Access Keys",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class AccessKeyController extends AuthenticatedController {

  @Inject AccessManager accessManager;

  @Inject TemplateManager templateManager;

  public static final Logger LOG = LoggerFactory.getLogger(AccessKeyController.class);

  @ApiOperation(value = "Get an access key", response = AccessKey.class)
  public Result index(UUID customerUUID, UUID providerUUID, String keyCode) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);

    AccessKey accessKey = AccessKey.getOrBadRequest(providerUUID, keyCode);
    return PlatformResults.withData(accessKey);
  }

  @ApiOperation(value = "List access keys for a specific provider", response = AccessKey.class)
  public Result list(UUID customerUUID, UUID providerUUID) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);

    List<AccessKey> accessKeys;
    accessKeys = AccessKey.getAll(providerUUID);
    return PlatformResults.withData(accessKeys);
  }

  @ApiOperation(value = "Create an access key", response = AccessKey.class)
  public Result create(UUID customerUUID, UUID providerUUID) throws IOException {
    Form<AccessKeyFormData> formData = formFactory.getFormDataOrBadRequest(AccessKeyFormData.class);

    UUID regionUUID = formData.get().regionUUID;
    Region region = Region.getOrBadRequest(customerUUID, providerUUID, regionUUID);

    String keyCode = formData.get().keyCode;
    String keyContent = formData.get().keyContent;
    AccessManager.KeyType keyType = formData.get().keyType;
    String sshUser = formData.get().sshUser;
    Integer sshPort = formData.get().sshPort;
    boolean airGapInstall = formData.get().airGapInstall;
    boolean skipProvisioning = formData.get().skipProvisioning;
    AccessKey accessKey;

    LOG.info(
        "Creating access key {} for customer {}, provider {}.",
        keyCode,
        customerUUID,
        providerUUID);

    // Check if a public/private key was uploaded as part of the request
    Http.MultipartFormData multiPartBody = request().body().asMultipartFormData();
    if (multiPartBody != null) {
      Http.MultipartFormData.FilePart filePart = multiPartBody.getFile("keyFile");
      File uploadedFile = (File) filePart.getFile();
      if (keyType == null || uploadedFile == null) {
        throw new PlatformServiceException(BAD_REQUEST, "keyType and keyFile params required.");
      }
      accessKey =
          accessManager.uploadKeyFile(
              region.uuid,
              uploadedFile,
              keyCode,
              keyType,
              sshUser,
              sshPort,
              airGapInstall,
              skipProvisioning);
    } else if (keyContent != null && !keyContent.isEmpty()) {
      if (keyType == null) {
        throw new PlatformServiceException(BAD_REQUEST, "keyType params required.");
      }
      // Create temp file and fill with content
      Path tempFile = Files.createTempFile(keyCode, keyType.getExtension());
      Files.write(tempFile, keyContent.getBytes());

      // Upload temp file to create the access key and return success/failure
      accessKey =
          accessManager.uploadKeyFile(
              regionUUID,
              tempFile.toFile(),
              keyCode,
              keyType,
              sshUser,
              sshPort,
              airGapInstall,
              skipProvisioning);
    } else {
      accessKey =
          accessManager.addKey(regionUUID, keyCode, sshPort, airGapInstall, skipProvisioning);
    }

    // In case of onprem provider, we add a couple of additional attributes like passwordlessSudo
    // and create a preprovision script
    if (region.provider.code.equals(onprem.name())) {
      templateManager.createProvisionTemplate(
          accessKey,
          airGapInstall,
          formData.get().passwordlessSudoAccess,
          formData.get().installNodeExporter,
          formData.get().nodeExporterPort,
          formData.get().nodeExporterUser);
    }
    auditService().createAuditEntry(ctx(), request(), Json.toJson(formData.data()));
    return PlatformResults.withData(accessKey);
  }

  @ApiOperation(value = "Delete an access key", response = YBPSuccess.class)
  public Result delete(UUID customerUUID, UUID providerUUID, String keyCode) {
    Customer.getOrBadRequest(customerUUID);
    Provider.getOrBadRequest(customerUUID, providerUUID);
    AccessKey accessKey = AccessKey.getOrBadRequest(providerUUID, keyCode);
    LOG.info(
        "Deleting access key {} for customer {}, provider {}", keyCode, customerUUID, providerUUID);

    accessKey.deleteOrThrow();
    auditService().createAuditEntry(ctx(), request());
    return withMessage("Deleted KeyCode: " + keyCode);
  }
}
