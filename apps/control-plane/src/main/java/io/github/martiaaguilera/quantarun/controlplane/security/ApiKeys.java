package io.github.martiaaguilera.quantarun.controlplane.security;

import io.github.martiaaguilera.quantarun.controlplane.security.internal.ApiKeyRepository;
import io.github.martiaaguilera.quantarun.controlplane.security.internal.ApiKeyRepository.KeySummary;
import io.github.martiaaguilera.quantarun.controlplane.web.ApiException;
import io.github.martiaaguilera.quantarun.controlplane.web.SecretTokens;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Issues, lists, revokes and verifies project API keys. The plaintext key leaves this class exactly once. */
@Component
public class ApiKeys {

    public record IssuedKey(KeySummary key, String plaintext) {}

    private static final SecretTokens API_KEYS = new SecretTokens("qr");

    private final ApiKeyRepository repository;

    ApiKeys(ApiKeyRepository repository) {
        this.repository = repository;
    }

    public IssuedKey issue(UUID projectId, String label) {
        var generated = API_KEYS.generate();
        try {
            var summary = repository.insert(projectId, generated.prefix(), generated.hash(), label);
            return new IssuedKey(summary, generated.plaintext());
        } catch (DataIntegrityViolationException e) {
            // A collision on the 32-bit prefix is possible in principle; a missing project is the realistic cause.
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project " + projectId + " not found.");
        }
    }

    public List<KeySummary> list(UUID projectId) {
        return repository.findByProject(projectId);
    }

    public void revoke(UUID projectId, UUID keyId) {
        if (!repository.revoke(projectId, keyId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API key " + keyId + " not found.");
        }
    }

    /** Returns the project owning a valid, unrevoked key. Any malformed or unknown key is simply rejected. */
    Optional<UUID> verify(String presented) {
        return API_KEYS.prefixOf(presented)
                .flatMap(repository::findActiveByPrefix)
                .filter(stored -> SecretTokens.matches(presented, stored.secretHash()))
                .map(ApiKeyRepository.StoredKey::projectId);
    }
}
