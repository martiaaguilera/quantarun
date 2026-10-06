package io.github.martiaaguilera.quantarun.controlplane.jobs;

import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository.ProjectEvent;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Live job events for the console, as one shared tail of {@code job_events} fanned out to subscribers.
 *
 * <p><b>Tailing an identity column.</b> Ids are assigned when a row is inserted, but transactions commit in their own
 * order, so a row with a smaller id can become visible after a larger one. A tail that simply moved its cursor to the
 * largest id seen would skip it for good. This one keeps a low watermark below which everything is settled, delivers
 * any id above it that it has not delivered yet, and moves past a missing id only once the gap has lasted
 * {@code gapTimeout}: by then the inserting transaction rolled back, or the id was never used.
 *
 * <p><b>Slow clients.</b> Each subscriber has a bounded queue drained by its own virtual thread, so one slow connection
 * never holds up the others or the tail. A subscriber whose queue overflows is disconnected; its browser reconnects with
 * {@code Last-Event-ID} and replays what it missed from the table.
 */
@Component
public class JobEventStream implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobEventStream.class);
    static final int TAIL_BATCH = 1_000;
    static final int REPLAY_LIMIT = 1_000;
    static final int QUEUE_CAPACITY = 2_000;

    /** What a subscriber is sent. */
    public sealed interface Item {
        record Event(ProjectEvent event) implements Item {}

        /** Too much was missed to replay: the client should reload its views from the API. */
        record Reset(String reason) implements Item {}

        /** A comment line that keeps proxies from closing an idle connection. */
        record Heartbeat() implements Item {}
    }

    /** Where a subscriber's items go: an SSE connection in production, a list in tests. */
    public interface Sink {
        void send(Item item) throws IOException;

        void close();
    }

    private final class Subscriber {
        final Caller caller;
        final Sink sink;
        final BlockingQueue<Item> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        /** Ids sent during the replay, so the same events arriving live are not sent twice. */
        final Set<Long> replayed = new HashSet<>();

        volatile boolean closed;

        @Nullable
        Thread drainer;

        Subscriber(Caller caller, Sink sink) {
            this.caller = caller;
            this.sink = sink;
        }

        void offer(Item item) {
            if (!closed && !queue.offer(item)) {
                log.atWarn().log("Event stream subscriber too slow; disconnecting it so it reconnects and replays");
                close();
            }
        }

        void drain() {
            try {
                while (!closed) {
                    sink.send(queue.take());
                }
            } catch (IOException e) {
                // The client went away; nothing to report.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            subscribers.remove(this);
            sink.close();
            var thread = drainer;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    private final JobEventRepository events;
    private final boolean pollingEnabled;
    private final Duration pollInterval;
    private final Duration gapTimeout;
    private final Duration heartbeatInterval;
    private final int maxSubscribers;
    /**
     * Streams one project key may hold open. Without it one tenant could take every slot and lock the operator's
     * console out of live updates (Phase 14 review). The operator is bounded only by the total.
     */
    private final int maxPerProject;

    private final Supplier<Instant> clock;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    // Tail state: touched only by poll(), which runs on one thread at a time.
    private long watermark = -1;
    private final TreeSet<Long> deliveredAbove = new TreeSet<>();
    private final Map<Long, Instant> gapsSince = new HashMap<>();
    private Instant lastHeartbeat = Instant.EPOCH;
    private volatile @Nullable Thread poller;

    @Autowired
    JobEventStream(
            JobEventRepository events,
            @Value("${quantarun.events.poll-enabled:true}") boolean pollingEnabled,
            @Value("${quantarun.events.poll-interval:250ms}") Duration pollInterval,
            @Value("${quantarun.events.gap-timeout:5s}") Duration gapTimeout,
            @Value("${quantarun.events.heartbeat-interval:15s}") Duration heartbeatInterval,
            @Value("${quantarun.events.max-subscribers:50}") int maxSubscribers,
            @Value("${quantarun.events.max-subscribers-per-project:5}") int maxPerProject) {
        this(
                events,
                pollingEnabled,
                pollInterval,
                gapTimeout,
                heartbeatInterval,
                maxSubscribers,
                maxPerProject,
                Instant::now);
    }

    JobEventStream(
            JobEventRepository events,
            boolean pollingEnabled,
            Duration pollInterval,
            Duration gapTimeout,
            Duration heartbeatInterval,
            int maxSubscribers,
            int maxPerProject,
            Supplier<Instant> clock) {
        this.events = events;
        this.pollingEnabled = pollingEnabled;
        this.pollInterval = pollInterval;
        this.gapTimeout = gapTimeout;
        this.heartbeatInterval = heartbeatInterval;
        this.maxSubscribers = maxSubscribers;
        this.maxPerProject = maxPerProject;
        this.clock = clock;
    }

    /**
     * @param lastEventId the last event the client saw (its {@code Last-Event-ID}); what came after is replayed first.
     *     Null for a fresh connection, which only receives what happens from now on.
     * @return ends the subscription; safe to call more than once
     */
    public Runnable subscribe(Caller caller, @Nullable Long lastEventId, Sink sink) {
        var subscriber = new Subscriber(caller, sink);
        // Registered before the replay is read, so nothing committed in between is lost; the replayed ids keep
        // anything that arrives both ways from being sent twice.
        synchronized (subscriber) {
            admit(subscriber);
            if (lastEventId != null) {
                replay(subscriber, lastEventId);
            }
        }
        subscriber.drainer = Thread.ofVirtual().name("event-stream-subscriber").start(subscriber::drain);
        return subscriber::close;
    }

    /** Checks the limits and registers in one step, so concurrent connections cannot overshoot them. */
    private synchronized void admit(Subscriber subscriber) {
        if (subscribers.size() >= maxSubscribers) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "EVENT_STREAM_FULL",
                    "Too many open event streams (" + maxSubscribers + "); retry shortly.");
        }
        if (subscriber.caller instanceof Caller.ProjectMember member
                && subscribers.stream()
                                .filter(other -> other.caller instanceof Caller.ProjectMember theirs
                                        && theirs.projectId().equals(member.projectId()))
                                .count()
                        >= maxPerProject) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "EVENT_STREAMS_PER_PROJECT",
                    "This project already has " + maxPerProject + " open event streams; close one first.");
        }
        subscribers.add(subscriber);
    }

    private void replay(Subscriber subscriber, long lastEventId) {
        var missed = events.findAfter(lastEventId, REPLAY_LIMIT + 1);
        if (missed.size() > REPLAY_LIMIT) {
            subscriber.offer(new Item.Reset("more than " + REPLAY_LIMIT + " events were missed"));
            return;
        }
        for (var event : missed) {
            if (subscriber.caller.canAccessProject(event.projectId())) {
                subscriber.replayed.add(event.event().id());
                subscriber.offer(new Item.Event(event));
            }
        }
    }

    /** One tail step: deliver new events, then move the watermark over everything settled. */
    synchronized void poll() {
        if (watermark < 0) {
            watermark = events.maxId();
        }
        var now = clock.get();
        var fresh = events.findAfter(watermark, TAIL_BATCH);
        for (var event : fresh) {
            var id = event.event().id();
            if (deliveredAbove.add(id)) {
                gapsSince.remove(id);
                publish(event);
            }
        }
        advanceWatermark(now);
        if (Duration.between(lastHeartbeat, now).compareTo(heartbeatInterval) >= 0) {
            lastHeartbeat = now;
            subscribers.forEach(subscriber -> subscriber.offer(new Item.Heartbeat()));
        }
    }

    private void advanceWatermark(Instant now) {
        while (!deliveredAbove.isEmpty()) {
            var next = watermark + 1;
            if (deliveredAbove.remove(next)) {
                watermark = next;
                continue;
            }
            // A missing id below one already delivered: a transaction that has not committed yet, or never will.
            var since = gapsSince.computeIfAbsent(next, id -> now);
            if (Duration.between(since, now).compareTo(gapTimeout) < 0) {
                return;
            }
            gapsSince.remove(next);
            watermark = next;
        }
    }

    private void publish(ProjectEvent event) {
        for (var subscriber : subscribers) {
            if (!subscriber.caller.canAccessProject(event.projectId())) {
                continue;
            }
            synchronized (subscriber) {
                if (!subscriber.replayed.remove(event.event().id())) {
                    subscriber.offer(new Item.Event(event));
                }
            }
        }
    }

    int subscriberCount() {
        return subscribers.size();
    }

    @Override
    public void start() {
        if (!pollingEnabled) {
            return;
        }
        poller = Thread.ofPlatform().name("job-event-tail").daemon().start(this::tail);
    }

    private void tail() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                poll();
            } catch (RuntimeException e) {
                // The database may be briefly unreachable; the next tick tries again from the same watermark.
                log.atWarn().addKeyValue("error", e.getMessage()).log("Event stream tail failed; retrying");
            }
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void stop() {
        var thread = poller;
        if (thread != null) {
            thread.interrupt();
        }
        poller = null;
        new ArrayList<>(subscribers).forEach(Subscriber::close);
    }

    @Override
    public boolean isRunning() {
        return poller != null;
    }
}
