package io.github.martiaaguilera.quantarun.controlplane;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Java-side time for request validation. Anything that coordinates between processes (leases, availability)
 * uses the database clock instead, so control-plane and worker clock skew cannot affect correctness.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
