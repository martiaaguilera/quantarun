package io.github.martiaaguilera.quantarun.controlplane.security.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Key layout: {@code qr_<prefix>_<secret>}.
 *
 * <ul>
 *   <li>prefix: 8 hex chars, stored in clear, used only to find the row;
 *   <li>secret: 32 random bytes, base64url, never stored.
 * </ul>
 *
 * The whole key is hashed, so a leaked prefix reveals nothing. The {@code qr_} marker makes leaked keys easy for
 * secret scanners to recognise.
 */
public final class ApiKeyFormat {

    private static final Pattern KEY_PATTERN = Pattern.compile("^qr_([0-9a-f]{8})_([A-Za-z0-9_-]{43})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record GeneratedKey(String prefix, String plaintext, byte[] hash) {}

    private ApiKeyFormat() {}

    public static GeneratedKey generate() {
        var prefixBytes = new byte[4];
        var secretBytes = new byte[32];
        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secretBytes);
        var prefix = HexFormat.of().formatHex(prefixBytes);
        var secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        var plaintext = "qr_" + prefix + "_" + secret;
        return new GeneratedKey(prefix, plaintext, sha256(plaintext));
    }

    /** Returns the lookup prefix, or empty when the value cannot be a QuantaRun key. */
    public static Optional<String> prefixOf(String presented) {
        var matcher = KEY_PATTERN.matcher(presented);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static boolean matches(String presented, byte[] storedHash) {
        // Constant-time comparison: timing must not reveal how many leading bytes of the hash were correct.
        return MessageDigest.isEqual(sha256(presented), storedHash);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
