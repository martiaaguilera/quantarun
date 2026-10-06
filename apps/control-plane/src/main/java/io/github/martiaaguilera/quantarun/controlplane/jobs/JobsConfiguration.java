package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.workers.WorkerProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
class JobsConfiguration {

    @Bean
    RetryPolicy retryPolicy(
            @Value("${quantarun.retries.base-delay:1s}") Duration baseDelay,
            @Value("${quantarun.retries.max-delay:60s}") Duration maxDelay) {
        return new RetryPolicy(baseDelay, maxDelay);
    }

    @Bean
    LeaseContinuity leaseContinuity(
            JobAttempts attempts,
            Clock clock,
            WorkerProperties workers,
            @Value("${quantarun.reliability.lease-continuity-enabled:true}") boolean enabled) {
        return new LeaseContinuity(attempts, clock, workers.deafAfter(), enabled);
    }

    /** Off in tests, which refresh the gauges themselves when they assert on them. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "quantarun.metrics.gauges-enabled", matchIfMissing = true)
    static class JobGaugeRefresher {

        private final JobGauges gauges;

        JobGaugeRefresher(JobGauges gauges) {
            this.gauges = gauges;
        }

        @Scheduled(fixedDelayString = "${quantarun.metrics.gauge-refresh-interval:10s}")
        void refresh() {
            gauges.refresh();
        }
    }
}
