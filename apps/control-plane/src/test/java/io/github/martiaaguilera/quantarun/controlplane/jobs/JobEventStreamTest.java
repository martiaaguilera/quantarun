package io.github.martiaaguilera.quantarun.controlplane.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.martiaaguilera.quantarun.controlplane.ApiTestSupport;
import io.github.martiaaguilera.quantarun.controlplane.IntegrationTest;
import io.github.martiaaguilera.quantarun.controlplane.jobs.internal.JobEventRepository;
import io.github.martiaaguilera.quantarun.controlplane.security.Caller;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The console's live feed: one tail of job_events, fanned out per caller, resumable, never skipping a late commit. */
@IntegrationTest
class JobEventStreamTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JobEventRepository repository;

    @Autowired
    JobEventStream sharedStream;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private final List<Runnable> subscriptions = new ArrayList<>();
    private Instant now = Instant.parse("2026-10-04T12:00:00Z");
    private JobEventStream stream;
    private UUID projectA;
    private UUID projectB;
    private UUID jobA;
    private UUID jobB;

    @BeforeEach
    void setUp() {
        stream = new JobEventStream(
                repository,
                false,
                Duration.ofMillis(250),
                Duration.ofSeconds(5),
                Duration.ofHours(1),
                50,
                3,
                () -> now);
        projectA = project("stream-a");
        projectB = project("stream-b");
        jobA = job(projectA);
        jobB = job(projectB);
        stream.poll();
    }

    @AfterEach
    void unsubscribe() {
        subscriptions.forEach(Runnable::run);
    }

    @Test
    void eachCallerSeesItsOwnProjects_inIdOrder() {
        var member = subscribe(new Caller.ProjectMember(projectA), null);
        var admin = subscribe(new Caller.Admin(), null);

        var first = event(jobA, "SUBMITTED");
        var other = event(jobB, "SUBMITTED");
        var second = event(jobA, "SCHEDULED");
        stream.poll();

        assertThat(member.awaitIds(2)).containsExactly(first, second);
        assertThat(admin.awaitIds(3)).containsExactly(first, other, second);
    }

    /** A reconnecting client gets what it missed first, then the live feed, and nothing twice. */
    @Test
    void lastEventId_replaysWhatWasMissed_withoutDuplicates() {
        var seen = event(jobA, "SUBMITTED");
        stream.poll();
        var missed1 = event(jobA, "SCHEDULED");
        var missed2 = event(jobA, "STARTED");

        var client = subscribe(new Caller.ProjectMember(projectA), seen);
        stream.poll();
        var live = event(jobA, "SUCCEEDED");
        stream.poll();

        assertThat(client.awaitIds(3)).containsExactly(missed1, missed2, live);
    }

    /**
     * The case the watermark exists for: an event whose id was assigned first commits after a later one. A tail that
     * moved its cursor to the largest id seen would never deliver it.
     */
    @Test
    void anEventThatCommitsLate_isStillDelivered() throws Exception {
        var client = subscribe(new Caller.Admin(), null);
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var lateId = new AtomicReference<Long>();
        var slow = Thread.ofVirtual()
                .start(() -> transactions.executeWithoutResult(tx -> {
                    lateId.set(event(jobA, "SCHEDULED"));
                    inserted.countDown();
                    try {
                        commit.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        var early = event(jobA, "STARTED");
        assertThat(early).isGreaterThan(lateId.get());

        stream.poll();
        assertThat(client.awaitIds(1)).containsExactly(early);
        commit.countDown();
        slow.join();
        stream.poll();

        assertThat(client.awaitIds(2)).containsExactly(early, lateId.get());
    }

    /** An id that never commits (a rolled-back insert) holds the tail back only for the gap timeout. */
    @Test
    void aRolledBackId_isSkippedAfterTheGapTimeout() {
        var client = subscribe(new Caller.Admin(), null);
        transactions.executeWithoutResult(tx -> {
            event(jobA, "SCHEDULED");
            tx.setRollbackOnly();
        });
        var after = event(jobA, "STARTED");
        stream.poll();
        assertThat(client.awaitIds(1)).containsExactly(after);

        now = now.plusSeconds(6);
        stream.poll();
        var next = event(jobA, "SUCCEEDED");
        stream.poll();

        assertThat(client.awaitIds(2)).containsExactly(after, next);
    }

    @Test
    void missingTooMuch_asksTheClientToReload() {
        var seen = event(jobA, "SUBMITTED");
        bulkEvents(jobA, JobEventStream.REPLAY_LIMIT + 1);

        var client = subscribe(new Caller.Admin(), seen);

        client.awaitItems(1);
        assertThat(client.items.getFirst()).isInstanceOf(JobEventStream.Item.Reset.class);
    }

    /** A client that stops reading is disconnected instead of growing a queue without bound. */
    @Test
    void aSlowSubscriber_isDisconnected_andTheOthersKeepReceiving() throws Exception {
        var stuck = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        subscriptions.add(stream.subscribe(new Caller.Admin(), null, new JobEventStream.Sink() {
            @Override
            public void send(JobEventStream.Item item) throws java.io.IOException {
                try {
                    stuck.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted");
                }
            }

            @Override
            public void close() {
                closed.countDown();
            }
        }));
        var healthy = subscribe(new Caller.Admin(), null);

        bulkEvents(jobA, JobEventStream.QUEUE_CAPACITY + 10);
        for (int i = 0; i < 3; i++) {
            stream.poll();
        }

        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(healthy.awaitItems(JobEventStream.QUEUE_CAPACITY + 10)).hasSize(JobEventStream.QUEUE_CAPACITY + 10);
        assertThat(stream.subscriberCount()).isEqualTo(1);
        stuck.countDown();
    }

    /**
     * One project key may hold a bounded number of streams, so a tenant cannot take every slot from the operator's
     * console (Phase 14 review). Here the per-project limit is 3 and the total 50.
     */
    @Test
    void aProject_cannotHoldMoreThanItsShareOfStreams_butTheOperatorStillConnects() {
        var member = new Caller.ProjectMember(projectA);
        for (int i = 0; i < 3; i++) {
            subscribe(member, null);
        }

        assertThatThrownBy(() -> subscribe(member, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3 open event streams");
        subscribe(new Caller.ProjectMember(projectB), null);
        subscribe(new Caller.Admin(), null);
    }

    /** Over HTTP: text/event-stream, the job events with their ids, and only the caller's own. */
    @Test
    void theEndpoint_streamsTheCallersEvents() throws Exception {
        var support = new ApiTestSupport(mvc, json);
        var project = support.createProject();
        var job = job(project.id());
        var before = repository.maxId();
        sharedStream.poll();

        var result = mvc.perform(get("/api/v1/events/stream")
                        .header(HttpHeaders.AUTHORIZATION, project.bearer())
                        .header("Last-Event-ID", String.valueOf(before)))
                .andExpect(request().asyncStarted())
                .andReturn();
        // The stream opens with a comment at once, so the client knows it is connected before any event or heartbeat.
        assertThat(result.getResponse().getContentAsString()).startsWith(":connected");
        var mine = event(job, "SUBMITTED");
        event(jobB, "SUBMITTED");
        sharedStream.poll();

        // An event is written in pieces; wait for the blank line that ends it.
        var body = awaitEvent(result, "id:" + mine);
        assertThat(body).contains("id:" + mine);
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(body)
                .contains("event:job", "\"jobId\":\"" + job + "\"", "\"type\":\"SUBMITTED\"")
                .doesNotContain(jobB.toString());
        mvc.perform(get("/api/v1/events/stream")).andExpect(status().isUnauthorized());
    }

    private String awaitEvent(org.springframework.test.web.servlet.MvcResult result, String idLine) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var body = result.getResponse().getContentAsString();
        while (!(body.contains(idLine) && body.indexOf("\n\n", body.indexOf(idLine)) > 0)
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            body = result.getResponse().getContentAsString();
        }
        return body;
    }

    private RecordingSink subscribe(Caller caller, Long lastEventId) {
        var sink = new RecordingSink();
        subscriptions.add(stream.subscribe(caller, lastEventId, sink));
        return sink;
    }

    private UUID project(String name) {
        return jdbc.sql("INSERT INTO projects (name) VALUES (:name) RETURNING id")
                .param("name", name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .query(UUID.class)
                .single();
    }

    private UUID job(UUID project) {
        return jdbc.sql("""
                        INSERT INTO jobs (project_id, workload_type, payload, status, cpu_millis, memory_mib,
                                          max_attempts, timeout_seconds)
                        VALUES (:p, 'delay', '{}', 'QUEUED', 100, 64, 3, 60) RETURNING id
                        """).param("p", project).query(UUID.class).single();
    }

    private long event(UUID job, String type) {
        return jdbc.sql("INSERT INTO job_events (job_id, type) VALUES (:job, :type) RETURNING id")
                .param("job", job)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private void bulkEvents(UUID job, int count) {
        jdbc.sql("INSERT INTO job_events (job_id, type) SELECT :job, 'SUBMITTED' FROM generate_series(1, :n)")
                .param("job", job)
                .param("n", count)
                .update();
    }

    static final class RecordingSink implements JobEventStream.Sink {
        final List<JobEventStream.Item> items = new CopyOnWriteArrayList<>();

        @Override
        public void send(JobEventStream.Item item) {
            items.add(item);
        }

        @Override
        public void close() {}

        List<JobEventStream.Item> awaitItems(int count) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (items.size() < count && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            return List.copyOf(items);
        }

        List<Long> awaitIds(int count) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<Long> ids = List.of();
            while (System.nanoTime() < deadline) {
                ids = items.stream()
                        .filter(JobEventStream.Item.Event.class::isInstance)
                        .map(item -> ((JobEventStream.Item.Event) item)
                                .event()
                                .event()
                                .id())
                        .toList();
                if (ids.size() >= count) {
                    break;
                }
                Thread.onSpinWait();
            }
            return ids;
        }
    }
}
