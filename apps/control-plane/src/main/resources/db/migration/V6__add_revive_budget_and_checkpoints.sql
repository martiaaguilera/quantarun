-- Revive (DEAD -> QUEUED) grants a fresh attempt budget while every earlier attempt stays in history. attempt_count
-- keeps counting all attempts (it numbers them), so the budget is measured from where it was granted.
ALTER TABLE jobs ADD COLUMN budget_start smallint NOT NULL DEFAULT 0;
ALTER TABLE jobs ADD COLUMN revive_count smallint NOT NULL DEFAULT 0;

-- Invariant I9, restated for revive: within one budget, attempts never exceed max_attempts.
ALTER TABLE jobs DROP CONSTRAINT jobs_attempts_within_budget;
ALTER TABLE jobs ADD CONSTRAINT jobs_attempts_within_budget
    CHECK (attempt_count >= budget_start AND attempt_count - budget_start <= max_attempts);
-- Bounded like everything else: a job that keeps dying is a bug to look at, not something to revive forever.
ALTER TABLE jobs ADD CONSTRAINT jobs_revive_count_range CHECK (revive_count BETWEEN 0 AND 10);
ALTER TABLE jobs ADD CONSTRAINT jobs_budget_start_bounded CHECK (budget_start BETWEEN 0 AND 110);

-- A committed stage result of a staged workload. The attempt that committed it is recorded, but the checkpoint
-- belongs to the job: a later attempt (on any worker) resumes after the last committed stage.
CREATE TABLE job_checkpoints (
    job_id       uuid        NOT NULL REFERENCES jobs (id),
    stage_index  smallint    NOT NULL,
    attempt_id   uuid        NOT NULL REFERENCES job_attempts (id),
    result       jsonb       NOT NULL,
    committed_at timestamptz NOT NULL DEFAULT now(),

    -- Invariant I14: one result per stage. Together with "next stage = last + 1" under the attempt's row lock, stage
    -- indexes never go backwards and never skip.
    CONSTRAINT job_checkpoints_pk PRIMARY KEY (job_id, stage_index),
    CONSTRAINT job_checkpoints_stage_range CHECK (stage_index BETWEEN 0 AND 99),
    CONSTRAINT job_checkpoints_result_is_object CHECK (jsonb_typeof(result) = 'object'),
    CONSTRAINT job_checkpoints_result_size CHECK (octet_length(result::text) <= 8192)
);
