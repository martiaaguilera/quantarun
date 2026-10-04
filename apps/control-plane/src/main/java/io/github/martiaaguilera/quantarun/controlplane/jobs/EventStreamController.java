package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server-Sent Events of job events, scoped to what the caller may see. Resumable: a client that reconnects with
 * {@code Last-Event-ID} first receives what it missed. Connections are closed after {@link #CONNECTION_LIFETIME}, and
 * the client reconnects the same way, so no connection lives forever behind a proxy.
 */
@RestController
class EventStreamController {

    static final Duration CONNECTION_LIFETIME = Duration.ofMinutes(30);

    record StreamEvent(
            long id,
            UUID jobId,
            UUID projectId,
            @Nullable UUID attemptId,
            JobEventType type,
            Instant occurredAt,
            ObjectNode details) {}

    private final JobEventStream stream;

    EventStreamController(JobEventStream stream) {
        this.stream = stream;
    }

    @GetMapping(path = "/api/v1/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            Caller caller, @RequestHeader(name = "Last-Event-ID", required = false) @Nullable Long lastEventId) {
        var emitter = new SseEmitter(CONNECTION_LIFETIME.toMillis());
        var unsubscribe = stream.subscribe(caller, lastEventId, new JobEventStream.Sink() {
            @Override
            public void send(JobEventStream.Item item) throws IOException {
                switch (item) {
                    case JobEventStream.Item.Event(var projectEvent) -> {
                        var event = projectEvent.event();
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(event.id()))
                                .name("job")
                                .data(
                                        new StreamEvent(
                                                event.id(),
                                                event.jobId(),
                                                projectEvent.projectId(),
                                                event.attemptId(),
                                                event.type(),
                                                event.occurredAt(),
                                                event.details()),
                                        MediaType.APPLICATION_JSON));
                    }
                    case JobEventStream.Item.Reset(var reason) ->
                        emitter.send(SseEmitter.event().name("reset").data(reason, MediaType.TEXT_PLAIN));
                    case JobEventStream.Item.Heartbeat() ->
                        emitter.send(SseEmitter.event().comment("keepalive"));
                }
            }

            @Override
            public void close() {
                emitter.complete();
            }
        });
        // A client that disconnects frees its slot now, not at the next failed send.
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(error -> unsubscribe.run());
        return emitter;
    }
}
