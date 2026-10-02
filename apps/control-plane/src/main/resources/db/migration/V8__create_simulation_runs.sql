-- A simulation run: one scenario, seed and job count replayed against several policies. The results are a document
-- read as a whole and never filtered on (hence jsonb); what identifies a run is a real column.
CREATE TABLE simulation_runs (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    scenario             text        NOT NULL,
    seed                 bigint      NOT NULL,
    job_count            integer     NOT NULL,
    -- NULL when an operator ran it; otherwise the project whose key requested it, which alone may read it back.
    requested_by_project uuid        REFERENCES projects (id),
    results              jsonb       NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT simulation_runs_job_count_range CHECK (job_count BETWEEN 1 AND 20000),
    CONSTRAINT simulation_runs_results_object CHECK (jsonb_typeof(results) = 'object')
);
CREATE INDEX simulation_runs_project_idx ON simulation_runs (requested_by_project, id DESC);
