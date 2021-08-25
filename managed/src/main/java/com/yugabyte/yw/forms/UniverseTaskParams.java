// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.forms;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yugabyte.yw.common.kms.util.AwsEARServiceUtil.KeyType;
import com.yugabyte.yw.models.AsyncReplicationRelationship;
import com.yugabyte.yw.models.helpers.DeviceInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import io.ebean.annotation.EnumValue;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import play.data.validation.Constraints;

public class UniverseTaskParams extends AbstractTaskParams {

  @ApiModel(description = "Encryption at rest configuration")
  public static class EncryptionAtRestConfig {
    public enum OpType {
      @EnumValue("ENABLE")
      ENABLE,
      @EnumValue("DISABLE")
      DISABLE,
      @EnumValue("UNDEFINED")
      UNDEFINED;
    }

    // Whether a universe is currently encrypted at rest or not
    @ApiModelProperty(value = "Whether a universe is currently encrypted at rest")
    public boolean encryptionAtRestEnabled;

    // The KMS Configuration associated with the encryption keys being used on this universe
    @JsonAlias({"configUUID"})
    @ApiModelProperty(value = "KMS configuration UUID")
    public UUID kmsConfigUUID;

    // Whether to enable/disable/rotate universe key/encryption at rest
    @JsonAlias({"key_op"})
    @ApiModelProperty(
        value = "Operation type: enable, disable, or rotate the universe key/encryption at rest")
    public OpType opType;

    // Whether to generate a data key or just retrieve the CMK arn
    @JsonAlias({"key_type"})
    @ApiModelProperty(value = "Whether to generate a data key or just retrieve the CMK ARN")
    public KeyType type;

    public EncryptionAtRestConfig() {
      this.encryptionAtRestEnabled = false;
      this.kmsConfigUUID = null;
      this.type = KeyType.DATA_KEY;
      this.opType = OpType.UNDEFINED;
    }

    public EncryptionAtRestConfig(EncryptionAtRestConfig config) {
      this.encryptionAtRestEnabled = config.encryptionAtRestEnabled;
      this.kmsConfigUUID = config.kmsConfigUUID;
      this.type = config.type;
      this.opType = config.opType;
    }

    public EncryptionAtRestConfig clone() {
      return new EncryptionAtRestConfig(this);
    }
  }

  @ApiModel(description = "Communication ports")
  public static class CommunicationPorts {
    public CommunicationPorts() {
      // Set default port values.
      exportToCommunicationPorts(this);
    }

    // Ports that are customizable universe-wide.
    @ApiModelProperty(value = "Master table HTTP port")
    public int masterHttpPort;

    @ApiModelProperty(value = "Master table RCP port")
    public int masterRpcPort;

    @ApiModelProperty(value = "Tablet server HTTP port")
    public int tserverHttpPort;

    @ApiModelProperty(value = "Tablet server RPC port")
    public int tserverRpcPort;

    @ApiModelProperty(value = "Redis HTTP port")
    public int redisServerHttpPort;

    @ApiModelProperty(value = "Redis RPC port")
    public int redisServerRpcPort;

    @ApiModelProperty(value = "YQL HTTP port")
    public int yqlServerHttpPort;

    @ApiModelProperty(value = "YQL RPC port")
    public int yqlServerRpcPort;

    @ApiModelProperty(value = "YSQL HTTP port")
    public int ysqlServerHttpPort;

    @ApiModelProperty(value = "YSQL RPC port")
    public int ysqlServerRpcPort;

    @ApiModelProperty(value = "Node exporter port")
    public int nodeExporterPort;

    public static CommunicationPorts exportToCommunicationPorts(NodeDetails node) {
      return exportToCommunicationPorts(new CommunicationPorts(), node);
    }

    public static CommunicationPorts exportToCommunicationPorts(CommunicationPorts portsObj) {
      return exportToCommunicationPorts(portsObj, new NodeDetails());
    }

    public static CommunicationPorts exportToCommunicationPorts(
        CommunicationPorts portsObj, NodeDetails node) {
      portsObj.masterHttpPort = node.masterHttpPort;
      portsObj.masterRpcPort = node.masterRpcPort;
      portsObj.tserverHttpPort = node.tserverHttpPort;
      portsObj.tserverRpcPort = node.tserverRpcPort;
      portsObj.redisServerHttpPort = node.redisServerHttpPort;
      portsObj.redisServerRpcPort = node.redisServerRpcPort;
      portsObj.yqlServerHttpPort = node.yqlServerHttpPort;
      portsObj.yqlServerRpcPort = node.yqlServerRpcPort;
      portsObj.ysqlServerHttpPort = node.ysqlServerHttpPort;
      portsObj.ysqlServerRpcPort = node.ysqlServerRpcPort;
      portsObj.nodeExporterPort = node.nodeExporterPort;

      return portsObj;
    }

