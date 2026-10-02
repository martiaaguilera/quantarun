package io.github.martiaaguilera.quantarun.controlplane.scheduler.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Virtual times for fair share. Read and written only by scheduling cycles after they have locked the workers, so
 * cycles that place work are serialised and these plain reads and writes cannot interleave into a lost update.
 */
@Repository
public class FairShareRepository {

    public record ProjectClock(UUID projectId, double virtualTime) {}

    private final JdbcClient jdbc;

    FairShareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Projects that never received service have no row yet; they read as 0 and are raised to the system floor. */
    public Map<UUID, Double> virtualTimes(Collection<UUID> projectIds) {
        var times = new HashMap<UUID, Double>();
        jdbc.sql("SELECT project_id, virtual_time FROM fair_share_clock WHERE project_id = ANY(:ids)")
                .param("ids", projectIds.toArray(UUID[]::new))
                .query((rs, row) -> times.put(rs.getObject("project_id", UUID.class), rs.getDouble("virtual_time")))
                .list();
        return times;
    }

    public double systemVirtualTime() {
        return jdbc.sql("SELECT virtual_time FROM fair_share_system")
                .query(Double.class)
                .single();
    }

    /** Never moves a clock backwards, even if called with stale values. */
    public void save(Map<UUID, Double> virtualTimes, double systemVirtualTime) {
        for (var entry : virtualTimes.entrySet()) {
            jdbc.sql("""
                            INSERT INTO fair_share_clock (project_id, virtual_time) VALUES (:project, :time)
                            ON CONFLICT (project_id) DO UPDATE
                            SET virtual_time = greatest(fair_share_clock.virtual_time, excluded.virtual_time),
                                updated_at = now()
                            """)
                    .param("project", entry.getKey())
                    .param("time", entry.getValue())
                    .update();
        }
        jdbc.sql("UPDATE fair_share_system SET virtual_time = greatest(virtual_time, :time)")
                .param("time", systemVirtualTime)
                .update();
    }

    public List<ProjectClock> all() {
        return jdbc.sql("SELECT project_id, virtual_time FROM fair_share_clock ORDER BY virtual_time, project_id")
                .query((rs, row) ->
                        new ProjectClock(rs.getObject("project_id", UUID.class), rs.getDouble("virtual_time")))
                .list();
    }
}
