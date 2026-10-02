package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Drives scheduling through the real background loop, exactly as production does. A cycle-level test alone missed a
 * self-invocation bug that only the loop's call path triggered (docs/ENGINEERING_LOG.md, 2026-10-02).
 */
@IntegrationTest
class SchedulerLoopTest {

    @Autowired
    SchedulingCycle cycle;

    @Autowired
    SchedulerProperties properties;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void emptyQueueAndFleet() {
        jdbc.sql("TRUNCATE scheduler_decisions, job_events, job_attempts, jobs, worker_heartbeats, workers CASCADE")
                .update();
    }

    @Test
    void backgroundLoop_placesRunnableJobs() {
        var worker = jdbc.sql("""
                        INSERT INTO workers (name, version, cpu_millis_capacity, memory_mib_capacity,
                                             accelerator_capacity, slot_capacity, credential_prefix, credential_hash)
                        VALUES ('loop-worker', 't', 4000, 8192, 0, 4, 'abcdef01', sha256('x'::bytea)) RETURNING id
                        """).query(UUID.class).single();
        jdbc.sql("INSERT INTO worker_heartbeats (worker_id) VALUES (:w)")
                .param("w", worker)
                .update();
        var project = jdbc.sql("INSERT INTO projects (name) VALUES ('loop-test') RETURNING id")
                .query(UUID.class)
                .single();
        var job = jdbc.sql("""
                        INSERT INTO jobs (project_id, workload_type, payload, status, cpu_millis, memory_mib,
                                          max_attempts, timeout_seconds)
                        VALUES (:p, 'delay', '{}', 'QUEUED', 500, 256, 3, 60) RETURNING id
                        """).param("p", project).query(UUID.class).single();

        var loop = new SchedulerLoop(cycle, properties);
        loop.start();
        try {
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(jdbc.sql("SELECT status FROM jobs WHERE id = :j")
                                    .param("j", job)
                                    .query(String.class)
                                    .single())
                            .isEqualTo("SCHEDULED"));
        } finally {
            loop.stop();
        }
        assertThat(loop.isRunning()).isFalse();
    }
}
