package io.github.martiaaguilera.quantarun.controlplane.jobs;

/** Timeline entries. The set grows as phases add behaviour; each value is written by exactly one code path. */
public enum JobEventType {
    SUBMITTED,
    SCHEDULED,
    /** The worker claimed the attempt and began executing it. */
    STARTED,
    SUCCEEDED,
    /** One attempt failed; a RETRY_SCHEDULED, FAILED or DEAD event follows with the decision. */
    ATTEMPT_FAILED,
    /** The attempt's lease expired: the worker is presumed lost and its reservation is released. */
    ATTEMPT_LOST,
    RETRY_SCHEDULED,
    FAILED,
    DEAD,
    CANCEL_REQUESTED,
    CANCELLED
}
