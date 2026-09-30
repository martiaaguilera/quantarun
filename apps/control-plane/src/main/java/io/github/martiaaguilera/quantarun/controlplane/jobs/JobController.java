package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/jobs")
class JobController {

    static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(30);
    private static final String LABEL_PATTERN = "^[a-z0-9][a-z0-9.-]{0,62}$";
    private static final String IDEMPOTENCY_KEY_PATTERN = "^[\\x21-\\x7E]{1,200}$";

    /**
     * Boxed on purpose: Jackson 3 rejects a missing field bound to a primitive (FAIL_ON_NULL_FOR_PRIMITIVES is on by
     * default), so required fields are {@code @NotNull} wrappers and the optional one has an explicit default.
     */
    record Resources(
            @NotNull @Min(1) @Max(256_000) Integer cpuMillis,
            @NotNull @Min(1) @Max(1_048_576) Integer memoryMib,
            @Min(0) @Max(64) @Nullable Integer accelerators) {

        int acceleratorsOrDefault() {
            return accelerators == null ? 0 : accelerators;
        }
    }

    record SubmitJobRequest(
            @NotBlank String workloadType,
            @NotNull ObjectNode payload,
            @Min(0) @Max(9) @Nullable Integer priority,
            @NotNull @Valid Resources resources,
            @Size(max = 16) @Nullable List<@NotNull @Pattern(regexp = LABEL_PATTERN) String> requiredLabels,
            @Min(1) @Max(10) @Nullable Integer maxAttempts,
            @Min(1) @Max(86_400) @Nullable Integer timeoutSeconds,
            @Nullable Instant notBefore,
            @Nullable Instant deadline) {}

    record JobResponse(
            UUID id,
            UUID projectId,
            String workloadType,
            ObjectNode payload,
            JobStatus status,
            int priority,
            Resources resources,
            List<String> requiredLabels,
            int maxAttempts,
            int attemptCount,
            int timeoutSeconds,
            Instant availableAt,
            @Nullable Instant deadline,
            @Nullable String idempotencyKey,
            @Nullable Instant cancelRequestedAt,
            @Nullable String unschedulableReason,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant finishedAt) {

        static JobResponse from(Job job) {
            var resources = job.resources();
            return new JobResponse(
                    job.id(),
                    job.projectId(),
                    job.workloadType().wireName(),
                    job.payload(),
                    job.status(),
                    job.priority(),
                    new Resources(resources.cpuMillis(), resources.memoryMib(), resources.accelerators()),
                    job.requiredLabels(),
                    job.maxAttempts(),
                    job.attemptCount(),
                    job.timeoutSeconds(),
                    job.availableAt(),
                    job.deadlineAt(),
                    job.idempotencyKey(),
                    job.cancelRequestedAt(),
                    job.unschedulableReason(),
                    job.createdAt(),
                    job.updatedAt(),
                    job.finishedAt());
        }
    }

    /** {@code nextBefore} is the cursor for the next (older) page; absent on the last page. */
    record JobPage(List<JobResponse> items, @Nullable UUID nextBefore) {}

    record JobEventResponse(
            long id, @Nullable UUID attemptId, JobEventType type, Instant occurredAt, ObjectNode details) {}

    record CancelResponse(UUID jobId, JobLifecycle.CancelOutcome outcome) {}

    private final JobLifecycle lifecycle;
    private final JobQueries queries;
    private final Clock clock;

    JobController(JobLifecycle lifecycle, JobQueries queries, Clock clock) {
        this.lifecycle = lifecycle;
        this.queries = queries;
        this.clock = clock;
    }

    /**
     * 201 for a new job, 200 with {@code Idempotent-Replayed: true} when the same key and request were seen before.
     */
    @PostMapping
    ResponseEntity<JobResponse> submit(
            Caller caller,
            @RequestHeader(name = "Idempotency-Key", required = false)
                    @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = "1-200 printable ASCII characters")
                    @Nullable
                    String idempotencyKey,
            @Valid @RequestBody SubmitJobRequest request) {
        var projectId = caller.requireProjectMember();
        var result = lifecycle.submit(projectId, toSubmission(request), idempotencyKey);
        var body = JobResponse.from(result.job());
        return switch (result) {
            case JobLifecycle.SubmissionResult.Created created ->
                ResponseEntity.created(
                                URI.create("/api/v1/jobs/" + created.job().id()))
                        .body(body);
            case JobLifecycle.SubmissionResult.Replayed _ ->
                ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        };
    }

    @GetMapping("/{jobId}")
    JobResponse get(Caller caller, @PathVariable UUID jobId) {
        return JobResponse.from(queries.get(caller, jobId));
    }

    @GetMapping
    JobPage list(
            Caller caller,
            @RequestParam(required = false) @Nullable UUID projectId,
            @RequestParam(required = false) @Nullable JobStatus status,
            @RequestParam(required = false) @Nullable UUID before,
            @RequestParam(defaultValue = "50") @Min(1) @Max(JobQueries.MAX_PAGE_SIZE) int limit) {
        var jobs = queries.list(caller, projectId, status, before, limit);
        var nextBefore = jobs.size() == limit ? jobs.getLast().id() : null;
        return new JobPage(jobs.stream().map(JobResponse::from).toList(), nextBefore);
    }

    @GetMapping("/{jobId}/events")
    List<JobEventResponse> events(Caller caller, @PathVariable UUID jobId) {
        return queries.events(caller, jobId).stream()
                .map(event -> new JobEventResponse(
                        event.id(), event.attemptId(), event.type(), event.occurredAt(), event.details()))
                .toList();
    }

    @PostMapping("/{jobId}/cancel")
    ResponseEntity<CancelResponse> cancel(Caller caller, @PathVariable UUID jobId) {
        queries.get(caller, jobId);
        var outcome = lifecycle.cancel(jobId);
        var status = outcome == JobLifecycle.CancelOutcome.CANCEL_REQUESTED ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(new CancelResponse(jobId, outcome));
    }

    private JobSubmission toSubmission(SubmitJobRequest request) {
        var workloadType = WorkloadType.fromWireName(request.workloadType())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "UNKNOWN_WORKLOAD_TYPE",
                        "Unknown workload type '" + request.workloadType() + "'."));
        if (request.payload().toString().getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE", "Payload exceeds " + MAX_PAYLOAD_BYTES + " bytes.");
        }
        var now = clock.instant();
        if (request.deadline() != null && !request.deadline().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DEADLINE_IN_PAST", "The deadline has already passed.");
        }
        if (request.notBefore() != null && request.notBefore().isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "NOT_BEFORE_TOO_FAR",
                    "notBefore may be at most " + MAX_SCHEDULE_AHEAD.toDays() + " days ahead.");
        }
        var resources = request.resources();
        return new JobSubmission(
                workloadType,
                request.payload(),
                request.priority() == null ? JobSubmission.DEFAULT_PRIORITY : request.priority(),
                new ResourceRequest(resources.cpuMillis(), resources.memoryMib(), resources.acceleratorsOrDefault()),
                request.requiredLabels() == null ? List.of() : request.requiredLabels(),
                request.maxAttempts() == null ? JobSubmission.DEFAULT_MAX_ATTEMPTS : request.maxAttempts(),
                request.timeoutSeconds() == null ? JobSubmission.DEFAULT_TIMEOUT_SECONDS : request.timeoutSeconds(),
                request.notBefore(),
                request.deadline());
    }
}
