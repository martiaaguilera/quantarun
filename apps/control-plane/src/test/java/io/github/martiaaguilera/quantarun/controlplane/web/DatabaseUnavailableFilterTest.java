package io.github.martiaaguilera.quantarun.controlplane.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.CannotCreateTransactionException;

class DatabaseUnavailableFilterTest {

    private final DatabaseUnavailableFilter filter = new DatabaseUnavailableFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/worker-api/v1/heartbeat");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void aCredentialLookupThatCannotReachTheDatabase_isA503WithRetryAfter() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection");
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getContentAsString()).contains("\"code\":\"DATABASE_UNAVAILABLE\"");
    }

    @Test
    void aTransactionThatCannotStart_wrappedByTheServlet_isA503Too() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            throw new ServletException(new CannotCreateTransactionException("Could not open JDBC Connection"));
        });

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void anyOtherFailure_isLeftAlone() {
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                    throw new IllegalStateException("a bug");
                }))
                .isInstanceOf(IllegalStateException.class);
    }
}
