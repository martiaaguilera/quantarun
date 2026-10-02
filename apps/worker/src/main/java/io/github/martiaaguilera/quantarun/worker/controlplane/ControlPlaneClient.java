package io.github.martiaaguilera.quantarun.worker.controlplane;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/** Typed client for the worker protocol. Every call is a single bounded HTTP request; retry policy lives in the caller. */
public class ControlPlaneClient {

    /** The control plane retired this registration (OFFLINE or DEREGISTERED): the worker must register again. */
    public static final class RegistrationRetiredException extends RuntimeException {
        RegistrationRetiredException() {
            super("The control plane retired this worker registration");
        }
    }

    /**
     * The control plane will never apply this report or checkpoint: the attempt already ended (its lease expired and it was
     * recovered), it is not this worker's, or this registration was retired. Retrying cannot help.
     */
    public static final class ReportRejectedException extends RuntimeException {
        ReportRejectedException(int status) {
            super("The control plane rejected the report with HTTP " + status);
        }
    }

    private final RestClient http;
    private final String bootstrapToken;

    public ControlPlaneClient(RestClient http, String bootstrapToken) {
        this.http = http;
        this.bootstrapToken = bootstrapToken;
    }

    public WorkerProtocol.RegisterResponse register(WorkerProtocol.RegisterRequest request) {
        return http.post()
                .uri(WorkerProtocol.BASE_PATH + "/register")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bootstrapToken)
                .body(request)
                .retrieve()
                .body(WorkerProtocol.RegisterResponse.class);
    }

    public WorkerProtocol.HeartbeatResponse heartbeat(String workerSecret, List<UUID> activeAttemptIds) {
        return http.post()
                .uri(WorkerProtocol.BASE_PATH + "/heartbeat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerSecret)
                .body(new WorkerProtocol.HeartbeatRequest(activeAttemptIds))
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (request, response) -> {
                    throw new RegistrationRetiredException();
                })
                .body(WorkerProtocol.HeartbeatResponse.class);
    }

    public WorkerProtocol.ClaimResponse claim(String workerSecret, int maxAssignments) {
        return http.post()
                .uri(WorkerProtocol.BASE_PATH + "/claim")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerSecret)
                .body(new WorkerProtocol.ClaimRequest(maxAssignments))
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.CONFLICT.value(), (request, response) -> {
                    throw new RegistrationRetiredException();
                })
                .body(WorkerProtocol.ClaimResponse.class);
    }

    /** 404 and 409 are final answers (the report is fenced); other 4xx are bugs; 5xx and I/O errors may be retried. */
    public WorkerProtocol.ReportResponse report(
            String workerSecret, UUID attemptId, WorkerProtocol.ReportRequest report) {
        return http.post()
                .uri(WorkerProtocol.BASE_PATH + "/attempts/{attemptId}/report", attemptId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerSecret)
                .body(report)
                .retrieve()
                .onStatus(
                        status -> status.value() == HttpStatus.CONFLICT.value()
                                || status.value() == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw new ReportRejectedException(
                                    response.getStatusCode().value());
                        })
                .body(WorkerProtocol.ReportResponse.class);
    }

    /** 404 and 409 mean the attempt is no longer this worker's to write for; they are final, like a report's. */
    public WorkerProtocol.CheckpointResponse checkpoint(
            String workerSecret, UUID attemptId, WorkerProtocol.CheckpointRequest checkpoint) {
        return http.post()
                .uri(WorkerProtocol.BASE_PATH + "/attempts/{attemptId}/checkpoints", attemptId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerSecret)
                .body(checkpoint)
                .retrieve()
                .onStatus(
                        status -> status.value() == HttpStatus.CONFLICT.value()
                                || status.value() == HttpStatus.NOT_FOUND.value(),
                        (request, response) -> {
                            throw new ReportRejectedException(
                                    response.getStatusCode().value());
                        })
                .body(WorkerProtocol.CheckpointResponse.class);
    }

    public void deregister(String workerSecret) {
        http.post()
                .uri(WorkerProtocol.BASE_PATH + "/deregister")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + workerSecret)
                .retrieve()
                .toBodilessEntity();
    }
}
