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

#include <gtest/gtest.h>

#include "yb/integration-tests/mini_cluster.h"
#include "yb/util/test_macros.h"
#include "yb/util/test_util.h"
#include "yb/yql/pgwrapper/pg_mini_test_base.h"

#include "yb/master/catalog_entity_info.h"
#include "yb/master/catalog_manager.h"
#include "yb/master/mini_master.h"
#include "yb/master/sys_catalog_constants.h"

#include "yb/util/logging.h"
#include "yb/yql/pggate/pggate_flags.h"

#include "yb/common/pgsql_error.h"
#include "yb/common/row_mark.h"
#include "yb/util/random_util.h"
#include "yb/util/scope_exit.h"

using namespace std::literals;

DECLARE_bool(flush_rocksdb_on_shutdown);
DECLARE_bool(TEST_force_master_leader_resolution);
DECLARE_double(TEST_respond_write_failed_probability);
DECLARE_double(TEST_transaction_ignore_applying_probability_in_tests);
DECLARE_int32(history_cutoff_propagation_interval_ms);
DECLARE_int32(timestamp_history_retention_interval_sec);
DECLARE_int32(txn_max_apply_batch_records);
DECLARE_int64(apply_intents_task_injected_delay_ms);
DECLARE_uint64(max_clock_skew_usec);
DECLARE_int64(db_write_buffer_size);
DECLARE_bool(rocksdb_use_logging_iterator);
DECLARE_bool(enable_automatic_tablet_splitting);
DECLARE_int32(yb_num_shards_per_tserver);
DECLARE_int64(tablet_split_low_phase_size_threshold_bytes);
DECLARE_int64(tablet_split_high_phase_size_threshold_bytes);
DECLARE_int64(tablet_split_low_phase_shard_count_per_node);
DECLARE_int64(tablet_split_high_phase_shard_count_per_node);
DECLARE_int32(max_queued_split_candidates);

DECLARE_int32(heartbeat_interval_ms);
DECLARE_int32(process_split_tablet_candidates_interval_msec);
DECLARE_int32(tserver_heartbeat_metrics_interval_ms);

DECLARE_int64(db_block_size_bytes);
DECLARE_int64(db_filter_block_size_bytes);
DECLARE_int64(db_index_block_size_bytes);
DECLARE_int64(tablet_force_split_threshold_bytes);

DECLARE_bool(enable_pg_savepoints);

namespace yb {
namespace pgwrapper {
namespace {

template<IsolationLevel level>
class TxnHelper {
 public:
  static CHECKED_STATUS StartTxn(PGConn* connection) {
    return connection->StartTransaction(level);
  }

  static CHECKED_STATUS ExecuteInTxn(PGConn* connection, const std::string& query) {
    const auto guard = CreateTxnGuard(connection);
    return connection->Execute(query);
  }

  static Result<PGResultPtr> FetchInTxn(PGConn* connection, const std::string& query) {
    const auto guard = CreateTxnGuard(connection);
    return connection->Fetch(query);
  }

 private:
  static auto CreateTxnGuard(PGConn* connection) {
    EXPECT_OK(StartTxn(connection));
    return ScopeExit([connection]() {
      // Event in case some operations in transaction failed the COMMIT command
      // will complete successfully as ROLLBACK will be performed by postgres.
      EXPECT_OK(connection->Execute("COMMIT"));
    });
  }
};

std::string RowMarkTypeToPgsqlString(const RowMarkType row_mark_type) {
  switch (row_mark_type) {
    case RowMarkType::ROW_MARK_EXCLUSIVE:
      return "UPDATE";
    case RowMarkType::ROW_MARK_NOKEYEXCLUSIVE:
      return "NO KEY UPDATE";
    case RowMarkType::ROW_MARK_SHARE:
      return "SHARE";
    case RowMarkType::ROW_MARK_KEYSHARE:
      return "KEY SHARE";
    default:
      // We shouldn't get here because other row lock types are disabled at the postgres level.
      LOG(DFATAL) << "Unsupported row lock of type " << RowMarkType_Name(row_mark_type);
      return "";
  }
}

} // namespace

class PgMiniTest : public PgMiniTestBase {
 protected:
  // Have several threads doing updates and several threads doing large scans in parallel.
  // If deferrable is true, then the scans are in deferrable transactions, so no read restarts are
  // expected.
  // Otherwise, the scans are in transactions with snapshot isolation, but we still don't expect any
  // read restarts to be observed because they should be transparently handled on the postgres side.
  void TestReadRestart(bool deferrable = true);

  // Run interleaved INSERT, SELECT with specified isolation level and row mark.  Possible isolation
  // levels are SNAPSHOT_ISOLATION and SERIALIZABLE_ISOLATION.  Possible row marks are
  // ROW_MARK_KEYSHARE, ROW_MARK_SHARE, ROW_MARK_NOKEYEXCLUSIVE, and ROW_MARK_EXCLUSIVE.
  void TestInsertSelectRowLock(IsolationLevel isolation, RowMarkType row_mark);

  // Run interleaved DELETE, SELECT with specified isolation level and row mark.  Possible isolation
  // levels are SNAPSHOT_ISOLATION and SERIALIZABLE_ISOLATION.  Possible row marks are
  // ROW_MARK_KEYSHARE, ROW_MARK_SHARE, ROW_MARK_NOKEYEXCLUSIVE, and ROW_MARK_EXCLUSIVE.
  void TestDeleteSelectRowLock(IsolationLevel isolation, RowMarkType row_mark);

  void TestForeignKey(IsolationLevel isolation);

  void TestBigInsert(bool restart);

  void CreateTableAndInitialize(std::string table_name, int num_tablets);

  void DestroyTable(std::string table_name);

  void GetTableIDFromTableName(const std::string table_name, std::string* table_id);

  void StartReadWriteThreads(std::string table_name, TestThreadHolder *thread_holder);

  void TestConcurrentDeleteRowAndUpdateColumn(bool select_before_update);

  void FlushAndCompactTablets() {
    FLAGS_timestamp_history_retention_interval_sec = 0;
    FLAGS_history_cutoff_propagation_interval_ms = 1;
    ASSERT_OK(cluster_->FlushTablets(tablet::FlushMode::kSync));
    const auto compaction_start = MonoTime::Now();
    ASSERT_OK(cluster_->CompactTablets());
    const auto compaction_finish = MonoTime::Now();
    const double compaction_elapsed_time_sec = (compaction_finish - compaction_start).ToSeconds();
    LOG(INFO) << "Compaction duration: " << compaction_elapsed_time_sec << " s";
  }
};

class PgMiniSingleTServerTest : public PgMiniTest {
 public:
  int NumTabletServers() override {
    return 1;
  }
};

class PgMiniMasterFailoverTest : public PgMiniTest {
 public:
  int NumMasters() override {
    return 3;
  }
};

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(Simple)) {
  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.Execute("CREATE TABLE t (key INT PRIMARY KEY, value TEXT)"));
  ASSERT_OK(conn.Execute("INSERT INTO t (key, value) VALUES (1, 'hello')"));

  auto value = ASSERT_RESULT(conn.FetchValue<std::string>("SELECT value FROM t WHERE key = 1"));
  ASSERT_EQ(value, "hello");
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(WriteRetry)) {
  constexpr int kKeys = 100;
  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.Execute("CREATE TABLE t (key INT PRIMARY KEY)"));

  SetAtomicFlag(0.25, &FLAGS_TEST_respond_write_failed_probability);

  LOG(INFO) << "Insert " << kKeys << " keys";
  for (int key = 0; key != kKeys; ++key) {
    auto status = conn.ExecuteFormat("INSERT INTO t (key) VALUES ($0)", key);
    ASSERT_TRUE(status.ok() || PgsqlError(status) == YBPgErrorCode::YB_PG_UNIQUE_VIOLATION ||
                status.ToString().find("Already present: Duplicate request") != std::string::npos)
        << status;
  }

  SetAtomicFlag(0, &FLAGS_TEST_respond_write_failed_probability);

  auto result = ASSERT_RESULT(conn.FetchMatrix("SELECT * FROM t ORDER BY key", kKeys, 1));
  for (int key = 0; key != kKeys; ++key) {
    auto fetched_key = ASSERT_RESULT(GetInt32(result.get(), key, 0));
    ASSERT_EQ(fetched_key, key);
  }

  LOG(INFO) << "Insert duplicate key";
  auto status = conn.Execute("INSERT INTO t (key) VALUES (1)");
  ASSERT_EQ(PgsqlError(status), YBPgErrorCode::YB_PG_UNIQUE_VIOLATION) << status;
  ASSERT_STR_CONTAINS(status.ToString(), "duplicate key value violates unique constraint");
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(With)) {
  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.Execute("CREATE TABLE test (k int PRIMARY KEY, v int)"));

  ASSERT_OK(conn.Execute(
      "WITH test2 AS (UPDATE test SET v = 2 WHERE k = 1) "
      "UPDATE test SET v = 3 WHERE k = 1"));
}

void PgMiniTest::TestReadRestart(const bool deferrable) {
  constexpr CoarseDuration kWaitTime = 60s;
  constexpr int kKeys = 100;
  constexpr int kNumReadThreads = 8;
  constexpr int kNumUpdateThreads = 8;
  constexpr int kRequiredNumReads = 500;
  constexpr std::chrono::milliseconds kClockSkew = -100ms;
  std::atomic<int> num_read_restarts(0);
  std::atomic<int> num_read_successes(0);
  TestThreadHolder thread_holder;

  // Set up table
  auto setup_conn = ASSERT_RESULT(Connect());
  ASSERT_OK(setup_conn.Execute("CREATE TABLE t (key INT PRIMARY KEY, value INT)"));
  for (int key = 0; key != kKeys; ++key) {
    ASSERT_OK(setup_conn.Execute(Format("INSERT INTO t (key, value) VALUES ($0, 0)", key)));
  }

  // Introduce clock skew
  auto delta_changers = SkewClocks(cluster_.get(), kClockSkew);

  // Start read threads
  for (int i = 0; i < kNumReadThreads; ++i) {
    thread_holder.AddThreadFunctor([this, deferrable, &num_read_restarts, &num_read_successes,
                                    &stop = thread_holder.stop_flag()] {
      auto read_conn = ASSERT_RESULT(Connect());
      while (!stop.load(std::memory_order_acquire)) {
        if (deferrable) {
          ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY, "
                                      "DEFERRABLE"));
        } else {
          ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ"));
        }
        auto result = read_conn.FetchMatrix("SELECT * FROM t", kKeys, 2);
        if (!result.ok()) {
          ASSERT_TRUE(result.status().IsNetworkError()) << result.status();
          ASSERT_EQ(PgsqlError(result.status()), YBPgErrorCode::YB_PG_T_R_SERIALIZATION_FAILURE)
              << result.status();
          ASSERT_STR_CONTAINS(result.status().ToString(), "Restart read");
          ++num_read_restarts;
          ASSERT_OK(read_conn.Execute("ABORT"));
          break;
        } else {
          ASSERT_OK(read_conn.Execute("COMMIT"));
          ++num_read_successes;
        }
      }
    });
  }

  // Start update threads
  for (int i = 0; i < kNumUpdateThreads; ++i) {
    thread_holder.AddThreadFunctor([this, i, &stop = thread_holder.stop_flag()] {
      auto update_conn = ASSERT_RESULT(Connect());
      while (!stop.load(std::memory_order_acquire)) {
        for (int key = i; key < kKeys; key += kNumUpdateThreads) {
          ASSERT_OK(update_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ"));
          ASSERT_OK(update_conn.Execute(
              Format("UPDATE t SET value = value + 1 WHERE key = $0", key)));
          ASSERT_OK(update_conn.Execute("COMMIT"));
        }
      }
    });
  }

  // Stop threads after a while
  thread_holder.WaitAndStop(kWaitTime);

  // Count successful reads
  int num_reads = (num_read_restarts.load(std::memory_order_acquire)
                   + num_read_successes.load(std::memory_order_acquire));
  LOG(INFO) << "Successful reads: " << num_read_successes.load(std::memory_order_acquire) << "/"
      << num_reads;
  ASSERT_EQ(num_read_restarts.load(std::memory_order_acquire), 0);
  ASSERT_GT(num_read_successes.load(std::memory_order_acquire), kRequiredNumReads);
}

