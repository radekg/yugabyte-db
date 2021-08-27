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

import com.google.inject.Inject;
import com.typesafe.config.Config;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.config.impl.RuntimeConfig;
import com.yugabyte.yw.common.config.impl.SettableRuntimeConfigFactory;
import com.yugabyte.yw.forms.RuntimeConfigFormData;
import com.yugabyte.yw.forms.RuntimeConfigFormData.ConfigEntry;
import com.yugabyte.yw.forms.RuntimeConfigFormData.ScopedConfig;
import com.yugabyte.yw.forms.RuntimeConfigFormData.ScopedConfig.ScopeType;
import com.yugabyte.yw.forms.PlatformResults;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.RuntimeConfigEntry;
import com.yugabyte.yw.models.Universe;
import io.ebean.Model;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Result;

@Api(
    value = "Runtime configuration",
    authorizations = @Authorization(AbstractPlatformController.API_KEY_AUTH))
public class RuntimeConfController extends AuthenticatedController {
  private static final Logger LOG = LoggerFactory.getLogger(RuntimeConfController.class);
  private final SettableRuntimeConfigFactory settableRuntimeConfigFactory;
  private final Result mutableKeysResult;
  private final Set<String> mutableKeys;

  @Inject
  public RuntimeConfController(SettableRuntimeConfigFactory settableRuntimeConfigFactory) {
    this.settableRuntimeConfigFactory = settableRuntimeConfigFactory;
    this.mutableKeys = buildMutableKeysSet();
    this.mutableKeysResult = buildCachedResult();
  }

  private static RuntimeConfigFormData listScopesInternal(Customer customer) {
    boolean isSuperAdmin = TokenAuthenticator.superAdminAuthentication(ctx());
    RuntimeConfigFormData formData = new RuntimeConfigFormData();
    formData.addGlobalScope(isSuperAdmin);
    formData.addMutableScope(ScopeType.CUSTOMER, customer.uuid);
    Provider.getAll(customer.uuid)
        .forEach(provider -> formData.addMutableScope(ScopeType.PROVIDER, provider.uuid));
    Universe.getAllUUIDs(customer)
        .forEach(universeUUID -> formData.addMutableScope(ScopeType.UNIVERSE, universeUUID));
    return formData;
  }

  private static Optional<ScopedConfig> getScopedConfigInternal(UUID customerUUID, UUID scopeUUID) {
    RuntimeConfigFormData runtimeConfigFormData =
        listScopesInternal(Customer.getOrBadRequest(customerUUID));
    return runtimeConfigFormData
        .scopedConfigList
        .stream()
        .filter(config -> config.uuid.equals(scopeUUID))
        .findFirst();
  }

  private Result buildCachedResult() {
    return PlatformResults.withData(mutableKeys);
  }

  private Set<String> buildMutableKeysSet() {
    Config config = settableRuntimeConfigFactory.staticApplicationConf();
    List<String> included = config.getStringList("runtime_config.included_paths");
    List<String> excluded = config.getStringList("runtime_config.excluded_paths");
    return config
        .entrySet()
        .stream()
        .map(Map.Entry::getKey)
        .filter(
            key ->
                included.stream().anyMatch(key::startsWith)
                    && excluded.stream().noneMatch(key::startsWith))
        .collect(Collectors.toSet());
  }

  @ApiOperation(
      value = "List mutable keys",
      response = String.class,
      responseContainer = "List",
      notes = "List all the mutable runtime config keys")
  public Result listKeys() {
    return mutableKeysResult;
  }

  @ApiOperation(
      value = "List configuration scopes",
      response = RuntimeConfigFormData.class,
      notes =
          "Lists all (including empty scopes) runtime config scopes for current customer. "
              + "List includes the Global scope that spans multiple customers, scope for customer "
              + "specific overrides for current customer and one scope each for each universe and "
              + "provider.")
  public Result listScopes(UUID customerUUID) {
    return PlatformResults.withData(listScopesInternal(Customer.getOrBadRequest(customerUUID)));
  }

