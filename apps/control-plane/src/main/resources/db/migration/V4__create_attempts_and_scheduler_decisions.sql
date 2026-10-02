-- An attempt is one execution try of a job on one worker. It is also the *assignment*: it carries the resources
-- reserved on that worker and the lease that keeps the reservation alive. Rows are never updated back into an
-- active state; a retry inserts attempt N+1, so the full history is preserved.
CREATE TABLE job_attempts (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    job_id           uuid        NOT NULL REFERENCES jobs (id),
    attempt_no       smallint    NOT NULL,
    worker_id        uuid        NOT NULL REFERENCES workers (id),
    status           text        NOT NULL DEFAULT 'ASSIGNED',
    cpu_millis       integer     NOT NULL,
    memory_mib       integer     NOT NULL,
    accelerators     integer     NOT NULL,
    assigned_at      timestamptz NOT NULL DEFAULT now(),
    started_at       timestamptz,
    finished_at      timestamptz,
    lease_expires_at timestamptz NOT NULL,
    lease_renewals   integer     NOT NULL DEFAULT 0,
    failure_class    text,
    failure_message  text,
    retry_decision   text,
    trace_id         text,

    CONSTRAINT job_attempts_status_known CHECK (status IN
        ('ASSIGNED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED')),
    CONSTRAINT job_attempts_number_positive CHECK (attempt_no >= 1),
    CONSTRAINT job_attempts_number_unique UNIQUE (job_id, attempt_no),
    CONSTRAINT job_attempts_reservation_valid CHECK (cpu_millis > 0 AND memory_mib > 0 AND accelerators >= 0),
    CONSTRAINT job_attempts_finished_iff_ended CHECK
        ((finished_at IS NOT NULL) = (status IN ('SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED')))
);

-- Invariant I3: a job has at most one active attempt. Even a scheduling bug cannot place the same job twice.
CREATE UNIQUE INDEX job_attempts_one_active_per_job ON job_attempts (job_id) WHERE status IN ('ASSIGNED', 'RUNNING');
-- Claim and heartbeat paths: a worker's active attempts.
CREATE INDEX job_attempts_worker_active_idx ON job_attempts (worker_id) WHERE status IN ('ASSIGNED', 'RUNNING');
-- Lease reaper: only active attempts, ordered by expiry.
CREATE INDEX job_attempts_lease_idx ON job_attempts (lease_expires_at) WHERE status IN ('ASSIGNED', 'RUNNING');

ALTER TABLE job_events
    ADD CONSTRAINT job_events_attempt_fk FOREIGN KEY (attempt_id) REFERENCES job_attempts (id);

-- The scheduler's latest verdict for a waiting job, so the API can show *why* a job is still queued and the
-- scheduler can skip writing a decision record when nothing changed since the last cycle.
ALTER TABLE jobs RENAME COLUMN unschedulable_reason TO scheduling_reason;
ALTER TABLE jobs ADD COLUMN scheduling_outcome text;
ALTER TABLE jobs ADD CONSTRAINT jobs_scheduling_outcome_known CHECK
    (scheduling_outcome IS NULL OR scheduling_outcome IN ('PLACED', 'WAITING_FOR_CAPACITY', 'UNSCHEDULABLE'));

-- One structured record per placement evaluation that changed something: every placement, and each change in why a
-- job is waiting. A job stuck for an hour produces one row, not one per scheduling cycle.
CREATE TABLE scheduler_decisions (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id           uuid        NOT NULL REFERENCES jobs (id),
    attempt_id       uuid        REFERENCES job_attempts (id),
    policy           text        NOT NULL,
    outcome          text        NOT NULL,
    chosen_worker_id uuid        REFERENCES workers (id),
    reason           text        NOT NULL,
    -- Bounded list of {workerId, workerName, verdict, detail, score}; see PlacementPlanner.MAX_CANDIDATES_RECORDED.
    candidates       jsonb       NOT NULL,
    queue_wait_ms    bigint      NOT NULL,
    decided_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT scheduler_decisions_outcome_known CHECK (outcome IN ('PLACED', 'WAITING_FOR_CAPACITY', 'UNSCHEDULABLE')),
    CONSTRAINT scheduler_decisions_placed_has_worker CHECK
        ((outcome = 'PLACED') = (chosen_worker_id IS NOT NULL AND attempt_id IS NOT NULL)),
    CONSTRAINT scheduler_decisions_candidates_array CHECK (jsonb_typeof(candidates) = 'array')
);
CREATE INDEX scheduler_decisions_job_idx ON scheduler_decisions (job_id, id);