class PgMiniLargeClockSkewTest : public PgMiniTest {
 public:
  void SetUp() override {
    SetAtomicFlag(250000ULL, &FLAGS_max_clock_skew_usec);
    PgMiniTestBase::SetUp();
  }
};

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(ReadRestartSerializableDeferrable),
          PgMiniLargeClockSkewTest) {
  TestReadRestart(true /* deferrable */);
}

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(ReadRestartSnapshot),
          PgMiniLargeClockSkewTest) {
  TestReadRestart(false /* deferrable */);
}

void PgMiniTest::TestInsertSelectRowLock(IsolationLevel isolation, RowMarkType row_mark) {
  const std::string isolation_str = (
      isolation == IsolationLevel::SNAPSHOT_ISOLATION ? "REPEATABLE READ" : "SERIALIZABLE");
  const std::string row_mark_str = RowMarkTypeToPgsqlString(row_mark);
  constexpr auto kSleepTime = 1s;
  constexpr int kKeys = 3;
  PGConn read_conn = ASSERT_RESULT(Connect());
  PGConn misc_conn = ASSERT_RESULT(Connect());
  PGConn write_conn = ASSERT_RESULT(Connect());

  // Set up table
  ASSERT_OK(misc_conn.Execute("CREATE TABLE t (i INT PRIMARY KEY, j INT)"));
  // TODO: remove this when issue #2857 is fixed.
  std::this_thread::sleep_for(kSleepTime);
  for (int i = 0; i < kKeys; ++i) {
    ASSERT_OK(misc_conn.ExecuteFormat("INSERT INTO t (i, j) VALUES ($0, $0)", i));
  }

  ASSERT_OK(read_conn.ExecuteFormat("BEGIN TRANSACTION ISOLATION LEVEL $0", isolation_str));
  ASSERT_OK(read_conn.Fetch("SELECT '(setting read point)'"));
  ASSERT_OK(write_conn.ExecuteFormat("INSERT INTO t (i, j) VALUES ($0, $0)", kKeys));
  auto result = read_conn.FetchFormat("SELECT * FROM t FOR $0", row_mark_str);
  if (isolation == IsolationLevel::SNAPSHOT_ISOLATION) {
    // TODO: change to ASSERT_OK and expect kKeys rows when issue #2809 is fixed.
    ASSERT_NOK(result);
    ASSERT_TRUE(result.status().IsNetworkError()) << result.status();
    ASSERT_EQ(PgsqlError(result.status()), YBPgErrorCode::YB_PG_T_R_SERIALIZATION_FAILURE)
        << result.status();
    ASSERT_STR_CONTAINS(result.status().ToString(), "Value write after transaction start");
    ASSERT_OK(read_conn.Execute("ABORT"));
  } else {
    ASSERT_OK(result);
    // NOTE: vanilla PostgreSQL expects kKeys rows, but kKeys + 1 rows are expected for Yugabyte.
    ASSERT_EQ(PQntuples(result.get().get()), kKeys + 1);
    ASSERT_OK(read_conn.Execute("COMMIT"));
  }
}

