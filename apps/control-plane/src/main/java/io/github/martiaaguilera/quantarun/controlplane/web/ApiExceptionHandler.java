package io.github.martiaaguilera.quantarun.controlplane.web;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Keeps one error contract for the whole API: every error is Problem Details with a {@code code}. Extending
 * {@link ResponseEntityExceptionHandler} preserves Spring's mapping of framework errors (malformed JSON, wrong
 * method, unsupported media type); this class only adds codes, field violations and a safe 500.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record FieldViolation(String field, String message) {}

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), String.valueOf(error.getDefaultMessage())))
                .toList();
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Once any controller parameter carries a constraint, Spring validates the whole method and reports a
        // @Valid body here as well; its field errors must still be reported per field, not as one "request" error.
        var violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    if (result instanceof ParameterErrors bodyErrors) {
                        return bodyErrors.getFieldErrors().stream()
                                .map(error -> new FieldViolation(
                                        error.getField(), String.valueOf(error.getDefaultMessage())));
                    }
                    var parameterName = result.getMethodParameter().getParameterName();
                    return result.getResolvableErrors().stream()
                            .map(error -> new FieldViolation(
                                    parameterName == null ? "parameter" : parameterName,
                                    String.valueOf(error.getDefaultMessage())));
                })
                .toList();
        return validationProblem(ex, violations, headers, request);
    }

    /**
     * The database is unreachable (no connection within the pool's timeout). That is the server's state, not a bug in
     * the request, so it is a 503 that clients and workers retry with backoff, logged without a stack trace per request.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(RuntimeException ex) {
        log.atWarn().addKeyValue("error", ex.getMessage()).log("Database unavailable; answered 503");
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "The database is unavailable. Retry shortly.");
        problem.setProperty("code", "DATABASE_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(problem);
    }

    /** Unexpected failures: log everything server-side, reveal nothing internal to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception while processing request", ex);
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred. It has been logged.");
        problem.setProperty("code", "INTERNAL_ERROR");
        return ResponseEntity.internalServerError().body(problem);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            problem.setProperty("code", defaultCode(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private @Nullable ResponseEntity<Object> validationProblem(
            Exception ex, List<FieldViolation> violations, HttpHeaders headers, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is invalid.");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("violations", violations);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static String defaultCode(HttpStatusCode statusCode) {
        var resolved = HttpStatus.resolve(statusCode.value());
        return resolved == null ? "HTTP_" + statusCode.value() : resolved.name().toUpperCase(Locale.ROOT);
    }
}
