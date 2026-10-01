package io.github.martiaaguilera.quantarun.controlplane.workers;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Worker fleet timing, owned by the control plane and handed to workers at registration.
 *
 * @param bootstrapToken shared secret a worker presents only to register; every later call uses the per-worker
 *     credential issued at registration.
 * @param heartbeatInterval how often workers heartbeat.
 * @param lateAfter silence after which a worker stops receiving new placements.
 * @param offlineAfter silence after which the registration is retired.
 * @param leaseDuration how long an attempt survives without renewal.
 * @param livenessCheckInterval how often the control plane looks for silent workers.
 * @param startupGrace how long after this control plane starts before it may retire anyone. While the control plane
 *     is down nobody records heartbeats, so right after a restart every live worker looks silent; retiring them
 *     would throw away healthy registrations (and, once leases exist, re-run work that was fine). Defaults to the
 *     offline threshold, which gives every live worker time to heartbeat again.
 */
@Validated
@ConfigurationProperties("quantarun.workers")
public record WorkerProperties(
        @NotBlank @Size(min = 32) String bootstrapToken,
        @NotNull @DefaultValue("3s") Duration heartbeatInterval,
        @NotNull @DefaultValue("7s") Duration lateAfter,
        @NotNull @DefaultValue("15s") Duration offlineAfter,
        @NotNull @DefaultValue("15s") Duration leaseDuration,
        @NotNull @DefaultValue("1s") Duration livenessCheckInterval,
        @DefaultValue("15s") Duration startupGrace) {

    public WorkerProperties {
        // Misordered thresholds would make healthy workers flap between states; fail at startup instead.
        if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeat-interval must be positive");
        }
        if (lateAfter.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("late-after must cover at least two heartbeat intervals");
        }
        if (offlineAfter.compareTo(lateAfter) <= 0) {
            throw new IllegalArgumentException("offline-after must be longer than late-after");
        }
        if (startupGrace.isNegative()) {
            throw new IllegalArgumentException("startup-grace must not be negative");
        }
        if (leaseDuration.compareTo(heartbeatInterval.multipliedBy(3)) < 0) {
            throw new IllegalArgumentException("lease-duration must cover at least three heartbeat intervals");
        }
    }

    @Override
    public String toString() {
        return "WorkerProperties[bootstrapToken=<redacted>, heartbeatInterval=" + heartbeatInterval + ", lateAfter="
                + lateAfter + ", offlineAfter=" + offlineAfter + ", leaseDuration=" + leaseDuration + ", startupGrace="
                + startupGrace + "]";
    }
}