void PgMiniTest::TestDeleteSelectRowLock(IsolationLevel isolation, RowMarkType row_mark) {
  const std::string isolation_str = (
      isolation == IsolationLevel::SNAPSHOT_ISOLATION ? "REPEATABLE READ" : "SERIALIZABLE");
  const std::string row_mark_str = RowMarkTypeToPgsqlString(row_mark);
  constexpr auto kSleepTime = 1s;
  constexpr int kKeys = 3;
  PGConn read_conn = ASSERT_RESULT(Connect());
  PGConn misc_conn = ASSERT_RESULT(Connect());
  PGConn write_conn = ASSERT_RESULT(Connect());

  // Set up table
  ASSERT_OK(misc_conn.Execute("CREATE TABLE t (i INT PRIMARY KEY, j INT)"));
  // TODO: remove this when issue #2857 is fixed.
  std::this_thread::sleep_for(kSleepTime);
  for (int i = 0; i < kKeys; ++i) {
    ASSERT_OK(misc_conn.ExecuteFormat("INSERT INTO t (i, j) VALUES ($0, $0)", i));
  }

  ASSERT_OK(read_conn.ExecuteFormat("BEGIN TRANSACTION ISOLATION LEVEL $0", isolation_str));
  ASSERT_OK(read_conn.Fetch("SELECT '(setting read point)'"));
  ASSERT_OK(write_conn.ExecuteFormat("DELETE FROM t WHERE i = $0", RandomUniformInt(0, kKeys - 1)));
  auto result = read_conn.FetchFormat("SELECT * FROM t FOR $0", row_mark_str);
  if (isolation == IsolationLevel::SNAPSHOT_ISOLATION) {
    ASSERT_NOK(result);
    ASSERT_TRUE(result.status().IsNetworkError()) << result.status();
    ASSERT_EQ(PgsqlError(result.status()), YBPgErrorCode::YB_PG_T_R_SERIALIZATION_FAILURE)
        << result.status();
    ASSERT_STR_CONTAINS(result.status().ToString(), "Value write after transaction start");
    ASSERT_OK(read_conn.Execute("ABORT"));
  } else {
    ASSERT_OK(result);
    // NOTE: vanilla PostgreSQL expects kKeys rows, but kKeys - 1 rows are expected for Yugabyte.
    ASSERT_EQ(PQntuples(result.get().get()), kKeys - 1);
    ASSERT_OK(read_conn.Execute("COMMIT"));
  }
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotInsertForUpdate)) {
  TestInsertSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION, RowMarkType::ROW_MARK_EXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableInsertForUpdate)) {
  TestInsertSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION, RowMarkType::ROW_MARK_EXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotInsertForNoKeyUpdate)) {
  TestInsertSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION,
                          RowMarkType::ROW_MARK_NOKEYEXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableInsertForNoKeyUpdate)) {
  TestInsertSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION,
                          RowMarkType::ROW_MARK_NOKEYEXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotInsertForShare)) {
  TestInsertSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION, RowMarkType::ROW_MARK_SHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableInsertForShare)) {
  TestInsertSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION, RowMarkType::ROW_MARK_SHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotInsertForKeyShare)) {
  TestInsertSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION, RowMarkType::ROW_MARK_KEYSHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableInsertForKeyShare)) {
  TestInsertSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION, RowMarkType::ROW_MARK_KEYSHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotDeleteForUpdate)) {
  TestDeleteSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION, RowMarkType::ROW_MARK_EXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableDeleteForUpdate)) {
  TestDeleteSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION, RowMarkType::ROW_MARK_EXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotDeleteForNoKeyUpdate)) {
  TestDeleteSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION,
                          RowMarkType::ROW_MARK_NOKEYEXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableDeleteForNoKeyUpdate)) {
  TestDeleteSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION,
                          RowMarkType::ROW_MARK_NOKEYEXCLUSIVE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotDeleteForShare)) {
  TestDeleteSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION, RowMarkType::ROW_MARK_SHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableDeleteForShare)) {
  TestDeleteSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION, RowMarkType::ROW_MARK_SHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SnapshotDeleteForKeyShare)) {
  TestDeleteSelectRowLock(IsolationLevel::SNAPSHOT_ISOLATION, RowMarkType::ROW_MARK_KEYSHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(SerializableDeleteForKeyShare)) {
  TestDeleteSelectRowLock(IsolationLevel::SERIALIZABLE_ISOLATION, RowMarkType::ROW_MARK_KEYSHARE);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(SerializableReadOnly)) {
  PGConn read_conn = ASSERT_RESULT(Connect());
  PGConn setup_conn = ASSERT_RESULT(Connect());
  PGConn write_conn = ASSERT_RESULT(Connect());

  // Set up table
  ASSERT_OK(setup_conn.Execute("CREATE TABLE t (i INT)"));
  ASSERT_OK(setup_conn.Execute("INSERT INTO t (i) VALUES (0)"));

  // SERIALIZABLE, READ ONLY should use snapshot isolation
  ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY"));
  ASSERT_OK(write_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ WRITE"));
  ASSERT_OK(write_conn.Execute("UPDATE t SET i = i + 1"));
  ASSERT_OK(read_conn.Fetch("SELECT * FROM t"));
  ASSERT_OK(read_conn.Execute("COMMIT"));
  ASSERT_OK(write_conn.Execute("COMMIT"));

  // READ ONLY, SERIALIZABLE should use snapshot isolation
  ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION READ ONLY, ISOLATION LEVEL SERIALIZABLE"));
  ASSERT_OK(write_conn.Execute("BEGIN TRANSACTION READ WRITE, ISOLATION LEVEL SERIALIZABLE"));
  ASSERT_OK(read_conn.Fetch("SELECT * FROM t"));
  ASSERT_OK(write_conn.Execute("UPDATE t SET i = i + 1"));
  ASSERT_OK(read_conn.Execute("COMMIT"));
  ASSERT_OK(write_conn.Execute("COMMIT"));

  // SHOW for READ ONLY should show serializable
  ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY"));
  Result<PGResultPtr> result = read_conn.Fetch("SHOW transaction_isolation");
  ASSERT_TRUE(result.ok()) << result.status();
  string value = ASSERT_RESULT(GetString(result.get().get(), 0, 0));
  ASSERT_EQ(value, "serializable");
  ASSERT_OK(read_conn.Execute("COMMIT"));

  // SHOW for READ WRITE to READ ONLY should show serializable and read_only
  ASSERT_OK(write_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ WRITE"));
  ASSERT_OK(write_conn.Execute("SET TRANSACTION READ ONLY"));
  result = write_conn.Fetch("SHOW transaction_isolation");
  ASSERT_TRUE(result.ok()) << result.status();
  value = ASSERT_RESULT(GetString(result.get().get(), 0, 0));
  ASSERT_EQ(value, "serializable");
  result = write_conn.Fetch("SHOW transaction_read_only");
  ASSERT_TRUE(result.ok()) << result.status();
  value = ASSERT_RESULT(GetString(result.get().get(), 0, 0));
  ASSERT_EQ(value, "on");
  ASSERT_OK(write_conn.Execute("COMMIT"));

  // SERIALIZABLE, READ ONLY to READ WRITE should not use snapshot isolation
  ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ ONLY"));
  ASSERT_OK(write_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE, READ WRITE"));
  ASSERT_OK(read_conn.Execute("SET TRANSACTION READ WRITE"));
  ASSERT_OK(write_conn.Execute("UPDATE t SET i = i + 1"));
  // The result of the following statement is probabilistic.  If it does not fail now, then it
  // should fail during COMMIT.
  result = read_conn.Fetch("SELECT * FROM t");
  if (result.ok()) {
    ASSERT_OK(read_conn.Execute("COMMIT"));
    Status status = write_conn.Execute("COMMIT");
    ASSERT_NOK(status);
    ASSERT_TRUE(status.IsNetworkError()) << status;
    ASSERT_EQ(PgsqlError(status), YBPgErrorCode::YB_PG_T_R_SERIALIZATION_FAILURE) << status;
  } else {
    ASSERT_TRUE(result.status().IsNetworkError()) << result.status();
    ASSERT_EQ(PgsqlError(result.status()), YBPgErrorCode::YB_PG_T_R_SERIALIZATION_FAILURE)
        << result.status();
    ASSERT_STR_CONTAINS(result.status().ToString(), "Conflicts with higher priority transaction");
  }
}

void AssertAborted(const Status& status) {
  ASSERT_NOK(status);
  ASSERT_STR_CONTAINS(status.ToString(), "aborted");
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(SelectModifySelect)) {
  {
    auto read_conn = ASSERT_RESULT(Connect());
    auto write_conn = ASSERT_RESULT(Connect());

    ASSERT_OK(read_conn.Execute("CREATE TABLE t (i INT)"));
    ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE"));
    ASSERT_RESULT(read_conn.FetchMatrix("SELECT * FROM t", 0, 1));
    ASSERT_OK(write_conn.Execute("INSERT INTO t VALUES (1)"));
    ASSERT_NO_FATALS(AssertAborted(ResultToStatus(read_conn.Fetch("SELECT * FROM t"))));
  }
  {
    auto read_conn = ASSERT_RESULT(Connect());
    auto write_conn = ASSERT_RESULT(Connect());

    ASSERT_OK(read_conn.Execute("CREATE TABLE t2 (i INT PRIMARY KEY)"));
    ASSERT_OK(read_conn.Execute("INSERT INTO t2 VALUES (1)"));

    ASSERT_OK(read_conn.Execute("BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE"));
    ASSERT_RESULT(read_conn.FetchMatrix("SELECT * FROM t2", 1, 1));
    ASSERT_OK(write_conn.Execute("DELETE FROM t2 WHERE i = 1"));
    ASSERT_NO_FATALS(AssertAborted(ResultToStatus(read_conn.Fetch("SELECT * FROM t2"))));
  }
}

class PgMiniSmallWriteBufferTest : public PgMiniTest {
 public:
  void SetUp() override {
    FLAGS_db_write_buffer_size = 256_KB;
    PgMiniTest::SetUp();
  }
};

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BulkCopyWithRestart), PgMiniSmallWriteBufferTest) {
  const std::string kTableName = "key_value";
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.ExecuteFormat(
      "CREATE TABLE $0 (key INTEGER NOT NULL PRIMARY KEY, value VARCHAR)",
      kTableName));

  TestThreadHolder thread_holder;
  constexpr int kTotalBatches = RegularBuildVsSanitizers(50, 5);
  constexpr int kBatchSize = 1000;
  constexpr int kValueSize = 128;

  std::atomic<int> key(0);

  thread_holder.AddThreadFunctor([this, &kTableName, &stop = thread_holder.stop_flag(), &key] {
    SetFlagOnExit set_flag(&stop);
    auto connection = ASSERT_RESULT(Connect());

    auto se = ScopeExit([&key] {
      LOG(INFO) << "Total keys: " << key;
    });

    while (!stop.load(std::memory_order_acquire) && key < kBatchSize * kTotalBatches) {
      ASSERT_OK(connection.CopyBegin(Format("COPY $0 FROM STDIN WITH BINARY", kTableName)));
      for (int j = 0; j != kBatchSize; ++j) {
        connection.CopyStartRow(2);
        connection.CopyPutInt32(++key);
        connection.CopyPutString(RandomHumanReadableString(kValueSize));
      }

      ASSERT_OK(connection.CopyEnd());
    }
  });

  thread_holder.AddThread(RestartsThread(cluster_.get(), 5s, &thread_holder.stop_flag()));

  thread_holder.WaitAndStop(120s); // Actually will stop when enough batches were copied

  ASSERT_EQ(key.load(std::memory_order_relaxed), kTotalBatches * kBatchSize);

  LOG(INFO) << "Restarting cluster";
  ASSERT_OK(cluster_->RestartSync());

  ASSERT_OK(WaitFor([this, &conn, &key, &kTableName] {
    auto intents_count = CountIntents(cluster_.get());
    LOG(INFO) << "Intents count: " << intents_count;

    if (intents_count <= 5000) {
      return true;
    }

    // We cleanup only transactions that were completely aborted/applied before last replication
    // happens.
    // So we could get into situation when intents of the last transactions are not cleaned.
    // To avoid such scenario in this test we write one more row to allow cleanup.
    EXPECT_OK(conn.ExecuteFormat(
        "INSERT INTO $0 VALUES ($1, '$2')", kTableName, ++key,
        RandomHumanReadableString(kValueSize)));

    return false;
  }, 5s, "Intents cleanup", 200ms));
}

void PgMiniTest::TestForeignKey(IsolationLevel isolation_level) {
  const std::string kDataTable = "data";
  const std::string kReferenceTable = "reference";
  constexpr int kRows = 10;
  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.ExecuteFormat(
      "CREATE TABLE $0 (id int NOT NULL, name VARCHAR, PRIMARY KEY (id))",
      kReferenceTable));
  ASSERT_OK(conn.ExecuteFormat(
      "CREATE TABLE $0 (ref_id INTEGER, data_id INTEGER, name VARCHAR, "
          "PRIMARY KEY (ref_id, data_id))",
      kDataTable));
  ASSERT_OK(conn.ExecuteFormat(
      "ALTER TABLE $0 ADD CONSTRAINT fk FOREIGN KEY(ref_id) REFERENCES $1(id) "
          "ON DELETE CASCADE",
      kDataTable, kReferenceTable));

  ASSERT_OK(conn.ExecuteFormat(
      "INSERT INTO $0 VALUES ($1, 'reference_$1')", kReferenceTable, 1));

  for (int i = 1; i <= kRows; ++i) {
    ASSERT_OK(conn.StartTransaction(isolation_level));
    ASSERT_OK(conn.ExecuteFormat(
        "INSERT INTO $0 VALUES ($1, $2, 'data_$2')", kDataTable, 1, i));
    ASSERT_OK(conn.CommitTransaction());
  }

  ASSERT_OK(WaitFor([this] {
    return CountIntents(cluster_.get()) == 0;
  }, 15s, "Intents cleanup"));
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(ForeignKeySerializable)) {
  TestForeignKey(IsolationLevel::SERIALIZABLE_ISOLATION);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(ForeignKeySnapshot)) {
  TestForeignKey(IsolationLevel::SNAPSHOT_ISOLATION);
}

class PgMiniTestNoTxnRetry : public PgMiniTest {
 protected:
  void BeforePgProcessStart() override {
    FLAGS_ysql_sleep_before_retry_on_txn_conflict = false;
  }
};

template<IsolationLevel level>
class PgMiniTestTxnHelper : public PgMiniTestNoTxnRetry {
 protected:

  // Check possibility of updating column in case row is referenced by foreign key from another txn.
  void TestReferencedTableUpdate() {
    auto conn = ASSERT_RESULT(Connect());
    ASSERT_OK(conn.Execute("CREATE TABLE pktable (k INT PRIMARY KEY, v INT)"));
    ASSERT_OK(conn.Execute("CREATE TABLE fktable (k INT PRIMARY KEY, fk_p INT, v INT, "
                           "FOREIGN KEY(fk_p) REFERENCES pktable(k))"));
    ASSERT_OK(conn.Execute("INSERT INTO pktable VALUES(1, 2)"));
    auto extra_conn = ASSERT_RESULT(Connect());
    ASSERT_OK(StartTxn(&extra_conn));
    ASSERT_OK(extra_conn.Execute("INSERT INTO fktable VALUES(1, 1, 2)"));
    ASSERT_OK(conn.Execute("UPDATE pktable SET v = 20 WHERE k = 1"));
    // extra_conn created strong read intent on (1, liveness) due to foreign key check.
    // As a result weak read intent is created for (1).
    // conn UPDATE created strong write intent on (1, v).
    // As a result weak write intent is created for (1).
    // Weak read + weak write on (1) has no conflicts.
    ASSERT_OK(extra_conn.Execute("COMMIT"));
    auto res = ASSERT_RESULT(
        conn.template FetchValue<int64_t>("SELECT COUNT(*) FROM pktable WHERE v = 20"));
    ASSERT_EQ(res, 1);
  }

