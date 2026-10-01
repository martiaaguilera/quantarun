package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkersConfiguration {

    @Bean
    FilterRegistrationBean<WorkerAuthenticationFilter> workerAuthenticationFilter(
            WorkerProperties properties, WorkerRegistry registry) {
        var registration = new FilterRegistrationBean<>(new WorkerAuthenticationFilter(properties, registry));
        registration.addUrlPatterns(WorkerProtocol.BASE_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    /**
     * Retires workers that stopped heartbeating. Disabled in tests, which call {@link WorkerRegistry#retireSilentWorkers}
     * directly with a controlled clock so no test has to sleep through the offline threshold.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "quantarun.workers.liveness-monitor-enabled", matchIfMissing = true)
    static class WorkerLivenessMonitor {

        private final WorkerRegistry registry;

        WorkerLivenessMonitor(WorkerRegistry registry) {
            this.registry = registry;
        }

        @Scheduled(fixedDelayString = "${quantarun.workers.liveness-check-interval:1s}")
        void retireSilentWorkers() {
            registry.retireSilentWorkers();
        }
    }
}
