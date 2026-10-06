package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * @param unfinished every unfinished status, including zeros. Finished jobs are counted by recent window only.
 * @param retriesLastHour attempts after the first, placed in the last hour.
 * @param timeToStartP95Seconds 95th percentile, over first attempts started in the last 15 minutes, of the time from
 *     submission to start; null when none started.
 */
public record JobSummary(
        Map<JobStatus, Long> unfinished,
        long succeededLastHour,
        long failedLastHour,
        long deadLastHour,
        long cancelledLastHour,
        long retriesLastHour,
        @Nullable Double timeToStartP95Seconds) {}
