package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Queue depth and running jobs, by status. Refreshed on a schedule rather than queried on every scrape: a scrape
 * stays cheap and never waits on the database, and the counts are seconds old at worst.
 */
@Component
public class JobGauges {

    static final List<JobStatus> ACTIVE =
            List.of(JobStatus.QUEUED, JobStatus.RETRY_WAIT, JobStatus.SCHEDULED, JobStatus.RUNNING);

    private final JdbcClient jdbc;
    private final Map<JobStatus, AtomicLong> counts = new EnumMap<>(JobStatus.class);

    JobGauges(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (var status : ACTIVE) {
            var count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder("quantarun.jobs.active", count, AtomicLong::get)
                    .description("Jobs not yet finished, by status (QUEUED and RETRY_WAIT are the queue)")
                    .tag("status", status.name())
                    .register(registry);
        }
    }

    public void refresh() {
        var fresh = new EnumMap<JobStatus, Long>(JobStatus.class);
        jdbc.sql("""
                        SELECT status, count(*) AS jobs FROM jobs
                        WHERE status IN ('QUEUED', 'RETRY_WAIT', 'SCHEDULED', 'RUNNING')
                        GROUP BY status
                        """).query(rs -> {
            fresh.put(JobStatus.valueOf(rs.getString("status")), rs.getLong("jobs"));
        });
        counts.forEach((status, count) -> count.set(fresh.getOrDefault(status, 0L)));
    }
}
