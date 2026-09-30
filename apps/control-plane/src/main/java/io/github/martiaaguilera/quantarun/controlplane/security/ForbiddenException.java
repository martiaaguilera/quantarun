package io.github.martiaaguilera.quantarun.controlplane.security;

import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import org.springframework.http.HttpStatus;

public class ForbiddenException extends ApiException {

    public ForbiddenException(String detail) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", detail);
    }
}
