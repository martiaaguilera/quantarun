package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import jakarta.validation.Valid;
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
        @NotNull @DefaultValue("30s") Duration maxRetryDelay) {

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
                + ", capacity=" + capacity + ", bootstrapToken=<redacted>]";
    }
}
