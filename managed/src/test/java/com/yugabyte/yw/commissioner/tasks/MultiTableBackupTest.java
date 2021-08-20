// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.yb.Common.TableType;
import org.yb.client.GetTableSchemaResponse;
import org.yb.client.ListTablesResponse;
import org.yb.client.YBClient;
import org.yb.master.Master;
import org.yb.master.Master.ListTablesResponsePB.TableInfo;

@RunWith(MockitoJUnitRunner.class)
public class MultiTableBackupTest extends CommissionerBaseTest {

  Universe defaultUniverse;
  YBClient mockClient;
  ListTablesResponse mockListTablesResponse;
  ListTablesResponse mockListTablesResponse1;
  ListTablesResponse mockListTablesResponse2;
  GetTableSchemaResponse mockSchemaResponse1;
  GetTableSchemaResponse mockSchemaResponse2;
  GetTableSchemaResponse mockSchemaResponse3;
  GetTableSchemaResponse mockSchemaResponse4;
  UUID table1UUID = UUID.randomUUID();
  UUID table2UUID = UUID.randomUUID();
  UUID table3UUID = UUID.randomUUID();
  UUID table4UUID = UUID.randomUUID();

  @Before
  public void setUp() {
    super.setUp();

    defaultCustomer = ModelFactory.testCustomer();
    defaultUniverse = ModelFactory.createUniverse();
    List<TableInfo> tableInfoList = new ArrayList<>();
    List<TableInfo> tableInfoList1 = new ArrayList<>();
    List<TableInfo> tableInfoList2 = new ArrayList<>();
    TableInfo ti1 =
        TableInfo.newBuilder()
            .setName("Table1")
            .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default0"))
            .setId(ByteString.copyFromUtf8(table1UUID.toString()))
            .setTableType(TableType.REDIS_TABLE_TYPE)
            .build();
    TableInfo ti2 =
        TableInfo.newBuilder()
            .setName("Table2")
            .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default1"))
            .setId(ByteString.copyFromUtf8(table2UUID.toString()))
            .setTableType(TableType.YQL_TABLE_TYPE)
            .build();
    TableInfo ti3 =
        TableInfo.newBuilder()
            .setName("Table3")
            .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default2"))
            .setId(ByteString.copyFromUtf8(table3UUID.toString()))
            .setTableType(TableType.PGSQL_TABLE_TYPE)
            .build();
    TableInfo ti4 =
        TableInfo.newBuilder()
            .setName("Table4")
            .setNamespace(Master.NamespaceIdentifierPB.newBuilder().setName("$$$Default2"))
            .setId(ByteString.copyFromUtf8(table4UUID.toString()))
            .setTableType(TableType.PGSQL_TABLE_TYPE)
            .build();
    tableInfoList.add(ti1);
    tableInfoList.add(ti2);
    tableInfoList.add(ti3);
    tableInfoList.add(ti4);
    tableInfoList1.add(ti1);
    tableInfoList2.add(ti3);
    tableInfoList2.add(ti4);
    mockClient = mock(YBClient.class);
    mockListTablesResponse = mock(ListTablesResponse.class);
    mockListTablesResponse1 = mock(ListTablesResponse.class);
    mockListTablesResponse2 = mock(ListTablesResponse.class);
    mockSchemaResponse1 = mock(GetTableSchemaResponse.class);
    mockSchemaResponse2 = mock(GetTableSchemaResponse.class);
    mockSchemaResponse3 = mock(GetTableSchemaResponse.class);
    mockSchemaResponse4 = mock(GetTableSchemaResponse.class);
    mockClient = mock(YBClient.class);
    when(mockYBClient.getClient(any(), any())).thenReturn(mockClient);
    try {
      when(mockClient.getTablesList(null, true, null)).thenReturn(mockListTablesResponse);
      when(mockClient.getTableSchemaByUUID(table1UUID.toString().replace("-", "")))
          .thenReturn(mockSchemaResponse1);
      when(mockClient.getTableSchemaByUUID(table2UUID.toString().replace("-", "")))
          .thenReturn(mockSchemaResponse2);
      when(mockClient.getTableSchemaByUUID(table3UUID.toString().replace("-", "")))
          .thenReturn(mockSchemaResponse3);
    } catch (Exception e) {
      // Do nothing.
    }
    when(mockListTablesResponse.getTableInfoList()).thenReturn(tableInfoList);
    when(mockSchemaResponse1.getTableType()).thenReturn(TableType.REDIS_TABLE_TYPE);
    when(mockSchemaResponse2.getTableName()).thenReturn("Table2");
    when(mockSchemaResponse2.getNamespace()).thenReturn("$$$Default1");
    when(mockSchemaResponse2.getTableType()).thenReturn(TableType.YQL_TABLE_TYPE);
    when(mockSchemaResponse3.getTableType()).thenReturn(TableType.PGSQL_TABLE_TYPE);
  }

