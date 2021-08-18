// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks.upgrade;

import static com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType.MASTER;
import static com.yugabyte.yw.common.TestHelper.createTempFile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.CommissionerBaseTest;
import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.common.ApiUtils;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.TestHelper;
import com.yugabyte.yw.forms.CertificateParams;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.forms.UpgradeTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.CertificateInfo;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.TaskType;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Before;
import org.yb.client.IsServerReadyResponse;
import org.yb.client.YBClient;

public abstract class UpgradeTaskTest extends CommissionerBaseTest {

  public enum UpgradeType {
    ROLLING_UPGRADE,
    ROLLING_UPGRADE_MASTER_ONLY,
    ROLLING_UPGRADE_TSERVER_ONLY,
    FULL_UPGRADE,
    FULL_UPGRADE_MASTER_ONLY,
    FULL_UPGRADE_TSERVER_ONLY
  }

  protected YBClient mockClient;
  protected Universe defaultUniverse;
  protected ShellResponse dummyShellResponse;

  protected Region region;
  protected AvailabilityZone az1;
  protected AvailabilityZone az2;
  protected AvailabilityZone az3;

  protected String certContents =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDEjCCAfqgAwIBAgIUEdzNoxkMLrZCku6H1jQ4pUgPtpQwDQYJKoZIhvcNAQEL\n"
          + "BQAwLzERMA8GA1UECgwIWXVnYWJ5dGUxGjAYBgNVBAMMEUNBIGZvciBZdWdhYnl0\n"
          + "ZURCMB4XDTIwMTIyMzA3MjU1MVoXDTIxMDEyMjA3MjU1MVowLzERMA8GA1UECgwI\n"
          + "WXVnYWJ5dGUxGjAYBgNVBAMMEUNBIGZvciBZdWdhYnl0ZURCMIIBIjANBgkqhkiG\n"
          + "9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuLPcCR1KpVSs3B2515xNAR8ntfhOM5JjLl6Y\n"
          + "WjqoyRQ4wiOg5fGQpvjsearpIntr5t6uMevpzkDMYY4U21KbIW8Vvg/kXiASKMmM\n"
          + "W4ToH3Q0NfgLUNb5zJ8df3J2JZ5CgGSpipL8VPWsuSZvrqL7V77n+ndjMTUBNf57\n"
          + "eW4VjzYq+YQhyloOlXtlfWT6WaAsoaVOlbU0GK4dE2rdeo78p2mS2wQZSBDXovpp\n"
          + "0TX4zhT6LsJaRBZe49GE4SMkxz74alK1ezRvFcrPiNKr5NOYYG9DUUqFHWg47Bmw\n"
          + "KbiZ5dLdyxgWRDbanwl2hOMfExiJhHE7pqgr8XcizCiYuUzlDwIDAQABoyYwJDAO\n"
          + "BgNVHQ8BAf8EBAMCAuQwEgYDVR0TAQH/BAgwBgEB/wIBATANBgkqhkiG9w0BAQsF\n"
          + "AAOCAQEAVI3NTJVNX4XTcVAxXXGumqCbKu9CCLhXxaJb+J8YgmMQ+s9lpmBuC1eB\n"
          + "38YFdSEG9dKXZukdQcvpgf4ryehtvpmk03s/zxNXC5237faQQqejPX5nm3o35E3I\n"
          + "ZQqN3h+mzccPaUvCaIlvYBclUAt4VrVt/W66kLFPsfUqNKVxm3B56VaZuQL1ZTwG\n"
          + "mrIYBoaVT/SmEeIX9PNjlTpprDN/oE25fOkOxwHyI9ydVFkMCpBNRv+NisQN9c+R\n"
          + "/SBXfs+07aqFgrGTte6/I4VZ/6vz2cWMwZU+TUg/u0fc0Y9RzOuJrZBV2qPAtiEP\n"
          + "YvtLjmJF//b3rsty6NFIonSVgq6Nqw==\n"
          + "-----END CERTIFICATE-----\n";

  protected List<String> PROPERTY_KEYS = ImmutableList.of("processType", "taskSubType");

  protected List<TaskType> NON_NODE_TASKS =
      ImmutableList.of(
          TaskType.LoadBalancerStateChange,
          TaskType.UpdateAndPersistGFlags,
          TaskType.UpdateSoftwareVersion,
          TaskType.UnivSetCertificate,
          TaskType.UniverseSetTlsParams,
          TaskType.UniverseUpdateSucceeded);

