package com.tasked.modular.shared.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

/**
 * Storage-side hashing for refresh tokens.
 *
 * <p><strong>Why two hashes.</strong> BCrypt silently truncates its input at <em>72 bytes</em>.
 * A refresh JWT is ~300+ bytes, so hashing it directly would cover only the header and part of
 * the payload — the signature would never be part of what is stored, and two different tokens
 * sharing a prefix would collide. Pre-hashing with SHA-256 collapses the token to a fixed
 * 64-character hex string that fits comfortably under the limit while depending on every byte
 * of the input.
 *
 * <p>Hex is emitted UPPER CASE and must stay that way: the casing is part of the input to
 * BCrypt, so flipping it would invalidate every stored hash at once.
 *
 * <p>Work factor 10 (not the 11 used for passwords) because this runs on the hot path of every
 * token rotation, and the pre-image here is a 256-bit random-equivalent digest rather than a
 * low-entropy human password — brute-force resistance is already carried by the token itself.
 */
public final class TokenSecurityHelper {

    private static final PasswordEncoder TOKEN_ENCODER = new BCryptPasswordEncoder(10);

    private TokenSecurityHelper() {
    }

    /** {@code BCrypt(SHA256_HEX_UPPER(token))} — what actually lands in {@code users.refresh_token}. */
    public static String doubleHashToken(String rawToken) {
        return TOKEN_ENCODER.encode(sha256Hex(rawToken));
    }

    /** Constant-time-ish comparison performed by BCrypt itself; null/blank inputs never match. */
    public static boolean verifyDoubleHashedToken(String rawToken, String storedHash) {
        if (!StringUtils.hasText(rawToken) || !StringUtils.hasText(storedHash)) {
            return false;
        }
        return TOKEN_ENCODER.matches(sha256Hex(rawToken), storedHash);
    }

    /** 64 uppercase hex characters. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
