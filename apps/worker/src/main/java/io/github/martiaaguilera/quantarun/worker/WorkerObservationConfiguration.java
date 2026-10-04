package io.github.martiaaguilera.quantarun.worker;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import io.micrometer.observation.ObservationPredicate;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Only calls made for an attempt (reports and checkpoints) are traced: they carry the job's trace to the control
 * plane. The membership loop's calls (register, heartbeat, claim every 500 ms) and health or scrape requests would each
 * start a trace of their own and bury the job traces, so they are not observed.
 */
@Configuration(proxyBeanMethods = false)
class WorkerObservationConfiguration {

    static final Set<String> MEMBERSHIP_PATHS = Set.of(
            WorkerProtocol.BASE_PATH + "/register",
            WorkerProtocol.BASE_PATH + "/heartbeat",
            WorkerProtocol.BASE_PATH + "/claim",
            WorkerProtocol.BASE_PATH + "/deregister");

    @Bean
    ObservationPredicate skipMembershipAndHealth() {
        return (name, context) -> {
            if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
                return !MEMBERSHIP_PATHS.contains(client.getCarrier().getURI().getPath());
            }
            if (context instanceof ServerRequestObservationContext server) {
                return !server.getCarrier().getRequestURI().startsWith("/actuator");
            }
            return true;
        };
    }
}
