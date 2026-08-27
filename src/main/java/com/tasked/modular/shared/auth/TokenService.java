package com.tasked.modular.shared.auth;

import java.time.Duration;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import com.tasked.modular.shared.enums.Role;

/**
 * Mints and verifies the two token types used by the pipeline.
 *
 * <p>Only the <em>refresh</em> token is verified here. Access tokens are verified by the
 * Spring Security filter chain (see {@code SecurityConfig#accessTokenDecoder}) before a
 * request ever reaches a controller, so no application code needs to touch them.
 */
public interface TokenService {

    /** Short-lived credential sent as {@code Authorization: Bearer ...} on every call. */
    String generateAccessToken(UUID userId, String email, Role role);

    /** Long-lived credential, exchanged at {@code POST /users/refresh-token} and rotated on use. */
    String generateRefreshToken(UUID userId, String email, Role role);

    /**
     * Verifies signature (refresh secret), issuer, audience and expiry with zero clock skew.
     *
     * @throws JwtException on any failure — never returns null
     */
    Jwt validateRefreshToken(String token) throws JwtException;

    /** Configured refresh lifetime, so callers can persist a server-side expiry alongside the hash. */
    Duration refreshTokenTtl();
}
