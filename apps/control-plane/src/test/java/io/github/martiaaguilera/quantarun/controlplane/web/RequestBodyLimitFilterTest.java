package io.github.martiaaguilera.quantarun.controlplane.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The two ways a body arrives. A declared Content-Length over the limit is refused before anything is read (the API
 * test covers that path); a chunked body declares no length, so it is counted while it is read and stopped at the
 * limit, never buffered whole.
 */
class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(1024);

    @Test
    void aBodyWithoutADeclaredLength_isStoppedOnceItPassesTheLimit() {
        var request = chunked(new byte[4096]);
        var read = new long[1];

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    var in = req.getInputStream();
                    var buffer = new byte[256];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        read[0] += n;
                    }
                }))
                .isInstanceOf(RequestBodyLimitFilter.BodyTooLargeException.class);
        assertThat(read[0]).isLessThanOrEqualTo(1024);
    }

    @Test
    void aBodyWithoutADeclaredLength_withinTheLimit_isReadWhole() throws Exception {
        var request = chunked(new byte[1000]);
        var read = new long[1];

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> read[0] = req.getInputStream().readAllBytes().length);

        assertThat(read[0]).isEqualTo(1000);
    }

    private static HttpServletRequest chunked(byte[] body) {
        var request = new MockHttpServletRequest("POST", "/api/v1/jobs");
        request.setContent(body);
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
    }
}
