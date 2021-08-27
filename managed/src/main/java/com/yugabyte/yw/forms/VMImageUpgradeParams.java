// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yugabyte.yw.cloud.PublicCloudConstants;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.NodeDetails;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import play.mvc.Http.Status;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(converter = VMImageUpgradeParams.Converter.class)
public class VMImageUpgradeParams extends UpgradeTaskParams {

  public Map<UUID, String> machineImages = new HashMap<>();
  public boolean forceVMImageUpgrade = false;

  @JsonIgnore public final Map<UUID, UUID> nodeToRegion = new HashMap<>();

  public VMImageUpgradeParams() {}

  @JsonCreator
  public VMImageUpgradeParams(
      @JsonProperty(value = "machineImages", required = true) Map<UUID, String> machineImages) {
    this.machineImages = machineImages;
  }

  @Override
  public void verifyParams(Universe universe) {
    super.verifyParams(universe);

    if (upgradeOption != UpgradeOption.ROLLING_UPGRADE) {
      throw new PlatformServiceException(
          Status.BAD_REQUEST, "Only ROLLING_UPGRADE option is supported for OS upgrades.");
    }

    UserIntent userIntent = universe.getUniverseDetails().getPrimaryCluster().userIntent;
    CloudType provider = userIntent.providerType;
    if (!(provider == CloudType.gcp || provider == CloudType.aws)) {
      throw new PlatformServiceException(
          Status.BAD_REQUEST,
          "VM image upgrade is only supported for AWS / GCP, got: " + provider.toString());
    }

    boolean hasEphemeralStorage = false;
    if (provider == CloudType.gcp) {
      if (userIntent.deviceInfo.storageType == PublicCloudConstants.StorageType.Scratch) {
        hasEphemeralStorage = true;
      }
    } else {
      if (universe.getUniverseDetails().getPrimaryCluster().isAwsClusterWithEphemeralStorage()) {
        hasEphemeralStorage = true;
      }
    }
    if (hasEphemeralStorage) {
      throw new PlatformServiceException(
          Status.BAD_REQUEST, "Cannot upgrade a universe with ephemeral storage.");
    }

    if (machineImages.isEmpty()) {
      throw new PlatformServiceException(Status.BAD_REQUEST, "machineImages param is required.");
    }

    nodeToRegion.clear();
    for (NodeDetails node : universe.getUniverseDetails().nodeDetailsSet) {
      if (node.isMaster || node.isTserver) {
        Region region =
            AvailabilityZone.maybeGet(node.azUuid)
                .map(az -> az.region)
                .orElseThrow(
                    () ->
                        new PlatformServiceException(
                            Status.BAD_REQUEST,
                            "Could not find region for AZ " + node.cloudInfo.az));

        if (!machineImages.containsKey(region.uuid)) {
          throw new PlatformServiceException(
              Status.BAD_REQUEST, "No VM image was specified for region " + node.cloudInfo.region);
        }

        nodeToRegion.putIfAbsent(node.nodeUuid, region.uuid);
      }
    }
  }

  public static class Converter extends BaseConverter<VMImageUpgradeParams> {}
}
