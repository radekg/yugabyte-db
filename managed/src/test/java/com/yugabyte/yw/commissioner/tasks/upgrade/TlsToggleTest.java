// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.MASTER;
import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.TSERVER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.common.CertificateHelper;
import com.yugabyte.yw.common.TestHelper;
import com.yugabyte.yw.common.utils.Pair;
import com.yugabyte.yw.forms.TlsToggleParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;
import com.yugabyte.yw.forms.UpgradeTaskParams.UpgradeOption;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnitParamsRunner.class)
public class TlsToggleTest extends UpgradeTaskTest {

  @Rule public MockitoRule rule = MockitoJUnit.rule();

  @InjectMocks TlsToggle tlsToggle;

  List<TaskType> ROLLING_UPGRADE_TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.SetNodeState,
          TaskType.AnsibleConfigureServers,
          TaskType.AnsibleClusterServerCtl,
          TaskType.AnsibleClusterServerCtl,
          TaskType.WaitForServer,
          TaskType.WaitForServerReady,
          TaskType.WaitForEncryptionKeyInMemory,
          TaskType.SetNodeState);

  List<TaskType> NON_ROLLING_UPGRADE_TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.SetNodeState,
          TaskType.AnsibleConfigureServers,
          TaskType.AnsibleClusterServerCtl,
          TaskType.AnsibleClusterServerCtl,
          TaskType.SetNodeState,
          TaskType.WaitForServer);

  List<TaskType> NON_RESTART_UPGRADE_TASK_SEQUENCE =
      ImmutableList.of(
          TaskType.SetNodeState,
          TaskType.AnsibleConfigureServers,
          TaskType.SetFlagInMemory,
          TaskType.SetNodeState);

  @Before
  public void setUp() {
    super.setUp();

    MockitoAnnotations.initMocks(this);
    try {
      when(mockClient.setFlag(any(HostAndPort.class), anyString(), anyString(), anyBoolean()))
          .thenReturn(true);
    } catch (Exception ignored) {
    }
    tlsToggle.setUserTaskUUID(UUID.randomUUID());
  }

  private TaskInfo submitTask(TlsToggleParams requestParams) {
    return submitTask(requestParams, TaskType.TlsToggle, commissioner, -1);
  }

  private int assertCommonTasks(
      Map<Integer, List<TaskInfo>> subTasksByPosition,
      int startPosition,
      UpgradeOption upgradeOption,
      boolean isMetadataUpdateStep) {
    int position = startPosition;
    List<TaskType> commonNodeTasks = new ArrayList<>();
    if (upgradeOption == UpgradeOption.ROLLING_UPGRADE) {
      commonNodeTasks.add(TaskType.LoadBalancerStateChange);
    }
    if (isMetadataUpdateStep) {
      commonNodeTasks.addAll(ImmutableList.of(TaskType.UniverseSetTlsParams));
    }

    for (TaskType commonNodeTask : commonNodeTasks) {
      assertTaskType(subTasksByPosition.get(position), commonNodeTask);
      position++;
    }

    return position;
  }

  private int assertSequence(
      Map<Integer, List<TaskInfo>> subTasksByPosition,
      ServerType serverType,
      int startPosition,
      UpgradeOption option) {
    int position = startPosition;
    if (option == UpgradeOption.ROLLING_UPGRADE) {
      List<TaskType> taskSequence = ROLLING_UPGRADE_TASK_SEQUENCE;
      List<Integer> nodeOrder = getRollingUpgradeNodeOrder(serverType);

      for (int nodeIdx : nodeOrder) {
        String nodeName = String.format("host-n%d", nodeIdx);
        for (TaskType type : taskSequence) {
          List<TaskInfo> tasks = subTasksByPosition.get(position);
          TaskType taskType = tasks.get(0).getTaskType();
          assertEquals(1, tasks.size());
          assertEquals(type, taskType);
          if (!NON_NODE_TASKS.contains(taskType)) {
            Map<String, Object> assertValues =
                new HashMap<>(ImmutableMap.of("nodeName", nodeName, "nodeCount", 1));
            if (taskType.equals(TaskType.AnsibleConfigureServers)) {
              assertValues.putAll(ImmutableMap.of("processType", serverType.toString()));
            }
            assertNodeSubTask(tasks, assertValues);
          }
          position++;
        }
      }
    } else if (option == UpgradeOption.NON_ROLLING_UPGRADE) {
      for (TaskType type : NON_ROLLING_UPGRADE_TASK_SEQUENCE) {
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        TaskType taskType = assertTaskType(tasks, type);

        if (NON_NODE_TASKS.contains(taskType)) {
          assertEquals(1, tasks.size());
        } else {
          Map<String, Object> assertValues =
              new HashMap<>(
                  ImmutableMap.of(
                      "nodeNames",
                      (Object) ImmutableList.of("host-n1", "host-n2", "host-n3"),
                      "nodeCount",
                      3));
          if (taskType.equals(TaskType.AnsibleConfigureServers)) {
            assertValues.putAll(ImmutableMap.of("processType", serverType.toString()));
          }
          assertEquals(3, tasks.size());
          assertNodeSubTask(tasks, assertValues);
        }
        position++;
      }
    } else {
      for (TaskType type : NON_RESTART_UPGRADE_TASK_SEQUENCE) {
        List<TaskInfo> tasks = subTasksByPosition.get(position);
        TaskType taskType = assertTaskType(tasks, type);

        if (NON_NODE_TASKS.contains(taskType)) {
          assertEquals(1, tasks.size());
        } else {
          Map<String, Object> assertValues =
              new HashMap<>(
                  ImmutableMap.of(
                      "nodeNames",
                      (Object) ImmutableList.of("host-n1", "host-n2", "host-n3"),
                      "nodeCount",
                      3));
          if (taskType.equals(TaskType.AnsibleConfigureServers)) {
            assertValues.putAll(ImmutableMap.of("processType", serverType.toString()));
          }
          assertEquals(3, tasks.size());
          assertNodeSubTask(tasks, assertValues);
        }
        position++;
      }
    }

    return position;
  }

  private void prepareUniverse(
      boolean nodeToNode,
      boolean clientToNode,
      boolean rootAndClientRootCASame,
      UUID rootCA,
      UUID clientRootCA)
      throws IOException, NoSuchAlgorithmException {
    CertificateInfo.create(
        rootCA,
        defaultCustomer.uuid,
        "test1",
        new Date(),
        new Date(),
        "privateKey",
        TestHelper.TMP_PATH + "/ca.crt",
        CertificateInfo.Type.SelfSigned);

    CertificateInfo.create(
        clientRootCA,
        defaultCustomer.uuid,
        "test1",
        new Date(),
        new Date(),
        "privateKey",
        TestHelper.TMP_PATH + "/ca.crt",
        CertificateInfo.Type.SelfSigned);

    defaultUniverse =
        Universe.saveDetails(
            defaultUniverse.universeUUID,
            universe -> {
              UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
              PlacementInfo placementInfo = universeDetails.getPrimaryCluster().placementInfo;
              UserIntent userIntent = universeDetails.getPrimaryCluster().userIntent;
              userIntent.enableNodeToNodeEncrypt = nodeToNode;
              userIntent.enableClientToNodeEncrypt = clientToNode;
              universeDetails.allowInsecure = true;
              universeDetails.rootAndClientRootCASame = rootAndClientRootCASame;
              universeDetails.rootCA = null;
              if (CertificateHelper.isRootCARequired(
                  nodeToNode, clientToNode, rootAndClientRootCASame)) {
                universeDetails.rootCA = rootCA;
              }
              universeDetails.clientRootCA = null;
              if (CertificateHelper.isClientRootCARequired(
                  nodeToNode, clientToNode, rootAndClientRootCASame)) {
                universeDetails.clientRootCA = clientRootCA;
              }
              if (nodeToNode || clientToNode) {
                universeDetails.allowInsecure = false;
              }
              universeDetails.upsertPrimaryCluster(userIntent, placementInfo);
              universe.setUniverseDetails(universeDetails);
            },
            false);
  }

  private TlsToggleParams getTaskParams(
      boolean nodeToNode,
      boolean clientToNode,
      boolean rootAndClientRootCASame,
      UUID rootCA,
      UUID clientRootCA,
      UpgradeOption upgradeOption) {
    TlsToggleParams taskParams = new TlsToggleParams();
    taskParams.upgradeOption = upgradeOption;
    taskParams.enableNodeToNodeEncrypt = nodeToNode;
    taskParams.enableClientToNodeEncrypt = clientToNode;
    taskParams.rootAndClientRootCASame = rootAndClientRootCASame;
    taskParams.rootCA = rootCA;
    if (!taskParams.rootAndClientRootCASame) {
      taskParams.clientRootCA = clientRootCA;
    }
    return taskParams;
  }

  private Pair<UpgradeOption, UpgradeOption> getUpgradeOptions(
      int nodeToNodeChange, boolean isRolling) {
    if (isRolling) {
      return new Pair<>(
          nodeToNodeChange < 0 ? UpgradeOption.NON_RESTART_UPGRADE : UpgradeOption.ROLLING_UPGRADE,
          nodeToNodeChange > 0 ? UpgradeOption.NON_RESTART_UPGRADE : UpgradeOption.ROLLING_UPGRADE);
    } else {
      return new Pair<>(
          nodeToNodeChange < 0
              ? UpgradeOption.NON_RESTART_UPGRADE
              : UpgradeOption.NON_ROLLING_UPGRADE,
          nodeToNodeChange > 0
              ? UpgradeOption.NON_RESTART_UPGRADE
              : UpgradeOption.NON_ROLLING_UPGRADE);
    }
  }

  private Pair<Integer, Integer> getExpectedValues(TlsToggleParams taskParams) {
    int nodeToNodeChange = getNodeToNodeChange(taskParams.enableNodeToNodeEncrypt);
    int expectedPosition = 1;
    int expectedNumberOfInvocations = 0;

    if (taskParams.enableNodeToNodeEncrypt || taskParams.enableClientToNodeEncrypt) {
      expectedPosition += 1;
      expectedNumberOfInvocations += 3;
    }

    if (taskParams.upgradeOption == UpgradeOption.ROLLING_UPGRADE) {
      if (nodeToNodeChange != 0) {
        expectedPosition += 58;
        expectedNumberOfInvocations += 24;
      } else {
        expectedPosition += 50;
        expectedNumberOfInvocations += 18;
      }
    } else {
      if (nodeToNodeChange != 0) {
        expectedPosition += 20;
        expectedNumberOfInvocations += 24;
      } else {
        expectedPosition += 12;
        expectedNumberOfInvocations += 18;
      }
    }

    return new Pair<>(expectedPosition, expectedNumberOfInvocations);
  }

  private int getNodeToNodeChange(boolean enableNodeToNodeEncrypt) {
    return defaultUniverse
                .getUniverseDetails()
                .getPrimaryCluster()
                .userIntent
                .enableNodeToNodeEncrypt
            != enableNodeToNodeEncrypt
        ? (enableNodeToNodeEncrypt ? 1 : -1)
        : 0;
  }

  @Test
  public void testTlsToggleInvalidUpgradeOption() {
    TlsToggleParams taskParams = new TlsToggleParams();
    taskParams.enableNodeToNodeEncrypt = true;
    taskParams.upgradeOption = UpgradeOption.NON_RESTART_UPGRADE;
    TaskInfo taskInfo = submitTask(taskParams);
    if (taskInfo == null) {
      fail();
    }

    defaultUniverse.refresh();
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
    assertEquals(0, taskInfo.getSubTasks().size());
  }

  @Test
  public void testTlsToggleWithoutChangeInParams() {
    TlsToggleParams taskParams = new TlsToggleParams();
    TaskInfo taskInfo = submitTask(taskParams);
    if (taskInfo == null) {
      fail();
    }

    defaultUniverse.refresh();
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
    assertEquals(0, taskInfo.getSubTasks().size());
  }

  @Test
  public void testTlsToggleWithoutRootCa() {
    TlsToggleParams taskParams = new TlsToggleParams();
    taskParams.enableNodeToNodeEncrypt = true;
    TaskInfo taskInfo = submitTask(taskParams);
    if (taskInfo == null) {
      fail();
    }

    defaultUniverse.refresh();
    verify(mockNodeManager, times(0)).nodeCommand(any(), any());
    assertEquals(TaskInfo.State.Failure, taskInfo.getTaskState());
    assertEquals(0, taskInfo.getSubTasks().size());
  }

  @Test
  @Parameters({
    "true, true, false, false, true, true",
    "true, true, false, false, false, true",
    "true, false, false, false, true, true",
    "true, false, false, false, false, true",
    "false, true, false, true, true, true",
    "false, true, false, true, false, true",
    "false, false, false, true, true, true",
    "false, false, false, true, false, true",
    "true, true, false, true, false, true",
    "true, false, false, true, true, true",
    "false, true, false, false, false, true",
    "false, false, false, false, true, true",
    "true, true, true, false, true, true",
    "true, true, true, false, false, true",
    "true, false, true, false, true, true",
    "true, false, true, false, false, true",
    "false, true, true, true, true, true",
    "false, true, true, true, false, true",
    "false, false, true, true, true, true",
    "false, false, true, true, false, true",
    "true, true, true, true, false, true",
    "true, false, true, true, true, true",
    "false, true, true, false, false, true",
    "false, false, true, false, true, true",
    "true, true, true, false, true, false",
    "true, true, true, false, false, false",
    "true, false, true, false, true, false",
    "true, false, true, false, false, false",
    "false, true, true, true, true, false",
    "false, true, true, true, false, false",
    "false, false, true, true, true, false",
    "false, false, true, true, false, false",
    "true, true, true, true, false, false",
    "true, false, true, true, true, false",
    "false, true, true, false, false, false",
    "false, false, true, false, true, false",
    "true, true, false, false, true, false",
    "true, true, false, false, false, false",
    "true, false, false, false, true, false",
    "true, false, false, false, false, false",
    "false, true, false, true, true, false",
    "false, true, false, true, false, false",
    "false, false, false, true, true, false",
    "false, false, false, true, false, false",
    "true, true, false, true, false, false",
    "true, false, false, true, true, false",
    "false, true, false, false, false, false",
    "false, false, false, false, true, false"
  })
  @TestCaseName(
      "testTlsNonRollingUpgradeWhen"
          + "CurrNodeToNode:{0}_CurrClientToNode:{1}_CurrRootAndClientRootCASame:{2}"
          + "_NodeToNode:{3}_ClientToNode:{4}_RootAndClientRootCASame:{5}")
  public void testTlsNonRollingUpgrade(
      boolean currentNodeToNode,
      boolean currentClientToNode,
      boolean currRootAndClientRootCASame,
      boolean nodeToNode,
      boolean clientToNode,
      boolean rootAndClientRootCASame)
      throws IOException, NoSuchAlgorithmException {
    UUID rootCA = UUID.randomUUID();
    UUID clientRootCA = UUID.randomUUID();
    prepareUniverse(
        currentNodeToNode, currentClientToNode, currRootAndClientRootCASame, rootCA, clientRootCA);
    TlsToggleParams taskParams =
        getTaskParams(
            nodeToNode,
            clientToNode,
            rootAndClientRootCASame,
            rootCA,
            clientRootCA,
            UpgradeOption.NON_ROLLING_UPGRADE);

    int nodeToNodeChange = getNodeToNodeChange(nodeToNode);
    Pair<UpgradeOption, UpgradeOption> upgrade = getUpgradeOptions(nodeToNodeChange, false);
    Pair<Integer, Integer> expectedValues = getExpectedValues(taskParams);

    TaskInfo taskInfo = submitTask(taskParams);
    if (taskInfo == null) {
      fail();
    }

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    int position = 0;
    if (taskParams.enableNodeToNodeEncrypt || taskParams.enableClientToNodeEncrypt) {
      // Cert update tasks will be non rolling
      List<TaskInfo> certUpdateTasks = subTasksByPosition.get(position++);
      assertTaskType(certUpdateTasks, TaskType.AnsibleConfigureServers);
      assertEquals(3, certUpdateTasks.size());
    }
    // First round gflag update tasks
    position = assertSequence(subTasksByPosition, MASTER, position, upgrade.first);
    position = assertSequence(subTasksByPosition, TSERVER, position, upgrade.first);
    position = assertCommonTasks(subTasksByPosition, position, upgrade.first, true);
    if (nodeToNodeChange != 0) {
      // Second round gflag update tasks
      position = assertSequence(subTasksByPosition, MASTER, position, upgrade.second);
      position = assertSequence(subTasksByPosition, TSERVER, position, upgrade.second);
    }

    assertEquals((int) expectedValues.first, position);
    assertEquals(100.0, taskInfo.getPercentCompleted(), 0);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    verify(mockNodeManager, times(expectedValues.second)).nodeCommand(any(), any());

    Universe universe = Universe.getOrBadRequest(defaultUniverse.getUniverseUUID());
    if (CertificateHelper.isRootCARequired(nodeToNode, clientToNode, rootAndClientRootCASame)) {
      assertEquals(rootCA, universe.getUniverseDetails().rootCA);
    } else {
      assertNull(universe.getUniverseDetails().rootCA);
    }
    if (CertificateHelper.isClientRootCARequired(
        nodeToNode, clientToNode, rootAndClientRootCASame)) {
      assertEquals(clientRootCA, universe.getUniverseDetails().clientRootCA);
    } else {
      assertNull(universe.getUniverseDetails().clientRootCA);
    }
    assertEquals(
        nodeToNode,
        universe.getUniverseDetails().getPrimaryCluster().userIntent.enableNodeToNodeEncrypt);
    assertEquals(
        clientToNode,
        universe.getUniverseDetails().getPrimaryCluster().userIntent.enableClientToNodeEncrypt);
    assertEquals(rootAndClientRootCASame, universe.getUniverseDetails().rootAndClientRootCASame);
  }

  @Test
  @Parameters({
    "true, true, false, false, true, true",
    "true, true, false, false, false, true",
    "true, false, false, false, true, true",
    "true, false, false, false, false, true",
    "false, true, false, true, true, true",
    "false, true, false, true, false, true",
    "false, false, false, true, true, true",
    "false, false, false, true, false, true",
    "true, true, false, true, false, true",
    "true, false, false, true, true, true",
    "false, true, false, false, false, true",
    "false, false, false, false, true, true",
    "true, true, true, false, true, true",
    "true, true, true, false, false, true",
    "true, false, true, false, true, true",
    "true, false, true, false, false, true",
    "false, true, true, true, true, true",
    "false, true, true, true, false, true",
    "false, false, true, true, true, true",
    "false, false, true, true, false, true",
    "true, true, true, true, false, true",
    "true, false, true, true, true, true",
    "false, true, true, false, false, true",
    "false, false, true, false, true, true",
    "true, true, true, false, true, false",
    "true, true, true, false, false, false",
    "true, false, true, false, true, false",
    "true, false, true, false, false, false",
    "false, true, true, true, true, false",
    "false, true, true, true, false, false",
    "false, false, true, true, true, false",
    "false, false, true, true, false, false",
    "true, true, true, true, false, false",
    "true, false, true, true, true, false",
    "false, true, true, false, false, false",
    "false, false, true, false, true, false",
    "true, true, false, false, true, false",
    "true, true, false, false, false, false",
    "true, false, false, false, true, false",
    "true, false, false, false, false, false",
    "false, true, false, true, true, false",
    "false, true, false, true, false, false",
    "false, false, false, true, true, false",
    "false, false, false, true, false, false",
    "true, true, false, true, false, false",
    "true, false, false, true, true, false",
    "false, true, false, false, false, false",
    "false, false, false, false, true, false"
  })
  @TestCaseName(
      "testTlsRollingUpgradeWhen"
          + "CurrNodeToNode:{0}_CurrClientToNode:{1}_CurrRootAndClientRootCASame:{2}"
          + "_NodeToNode:{3}_ClientToNode:{4}_RootAndClientRootCASame:{5}")
  public void testTlsRollingUpgrade(
      boolean currentNodeToNode,
      boolean currentClientToNode,
      boolean currRootAndClientRootCASame,
      boolean nodeToNode,
      boolean clientToNode,
      boolean rootAndClientRootCASame)
      throws IOException, NoSuchAlgorithmException {
    UUID rootCA = UUID.randomUUID();
    UUID clientRootCA = UUID.randomUUID();
    prepareUniverse(
        currentNodeToNode, currentClientToNode, currRootAndClientRootCASame, rootCA, clientRootCA);
    TlsToggleParams taskParams =
        getTaskParams(
            nodeToNode,
            clientToNode,
            rootAndClientRootCASame,
            rootCA,
            clientRootCA,
            UpgradeOption.ROLLING_UPGRADE);

    int nodeToNodeChange = getNodeToNodeChange(nodeToNode);
    Pair<UpgradeOption, UpgradeOption> upgrade = getUpgradeOptions(nodeToNodeChange, true);
    Pair<Integer, Integer> expectedValues = getExpectedValues(taskParams);

    TaskInfo taskInfo = submitTask(taskParams);
    if (taskInfo == null) {
      fail();
    }

    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));

    int position = 0;
    if (taskParams.enableNodeToNodeEncrypt || taskParams.enableClientToNodeEncrypt) {
      // Cert update tasks will be non rolling
      List<TaskInfo> certUpdateTasks = subTasksByPosition.get(position++);
      assertTaskType(certUpdateTasks, TaskType.AnsibleConfigureServers);
      assertEquals(3, certUpdateTasks.size());
    }
    // First round gflag update tasks
    position = assertSequence(subTasksByPosition, MASTER, position, upgrade.first);
    position = assertCommonTasks(subTasksByPosition, position, upgrade.first, false);
    position = assertSequence(subTasksByPosition, TSERVER, position, upgrade.first);
    position = assertCommonTasks(subTasksByPosition, position, upgrade.first, true);
    if (nodeToNodeChange != 0) {
      // Second round gflag update tasks
      position = assertSequence(subTasksByPosition, MASTER, position, upgrade.second);
      position = assertCommonTasks(subTasksByPosition, position, upgrade.second, false);
      position = assertSequence(subTasksByPosition, TSERVER, position, upgrade.second);
      position = assertCommonTasks(subTasksByPosition, position, upgrade.second, false);
    }

    assertEquals((int) expectedValues.first, position);
    assertEquals(100.0, taskInfo.getPercentCompleted(), 0);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    verify(mockNodeManager, times(expectedValues.second)).nodeCommand(any(), any());

    Universe universe = Universe.getOrBadRequest(defaultUniverse.getUniverseUUID());
    if (CertificateHelper.isRootCARequired(nodeToNode, clientToNode, rootAndClientRootCASame)) {
      assertEquals(rootCA, universe.getUniverseDetails().rootCA);
    } else {
      assertNull(universe.getUniverseDetails().rootCA);
    }
    if (CertificateHelper.isClientRootCARequired(
        nodeToNode, clientToNode, rootAndClientRootCASame)) {
      assertEquals(clientRootCA, universe.getUniverseDetails().clientRootCA);
    } else {
      assertNull(universe.getUniverseDetails().clientRootCA);
    }
    assertEquals(
        nodeToNode,
        universe.getUniverseDetails().getPrimaryCluster().userIntent.enableNodeToNodeEncrypt);
    assertEquals(
        clientToNode,
        universe.getUniverseDetails().getPrimaryCluster().userIntent.enableClientToNodeEncrypt);
    assertEquals(rootAndClientRootCASame, universe.getUniverseDetails().rootAndClientRootCASame);
  }
}
