package io.github.martiaaguilera.quantarun.controlplane.security;

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
 * Resolves the {@link Caller} for every {@code /api/**} request from an {@code Authorization: Bearer} header,
 * which carries either the admin token or a project API key. Unauthenticated requests stop here with 401, so no
 * controller ever runs without an identity.
 */
class BearerAuthenticationFilter extends OncePerRequestFilter {

    static final String CALLER_ATTRIBUTE = BearerAuthenticationFilter.class.getName() + ".caller";
    private static final String BEARER = "Bearer ";

    private final byte[] adminToken;
    private final ApiKeys apiKeys;

    BearerAuthenticationFilter(SecurityProperties properties, ApiKeys apiKeys) {
        this.adminToken = properties.adminToken().getBytes(StandardCharsets.UTF_8);
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var caller = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (caller.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            ProblemResponses.write(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED",
                    "A valid admin token or project API key is required.");
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    private Optional<Caller> authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return Optional.empty();
        }
        var credential = authorization.substring(BEARER.length()).strip();
        if (MessageDigest.isEqual(credential.getBytes(StandardCharsets.UTF_8), adminToken)) {
            return Optional.of(new Caller.Admin());
        }
        return apiKeys.verify(credential).map(Caller.ProjectMember::new);
    }
}
