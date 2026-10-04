package io.github.martiaaguilera.quantarun.controlplane.reliability;

import io.github.martiaaguilera.quantarun.controlplane.jobs.JobAttempts;
import io.github.martiaaguilera.quantarun.controlplane.jobs.LeaseContinuity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Disabled in tests, which call {@link JobAttempts#recoverExpiredLeases} directly: a background reaper would race the
 * assertions of every test that expires a lease on purpose.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ReliabilityConfiguration {

    @Bean
    @ConditionalOnBooleanProperty(name = "quantarun.reliability.lease-reaper-enabled", matchIfMissing = true)
    LeaseReaper leaseReaper(JobAttempts attempts, LeaseContinuity continuity) {
        return new LeaseReaper(attempts, continuity);
    }
}
