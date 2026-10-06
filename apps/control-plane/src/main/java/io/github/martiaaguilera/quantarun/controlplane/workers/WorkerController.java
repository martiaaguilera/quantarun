package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.workers.internal.WorkerRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator view of the fleet. Any authenticated caller may read it; only the admin may drain. */
@RestController
@RequestMapping("/api/v1/workers")
class WorkerController {

    static final int MAX_WORKERS_LISTED = 500;

    record WorkerResponse(
            UUID id,
            String name,
            String version,
            WorkerLifecycle lifecycle,
            WorkerHealth health,
            List<String> labels,
            WorkerResources capacity,
            WorkerResources reserved,
            Instant registeredAt,
            Instant lastSeenAt) {}

    private final WorkerRepository workers;
    private final WorkerRegistry registry;
    private final WorkerProperties properties;

    WorkerController(WorkerRepository workers, WorkerRegistry registry, WorkerProperties properties) {
        this.workers = workers;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * The fleet is shared by every tenant, so its shape (names, labels, capacity, what is reserved right now) is the
     * operator's view, as SPEC §12 says; a project learns which workers were considered for its own jobs from those
     * jobs' decision records. Project keys could read it before Phase 14's review.
     */
    @GetMapping
    List<WorkerResponse> list(Caller caller, @RequestParam(required = false) @Nullable WorkerLifecycle lifecycle) {
        caller.requireAdmin();
        return workers.findAll(lifecycle, MAX_WORKERS_LISTED).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{workerId}")
    WorkerResponse get(Caller caller, @PathVariable UUID workerId) {
        caller.requireAdmin();
        return toResponse(workers.findById(workerId).orElseThrow(() -> new WorkerNotFoundException(workerId)));
    }

    @PostMapping("/{workerId}/drain")
    WorkerResponse drain(Caller caller, @PathVariable UUID workerId) {
        caller.requireAdmin();
        return toResponse(registry.drain(workerId));
    }

    private WorkerResponse toResponse(Worker worker) {
        return new WorkerResponse(
                worker.id(),
                worker.name(),
                worker.version(),
                worker.lifecycle(),
                worker.health(properties),
                worker.labels(),
                worker.capacity(),
                worker.reserved(),
                worker.registeredAt(),
                worker.lastSeenAt());
    }
}
