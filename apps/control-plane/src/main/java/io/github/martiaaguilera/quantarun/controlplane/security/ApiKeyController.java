package io.github.martiaaguilera.quantarun.controlplane.security;

import io.github.martiaaguilera.quantarun.controlplane.security.internal.ApiKeyRepository.KeySummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-keys")
class ApiKeyController {

    record IssueKeyRequest(@NotBlank @Size(max = 100) String label) {}

    record ApiKeyResponse(UUID id, String prefix, String label, Instant createdAt, Instant revokedAt) {
        static ApiKeyResponse from(KeySummary key) {
            return new ApiKeyResponse(key.id(), key.prefix(), key.label(), key.createdAt(), key.revokedAt());
        }
    }

    /** The only response that ever contains the plaintext key. */
    record IssuedKeyResponse(ApiKeyResponse key, String secret) {}

    private final ApiKeys apiKeys;

    ApiKeyController(ApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IssuedKeyResponse issue(Caller caller, @PathVariable UUID projectId, @Valid @RequestBody IssueKeyRequest request) {
        caller.requireAdmin();
        var issued = apiKeys.issue(projectId, request.label());
        return new IssuedKeyResponse(ApiKeyResponse.from(issued.key()), issued.plaintext());
    }

    @GetMapping
    List<ApiKeyResponse> list(Caller caller, @PathVariable UUID projectId) {
        caller.requireAdmin();
        return apiKeys.list(projectId).stream().map(ApiKeyResponse::from).toList();
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(Caller caller, @PathVariable UUID projectId, @PathVariable UUID keyId) {
        caller.requireAdmin();
        apiKeys.revoke(projectId, keyId);
    }
}
