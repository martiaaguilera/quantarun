package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import io.github.martiaaguilera.quantarun.controlplane.scheduler.policy.SchedulingPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled whether this instance runs scheduling loops (tests drive cycles directly instead).
 * @param policy placement policy for live scheduling.
 * @param windowSize runnable jobs locked per cycle. Bounds transaction length and lock footprint; jobs beyond the
 *     window wait for the next cycle.
 * @param idleDelay pause between cycles that placed nothing. A cycle that placed jobs runs again immediately, so a
 *     backlog drains without waiting.
 * @param loops concurrent scheduling loops in this process. More than one exists to prove the cycle is safe under
 *     concurrency, and lets the benchmarks measure contention.
 */
@Validated
@ConfigurationProperties("quantarun.scheduler")
public record SchedulerProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("FIFO") SchedulingPolicy policy,
        @Min(1) @Max(1000) @DefaultValue("200") int windowSize,
        @NotNull @DefaultValue("500ms") Duration idleDelay,
        @Min(1) @Max(32) @DefaultValue("1") int loops) {}