  // Check that `FOR KEY SHARE` prevents rows from being deleted even in case not all key
  // components are specified.
  void TestRowKeyShareLock() {
    auto conn = ASSERT_RESULT(SetHighPriTxn(Connect()));
    auto extra_conn = ASSERT_RESULT(SetLowPriTxn(Connect()));

    ASSERT_OK(conn.Execute(
        "CREATE TABLE t (h INT, r1 INT, r2 INT, v INT, PRIMARY KEY(h, r1, r2))"));
    ASSERT_OK(conn.Execute(
        "INSERT INTO t VALUES (1, 2, 3, 4), (1, 2, 30, 40), (1, 3, 4, 5), (10, 2, 3, 4)"));
    ASSERT_OK(StartTxn(&conn));
    // SELECT FOR KEY SHARE locks all sub doc keys of (1, 2)
    // as not all key components are specified.
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE h = 1 AND r1 = 2 FOR KEY SHARE"));

    ASSERT_NOK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 2 AND r2 = 3"));
    ASSERT_NOK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 2 AND r2 = 30"));
    // Doc key (1, 3, 4) in not locked.
    ASSERT_OK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 3 AND r2 = 4"));

    ASSERT_OK(conn.Execute("COMMIT"));
    ASSERT_OK(StartTxn(&conn));
    // SELECT FOR KEY SHARE locks all sub doc keys of () as not all key components are specified.
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE r2 = 2 FOR KEY SHARE"));

    ASSERT_NOK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 2 AND r2 = 3"));
    ASSERT_NOK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 10 AND r1 = 2 AND r2 = 3"));

    ASSERT_OK(conn.Execute("COMMIT"));
    ASSERT_OK(StartTxn(&conn));
    // SELECT FOR KEY SHARE locks all sub doc keys of (1) as not all key components are specified.
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE h = 1 AND r2 = 2 FOR KEY SHARE"));

    ASSERT_NOK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 2 AND r2 = 3"));
    // Doc key  (10, 2, 3) in not locked.
    ASSERT_OK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 10 AND r1 = 2 AND r2 = 3"));

    ASSERT_OK(conn.Execute("COMMIT"));
    ASSERT_OK(StartTxn(&conn));
    // SELECT FOR KEY SHARE locks one specific row with doc key (1, 2, 3) only.
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE h = 1 AND r1 = 2 AND r2 = 3 FOR KEY SHARE"));

    ASSERT_NOK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 2 AND r2 = 3"));
    ASSERT_OK(ExecuteInTxn(&extra_conn, "DELETE FROM t WHERE h = 1 AND r1 = 2 AND r2 = 30"));

    ASSERT_OK(conn.Execute("COMMIT"));

    auto res = ASSERT_RESULT(conn.template FetchValue<int64_t>("SELECT COUNT(*) FROM t"));
    ASSERT_EQ(res, 1);
  }

