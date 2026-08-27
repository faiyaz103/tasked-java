package com.tasked.modular.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks in the two properties the refresh-token storage scheme depends on: that the SHA-256
 * pre-hash actually matters, and that the hex encoding stays uppercase.
 */
class TokenSecurityHelperTest {

    @Test
    @DisplayName("sha256Hex returns 64 uppercase hex characters")
    void sha256HexShape() {
        String hex = TokenSecurityHelper.sha256Hex("anything");

        assertThat(hex).hasSize(64);
        assertThat(hex).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("a double-hashed token verifies against its own hash")
    void roundTrip() {
        String token = "header.payload.signature";
        String stored = TokenSecurityHelper.doubleHashToken(token);

        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(token, stored)).isTrue();
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(token + "x", stored)).isFalse();
    }

    @Test
    @DisplayName("BCrypt salts each call, so the same token hashes differently every time")
    void hashesAreSalted() {
        String token = "header.payload.signature";

        assertThat(TokenSecurityHelper.doubleHashToken(token))
                .isNotEqualTo(TokenSecurityHelper.doubleHashToken(token));
    }

    /**
     * The reason the SHA-256 pre-hash exists. BCrypt truncates at 72 bytes, so without it
     * these two tokens - identical for the first 100 characters - would produce interchangeable
     * hashes and either would satisfy the reuse check.
     */
    @Test
    @DisplayName("tokens differing only after byte 72 are still distinguished")
    void differencesBeyondBcryptTruncationPointStillMatter() {
        String prefix = "A".repeat(100);
        String tokenA = prefix + "aaaaaaaaaa";
        String tokenB = prefix + "bbbbbbbbbb";

        String storedA = TokenSecurityHelper.doubleHashToken(tokenA);

        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(tokenA, storedA)).isTrue();
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(tokenB, storedA)).isFalse();
        assertThat(TokenSecurityHelper.sha256Hex(tokenA)).isNotEqualTo(TokenSecurityHelper.sha256Hex(tokenB));
    }

    @Test
    @DisplayName("null or blank inputs never match")
    void blankInputsNeverMatch() {
        String stored = TokenSecurityHelper.doubleHashToken("token");

        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(null, stored)).isFalse();
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken("  ", stored)).isFalse();
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken("token", null)).isFalse();
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken("token", "")).isFalse();
    }
}
