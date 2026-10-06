package io.github.martiaaguilera.quantarun.worker.execution;

import io.github.martiaaguilera.quantarun.protocol.WorkerProtocol.FailureClass;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Calls an external HTTP endpoint, the stand-in for a model provider or any remote API.
 *
 * <p><b>SSRF guard.</b> The target's addresses are filtered inside the HTTP client's DNS resolver (Boot's
 * {@link InetAddressFilter} on Apache HttpClient 5), so the check applies to the very addresses the connection uses.
 * Checking a hostname first and connecting afterwards would let DNS rebinding swap in a private address between the
 * two. Loopback, private, link-local (cloud metadata), CGNAT, multicast and other special-purpose ranges are refused;
 * an operator can allow specific internal IPs or CIDRs (never hostnames, which can be rebound). Redirects are not
 * followed, so a public URL cannot bounce the worker to an internal one.
 *
 * <p>Only GET and HEAD are allowed. Execution is at-least-once (FAILURE_SEMANTICS.md), so a workload must tolerate
 * running twice; safe methods do by definition.
 */
final class HttpWorkload implements Workload {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HttpWorkload.class);

    static final int MAX_URL_LENGTH = 2048;
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final long MAX_TIMEOUT_MS = 30_000;
    private static final long DEFAULT_TIMEOUT_MS = 10_000;

    private final InetAddressFilter allowedTargets;
    private final Duration connectTimeout;
    private final Clock clock;
    private final ProviderCalls providerCalls;

    /** @param allowedPrivateAddresses IPs or CIDR ranges allowed despite being internal, for local demos and tests. */
    HttpWorkload(
            List<String> allowedPrivateAddresses, Duration connectTimeout, Clock clock, ProviderCalls providerCalls) {
        var filter = InetAddressFilter.externalAddresses();
        if (!allowedPrivateAddresses.isEmpty()) {
            filter = filter.or(allowedPrivateAddresses.toArray(String[]::new));
        }
        this.allowedTargets = filter;
        this.connectTimeout = connectTimeout;
        this.clock = clock;
        this.providerCalls = providerCalls;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public Map<String, Object> execute(Payload payload, AttemptContext context) throws InterruptedException {
        var uri = target(payload.requireString("url", MAX_URL_LENGTH));
        var method = method(payload.optionalString("method", 8).orElse("GET"));
        var timeout = Duration.ofMillis(
                payload.optionalLong("timeoutMs", 100, MAX_TIMEOUT_MS).orElse(DEFAULT_TIMEOUT_MS));
        rejectBlockedLiteral(uri.getHost());

        var factory = ClientHttpRequestFactoryBuilder.httpComponents()
                .withHttpClientCustomizer(client -> client
                        // Left on, the client sleeps out a 429's or 503's Retry-After itself (however long the target
                        // asks), holding the worker slot, then sends the request again. The retry policy decides.
                        .disableAutomaticRetries()
                        // Always connect directly. Through a proxy, the address filter would vet the proxy's address,
                        // not the target's.
                        .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE)))
                .build(HttpClientSettings.defaults()
                        .withTimeouts(connectTimeout, timeout)
                        .withRedirects(HttpRedirects.DONT_FOLLOW)
                        .withInetAddressFilter(allowedTargets));
        try {
            return providerCalls.observe(type(), () -> call(factory, uri, method));
        } finally {
            close(factory);
        }
    }

    private Map<String, Object> call(ClientHttpRequestFactory factory, URI uri, HttpMethod method)
            throws InterruptedException {
        try (var response = factory.createRequest(uri, method).execute()) {
            var status = response.getStatusCode().value();
            if (status == 429) {
                throw new WorkloadFailure(
                        FailureClass.RATE_LIMITED,
                        "HTTP 429 from the target",
                        retryAfter(response.getHeaders().getFirst("Retry-After")));
            }
            if (status == 408) {
                throw new WorkloadFailure(FailureClass.TIMEOUT, "HTTP 408 from the target");
            }
            if (status == 502 || status == 503 || status == 504) {
                throw new WorkloadFailure(FailureClass.PROVIDER_UNAVAILABLE, "HTTP " + status + " from the target");
            }
            if (status >= 500) {
                throw new WorkloadFailure(FailureClass.TRANSIENT, "HTTP " + status + " from the target");
            }
            if (status >= 400) {
                // The same request will get the same answer: retrying only burns budget.
                throw new WorkloadFailure(FailureClass.NON_RETRYABLE, "HTTP " + status + " from the target");
            }
            return digestBody(response.getBody(), status);
        } catch (FilteredHostException e) {
            throw blocked();
        } catch (SocketTimeoutException e) {
            throw new WorkloadFailure(FailureClass.TIMEOUT, "the target did not answer in time");
        } catch (InterruptedIOException e) {
            throw new InterruptedException("http call interrupted");
        } catch (UnknownHostException e) {
            throw new WorkloadFailure(FailureClass.TRANSIENT, "the target's host name did not resolve");
        } catch (IOException e) {
            if (causedByFilter(e)) {
                throw blocked();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("http call interrupted");
            }
            throw new WorkloadFailure(FailureClass.TRANSIENT, "connection to the target failed");
        }
    }

    private static Map<String, Object> digestBody(java.io.InputStream body, int status) throws IOException {
        var sha256 = sha256();
        var buffer = new byte[8192];
        long total = 0;
        var truncated = false;
        int read;
        while ((read = body.read(buffer)) != -1) {
            var usable = (int) Math.min(read, MAX_RESPONSE_BYTES - total);
            sha256.update(buffer, 0, usable);
            total += usable;
            if (total >= MAX_RESPONSE_BYTES) {
                truncated = true;
                break;
            }
        }
        // The body itself is not reported: it is unbounded third-party content and may be sensitive.
        var result = new LinkedHashMap<String, Object>();
        result.put("status", status);
        result.put("bytes", total);
        result.put("sha256", HexFormat.of().formatHex(sha256.digest()));
        result.put("truncated", truncated);
        return result;
    }

    private static URI target(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw WorkloadFailure.invalidInput("'url' is not a valid URI");
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw WorkloadFailure.invalidInput("'url' must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw WorkloadFailure.invalidInput("'url' must name a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw WorkloadFailure.invalidInput("'url' must not carry credentials");
        }
        return uri;
    }

    private static HttpMethod method(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "GET" -> HttpMethod.GET;
            case "HEAD" -> HttpMethod.HEAD;
            default -> throw WorkloadFailure.invalidInput("'method' must be GET or HEAD");
        };
    }

    /**
     * Defence in depth for IP literals. HttpClient 5.6 passes literals through the filtered resolver as well (with this
     * check disabled, every literal case in HttpWorkloadTest is still blocked), but refusing a literal here keeps the
     * guarantee independent of how a future client version treats them.
     */
    private void rejectBlockedLiteral(String host) {
        var literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException notALiteral) {
            return;
        }
        if (!allowedTargets.matches(address)) {
            throw blocked();
        }
    }

    private static boolean causedByFilter(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof FilteredHostException) {
                return true;
            }
        }
        return false;
    }

    private static WorkloadFailure blocked() {
        // INVALID_INPUT: the same target is refused on every worker, so a retry cannot succeed.
        return WorkloadFailure.invalidInput("the target address is not allowed (private, loopback or reserved)");
    }

    /** Retry-After is either delta-seconds or an HTTP-date (RFC 9110 §10.2.3); anything else is ignored. */
    private @Nullable Duration retryAfter(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(header.strip())));
        } catch (NumberFormatException notSeconds) {
            try {
                var at = ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
                var wait = Duration.between(clock.instant(), at.toInstant());
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    /** Releases this call's connection pool. HttpComponents' factory exposes that only through DisposableBean. */
    private static void close(ClientHttpRequestFactory factory) {
        if (factory instanceof org.springframework.beans.factory.DisposableBean disposable) {
            try {
                disposable.destroy();
            } catch (Exception e) {
                log.atDebug().addKeyValue("error", e.getMessage()).log("Closing the http workload's client failed");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