  // Check conflicts according to the following matrix (X - conflict, O - no conflict):
  //                   | FOR KEY SHARE | FOR SHARE | FOR NO KEY UPDATE | FOR UPDATE
  // ------------------+---------------+-----------+-------------------+-----------
  // FOR KEY SHARE     |       O       |     O     |         O         |     X
  // FOR SHARE         |       O       |     O     |         X         |     X
  // FOR NO KEY UPDATE |       O       |     X     |         X         |     X
  // FOR UPDATE        |       X       |     X     |         X         |     X
  void TestRowLockConflictMatrix() {
    auto conn = ASSERT_RESULT(SetHighPriTxn(Connect()));
    auto extra_conn = ASSERT_RESULT(SetLowPriTxn(Connect()));

    ASSERT_OK(conn.Execute("CREATE TABLE t (k INT PRIMARY KEY, v INT)"));
    ASSERT_OK(conn.Execute("INSERT INTO t VALUES (1, 1)"));

    ASSERT_OK(StartTxn(&conn));
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE k = 1 FOR UPDATE"));

    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR UPDATE"));
    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR NO KEY UPDATE"));
    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR SHARE"));
    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR KEY SHARE"));

    ASSERT_OK(conn.Execute("COMMIT"));
    ASSERT_OK(StartTxn(&conn));
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE k = 1 FOR NO KEY UPDATE"));

    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR UPDATE"));
    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR NO KEY UPDATE"));
    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR SHARE"));
    ASSERT_RESULT(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR KEY SHARE"));

    ASSERT_OK(conn.Execute("COMMIT"));
    ASSERT_OK(StartTxn(&conn));
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE k = 1 FOR SHARE"));

    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR UPDATE"));
    ASSERT_NOK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR NO KEY UPDATE"));
    ASSERT_RESULT(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR SHARE"));
    ASSERT_RESULT(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR KEY SHARE"));

    ASSERT_OK(conn.Execute("COMMIT"));
    ASSERT_OK(StartTxn(&conn));
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE k = 1 FOR KEY SHARE"));

    ASSERT_RESULT(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR NO KEY UPDATE"));
    ASSERT_RESULT(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR SHARE"));
    ASSERT_RESULT(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR KEY SHARE"));

    ASSERT_OK(conn.Execute("COMMIT"));

    // Check FOR KEY SHARE + FOR UPDATE conflict separately
    // as FOR KEY SHARE uses regular and FOR UPDATE uses high txn priority.
    ASSERT_OK(StartTxn(&conn));
    ASSERT_RESULT(conn.Fetch("SELECT * FROM t WHERE k = 1 FOR KEY SHARE"));

    ASSERT_OK(FetchInTxn(&extra_conn, "SELECT * FROM t WHERE k = 1 FOR UPDATE"));

    ASSERT_NOK(conn.Execute("COMMIT"));
  }

  static Result<PGConn> SetHighPriTxn(Result<PGConn> connection) {
    return Execute(std::move(connection), "SET yb_transaction_priority_lower_bound=0.5");
  }

  static Result<PGConn> SetLowPriTxn(Result<PGConn> connection) {
    return Execute(std::move(connection), "SET yb_transaction_priority_upper_bound=0.4");
  }

  static Result<PGConn> Execute(Result<PGConn> connection, const std::string& query) {
    if (connection.ok()) {
      RETURN_NOT_OK((*connection).Execute(query));
    }
    return connection;
  }

  static CHECKED_STATUS StartTxn(PGConn* connection) {
    return TxnHelper<level>::StartTxn(connection);
  }

  static CHECKED_STATUS ExecuteInTxn(PGConn* connection, const std::string& query) {
    return TxnHelper<level>::ExecuteInTxn(connection, query);
  }

  static Result<PGResultPtr> FetchInTxn(PGConn* connection, const std::string& query) {
    return TxnHelper<level>::FetchInTxn(connection, query);
  }
};

class PgMiniTestTxnHelperSerializable
    : public PgMiniTestTxnHelper<IsolationLevel::SERIALIZABLE_ISOLATION> {
 protected:
  // Check two SERIALIZABLE txns has no conflict in case of updating same column in same row.
  void TestSameColumnUpdate() {
    auto conn = ASSERT_RESULT(SetHighPriTxn(Connect()));
    auto extra_conn = ASSERT_RESULT(SetLowPriTxn(Connect()));

    ASSERT_OK(conn.Execute("CREATE TABLE t (k INT PRIMARY KEY, v1 INT, v2 INT)"));
    ASSERT_OK(conn.Execute("INSERT INTO t VALUES(1, 2, 3)"));

    ASSERT_OK(StartTxn(&conn));
    ASSERT_OK(conn.Execute("UPDATE t SET v1 = 20 WHERE k = 1"));

    ASSERT_OK(ExecuteInTxn(&extra_conn, "UPDATE t SET v1 = 40 WHERE k = 1"));

    ASSERT_OK(conn.Execute("COMMIT"));

    auto res = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT COUNT(*) FROM t WHERE v1 = 20"));
    ASSERT_EQ(res, 1);

    ASSERT_OK(StartTxn(&conn));
    // Next statement will lock whole row for updates due to expression
    ASSERT_OK(conn.Execute("UPDATE t SET v2 = v2 * 2 WHERE k = 1"));

    ASSERT_NOK(ExecuteInTxn(&extra_conn, "UPDATE t SET v2 = 10 WHERE k = 1"));
    ASSERT_NOK(ExecuteInTxn(&extra_conn, "UPDATE t SET v1 = 10 WHERE k = 1"));

    ASSERT_OK(conn.Execute("COMMIT"));

    res = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT COUNT(*) FROM t WHERE v2 = 6"));
    ASSERT_EQ(res, 1);
  }
};

class PgMiniTestTxnHelperSnapshot
    : public PgMiniTestTxnHelper<IsolationLevel::SNAPSHOT_ISOLATION> {
 protected:
  // Check two SNAPSHOT txns has a conflict in case of updating same column in same row.
  void TestSameColumnUpdate() {
    auto conn = ASSERT_RESULT(SetHighPriTxn(Connect()));
    auto extra_conn = ASSERT_RESULT(SetLowPriTxn(Connect()));

    ASSERT_OK(conn.Execute("CREATE TABLE t (k INT PRIMARY KEY, v INT)"));
    ASSERT_OK(conn.Execute("INSERT INTO t VALUES(1, 2)"));

    ASSERT_OK(StartTxn(&conn));
    ASSERT_OK(conn.Execute("UPDATE t SET v = 20 WHERE k = 1"));

    ASSERT_NOK(ExecuteInTxn(&extra_conn, "UPDATE t SET v = 40 WHERE k = 1"));

    ASSERT_OK(conn.Execute("COMMIT"));

    const auto res = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT COUNT(*) FROM t WHERE v = 20"));
    ASSERT_EQ(res, 1);
  }
};

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(ReferencedTableUpdateSerializable),
          PgMiniTestTxnHelperSerializable) {
  TestReferencedTableUpdate();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(ReferencedTableUpdateSnapshot),
          PgMiniTestTxnHelperSnapshot) {
  TestReferencedTableUpdate();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(RowKeyShareLockSerializable),
          PgMiniTestTxnHelperSerializable) {
  TestRowKeyShareLock();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(RowKeyShareLockSnapshot),
          PgMiniTestTxnHelperSnapshot) {
  TestRowKeyShareLock();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(RowLockConflictMatrixSerializable),
          PgMiniTestTxnHelperSerializable) {
  TestRowLockConflictMatrix();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(RowLockConflictMatrixSnapshot),
          PgMiniTestTxnHelperSnapshot) {
  TestRowLockConflictMatrix();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(SameColumnUpdateSerializable),
          PgMiniTestTxnHelperSerializable) {
  TestSameColumnUpdate();
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(SameColumnUpdateSnapshot),
          PgMiniTestTxnHelperSnapshot) {
  TestSameColumnUpdate();
}

// ------------------------------------------------------------------------------------------------
// A test performing manual transaction control on system tables.
// ------------------------------------------------------------------------------------------------

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(SystemTableTxnTest), PgMiniTestNoTxnRetry) {

  // Resolving conflicts between transactions on a system table.
  //
  // postgres=# \d pg_ts_dict;
  //
  //              Table "pg_catalog.pg_ts_dict"
  //      Column     | Type | Collation | Nullable | Default
  // ----------------+------+-----------+----------+---------
  //  dictname       | name |           | not null |
  //  dictnamespace  | oid  |           | not null |
  //  dictowner      | oid  |           | not null |
  //  dicttemplate   | oid  |           | not null |
  //  dictinitoption | text |           |          |
  // Indexes:
  //     "pg_ts_dict_oid_index" PRIMARY KEY, lsm (oid)
  //     "pg_ts_dict_dictname_index" UNIQUE, lsm (dictname, dictnamespace)

  auto conn1 = ASSERT_RESULT(Connect());
  auto conn2 = ASSERT_RESULT(Connect());
  ASSERT_OK(conn1.Execute("SET yb_non_ddl_txn_for_sys_tables_allowed=1"));
  ASSERT_OK(conn2.Execute("SET yb_non_ddl_txn_for_sys_tables_allowed=1"));

  size_t commit1_fail_count = 0;
  size_t commit2_fail_count = 0;
  size_t insert2_fail_count = 0;

  const auto kStartTxnStatementStr = "START TRANSACTION ISOLATION LEVEL REPEATABLE READ";
  const int iterations = 48;
  for (int i = 1; i <= iterations; ++i) {
    std::string dictname = Format("contendedkey$0", i);
    const int dictnamespace = i;
    ASSERT_OK(conn1.Execute(kStartTxnStatementStr));
    ASSERT_OK(conn2.Execute(kStartTxnStatementStr));

    // Insert a row in each transaction. The first insert should always succeed.
    ASSERT_OK(conn1.Execute(
        Format("INSERT INTO pg_ts_dict VALUES ('$0', $1, 1, 2, 'b')", dictname, dictnamespace)));
    Status insert_status2 = conn2.Execute(
        Format("INSERT INTO pg_ts_dict VALUES ('$0', $1, 3, 4, 'c')", dictname, dictnamespace));
    if (!insert_status2.ok()) {
      LOG(INFO) << "MUST BE A CONFLICT: Insert failed: " << insert_status2;
      insert2_fail_count++;
    }

    Status commit_status1;
    Status commit_status2;
    if (RandomUniformBool()) {
      commit_status1 = conn1.Execute("COMMIT");
      commit_status2 = conn2.Execute("COMMIT");
    } else {
      commit_status2 = conn2.Execute("COMMIT");
      commit_status1 = conn1.Execute("COMMIT");
    }
    if (!commit_status1.ok()) {
      commit1_fail_count++;
    }
    if (!commit_status2.ok()) {
      commit2_fail_count++;
    }

    auto get_commit_statuses_str = [&commit_status1, &commit_status2]() {
      return Format("commit_status1=$0, commit_status2=$1", commit_status1, commit_status2);
    };

    bool succeeded1 = commit_status1.ok();
    bool succeeded2 = insert_status2.ok() && commit_status2.ok();

    ASSERT_TRUE(!succeeded1 || !succeeded2)
        << "Both transactions can't commit. " << get_commit_statuses_str();
    ASSERT_TRUE(succeeded1 || succeeded2)
        << "We expect one of the two transactions to succeed. " << get_commit_statuses_str();
    if (!commit_status1.ok()) {
      ASSERT_OK(conn1.Execute("ROLLBACK"));
    }
    if (!commit_status2.ok()) {
      ASSERT_OK(conn2.Execute("ROLLBACK"));
    }

    if (RandomUniformBool()) {
      std::swap(conn1, conn2);
    }
  }
  LOG(INFO) << "Test stats: "
            << EXPR_VALUE_FOR_LOG(commit1_fail_count) << ", "
            << EXPR_VALUE_FOR_LOG(insert2_fail_count) << ", "
            << EXPR_VALUE_FOR_LOG(commit2_fail_count);
  ASSERT_GE(commit1_fail_count, iterations / 4);
  ASSERT_GE(insert2_fail_count, iterations / 4);
  ASSERT_EQ(commit2_fail_count, 0);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(DropDBUpdateSysTablet)) {
  const std::string kDatabaseName = "testdb";
  master::CatalogManager *catalog_manager =
      ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->master()->catalog_manager();
  PGConn conn = ASSERT_RESULT(Connect());
  scoped_refptr<master::TabletInfo> sys_tablet;
  std::array<int, 4> num_tables;

  {
    master::CatalogManager::SharedLock catalog_lock(catalog_manager->mutex_);
    sys_tablet = catalog_manager->tablet_map_->find(master::kSysCatalogTabletId)->second;
  }
  {
    auto tablet_lock = sys_tablet->LockForWrite();
    num_tables[0] = tablet_lock->pb.table_ids_size();
  }
  ASSERT_OK(conn.ExecuteFormat("CREATE DATABASE $0", kDatabaseName));
  {
    auto tablet_lock = sys_tablet->LockForWrite();
    num_tables[1] = tablet_lock->pb.table_ids_size();
  }
  ASSERT_OK(conn.ExecuteFormat("DROP DATABASE $0", kDatabaseName));
  {
    auto tablet_lock = sys_tablet->LockForWrite();
    num_tables[2] = tablet_lock->pb.table_ids_size();
  }
  // Make sure that the system catalog tablet table_ids is persisted.
  ASSERT_OK(cluster_->RestartSync());
  {
    // Refresh stale local variables after RestartSync.
    catalog_manager = ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->master()->catalog_manager();
    master::CatalogManager::SharedLock catalog_lock(catalog_manager->mutex_);
    sys_tablet = catalog_manager->tablet_map_->find(master::kSysCatalogTabletId)->second;
  }
  {
    auto tablet_lock = sys_tablet->LockForWrite();
    num_tables[3] = tablet_lock->pb.table_ids_size();
  }
  ASSERT_LT(num_tables[0], num_tables[1]);
  ASSERT_EQ(num_tables[0], num_tables[2]);
  ASSERT_EQ(num_tables[0], num_tables[3]);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(DropDBMarkDeleted)) {
  const std::string kDatabaseName = "testdb";
  constexpr auto kSleepTime = 500ms;
  constexpr int kMaxNumSleeps = 20;
  master::CatalogManager *catalog_manager =
      ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->master()->catalog_manager();
  PGConn conn = ASSERT_RESULT(Connect());

  ASSERT_FALSE(catalog_manager->AreTablesDeleting());
  ASSERT_OK(conn.ExecuteFormat("CREATE DATABASE $0", kDatabaseName));
  ASSERT_OK(conn.ExecuteFormat("DROP DATABASE $0", kDatabaseName));
  // System tables should be deleting then deleted.
  int num_sleeps = 0;
  while (catalog_manager->AreTablesDeleting() && (num_sleeps++ != kMaxNumSleeps)) {
    LOG(INFO) << "Tables are deleting...";
    std::this_thread::sleep_for(kSleepTime);
  }
  ASSERT_FALSE(catalog_manager->AreTablesDeleting()) << "Tables should have finished deleting";
  // Make sure that the table deletions are persisted.
  ASSERT_OK(cluster_->RestartSync());
  // Refresh stale local variable after RestartSync.
  catalog_manager = ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->master()->catalog_manager();
  ASSERT_FALSE(catalog_manager->AreTablesDeleting());
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(DropDBWithTables)) {
  const std::string kDatabaseName = "testdb";
  const std::string kTablePrefix = "testt";
  constexpr auto kSleepTime = 500ms;
  constexpr int kMaxNumSleeps = 20;
  int num_tables_before, num_tables_after;
  master::CatalogManager *catalog_manager =
      ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->master()->catalog_manager();
  PGConn conn = ASSERT_RESULT(Connect());
  scoped_refptr<master::TabletInfo> sys_tablet;

  {
    master::CatalogManager::SharedLock catalog_lock(catalog_manager->mutex_);
    sys_tablet = catalog_manager->tablet_map_->find(master::kSysCatalogTabletId)->second;
  }
  {
    auto tablet_lock = sys_tablet->LockForWrite();
    num_tables_before = tablet_lock->pb.table_ids_size();
  }
  ASSERT_OK(conn.ExecuteFormat("CREATE DATABASE $0", kDatabaseName));
  {
    PGConn conn_new = ASSERT_RESULT(ConnectToDB(kDatabaseName));
    for (int i = 0; i < 10; ++i) {
      ASSERT_OK(conn_new.ExecuteFormat("CREATE TABLE $0$1 (i int)", kTablePrefix, i));
    }
    ASSERT_OK(conn_new.ExecuteFormat("INSERT INTO $0$1 (i) VALUES (1), (2), (3)", kTablePrefix, 5));
  }
  ASSERT_OK(conn.ExecuteFormat("DROP DATABASE $0", kDatabaseName));
  // User and system tables should be deleting then deleted.
  int num_sleeps = 0;
  while (catalog_manager->AreTablesDeleting() && (num_sleeps++ != kMaxNumSleeps)) {
    LOG(INFO) << "Tables are deleting...";
    std::this_thread::sleep_for(kSleepTime);
  }
  ASSERT_FALSE(catalog_manager->AreTablesDeleting()) << "Tables should have finished deleting";
  // Make sure that the table deletions are persisted.
  ASSERT_OK(cluster_->RestartSync());
  {
    // Refresh stale local variables after RestartSync.
    catalog_manager = ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->master()->catalog_manager();
    master::CatalogManager::SharedLock catalog_lock(catalog_manager->mutex_);
    sys_tablet = catalog_manager->tablet_map_->find(master::kSysCatalogTabletId)->second;
  }
  ASSERT_FALSE(catalog_manager->AreTablesDeleting());
  {
    auto tablet_lock = sys_tablet->LockForWrite();
    num_tables_after = tablet_lock->pb.table_ids_size();
  }
  ASSERT_EQ(num_tables_before, num_tables_after);
}

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(DropAllTablesInColocatedDB),
          PgMiniMasterFailoverTest) {
  const std::string kDatabaseName = "testdb";
  // Create a colocated DB, create some tables, delete all of them.
  {
    PGConn conn = ASSERT_RESULT(Connect());
    ASSERT_OK(conn.ExecuteFormat("CREATE DATABASE $0 with colocated=true", kDatabaseName));
    {
      PGConn conn_new = ASSERT_RESULT(ConnectToDB(kDatabaseName));
      ASSERT_OK(conn_new.Execute("CREATE TABLE foo (i int)"));
      ASSERT_OK(conn_new.Execute("DROP TABLE foo"));
    }
  }
  // Failover to a new master.
  LOG(INFO) << "Failover to new Master";
  auto old_master = ASSERT_RESULT(cluster_->GetLeaderMiniMaster());
  ASSERT_RESULT(cluster_->GetLeaderMiniMaster())->Shutdown();
  auto new_master = ASSERT_RESULT(cluster_->GetLeaderMiniMaster());
  ASSERT_NE(nullptr, new_master);
  ASSERT_NE(old_master, new_master);
  // Wait for all the TabletServers to report in, so we can run CREATE TABLE with working replicas.
  ASSERT_OK(cluster_->WaitForAllTabletServers());
  // Ensure we can still access the colocated DB on restart.
  {
    PGConn conn_new = ASSERT_RESULT(ConnectToDB(kDatabaseName));
    ASSERT_OK(conn_new.Execute("CREATE TABLE foo (i int)"));
  }
}


TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigSelect)) {
  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.Execute("CREATE TABLE t (key INT PRIMARY KEY, value TEXT)"));

  constexpr size_t kRows = 400;
  constexpr size_t kValueSize = RegularBuildVsSanitizers(256_KB, 4_KB);

  for (size_t i = 0; i != kRows; ++i) {
    ASSERT_OK(conn.ExecuteFormat(
        "INSERT INTO t VALUES ($0, '$1')", i, RandomHumanReadableString(kValueSize)));
  }

