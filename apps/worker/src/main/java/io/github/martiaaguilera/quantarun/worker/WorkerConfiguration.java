package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.worker.chaos.ChaosInjector;
import io.github.martiaaguilera.quantarun.worker.controlplane.ControlPlaneClient;
import io.github.martiaaguilera.quantarun.worker.execution.AttemptExecutor;
import io.github.martiaaguilera.quantarun.worker.execution.AttemptTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import java.time.Duration;
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

    @Bean(destroyMethod = "close")
    ChaosInjector chaosInjector(WorkerSettings settings) {
        return new ChaosInjector(settings.chaosEnabled(), Clock.systemUTC(), ChaosInjector.haltProcess());
    }

    @Bean
    ControlPlaneClient controlPlaneClient(RestClient.Builder builder, WorkerSettings settings, ChaosInjector chaos) {
        // Explicit timeouts: a hung control plane must turn into a failed heartbeat, not a stuck membership thread.
        var requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(settings.connectTimeout(), settings.requestTimeout()));
        var http = builder.baseUrl(settings.controlPlaneUrl().toString())
                .requestFactory(requestFactory)
                // NETWORK_LATENCY chaos: every call to the control plane waits first, the way a slow link would.
                .requestInterceptor((request, body, execution) -> {
                    chaos.delayCall();
                    return execution.execute(request, body);
                })
                .build();
        return new ControlPlaneClient(http, settings.bootstrapToken());
    }

    /** One execution slot per declared slot: the control plane reserves exactly this many. */
    @Bean(destroyMethod = "close")
    AttemptExecutor attemptExecutor(
            ControlPlaneClient client,
            WorkerSettings settings,
            ChaosInjector chaos,
            Tracer tracer,
            Propagator propagator,
            ObservationRegistry observations,
            MeterRegistry meters) {
        return new AttemptExecutor(
                client,
                settings.capacity().slots(),
                new AttemptExecutor.ReportPolicy(
                        settings.reportAttempts(), Duration.ofMillis(200), Duration.ofSeconds(5)),
                new AttemptExecutor.HttpSettings(settings.httpAllowedPrivateAddresses(), settings.connectTimeout()),
                chaos,
                new AttemptTelemetry(tracer, propagator, observations, meters),
                RandomGenerator.getDefault());
    }

    @Bean
    WorkerAgent workerAgent(
            ControlPlaneClient client,
            AttemptExecutor executor,
            WorkerSettings settings,
            ChaosInjector chaos,
            ObjectProvider<BuildProperties> build) {
        var version =
                build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
        return new WorkerAgent(client, executor, settings, chaos, version, RandomGenerator.getDefault());
    }

    @Bean
    WorkerLifecycleRunner workerLifecycleRunner(WorkerAgent agent, WorkerSettings settings) {
        return new WorkerLifecycleRunner(agent, settings);
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
