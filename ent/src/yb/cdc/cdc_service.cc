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

#include "yb/cdc/cdc_service.h"

#include <chrono>
#include <memory>

#include <boost/multi_index/hashed_index.hpp>
#include <boost/multi_index/mem_fun.hpp>
#include <boost/multi_index/member.hpp>
#include <boost/multi_index_container.hpp>

#include "yb/cdc/cdc_producer.h"
#include "yb/cdc/cdc_rpc.h"
#include "yb/cdc/cdc_service.proxy.h"

#include "yb/client/client.h"
#include "yb/client/meta_cache.h"
#include "yb/client/schema.h"
#include "yb/client/session.h"
#include "yb/client/table.h"
#include "yb/client/table_alterer.h"
#include "yb/client/table_handle.h"
#include "yb/client/yb_op.h"
#include "yb/client/yb_table_name.h"

#include "yb/common/entity_ids.h"
#include "yb/common/pg_system_attr.h"
#include "yb/common/ql_expr.h"
#include "yb/common/ql_value.h"
#include "yb/common/schema.h"
#include "yb/common/wire_protocol.h"

#include "yb/consensus/log.h"
#include "yb/consensus/raft_consensus.h"
#include "yb/consensus/replicate_msgs_holder.h"

#include "yb/gutil/dynamic_annotations.h"
#include "yb/gutil/strings/join.h"

#include "yb/master/master_client.pb.h"
#include "yb/master/master_ddl.pb.h"
#include "yb/master/master_defaults.h"

#include "yb/rpc/rpc_context.h"
#include "yb/rpc/rpc_controller.h"

#include "yb/tablet/tablet_metadata.h"
#include "yb/tablet/tablet_peer.h"
#include "yb/tablet/transaction_participant.h"

#include "yb/tserver/tablet_server.h"
#include "yb/tserver/ts_tablet_manager.h"

#include "yb/util/debug/trace_event.h"
#include "yb/util/flag_tags.h"
#include "yb/util/format.h"
#include "yb/util/logging.h"
#include "yb/util/metrics.h"
#include "yb/util/monotime.h"
#include "yb/util/scope_exit.h"
#include "yb/util/shared_lock.h"
#include "yb/util/status_format.h"
#include "yb/util/status_log.h"
#include "yb/util/trace.h"

#include "yb/yql/cql/ql/util/statement_result.h"

constexpr uint32_t kUpdateIntervalMs = 15 * 1000;

DEFINE_int32(cdc_read_rpc_timeout_ms, 30 * 1000,
             "Timeout used for CDC read rpc calls.  Reads normally occur cross-cluster.");
TAG_FLAG(cdc_read_rpc_timeout_ms, advanced);

DEFINE_int32(cdc_write_rpc_timeout_ms, 30 * 1000,
             "Timeout used for CDC write rpc calls.  Writes normally occur intra-cluster.");
TAG_FLAG(cdc_write_rpc_timeout_ms, advanced);

DEFINE_int32(cdc_ybclient_reactor_threads, 50,
             "The number of reactor threads to be used for processing ybclient "
             "requests for CDC.");
TAG_FLAG(cdc_ybclient_reactor_threads, advanced);

DEFINE_int32(cdc_state_checkpoint_update_interval_ms, kUpdateIntervalMs,
             "Rate at which CDC state's checkpoint is updated.");

DEFINE_string(certs_for_cdc_dir, "",
              "The parent directory of where all certificates for xCluster producer universes will "
              "be stored, for when the producer and consumer clusters use different certificates. "
              "Place the certificates for each producer cluster in "
              "<certs_for_cdc_dir>/<producer_cluster_id>/*.");

DEFINE_int32(update_min_cdc_indices_interval_secs, 60,
             "How often to read cdc_state table to get the minimum applied index for each tablet "
             "across all streams. This information is used to correctly keep log files that "
             "contain unapplied entries. This is also the rate at which a tablet's minimum "
             "replicated index across all streams is sent to the other peers in the configuration. "
             "If flag enable_log_retention_by_op_idx is disabled, this flag has no effect.");

DEFINE_int32(update_metrics_interval_ms, kUpdateIntervalMs,
             "How often to update xDC cluster metrics.");

DEFINE_bool(enable_cdc_state_table_caching, true, "Enable caching the cdc_state table schema.");

DEFINE_bool(enable_collect_cdc_metrics, true, "Enable collecting cdc metrics.");

DEFINE_double(cdc_read_safe_deadline_ratio, .10,
              "When the heartbeat deadline has this percentage of time remaining, "
              "the master should halt tablet report processing so it can respond in time.");

DECLARE_bool(enable_log_retention_by_op_idx);

DECLARE_int32(cdc_checkpoint_opid_interval_ms);

METRIC_DEFINE_entity(cdc);

namespace yb {
namespace cdc {

using namespace std::literals;

using rpc::RpcContext;
using tserver::TSTabletManager;
using client::internal::RemoteTabletServer;

constexpr int kMaxDurationForTabletLookup = 50;
const client::YBTableName kCdcStateTableName(
    YQL_DATABASE_CQL, master::kSystemNamespaceName, master::kCdcStateTableName);

namespace {

// These are guarded by lock_.
// Map of checkpoints that have been sent to CDC consumer and stored in cdc_state.
struct TabletCheckpointInfo {
 public:
  ProducerTabletInfo producer_tablet_info;

  mutable TabletCheckpoint cdc_state_checkpoint;
  mutable TabletCheckpoint sent_checkpoint;
  mutable MemTrackerPtr mem_tracker;

  const TabletId& tablet_id() const {
    return producer_tablet_info.tablet_id;
  }

  const CDCStreamId& stream_id() const {
    return producer_tablet_info.stream_id;
  }
};

struct CDCStateMetadataInfo {
  ProducerTabletInfo producer_tablet_info;

  mutable std::string commit_timestamp;
  mutable std::shared_ptr<Schema>  current_schema;
  mutable OpId last_streamed_op_id;

  std::shared_ptr<MemTracker> mem_tracker;

  const TableId& tablet_id() const {
    return producer_tablet_info.tablet_id;
  }

  const CDCStreamId& stream_id() const {
    return producer_tablet_info.stream_id;
  }

};

class TabletTag;
class StreamTag;

using TabletCheckpoints = boost::multi_index_container <
    TabletCheckpointInfo,
    boost::multi_index::indexed_by <
        boost::multi_index::hashed_unique <
            boost::multi_index::member <
                TabletCheckpointInfo, ProducerTabletInfo,
                &TabletCheckpointInfo::producer_tablet_info
            >
        >,
        boost::multi_index::hashed_non_unique <
            boost::multi_index::tag <TabletTag>,
            boost::multi_index::const_mem_fun <
                TabletCheckpointInfo, const TabletId&, &TabletCheckpointInfo::tablet_id
            >
        >,
        boost::multi_index::hashed_non_unique <
            boost::multi_index::tag <StreamTag>,
            boost::multi_index::const_mem_fun <
                TabletCheckpointInfo, const CDCStreamId&, &TabletCheckpointInfo::stream_id
            >
        >
    >
>;

using CDCStateMetadata = boost::multi_index_container <
    CDCStateMetadataInfo,
    boost::multi_index::indexed_by <
        boost::multi_index::hashed_unique <
            boost::multi_index::member <
                CDCStateMetadataInfo, ProducerTabletInfo,
                &CDCStateMetadataInfo::producer_tablet_info>
        >,
        boost::multi_index::hashed_non_unique <
            boost::multi_index::tag <TabletTag>,
            boost::multi_index::const_mem_fun <
                CDCStateMetadataInfo, const TabletId&, &CDCStateMetadataInfo::tablet_id
            >
        >,
        boost::multi_index::hashed_non_unique <
            boost::multi_index::tag <StreamTag>,
            boost::multi_index::const_mem_fun <
                CDCStateMetadataInfo, const CDCStreamId&, &CDCStateMetadataInfo::stream_id
            >
        >
    >
>;

} // namespace

class CDCServiceImpl::Impl {
 public:
  explicit Impl(TSTabletManager* tablet_manager, rw_spinlock* mutex) : mutex_(*mutex) {
    const auto server = tablet_manager->server();
    async_client_init_.emplace(
        "cdc_client", FLAGS_cdc_ybclient_reactor_threads, FLAGS_cdc_read_rpc_timeout_ms / 1000,
        server->permanent_uuid(), &server->options(), server->metric_entity(),
        server->mem_tracker(), server->messenger());
    async_client_init_->Start();
  }

  void UpdateCDCStateMetadata(
      const ProducerTabletInfo& producer_tablet,
      const std::string& timestamp,
      const std::shared_ptr<Schema>& schema,
      const OpId& op_id) {
    std::lock_guard<decltype(mutex_)> l(mutex_);
    auto it = cdc_state_metadata_.find(producer_tablet);
    if (it == cdc_state_metadata_.end()) {
      LOG(DFATAL) << "Failed to update the cdc state metadata for tablet id: "
                  << producer_tablet.tablet_id;
      return;
    }
    it->commit_timestamp = timestamp;
    it->current_schema = schema;
    it->last_streamed_op_id = op_id;
  }

  std::shared_ptr<Schema> GetOrAddSchema(const ProducerTabletInfo& producer_tablet) {
    std::lock_guard<decltype(mutex_)> l(mutex_);
    auto it = cdc_state_metadata_.find(producer_tablet);

    if (it != cdc_state_metadata_.end()) {
      return it->current_schema;
    }
    CDCStateMetadataInfo info = CDCStateMetadataInfo {
      .producer_tablet_info = producer_tablet,
      .current_schema = std::make_shared<Schema>()
    };
    cdc_state_metadata_.emplace(info);
    return info.current_schema;
  }

  void AddTabletCheckpoint(
      OpId op_id,
      const CDCStreamId& stream_id,
      const TabletId& tablet_id,
      std::vector<ProducerTabletInfo>* producer_entries_modified) {
    ProducerTabletInfo producer_tablet{
      .universe_uuid = "",
      .stream_id = stream_id,
      .tablet_id = tablet_id
    };
    CoarseTimePoint time;
    if (producer_entries_modified) {
      producer_entries_modified->push_back(producer_tablet);
      time = CoarseMonoClock::Now();
    } else {
      time = CoarseTimePoint::min();
    }
    std::lock_guard<decltype(mutex_)> l(mutex_);
    if (!producer_entries_modified && tablet_checkpoints_.count(producer_tablet)) {
      return;
    }
    tablet_checkpoints_.emplace(TabletCheckpointInfo {
      .producer_tablet_info = producer_tablet,
      .cdc_state_checkpoint = {op_id, time},
      .sent_checkpoint = {op_id, time},
    });
  }

  void EraseTablets(const std::vector<ProducerTabletInfo>& producer_entries_modified,
                    bool erase_cdc_states)
      NO_THREAD_SAFETY_ANALYSIS {
    for (const auto& entry : producer_entries_modified) {
      tablet_checkpoints_.get<TabletTag>().erase(entry.tablet_id);
      if (erase_cdc_states) {
        cdc_state_metadata_.get<TabletTag>().erase(entry.tablet_id);
      }
    }
  }