  auto start = MonoTime::Now();
  auto res = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT COUNT(DISTINCT(value)) FROM t"));
  auto finish = MonoTime::Now();
  LOG(INFO) << "Time: " << finish - start;
  ASSERT_EQ(res, kRows);
}

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(ManyRowsInsert), PgMiniSingleTServerTest) {
  constexpr int kRows = 100000;
  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.Execute("CREATE TABLE t (key INT PRIMARY KEY)"));

  auto start = MonoTime::Now();
  ASSERT_OK(conn.ExecuteFormat("INSERT INTO t SELECT generate_series(1, $0)", kRows));
  auto finish = MonoTime::Now();
  LOG(INFO) << "Time: " << finish - start;
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(MoveMaster)) {
  ShutdownAllMasters(cluster_.get());
  cluster_->mini_master(0)->set_pass_master_addresses(false);
  ASSERT_OK(StartAllMasters(cluster_.get()));

  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(WaitFor([&conn] {
    auto status = conn.Execute("CREATE TABLE t (key INT PRIMARY KEY)");
    WARN_NOT_OK(status, "Failed to create table");
    return status.ok();
  }, 15s, "Create table"));
}

class PgMiniBigPrefetchTest : public PgMiniSingleTServerTest {
 protected:
  void SetUp() override {
    FLAGS_ysql_prefetch_limit = 20000000;
    PgMiniTest::SetUp();
  }

  void Run(int rows, int block_size, int reads, bool compact = false) {
    auto conn = ASSERT_RESULT(Connect());

    ASSERT_OK(conn.Execute("CREATE TABLE t (a int PRIMARY KEY) SPLIT INTO 1 TABLETS"));
    auto last_row = 0;
    while (last_row < rows) {
      auto first_row = last_row + 1;
      last_row = std::min(rows, last_row + block_size);
      ASSERT_OK(conn.ExecuteFormat(
          "INSERT INTO t SELECT generate_series($0, $1)", first_row, last_row));
    }

    auto peers = ListTabletPeers(cluster_.get(), ListPeersFilter::kAll);
    for (const auto& peer : peers) {
      auto tp = peer->tablet()->transaction_participant();
      if (tp) {
        LOG(INFO) << peer->LogPrefix() << "Intents: " << tp->TEST_CountIntents().first;
      }
    }

    if (compact) {
      FlushAndCompactTablets();
    }

    LOG(INFO) << "Perform read";

    if (VLOG_IS_ON(4)) {
      google::SetVLOGLevel("intent_aware_iterator", 4);
      google::SetVLOGLevel("docdb_rocksdb_util", 4);
      google::SetVLOGLevel("docdb", 4);
    }

    for (int i = 0; i != reads; ++i) {
      auto start = MonoTime::Now();
      auto fetched_rows = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT count(*) FROM t"));
      auto finish = MonoTime::Now();
      ASSERT_EQ(rows, fetched_rows);
      LOG(INFO) << i << ") Full Time: " << finish - start;
    }
  }
};

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigRead), PgMiniBigPrefetchTest) {
  constexpr int kRows = RegularBuildVsSanitizers(1000000, 10000);
  constexpr int kBlockSize = 1000;
  constexpr int kReads = 3;

  Run(kRows, kBlockSize, kReads);
}

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigReadWithCompaction), PgMiniBigPrefetchTest) {
  constexpr int kRows = RegularBuildVsSanitizers(1000000, 10000);
  constexpr int kBlockSize = 1000;
  constexpr int kReads = 3;

  Run(kRows, kBlockSize, kReads, /* compact= */ true);
}

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(SmallRead), PgMiniBigPrefetchTest) {
  constexpr int kRows = 10;
  constexpr int kBlockSize = kRows;
  constexpr int kReads = 1;

  Run(kRows, kBlockSize, kReads);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(DDLWithRestart)) {
  SetAtomicFlag(1.0, &FLAGS_TEST_transaction_ignore_applying_probability_in_tests);
  FLAGS_TEST_force_master_leader_resolution = true;

  auto conn = ASSERT_RESULT(Connect());

  ASSERT_OK(conn.StartTransaction(IsolationLevel::SERIALIZABLE_ISOLATION));
  ASSERT_OK(conn.Execute("CREATE TABLE t (a int PRIMARY KEY)"));
  ASSERT_OK(conn.CommitTransaction());

  ShutdownAllMasters(cluster_.get());

  LOG(INFO) << "Start masters";
  ASSERT_OK(StartAllMasters(cluster_.get()));

  auto res = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT COUNT(*) FROM t"));
  ASSERT_EQ(res, 0);
}

class PgMiniRocksDbIteratorLoggingTest : public PgMiniSingleTServerTest {
 public:
  struct IteratorLoggingTestConfig {
    int num_non_pk_columns;
    int num_rows;
    int num_overwrites;
    int first_row_to_scan;
    int last_row_to_scan;
  };

  void RunIteratorLoggingTest(const IteratorLoggingTestConfig& config) {
    auto conn = ASSERT_RESULT(Connect());

    std::string non_pk_columns_schema;
    std::string non_pk_column_names;
    for (int i = 0; i < config.num_non_pk_columns; ++i) {
      non_pk_columns_schema += Format(", $0 TEXT", GetNonPkColName(i));
      non_pk_column_names += Format(", $0", GetNonPkColName(i));
    }
    ASSERT_OK(conn.ExecuteFormat("CREATE TABLE t (pk TEXT, PRIMARY KEY (pk ASC)$0)",
                                 non_pk_columns_schema));
    // Delete and overwrite every row multiple times.
    for (int overwrite_index = 0; overwrite_index < config.num_overwrites; ++overwrite_index) {
      for (int row_index = 0; row_index < config.num_rows; ++row_index) {
        string non_pk_values;
        for (int non_pk_col_index = 0;
             non_pk_col_index < config.num_non_pk_columns;
             ++non_pk_col_index) {
          non_pk_values += Format(", '$0'", GetNonPkColValue(
              non_pk_col_index, row_index, overwrite_index));
        }

        const auto pk_value = GetPkForRow(row_index);
        ASSERT_OK(conn.ExecuteFormat(
            "INSERT INTO t(pk$0) VALUES('$1'$2)", non_pk_column_names, pk_value, non_pk_values));
        if (overwrite_index != config.num_overwrites - 1) {
          ASSERT_OK(conn.ExecuteFormat("DELETE FROM t WHERE pk = '$0'", pk_value));
        }
      }
    }
    const auto first_pk_to_scan = GetPkForRow(config.first_row_to_scan);
    const auto last_pk_to_scan = GetPkForRow(config.last_row_to_scan);
    auto count_stmt_str = Format(
        "SELECT COUNT(*) FROM t WHERE pk >= '$0' AND pk <= '$1'",
        first_pk_to_scan,
        last_pk_to_scan);
    // Do the same scan twice, and only turn on iterator logging on the second scan.
    // This way we won't be logging system table operations needed to fetch PostgreSQL metadata.
    for (bool is_warmup : {true, false}) {
      if (!is_warmup) {
        SetAtomicFlag(true, &FLAGS_rocksdb_use_logging_iterator);
      }
      auto count_result = ASSERT_RESULT(conn.Fetch(count_stmt_str));
      ASSERT_EQ(PQntuples(count_result.get()), 1);

      auto actual_num_rows = ASSERT_RESULT(GetInt64(count_result.get(), 0, 0));
      const int expected_num_rows = config.last_row_to_scan - config.first_row_to_scan + 1;
      ASSERT_EQ(expected_num_rows, actual_num_rows);
    }
    SetAtomicFlag(false, &FLAGS_rocksdb_use_logging_iterator);
  }

 private:
  std::string GetNonPkColName(int non_pk_col_index) {
    return Format("non_pk_col$0", non_pk_col_index);
  }

  std::string GetPkForRow(int row_index) {
    return Format("PrimaryKeyForRow$0", row_index);
  }

  std::string GetNonPkColValue(int non_pk_col_index, int row_index, int overwrite_index) {
    return Format("NonPkCol$0ValueForRow$1Overwrite$2",
                  non_pk_col_index, row_index, overwrite_index);
  }
};

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(IteratorLogPkOnly), PgMiniRocksDbIteratorLoggingTest) {
  RunIteratorLoggingTest({
    .num_non_pk_columns = 0,
    .num_rows = 5,
    .num_overwrites = 100,
    .first_row_to_scan = 1,  // 0-based
    .last_row_to_scan = 3,
  });
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(IteratorLogTwoNonPkCols), PgMiniRocksDbIteratorLoggingTest) {
  RunIteratorLoggingTest({
    .num_non_pk_columns = 2,
    .num_rows = 5,
    .num_overwrites = 100,
    .first_row_to_scan = 1,  // 0-based
    .last_row_to_scan = 3,
  });
}

// ------------------------------------------------------------------------------------------------
// Backward scan on an index
// ------------------------------------------------------------------------------------------------

class PgMiniBackwardIndexScanTest : public PgMiniSingleTServerTest {
 protected:
  void BackwardIndexScanTest(bool uncommitted_intents) {
    auto conn = ASSERT_RESULT(Connect());

    ASSERT_OK(conn.Execute(R"#(
        create table events_backwardscan (

          log       text not null,
          src       text not null,
          inserted  timestamp(3) without time zone not null,
          created   timestamp(3) without time zone not null,
          data      jsonb not null,

          primary key (log, src, created)
        );
      )#"));
    ASSERT_OK(conn.Execute("create index on events_backwardscan (inserted asc);"));

    for (int day = 1; day <= 31; ++day) {
      ASSERT_OK(conn.ExecuteFormat(R"#(
          insert into events_backwardscan

          select
            'log',
            'src',
            t,
            t,
            '{}'

          from generate_series(
            timestamp '2020-01-$0 00:00:00',
            timestamp '2020-01-$0 23:59:59',
            interval  '1 minute'
          )

          as t(day);
      )#", day));
    }

    boost::optional<PGConn> uncommitted_intents_conn;
    if (uncommitted_intents) {
      uncommitted_intents_conn = ASSERT_RESULT(Connect());
      ASSERT_OK(uncommitted_intents_conn->Execute("BEGIN"));
      auto ts = "1970-01-01 00:00:00";
      ASSERT_OK(uncommitted_intents_conn->ExecuteFormat(
          "insert into events_backwardscan values ('log', 'src', '$0', '$0', '{}')", ts, ts));
    }

    auto count = ASSERT_RESULT(
        conn.FetchValue<int64_t>("SELECT COUNT(*) FROM events_backwardscan"));
    LOG(INFO) << "Total rows inserted: " << count;

    auto select_result = ASSERT_RESULT(conn.Fetch(
        "select * from events_backwardscan order by inserted desc limit 100"
    ));
    ASSERT_EQ(PQntuples(select_result.get()), 100);

    if (uncommitted_intents) {
      ASSERT_OK(uncommitted_intents_conn->Execute("ROLLBACK"));
    }
  }
};

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(BackwardIndexScanNoIntents),
          PgMiniBackwardIndexScanTest) {
  BackwardIndexScanTest(/* uncommitted_intents */ false);
}

TEST_F_EX(PgMiniTest,
          YB_DISABLE_TEST_IN_TSAN(BackwardIndexScanWithIntents),
          PgMiniBackwardIndexScanTest) {
  BackwardIndexScanTest(/* uncommitted_intents */ true);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(CreateDatabase)) {
  FLAGS_flush_rocksdb_on_shutdown = false;
  auto conn = ASSERT_RESULT(Connect());
  const std::string kDatabaseName = "testdb";
  ASSERT_OK(conn.ExecuteFormat("CREATE DATABASE $0", kDatabaseName));
  ASSERT_OK(cluster_->RestartSync());
}

void PgMiniTest::TestBigInsert(bool restart) {
  constexpr int64_t kNumRows = RegularBuildVsSanitizers(100000, 10000);
  FLAGS_txn_max_apply_batch_records = kNumRows / 10;

  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.Execute("CREATE TABLE t (a int PRIMARY KEY) SPLIT INTO 1 TABLETS"));
  ASSERT_OK(conn.Execute("INSERT INTO t VALUES (0)"));