    public static void setCommunicationPorts(CommunicationPorts ports, NodeDetails node) {
      node.masterHttpPort = ports.masterHttpPort;
      node.masterRpcPort = ports.masterRpcPort;
      node.tserverHttpPort = ports.tserverHttpPort;
      node.tserverRpcPort = ports.tserverRpcPort;
      node.redisServerHttpPort = ports.redisServerHttpPort;
      node.redisServerRpcPort = ports.redisServerRpcPort;
      node.yqlServerHttpPort = ports.yqlServerHttpPort;
      node.yqlServerRpcPort = ports.yqlServerRpcPort;
      node.ysqlServerHttpPort = ports.ysqlServerHttpPort;
      node.ysqlServerRpcPort = ports.ysqlServerRpcPort;
      node.nodeExporterPort = ports.nodeExporterPort;
    }
  }

  @ApiModel(description = "Extra dependencies")
  public static class ExtraDependencies {
    // Flag to install node_exporter on nodes.
    @ApiModelProperty(value = "Install node exporter on nodes")
    public boolean installNodeExporter = true;
  }

  public static class AsyncReplicationConfig {
    @Constraints.Required() public String sourceTableID;

    @Constraints.Required() public UUID sourceUniverseUUID;

    @Constraints.Required() public String targetTableID;

    @Constraints.Required() public UUID targetUniverseUUID;

    @Constraints.Required() public boolean active;

    public static AsyncReplicationConfig convert(AsyncReplicationRelationship relationship) {
      AsyncReplicationConfig config = new AsyncReplicationConfig();
      config.targetUniverseUUID = relationship.targetUniverse.universeUUID;
      config.targetTableID = relationship.targetTableID;
      config.sourceUniverseUUID = relationship.sourceUniverse.universeUUID;
      config.sourceTableID = relationship.sourceTableID;
      config.active = relationship.active;
      return config;
    }

    @Override
    public String toString() {
      return "AsyncReplicationConfig "
          + "sourceTableID='"
          + sourceTableID
          + "', sourceUniverseUUID="
          + sourceUniverseUUID
          + ", targetTableID='"
          + targetTableID
          + "', targetUniverseUUID="
          + targetUniverseUUID
          + "', active="
          + active;
    }
  }

  @JsonProperty(
      value = "targetAsyncReplicationRelationships",
      access = JsonProperty.Access.READ_ONLY)
  @ApiModelProperty(value = "The target universe's async replication relationships")
  public List<AsyncReplicationConfig> getTargetAsyncReplicationRelationships() {
    if (universeUUID == null) {
      return new ArrayList<>();
    }

    return AsyncReplicationRelationship.getByTargetUniverseUUID(universeUUID)
        .stream()
        .map(AsyncReplicationConfig::convert)
        .collect(Collectors.toList());
  }

  @JsonProperty(
      value = "sourceAsyncReplicationRelationships",
      access = JsonProperty.Access.READ_ONLY)
  @ApiModelProperty(value = "The source universe's sync replication relationships")
  public List<AsyncReplicationConfig> getSourceAsyncReplicationRelationships() {
    if (universeUUID == null) {
      return new ArrayList<>();
    }

    return AsyncReplicationRelationship.getBySourceUniverseUUID(universeUUID)
        .stream()
        .map(AsyncReplicationConfig::convert)
        .collect(Collectors.toList());
  }

  // Which user to run the node exporter service on nodes with
  @ApiModelProperty(value = "Node exporter user")
  public String nodeExporterUser = "prometheus";

  // The primary device info.
  @ApiModelProperty(value = "Device information")
  public DeviceInfo deviceInfo;

  // The universe against which this operation is being executed.
  @ApiModelProperty(value = "Associated universe UUID")
  public UUID universeUUID;

  // Previous version used for task info.
  @ApiModelProperty(value = "Previous software version")
  public String ybPrevSoftwareVersion;

  // Expected version of the universe for operation execution. Set to -1 if an operation should
  // not verify expected version of the universe.
  @ApiModelProperty(
      value = "Expected universe version",
      notes = "The expected version of the universe. Set to -1 to skip version checking.")
  public Integer expectedUniverseVersion;

  // If an AWS backed universe has chosen EBS volume encryption, this will be set to the
  // Amazon Resource Name (ARN) of the CMK to be used to generate data keys for volume encryption
  @ApiModelProperty(value = "Amazon Resource Name (ARN) of the CMK")
  public String cmkArn;

  // Store encryption key provider specific configuration/authorization values
  @ApiModelProperty(value = "Encryption at rest configation")
  public EncryptionAtRestConfig encryptionAtRestConfig = new EncryptionAtRestConfig();

  // The set of nodes that are part of this universe. Should contain nodes in both primary and
  // readOnly clusters.
  @ApiModelProperty(value = "Node details")
  public Set<NodeDetails> nodeDetailsSet = null;

  // A list of ports to configure different parts of YB to listen on.
  @ApiModelProperty(value = "Communication ports")
  public CommunicationPorts communicationPorts = new CommunicationPorts();

  // Dependencies that can be install on nodes or not
  @ApiModelProperty(value = "Extra dependencies")
  public ExtraDependencies extraDependencies = new ExtraDependencies();

  // Whether this task has been tried before or not. Awkward naming because we cannot use
  // `isRetry` due to play reading the "is" prefix differently.
  @ApiModelProperty(value = "Whether this task has been tried before")
  public boolean firstTry = true;
}
