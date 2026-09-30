package io.github.martiaaguilera.quantarun.controlplane.jobs;

/** Timeline entries. The set grows as phases add behaviour; each value is written by exactly one code path. */
public enum JobEventType {
    SUBMITTED,
    CANCEL_REQUESTED,
    CANCELLED
}