  TestThreadHolder thread_holder;

  std::atomic<int> post_insert_reads{0};
  thread_holder.AddThreadFunctor([this, &stop = thread_holder.stop_flag(), &post_insert_reads] {
    auto connection = ASSERT_RESULT(Connect());
    while (!stop.load(std::memory_order_acquire)) {
      auto res = ASSERT_RESULT(connection.FetchValue<int64_t>("SELECT SUM(a) FROM t"));

      // We should see zero or full sum only.
      if (res) {
        ASSERT_EQ(res, kNumRows * (kNumRows + 1) / 2);
        ++post_insert_reads;
      }
    }
  });

  ASSERT_OK(conn.ExecuteFormat(
      "INSERT INTO t SELECT generate_series(1, $0)", kNumRows));

  if (restart) {
    LOG(INFO) << "Restart cluster";
    ASSERT_OK(cluster_->RestartSync());
  }

  ASSERT_OK(WaitFor([this] {
    auto intents_count = CountIntents(cluster_.get());
    LOG(INFO) << "Intents count: " << intents_count;

    return intents_count == 0;
  }, 60s * kTimeMultiplier, "Intents cleanup", 200ms));

  thread_holder.Stop();

  ASSERT_GT(post_insert_reads.load(std::memory_order_acquire), 0);

  FlushAndCompactTablets();

  auto peers = ListTabletPeers(cluster_.get(), ListPeersFilter::kAll);
  for (const auto& peer : peers) {
    auto db = peer->tablet()->TEST_db();
    if (!db) {
      continue;
    }
    rocksdb::ReadOptions read_opts;
    read_opts.query_id = rocksdb::kDefaultQueryId;
    std::unique_ptr<rocksdb::Iterator> iter(db->NewIterator(read_opts));

    for (iter->SeekToFirst(); iter->Valid(); iter->Next()) {
      Slice key = iter->key();
      ASSERT_FALSE(key.TryConsumeByte(docdb::ValueTypeAsChar::kTransactionApplyState))
          << "Key: " << iter->key().ToDebugString() << ", value: " << iter->value().ToDebugString();
    }
  }
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigInsert)) {
  TestBigInsert(/* restart= */ false);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigInsertWithRestart)) {
  FLAGS_apply_intents_task_injected_delay_ms = 200;
  TestBigInsert(/* restart= */ true);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigInsertWithDropTable)) {
  constexpr int kNumRows = 10000;
  FLAGS_txn_max_apply_batch_records = kNumRows / 10;
  FLAGS_apply_intents_task_injected_delay_ms = 200;
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.Execute("CREATE TABLE t(id int) SPLIT INTO 1 TABLETS"));
  ASSERT_OK(conn.ExecuteFormat(
      "INSERT INTO t SELECT generate_series(1, $0)", kNumRows));
  ASSERT_OK(conn.Execute("DROP TABLE t"));
}

void PgMiniTest::TestConcurrentDeleteRowAndUpdateColumn(bool select_before_update) {
  auto conn1 = ASSERT_RESULT(Connect());
  auto conn2 = ASSERT_RESULT(Connect());
  ASSERT_OK(conn1.Execute("CREATE TABLE t (i INT PRIMARY KEY, j INT)"));
  ASSERT_OK(conn1.Execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)"));
  ASSERT_OK(conn1.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  if (select_before_update) {
    ASSERT_OK(conn1.Fetch("SELECT * FROM t"));
  }
  ASSERT_OK(conn2.Execute("DELETE FROM t WHERE i = 2"));
  auto status = conn1.Execute("UPDATE t SET j = 21 WHERE i = 2");
  if (select_before_update) {
    ASSERT_NOK(status);
    ASSERT_STR_CONTAINS(status.message().ToBuffer(), "Value write after transaction start");
    return;
  }
  ASSERT_OK(status);
  ASSERT_OK(conn1.CommitTransaction());
  auto result = ASSERT_RESULT(conn1.FetchMatrix("SELECT * FROM t ORDER BY i", 2, 2));
  auto value = ASSERT_RESULT(GetInt32(result.get(), 0, 0));
  ASSERT_EQ(value, 1);
  value = ASSERT_RESULT(GetInt32(result.get(), 0, 1));
  ASSERT_EQ(value, 10);
  value = ASSERT_RESULT(GetInt32(result.get(), 1, 0));
  ASSERT_EQ(value, 3);
  value = ASSERT_RESULT(GetInt32(result.get(), 1, 1));
  ASSERT_EQ(value, 30);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(ConcurrentDeleteRowAndUpdateColumn)) {
  TestConcurrentDeleteRowAndUpdateColumn(/* select_before_update= */ false);
}

TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(ConcurrentDeleteRowAndUpdateColumnWithSelect)) {
  TestConcurrentDeleteRowAndUpdateColumn(/* select_before_update= */ true);
}

// Test that we don't sequential restart read on the same table if intents were written
// after the first read. GH #6972.
TEST_F(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(NoRestartSecondRead)) {
  FLAGS_max_clock_skew_usec = 1000000000LL * kTimeMultiplier;
  auto conn1 = ASSERT_RESULT(Connect());
  auto conn2 = ASSERT_RESULT(Connect());
  ASSERT_OK(conn1.Execute("CREATE TABLE t (a int PRIMARY KEY, b int) SPLIT INTO 1 TABLETS"));
  ASSERT_OK(conn1.Execute("INSERT INTO t VALUES (1, 1), (2, 1), (3, 1)"));
  auto start_time = MonoTime::Now();
  ASSERT_OK(conn1.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  LOG(INFO) << "Select1";
  auto res = ASSERT_RESULT(conn1.FetchValue<int32_t>("SELECT b FROM t WHERE a = 1"));
  ASSERT_EQ(res, 1);
  LOG(INFO) << "Update";
  ASSERT_OK(conn2.StartTransaction(IsolationLevel::SNAPSHOT_ISOLATION));
  ASSERT_OK(conn2.Execute("UPDATE t SET b = 2 WHERE a = 2"));
  ASSERT_OK(conn2.CommitTransaction());
  auto update_time = MonoTime::Now();
  ASSERT_LE(update_time, start_time + FLAGS_max_clock_skew_usec * 1us);
  LOG(INFO) << "Select2";
  res = ASSERT_RESULT(conn1.FetchValue<int32_t>("SELECT b FROM t WHERE a = 2"));
  ASSERT_EQ(res, 1);
  ASSERT_OK(conn1.CommitTransaction());
}

TEST_F_EX(PgMiniTest, YB_DISABLE_TEST_IN_TSAN(InOperatorLock), PgMiniTestNoTxnRetry) {
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.Execute("SET yb_transaction_priority_lower_bound = 0.9"));
  ASSERT_OK(conn.Execute("CREATE TABLE t (h INT, r1 INT, r2 INT, PRIMARY KEY(h, r1 ASC, r2 ASC))"));
  ASSERT_OK(conn.Execute(
      "INSERT INTO t VALUES (1, 11, 1),(1, 12, 1),(1, 13, 1),(2, 11, 2),(2, 12, 2),(2, 13, 2)"));
  ASSERT_OK(conn.Execute("BEGIN;"));
  auto res = ASSERT_RESULT(conn.Fetch(
      "SELECT * FROM t WHERE h = 1 AND r1 IN (11, 12) AND r2 = 1 FOR KEY SHARE"));

  auto extra_conn = ASSERT_RESULT(Connect());
  ASSERT_OK(extra_conn.Execute("SET yb_transaction_priority_upper_bound = 0.1"));
  ASSERT_OK(extra_conn.Execute("BEGIN"));
  ASSERT_NOK(extra_conn.Execute("DELETE FROM t WHERE h = 1 AND r1 = 11 AND r2 = 1"));
  ASSERT_OK(extra_conn.Execute("ROLLBACK"));
  ASSERT_OK(extra_conn.Execute("BEGIN"));
  ASSERT_OK(extra_conn.Execute("DELETE FROM t WHERE h = 1 AND r1 = 13 AND r2 = 1"));
  ASSERT_OK(extra_conn.Execute("COMMIT"));

  ASSERT_OK(conn.Execute("COMMIT;"));

  ASSERT_OK(conn.Execute("BEGIN;"));
  res = ASSERT_RESULT(conn.Fetch(
      "SELECT * FROM t WHERE h IN (1, 2) AND r1 = 11 FOR KEY SHARE"));

  ASSERT_OK(extra_conn.Execute("BEGIN"));
  ASSERT_NOK(extra_conn.Execute("DELETE FROM t WHERE h = 1 AND r1 = 11 AND r2 = 1"));
  ASSERT_OK(extra_conn.Execute("ROLLBACK"));
  ASSERT_OK(extra_conn.Execute("BEGIN"));
  ASSERT_NOK(extra_conn.Execute("DELETE FROM t WHERE h = 2 AND r1 = 11 AND r2 = 1"));
  ASSERT_OK(extra_conn.Execute("ROLLBACK"));
  ASSERT_OK(extra_conn.Execute("BEGIN"));
  ASSERT_OK(extra_conn.Execute("DELETE FROM t WHERE h = 2 AND r1 = 12 AND r2 = 2"));
  ASSERT_OK(extra_conn.Execute("COMMIT"));

  ASSERT_OK(conn.Execute("COMMIT;"));
  const auto count = ASSERT_RESULT(conn.FetchValue<int64_t>("SELECT COUNT(*) FROM t"));
  ASSERT_EQ(4, count);
}

// ------------------------------------------------------------------------------------------------
// Tablet Splitting Tests
// ------------------------------------------------------------------------------------------------

class PgMiniTestAutoScanNextPartitions : public PgMiniTest {
 public:
  void SetUp() override {
    FLAGS_TEST_index_read_multiple_partitions = true;
    PgMiniTest::SetUp();
  }
};

