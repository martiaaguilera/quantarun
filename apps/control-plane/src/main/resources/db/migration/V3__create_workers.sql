-- One row per worker *registration*. A restarted worker process registers again and gets a new id, so stale
-- reservations and leases of the previous incarnation can never be confused with the new one.
CREATE TABLE workers (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    name                  text        NOT NULL,
    version               text        NOT NULL,
    lifecycle             text        NOT NULL DEFAULT 'ACTIVE',
    labels                text[]      NOT NULL DEFAULT '{}',
    cpu_millis_capacity   integer     NOT NULL,
    memory_mib_capacity   integer     NOT NULL,
    accelerator_capacity  integer     NOT NULL,
    slot_capacity         integer     NOT NULL,
    cpu_millis_reserved   integer     NOT NULL DEFAULT 0,
    memory_mib_reserved   integer     NOT NULL DEFAULT 0,
    accelerators_reserved integer     NOT NULL DEFAULT 0,
    slots_reserved        integer     NOT NULL DEFAULT 0,
    credential_prefix     text        NOT NULL,
    credential_hash       bytea       NOT NULL,
    registered_at         timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT workers_lifecycle_known CHECK (lifecycle IN ('ACTIVE', 'DRAINING', 'OFFLINE', 'DEREGISTERED')),
    CONSTRAINT workers_capacity_positive CHECK (
        cpu_millis_capacity > 0 AND memory_mib_capacity > 0 AND accelerator_capacity >= 0 AND slot_capacity > 0),
    -- Invariant I1: whatever the scheduler computes, the database refuses to reserve more than exists or to
    -- release more than was reserved. These are the last line of defence, not the primary mechanism.
    CONSTRAINT workers_cpu_within_capacity CHECK (cpu_millis_reserved BETWEEN 0 AND cpu_millis_capacity),
    CONSTRAINT workers_memory_within_capacity CHECK (memory_mib_reserved BETWEEN 0 AND memory_mib_capacity),
    CONSTRAINT workers_accelerators_within_capacity CHECK (accelerators_reserved BETWEEN 0 AND accelerator_capacity),
    CONSTRAINT workers_slots_within_capacity CHECK (slots_reserved BETWEEN 0 AND slot_capacity),
    CONSTRAINT workers_credential_prefix_unique UNIQUE (credential_prefix),
    CONSTRAINT workers_credential_hash_is_sha256 CHECK (octet_length(credential_hash) = 32)
);

-- Placement candidates: only workers that can take new work are indexed.
CREATE INDEX workers_schedulable_idx ON workers (id) WHERE lifecycle = 'ACTIVE';

-- Heartbeats live apart from `workers` on purpose: the scheduler holds FOR UPDATE locks on worker rows while it
-- reserves capacity, and any UPDATE of the same row would queue behind those locks. A separate row keeps a
-- heartbeat from ever waiting on a scheduling cycle (docs/ARCHITECTURE.md §4).
CREATE TABLE worker_heartbeats (
    worker_id    uuid        PRIMARY KEY REFERENCES workers (id),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    beats        bigint      NOT NULL DEFAULT 1
);
CREATE INDEX worker_heartbeats_last_seen_idx ON worker_heartbeats (last_seen_at);
