package com.tasked.modular.shared.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Strongly-typed binding of the {@code tasked.jwt.*} block in application.yaml.
 *
 * <p>{@code @Validated} makes the constraints below <em>boot-time</em> checks: a missing or
 * too-short secret aborts startup instead of producing tokens nobody can trust. HS256 keys
 * shorter than 32 bytes are rejected outright by Nimbus, so {@code @Size(min = 32)} turns a
 * confusing runtime failure into a clear configuration error.
 *
 * <p>The compact constructor enforces the single most important design decision of this
 * pipeline: <strong>the access secret and the refresh secret must differ</strong>. Because
 * each token type is signed with its own key, an access token replayed at
 * {@code /users/refresh-token} — or a refresh token presented as a {@code Bearer} credential —
 * fails signature verification before any application code runs.
 */
@ConfigurationProperties(prefix = "tasked.jwt")
@Validated
public record JwtProperties(

        @NotBlank(message = "tasked.jwt.issuer must be set")
        String issuer,

        @NotBlank(message = "tasked.jwt.audience must be set")
        String audience,

        @NotBlank(message = "tasked.jwt.access-secret must be set")
        @Size(min = 32, message = "tasked.jwt.access-secret must be at least 32 bytes for HS256")
        String accessSecret,

        @NotBlank(message = "tasked.jwt.refresh-secret must be set")
        @Size(min = 32, message = "tasked.jwt.refresh-secret must be at least 32 bytes for HS256")
        String refreshSecret,

        @NotNull(message = "tasked.jwt.access-ttl must be set, e.g. PT15M")
        Duration accessTtl,

        @NotNull(message = "tasked.jwt.refresh-ttl must be set, e.g. P7D")
        Duration refreshTtl) {

    public JwtProperties {
        if (accessSecret != null && accessSecret.equals(refreshSecret)) {
            throw new IllegalStateException(
                    "tasked.jwt.access-secret and tasked.jwt.refresh-secret must be different keys. "
                            + "Sharing one key lets an access token be replayed as a refresh token.");
        }
    }
}
