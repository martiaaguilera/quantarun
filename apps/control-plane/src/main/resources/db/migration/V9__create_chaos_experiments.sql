-- A chaos experiment: one predefined fault, aimed at one worker registration, delivered to it in a heartbeat
-- response. The fault is a closed set and every parameter is bounded here as well as in the API, so even a bug in the
-- API layer cannot store an unbounded fault.
CREATE TABLE chaos_experiments (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    fault            text        NOT NULL,
    worker_id        uuid        NOT NULL REFERENCES workers (id),
    -- Set when the experiment was aimed at "the worker running this job"; the timeline follows that job closely.
    job_id           uuid        REFERENCES jobs (id),
    delay_ms         integer     NOT NULL DEFAULT 0,
    duration_ms      integer     NOT NULL DEFAULT 0,
    fault_count      integer     NOT NULL DEFAULT 0,
    retry_after_ms   integer     NOT NULL DEFAULT 0,
    latency_ms       integer     NOT NULL DEFAULT 0,
    status           text        NOT NULL DEFAULT 'PENDING',
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- An undelivered fault must not fire long after the operator asked for it (the worker may come back much later).
    deliver_by       timestamptz NOT NULL,
    delivered_at     timestamptz,
    ended_at         timestamptz,

    CONSTRAINT chaos_fault_known CHECK (fault IN ('KILL_WORKER', 'PAUSE_HEARTBEAT', 'STOP_CLAIMING', 'NETWORK_LATENCY',
                                                  'STALL_ATTEMPTS', 'PROVIDER_RATE_LIMITED', 'PROVIDER_ERROR',
                                                  'PROVIDER_MALFORMED')),
    CONSTRAINT chaos_status_known CHECK (status IN ('PENDING', 'DELIVERED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chaos_delay_bounded CHECK (delay_ms BETWEEN 0 AND 60000),
    CONSTRAINT chaos_duration_bounded CHECK (duration_ms BETWEEN 0 AND 120000),
    CONSTRAINT chaos_count_bounded CHECK (fault_count BETWEEN 0 AND 20),
    CONSTRAINT chaos_retry_after_bounded CHECK (retry_after_ms BETWEEN 0 AND 60000),
    CONSTRAINT chaos_latency_bounded CHECK (latency_ms BETWEEN 0 AND 5000),
    CONSTRAINT chaos_delivery_consistent CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL)),
    CONSTRAINT chaos_end_consistent CHECK ((status IN ('EXPIRED', 'CANCELLED')) = (ended_at IS NOT NULL))
);

-- Every heartbeat asks for its worker's pending experiments; almost always there are none.
CREATE INDEX chaos_experiments_pending_idx ON chaos_experiments (worker_id) WHERE status = 'PENDING';
