package io.github.martiaaguilera.quantarun.controlplane.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param adminToken operator credential for project administration and cross-project reads. No default:
 *     startup fails if it is missing or short, rather than running with a guessable token.
 */
@Validated
@ConfigurationProperties("quantarun.security")
record SecurityProperties(@NotBlank @Size(min = 32) String adminToken) {

    // Records print every component; this one must never reach a log line.
    @Override
    public String toString() {
        return "SecurityProperties[adminToken=<redacted>]";
    }
}