  private TaskInfo submitTask(
      String keyspace, List<UUID> tableUUIDs, boolean transactional, TableType backupType) {
    MultiTableBackup.Params backupTableParams = new MultiTableBackup.Params();
    backupTableParams.universeUUID = defaultUniverse.universeUUID;
    backupTableParams.customerUUID = defaultCustomer.uuid;
    backupTableParams.setKeyspace(keyspace);
    backupTableParams.backupType = backupType;
    backupTableParams.storageConfigUUID = UUID.randomUUID();
    backupTableParams.tableUUIDList = tableUUIDs;
    backupTableParams.transactionalBackup = transactional;
    try {
      UUID taskUUID = commissioner.submit(TaskType.MultiTableBackup, backupTableParams);
      CustomerTask.create(
          defaultCustomer,
          defaultUniverse.universeUUID,
          taskUUID,
          CustomerTask.TargetType.Universe,
          CustomerTask.TaskType.Backup,
          "bar");
      return waitForTask(taskUUID);
    } catch (InterruptedException e) {
      assertNull(e.getMessage());
    }
    return null;
  }

  private TaskInfo submitTask(String keyspace, List<UUID> tableUUIDs) {
    return submitTask(keyspace, tableUUIDs, false, TableType.YQL_TABLE_TYPE);
  }

  @Test
  public void testMultiTableBackup() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.updateConfig(config);
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);

    // Entire universe backup, only YCQL tables
    TaskInfo taskInfo = submitTask(null, new ArrayList<>());
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testMultiTableBackupKeyspace() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.updateConfig(config);
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);

    TaskInfo taskInfo = submitTask("$$$Default1", new ArrayList<>());
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testTransactionalUniverseBackup() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.updateConfig(config);
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);

    TaskInfo taskInfo = submitTask(null, new ArrayList<>(), true, TableType.YQL_TABLE_TYPE);
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testMultiTableBackupList() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.updateConfig(config);
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);
    List<UUID> tableUUIDs = new ArrayList<>();
    tableUUIDs.add(table1UUID);
    tableUUIDs.add(table2UUID);
    tableUUIDs.add(table3UUID);
    TaskInfo taskInfo = submitTask("bar", tableUUIDs);
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testTransactionalMultiTableBackupList() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.updateConfig(config);
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);
    List<UUID> tableUUIDs = new ArrayList<>();
    tableUUIDs.add(table1UUID);
    tableUUIDs.add(table2UUID);
    tableUUIDs.add(table3UUID);
    // Adding random keyspace here because the number of keyspace keys and tables
    // must be equal in CREATE mode.
    TaskInfo taskInfo = submitTask("bar", tableUUIDs, true, TableType.YQL_TABLE_TYPE);
    // Note that since we don't backup YSQL tables directly, there will only be
    // two tables backed up (YEDIS and YCQL). Non-universe backups can only be for
    // a single keyspace so we expect the two tables to be backed up together.
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testYSQLBackupTables() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "true");
    defaultUniverse.updateConfig(config);
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"success\": true}";
    shellResponse.code = 0;
    when(mockTableManager.createBackup(any())).thenReturn(shellResponse);
    TaskInfo taskInfo =
        submitTask("$$$Default2", new ArrayList<>(), true, TableType.PGSQL_TABLE_TYPE);
    verify(mockTableManager, times(1)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }

  @Test
  public void testMultiTableBackupIgnore() {
    Map<String, String> config = new HashMap<>();
    config.put(Universe.TAKE_BACKUPS, "false");
    defaultUniverse.updateConfig(config);
    TaskInfo taskInfo = submitTask(null, new ArrayList<>());
    verify(mockTableManager, times(0)).createBackup(any());
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
  }
}
