package io.github.martiaaguilera.quantarun.controlplane.chaos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled off by default: a deployment injects faults only when its operator turns this on, and each worker
 *     must opt in as well.
 * @param deliveryWindow how long an experiment waits for its worker's next heartbeat before it expires; a fault must
 *     not fire long after the operator asked for it.
 * @param maxPendingPerWorker undelivered experiments one worker may have queued.
 */
@Validated
@ConfigurationProperties("quantarun.chaos")
public record ChaosProperties(
        @DefaultValue("false") boolean enabled,
        @NotNull @DefaultValue("30s") Duration deliveryWindow,
        @Min(1) @Max(20) @DefaultValue("5") int maxPendingPerWorker) {}
