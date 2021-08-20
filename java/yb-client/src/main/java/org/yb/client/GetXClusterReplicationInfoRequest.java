// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.client;

import com.google.protobuf.Message;
import org.jboss.netty.buffer.ChannelBuffer;
import org.yb.master.Master;
import org.yb.util.Pair;

import java.util.UUID;

public class GetXClusterReplicationInfoRequest extends YRpc<GetXClusterReplicationInfoResponse> {

  private final UUID sourceUniverseUUID;

  GetXClusterReplicationInfoRequest(YBTable table, UUID sourceUniverseUUID) {
    super(table);
    this.sourceUniverseUUID = sourceUniverseUUID;
  }

  @Override
  ChannelBuffer serialize(Message header) {
    assert header.isInitialized();

    final Master.GetUniverseReplicationRequestPB.Builder builder =
      Master.GetUniverseReplicationRequestPB.newBuilder()
        .setProducerId(sourceUniverseUUID.toString());

    return toChannelBuffer(header, builder.build());
  }

  @Override
  String serviceName() {
    return MASTER_SERVICE_NAME;
  }

  @Override
  String method() {
    return "GetUniverseReplication";
  }

  @Override
  Pair<GetXClusterReplicationInfoResponse, Object> deserialize(
    CallResponse callResponse, String tsUUID) throws Exception {
    final Master.GetUniverseReplicationResponsePB.Builder builder =
      Master.GetUniverseReplicationResponsePB.newBuilder();

    readProtobuf(callResponse.getPBMessage(), builder);

    final Master.MasterErrorPB error = builder.hasError() ? builder.getError() : null;
    final Master.SysUniverseReplicationEntryPB info = builder.getEntry();

    GetXClusterReplicationInfoResponse response =
      new GetXClusterReplicationInfoResponse(deadlineTracker.getElapsedMillis(),
        tsUUID, error, info);

    return new Pair<>(response, error);
  }
}
