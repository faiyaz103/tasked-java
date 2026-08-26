package com.tasked.modular.shared.config;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.tasked.modular.shared.auth.JwtProperties;
import com.tasked.modular.shared.auth.JwtTokenService;
import com.tasked.modular.shared.ratelimit.AuthRateLimitFilter;

/**
 * The composition root of the whole auth pipeline.
 *
 * <p>Three annotations carry the configuration:
 * <ul>
 *   <li>{@code @EnableWebSecurity} activates the servlet filter-chain machinery.</li>
 *   <li>{@code @EnableMethodSecurity} activates {@code @PreAuthorize}, i.e. the RBAC checks
 *       that live on controller methods rather than on URL patterns.</li>
 *   <li>{@code @Configuration} makes the beans below available for injection.</li>
 * </ul>
 *
 * <p><strong>Two decoders, two secrets.</strong> {@link #accessTokenDecoder} is the only
 * decoder wired into the resource server, so the filter chain will only ever accept an
 * <em>access</em> token as a Bearer credential. The refresh decoder lives inside
 * {@code JwtTokenService} and is invoked by hand during rotation. A refresh token presented
 * as a bearer credential therefore fails at signature verification, which makes the two
 * token types genuinely non-interchangeable rather than merely conventionally so.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * @param entryPoint      renders a JSON 401 (see JsonAuthenticationEntryPoint)
     * @param deniedHandler   renders a JSON 403 (see JsonAccessDeniedHandler)
     * @param rateLimitFilter throttles the credential-accepting endpoints
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder accessTokenDecoder,
                                                   JwtAuthenticationConverter jwtAuthenticationConverter,
                                                   AuthenticationEntryPoint entryPoint,
                                                   AccessDeniedHandler deniedHandler,
                                                   AuthRateLimitFilter rateLimitFilter) throws Exception {
        http
                // No cookies, no server session, no CSRF token to steal: the credential is a
                // bearer header the browser never attaches automatically. CSRF protection
                // would only add a token nobody can submit.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // Never create an HttpSession. Every request re-authenticates from its token,
                // which is what makes the API horizontally scalable with no sticky sessions.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Turn off the servlet-container defaults we are replacing.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                // -- URL-level rules. Order matters: the first match wins. -----------------
                .authorizeHttpRequests(auth -> auth
                        // The three endpoints that mint or exchange credentials must be
                        // reachable without one.
                        .requestMatchers(HttpMethod.POST, "/users", "/users/login", "/users/refresh-token")
                            .permitAll()
                        .requestMatchers(HttpMethod.GET, "/users/hello").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        // CORS preflights carry no Authorization header by definition.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Deny by default. Anything not listed above needs a valid token, so
                        // a newly added endpoint is protected unless someone opts out.
                        .anyRequest().authenticated())

                // -- Bearer-token authentication -------------------------------------------
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(accessTokenDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))

                // Also covers requests that fail before the resource-server configurer runs.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))

                // Runs before authentication so a flood of bad credentials is rejected
                // without paying for a BCrypt verification each time.
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Verifies incoming Bearer tokens against the <strong>access</strong> secret.
     *
     * <p>{@code Duration.ZERO} replaces the default 60-second clock-skew allowance, so a token
     * is invalid the instant it passes {@code exp}. Issuer and audience are checked as well:
     * without them, any token signed with a leaked copy of this secret by any other service
     * would be accepted here.
     */
    @Bean
    public JwtDecoder accessTokenDecoder(JwtProperties props) {
        return JwtTokenService.strictDecoder(props.accessSecret(), props);
    }

    /**
     * Turns the flat {@code role} claim into Spring's authority model.
     *
     * <p><strong>The single biggest RBAC trap.</strong> {@code hasRole('ADMIN')} prepends
     * {@code ROLE_} before comparing, so it looks for the authority {@code ROLE_ADMIN}. If
     * this converter granted a bare {@code ADMIN}, every {@code hasRole} check would fail
     * silently with a 403 that looks like a permissions bug rather than a naming bug. We grant
     * the prefixed form and use {@code hasRole} consistently.
     *
     * <p>{@code setPrincipalClaimName(SUB)} makes {@code Authentication#getName()} return the
     * user id rather than the default, which is convenient for audit logging.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return List.<GrantedAuthority>of();
            }
            return List.<GrantedAuthority>of(
                    new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
        });
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        return converter;
    }

    /**
     * Password hashing, separate from the token hashing in {@code TokenSecurityHelper}.
     *
     * <p>Work factor 11 is roughly 100-200 ms per verification on typical hardware: slow
     * enough to make offline brute force of a leaked table expensive, fast enough for an
     * interactive login. BCrypt salts each hash internally, so identical passwords produce
     * different digests and a rainbow table buys an attacker nothing.
     *
     * <p>Returned as {@code PasswordEncoder} so the algorithm can be swapped (for example to
     * a {@code DelegatingPasswordEncoder} with Argon2) without touching call sites.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(11);
    }

    /**
     * Browsers refuse cross-origin responses unless the server opts in. Origins are listed
     * explicitly: {@code allowCredentials(true)} is incompatible with a wildcard, and a
     * wildcard would defeat the point in any case.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