TEST_F_EX(
    PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(AutoScanNextPartitions),
    PgMiniTestAutoScanNextPartitions) {
  auto conn = ASSERT_RESULT(Connect());
  constexpr int numRows = 100;
  ASSERT_OK(conn.Execute("CREATE TABLE t (k INT PRIMARY KEY, v1 INT, v2 INT) "
                         "SPLIT INTO 6 TABLETS"));
  ASSERT_OK(conn.Execute("CREATE INDEX ON t(v1, v2)"));

  // Insert elements into the table
  for (int i = 0; i < numRows; i++) {
    ASSERT_OK(conn.ExecuteFormat("INSERT INTO t (k, v1, v2) VALUES ($0, $1, $2)", i, 1, i));
  }

  // Secondary index read from the table
  // While performing secondary index read on ybctids, the pggate layer batches requests belonging
  // to the same tablet. However, if the tablet is split after batching, we need a mechanism to
  // execute the batched request across both the sub-tablets. We create a scenario to test this
  // phenomenon here.
  //
  // FLAGS_index_read_multiple_partitions is a test flag when set will create a scenario to check if
  // index scans of ybctids span across multiple tablets. Specifically in this example, we try to
  // scan the for elements that are present in tablets 0,1 which contain value v1=1 and see if they
  // match the expected number of rows.
  auto res = ASSERT_RESULT(conn.Fetch("SELECT k FROM t WHERE v1 = 1"));
  auto lines = PQntuples(res.get());
  ASSERT_EQ(lines, numRows);
}

class PgMiniTabletSplitTest : public PgMiniTest {
 public:
  void SetUp() override {
    FLAGS_yb_num_shards_per_tserver = 1;
    FLAGS_tablet_split_low_phase_size_threshold_bytes = 0;
    FLAGS_tablet_split_high_phase_size_threshold_bytes = 0;
    FLAGS_max_queued_split_candidates = 10;
    FLAGS_tablet_split_low_phase_shard_count_per_node = 0;
    FLAGS_tablet_split_high_phase_shard_count_per_node = 0;
    FLAGS_tablet_force_split_threshold_bytes = 30_KB;
    FLAGS_db_write_buffer_size = FLAGS_tablet_force_split_threshold_bytes / 4;
    FLAGS_db_block_size_bytes = 2_KB;
    FLAGS_db_filter_block_size_bytes = 2_KB;
    FLAGS_db_index_block_size_bytes = 2_KB;
    FLAGS_heartbeat_interval_ms = 1000;
    FLAGS_tserver_heartbeat_metrics_interval_ms = 1000;
    FLAGS_process_split_tablet_candidates_interval_msec = 1000;
    FLAGS_TEST_inject_delay_between_prepare_ybctid_execute_batch_ybctid_ms = 4000;
    FLAGS_ysql_prefetch_limit = 32;
    PgMiniTest::SetUp();
  }
};

void PgMiniTest::CreateTableAndInitialize(std::string table_name, int num_tablets) {
  auto conn = ASSERT_RESULT(Connect());

  ANNOTATE_UNPROTECTED_WRITE(FLAGS_enable_automatic_tablet_splitting) = false;
  ASSERT_OK(conn.ExecuteFormat("CREATE TABLE $0 (h1 int, h2 int, r int, i int, "
                               "PRIMARY KEY ((h1, h2) HASH, r ASC)) "
                               "SPLIT INTO $1 TABLETS", table_name, num_tablets));

  ASSERT_OK(conn.ExecuteFormat("CREATE INDEX $0_idx "
                               "ON $1(i HASH, r ASC)", table_name, table_name));

  ASSERT_OK(conn.ExecuteFormat("INSERT INTO $0 SELECT i, i, i, 1 FROM "
                               "(SELECT generate_series(1, 500) i) t", table_name));
}

void PgMiniTest::DestroyTable(std::string table_name) {
  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.ExecuteFormat("DROP TABLE $0", table_name));
}

void PgMiniTest::GetTableIDFromTableName(const std::string table_name, std::string* table_id) {
  // Get YBClient handler and tablet ID. Using this we can get the number of tablets before starting
  // the test and before the test ends. With this we can ensure that tablet splitting has occurred.
  auto client = ASSERT_RESULT(cluster_->CreateClient());
  const auto tables = ASSERT_RESULT(client->ListTables());
  for (const auto& table : tables) {
    if (table.has_table() && table.table_name() == "update_pk_complex_two_hash_one_range_keys") {
      table_id->assign(table.table_id());
      break;
    }
  }
}

void PgMiniTest::StartReadWriteThreads(const std::string table_name,
    TestThreadHolder *thread_holder) {
  // Writer thread that does parallel writes into table
  thread_holder->AddThread([this, table_name] {
    auto conn = ASSERT_RESULT(Connect());
    for (int i = 501; i < 2000; i++) {
      ASSERT_OK(conn.ExecuteFormat("INSERT INTO $0 VALUES ($1, $2, $3, $4)",
                                   table_name, i, i, i, 1));
    }
  });

  // Index read from the table
  thread_holder->AddThread([this, &stop = thread_holder->stop_flag(), table_name] {
    auto conn = ASSERT_RESULT(Connect());
    do {
      auto result = ASSERT_RESULT(conn.FetchFormat("SELECT * FROM  $0 WHERE i = 1 order by r",
                                                   table_name));
      std::vector<int> sort_check;
      for(int x = 0; x < PQntuples(result.get()); x++) {
        auto value = ASSERT_RESULT(GetInt32(result.get(), x, 2));
        sort_check.push_back(value);
      }
      ASSERT_TRUE(std::is_sorted(sort_check.begin(), sort_check.end()));
    }  while (!stop.load(std::memory_order_acquire));
  });
}

TEST_F_EX(
    PgMiniTest, YB_DISABLE_TEST_IN_SANITIZERS(TabletSplitSecondaryIndexYSQL),
    PgMiniTabletSplitTest) {

  std::string table_name = "update_pk_complex_two_hash_one_range_keys";
  CreateTableAndInitialize(table_name, 1);

  std::string table_id;
  GetTableIDFromTableName(table_name, &table_id);
  int start_num_tablets = ListTableActiveTabletLeadersPeers(cluster_.get(), table_id).size();
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_enable_automatic_tablet_splitting) = true;

  // Insert elements into the table using a parallel thread
  TestThreadHolder thread_holder;

  /*
   * Writer thread writes into the table continously, while the index read thread does a secondary
   * index lookup. During the index lookup, we inject artificial delays, specified by the flag
   * FLAGS_TEST_tablet_split_injected_delay_ms. Tablets will split in between those delays into
   * two different partitions.
   *
   * The purpose of this test is to verify that when the secondary index read request is being
   * executed, the results from both the tablets are being represented. Without the fix from
   * the pggate layer, only one half of the results will be obtained. Hence we verify that after the
   * split the number of elements is > 500, which is the number of elements inserted before the
   * split.
   */
  StartReadWriteThreads(table_name, &thread_holder);

  thread_holder.WaitAndStop(200s);
  int end_num_tablets = ListTableActiveTabletLeadersPeers(cluster_.get(), table_id).size();
  ASSERT_GT(end_num_tablets, start_num_tablets);
  DestroyTable(table_name);

  // Rerun the same test where table is created with 3 tablets.
  // When a table is created with three tablets, the lower and upper bounds are as follows;
  // tablet 1 -- empty to A
  // tablet 2 -- A to B
  // tablet 3 -- B to empty
  // However, in situations where tables are created with just one tablet lower_bound and
  // upper_bound for the tablet is empty to empty. Hence, to test both situations we run this test
  // with one tablet and three tablets respectively.
  CreateTableAndInitialize(table_name, 3);
  GetTableIDFromTableName(table_name, &table_id);
  start_num_tablets = ListTableActiveTabletLeadersPeers(cluster_.get(), table_id).size();
  ANNOTATE_UNPROTECTED_WRITE(FLAGS_enable_automatic_tablet_splitting) = true;

  StartReadWriteThreads(table_name, &thread_holder);
  thread_holder.WaitAndStop(200s);

  end_num_tablets = ListTableActiveTabletLeadersPeers(cluster_.get(), table_id).size();
  ASSERT_GT(end_num_tablets, start_num_tablets);
  DestroyTable(table_name);
}

class PgMiniTestWithSavepoints : public PgMiniTest {
 protected:
  void SetUp() override {
    FLAGS_enable_pg_savepoints = true;
    PgMiniTest::SetUp();
  }
};

TEST_F_EX(
    PgMiniTest, YB_DISABLE_TEST_IN_TSAN(BigInsertWithAbortedIntentsAndRestart),
    PgMiniTestWithSavepoints) {
  FLAGS_apply_intents_task_injected_delay_ms = 200;

  constexpr int64_t kRowNumModToAbort = 7;
  constexpr int64_t kNumBatches = 10;
  constexpr int64_t kNumRows = RegularBuildVsSanitizers(10000, 1000);
  FLAGS_txn_max_apply_batch_records = kNumRows / kNumBatches;

  auto conn = ASSERT_RESULT(Connect());
  ASSERT_OK(conn.Execute("CREATE TABLE t (a int PRIMARY KEY) SPLIT INTO 1 TABLETS"));

  ASSERT_OK(conn.StartTransaction(IsolationLevel::SERIALIZABLE_ISOLATION));
  for (int64_t rowNum = 0; rowNum < kNumRows; ++rowNum) {
    auto shouldAbort = rowNum % kRowNumModToAbort == 0;
    if (shouldAbort) {
      ASSERT_OK(conn.Execute("SAVEPOINT A"));
    }
    ASSERT_OK(conn.ExecuteFormat("INSERT INTO t VALUES ($0)", rowNum));
    if (shouldAbort) {
      ASSERT_OK(conn.Execute("ROLLBACK TO A"));
    }
  }

  ASSERT_OK(conn.CommitTransaction());

  LOG(INFO) << "Restart cluster";
  ASSERT_OK(cluster_->RestartSync());

  ASSERT_OK(WaitFor([this] {
    auto intents_count = CountIntents(cluster_.get());
    LOG(INFO) << "Intents count: " << intents_count;

    return intents_count == 0;
  }, 60s * kTimeMultiplier, "Intents cleanup", 200ms));

  for (int64_t rowNum = 0; rowNum < kNumRows; ++rowNum) {
    auto shouldAbort = rowNum % kRowNumModToAbort == 0;

    auto res = ASSERT_RESULT(conn.FetchFormat("SELECT * FROM t WHERE a = $0", rowNum));
    if (shouldAbort) {
      EXPECT_NOT_OK(GetInt32(res.get(), 0, 0)) << "Did not expect to find value for: " << rowNum;
    } else {
      int64_t value = EXPECT_RESULT(GetInt32(res.get(), 0, 0));
      EXPECT_EQ(value, rowNum) << "Expected to find " << rowNum << ", found " << value << ".";
    }
  }
}

} // namespace pgwrapper
} // namespace yb
