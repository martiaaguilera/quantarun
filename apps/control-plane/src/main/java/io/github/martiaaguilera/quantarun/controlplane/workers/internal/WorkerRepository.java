package io.github.martiaaguilera.quantarun.controlplane.workers.internal;

import io.github.martiaaguilera.quantarun.controlplane.workers.Worker;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerLifecycle;
import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerResources;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkerRepository {

    private static final String SELECT_WORKERS = """
            SELECT w.id, w.name, w.version, w.lifecycle, w.labels,
                   w.cpu_millis_capacity, w.memory_mib_capacity, w.accelerator_capacity, w.slot_capacity,
                   w.cpu_millis_reserved, w.memory_mib_reserved, w.accelerators_reserved, w.slots_reserved,
                   w.registered_at, h.last_seen_at, now() AS observed_at
            FROM workers w JOIN worker_heartbeats h ON h.worker_id = w.id
            """;

    public record Credential(UUID workerId, byte[] hash, WorkerLifecycle lifecycle) {}

    private final JdbcClient jdbc;

    WorkerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(WorkerProtocol.RegisterRequest request, String credentialPrefix, byte[] credentialHash) {
        var capacity = request.capacity();
        var workerId = jdbc.sql("""
                        INSERT INTO workers (name, version, labels, cpu_millis_capacity, memory_mib_capacity,
                                             accelerator_capacity, slot_capacity, credential_prefix, credential_hash)
                        VALUES (:name, :version, :labels, :cpu, :memory, :accelerators, :slots, :prefix, :hash)
                        RETURNING id
                        """)
                .param("name", request.name())
                .param("version", request.version())
                .param("labels", request.labels().stream().distinct().sorted().toArray(String[]::new))
                .param("cpu", capacity.cpuMillis())
                .param("memory", capacity.memoryMib())
                .param("accelerators", capacity.accelerators())
                .param("slots", capacity.slots())
                .param("prefix", credentialPrefix)
                .param("hash", credentialHash)
                .query(UUID.class)
                .single();
        jdbc.sql("INSERT INTO worker_heartbeats (worker_id) VALUES (:id)")
                .param("id", workerId)
                .update();
        return workerId;
    }

    public Optional<Credential> findCredential(String prefix) {
        return jdbc.sql("SELECT id, credential_hash, lifecycle FROM workers WHERE credential_prefix = :prefix")
                .param("prefix", prefix)
                .query((rs, row) -> new Credential(
                        rs.getObject("id", UUID.class),
                        rs.getBytes("credential_hash"),
                        WorkerLifecycle.valueOf(rs.getString("lifecycle"))))
                .optional();
    }

    public Optional<Worker> findById(UUID id) {
        return jdbc.sql(SELECT_WORKERS + " WHERE w.id = :id")
                .param("id", id)
                .query(this::mapWorker)
                .optional();
    }

    /** The fleet is small (tens of workers), so the list is bounded by a generous cap rather than paginated. */
    public List<Worker> findAll(@Nullable WorkerLifecycle lifecycle, int limit) {
        var sql = SELECT_WORKERS + (lifecycle == null ? "" : " WHERE w.lifecycle = :lifecycle")
                + " ORDER BY w.name, w.id LIMIT :limit";
        var statement = jdbc.sql(sql).param("limit", limit);
        if (lifecycle != null) {
            statement = statement.param("lifecycle", lifecycle.name());
        }
        return statement.query(this::mapWorker).list();
    }

    /**
     * Records liveness only. It never touches the {@code workers} row, so it cannot block on, or be blocked by, a
     * scheduler holding that row's lock.
     */
    public void recordHeartbeat(UUID workerId) {
        jdbc.sql("UPDATE worker_heartbeats SET last_seen_at = now(), beats = beats + 1 WHERE worker_id = :id")
                .param("id", workerId)
                .update();
    }

    /** Conditional lifecycle change; empty when the worker is not in one of {@code expected}. */
    public Optional<WorkerLifecycle> transition(UUID workerId, List<WorkerLifecycle> expected, WorkerLifecycle next) {
        expected.forEach(from -> from.requireTransitionTo(next));
        return jdbc.sql("""
                        UPDATE workers SET lifecycle = :next, updated_at = now()
                        WHERE id = :id AND lifecycle = ANY(:expected)
                        RETURNING lifecycle
                        """)
                .param("id", workerId)
                .param("next", next.name())
                .param("expected", expected.stream().map(Enum::name).toArray(String[]::new))
                .query((rs, row) -> WorkerLifecycle.valueOf(rs.getString(1)))
                .optional();
    }

    /**
     * Graceful exit: a worker with nothing reserved leaves immediately; one still holding reservations drains and
     * leaves once its attempts end. Decided in one statement so a concurrent placement cannot slip in between a
     * read of {@code slots_reserved} and the update.
     */
    public Optional<WorkerLifecycle> deregister(UUID workerId) {
        return jdbc.sql("""
                        UPDATE workers
                        SET lifecycle = CASE WHEN slots_reserved = 0 THEN 'DEREGISTERED' ELSE 'DRAINING' END,
                            updated_at = now()
                        WHERE id = :id AND lifecycle IN ('ACTIVE', 'DRAINING')
                        RETURNING lifecycle
                        """)
                .param("id", workerId)
                .query((rs, row) -> WorkerLifecycle.valueOf(rs.getString(1)))
                .optional();
    }

    public record RetiredWorker(UUID id, String name) {}

    /**
     * Retires registrations whose heartbeats stopped. Several control-plane instances may run this at once: the
     * lifecycle predicate is re-checked after any row lock wait, so each worker is retired exactly once.
     */
    public List<RetiredWorker> retireSilentWorkers(Duration offlineAfter) {
        return jdbc.sql("""
                        UPDATE workers w SET lifecycle = 'OFFLINE', updated_at = now()
                        FROM worker_heartbeats h
                        WHERE h.worker_id = w.id
                          AND w.lifecycle IN ('ACTIVE', 'DRAINING')
                          AND h.last_seen_at < now() - make_interval(secs => :offlineSeconds)
                        RETURNING w.id, w.name
                        """)
                .param("offlineSeconds", offlineAfter.toMillis() / 1000.0)
                .query((rs, row) -> new RetiredWorker(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    private Worker mapWorker(ResultSet rs, int rowNum) throws SQLException {
        return new Worker(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("version"),
                WorkerLifecycle.valueOf(rs.getString("lifecycle")),
                Arrays.asList((String[]) rs.getArray("labels").getArray()),
                new WorkerResources(
                        rs.getInt("cpu_millis_capacity"),
                        rs.getInt("memory_mib_capacity"),
                        rs.getInt("accelerator_capacity"),
                        rs.getInt("slot_capacity")),
                new WorkerResources(
                        rs.getInt("cpu_millis_reserved"),
                        rs.getInt("memory_mib_reserved"),
                        rs.getInt("accelerators_reserved"),
                        rs.getInt("slots_reserved")),
                rs.getTimestamp("registered_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("observed_at").toInstant());
    }
}