  boost::optional<OpId> GetLastCheckpoint(const ProducerTabletInfo& producer_tablet) {
    SharedLock<rw_spinlock> lock(mutex_);
    auto it = tablet_checkpoints_.find(producer_tablet);
    if (it != tablet_checkpoints_.end()) {
      // Use checkpoint from cache only if it is current.
      if (it->cdc_state_checkpoint.op_id.index > 0 &&
          !it->cdc_state_checkpoint.ExpiredAt(
              FLAGS_cdc_state_checkpoint_update_interval_ms * 1ms, CoarseMonoClock::Now())) {
        return it->cdc_state_checkpoint.op_id;
      }
    }
    return boost::none;
  }

  bool UpdateCheckpoint(const ProducerTabletInfo& producer_tablet,
                        const OpId& sent_op_id,
                        const OpId& commit_op_id) {
    auto now = CoarseMonoClock::Now();

    TabletCheckpoint sent_checkpoint = {
      .op_id = sent_op_id,
      .last_update_time = now,
    };
    TabletCheckpoint commit_checkpoint = {
      .op_id = commit_op_id,
      .last_update_time = now,
    };

    std::lock_guard<decltype(mutex_)> l(mutex_);
    auto it = tablet_checkpoints_.find(producer_tablet);
    if (it != tablet_checkpoints_.end()) {
      it->sent_checkpoint = sent_checkpoint;

      if (commit_op_id.index > 0) {
        it->cdc_state_checkpoint.op_id = commit_op_id;
      }

      // Check if we need to update cdc_state table.
      if (!it->cdc_state_checkpoint.ExpiredAt(
              FLAGS_cdc_state_checkpoint_update_interval_ms * 1ms, now)) {
        return false;
      }

      it->cdc_state_checkpoint.last_update_time = now;
    } else {
      tablet_checkpoints_.emplace(TabletCheckpointInfo{
        .producer_tablet_info = producer_tablet,
        .cdc_state_checkpoint = commit_checkpoint,
        .sent_checkpoint = sent_checkpoint
      });
    }

    return true;
  }

  OpId GetMinSentCheckpointForTablet(const TabletId& tablet_id) {
    OpId min_op_id = OpId::Max();

    SharedLock<rw_spinlock> l(mutex_);
    auto it_range = tablet_checkpoints_.get<TabletTag>().equal_range(tablet_id);
    if (it_range.first == it_range.second) {
      LOG(WARNING) << "Tablet ID not found in stream_tablets map: " << tablet_id;
      return min_op_id;
    }

    auto cdc_checkpoint_opid_interval = FLAGS_cdc_checkpoint_opid_interval_ms * 1ms;
    for (auto it = it_range.first; it != it_range.second; ++it) {
      // We don't want to include streams that are not being actively polled.
      // So, if the stream has not been polled in the last x seconds,
      // then we ignore that stream while calculating min op ID.
      if (!it->sent_checkpoint.ExpiredAt(cdc_checkpoint_opid_interval, CoarseMonoClock::Now()) &&
          it->sent_checkpoint.op_id.index < min_op_id.index) {
        min_op_id = it->sent_checkpoint.op_id;
      }
    }
    return min_op_id;
  }

  MemTrackerPtr GetMemTracker(
      const std::shared_ptr<tablet::TabletPeer>& tablet_peer,
      const ProducerTabletInfo& producer_info) {
    {
      SharedLock<rw_spinlock> l(mutex_);
      auto it = tablet_checkpoints_.find(producer_info);
      if (it == tablet_checkpoints_.end()) {
        return nullptr;
      }
      if (it->mem_tracker) {
        return it->mem_tracker;
      }
    }
    std::lock_guard<rw_spinlock> l(mutex_);
    auto it = tablet_checkpoints_.find(producer_info);
    if (it == tablet_checkpoints_.end()) {
      return nullptr;
    }
    if (it->mem_tracker) {
      return it->mem_tracker;
    }
    auto cdc_mem_tracker = MemTracker::FindOrCreateTracker(
        "CDC", tablet_peer->tablet()->mem_tracker());
    it->mem_tracker = MemTracker::FindOrCreateTracker(producer_info.stream_id, cdc_mem_tracker);
    return it->mem_tracker;
  }

  Result<bool> PreCheckTabletValidForStream(const ProducerTabletInfo& info) {
    SharedLock<rw_spinlock> l(mutex_);
    if (tablet_checkpoints_.count(info) != 0) {
      return true;
    }
    if (tablet_checkpoints_.get<StreamTag>().count(info.stream_id) != 0) {
      // Did not find matching tablet ID.
      // TODO: Add the split tablets in during tablet split?
      LOG(INFO) << "Tablet ID " << info.tablet_id << " is not part of stream ID " << info.stream_id
                << ". Repopulating tablet list for this stream.";
    }
    return false;
  }

  CHECKED_STATUS CheckTabletValidForStream(
      const ProducerTabletInfo& info,
      const google::protobuf::RepeatedPtrField<master::TabletLocationsPB>& tablets) {
    bool found = false;
    {
      std::lock_guard<rw_spinlock> l(mutex_);
      for (const auto &tablet : tablets) {
        // Add every tablet in the stream.
        ProducerTabletInfo producer_info{info.universe_uuid, info.stream_id, tablet.tablet_id()};
        tablet_checkpoints_.emplace(TabletCheckpointInfo{
            .producer_tablet_info = producer_info
        });
        cdc_state_metadata_.emplace(CDCStateMetadataInfo{
            .producer_tablet_info = producer_info,
            .current_schema = std::make_shared<Schema>()
        });
        // If this is the tablet that the user requested.
        if (tablet.tablet_id() == info.tablet_id) {
          found = true;
        }
      }
    }
    return found ? Status::OK()
                 : STATUS_FORMAT(InvalidArgument, "Tablet ID $0 is not part of stream ID $1",
                                 info.tablet_id, info.stream_id);
  }

  boost::optional<OpId> MinOpId(const TabletId& tablet_id) {
    boost::optional<OpId> result;
    SharedLock<rw_spinlock> l(mutex_);
    // right => multimap where keys are tablet_ids and values are stream_ids.
    // left => multimap where keys are stream_ids and values are tablet_ids.
    auto it_range = tablet_checkpoints_.get<TabletTag>().equal_range(tablet_id);
    if (it_range.first != it_range.second) {
      // Iterate over all the streams for this tablet.
      for (auto it = it_range.first; it != it_range.second; ++it) {
        if (!result || it->cdc_state_checkpoint.op_id.index < result->index) {
          result = it->cdc_state_checkpoint.op_id;
        }
      }
    } else {
      VLOG(2) << "Didn't find any streams for tablet " << tablet_id;
    }

    return result;
  }

  TabletCheckpoints TabletCheckpointsCopy() {
    SharedLock<rw_spinlock> lock(mutex_);
    return tablet_checkpoints_;
  }

  boost::optional<client::AsyncClientInitialiser> async_client_init_;

  // this will be used for the std::call_once call while caching the client
  std::once_flag is_client_cached_;
 private:
  rw_spinlock& mutex_;

  TabletCheckpoints tablet_checkpoints_ GUARDED_BY(mutex_);

