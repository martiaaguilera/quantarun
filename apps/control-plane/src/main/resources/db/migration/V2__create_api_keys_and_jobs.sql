-- Project API keys. Keys are 256-bit random secrets, so a fast hash (SHA-256) is enough: unlike passwords
-- there is nothing to brute-force. The short prefix is public and lets us find the row without scanning hashes.
CREATE TABLE api_keys (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    project_id  uuid        NOT NULL REFERENCES projects (id),
    prefix      text        NOT NULL,
    secret_hash bytea       NOT NULL,
    label       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    CONSTRAINT api_keys_prefix_unique UNIQUE (prefix),
    CONSTRAINT api_keys_hash_is_sha256 CHECK (octet_length(secret_hash) = 32),
    CONSTRAINT api_keys_label_length CHECK (char_length(label) BETWEEN 1 AND 100)
);
CREATE INDEX api_keys_project_idx ON api_keys (project_id);

-- One row per logical job. Every column the scheduler filters or sorts on is a real column; only the
-- workload-specific payload is JSONB.
CREATE TABLE jobs (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    project_id           uuid        NOT NULL REFERENCES projects (id),
    workload_type        text        NOT NULL,
    payload              jsonb       NOT NULL,
    status               text        NOT NULL,
    priority             smallint    NOT NULL DEFAULT 0,
    cpu_millis           integer     NOT NULL,
    memory_mib           integer     NOT NULL,
    accelerators         integer     NOT NULL DEFAULT 0,
    required_labels      text[]      NOT NULL DEFAULT '{}',
    max_attempts         smallint    NOT NULL,
    attempt_count        smallint    NOT NULL DEFAULT 0,
    timeout_seconds      integer     NOT NULL,
    available_at         timestamptz NOT NULL DEFAULT now(),
    deadline_at          timestamptz,
    idempotency_key      text,
    request_hash         bytea,
    cancel_requested_at  timestamptz,
    unschedulable_reason text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz,

    CONSTRAINT jobs_status_known CHECK (status IN
        ('QUEUED', 'SCHEDULED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')),
    -- finished_at marks the end of the job's life; revive (DEAD -> QUEUED) clears it again.
    CONSTRAINT jobs_finished_iff_final CHECK
        ((finished_at IS NOT NULL) = (status IN ('SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED'))),
    CONSTRAINT jobs_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT jobs_priority_range CHECK (priority BETWEEN 0 AND 9),
    CONSTRAINT jobs_cpu_positive CHECK (cpu_millis > 0),
    CONSTRAINT jobs_memory_positive CHECK (memory_mib > 0),
    CONSTRAINT jobs_accelerators_non_negative CHECK (accelerators >= 0),
    CONSTRAINT jobs_max_attempts_range CHECK (max_attempts BETWEEN 1 AND 10),
    -- Invariant I9: attempts never exceed the budget, whatever the application code does.
    CONSTRAINT jobs_attempts_within_budget CHECK (attempt_count BETWEEN 0 AND max_attempts),
    CONSTRAINT jobs_timeout_range CHECK (timeout_seconds BETWEEN 1 AND 86400),
    CONSTRAINT jobs_idempotency_pair CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),
    -- Invariant I7: concurrent duplicate submissions serialize on this index; exactly one insert wins.
    CONSTRAINT jobs_idempotency_unique UNIQUE (project_id, idempotency_key)
);

-- Scheduler hot path: only runnable rows are indexed, so the index stays small however many finished jobs pile up.
CREATE INDEX jobs_runnable_idx ON jobs (available_at, id) WHERE status IN ('QUEUED', 'RETRY_WAIT');
-- Job listing, newest first. uuidv7 ids are time-ordered, so id order is creation order.
CREATE INDEX jobs_project_recent_idx ON jobs (project_id, id DESC);
CREATE INDEX jobs_status_recent_idx ON jobs (status, id DESC);

-- Append-only operational timeline. It is not an event-sourcing log: jobs/job_attempts stay the source of truth.
CREATE TABLE job_events (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id      uuid        NOT NULL REFERENCES jobs (id),
    attempt_id  uuid,
    type        text        NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    details     jsonb       NOT NULL DEFAULT '{}',
    CONSTRAINT job_events_details_is_object CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX job_events_job_idx ON job_events (job_id, id);
