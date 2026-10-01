package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerSettings.class)
class WorkerConfiguration {

    @Bean
    ControlPlaneClient controlPlaneClient(RestClient.Builder builder, WorkerSettings settings) {
        // Explicit timeouts: a hung control plane must turn into a failed heartbeat, not a stuck membership thread.
        var requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(settings.connectTimeout(), settings.requestTimeout()));
        var http = builder.baseUrl(settings.controlPlaneUrl().toString())
                .requestFactory(requestFactory)
                .build();
        return new ControlPlaneClient(http, settings.bootstrapToken());
    }

    @Bean
    WorkerAgent workerAgent(ControlPlaneClient client, WorkerSettings settings, ObjectProvider<BuildProperties> build) {
        var version =
                build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
        return new WorkerAgent(client, settings, version, RandomGenerator.getDefault());
    }

    @Bean
    WorkerLifecycleRunner workerLifecycleRunner(WorkerAgent agent) {
        return new WorkerLifecycleRunner(agent);
    }

    /** Ready only once registered: before that the control plane cannot place work here. */
    @Bean
    HealthIndicator registration(WorkerAgent agent) {
        return () -> agent.workerId()
                .map(id -> Health.up()
                        .withDetail("workerId", id)
                        .withDetail("lifecycle", agent.lifecycle())
                        .build())
                .orElseGet(() -> Health.outOfService()
                        .withDetail("reason", "not registered yet")
                        .build());
    }
}
