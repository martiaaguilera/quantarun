package io.github.martiaaguilera.quantarun.controlplane.security.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepository {

    public record StoredKey(UUID id, UUID projectId, byte[] secretHash) {}

    public record KeySummary(
            UUID id, UUID projectId, String prefix, String label, Instant createdAt, Instant revokedAt) {}

    private final JdbcClient jdbc;

    ApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KeySummary insert(UUID projectId, String prefix, byte[] secretHash, String label) {
        return jdbc.sql("""
                        INSERT INTO api_keys (project_id, prefix, secret_hash, label)
                        VALUES (:projectId, :prefix, :hash, :label)
                        RETURNING id, project_id, prefix, label, created_at, revoked_at
                        """)
                .param("projectId", projectId)
                .param("prefix", prefix)
                .param("hash", secretHash)
                .param("label", label)
                .query(KeySummary.class)
                .single();
    }

    public Optional<StoredKey> findActiveByPrefix(String prefix) {
        return jdbc.sql("""
                        SELECT id, project_id, secret_hash FROM api_keys
                        WHERE prefix = :prefix AND revoked_at IS NULL
                        """).param("prefix", prefix).query(StoredKey.class).optional();
    }

    public List<KeySummary> findByProject(UUID projectId) {
        return jdbc.sql("""
                        SELECT id, project_id, prefix, label, created_at, revoked_at FROM api_keys
                        WHERE project_id = :projectId ORDER BY id
                        """)
                .param("projectId", projectId)
                .query(KeySummary.class)
                .list();
    }

    /** Idempotent: revoking an already revoked key keeps its original revocation time. */
    public boolean revoke(UUID projectId, UUID keyId) {
        return jdbc.sql("""
                        UPDATE api_keys SET revoked_at = coalesce(revoked_at, now())
                        WHERE id = :keyId AND project_id = :projectId
                        """).param("keyId", keyId).param("projectId", projectId).update() == 1;
    }
}
