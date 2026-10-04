package io.github.martiaaguilera.quantarun.controlplane.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 503 when the database is unreachable, wherever that is noticed: in the authentication filters, which look up
 * credentials before Spring MVC runs and so before any controller advice, or in a transaction that could not start. A
 * 500 would tell workers the request itself was at fault; a 503 with Retry-After tells them to back off and try again,
 * which is what a database outage needs (ENGINEERING_LOG, 2026-10-04).
 */
class DatabaseUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            if (!databaseUnavailable(e) || response.isCommitted()) {
                throw e;
            }
            log.atWarn().addKeyValue("path", request.getRequestURI()).log("Database unavailable; answered 503");
            response.setHeader(HttpHeaders.RETRY_AFTER, "2");
            ProblemResponses.write(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DATABASE_UNAVAILABLE",
                    "The database is unavailable. Retry shortly.");
        }
    }

    static boolean databaseUnavailable(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
