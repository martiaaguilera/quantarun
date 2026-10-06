-- The PRIORITY scheduling window (ORDER BY priority DESC, available_at, id over runnable jobs) had no matching index:
-- every cycle read and sorted the whole runnable backlog to lock 200 rows. Measured with 10,000 runnable jobs on
-- 2026-10-06: 10.2 ms per window with a sort, 0.15-0.24 ms with this index (BENCHMARKS.md). The FIFO, deadline and
-- per-project windows already have theirs (V2, V7). Partial like them, so finished history never enters it.
CREATE INDEX jobs_runnable_by_priority_idx ON jobs (priority DESC, available_at, id)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');
