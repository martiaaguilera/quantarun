package io.github.martiaaguilera.quantarun.worker.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The http workload against a real local server. Loopback is exactly what the SSRF guard blocks, so the tests that
 * need to reach the server allow 127.0.0.1 explicitly, and the guard tests use a workload without that exception.
 */
class HttpWorkloadTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();
    private final HttpWorkload guarded = new HttpWorkload(List.of(), Duration.ofSeconds(1), Clock.systemUTC());
    private final HttpWorkload allowingLoopback =
            new HttpWorkload(List.of("127.0.0.1/32"), Duration.ofSeconds(1), Clock.systemUTC());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            hits.incrementAndGet();
            var body = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/limited", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "7");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/huge", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            var chunk = new byte[64 * 1024];
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 40; i++) {
                    out.write(chunk);
                }
            } catch (IOException clientStoppedReading) {
                // Expected: the workload stops reading at its cap.
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void loopbackLiteral_isBlocked() {
        assertBlocked(guarded, "http://127.0.0.1:" + port + "/ok");
        assertThat(hits).hasValue(0);
    }

    /**
     * A hostname is checked inside the client's resolver, on the addresses it is about to connect to, not by a
     * separate lookup beforehand. This is the path that defeats DNS rebinding, so it gets its own test.
     */
    @Test
    void hostnameResolvingToLoopback_isBlockedAtConnectTime() {
        assertBlocked(guarded, "http://localhost:" + port + "/ok");
        assertThat(hits).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.1/",
                "http://192.168.1.1/",
                "http://172.16.0.1/",
                "http://100.64.0.1/",
                "http://0.0.0.0/",
                "http://[::1]/",
                "http://[fd00::1]/",
                "http://[::ffff:127.0.0.1]/"
            })
    void privateLoopbackAndReservedTargets_areBlocked(String url) {
        assertBlocked(guarded, url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "http://user:pw@example.com/", "not a url"})
    void nonHttpOrCredentialCarryingUrls_areInvalid(String url) {
        assertThatThrownBy(() -> guarded.execute(payload(Map.of("url", url)), context()))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.INVALID_INPUT));
    }

    @Test
    void onlySafeMethodsAreAllowed() {
        assertThatThrownBy(
                        () -> allowingLoopback.execute(payload(Map.of("url", url("/ok"), "method", "POST")), context()))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.INVALID_INPUT));
        assertThat(hits).hasValue(0);
    }

    @Test
    void allowedTarget_returnsStatusSizeAndDigest_butNotTheBody() throws Exception {
        var result = allowingLoopback.execute(payload(Map.of("url", url("/ok"))), context());

        var expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(result)
                .containsEntry("status", 200)
                .containsEntry("bytes", 5L)
                .containsEntry("sha256", expected)
                .containsEntry("truncated", false)
                .doesNotContainKey("body");
    }

    @Test
    void rateLimiting_isClassified_withTheProvidersRetryAfter() {
        assertThatThrownBy(() -> allowingLoopback.execute(payload(Map.of("url", url("/limited"))), context()))
                .isInstanceOfSatisfying(WorkloadFailure.class, failure -> {
                    assertThat(failure.failureClass()).isEqualTo(FailureClass.RATE_LIMITED);
                    assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    void statusCodes_mapToFailureClasses() {
        assertFailure("/down", FailureClass.PROVIDER_UNAVAILABLE);
        assertFailure("/missing", FailureClass.NON_RETRYABLE);
    }

    @Test
    void redirects_areNotFollowed() throws Exception {
        var result = allowingLoopback.execute(payload(Map.of("url", url("/redirect"))), context());

        assertThat(result).containsEntry("status", 302);
    }

    @Test
    void slowTarget_timesOut() {
        assertThatThrownBy(() ->
                        allowingLoopback.execute(payload(Map.of("url", url("/slow"), "timeoutMs", 300)), context()))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(FailureClass.TIMEOUT));
    }

    @Test
    void responseBody_isReadOnlyUpToTheCap() throws Exception {
        var result = allowingLoopback.execute(payload(Map.of("url", url("/huge"))), context());

        assertThat(result)
                .containsEntry("bytes", (long) HttpWorkload.MAX_RESPONSE_BYTES)
                .containsEntry("truncated", true);
    }

    private void assertBlocked(HttpWorkload workload, String url) {
        assertThatThrownBy(() -> workload.execute(payload(Map.of("url", url, "timeoutMs", 1000)), context()))
                .isInstanceOfSatisfying(WorkloadFailure.class, failure -> {
                    assertThat(failure.failureClass()).isEqualTo(FailureClass.INVALID_INPUT);
                    assertThat(failure.getMessage()).contains("not allowed");
                });
    }

    private void assertFailure(String path, FailureClass expected) {
        assertThatThrownBy(() -> allowingLoopback.execute(payload(Map.of("url", url(path))), context()))
                .isInstanceOfSatisfying(
                        WorkloadFailure.class,
                        failure -> assertThat(failure.failureClass()).isEqualTo(expected));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static Payload payload(Map<String, Object> values) {
        return new Payload(values);
    }

    private static AttemptContext context() {
        return AttemptContext.withoutCheckpoints(1);
    }
}
