-- Per-project quotas (docs/SPEC.md §7). NULL means unlimited.
--   max_queued_jobs: admission control; a submission beyond it is rejected with a reason.
--   max_running_jobs, max_accelerators: the scheduler holds back placements beyond them (WAITING_FOR_QUOTA).
ALTER TABLE projects ADD COLUMN max_queued_jobs integer;
ALTER TABLE projects ADD COLUMN max_running_jobs integer;
ALTER TABLE projects ADD COLUMN max_accelerators integer;
ALTER TABLE projects ADD CONSTRAINT projects_max_queued_positive CHECK (max_queued_jobs IS NULL OR max_queued_jobs >= 1);
ALTER TABLE projects ADD CONSTRAINT projects_max_running_positive CHECK (max_running_jobs IS NULL OR max_running_jobs >= 1);
ALTER TABLE projects ADD CONSTRAINT projects_max_accelerators_non_negative
    CHECK (max_accelerators IS NULL OR max_accelerators >= 0);

-- Fair share: each project's virtual time, plus the system virtual time a project returning from idle is raised to.
-- Kept apart from projects so the scheduler's frequent writes never contend with admission's row lock on projects.
CREATE TABLE fair_share_clock (
    project_id   uuid             PRIMARY KEY REFERENCES projects (id),
    virtual_time double precision NOT NULL DEFAULT 0,
    updated_at   timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT fair_share_clock_non_negative CHECK (virtual_time >= 0)
);

CREATE TABLE fair_share_system (
    singleton    boolean          PRIMARY KEY DEFAULT true,
    virtual_time double precision NOT NULL DEFAULT 0,
    CONSTRAINT fair_share_system_singleton CHECK (singleton),
    CONSTRAINT fair_share_system_non_negative CHECK (virtual_time >= 0)
);
INSERT INTO fair_share_system DEFAULT VALUES;

-- Fair-share window: each project's oldest runnable jobs, one index probe per project.
CREATE INDEX jobs_runnable_by_project_idx ON jobs (project_id, available_at, id)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');
-- Deadline window: earliest deadline first.
CREATE INDEX jobs_runnable_by_deadline_idx ON jobs (deadline_at, priority DESC, available_at, id)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');
-- Admission: a project's unfinished jobs, counted against max_queued_jobs.
CREATE INDEX jobs_unfinished_by_project_idx ON jobs (project_id)
    WHERE status IN ('QUEUED', 'SCHEDULED', 'RUNNING', 'RETRY_WAIT');

-- A job held back by its project's quota is a third kind of "waiting", with its own reason.
ALTER TABLE jobs DROP CONSTRAINT jobs_scheduling_outcome_known;
ALTER TABLE jobs ADD CONSTRAINT jobs_scheduling_outcome_known CHECK (scheduling_outcome IS NULL
    OR scheduling_outcome IN ('PLACED', 'WAITING_FOR_CAPACITY', 'WAITING_FOR_QUOTA', 'UNSCHEDULABLE'));
ALTER TABLE scheduler_decisions DROP CONSTRAINT scheduler_decisions_outcome_known;
ALTER TABLE scheduler_decisions ADD CONSTRAINT scheduler_decisions_outcome_known
    CHECK (outcome IN ('PLACED', 'WAITING_FOR_CAPACITY', 'WAITING_FOR_QUOTA', 'UNSCHEDULABLE'));
