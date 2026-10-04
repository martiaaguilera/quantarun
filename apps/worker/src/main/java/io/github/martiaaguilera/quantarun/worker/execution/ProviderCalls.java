package io.github.martiaaguilera.quantarun.worker.execution;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;

/**
 * Wraps a workload's call to a provider in an observation: a span in the attempt's trace and a timer whose outcome tag
 * gives the provider error rate. The outcome is the failure class, so a 429 and a 500 count apart.
 */
final class ProviderCalls {

    @FunctionalInterface
    interface Call {
        Map<String, Object> run() throws InterruptedException;
    }

    private final ObservationRegistry registry;

    ProviderCalls(ObservationRegistry registry) {
        this.registry = registry;
    }

    Map<String, Object> observe(String workloadType, Call call) throws InterruptedException {
        var observation = Observation.createNotStarted("quantarun.worker.provider.call", registry)
                .contextualName("provider.call")
                .lowCardinalityKeyValue("workload_type", workloadType)
                .start();
        try (var _ = observation.openScope()) {
            var result = call.run();
            observation.lowCardinalityKeyValue("outcome", "success");
            return result;
        } catch (WorkloadFailure e) {
            observation.lowCardinalityKeyValue(
                    "outcome", e.failureClass().name().toLowerCase());
            observation.error(e);
            throw e;
        } catch (InterruptedException e) {
            observation.lowCardinalityKeyValue("outcome", "interrupted");
            throw e;
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("outcome", "internal");
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }
}
