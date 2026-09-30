-- Projects are the tenants of QuantaRun: they own jobs and API keys, and their weight drives fair-share
-- scheduling. uuidv7() (new in PostgreSQL 18) is time-ordered, so new rows append to the right edge of the
-- primary-key index instead of landing at random pages the way uuidv4 keys do.
CREATE TABLE projects (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    name       text        NOT NULL,
    weight     integer     NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT projects_name_unique UNIQUE (name),
    CONSTRAINT projects_name_format CHECK (name ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT projects_weight_range CHECK (weight BETWEEN 1 AND 1000)
);
