package io.github.martiaaguilera.quantarun.controlplane.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Bearer secrets of the form {@code <marker>_<prefix>_<secret>}: project API keys ({@code qr}) and worker credentials
 * ({@code qw}).
 *
 * <ul>
 *   <li>prefix: 8 hex chars, stored in clear, used only to find the row;
 *   <li>secret: 32 random bytes, base64url, never stored.
 * </ul>
 *
 * Only the SHA-256 of the whole token is stored. For 256-bit random secrets a slow password hash adds nothing: there
 * is no dictionary to brute-force. The marker lets secret scanners recognise a leaked token.
 */
public final class SecretTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record GeneratedToken(String prefix, String plaintext, byte[] hash) {}

    private final String marker;
    private final Pattern pattern;

    public SecretTokens(String marker) {
        this.marker = marker;
        this.pattern = Pattern.compile("^" + Pattern.quote(marker) + "_([0-9a-f]{8})_([A-Za-z0-9_-]{43})$");
    }

    public GeneratedToken generate() {
        var prefixBytes = new byte[4];
        var secretBytes = new byte[32];
        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secretBytes);
        var prefix = HexFormat.of().formatHex(prefixBytes);
        var secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        var plaintext = marker + "_" + prefix + "_" + secret;
        return new GeneratedToken(prefix, plaintext, sha256(plaintext));
    }

    /** The lookup prefix, or empty when the value cannot be a token of this kind. */
    public Optional<String> prefixOf(String presented) {
        var matcher = pattern.matcher(presented);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static boolean matches(String presented, byte[] storedHash) {
        // Constant-time comparison: timing must not reveal how many leading bytes of the hash were correct.
        return MessageDigest.isEqual(sha256(presented), storedHash);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