  @ApiOperation(
      value = "List configuration entries for a scope",
      response = RuntimeConfigFormData.class,
      notes = "Lists all runtime config entries for a given scope for current customer.")
  public Result getConfig(UUID customerUUID, UUID scopeUUID, boolean includeInherited) {
    LOG.trace(
        "customerUUID: {} scopeUUID: {} includeInherited: {}",
        customerUUID,
        scopeUUID,
        includeInherited);

    ScopedConfig scopedConfig = getScopedConfigOrFail(customerUUID, scopeUUID);
    Config fullConfig = scopedConfig.runtimeConfig(settableRuntimeConfigFactory);
    Map<String, String> overriddenInScope = RuntimeConfigEntry.getAsMapForScope(scopeUUID);
    for (String k : mutableKeys) {
      boolean isOverridden = overriddenInScope.containsKey(k);
      LOG.trace(
          "key: {} overriddenInScope: {} includeInherited: {}", k, isOverridden, includeInherited);

      if (isOverridden) {
        scopedConfig.configEntries.add(new ConfigEntry(false, k, fullConfig.getString(k)));
      } else if (includeInherited) {
        // Show entries even if not overridden in this scope. We will lookup value from fullConfig
        // for this scope
        scopedConfig.configEntries.add(new ConfigEntry(true, k, fullConfig.getString(k)));
      }
    }

    return PlatformResults.withData(scopedConfig);
  }

  @ApiOperation(
      value = "Get a configuration key",
      nickname = "getConfigurationKey",
      produces = "text/plain")
  public Result getKey(UUID customerUUID, UUID scopeUUID, String path) {
    if (!mutableKeys.contains(path))
      throw new PlatformServiceException(NOT_FOUND, "No mutable key found: " + path);

    Optional<ScopedConfig> scopedConfig = getScopedConfigInternal(customerUUID, scopeUUID);

    if (!scopedConfig.isPresent()) {
      throw new PlatformServiceException(
          NOT_FOUND, String.format("No scope %s  found for customer %s", scopeUUID, customerUUID));
    }

    RuntimeConfigEntry runtimeConfigEntry = RuntimeConfigEntry.getOrBadRequest(scopeUUID, path);
    return ok(runtimeConfigEntry.getValue());
  }

  @ApiOperation(value = "Update a configuration key", consumes = "text/plain")
  @ApiImplicitParams(
      @ApiImplicitParam(
          name = "newValue",
          value = "New value for config key",
          paramType = "body",
          dataType = "java.lang.String",
          required = true))
  public Result setKey(UUID customerUUID, UUID scopeUUID, String path) {
    String contentType = request().contentType().orElse("UNKNOWN");
    if (!contentType.equals("text/plain")) {
      throw new PlatformServiceException(
          UNSUPPORTED_MEDIA_TYPE, "Accepts: text/plain but content-type: " + contentType);
    }
    String newValue = request().body().asText();
    if (newValue == null) {
      throw new PlatformServiceException(BAD_REQUEST, "Cannot set null value");
    }

    if (!mutableKeys.contains(path)) {
      throw new PlatformServiceException(NOT_FOUND, "No mutable key found: " + path);
    }
    getMutableRuntimeConfigForScopeOrFail(customerUUID, scopeUUID).setValue(path, newValue);

    return ok();
  }

  @ApiOperation(value = "Delete a configuration key")
  public Result deleteKey(UUID customerUUID, UUID scopeUUID, String path) {
    if (!mutableKeys.contains(path))
      throw new PlatformServiceException(NOT_FOUND, "No mutable key found: " + path);

    getMutableRuntimeConfigForScopeOrFail(customerUUID, scopeUUID).deleteEntry(path);
    return ok();
  }

  private RuntimeConfig<? extends Model> getMutableRuntimeConfigForScopeOrFail(
      UUID customerUUID, UUID scopeUUID) {
    ScopedConfig scopedConfig = getScopedConfigOrFail(customerUUID, scopeUUID);
    if (!scopedConfig.mutableScope) {
      throw new PlatformServiceException(
          FORBIDDEN,
          "Customer "
              + customerUUID
              + " does not have access to mutate configuration for this scope "
              + scopeUUID);
    }
    return scopedConfig.runtimeConfig(settableRuntimeConfigFactory);
  }

  private ScopedConfig getScopedConfigOrFail(UUID customerUUID, UUID scopeUUID) {
    Optional<ScopedConfig> optScopedConfig = getScopedConfigInternal(customerUUID, scopeUUID);
    if (!optScopedConfig.isPresent()) {
      throw new PlatformServiceException(
          NOT_FOUND, String.format("No scope %s found for customer %s", scopeUUID, customerUUID));
    }
    return optScopedConfig.get();
  }
}
