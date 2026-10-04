-- W3C trace context (traceparent) of the request that submitted the job. Placement, execution and completion of every
-- attempt are recorded as spans of that trace, so one trace tells the whole story of a job across both processes.
ALTER TABLE jobs ADD COLUMN trace_parent text;
ALTER TABLE jobs ADD CONSTRAINT jobs_trace_parent_w3c
    CHECK (trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');

-- V4 reserved trace_id and nothing ever wrote it. It becomes the attempt's own context: the placement span, which the
-- worker's spans are children of.
ALTER TABLE job_attempts RENAME COLUMN trace_id TO trace_parent;
ALTER TABLE job_attempts ADD CONSTRAINT job_attempts_trace_parent_w3c
    CHECK (trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
