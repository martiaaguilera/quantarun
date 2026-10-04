package io.github.martiaaguilera.quantarun.controlplane.web;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.micrometer.observation.ObservationPredicate;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces about jobs. Workers heartbeat every 3 s and poll for work every 500 ms, the reaper and the liveness
 * monitor tick every second, and Prometheus scrapes every few seconds. Observed, each of those calls is a trace of its
 * own, and in the first live run they buried the job traces. They are not observed at all, which also leaves them out of
 * {@code http.server.requests}; their health shows in worker liveness and the lease metrics instead.
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    static final Set<String> POLLING_PATHS = Set.of(
            WorkerProtocol.BASE_PATH + "/heartbeat",
            WorkerProtocol.BASE_PATH + "/claim",
            WorkerProtocol.BASE_PATH + "/register",
            WorkerProtocol.BASE_PATH + "/deregister");

    @Bean
    ObservationPredicate skipPollingAndHousekeeping() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext server) {
                var path = server.getCarrier().getRequestURI();
                return !path.startsWith("/actuator") && !POLLING_PATHS.contains(path);
            }
            return !name.equals("tasks.scheduled.execution");
        };
    }
}
