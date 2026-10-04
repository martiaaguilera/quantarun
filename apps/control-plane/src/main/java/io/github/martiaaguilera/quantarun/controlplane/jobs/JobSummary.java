package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * @param byStatus every status, including zeros.
 * @param retriesLastHour attempts after the first, placed in the last hour.
 * @param timeToStartP95Seconds 95th percentile, over first attempts started in the last 15 minutes, of the time from
 *     submission to start; null when none started.
 */
public record JobSummary(
        Map<JobStatus, Long> byStatus,
        long succeededLastHour,
        long failedLastHour,
        long deadLastHour,
        long retriesLastHour,
        @Nullable Double timeToStartP95Seconds) {}
