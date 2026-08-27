package com.tasked.modular.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.tasked.modular.shared.enums.Role;

/**
 * Proves the security properties of the token layer without any Spring context: key
 * separation, strict expiry, and issuer/audience binding.
 */
class JwtTokenServiceTest {

    private static final String ACCESS_SECRET = "unit-test-access-secret-value-0123456789abcdef";
    private static final String REFRESH_SECRET = "unit-test-refresh-secret-value-0123456789abcdef";

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "a@b.com";

    private JwtProperties props;
    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        props = properties(Duration.ofMinutes(15), Duration.ofDays(7));
        tokenService = new JwtTokenService(props);
    }

    private static JwtProperties properties(Duration accessTtl, Duration refreshTtl) {
        return new JwtProperties("tasked-api", "tasked-app", ACCESS_SECRET, REFRESH_SECRET,
                accessTtl, refreshTtl);
    }

    @Test
    @DisplayName("an access token carries sub, email, role, jti, iss and aud")
    void accessTokenClaimSet() {
        String token = tokenService.generateAccessToken(USER_ID, EMAIL, Role.ADMIN);

        Jwt decoded = JwtTokenService.strictDecoder(ACCESS_SECRET, props).decode(token);

        assertThat(decoded.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(decoded.getClaimAsString("email")).isEqualTo(EMAIL);
        assertThat(decoded.getClaimAsString("role")).isEqualTo("ADMIN");
        assertThat(decoded.getId()).isNotBlank();
        // getIssuer() coerces the claim to a URL; this issuer is a plain name, so read it raw.
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("tasked-api");
        assertThat(decoded.getAudience()).containsExactly("tasked-app");
        assertThat(decoded.getExpiresAt()).isNotNull();
        assertThat(decoded.getIssuedAt()).isNotNull();
    }

    /**
     * The core of the two-secret design: neither token type can stand in for the other, and
     * the failure is cryptographic rather than a convention anyone could forget to enforce.
     */
    @Test
    @DisplayName("an access token is rejected by the refresh decoder, and vice versa")
    void tokenTypesAreNotInterchangeable() {
        String accessToken = tokenService.generateAccessToken(USER_ID, EMAIL, Role.USER);
        String refreshToken = tokenService.generateRefreshToken(USER_ID, EMAIL, Role.USER);

        // access token presented at the refresh endpoint
        assertThatThrownBy(() -> tokenService.validateRefreshToken(accessToken))
                .isInstanceOf(JwtException.class);

        // refresh token presented as a Bearer credential
        assertThatThrownBy(() -> JwtTokenService.strictDecoder(ACCESS_SECRET, props).decode(refreshToken))
                .isInstanceOf(JwtException.class);

        // and the refresh token does verify against its own decoder
        assertThatCode(() -> tokenService.validateRefreshToken(refreshToken)).doesNotThrowAnyException();
    }

    /**
     * Zero clock skew: no 60-second grace period after {@code exp}.
     *
     * <p>The token is minted by hand because {@code JwtClaimsSet} refuses to build a claim set
     * whose {@code exp} precedes its {@code iat}. Signing a backdated pair directly is the only
     * way to test expiry without sleeping.
     */
    @Test
    @DisplayName("an already-expired token fails validation immediately")
    void expiredTokenIsRejected() {
        Instant now = Instant.now();
        JwtClaimsSet expiredClaims = JwtClaimsSet.builder()
                .issuer("tasked-api")
                .audience(List.of("tasked-app"))
                .subject(USER_ID.toString())
                .claim("email", EMAIL)
                .claim("role", Role.USER.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(now.minus(Duration.ofHours(2)))
                .expiresAt(now.minus(Duration.ofHours(1)))   // one hour past due
                .build();

        String alreadyExpired = NimbusJwtEncoder
                .withSecretKey(new SecretKeySpec(REFRESH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256)
                .build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), expiredClaims))
                .getTokenValue();

        assertThatThrownBy(() -> tokenService.validateRefreshToken(alreadyExpired))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token from a different issuer is rejected")
    void wrongIssuerIsRejected() {
        JwtTokenService otherIssuer = new JwtTokenService(new JwtProperties(
                "someone-else", "tasked-app", ACCESS_SECRET, REFRESH_SECRET,
                Duration.ofMinutes(15), Duration.ofDays(7)));
        String foreign = otherIssuer.generateRefreshToken(USER_ID, EMAIL, Role.USER);

        assertThatThrownBy(() -> tokenService.validateRefreshToken(foreign))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token for a different audience is rejected")
    void wrongAudienceIsRejected() {
        JwtTokenService otherAudience = new JwtTokenService(new JwtProperties(
                "tasked-api", "another-app", ACCESS_SECRET, REFRESH_SECRET,
                Duration.ofMinutes(15), Duration.ofDays(7)));
        String foreign = otherAudience.generateRefreshToken(USER_ID, EMAIL, Role.USER);

        assertThatThrownBy(() -> tokenService.validateRefreshToken(foreign))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a tampered signature is rejected")
    void tamperedTokenIsRejected() {
        String token = tokenService.generateRefreshToken(USER_ID, EMAIL, Role.USER);
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> tokenService.validateRefreshToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("configuring one secret for both token types fails fast at construction")
    void identicalSecretsAreRejected() {
        assertThatThrownBy(() -> new JwtProperties("tasked-api", "tasked-app",
                ACCESS_SECRET, ACCESS_SECRET, Duration.ofMinutes(15), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    @DisplayName("every token gets a distinct jti")
    void jtiIsUniquePerToken() {
        Jwt first = JwtTokenService.strictDecoder(ACCESS_SECRET, props)
                .decode(tokenService.generateAccessToken(USER_ID, EMAIL, Role.USER));
        Jwt second = JwtTokenService.strictDecoder(ACCESS_SECRET, props)
                .decode(tokenService.generateAccessToken(USER_ID, EMAIL, Role.USER));

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}
