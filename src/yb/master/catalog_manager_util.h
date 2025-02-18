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

#ifndef YB_MASTER_CATALOG_MANAGER_UTIL_H
#define YB_MASTER_CATALOG_MANAGER_UTIL_H

#include <unordered_map>
#include <vector>

#include "yb/consensus/consensus_fwd.h"
#include "yb/master/catalog_entity_info.h"
#include "yb/master/master_fwd.h"
#include "yb/master/ts_descriptor.h"

DECLARE_bool(transaction_tables_use_preferred_zones);

// Utility functions that can be shared between test and code for catalog manager.
namespace yb {
namespace master {

using ZoneToDescMap = std::unordered_map<string, TSDescriptorVector>;

struct Comparator;

class CatalogManagerUtil {
 public:
  // For the given set of descriptors, checks if the load is considered balanced across AZs in
  // multi AZ setup, else checks load distribution across tservers (single AZ).
  static CHECKED_STATUS IsLoadBalanced(const TSDescriptorVector& ts_descs);

  // For the given set of descriptors, checks if every tserver that shouldn't have leader load
  // actually has no leader load.
  // If transaction_tables_use_preferred_zones = false, then we also check if txn status tablet
  // leaders are spread evenly based on the information in `tables`.
  static CHECKED_STATUS AreLeadersOnPreferredOnly(
      const TSDescriptorVector& ts_descs,
      const ReplicationInfoPB& replication_info,
      const vector<scoped_refptr<TableInfo>>& tables = {});

  // Creates a mapping from tserver uuid to the number of transaction leaders present.
  static void CalculateTxnLeaderMap(std::map<std::string, int>* txn_map,
                                    int* num_txn_tablets,
                                    vector<scoped_refptr<TableInfo>> tables);

  // For the given set of descriptors, returns the map from each placement AZ to list of tservers
  // running in that zone.
  static CHECKED_STATUS GetPerZoneTSDesc(const TSDescriptorVector& ts_descs,
                                         ZoneToDescMap* zone_to_ts);

  // Checks whether two given cloud infos are identical.
  static bool IsCloudInfoEqual(const CloudInfoPB& lhs, const CloudInfoPB& rhs);

  // For the given placement info, checks whether a given cloud info is contained within it.
  static bool DoesPlacementInfoContainCloudInfo(const PlacementInfoPB& placement_info,
                                                const CloudInfoPB& cloud_info);

  // Checks whether the given placement info spans more than one region.
  static bool DoesPlacementInfoSpanMultipleRegions(const PlacementInfoPB& placement_info);

  // Called when registering a ts from raft, deduce a tservers placement from the peer's role
  // and cloud info.
  static Result<std::string> GetPlacementUuidFromRaftPeer(
      const ReplicationInfoPB& replication_info, const consensus::RaftPeerPB& peer);

  // Returns error if tablet partition is not covered by running inner tablets partitions.
  static CHECKED_STATUS CheckIfCanDeleteSingleTablet(const scoped_refptr<TabletInfo>& tablet);

  enum CloudInfoSimilarity {
    NO_MATCH = 0,
    CLOUD_MATCH = 1,
    REGION_MATCH = 2,
    ZONE_MATCH = 3
  };

  // Computes a similarity score between two cloudinfos (which may be prefixes).
  // 0: different clouds
  // 1: same cloud, different region
  // 2: same cloud and region, different zone
  // 3: same cloud and region and zone, or prefix matches
  static CloudInfoSimilarity ComputeCloudInfoSimilarity(const CloudInfoPB& ci1,
                                                        const CloudInfoPB& ci2);

  // Checks if one cloudinfo is a prefix of another. This assumes that ci1 and ci2 are
  // prefixes.
  static bool IsCloudInfoPrefix(const CloudInfoPB& ci1, const CloudInfoPB& ci2);

  // Validate if the specified placement information conforms to the rules.
  // Currently, the following assumption about placement blocks is made.
  // Every TS should have a unique placement block to which it can be mapped.
  // This translates to placement blocks being disjoint i.e. no placement
  // block string (C.R.Z format) should be proper prefix of another.
  // Validate placement information if passed.
  static CHECKED_STATUS IsPlacementInfoValid(const PlacementInfoPB& placement_info);

  template<class LoadState>
  static void FillTableLoadState(const scoped_refptr<TableInfo>& table_info, LoadState* state) {
    auto tablets = table_info->GetTablets(IncludeInactive::kTrue);

    for (const auto& tablet : tablets) {
      // Ignore if tablet is not running.
      {
        auto tablet_lock = tablet->LockForRead();
        if (!tablet_lock->is_running()) {
          continue;
        }
      }
      auto replica_locs = tablet->GetReplicaLocations();

      for (const auto& loc : *replica_locs) {
        // Ignore replica if not present in the tserver list passed.
        if (state->per_ts_load_.count(loc.first) == 0) {
          continue;
        }
        // Account for this load.
        state->per_ts_load_[loc.first]++;
      }
    }
  }

 private:
  CatalogManagerUtil();

  DISALLOW_COPY_AND_ASSIGN(CatalogManagerUtil);
};

class CMGlobalLoadState {
 public:
  uint32_t GetGlobalLoad(const TabletServerId& id) {
    return per_ts_load_[id];
  }
  std::unordered_map<TabletServerId, uint32_t> per_ts_load_;
};

class CMPerTableLoadState {
 public:
  explicit CMPerTableLoadState(CMGlobalLoadState* global_state)
    : global_load_state_(global_state) {}

  bool CompareLoads(const TabletServerId& ts1, const TabletServerId& ts2);

  void SortLoad();

  std::vector<TabletServerId> sorted_load_;
  std::unordered_map<TabletServerId, uint32_t> per_ts_load_;
  CMGlobalLoadState* global_load_state_;
};

struct Comparator {
  explicit Comparator(CMPerTableLoadState* state) : state_(state) {}

  bool operator()(const TabletServerId& id1, const TabletServerId& id2) {
    return state_->CompareLoads(id1, id2);
  }

  CMPerTableLoadState* state_;
};

} // namespace master
} // namespace yb

#endif // YB_MASTER_CATALOG_MANAGER_UTIL_H
