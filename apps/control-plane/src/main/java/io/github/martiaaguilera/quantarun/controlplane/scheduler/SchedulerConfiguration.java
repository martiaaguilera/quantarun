package io.github.martiaaguilera.quantarun.controlplane.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SchedulerProperties.class)
class SchedulerConfiguration {

    @Bean
    @ConditionalOnBooleanProperty(name = "quantarun.scheduler.enabled", matchIfMissing = true)
    SchedulerLoop schedulerLoop(SchedulingCycle cycle, SchedulerProperties properties) {
        return new SchedulerLoop(cycle, properties);
    }
}
