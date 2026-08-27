package com.tasked.modular.shared.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.stereotype.Service;

import com.tasked.modular.shared.enums.Role;

/**
 * HS256 token factory backed by Nimbus JOSE + JWT (pulled in by
 * {@code spring-boot-starter-security-oauth2-resource-server}).
 *
 * <p><strong>Two keys, three cryptographic objects.</strong> The access secret gets an encoder
 * only — its decoder lives in the security filter chain. The refresh secret gets both an
 * encoder and a decoder, because refresh tokens are verified by application code inside the
 * rotation flow rather than by the filter chain.
 *
 * <p>The emitted claim set is {@code sub}, {@code email}, {@code role}, {@code jti},
 * {@code iss}, {@code aud}, {@code iat}, {@code exp}. Note the <em>flat</em> {@code role}
 * claim name — {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter}
 * reads it directly and Java performs no inbound claim remapping.
 */
@Service
public class JwtTokenService implements TokenService {

    private final JwtProperties props;
    private final JwtEncoder accessEncoder;
    private final JwtEncoder refreshEncoder;
    private final JwtDecoder refreshDecoder;

    public JwtTokenService(JwtProperties props) {
        this.props = props;
        this.accessEncoder = encoder(props.accessSecret());
        this.refreshEncoder = encoder(props.refreshSecret());
        this.refreshDecoder = strictDecoder(props.refreshSecret(), props);
    }

    @Override
    public String generateAccessToken(UUID userId, String email, Role role) {
        return generate(userId, email, role, accessEncoder, props.accessTtl());
    }

    @Override
    public String generateRefreshToken(UUID userId, String email, Role role) {
        return generate(userId, email, role, refreshEncoder, props.refreshTtl());
    }

    @Override
    public Jwt validateRefreshToken(String token) {
        return refreshDecoder.decode(token);
    }

    @Override
    public Duration refreshTokenTtl() {
        return props.refreshTtl();
    }

    private String generate(UUID userId, String email, Role role, JwtEncoder encoder, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .audience(List.of(props.audience()))
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role.name())
                .id(UUID.randomUUID().toString())   // jti — unique per token, enables a denylist later
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Signing side. The algorithm is pinned so a caller can never downgrade it. */
    private static JwtEncoder encoder(String secret) {
        return NimbusJwtEncoder.withSecretKey(key(secret))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Verifying side, shared in shape with the access-token decoder in {@code SecurityConfig}.
     *
     * <p>{@code Duration.ZERO} removes the default 60-second leeway: a token is dead at exactly
     * {@code exp}. Pinning {@code macAlgorithm} closes the {@code alg: none} / algorithm-confusion
     * class of attacks.
     */
    public static JwtDecoder strictDecoder(String secret, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(props.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(props.audience()))));
        return decoder;
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
