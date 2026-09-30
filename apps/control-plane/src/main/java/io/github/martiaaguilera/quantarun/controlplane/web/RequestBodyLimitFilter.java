package io.github.martiaaguilera.quantarun.controlplane.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps API request bodies. A declared Content-Length over the limit is rejected before reading; chunked
 * bodies without a length are counted while being read, so neither form can make the server buffer an
 * arbitrarily large JSON document.
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public RequestBodyLimitFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            ProblemResponses.write(
                    response,
                    HttpStatus.CONTENT_TOO_LARGE,
                    "REQUEST_TOO_LARGE",
                    "Request body exceeds " + maxBodyBytes + " bytes.");
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, maxBodyBytes), response);
    }

    static final class BodyTooLargeException extends ApiException {
        BodyTooLargeException(long limit) {
            super(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE", "Request body exceeds " + limit + " bytes.");
        }
    }

    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;

        BoundedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            var delegate = super.getInputStream();
            return new ServletInputStream() {
                private long consumed;

                @Override
                public int read() throws IOException {
                    int next = delegate.read();
                    if (next != -1) {
                        count(1);
                    }
                    return next;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int read = delegate.read(buffer, offset, length);
                    if (read > 0) {
                        count(read);
                    }
                    return read;
                }

                private void count(long bytes) {
                    consumed += bytes;
                    if (consumed > limit) {
                        throw new BodyTooLargeException(limit);
                    }
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
