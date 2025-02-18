--
-- CREATE_INDEX
-- Create ancillary data structures (i.e. indices)
--

--
-- LSM
--
CREATE INDEX onek_unique1 ON onek USING lsm(unique1 int4_ops);

CREATE INDEX IF NOT EXISTS onek_unique1 ON onek USING lsm(unique1 int4_ops);

CREATE INDEX IF NOT EXISTS ON onek USING lsm(unique1 int4_ops);

CREATE INDEX onek_unique2 ON onek USING lsm(unique2 int4_ops);

CREATE INDEX onek_hundred ON onek USING lsm(hundred int4_ops);

CREATE INDEX onek_stringu1 ON onek USING lsm(stringu1 name_ops);

CREATE INDEX tenk1_unique1 ON tenk1 USING lsm(unique1 int4_ops);

CREATE INDEX tenk1_unique2 ON tenk1 USING lsm(unique2 int4_ops);

CREATE INDEX tenk1_hundred ON tenk1 USING lsm(hundred int4_ops);

CREATE INDEX tenk1_thous_tenthous ON tenk1 (thousand, tenthous);

CREATE INDEX tenk2_unique1 ON tenk2 USING lsm(unique1 int4_ops);

CREATE INDEX tenk2_unique2 ON tenk2 USING lsm(unique2 int4_ops);

CREATE INDEX tenk2_hundred ON tenk2 USING lsm(hundred int4_ops);

CREATE INDEX rix ON road USING lsm (name text_ops);

CREATE INDEX iix ON ihighway USING lsm (name text_ops);

CREATE INDEX six ON shighway USING lsm (name text_ops);

--
-- GIN over int[] and text[]
--
-- Note: GIN currently supports only bitmap scans, not plain indexscans
-- YB Note: ybgin uses plain indexscans, not bitmap scans
--

SET enable_seqscan = OFF;
SET enable_indexscan = OFF;
SET enable_bitmapscan = ON;

-- YB note: the following CREATE INDEXes are commented because errdetail varies.
-- TODO(jason): uncomment when working on (issue #9959).
-- CREATE INDEX NONCONCURRENTLY intarrayidx ON array_index_op_test USING gin (i);
--
-- CREATE INDEX NONCONCURRENTLY textarrayidx ON array_index_op_test USING gin (t);

-- And try it with a multicolumn GIN index

DROP INDEX intarrayidx, textarrayidx;
-- TODO(jason): remove the following drops when working on issue #880
DROP INDEX intarrayidx;
DROP INDEX textarrayidx;

CREATE INDEX botharrayidx ON array_index_op_test USING gin (i, t);

RESET enable_seqscan;
RESET enable_indexscan;
RESET enable_bitmapscan;

--
-- Try a GIN index with a lot of items with same key. (GIN creates a posting
-- tree when there are enough duplicates)
-- YB Note: ybgin does not use a posting list or tree
--
CREATE TABLE array_gin_test (a int[]);

INSERT INTO array_gin_test SELECT ARRAY[1, g%5, g] FROM generate_series(1, 10000) g;

CREATE INDEX array_gin_test_idx ON array_gin_test USING gin (a);

SELECT COUNT(*) FROM array_gin_test WHERE a @> '{2}';

DROP TABLE array_gin_test;

--
-- Test GIN index's reloptions
--
CREATE INDEX gin_relopts_test ON array_index_op_test USING gin (i)
  WITH (FASTUPDATE=on, GIN_PENDING_LIST_LIMIT=128);
\d+ gin_relopts_test

--
-- Try some concurrent index builds
--
-- Unfortunately this only tests about half the code paths because there are
-- no concurrent updates happening to the table at the same time.

CREATE TABLE concur_heap (f1 text, f2 text);
-- empty table
CREATE INDEX CONCURRENTLY concur_index1 ON concur_heap(f2,f1);

-- You can't do a concurrent index build in a transaction
BEGIN;
CREATE INDEX CONCURRENTLY concur_index7 ON concur_heap(f1);
COMMIT;

-- But you can do a regular index build in a transaction
BEGIN;
CREATE INDEX std_index on concur_heap(f2);
COMMIT;