  @Before
  public void setUp() {
    super.setUp();

    // Create test region and Availability Zones
    region = Region.create(defaultProvider, "region-1", "Region 1", "yb-image-1");
    az1 = AvailabilityZone.createOrThrow(region, "az-1", "AZ 1", "subnet-1");
    az2 = AvailabilityZone.createOrThrow(region, "az-2", "AZ 2", "subnet-2");
    az3 = AvailabilityZone.createOrThrow(region, "az-3", "AZ 3", "subnet-3");

    // Create test certificate
    UUID certUUID = UUID.randomUUID();
    Date date = new Date();
    CertificateParams.CustomCertInfo customCertInfo = new CertificateParams.CustomCertInfo();
    customCertInfo.rootCertPath = "rootCertPath";
    customCertInfo.nodeCertPath = "nodeCertPath";
    customCertInfo.nodeKeyPath = "nodeKeyPath";
    new File(TestHelper.TMP_PATH).mkdirs();
    createTempFile("ca.crt", certContents);
    try {
      CertificateInfo.create(
          certUUID,
          defaultCustomer.uuid,
          "test",
          date,
          date,
          TestHelper.TMP_PATH + "/ca.crt",
          customCertInfo);
    } catch (IOException | NoSuchAlgorithmException ignored) {
    }

    // Create default universe
    UniverseDefinitionTaskParams.UserIntent userIntent =
        new UniverseDefinitionTaskParams.UserIntent();
    userIntent.numNodes = 3;
    userIntent.ybSoftwareVersion = "old-version";
    userIntent.accessKeyCode = "demo-access";
    userIntent.regionList = ImmutableList.of(region.uuid);
    defaultUniverse = ModelFactory.createUniverse(defaultCustomer.getCustomerId(), certUUID);
    PlacementInfo placementInfo = new PlacementInfo();
    PlacementInfoUtil.addPlacementZone(az1.uuid, placementInfo, 1, 1, false);
    PlacementInfoUtil.addPlacementZone(az2.uuid, placementInfo, 1, 1, true);
    PlacementInfoUtil.addPlacementZone(az3.uuid, placementInfo, 1, 1, false);
    defaultUniverse =
        Universe.saveDetails(
            defaultUniverse.universeUUID,
            ApiUtils.mockUniverseUpdater(userIntent, placementInfo, true));

    // Setup mocks
    mockClient = mock(YBClient.class);
    try {
      when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
      when(mockClient.waitForServer(any(HostAndPort.class), anyLong())).thenReturn(true);
      when(mockClient.getLeaderMasterHostAndPort())
          .thenReturn(HostAndPort.fromString("host-n2").withDefaultPort(11));
      IsServerReadyResponse okReadyResp = new IsServerReadyResponse(0, "", null, 0, 0);
      when(mockClient.isServerReady(any(HostAndPort.class), anyBoolean())).thenReturn(okReadyResp);
    } catch (Exception ignored) {
    }

    // Create dummy shell response
    dummyShellResponse = new ShellResponse();
    when(mockNodeManager.nodeCommand(any(), any())).thenReturn(dummyShellResponse);
  }

  protected TaskInfo submitTask(
      UpgradeTaskParams taskParams, TaskType taskType, Commissioner commissioner) {
    return submitTask(taskParams, taskType, commissioner, 2);
  }

  protected TaskInfo submitTask(
      UpgradeTaskParams taskParams,
      TaskType taskType,
      Commissioner commissioner,
      int expectedVersion) {
    taskParams.universeUUID = defaultUniverse.universeUUID;
    taskParams.expectedUniverseVersion = expectedVersion;
    // Need not sleep for default 4min in tests.
    taskParams.sleepAfterMasterRestartMillis = 5;
    taskParams.sleepAfterTServerRestartMillis = 5;

    try {
      UUID taskUUID = commissioner.submit(taskType, taskParams);
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  protected List<Integer> getRollingUpgradeNodeOrder(ServerType serverType) {
    return serverType == MASTER
        ?
        // We need to check that the master leader is upgraded last.
        Arrays.asList(1, 3, 2)
        :
        // We need to check that isAffinitized zone node is upgraded first.
        defaultUniverse.getUniverseDetails().getReadOnlyClusters().isEmpty()
            ? Arrays.asList(2, 1, 3)
            :
            // Primary cluster first, then read replica.
            Arrays.asList(2, 1, 3, 6, 4, 5);
  }

  protected TaskType assertTaskType(List<TaskInfo> tasks, TaskType expectedTaskType) {
    TaskType taskType = tasks.get(0).getTaskType();
    assertEquals(expectedTaskType, taskType);
    return taskType;
  }

  protected void assertNodeSubTask(List<TaskInfo> subTasks, Map<String, Object> assertValues) {
    List<String> nodeNames =
        subTasks
            .stream()
            .map(t -> t.getTaskDetails().get("nodeName").textValue())
            .collect(Collectors.toList());
    int nodeCount = (int) assertValues.getOrDefault("nodeCount", 1);
    assertEquals(nodeCount, nodeNames.size());
    if (nodeCount == 1) {
      assertEquals(assertValues.get("nodeName"), nodeNames.get(0));
    } else {
      assertTrue(nodeNames.containsAll((List) assertValues.get("nodeNames")));
    }

    List<JsonNode> subTaskDetails =
        subTasks.stream().map(TaskInfo::getTaskDetails).collect(Collectors.toList());
    assertValues.forEach(
        (expectedKey, expectedValue) -> {
          if (!ImmutableList.of("nodeName", "nodeNames", "nodeCount").contains(expectedKey)) {
            List<Object> values =
                subTaskDetails
                    .stream()
                    .map(
                        t -> {
                          JsonNode data =
                              PROPERTY_KEYS.contains(expectedKey)
                                  ? t.get("properties").get(expectedKey)
                                  : t.get(expectedKey);
                          return data.isObject() ? data : data.textValue();
                        })
                    .collect(Collectors.toList());
            values.forEach((actualValue) -> assertEquals(actualValue, expectedValue));
          }
        });
  }
}
