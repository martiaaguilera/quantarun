package io.github.martiaaguilera.quantarun.controlplane.jobs;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class JobsConfiguration {

    @Bean
    RetryPolicy retryPolicy(
            @Value("${quantarun.retries.base-delay:1s}") Duration baseDelay,
            @Value("${quantarun.retries.max-delay:60s}") Duration maxDelay) {
        return new RetryPolicy(baseDelay, maxDelay);
    }
}
