-- Output of a successful attempt (for example the mock-inference response and its token counts). Stored on the
-- attempt, not the job, so retries keep their own history. Size is capped by the control plane before insert.
ALTER TABLE job_attempts ADD COLUMN result jsonb;
ALTER TABLE job_attempts ADD CONSTRAINT job_attempts_result_is_object CHECK (result IS NULL OR jsonb_typeof(result) = 'object');
