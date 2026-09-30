package io.github.martiaaguilera.quantarun.controlplane.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Base for expected, client-visible failures. Rendered as RFC 9457 Problem Details with a stable {@code code}
 * extension, so clients branch on the code rather than parsing human-readable text.
 */
public class ApiException extends ErrorResponseException {

    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(status, problem(status, code, detail), null);
        this.code = code;
    }

    public String code() {
        return code;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
