package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Fleet size and capacity across live workers, with no per-worker tag: worker registrations come and go, and each one
 * would be a new time series. Utilization is {@code reserved / capacity}, left to the query in Prometheus. Refreshed on
 * a schedule, like the job gauges.
 */
@Component
public class FleetGauges {

    private static final List<String> LIFECYCLES = List.of("ACTIVE", "DRAINING");
    private static final List<String> RESOURCES = List.of("cpu_millis", "memory_mib", "accelerators", "slots");

    private final JdbcClient jdbc;
    private final Map<String, AtomicLong> workers = Map.of("ACTIVE", new AtomicLong(), "DRAINING", new AtomicLong());
    private final Map<String, AtomicLong> capacity = Map.of(
            "cpu_millis",
            new AtomicLong(),
            "memory_mib",
            new AtomicLong(),
            "accelerators",
            new AtomicLong(),
            "slots",
            new AtomicLong());
    private final Map<String, AtomicLong> reserved = Map.of(
            "cpu_millis",
            new AtomicLong(),
            "memory_mib",
            new AtomicLong(),
            "accelerators",
            new AtomicLong(),
            "slots",
            new AtomicLong());

    FleetGauges(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (var lifecycle : LIFECYCLES) {
            Gauge.builder("quantarun.workers", workers.get(lifecycle), AtomicLong::get)
                    .description("Live worker registrations, by lifecycle")
                    .tag("lifecycle", lifecycle)
                    .register(registry);
        }
        for (var resource : RESOURCES) {
            Gauge.builder("quantarun.fleet.capacity", capacity.get(resource), AtomicLong::get)
                    .description("Capacity of live workers")
                    .tag("resource", resource)
                    .register(registry);
            Gauge.builder("quantarun.fleet.reserved", reserved.get(resource), AtomicLong::get)
                    .description("Capacity reserved by active attempts on live workers")
                    .tag("resource", resource)
                    .register(registry);
        }
    }

    public void refresh() {
        workers.values().forEach(count -> count.set(0));
        jdbc.sql("""
                        SELECT lifecycle, count(*) AS workers FROM workers
                        WHERE lifecycle IN ('ACTIVE', 'DRAINING') GROUP BY lifecycle
                        """).query(rs -> {
            workers.get(rs.getString("lifecycle")).set(rs.getLong("workers"));
        });
        jdbc.sql("""
                        SELECT coalesce(sum(cpu_millis_capacity), 0) AS cpu_cap,
                               coalesce(sum(memory_mib_capacity), 0) AS mem_cap,
                               coalesce(sum(accelerator_capacity), 0) AS acc_cap,
                               coalesce(sum(slot_capacity), 0) AS slot_cap,
                               coalesce(sum(cpu_millis_reserved), 0) AS cpu_res,
                               coalesce(sum(memory_mib_reserved), 0) AS mem_res,
                               coalesce(sum(accelerators_reserved), 0) AS acc_res,
                               coalesce(sum(slots_reserved), 0) AS slot_res
                        FROM workers WHERE lifecycle IN ('ACTIVE', 'DRAINING')
                        """).query(rs -> {
            capacity.get("cpu_millis").set(rs.getLong("cpu_cap"));
            capacity.get("memory_mib").set(rs.getLong("mem_cap"));
            capacity.get("accelerators").set(rs.getLong("acc_cap"));
            capacity.get("slots").set(rs.getLong("slot_cap"));
            reserved.get("cpu_millis").set(rs.getLong("cpu_res"));
            reserved.get("memory_mib").set(rs.getLong("mem_res"));
            reserved.get("accelerators").set(rs.getLong("acc_res"));
            reserved.get("slots").set(rs.getLong("slot_res"));
        });
    }
}