  CDCStateMetadata cdc_state_metadata_ GUARDED_BY(mutex_);
};

CDCServiceImpl::CDCServiceImpl(TSTabletManager* tablet_manager,
                               const scoped_refptr<MetricEntity>& metric_entity_server,
                               MetricRegistry* metric_registry)
    : CDCServiceIf(metric_entity_server),
      tablet_manager_(tablet_manager),
      metric_registry_(metric_registry),
      server_metrics_(std::make_shared<CDCServerMetrics>(metric_entity_server)),
      impl_(new Impl(tablet_manager, &mutex_)) {

  update_peers_and_metrics_thread_.reset(new std::thread(
      &CDCServiceImpl::UpdatePeersAndMetrics, this));
}

CDCServiceImpl::~CDCServiceImpl() {
  Shutdown();
}

client::YBClient* CDCServiceImpl::client() {
  return impl_->async_client_init_->client();
}

namespace {

bool YsqlTableHasPrimaryKey(const client::YBSchema& schema) {
  for (const auto& col : schema.columns()) {
    if (col.order() == static_cast<int32_t>(PgSystemAttrNum::kYBRowId)) {
      // ybrowid column is added for tables that don't have user-specified primary key.
      return false;
    }
  }
  return true;
}

bool IsTabletPeerLeader(const std::shared_ptr<tablet::TabletPeer>& peer) {
  return peer->LeaderStatus() == consensus::LeaderStatus::LEADER_AND_READY;
}

std::unordered_map<std::string, std::string> GetCreateCDCStreamOptions(
    const CreateCDCStreamRequestPB* req) {
  std::unordered_map<std::string, std::string> options;
  if(req->has_namespace_name()) {
    options.reserve(5);
  } else {
    options.reserve(4);
  }

  options.emplace(kRecordType, CDCRecordType_Name(req->record_type()));
  options.emplace(kRecordFormat, CDCRecordFormat_Name(req->record_format()));
  options.emplace(kSourceType, CDCRequestSource_Name(req->source_type()));
  options.emplace(kCheckpointType, CDCCheckpointType_Name(req->checkpoint_type()));
  if (req->has_namespace_name()) {
    options.emplace(kIdType, kNamespaceId);
  }

  return options;
}

Status DoUpdateCDCConsumerOpId(const std::shared_ptr<tablet::TabletPeer>& tablet_peer,
                               const OpId& checkpoint,
                               const TabletId& tablet_id) {
  std::shared_ptr<consensus::Consensus> shared_consensus = tablet_peer->shared_consensus();

  if (shared_consensus == nullptr) {
    return STATUS_FORMAT(InternalError,
                         "Failed to get tablet $0 peer consensus", tablet_id);
  }

  shared_consensus->UpdateCDCConsumerOpId(checkpoint);
  return Status::OK();
}

bool UpdateCheckpointRequired(const StreamMetadata& record,
                              const CDCSDKCheckpointPB& cdc_sdk_op_id) {

  switch (record.source_type) {
    case XCLUSTER:
      return true;

    case CDCSDK:
      if (cdc_sdk_op_id.write_id() == 0) {
        return true;
      }
      return cdc_sdk_op_id.write_id() == -1 && cdc_sdk_op_id.key().empty() &&
             cdc_sdk_op_id.snapshot_time() != 0;

    default:
      return false;
  }

}

bool GetFromOpId(const GetChangesRequestPB* req,
                 OpId* op_id,
                 CDCSDKCheckpointPB* cdc_sdk_op_id) {
  if (req->has_from_checkpoint()) {
    *op_id = OpId::FromPB(req->from_checkpoint().op_id());
  } else if (req->has_from_cdc_sdk_checkpoint()) {
    *cdc_sdk_op_id = req->from_cdc_sdk_checkpoint();
    *op_id = OpId::FromPB(*cdc_sdk_op_id);
  } else {
    return false;
  }
  return true;
}

// Check for compatibility whether CDC can be setup on the table
// This essentially checks that the table should not be a REDIS table since we do not support it
// and if it's a YSQL or YCQL one, it should have a primary key
Status CheckCdcCompatibility(const std::shared_ptr<client::YBTable>& table) {
  // return if it is a CQL table because they always have a user specified primary key
  if (table->table_type() == client::YBTableType::YQL_TABLE_TYPE) {
    LOG(INFO) << "Returning while checking CDC compatibility, table is a YCQL table";
    return Status::OK();
  }

  if (table->table_type() == client::YBTableType::REDIS_TABLE_TYPE) {
    return STATUS(InvalidArgument, "Cannot setup CDC on YEDIS_TABLE");;
  }

  // Check if YSQL table has a primary key. CQL tables always have a
  // user specified primary key.
  if (!YsqlTableHasPrimaryKey(table->schema())) {
    return STATUS(InvalidArgument, "Cannot setup CDC on table without primary key");
  }

  return Status::OK();
}

CoarseTimePoint GetDeadline(const RpcContext& context, client::YBClient* client) {
  CoarseTimePoint deadline = context.GetClientDeadline();
  if (deadline == CoarseTimePoint::max()) {  // Not specified by user.
    deadline = CoarseMonoClock::now() + client->default_rpc_timeout();
  }
  return deadline;
}

CHECKED_STATUS VerifyArg(const SetCDCCheckpointRequestPB& req) {
  if (!req.has_checkpoint()) {
    return STATUS(InvalidArgument, "OpId is required to set checkpoint");
  }

  if (!req.has_tablet_id()) {
    return STATUS(InvalidArgument, "Tablet ID is required to set checkpoint");
  }

  if(!req.has_stream_id()) {
    return STATUS(InvalidArgument, "Stream ID is required to set checkpoint");
  }

  return Status::OK();
}

} // namespace

template <class ReqType, class RespType>
bool CDCServiceImpl::CheckOnline(const ReqType* req, RespType* resp, rpc::RpcContext* rpc) {
  TRACE("Received RPC $0: $1", rpc->ToString(), req->DebugString());
  if (PREDICT_FALSE(!tablet_manager_)) {
    SetupErrorAndRespond(resp->mutable_error(),
                         STATUS(ServiceUnavailable, "Tablet Server is not running"),
                         CDCErrorPB::NOT_RUNNING,
                         rpc);
    return false;
  }
  return true;
}

void CDCServiceImpl::CreateEntryInCdcStateTable(
    const std::shared_ptr<client::TableHandle>& cdc_state_table,
    std::vector<ProducerTabletInfo>* producer_entries_modified,
    std::vector<client::YBOperationPtr>* ops,
    const CDCStreamId& stream_id,
    const TableId& table_id,
    const TabletId& tablet_id) {
  OpId op_id;

  const auto cdc_state_table_op = cdc_state_table->NewWriteOp(
      QLWriteRequestPB::QL_STMT_INSERT);
  auto *const cdc_state_table_write_req = cdc_state_table_op->mutable_request();

  QLAddStringHashValue(cdc_state_table_write_req, tablet_id);
  QLAddStringRangeValue(cdc_state_table_write_req, stream_id);
  cdc_state_table->AddStringColumnValue(cdc_state_table_write_req,
                                        master::kCdcCheckpoint, op_id.ToString());
  ops->push_back(std::move(cdc_state_table_op));

  impl_->AddTabletCheckpoint(op_id, stream_id, tablet_id, producer_entries_modified);
}

Result<NamespaceId> CDCServiceImpl::GetNamespaceId(const std::string& ns_name) {
  master::GetNamespaceInfoResponsePB namespace_info_resp;
  RETURN_NOT_OK(client()->GetNamespaceInfo(std::string(),
                                           ns_name,
                                           YQL_DATABASE_PGSQL,
                                           &namespace_info_resp));

  return namespace_info_resp.namespace_().id();
}

Status CDCServiceImpl::CreateCDCStreamForNamespace(
    const CreateCDCStreamRequestPB* req,
    CreateCDCStreamResponsePB* resp,
    CoarseTimePoint deadline) {
  auto session = client()->NewSession();

  // Used to delete streams in case of failure.
  CDCCreationState creation_state;

  auto scope_exit = ScopeExit([this, &creation_state] {
    RollbackPartialCreate(creation_state);
  });

  auto ns_id = VERIFY_RESULT_OR_SET_CODE(
      GetNamespaceId(req->namespace_name()), CDCError(CDCErrorPB::INVALID_REQUEST));

  // Generate a stream id by calling CreateCDCStream, and also setup the stream in the master.
  std::unordered_map<std::string, std::string> options = GetCreateCDCStreamOptions(req);

  CDCStreamId db_stream_id = VERIFY_RESULT_OR_SET_CODE(
      client()->CreateCDCStream(ns_id, options), CDCError(CDCErrorPB::INTERNAL_ERROR));

  auto table_list = VERIFY_RESULT_OR_SET_CODE(
      client()->ListUserTables(ns_id), CDCError(CDCErrorPB::INTERNAL_ERROR));

  options.erase(kIdType);

  std::vector<client::YBOperationPtr> ops;
  std::vector<TableId> table_ids;
  std::vector<CDCStreamId> stream_ids;

  auto cdc_state_table =
      VERIFY_RESULT_OR_SET_CODE(GetCdcStateTable(), CDCError(CDCErrorPB::INTERNAL_ERROR));

  for (const auto& table_iter : table_list) {
    std::shared_ptr<client::YBTable> table;

    RETURN_NOT_OK_SET_CODE(
        client()->OpenTable(table_iter.table_id(), &table), CDCError(CDCErrorPB::TABLE_NOT_FOUND));

    // internally if any of the table doesn't have a primary key, then do not create
    // a CDC stream ID for that table
    if (!YsqlTableHasPrimaryKey(table->schema())) {
      LOG(WARNING) << "Skipping CDC stream creation on " << table->name().table_name()
                   << " because it does not have a primary key";
      continue;
    }

    // We don't allow CDC on YEDIS and tables without a primary key.
    if (req->record_format() != CDCRecordFormat::WAL) {
      RETURN_NOT_OK_SET_CODE(CheckCdcCompatibility(table), CDCError(CDCErrorPB::INVALID_REQUEST));
    }

    const CDCStreamId stream_id = VERIFY_RESULT_OR_SET_CODE(
        client()->CreateCDCStream(table_iter.table_id(), options, true, db_stream_id),
        CDCError(CDCErrorPB::INTERNAL_ERROR));

    creation_state.created_cdc_streams.push_back(stream_id);

    google::protobuf::RepeatedPtrField<master::TabletLocationsPB> tablets;
    RETURN_NOT_OK_SET_CODE(
        client()->GetTabletsFromTableId(table_iter.table_id(), 0, &tablets),
        CDCError(CDCErrorPB::TABLE_NOT_FOUND));

    // For each tablet, create a row in cdc_state table containing the generated stream id, and
    // the op id as max in the logs.
    for (const auto& tablet : tablets) {
      CreateEntryInCdcStateTable(
          cdc_state_table,
          &creation_state.producer_entries_modified,
          &ops,
          db_stream_id,
          table_iter.table_id(),
          tablet.tablet_id());
    }
    stream_ids.push_back(std::move(stream_id));
    table_ids.push_back(table_iter.table_id());
  }

  // Add stream to cache.
  AddStreamMetadataToCache(
      db_stream_id,
      std::make_shared<StreamMetadata>(
          ns_id, table_ids, req->record_type(), req->record_format(), req->source_type(),
          req->checkpoint_type()));

  session->SetDeadline(deadline);

  RETURN_NOT_OK_SET_CODE(
      RefreshCacheOnFail(session->ApplyAndFlush(ops)), CDCError(CDCErrorPB::INTERNAL_ERROR));

  resp->set_db_stream_id(db_stream_id);

  // Clear creation_state so no changes are reversed by scope_exit since we succeeded.
  creation_state.Clear();

  return Status::OK();
}

void CDCServiceImpl::CreateCDCStream(const CreateCDCStreamRequestPB* req,
                                     CreateCDCStreamResponsePB* resp,
                                     RpcContext context) {
  CDCStreamId streamId;

  if (!CheckOnline(req, resp, &context)) {
    return;
  }

  RPC_CHECK_AND_RETURN_ERROR(req->has_table_id() || req->has_namespace_name(),
                             STATUS(InvalidArgument,
                                    "Table ID or Database name is required to create CDC stream"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  bool is_xcluster = req->source_type() == XCLUSTER;
  if (is_xcluster || req->has_table_id()) {
    std::shared_ptr<client::YBTable> table;
    Status s = client()->OpenTable(req->table_id(), &table);
    RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::TABLE_NOT_FOUND, context);

    // We don't allow CDC on YEDIS and tables without a primary key.
    if (req->record_format() != CDCRecordFormat::WAL) {
      s = CheckCdcCompatibility(table);
      RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INVALID_REQUEST, context);
    }

    std::unordered_map<std::string, std::string> options = GetCreateCDCStreamOptions(req);

    auto result = client()->CreateCDCStream(req->table_id(), options);
    RPC_CHECK_AND_RETURN_ERROR(result.ok(), result.status(), resp->mutable_error(),
                               CDCErrorPB::INTERNAL_ERROR, context);

    resp->set_stream_id(*result);

    // Add stream to cache.
    AddStreamMetadataToCache(
        *result, std::make_shared<StreamMetadata>(
                     "",
                     std::vector<TableId>{req->table_id()},
                     req->record_type(),
                     req->record_format(),
                     req->source_type(),
                     req->checkpoint_type()));
  } else if (req->has_namespace_name()) {
    auto deadline = GetDeadline(context, client());
    Status status = CreateCDCStreamForNamespace(req, resp, deadline);
    CDCError error(status);

    if (!status.ok()) {
      SetupErrorAndRespond(resp->mutable_error(), status, error.value(), &context);
      return;
    }
  }

  context.RespondSuccess();
}

Result<SetCDCCheckpointResponsePB> CDCServiceImpl::SetCDCCheckpoint(
    const SetCDCCheckpointRequestPB& req, CoarseTimePoint deadline) {
  VLOG(1) << "Received SetCDCCheckpoint request " << req.ShortDebugString();

  RETURN_NOT_OK_SET_CODE(VerifyArg(req), CDCError(CDCErrorPB::INVALID_REQUEST));

  auto record = VERIFY_RESULT(GetStream(req.stream_id()));
  if ((*record).checkpoint_type != EXPLICIT) {
    LOG(WARNING) << "Setting the checkpoint explicitly even though the checkpoint type is implicit";
  }

  std::shared_ptr<tablet::TabletPeer> tablet_peer;
  auto s = tablet_manager_->GetTabletPeer(req.tablet_id(), &tablet_peer);

  if (s.IsNotFound()) {
    RETURN_NOT_OK_SET_CODE(s, CDCError(CDCErrorPB::TABLET_NOT_FOUND));
  } else if (tablet_peer->LeaderStatus() == consensus::LeaderStatus::NOT_LEADER) {
    RETURN_NOT_OK_SET_CODE(s, CDCError(CDCErrorPB::NOT_LEADER));
  } else if (!s.ok()) {
    RETURN_NOT_OK_SET_CODE(s, CDCError(CDCErrorPB::LEADER_NOT_READY));
  }

  ProducerTabletInfo producer_tablet{"" /* UUID */, req.stream_id(), req.tablet_id()};
  OpId checkpoint = OpId::FromPB(req.checkpoint().op_id());

  auto session = client()->NewSession();
  session->SetDeadline(deadline);
  RETURN_NOT_OK_SET_CODE(
      UpdateCheckpoint(producer_tablet, checkpoint, checkpoint, session, GetCurrentTimeMicros()),
      CDCError(CDCErrorPB::INTERNAL_ERROR));

  RETURN_NOT_OK_SET_CODE(
      DoUpdateCDCConsumerOpId(tablet_peer, checkpoint, req.tablet_id()),
      CDCError(CDCErrorPB::INTERNAL_ERROR));

  return SetCDCCheckpointResponsePB();
}

void CDCServiceImpl::DeleteCDCStream(const DeleteCDCStreamRequestPB* req,
                                     DeleteCDCStreamResponsePB* resp,
                                     RpcContext context) {
  if (!CheckOnline(req, resp, &context)) {
    return;
  }

  LOG(INFO) << "Received DeleteCDCStream request " << req->ShortDebugString();

  RPC_CHECK_AND_RETURN_ERROR(
      !req->stream_id().empty(),
      STATUS(InvalidArgument, "Stream ID or Database stream ID is required to delete CDC stream"),
      resp->mutable_error(),
      CDCErrorPB::INVALID_REQUEST,
      context);

  vector<CDCStreamId> streams(req->stream_id().begin(), req->stream_id().end());
  Status s = client()->DeleteCDCStream(
        streams,
        (req->has_force_delete() && req->force_delete()),
        (req->has_ignore_errors() && req->ignore_errors()));
  RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  context.RespondSuccess();
}

void CDCServiceImpl::ListTablets(const ListTabletsRequestPB* req,
                                 ListTabletsResponsePB* resp,
                                 RpcContext context) {
  if (!CheckOnline(req, resp, &context)) {
    return;
  }

  RPC_CHECK_AND_RETURN_ERROR(req->has_stream_id(),
                             STATUS(InvalidArgument, "Stream ID is required to list tablets"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  auto tablets = GetTablets(req->stream_id());
  RPC_CHECK_AND_RETURN_ERROR(tablets.ok(), tablets.status(), resp->mutable_error(),
                             CDCErrorPB::INTERNAL_ERROR, context);

  if (!req->local_only()) {
    resp->mutable_tablets()->Reserve(tablets->size());
  }

  for (const auto& tablet : *tablets) {
    // Filter local tablets if needed.
    if (req->local_only()) {
      bool is_local = false;
      for (const auto& replica : tablet.replicas()) {
        if (replica.ts_info().permanent_uuid() == tablet_manager_->server()->permanent_uuid()) {
          is_local = true;
          break;
        }
      }

      if (!is_local) {
        continue;
      }
    }

    auto res = resp->add_tablets();
    res->set_tablet_id(tablet.tablet_id());
    res->mutable_tservers()->Reserve(tablet.replicas_size());
    for (const auto& replica : tablet.replicas()) {
      auto tserver =  res->add_tservers();
      tserver->mutable_broadcast_addresses()->CopyFrom(replica.ts_info().broadcast_addresses());
      if (tserver->broadcast_addresses_size() == 0) {
        LOG(WARNING) << "No public broadcast addresses found for "
                     << replica.ts_info().permanent_uuid() << ".  Using private addresses instead.";
        tserver->mutable_broadcast_addresses()->CopyFrom(replica.ts_info().private_rpc_addresses());
      }
    }
  }

  context.RespondSuccess();
}

Result<google::protobuf::RepeatedPtrField<master::TabletLocationsPB>> CDCServiceImpl::GetTablets(
    const CDCStreamId& stream_id) {
  auto stream_metadata = VERIFY_RESULT(GetStream(stream_id));
  client::YBTableName table_name;
  google::protobuf::RepeatedPtrField<master::TabletLocationsPB> all_tablets;

  for (const auto& table_id : stream_metadata->table_ids) {
    google::protobuf::RepeatedPtrField<master::TabletLocationsPB> tablets;
    table_name.set_table_id(table_id);
    RETURN_NOT_OK(client()->GetTablets(
        table_name, 0, &tablets, /* partition_list_version =*/nullptr,
        RequireTabletsRunning::kFalse, master::IncludeInactive::kTrue));

    all_tablets.MergeFrom(tablets);
  }

  return all_tablets;
}

void CDCServiceImpl::GetChanges(const GetChangesRequestPB* req,
                                GetChangesResponsePB* resp,
                                RpcContext context) {
  if (!CheckOnline(req, resp, &context)) {
    return;
  }
  YB_LOG_EVERY_N_SECS(INFO, 300) << "Received GetChanges request " << req->ShortDebugString();

  RPC_CHECK_AND_RETURN_ERROR(req->has_tablet_id(),
                             STATUS(InvalidArgument, "Tablet ID is required to get CDC changes"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);
  RPC_CHECK_AND_RETURN_ERROR(req->has_stream_id() || req->has_db_stream_id(),
                             STATUS(InvalidArgument,
                             "Stream ID/DB Stream ID is required to get CDC changes"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  ProducerTabletInfo producer_tablet;
  CDCStreamId stream_id = req->has_db_stream_id() ? req->db_stream_id() : req->stream_id();

  auto session = client()->NewSession();
  CoarseTimePoint deadline = GetDeadline(context, client());
  session->SetDeadline(deadline);

  // Check that requested tablet_id is part of the CDC stream.
  producer_tablet = {"" /* UUID */, stream_id, req->tablet_id()};

  Status s = CheckTabletValidForStream(producer_tablet);
  RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INVALID_REQUEST, context);

  std::shared_ptr<tablet::TabletPeer> tablet_peer;
  s = tablet_manager_->GetTabletPeer(req->tablet_id(), &tablet_peer);
  auto original_leader_term = tablet_peer ? tablet_peer->LeaderTerm() : OpId::kUnknownTerm;

  // If we can't serve this tablet...
  if (s.IsNotFound() || tablet_peer->LeaderStatus() != consensus::LeaderStatus::LEADER_AND_READY) {
    if (req->serve_as_proxy()) {
      // Forward GetChanges() to tablet leader. This commonly happens in Kubernetes setups.
      auto context_ptr = std::make_shared<RpcContext>(std::move(context));
      TabletLeaderGetChanges(req, resp, context_ptr, tablet_peer);
    // Otherwise, figure out the proper return code.
    } else if (s.IsNotFound()) {
      SetupErrorAndRespond(resp->mutable_error(), s, CDCErrorPB::TABLET_NOT_FOUND, &context);
    } else if (tablet_peer->LeaderStatus() == consensus::LeaderStatus::NOT_LEADER) {
      // TODO: we may be able to get some changes, even if we're not the leader.
      SetupErrorAndRespond(resp->mutable_error(),
          STATUS(NotFound, Format("Not leader for $0", req->tablet_id())),
          CDCErrorPB::TABLET_NOT_FOUND, &context);
    } else {
      SetupErrorAndRespond(resp->mutable_error(),
          STATUS(LeaderNotReadyToServe, "Not ready to serve"),
          CDCErrorPB::LEADER_NOT_READY, &context);
    }
    return;
  }

  // This is the leader tablet, so mark cdc as enabled.
  cdc_enabled_.store(true, std::memory_order_release);

  auto res = GetStream(stream_id);
  RPC_CHECK_AND_RETURN_ERROR(res.ok(), res.status(), resp->mutable_error(),
                             CDCErrorPB::INTERNAL_ERROR, context);
  StreamMetadata record = **res;

  OpId op_id;
  CDCSDKCheckpointPB cdc_sdk_op_id;
  // Get opId from request.
  if (!GetFromOpId(req, &op_id, &cdc_sdk_op_id)) {
    auto result = GetLastCheckpoint(producer_tablet, session);
    RPC_CHECK_AND_RETURN_ERROR(
        result.ok(), result.status(), resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);
    if (record.source_type == XCLUSTER) {
      op_id = *result;
    } else {
      result->ToPB(&cdc_sdk_op_id);
      op_id = OpId::FromPB(cdc_sdk_op_id);
    }
  }

  int64_t last_readable_index;
  consensus::ReplicateMsgsHolder msgs_holder;
  MemTrackerPtr mem_tracker = impl_->GetMemTracker(tablet_peer, producer_tablet);

  // Calculate deadline to be passed to GetChanges.
  CoarseTimePoint get_changes_deadline = CoarseTimePoint::max();
  if (deadline != CoarseTimePoint::max()) {
    // Check if we are too close to calculate a safe deadline.
    RPC_CHECK_AND_RETURN_ERROR(
      deadline - CoarseMonoClock::Now() > 1ms,
      STATUS(TimedOut, "Too close to rpc timeout to call GetChanges."),
      resp->mutable_error(),
      CDCErrorPB::INTERNAL_ERROR, context);

    // Calculate a safe deadline so that CdcProducer::GetChanges times out
    // 20% faster than CdcServiceImpl::GetChanges. This gives enough
    // time (unless timeouts are unrealistically small) for CdcServiceImpl::GetChanges
    // to finish post-processing and return the partial results without itself timing out.
    const auto safe_deadline = deadline -
      (FLAGS_cdc_read_rpc_timeout_ms * 1ms * FLAGS_cdc_read_safe_deadline_ratio);
    get_changes_deadline = ToCoarse(MonoTime::FromUint64(safe_deadline.time_since_epoch().count()));
  }

  // Read the latest changes from the Log.
  if (record.source_type == XCLUSTER) {
    s = cdc::GetChangesForXCluster(
        stream_id, req->tablet_id(), op_id, record, tablet_peer, mem_tracker,
        &msgs_holder, resp, &last_readable_index, get_changes_deadline);
  } else {
    std::string commit_timestamp;
    OpId last_streamed_op_id;

    auto cached_schema = impl_->GetOrAddSchema(producer_tablet);
    s = cdc::GetChangesForCDCSDK(
        req->stream_id(), req->tablet_id(), cdc_sdk_op_id, record, tablet_peer, mem_tracker,
        &msgs_holder, resp, &commit_timestamp, &cached_schema,
        &last_streamed_op_id, &last_readable_index, get_changes_deadline);

    impl_->UpdateCDCStateMetadata(
        producer_tablet, commit_timestamp, cached_schema, last_streamed_op_id);
  }

  RPC_STATUS_RETURN_ERROR(
      s,
      resp->mutable_error(),
      s.IsNotFound() ? CDCErrorPB::CHECKPOINT_TOO_OLD : CDCErrorPB::UNKNOWN_ERROR,
      context);

  // Verify leadership was maintained for the duration of the GetChanges() read.
  s = tablet_manager_->GetTabletPeer(req->tablet_id(), &tablet_peer);
  if (s.IsNotFound() || tablet_peer->LeaderStatus() != consensus::LeaderStatus::LEADER_AND_READY ||
      tablet_peer->LeaderTerm() != original_leader_term) {
    SetupErrorAndRespond(resp->mutable_error(),
        STATUS(NotFound, Format("Not leader for $0", req->tablet_id())),
        CDCErrorPB::TABLET_NOT_FOUND, &context);
    return;
  }

  // Store information about the last server read & remote client ACK.
  uint64_t last_record_hybrid_time = resp->records_size() > 0 ?
      resp->records(resp->records_size() - 1).time() : 0;

  if (record.checkpoint_type == IMPLICIT) {
    if (UpdateCheckpointRequired(record, cdc_sdk_op_id)) {
      s = UpdateCheckpoint(producer_tablet, OpId::FromPB(resp->checkpoint().op_id()),
                           op_id, session, last_record_hybrid_time);
    }

    RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

    s = DoUpdateCDCConsumerOpId(tablet_peer,
                                impl_->GetMinSentCheckpointForTablet(req->tablet_id()),
                                req->tablet_id());

    RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);
  }
  // Update relevant GetChanges metrics before handing off the Response.
  UpdateCDCTabletMetrics(resp, producer_tablet, tablet_peer, op_id, last_readable_index);
  context.RespondSuccess();
}

Status CDCServiceImpl::UpdatePeersCdcMinReplicatedIndex(const TabletId& tablet_id,
                                                        int64_t min_index,
                                                        int64_t min_term) {
  std::vector<client::internal::RemoteTabletServer *> servers;
  RETURN_NOT_OK(GetTServers(tablet_id, &servers));
  for (const auto &server : servers) {
    if (server->IsLocal()) {
      // We modify our log directly. Avoid calling itself through the proxy.
      continue;
    }
    LOG(INFO) << "Modifying remote peer " << server->ToString();
    auto proxy = GetCDCServiceProxy(server);
    UpdateCdcReplicatedIndexRequestPB update_index_req;
    UpdateCdcReplicatedIndexResponsePB update_index_resp;
    update_index_req.set_tablet_id(tablet_id);
    update_index_req.set_replicated_index(min_index);
    update_index_req.set_replicated_term(min_term);
    rpc::RpcController rpc;
    rpc.set_timeout(MonoDelta::FromMilliseconds(FLAGS_cdc_write_rpc_timeout_ms));
    RETURN_NOT_OK(proxy->UpdateCdcReplicatedIndex(update_index_req, &update_index_resp, &rpc));
    if (update_index_resp.has_error()) {
      return StatusFromPB(update_index_resp.error().status());
    }
  }
  return Status::OK();
}

void CDCServiceImpl::ComputeLagMetric(int64_t last_replicated_micros,
                                      int64_t metric_last_timestamp_micros,
                                      int64_t cdc_state_last_replication_time_micros,
                                      scoped_refptr<AtomicGauge<int64_t>> metric) {
  if (metric_last_timestamp_micros == 0) {
    // The tablet metric timestamp is uninitialized, so try to use last replicated time in cdc
    // state.
    if (cdc_state_last_replication_time_micros == 0) {
      // Last replicated time in cdc state is uninitialized as well, so set the metric value to
      // 0 and update later when we have a suitable lower bound.
      metric->set_value(0);
    } else {
      metric->set_value(last_replicated_micros - cdc_state_last_replication_time_micros);
    }
  } else {
    metric->set_value(last_replicated_micros - metric_last_timestamp_micros);
  }
}

void CDCServiceImpl::UpdateLagMetrics() {
  auto tablet_checkpoints = impl_->TabletCheckpointsCopy();

  auto cdc_state_table_result = GetCdcStateTable();
  if (!cdc_state_table_result.ok()) {
    // It is possible that this runs before the cdc_state table is created. This is
    // ok. It just means that this is the first time the cluster starts.
    YB_LOG_EVERY_N_SECS(WARNING, 30)
        << "Unable to open table " << kCdcStateTableName.table_name() << " for metrics update.";
    return;
  }

  std::unordered_set<ProducerTabletInfo, ProducerTabletInfo::Hash> tablets_in_cdc_state_table;
  client::TableIteratorOptions options;
  options.columns = std::vector<string>{
      master::kCdcTabletId, master::kCdcStreamId, master::kCdcLastReplicationTime};
  bool failed = false;
  options.error_handler = [&failed](const Status& status) {
    YB_LOG_EVERY_N_SECS(WARNING, 30) << "Scan of table " << kCdcStateTableName.table_name()
                                     << " failed: " << status << ". Could not update metrics.";
    failed = true;
  };
  // First go through tablets in the cdc_state table and update metrics for each one.
  for (const auto& row : client::TableRange(**cdc_state_table_result, options)) {
    auto tablet_id = row.column(master::kCdcTabletIdIdx).string_value();
    auto stream_id = row.column(master::kCdcStreamIdIdx).string_value();
    std::shared_ptr<tablet::TabletPeer> tablet_peer;
    Status s = tablet_manager_->GetTabletPeer(tablet_id, &tablet_peer);
    if (s.IsNotFound()) {
      continue;
    }

    ProducerTabletInfo tablet_info = {"" /* universe_uuid */, stream_id, tablet_id};
    tablets_in_cdc_state_table.insert(tablet_info);
    auto tablet_metric = GetCDCTabletMetrics(tablet_info, tablet_peer);
    if (!tablet_metric) {
      continue;
    }
    if (tablet_peer->LeaderStatus() != consensus::LeaderStatus::LEADER_AND_READY) {
      // Set lag to 0 because we're not the leader for this tablet anymore, which means another peer
      // is responsible for tracking this tablet's lag.
      tablet_metric->async_replication_sent_lag_micros->set_value(0);
      tablet_metric->async_replication_committed_lag_micros->set_value(0);
    } else {
      // Get the physical time of the last committed record on producer.
      auto last_replicated_micros = GetLastReplicatedTime(tablet_peer);
      const auto& timestamp_ql_value = row.column(2);
      auto cdc_state_last_replication_time_micros =
          !timestamp_ql_value.IsNull() ?
          timestamp_ql_value.timestamp_value().ToInt64() : 0;
      auto last_sent_micros = tablet_metric->last_read_physicaltime->value();
      ComputeLagMetric(last_replicated_micros, last_sent_micros,
                       cdc_state_last_replication_time_micros,
                       tablet_metric->async_replication_sent_lag_micros);
      auto last_committed_micros = tablet_metric->last_checkpoint_physicaltime->value();
      ComputeLagMetric(last_replicated_micros, last_committed_micros,
                       cdc_state_last_replication_time_micros,
                       tablet_metric->async_replication_committed_lag_micros);
    }
  }
  if (failed) {
    RefreshCdcStateTable();
    return;
  }

  // Now, go through tablets in tablet_checkpoints_ and set lag to 0 for all tablets we're no
  // longer replicating.
  for (const auto& checkpoint : tablet_checkpoints) {
    const ProducerTabletInfo& tablet_info = checkpoint.producer_tablet_info;
    if (tablets_in_cdc_state_table.find(tablet_info) == tablets_in_cdc_state_table.end()) {
      // We're no longer replicating this tablet, so set lag to 0.
      std::shared_ptr<tablet::TabletPeer> tablet_peer;
      Status s = tablet_manager_->GetTabletPeer(checkpoint.tablet_id(), &tablet_peer);
      if (s.IsNotFound()) {
        continue;
      }
      auto tablet_metric = GetCDCTabletMetrics(checkpoint.producer_tablet_info, tablet_peer);
      if (!tablet_metric) {
        continue;
      }
      tablet_metric->async_replication_sent_lag_micros->set_value(0);
      tablet_metric->async_replication_committed_lag_micros->set_value(0);
    }
  }
}

bool CDCServiceImpl::ShouldUpdateLagMetrics(MonoTime time_since_update_metrics) {
  // Only update metrics if cdc is enabled, which means we have a valid replication stream.
  return GetAtomicFlag(&FLAGS_enable_collect_cdc_metrics) &&
         (time_since_update_metrics == MonoTime::kUninitialized ||
         MonoTime::Now() - time_since_update_metrics >=
             MonoDelta::FromMilliseconds(GetAtomicFlag(&FLAGS_update_metrics_interval_ms)));
}

bool CDCServiceImpl::CDCEnabled() {
  return cdc_enabled_.load(std::memory_order_acquire);
}

Result<std::shared_ptr<client::TableHandle>> CDCServiceImpl::GetCdcStateTable() {
  bool use_cache = GetAtomicFlag(&FLAGS_enable_cdc_state_table_caching);
  {
    SharedLock<decltype(mutex_)> l(mutex_);
    if (cdc_state_table_ && use_cache) {
      return cdc_state_table_;
    }
    if (cdc_service_stopped_) {
      return STATUS(ShutdownInProgress, "");
    }
  }

  auto cdc_state_table = std::make_shared<yb::client::TableHandle>();
  auto s = cdc_state_table->Open(kCdcStateTableName, client());
  // It is possible that this runs before the cdc_state table is created.
  RETURN_NOT_OK(s);

  {
    std::lock_guard<decltype(mutex_)> l(mutex_);
    if (cdc_state_table_ && use_cache) {
      return cdc_state_table_;
    }
    if (cdc_service_stopped_) {
      return STATUS(ShutdownInProgress, "");
    }
    cdc_state_table_ = cdc_state_table;
    return cdc_state_table_;
  }
}

void CDCServiceImpl::RefreshCdcStateTable() {
  // Set cached value to null so we regenerate it on the next call.
  std::lock_guard<decltype(mutex_)> l(mutex_);
  cdc_state_table_ = nullptr;
}

Status CDCServiceImpl::RefreshCacheOnFail(const Status& s) {
  if (!s.ok()) {
    RefreshCdcStateTable();
  }
  return s;
}

MicrosTime CDCServiceImpl::GetLastReplicatedTime(
    const std::shared_ptr<tablet::TabletPeer>& tablet_peer) {
  tablet::RemoveIntentsData data;
  tablet_peer->GetLastReplicatedData(&data);
  return data.log_ht.GetPhysicalValueMicros();
}

void CDCServiceImpl::UpdatePeersAndMetrics() {
  int64_t current_term = -1;
  MonoTime time_since_update_peers = MonoTime::kUninitialized;
  MonoTime time_since_update_metrics = MonoTime::kUninitialized;

  // Returns false if the CDC service has been stopped.
  auto sleep_while_not_stopped = [this]() {
    int min_sleep_ms = std::min(100, GetAtomicFlag(&FLAGS_update_metrics_interval_ms));
    auto sleep_period = MonoDelta::FromMilliseconds(min_sleep_ms);
    SleepFor(sleep_period);

    SharedLock<decltype(mutex_)> l(mutex_);
    return !cdc_service_stopped_;
  };

  do {
    if (!cdc_enabled_.load(std::memory_order_acquire)) {
      // Have not yet received any GetChanges requests, so skip background thread work.
      continue;
    }
    // Should we update lag metrics default every 1s.
    if (ShouldUpdateLagMetrics(time_since_update_metrics)) {
      UpdateLagMetrics();
      time_since_update_metrics = MonoTime::Now();
    }

    // If its not been 60s since the last peer update, continue.
    if (!FLAGS_enable_log_retention_by_op_idx ||
        (time_since_update_peers != MonoTime::kUninitialized &&
         MonoTime::Now() - time_since_update_peers <
             MonoDelta::FromSeconds(GetAtomicFlag(&FLAGS_update_min_cdc_indices_interval_secs)))) {
      continue;
    }

    time_since_update_peers = MonoTime::Now();
    LOG(INFO) << "Started to read minimum replicated indices for all tablets";

    auto cdc_state_table_result = GetCdcStateTable();
    if (!cdc_state_table_result.ok()) {
      // It is possible that this runs before the cdc_state table is created. This is
      // ok. It just means that this is the first time the cluster starts.
      YB_LOG_EVERY_N_SECS(WARNING, 3600) << "Unable to open table "
                                         << kCdcStateTableName.table_name()
                                         << ". CDC min replicated indices won't be updated";
      continue;
    }

    int count = 0;
    std::unordered_map<std::string, int64_t> tablet_min_checkpoint_index;
    client::TableIteratorOptions options;
    bool failed = false;
    options.error_handler = [&failed](const Status& status) {
      LOG(WARNING) << "Scan of table " << kCdcStateTableName.table_name() << " failed: " << status;
      failed = true;
    };
    options.columns = std::vector<std::string>{master::kCdcTabletId, master::kCdcStreamId,
        master::kCdcCheckpoint, master::kCdcLastReplicationTime};
    for (const auto& row : client::TableRange(**cdc_state_table_result, options)) {
      count++;
      auto tablet_id = row.column(master::kCdcTabletIdIdx).string_value();
      auto stream_id = row.column(master::kCdcStreamIdIdx).string_value();
      auto checkpoint = row.column(master::kCdcCheckpointIdx).string_value();
      std::string last_replicated_time_str;
      const auto& timestamp_ql_value = row.column(3);
      if (!timestamp_ql_value.IsNull()) {
        last_replicated_time_str = timestamp_ql_value.timestamp_value().ToFormattedString();
      }

      VLOG(1) << "stream_id: " << stream_id << ", tablet_id: " << tablet_id
              << ", checkpoint: " << checkpoint << ", last replicated time: "
              << last_replicated_time_str;

      auto result = OpId::FromString(checkpoint);
      if (!result.ok()) {
        LOG(WARNING) << "Read invalid op id " << row.column(1).string_value()
                     << " for tablet " << tablet_id;
        continue;
      }

      auto index = (*result).index;
      current_term = (*result).term;
      auto it = tablet_min_checkpoint_index.find(tablet_id);
      if (it == tablet_min_checkpoint_index.end()) {
        tablet_min_checkpoint_index[tablet_id] = index;
      } else {
        if (index < it->second) {
          it->second = index;
        }
      }
    }
    if (failed) {
      RefreshCdcStateTable();
      continue;
    }
    LOG(INFO) << "Read " << count << " records from " << kCdcStateTableName.table_name();

    VLOG(3) << "tablet_min_checkpoint_index size " << tablet_min_checkpoint_index.size();
    for (const auto &elem : tablet_min_checkpoint_index) {
      auto tablet_id = elem.first;
      std::shared_ptr<tablet::TabletPeer> tablet_peer;

      Status s = tablet_manager_->GetTabletPeer(tablet_id, &tablet_peer);
      if (s.IsNotFound()) {
        VLOG(2) << "Did not found tablet peer for tablet " << tablet_id;
        continue;
      } else if (!IsTabletPeerLeader(tablet_peer)) {
        VLOG(2) << "Tablet peer " << tablet_peer->permanent_uuid()
                << " is not the leader for tablet " << tablet_id;
        continue;
      } else if (!s.ok()) {
        LOG(WARNING) << "Error getting tablet_peer for tablet " << tablet_id << ": " << s;
        continue;
      }

      auto min_index = elem.second;
      s = tablet_peer->set_cdc_min_replicated_index(min_index);
      if (!s.ok()) {
        LOG(WARNING) << "Unable to set cdc min index for tablet peer "
                     << tablet_peer->permanent_uuid()
                     << " and tablet " << tablet_peer->tablet_id()
                     << ": " << s;
      }
      VLOG(1) << "Updating followers for tablet " << tablet_id << " with index " << min_index;
      WARN_NOT_OK(UpdatePeersCdcMinReplicatedIndex(tablet_id, min_index, current_term),
                  "UpdatePeersCdcMinReplicatedIndex failed");
    }
    LOG(INFO) << "Done reading all the indices for all tablets and updating peers";
  } while (sleep_while_not_stopped());
}

Result<client::internal::RemoteTabletPtr> CDCServiceImpl::GetRemoteTablet(
    const TabletId& tablet_id) {
  std::promise<Result<client::internal::RemoteTabletPtr>> tablet_lookup_promise;
  auto future = tablet_lookup_promise.get_future();
  auto callback = [&tablet_lookup_promise](
      const Result<client::internal::RemoteTabletPtr>& result) {
    tablet_lookup_promise.set_value(result);
  };

  auto start = CoarseMonoClock::Now();
  client()->LookupTabletById(
      tablet_id,
      /* table =*/ nullptr,
      // In case this is a split parent tablet, it will be hidden so we need this flag to access it.
      master::IncludeInactive::kTrue,
      CoarseMonoClock::Now() + MonoDelta::FromMilliseconds(FLAGS_cdc_read_rpc_timeout_ms),
      callback, client::UseCache::kFalse);
  future.wait();

  auto duration = CoarseMonoClock::Now() - start;
  if (duration > (kMaxDurationForTabletLookup * 1ms)) {
    LOG(WARNING) << "LookupTabletByKey took long time: " << duration << " ms";
  }

  auto remote_tablet = VERIFY_RESULT(future.get());
  return remote_tablet;
}

Result<RemoteTabletServer *> CDCServiceImpl::GetLeaderTServer(const TabletId& tablet_id) {
  auto result = VERIFY_RESULT(GetRemoteTablet(tablet_id));

  auto ts = result->LeaderTServer();
  if (ts == nullptr) {
    return STATUS(NotFound, "Tablet leader not found for tablet", tablet_id);
  }
  return ts;
}

Status CDCServiceImpl::GetTServers(const TabletId& tablet_id,
                                   std::vector<client::internal::RemoteTabletServer*>* servers) {
  auto result = VERIFY_RESULT(GetRemoteTablet(tablet_id));

  result->GetRemoteTabletServers(servers);
  return Status::OK();
}

std::shared_ptr<CDCServiceProxy> CDCServiceImpl::GetCDCServiceProxy(RemoteTabletServer* ts) {
  auto hostport = HostPortFromPB(DesiredHostPort(
      ts->public_rpc_hostports(), ts->private_rpc_hostports(), ts->cloud_info(),
      client()->cloud_info()));
  DCHECK(!hostport.host().empty());

  {
    SharedLock<decltype(mutex_)> l(mutex_);
    auto it = cdc_service_map_.find(hostport);
    if (it != cdc_service_map_.end()) {
      return it->second;
    }
  }

  auto cdc_service = std::make_shared<CDCServiceProxy>(&client()->proxy_cache(), hostport);

  {
    std::lock_guard<decltype(mutex_)> l(mutex_);
    auto it = cdc_service_map_.find(hostport);
    if (it != cdc_service_map_.end()) {
      return it->second;
    }
    cdc_service_map_.emplace(hostport, cdc_service);
  }
  return cdc_service;
}

void CDCServiceImpl::TabletLeaderGetChanges(const GetChangesRequestPB* req,
                                            GetChangesResponsePB* resp,
                                            std::shared_ptr<RpcContext> context,
                                            std::shared_ptr<tablet::TabletPeer> peer) {
  auto rpc_handle = rpcs_.Prepare();
  RPC_CHECK_AND_RETURN_ERROR(rpc_handle != rpcs_.InvalidHandle(),
      STATUS(Aborted,
          Format("Could not create valid handle for GetChangesCDCRpc: tablet=$0, peer=$1",
                 req->tablet_id(),
                 peer->permanent_uuid())),
      resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, *context.get());

  // Increment Proxy Metric.
  server_metrics_->cdc_rpc_proxy_count->Increment();

  // Forward this Request Info to the proper TabletServer.
  GetChangesRequestPB new_req;
  new_req.CopyFrom(*req);
  new_req.set_serve_as_proxy(false);
  CoarseTimePoint deadline = GetDeadline(*context.get(), client());

  *rpc_handle = CreateGetChangesCDCRpc(
      deadline,
      nullptr, /* RemoteTablet: will get this from 'new_req' */
      client(),
      &new_req,
      [=] (Status status, GetChangesResponsePB&& new_resp) {
        auto retained = rpcs_.Unregister(rpc_handle);
        *resp = std::move(new_resp);
        RPC_STATUS_RETURN_ERROR(status, resp->mutable_error(), resp->error().code(),
                                *context.get());
        context->RespondSuccess();
      });
  (**rpc_handle).SendRpc();
}

void CDCServiceImpl::TabletLeaderGetCheckpoint(const GetCheckpointRequestPB* req,
                                               GetCheckpointResponsePB* resp,
                                               RpcContext* context,
                                               const std::shared_ptr<tablet::TabletPeer>& peer) {
  auto result = GetLeaderTServer(req->tablet_id());
  RPC_CHECK_AND_RETURN_ERROR(result.ok(), result.status(), resp->mutable_error(),
                             CDCErrorPB::TABLET_NOT_FOUND, *context);

  auto ts_leader = *result;
  // Check that tablet leader identified by master is not current tablet peer.
  // This can happen during tablet rebalance if master and tserver have different views of
  // leader. We need to avoid self-looping in this case.
  if (peer) {
    RPC_CHECK_NE_AND_RETURN_ERROR(ts_leader->permanent_uuid(), peer->permanent_uuid(),
                                  STATUS(IllegalState,
                                         Format("Tablet leader changed: leader=$0, peer=$1",
                                                ts_leader->permanent_uuid(),
                                                peer->permanent_uuid())),
                                  resp->mutable_error(), CDCErrorPB::NOT_LEADER, *context);
  }

  auto cdc_proxy = GetCDCServiceProxy(ts_leader);
  rpc::RpcController rpc;
  rpc.set_timeout(MonoDelta::FromMilliseconds(FLAGS_cdc_read_rpc_timeout_ms));
  // TODO(NIC): Change to GetCheckpointAsync like CDCPoller::DoPoll.
  auto status = cdc_proxy->GetCheckpoint(*req, resp, &rpc);
  RPC_STATUS_RETURN_ERROR(status, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, *context);
  context->RespondSuccess();
}

void CDCServiceImpl::GetCheckpoint(const GetCheckpointRequestPB* req,
                                   GetCheckpointResponsePB* resp,
                                   RpcContext context) {
  if (!CheckOnline(req, resp, &context)) {
    return;
  }

  RPC_CHECK_AND_RETURN_ERROR(req->has_tablet_id(),
                             STATUS(InvalidArgument, "Tablet ID is required to get CDC checkpoint"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);
  RPC_CHECK_AND_RETURN_ERROR(req->has_stream_id(),
                             STATUS(InvalidArgument, "Stream ID is required to get CDC checkpoint"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  std::shared_ptr<tablet::TabletPeer> tablet_peer;
  Status s = tablet_manager_->GetTabletPeer(req->tablet_id(), &tablet_peer);

  if (s.IsNotFound() || !IsTabletPeerLeader(tablet_peer)) {
    // Forward GetChanges() to tablet leader. This happens often in Kubernetes setups.
    TabletLeaderGetCheckpoint(req, resp, &context, tablet_peer);
    return;
  }

  // Check that requested tablet_id is part of the CDC stream.
  ProducerTabletInfo producer_tablet = {"" /* UUID */, req->stream_id(), req->tablet_id()};
  s = CheckTabletValidForStream(producer_tablet);
  RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INVALID_REQUEST, context);

  auto session = client()->NewSession();
  CoarseTimePoint deadline = GetDeadline(context, client());

  session->SetDeadline(deadline);

  auto result = GetLastCheckpoint(producer_tablet, session);
  RPC_CHECK_AND_RETURN_ERROR(result.ok(), result.status(), resp->mutable_error(),
                             CDCErrorPB::INTERNAL_ERROR, context);

  result->ToPB(resp->mutable_checkpoint()->mutable_op_id());
  context.RespondSuccess();
}

void CDCServiceImpl::UpdateCdcReplicatedIndex(const UpdateCdcReplicatedIndexRequestPB* req,
                                              UpdateCdcReplicatedIndexResponsePB* resp,
                                              rpc::RpcContext context) {
  if (!CheckOnline(req, resp, &context)) {
    return;
  }

  RPC_CHECK_AND_RETURN_ERROR(req->has_tablet_id(),
                             STATUS(InvalidArgument,
                                    "Tablet ID is required to set the log replicated index"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  RPC_CHECK_AND_RETURN_ERROR(req->has_replicated_index(),
                             STATUS(InvalidArgument,
                                    "Replicated index is required to set the log replicated index"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  std::shared_ptr<tablet::TabletPeer> tablet_peer;
  RPC_STATUS_RETURN_ERROR(tablet_manager_->GetTabletPeer(req->tablet_id(), &tablet_peer),
                          resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  RPC_CHECK_AND_RETURN_ERROR(tablet_peer->log_available(),
                             STATUS(TryAgain, "Tablet peer is not ready to set its log cdc index"),
                             resp->mutable_error(),
                             CDCErrorPB::INTERNAL_ERROR,
                             context);

  RPC_STATUS_RETURN_ERROR(tablet_peer->set_cdc_min_replicated_index(req->replicated_index()),
                          resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  auto status = DoUpdateCDCConsumerOpId(tablet_peer,
                                        OpId(req->replicated_term(), req->replicated_index()),
                                        req->tablet_id());

  RPC_STATUS_RETURN_ERROR(status, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  {
    RequestScope request_scope;
    auto txn_participant = tablet_peer->tablet()->transaction_participant();
    if (txn_participant) {
      VLOG(1) << "Registering and unregistering request so that transactions are "
                   "cleaned up on followers.";
      request_scope = RequestScope(txn_participant);
    }
  }

  context.RespondSuccess();
}

Result<OpId> CDCServiceImpl::TabletLeaderLatestEntryOpId(const TabletId& tablet_id) {
    auto ts_leader = VERIFY_RESULT(GetLeaderTServer(tablet_id));

    auto cdc_proxy = GetCDCServiceProxy(ts_leader);
    rpc::RpcController rpc;
    rpc.set_timeout(MonoDelta::FromMilliseconds(FLAGS_cdc_read_rpc_timeout_ms));
    GetLatestEntryOpIdRequestPB req;
    GetLatestEntryOpIdResponsePB resp;
    req.set_tablet_id(tablet_id);
    auto status = cdc_proxy->GetLatestEntryOpId(req, &resp, &rpc);
    if (!status.ok()) {
      // If we failed to get the latest entry op id, we try other tservers. The leader is guaranteed
      // to have the most up-to-date information, but for our purposes, it's ok to be slightly
      // behind.
      std::vector<client::internal::RemoteTabletServer *> servers;
      auto s = GetTServers(tablet_id, &servers);
      for (const auto& server : servers) {
        // We don't want to try the leader again.
        if (server->permanent_uuid() == ts_leader->permanent_uuid()) {
          continue;
        }
        auto follower_cdc_proxy = GetCDCServiceProxy(server);
        status = follower_cdc_proxy->GetLatestEntryOpId(req, &resp, &rpc);
        if (status.ok()) {
          return OpId::FromPB(resp.op_id());
        }
      }
      DCHECK(!status.ok());
      return status;
    }
    return OpId::FromPB(resp.op_id());
  }

void CDCServiceImpl::GetLatestEntryOpId(const GetLatestEntryOpIdRequestPB* req,
                                        GetLatestEntryOpIdResponsePB* resp,
                                        rpc::RpcContext context) {
  std::shared_ptr<tablet::TabletPeer> tablet_peer;
  Status s = tablet_manager_->GetTabletPeer(req->tablet_id(), &tablet_peer);
  RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  if (!tablet_peer->log_available()) {
    const string err_message = strings::Substitute("Unable to get the latest entry op id from "
        "peer $0 and tablet $1 because its log object hasn't been initialized",
        tablet_peer->permanent_uuid(), tablet_peer->tablet_id());
    LOG(WARNING) << err_message;
    SetupErrorAndRespond(resp->mutable_error(),
                         STATUS(ServiceUnavailable, err_message),
                         CDCErrorPB::INTERNAL_ERROR,
                         &context);
    return;
  }
  OpId op_id = tablet_peer->log()->GetLatestEntryOpId();
  op_id.ToPB(resp->mutable_op_id());
  context.RespondSuccess();
}

void CDCServiceImpl::GetCDCDBStreamInfo(const GetCDCDBStreamInfoRequestPB* req,
                                        GetCDCDBStreamInfoResponsePB* resp,
                                        rpc::RpcContext context) {
  if (!CheckOnline(req, resp, &context)) {
    return;
  }

  LOG(INFO) << "Received GetCDCDBStreamInfo request " << req->ShortDebugString();

  RPC_CHECK_AND_RETURN_ERROR(
    req->has_db_stream_id(),
    STATUS(InvalidArgument, "Database Stream ID is required to get DB stream information"),
    resp->mutable_error(),
    CDCErrorPB::INVALID_REQUEST,
    context);

  std::vector<pair<std::string, std::string>> db_stream_info;
  Status s = client()->GetCDCDBStreamInfo(req->db_stream_id(), &db_stream_info);
  RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  for (const auto& tabinfo : db_stream_info) {
    auto* const table_info = resp->add_table_info();
    table_info->set_stream_id(tabinfo.first);
    table_info->set_table_id(tabinfo.second);
  }

  context.RespondSuccess();
}

void CDCServiceImpl::RollbackPartialCreate(const CDCCreationState& creation_state) {
  if (!creation_state.created_cdc_streams.empty()) {
    Status s = client()->DeleteCDCStream(creation_state.created_cdc_streams);
    if (!s.ok()) {
      LOG(WARNING) << "Unable to delete streams " << JoinCSVLine(creation_state.created_cdc_streams)
                   << ": " << s;
    }
  }

  // For all tablets we modified state for, reverse those changes if the operation failed
  // halfway through.
  if (creation_state.producer_entries_modified.empty()) {
    return;
  }
  std::lock_guard<decltype(mutex_)> l(mutex_);
  impl_->EraseTablets(creation_state.producer_entries_modified, false);
  for (const auto& entry : creation_state.producer_entries_modified) {
    WARN_NOT_OK(
        UpdatePeersCdcMinReplicatedIndex(entry.tablet_id, numeric_limits<uint64_t>::max()),
        "Unable to update tablet " + entry.tablet_id);
  }

}

void CDCServiceImpl::BootstrapProducer(const BootstrapProducerRequestPB* req,
                                       BootstrapProducerResponsePB* resp,
                                       rpc::RpcContext context) {
  LOG(INFO) << "Received BootstrapProducer request " << req->ShortDebugString();
  RPC_CHECK_AND_RETURN_ERROR(req->table_ids().size() > 0,
                             STATUS(InvalidArgument, "Table ID is required to create CDC stream"),
                             resp->mutable_error(),
                             CDCErrorPB::INVALID_REQUEST,
                             context);

  std::shared_ptr<client::TableHandle> cdc_state_table;

  std::vector<client::YBOperationPtr> ops;
  auto session = client()->NewSession();

  // Used to delete streams in case of failure.
  CDCCreationState creation_state;
  auto scope_exit = ScopeExit([this, &creation_state] {
    RollbackPartialCreate(creation_state);
  });

  std::vector<CDCStreamId> bootstrap_ids;

  for (const auto& table_id : req->table_ids()) {
    std::shared_ptr<client::YBTable> table;
    Status s = client()->OpenTable(table_id, &table);
    RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::TABLE_NOT_FOUND, context);

    // Generate a bootstrap id by calling CreateCDCStream, and also setup the stream in the master.
    // If the consumer's master sends a CreateCDCStream with a bootstrap id, the producer's master
    // will verify that the stream id exists and return success if it does since everything else
    // has already been done by this call.
    std::unordered_map<std::string, std::string> options;
    options.reserve(4);
    options.emplace(cdc::kRecordType, CDCRecordType_Name(cdc::CDCRecordType::CHANGE));
    options.emplace(cdc::kRecordFormat, CDCRecordFormat_Name(cdc::CDCRecordFormat::WAL));
    options.emplace(cdc::kSourceType, CDCRequestSource_Name(cdc::CDCRequestSource::XCLUSTER));
    options.emplace(cdc::kCheckpointType, CDCCheckpointType_Name(cdc::CDCCheckpointType::IMPLICIT));

    // Mark this stream as being bootstrapped, to help in finding dangling streams.
    auto result = client()->CreateCDCStream(table_id, options, false);
    RPC_CHECK_AND_RETURN_ERROR(result.ok(), result.status(), resp->mutable_error(),
                               CDCErrorPB::INTERNAL_ERROR, context);
    const std::string& bootstrap_id = *result;
    creation_state.created_cdc_streams.push_back(bootstrap_id);

    if (cdc_state_table == nullptr) {
      auto res = GetCdcStateTable();
      RPC_CHECK_AND_RETURN_ERROR(res.ok(), res.status(), resp->mutable_error(),
          CDCErrorPB::INTERNAL_ERROR, context);
      cdc_state_table = *res;
    }

    google::protobuf::RepeatedPtrField<master::TabletLocationsPB> tablets;
    s = client()->GetTabletsFromTableId(table_id, 0, &tablets);
    RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::TABLE_NOT_FOUND, context);

    // For each tablet, create a row in cdc_state table containing the generated bootstrap id, and
    // the latest op id in the logs.
    for (const auto &tablet : tablets) {
      std::shared_ptr<tablet::TabletPeer> tablet_peer;
      OpId op_id;

      s = tablet_manager_->GetTabletPeer(tablet.tablet_id(), &tablet_peer);
      if (!s.ok()) {
        auto res = TabletLeaderLatestEntryOpId(tablet.tablet_id());
        RPC_CHECK_AND_RETURN_ERROR(res.ok(), res.status(), resp->mutable_error(),
            CDCErrorPB::INTERNAL_ERROR, context);
        op_id = *res;
      } else {
        if (!tablet_peer->log_available()) {
          const string err_message = strings::Substitute("Unable to get the latest entry op id "
              "from peer $0 and tablet $1 because its log object hasn't been initialized",
              tablet_peer->permanent_uuid(), tablet_peer->tablet_id());
          LOG(WARNING) << err_message;
          SetupErrorAndRespond(resp->mutable_error(),
                               STATUS(ServiceUnavailable, err_message),
                               CDCErrorPB::INTERNAL_ERROR,
                               &context);
          return;
        }
        op_id = tablet_peer->log()->GetLatestEntryOpId();
        RPC_STATUS_RETURN_ERROR(UpdatePeersCdcMinReplicatedIndex(tablet.tablet_id(), op_id.index),
                                resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR,
                                context);
      }

      const auto op = cdc_state_table->NewWriteOp(QLWriteRequestPB::QL_STMT_INSERT);
      auto *const write_req = op->mutable_request();

      QLAddStringHashValue(write_req, tablet.tablet_id());
      QLAddStringRangeValue(write_req, bootstrap_id);
      cdc_state_table->AddStringColumnValue(write_req, master::kCdcCheckpoint, op_id.ToString());
      ops.push_back(std::move(op));
      impl_->AddTabletCheckpoint(
          op_id, bootstrap_id, tablet.tablet_id(), &creation_state.producer_entries_modified);
    }
    bootstrap_ids.push_back(std::move(bootstrap_id));
  }
  CoarseTimePoint deadline = GetDeadline(context, client());

  session->SetDeadline(deadline);
  Status s = RefreshCacheOnFail(session->ApplyAndFlush(ops));
  RPC_STATUS_RETURN_ERROR(s, resp->mutable_error(), CDCErrorPB::INTERNAL_ERROR, context);

  for (const auto& bootstrap_id : bootstrap_ids) {
    resp->add_cdc_bootstrap_ids(bootstrap_id);
  }
  // Clear these vectors so no changes are reversed by scope_exit since we succeeded.
  creation_state.Clear();
  context.RespondSuccess();
}

void CDCServiceImpl::Shutdown() {
  if (impl_->async_client_init_) {
    impl_->async_client_init_->Shutdown();
    rpcs_.Shutdown();
    {
      std::lock_guard<decltype(mutex_)> l(mutex_);
      cdc_service_stopped_ = true;
      cdc_state_table_ = nullptr;
    }
    if (update_peers_and_metrics_thread_) {
      update_peers_and_metrics_thread_->join();
    }
    impl_->async_client_init_ = boost::none;
  }
}

Result<OpId> CDCServiceImpl::GetLastCheckpoint(
    const ProducerTabletInfo& producer_tablet,
    const client::YBSessionPtr& session) {
  auto result = impl_->GetLastCheckpoint(producer_tablet);
  if (result) {
    return *result;
  }

  auto cdc_state_table_result = GetCdcStateTable();
  RETURN_NOT_OK(cdc_state_table_result);

  const auto op = (*cdc_state_table_result)->NewReadOp();
  auto* const req = op->mutable_request();
  DCHECK(!producer_tablet.stream_id.empty() && !producer_tablet.tablet_id.empty());
  QLAddStringHashValue(req, producer_tablet.tablet_id);

  auto cond = req->mutable_where_expr()->mutable_condition();
  cond->set_op(QLOperator::QL_OP_AND);
  QLAddStringCondition(cond, Schema::first_column_id() + master::kCdcStreamIdIdx,
      QL_OP_EQUAL, producer_tablet.stream_id);
  req->mutable_column_refs()->add_ids(Schema::first_column_id() + master::kCdcTabletIdIdx);
  req->mutable_column_refs()->add_ids(Schema::first_column_id() + master::kCdcStreamIdIdx);
  (*cdc_state_table_result)->AddColumns({master::kCdcCheckpoint}, req);

  RETURN_NOT_OK(RefreshCacheOnFail(session->ReadSync(op)));
  auto row_block = ql::RowsResult(op.get()).GetRowBlock();
  if (row_block->row_count() == 0) {
    return OpId(0, 0);
  }

  DCHECK_EQ(row_block->row_count(), 1);
  DCHECK_EQ(row_block->row(0).column(0).type(), InternalType::kStringValue);

  return OpId::FromString(row_block->row(0).column(0).string_value());
}

void CDCServiceImpl::UpdateCDCTabletMetrics(
    const GetChangesResponsePB* resp,
    const ProducerTabletInfo& producer_tablet,
    const std::shared_ptr<tablet::TabletPeer>& tablet_peer,
    const OpId& op_id,
    int64_t last_readable_index) {
  auto tablet_metric = GetCDCTabletMetrics(producer_tablet, tablet_peer);
  if (!tablet_metric) {
    return;
  }

  auto lid = resp->checkpoint().op_id();
  tablet_metric->last_read_opid_term->set_value(lid.term());
  tablet_metric->last_read_opid_index->set_value(lid.index());
  tablet_metric->last_readable_opid_index->set_value(last_readable_index);
  tablet_metric->last_checkpoint_opid_index->set_value(op_id.index);
  if (resp->records_size() > 0) {
    auto& last_record = resp->records(resp->records_size() - 1);
    tablet_metric->last_read_hybridtime->set_value(last_record.time());
    auto last_record_micros = HybridTime(last_record.time()).GetPhysicalValueMicros();
    tablet_metric->last_read_physicaltime->set_value(last_record_micros);
    // Only count bytes responded if we are including a response payload.
    tablet_metric->rpc_payload_bytes_responded->Increment(resp->ByteSize());
    // Get the physical time of the last committed record on producer.
    auto last_replicated_micros = GetLastReplicatedTime(tablet_peer);
    tablet_metric->async_replication_sent_lag_micros->set_value(
        last_replicated_micros - last_record_micros);
    auto& first_record = resp->records(0);
    auto first_record_micros = HybridTime(first_record.time()).GetPhysicalValueMicros();
    tablet_metric->last_checkpoint_physicaltime->set_value(first_record_micros);
    tablet_metric->async_replication_committed_lag_micros->set_value(
        last_replicated_micros - first_record_micros);
  } else {
    tablet_metric->rpc_heartbeats_responded->Increment();
    // If there are no more entries to be read, that means we're caught up.
    auto last_replicated_micros = GetLastReplicatedTime(tablet_peer);
    tablet_metric->last_read_physicaltime->set_value(last_replicated_micros);
    tablet_metric->last_checkpoint_physicaltime->set_value(last_replicated_micros);
    tablet_metric->async_replication_sent_lag_micros->set_value(0);
    tablet_metric->async_replication_committed_lag_micros->set_value(0);
  }
}

Status CDCServiceImpl::UpdateCheckpoint(const ProducerTabletInfo& producer_tablet,
                                        const OpId& sent_op_id,
                                        const OpId& commit_op_id,
                                        const client::YBSessionPtr& session,
                                        uint64_t last_record_hybrid_time) {
  bool update_cdc_state = impl_->UpdateCheckpoint(producer_tablet, sent_op_id, commit_op_id);

  if (update_cdc_state) {
    auto cdc_state = VERIFY_RESULT(GetCdcStateTable());
    const auto op = cdc_state->NewUpdateOp();
    auto* const req = op->mutable_request();
    DCHECK(!producer_tablet.stream_id.empty() && !producer_tablet.tablet_id.empty());
    QLAddStringHashValue(req, producer_tablet.tablet_id);
    QLAddStringRangeValue(req, producer_tablet.stream_id);

    cdc_state->AddStringColumnValue(req, master::kCdcCheckpoint, commit_op_id.ToString());
    // If we have a last record hybrid time, use that for physical time. If not, it means we're
    // caught up, so the current time.
    uint64_t last_replication_time_micros = last_record_hybrid_time != 0 ?
        HybridTime(last_record_hybrid_time).GetPhysicalValueMicros() : GetCurrentTimeMicros();
    cdc_state->AddTimestampColumnValue(req, master::kCdcLastReplicationTime,
                                       last_replication_time_micros);
    // Only perform the update if we have a row in cdc_state to prevent a race condition where
    // a stream is deleted and then this logic inserts entries in cdc_state from that deleted
    // stream.
    auto* condition = req->mutable_if_expr()->mutable_condition();
    condition->set_op(QL_OP_EXISTS);
    RETURN_NOT_OK(RefreshCacheOnFail(session->ApplyAndFlush(op)));
  }

  return Status::OK();
}

std::shared_ptr<CDCTabletMetrics> CDCServiceImpl::GetCDCTabletMetrics(
    const ProducerTabletInfo& producer,
    std::shared_ptr<tablet::TabletPeer> tablet_peer) {
  // 'nullptr' not recommended: using for tests.
  if (tablet_peer == nullptr) {
    auto status = tablet_manager_->GetTabletPeer(producer.tablet_id, &tablet_peer);
    if (!status.ok() || tablet_peer == nullptr) return nullptr;
  }

  auto tablet = tablet_peer->shared_tablet();
  if (tablet == nullptr) return nullptr;

  std::string key = "CDCMetrics::" + producer.stream_id;
  std::shared_ptr<void> metrics_raw = tablet->GetAdditionalMetadata(key);
  if (metrics_raw == nullptr) {
    //  Create a new METRIC_ENTITY_cdc here.
    MetricEntity::AttributeMap attrs;
    {
      SharedLock<rw_spinlock> l(mutex_);
      auto raft_group_metadata = tablet_peer->tablet()->metadata();
      attrs["table_id"] = raft_group_metadata->table_id();
      attrs["namespace_name"] = raft_group_metadata->namespace_name();
      attrs["table_name"] = raft_group_metadata->table_name();
      attrs["stream_id"] = producer.stream_id;
    }
    auto entity = METRIC_ENTITY_cdc.Instantiate(metric_registry_, producer.MetricsString(), attrs);
    metrics_raw = std::make_shared<CDCTabletMetrics>(entity);
    // Adding the new metric to the tablet so it maintains the same lifetime scope.
    tablet->AddAdditionalMetadata(key, metrics_raw);
  }

  return std::static_pointer_cast<CDCTabletMetrics>(metrics_raw);
}

Result<std::shared_ptr<StreamMetadata>> CDCServiceImpl::GetStream(const std::string& stream_id) {
  auto stream = GetStreamMetadataFromCache(stream_id);
  if (stream != nullptr) {
    return stream;
  }

  // Look up stream in sys catalog.
  std::vector<ObjectId> object_ids;
  NamespaceId ns_id;
  std::unordered_map<std::string, std::string> options;
  RETURN_NOT_OK(client()->GetCDCStream(stream_id, &ns_id, &object_ids, &options));

  auto stream_metadata = std::make_shared<StreamMetadata>();
  for (const auto& option : options) {
    if (option.first == kRecordType) {
      SCHECK(CDCRecordType_Parse(option.second, &stream_metadata->record_type),
             IllegalState, "CDC record type parsing error");
    } else if (option.first == kRecordFormat) {
      SCHECK(CDCRecordFormat_Parse(option.second, &stream_metadata->record_format),
             IllegalState, "CDC record format parsing error");
    } else if (option.first == kSourceType) {
      SCHECK(CDCRequestSource_Parse(option.second, &stream_metadata->source_type), IllegalState,
             "CDC record format parsing error");
    } else if (option.first == kCheckpointType) {
      SCHECK(CDCCheckpointType_Parse(option.second, &stream_metadata->checkpoint_type),
             IllegalState, "CDC record format parsing error");
    } else if (option.first == cdc::kIdType && option.second == cdc::kNamespaceId) {
      stream_metadata->ns_id = ns_id;
      stream_metadata->table_ids.insert(
          stream_metadata->table_ids.end(), object_ids.begin(), object_ids.end());
    } else if (option.first == cdc::kIdType && option.second == cdc::kTableId) {
      stream_metadata->table_ids.insert(
          stream_metadata->table_ids.end(), object_ids.begin(), object_ids.end());
    } else {
      LOG(WARNING) << "Unsupported CDC option: " << option.first;
    }
  }

  AddStreamMetadataToCache(stream_id, stream_metadata);
  return stream_metadata;
}

void CDCServiceImpl::AddStreamMetadataToCache(const std::string& stream_id,
                                              const std::shared_ptr<StreamMetadata>& metadata) {
  std::lock_guard<decltype(mutex_)> l(mutex_);
  stream_metadata_.emplace(stream_id, metadata);
}

std::shared_ptr<StreamMetadata> CDCServiceImpl::GetStreamMetadataFromCache(
    const std::string& stream_id) {
  SharedLock<decltype(mutex_)> l(mutex_);
  auto it = stream_metadata_.find(stream_id);
  if (it != stream_metadata_.end()) {
    return it->second;
  } else {
    return nullptr;
  }
}

Status CDCServiceImpl::CheckTabletValidForStream(const ProducerTabletInfo& info) {
  auto result = VERIFY_RESULT(impl_->PreCheckTabletValidForStream(info));
  if (result) {
    return Status::OK();
  }
  // If we don't recognize the stream_id, populate our full tablet list for this stream.
  auto tablets = VERIFY_RESULT(GetTablets(info.stream_id));
  return impl_->CheckTabletValidForStream(info, tablets);
}

}  // namespace cdc
}  // namespace yb
