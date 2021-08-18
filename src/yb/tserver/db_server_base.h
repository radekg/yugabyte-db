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

#ifndef YB_TSERVER_DB_SERVER_BASE_H
#define YB_TSERVER_DB_SERVER_BASE_H

#include "yb/client/client_fwd.h"

#include "yb/server/server_base.h"

#include "yb/tserver/tserver_util_fwd.h"
#include "yb/tserver/tablet_server_interface.h"

namespace yb {
namespace tserver {

class DbServerBase : public server::RpcAndWebServerBase {
 public:
  DbServerBase(
      std::string name, const server::ServerBaseOptions& options,
      const std::string& metrics_namespace,
      std::shared_ptr<MemTracker> mem_tracker);
  ~DbServerBase();

  int GetSharedMemoryFd() {
    return shared_object_.GetFd();
  }

  client::TransactionPool* TransactionPool();

  virtual const std::shared_future<client::YBClient*>& client_future() const = 0;

  virtual client::LocalTabletFilter CreateLocalTabletFilter() = 0;

 protected:
  tserver::TServerSharedData& shared_object() {
    return *shared_object_;
  }

  // Shared memory owned by the tablet server.
  tserver::TServerSharedObject shared_object_;

  std::atomic<client::TransactionPool*> transaction_pool_{nullptr};
  std::mutex transaction_pool_mutex_;
  std::unique_ptr<client::TransactionManager> transaction_manager_holder_;
  std::unique_ptr<client::TransactionPool> transaction_pool_holder_;
};

}  // namespace tserver
}  // namespace yb

#endif  // YB_TSERVER_DB_SERVER_BASE_H
