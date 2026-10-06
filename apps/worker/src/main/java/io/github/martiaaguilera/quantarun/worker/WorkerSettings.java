package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * What this worker offers and where it reports. Capacity is declared, not measured: accelerators are simulated slots
 * and CPU/memory are the budget this worker will accept, which keeps scheduling demonstrable on any laptop.
 *
 * @param claimInterval how often an idle worker asks for work; with free slots and work waiting it asks again at once.
 * @param claimWait how long a claim may wait at the control plane for work to be placed on this worker. The claim
 *     answers as soon as something is placed, so an assignment reaches the worker within milliseconds instead of up to
 *     one claim interval later (BENCHMARKS.md). Zero turns waiting off and falls back to polling every claim interval.
 *     Must stay below {@code requestTimeout}.
 * @param shutdownGrace how long a graceful shutdown waits for running attempts before stopping them. Must stay below
 *     {@code spring.lifecycle.timeout-per-shutdown-phase}, or the platform kills the process mid-wait.
 * @param reportAttempts how many unexpected server errors an outcome report or checkpoint may meet before the worker
 *     gives up and lets the lease expire. An unreachable control plane or a 503 does not count: the worker keeps the
 *     attempt in its heartbeats and retries until the control plane answers.
 * @param httpAllowedPrivateAddresses IPs or CIDRs the http workload may call although they are private or loopback
 *     (SSRF exceptions, for a local mock provider). Empty by default: only public addresses are reachable.
 * @param chaosEnabled whether this worker applies the chaos faults the control plane sends it. Off by default: a
 *     worker someone did not start for chaos testing ignores them, whatever the control plane asks.
 */
@Validated
@ConfigurationProperties("quantarun.worker")
public record WorkerSettings(
        @NotNull URI controlPlaneUrl,
        @NotBlank @Size(min = 32) String bootstrapToken,

        @NotBlank @Pattern(regexp = WorkerProtocol.LABEL_PATTERN)
        String name,

        @NotNull @DefaultValue List<@Pattern(regexp = WorkerProtocol.LABEL_PATTERN) String> labels,
        @NotNull @Valid Capacity capacity,
        @NotNull @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DefaultValue("10s") Duration requestTimeout,
        @NotNull @DefaultValue("30s") Duration maxRetryDelay,
        @NotNull @DefaultValue("500ms") Duration claimInterval,
        @NotNull @DefaultValue("2s") Duration claimWait,
        @NotNull @DefaultValue("25s") Duration shutdownGrace,
        @Min(1) @Max(20) @DefaultValue("5") int reportAttempts,
        @NotNull @DefaultValue @Size(max = 32) List<@NotBlank String> httpAllowedPrivateAddresses,
        @DefaultValue("false") boolean chaosEnabled) {

    public WorkerSettings {
        if (claimWait.isNegative()
                || claimWait.toMillis() > WorkerProtocol.MAX_CLAIM_WAIT_MILLIS
                || claimWait.compareTo(requestTimeout) >= 0) {
            throw new IllegalArgumentException("claim-wait must be between 0 and "
                    + WorkerProtocol.MAX_CLAIM_WAIT_MILLIS + " ms, and below request-timeout");
        }
    }

    public record Capacity(
            @DefaultValue("2000") int cpuMillis,
            @DefaultValue("2048") int memoryMib,
            @DefaultValue("0") int accelerators,
            @DefaultValue("2") int slots) {

        WorkerProtocol.Capacity toProtocol() {
            return new WorkerProtocol.Capacity(cpuMillis, memoryMib, accelerators, slots);
        }
    }

    @Override
    public String toString() {
        return "WorkerSettings[controlPlaneUrl=" + controlPlaneUrl + ", name=" + name + ", labels=" + labels
                + ", capacity=" + capacity + ", claimInterval=" + claimInterval + ", shutdownGrace=" + shutdownGrace
                + ", chaosEnabled=" + chaosEnabled + ", bootstrapToken=<redacted>]";
    }
}
