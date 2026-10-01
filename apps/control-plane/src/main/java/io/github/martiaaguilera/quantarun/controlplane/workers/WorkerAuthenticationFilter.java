package io.github.martiaaguilera.quantarun.controlplane.workers;

import io.github.martiaaguilera.quantarun.controlplane.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every worker-protocol request as either the bootstrap token or a registered worker credential. The
 * controller acts on the authenticated worker id, never on a client-supplied one, so one worker cannot heartbeat or
 * report for another.
 */
class WorkerAuthenticationFilter extends OncePerRequestFilter {

    static final String PRINCIPAL_ATTRIBUTE = "quantarun.workerPrincipal";
    private static final String BEARER = "Bearer ";

    private final byte[] bootstrapToken;
    private final WorkerRegistry registry;

    WorkerAuthenticationFilter(WorkerProperties properties, WorkerRegistry registry) {
        this.bootstrapToken = properties.bootstrapToken().getBytes(StandardCharsets.UTF_8);
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var principal = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (principal.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            ProblemResponses.write(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "WORKER_UNAUTHENTICATED",
                    "A worker credential, or the bootstrap token to register, is required.");
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }

    private Optional<WorkerPrincipal> authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return Optional.empty();
        }
        var credential = authorization.substring(BEARER.length()).strip();
        if (MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8), bootstrapToken)) {
            return Optional.of(new WorkerPrincipal.Bootstrap());
        }
        return registry.authenticate(credential).map(WorkerPrincipal.Registered::new);
    }
}
