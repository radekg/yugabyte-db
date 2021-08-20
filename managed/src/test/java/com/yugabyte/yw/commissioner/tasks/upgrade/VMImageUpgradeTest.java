// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.cloud.PublicCloudConstants.StorageType;
import com.yugabyte.yw.commissioner.Common.CloudType;
import com.yugabyte.yw.commissioner.tasks.params.NodeTaskParams;
import com.yugabyte.yw.commissioner.tasks.subtasks.CreateRootVolumes;
import com.yugabyte.yw.common.NodeManager.NodeCommandType;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.Cluster;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.VMImageUpgradeParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class VMImageUpgradeTest extends UpgradeTaskTest {

  private static class CreateRootVolumesMatcher implements ArgumentMatcher<NodeTaskParams> {
    private final UUID azUUID;

    public CreateRootVolumesMatcher(UUID azUUID) {
      this.azUUID = azUUID;
    }

    @Override
    public boolean matches(NodeTaskParams right) {
      if (!(right instanceof CreateRootVolumes.Params)) {
        return false;
      }

      return right.azUuid.equals(this.azUUID);
    }
  }

  @InjectMocks VMImageUpgrade vmImageUpgrade;

  List<TaskType> UPGRADE_TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.AnsibleClusterServerCtl,
          TaskType.AnsibleClusterServerCtl,
          TaskType.ReplaceRootVolume,
          TaskType.AnsibleSetupServer,
          TaskType.AnsibleConfigureServers,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.WaitForServerReady,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.WaitForServerReady,
          TaskType.WaitForEncryptionKeyInMemory,
          TaskType.UpdateNodeDetails);

  @Before
  public void setUp() {
    super.setUp();

    vmImageUpgrade.setUserTaskUUID(UUID.randomUUID());
  }

  private TaskInfo submitTask(VMImageUpgradeParams requestParams, int version) {
    return submitTask(requestParams, TaskType.VMImageUpgrade, commissioner, version);
  }

  @Test
  public void testVMImageUpgrade() {
    Region secondRegion = Region.create(defaultProvider, "region-2", "Region 2", "yb-image-1");
    AvailabilityZone az4 = AvailabilityZone.createOrThrow(secondRegion, "az-4", "AZ 4", "subnet-4");

    Universe.UniverseUpdater updater =
        universe -> {
          UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
          Cluster primaryCluster = universeDetails.getPrimaryCluster();
          UserIntent userIntent = primaryCluster.userIntent;
          userIntent.regionList = ImmutableList.of(region.uuid, secondRegion.uuid);

          PlacementInfo placementInfo = primaryCluster.placementInfo;
          PlacementInfoUtil.addPlacementZone(az4.uuid, placementInfo, 1, 2, false);
          universe.setUniverseDetails(universeDetails);

          for (int idx = userIntent.numNodes + 1; idx <= userIntent.numNodes + 2; idx++) {
            NodeDetails node = new NodeDetails();
            node.nodeIdx = idx;
            node.placementUuid = primaryCluster.uuid;
            node.nodeName = "host-n" + idx;
            node.isMaster = true;
            node.isTserver = true;
            node.cloudInfo = new CloudSpecificInfo();
            node.cloudInfo.private_ip = "1.2.3." + idx;
            node.cloudInfo.az = az4.code;
            node.azUuid = az4.uuid;
            universeDetails.nodeDetailsSet.add(node);
          }

          for (NodeDetails node : universeDetails.nodeDetailsSet) {
            node.nodeUuid = UUID.randomUUID();
          }

          userIntent.numNodes += 2;
          userIntent.providerType = CloudType.gcp;
          userIntent.deviceInfo = new DeviceInfo();
          userIntent.deviceInfo.storageType = StorageType.Persistent;
        };

    defaultUniverse = Universe.saveDetails(defaultUniverse.universeUUID, updater);

    VMImageUpgradeParams taskParams = new VMImageUpgradeParams();
    taskParams.clusters = defaultUniverse.getUniverseDetails().clusters;
    taskParams.machineImages.put(region.uuid, "test-vm-image-1");
    taskParams.machineImages.put(secondRegion.uuid, "test-vm-image-2");

    // expect a CreateRootVolume for each AZ
    final int expectedRootVolumeCreationTasks = 4;

    Map<UUID, List<String>> createVolumeOutput =
        Stream.of(az1, az2, az3)
            .collect(
                Collectors.toMap(
                    az -> az.uuid,
                    az -> Collections.singletonList(String.format("root-volume-%s", az.code))));
    // AZ 4 has 2 nodes so return 2 volumes here
    createVolumeOutput.put(az4.uuid, Arrays.asList("root-volume-4", "root-volume-5"));

    ObjectMapper om = new ObjectMapper();
    for (Map.Entry<UUID, List<String>> e : createVolumeOutput.entrySet()) {
      try {
        when(mockNodeManager.nodeCommand(
                eq(NodeCommandType.Create_Root_Volumes),
                argThat(new CreateRootVolumesMatcher(e.getKey()))))
            .thenReturn(ShellResponse.create(0, om.writeValueAsString(e.getValue())));
      } catch (JsonProcessingException ex) {
        throw new RuntimeException(ex);
      }
    }

    TaskInfo taskInfo = submitTask(taskParams, defaultUniverse.version);

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    int position = 0;
    List<TaskInfo> createRootVolumeTasks = subTasksByPosition.get(position++);
    assertTaskType(createRootVolumeTasks, TaskType.CreateRootVolumes);
    assertEquals(expectedRootVolumeCreationTasks, createRootVolumeTasks.size());

    createRootVolumeTasks.forEach(
        task -> {
          JsonNode details = task.getTaskDetails();
          UUID region = UUID.fromString(details.get("region").get("uuid").asText());
          String machineImage = details.get("machineImage").asText();
          assertEquals(taskParams.machineImages.get(region), machineImage);

          String azUUID = details.get("azUuid").asText();
          if (azUUID.equals(az4.uuid.toString())) {
            assertEquals(2, details.get("numVolumes").asInt());
          }
        });

    List<Integer> nodeOrder = Arrays.asList(1, 3, 4, 5, 2);

    Map<UUID, Integer> replaceRootVolumeParams = new HashMap<>();

    for (int nodeIdx : nodeOrder) {
      String nodeName = String.format("host-n%d", nodeIdx);
      for (TaskType type : UPGRADE_TASK_SEQUENCE) {
        List<TaskInfo> tasks = subTasksByPosition.get(position++);

        assertEquals(1, tasks.size());

        TaskInfo task = tasks.get(0);
        TaskType taskType = task.getTaskType();

        assertEquals(type, taskType);

        if (!NON_NODE_TASKS.contains(taskType)) {
          Map<String, Object> assertValues =
              new HashMap<>(ImmutableMap.of("nodeName", nodeName, "nodeCount", 1));

          assertNodeSubTask(tasks, assertValues);
        }

        if (taskType == TaskType.ReplaceRootVolume) {
          JsonNode details = task.getTaskDetails();
          UUID az = UUID.fromString(details.get("azUuid").asText());
          replaceRootVolumeParams.compute(az, (k, v) -> v == null ? 1 : v + 1);
        }
      }
    }

    assertEquals(createVolumeOutput.keySet(), replaceRootVolumeParams.keySet());
    createVolumeOutput.forEach(
        (key, value) -> assertEquals(value.size(), (int) replaceRootVolumeParams.get(key)));
    assertEquals(100.0, taskInfo.getPercentCompleted(), 0);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }
}
