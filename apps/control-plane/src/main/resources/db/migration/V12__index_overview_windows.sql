-- The overview reads recent windows of history (finished in the last hour, retried in the last hour, started in the
-- last 15 minutes), and the console asks for it up to once a second while events flow. Without these, each window was
-- a scan of the whole history: 260–320 ms per overview at a million jobs (ENGINEERING_LOG, 2026-10-06).
CREATE INDEX jobs_finished_idx ON jobs (finished_at) WHERE finished_at IS NOT NULL;
CREATE INDEX job_attempts_retries_recent_idx ON job_attempts (assigned_at) WHERE attempt_no > 1;
CREATE INDEX job_attempts_first_started_idx ON job_attempts (started_at) WHERE attempt_no = 1;
