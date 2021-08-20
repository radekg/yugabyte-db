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

#ifndef YB_INTEGRATION_TESTS_BACKFILL_TEST_UTIL_H
#define YB_INTEGRATION_TESTS_BACKFILL_TEST_UTIL_H

#include <algorithm>
#include <string>

#include "yb/integration-tests/external_mini_cluster.h"
#include "yb/master/master.pb.h"
#include "yb/master/master.proxy.h"
#include "yb/master/master_service.h"
#include "yb/util/test_util.h"
#include "yb/client/yb_table_name.h"

namespace yb {

Result<master::BackfillJobPB> GetBackfillJobs(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const master::TableIdentifierPB &table_identifier) {
  master::GetBackfillJobsRequestPB req;
  master::GetBackfillJobsResponsePB resp;
  rpc::RpcController rpc;
  constexpr auto kAdminRpcTimeout = 5;
  rpc.set_timeout(MonoDelta::FromSeconds(kAdminRpcTimeout));

  req.mutable_table_identifier()->CopyFrom(table_identifier);
  RETURN_NOT_OK(proxy->GetBackfillJobs(req, &resp, &rpc));
  if (resp.backfill_jobs_size() == 0) {
    return STATUS(NotFound, "No backfill job running yet");
  } else {
    CHECK_EQ(resp.backfill_jobs_size(), 1) << "As of now only one outstanding backfill "
                                           << "job should be pending.";
    return resp.backfill_jobs(0);
  }
}

Result<master::BackfillJobPB> GetBackfillJobs(
    ExternalMiniCluster* cluster,
    const client::YBTableName& table_name) {
  master::TableIdentifierPB table_identifier;
  table_name.SetIntoTableIdentifierPB(&table_identifier);
  return GetBackfillJobs(cluster->master_proxy(), table_identifier);
}

Result<master::BackfillJobPB> GetBackfillJobs(
    ExternalMiniCluster* cluster,
    const TableId& table_id) {
  master::TableIdentifierPB table_identifier;
  if (!table_id.empty()) {
    table_identifier.set_table_id(table_id);
  }
  return GetBackfillJobs(cluster->master_proxy(), table_identifier);
}

CHECKED_STATUS WaitForBackfillSatisfyCondition(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const master::TableIdentifierPB& table_identifier,
    const std::function<Result<bool>(Result<master::BackfillJobPB>)>& condition,
    MonoDelta max_wait) {
  return WaitFor(
      [proxy, condition, &table_identifier]() {
        Result<master::BackfillJobPB> backfill_job = GetBackfillJobs(proxy, table_identifier);
        return condition(backfill_job);
      },
      max_wait, "Waiting for backfill to satisfy condition.");
}

CHECKED_STATUS WaitForBackfillSatisfyCondition(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const client::YBTableName& table_name,
    const std::function<Result<bool>(Result<master::BackfillJobPB>)>& condition,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  master::TableIdentifierPB table_identifier;
  table_name.SetIntoTableIdentifierPB(&table_identifier);
  return WaitForBackfillSatisfyCondition(proxy, table_identifier, condition, max_wait);
}

CHECKED_STATUS WaitForBackfillSatisfyCondition(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const TableId& table_id,
    const std::function<Result<bool>(Result<master::BackfillJobPB>)>& condition,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  master::TableIdentifierPB table_identifier;
  if (!table_id.empty()) {
    table_identifier.set_table_id(table_id);
  }
  return WaitForBackfillSatisfyCondition(proxy, table_identifier, condition, max_wait);
}

CHECKED_STATUS WaitForBackfillSafeTimeOn(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const master::TableIdentifierPB& table_identifier,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  return WaitFor(
      [proxy, &table_identifier]() {
        Result<master::BackfillJobPB> backfill_job = GetBackfillJobs(proxy, table_identifier);
        return backfill_job && backfill_job->has_backfilling_timestamp();
      },
      max_wait, "waiting for backfill to get past GetSafeTime.");
}

CHECKED_STATUS WaitForBackfillSafeTimeOn(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const client::YBTableName& table_name,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  master::TableIdentifierPB table_identifier;
  table_name.SetIntoTableIdentifierPB(&table_identifier);
  return WaitForBackfillSafeTimeOn(proxy, table_identifier, max_wait);
}

CHECKED_STATUS WaitForBackfillSafeTimeOn(
    std::shared_ptr<master::MasterServiceProxy> proxy,
    const TableId& table_id,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  master::TableIdentifierPB table_identifier;
  if (!table_id.empty()) {
    table_identifier.set_table_id(table_id);
  }
  return WaitForBackfillSafeTimeOn(proxy, table_identifier, max_wait);
}

CHECKED_STATUS WaitForBackfillSafeTimeOn(
    ExternalMiniCluster* cluster,
    const client::YBTableName& table_name,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  return WaitForBackfillSafeTimeOn(cluster->master_proxy(), table_name, max_wait);
}

CHECKED_STATUS WaitForBackfillSafeTimeOn(
    ExternalMiniCluster* cluster,
    const TableId& table_id,
    MonoDelta max_wait = MonoDelta::FromSeconds(60)) {
  return WaitForBackfillSafeTimeOn(cluster->master_proxy(), table_id, max_wait);
}

}  // namespace yb

#endif  // YB_INTEGRATION_TESTS_BACKFILL_TEST_UTIL_H
