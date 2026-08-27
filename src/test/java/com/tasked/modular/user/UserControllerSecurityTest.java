package com.tasked.modular.user;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tasked.modular.shared.auth.CurrentUserIdArgumentResolver;
import com.tasked.modular.shared.auth.JwtProperties;
import com.tasked.modular.shared.auth.JwtTokenService;
import com.tasked.modular.shared.config.SecurityConfig;
import com.tasked.modular.shared.config.WebMvcConfig;
import com.tasked.modular.shared.enums.Role;
import com.tasked.modular.shared.exception.GlobalExceptionHandler;
import com.tasked.modular.shared.exception.JsonAccessDeniedHandler;
import com.tasked.modular.shared.exception.JsonAuthenticationEntryPoint;
import com.tasked.modular.shared.exception.UnauthorizedException;
import com.tasked.modular.shared.ratelimit.AuthRateLimitFilter;
import com.tasked.modular.user.controller.UserController;
import com.tasked.modular.user.dtos.CreateUserDto;
import com.tasked.modular.user.dtos.TokenResponse;
import com.tasked.modular.user.dtos.UserResponse;
import com.tasked.modular.user.service.UserService;

/**
 * Drives real, signed tokens through the real {@code SecurityFilterChain}.
 *
 * <p>These are the tests that catch the failures unit tests cannot see: the {@code ROLE_}
 * prefix mismatch, an empty-bodied 401 because no entry point was registered, and a refresh
 * token being accepted as a Bearer credential.
 *
 * <p>Only {@link UserService} is mocked - everything from the HTTP layer down to the JWT
 * decoder is the production wiring.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, WebMvcConfig.class, CurrentUserIdArgumentResolver.class,
        JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class,
        AuthRateLimitFilter.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@TestPropertySource(properties = {
        "tasked.jwt.issuer=tasked-api",
        "tasked.jwt.audience=tasked-app",
        "tasked.jwt.access-secret=integration-test-access-secret-0123456789abcdef",
        "tasked.jwt.refresh-secret=integration-test-refresh-secret-0123456789abcdef",
        "tasked.jwt.access-ttl=PT15M",
        "tasked.jwt.refresh-ttl=P7D",
        "cors.allowed-origins=http://localhost:3000"
})
class UserControllerSecurityTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProperties jwtProperties;

    @MockitoBean
    private UserService userService;

    private JwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService(jwtProperties);
    }

    private String accessTokenFor(Role role) {
        return tokenService.generateAccessToken(USER_ID, "a@b.com", role);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ── Authentication ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no Authorization header yields a 401 with a JSON body")
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("a garbage bearer token yields 401, not 500")
    void malformedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    /**
     * The two-secret property, observed from the outside: a refresh token is a perfectly valid
     * JWT, and the filter chain still refuses it because it was signed with the other key.
     */
    @Test
    @DisplayName("a refresh token presented as a Bearer credential is rejected")
    void refreshTokenIsNotAccepted() throws Exception {
        String refreshToken = tokenService.generateRefreshToken(USER_ID, "a@b.com", Role.ADMIN);

        mockMvc.perform(get("/users/me").header(HttpHeaders.AUTHORIZATION, bearer(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid access token reaches the handler with the identity from its sub claim")
    void validTokenResolvesCurrentUserId() throws Exception {
        given(userService.getCurrentUser(USER_ID))
                .willReturn(new UserResponse(USER_ID, "a@b.com", Role.USER, LocalDateTime.now()));

        mockMvc.perform(get("/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessTokenFor(Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("a@b.com"));
    }

    // ── Authorization / RBAC ────────────────────────────────────────────────────────────

    /**
     * The {@code ROLE_} prefix regression test. If the authorities converter granted a bare
     * {@code ADMIN} instead of {@code ROLE_ADMIN}, this would return 403 and look like a
     * permissions bug rather than the naming bug it is.
     */
    @Test
    @DisplayName("hasRole('ADMIN') passes for a token whose role claim is ADMIN")
    void adminTokenReachesAdminEndpoint() throws Exception {
        given(userService.listUsers()).willReturn(List.of());

        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessTokenFor(Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a USER token on an admin endpoint yields a 403 with a JSON body")
    void userTokenIsForbiddenOnAdminEndpoint() throws Exception {
        mockMvc.perform(get("/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessTokenFor(Role.USER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ── Anonymous endpoints and validation ──────────────────────────────────────────────

    @Test
    @DisplayName("login is reachable without a token")
    void loginIsAnonymous() throws Exception {
        given(userService.signIn(any())).willReturn(new TokenResponse("access", "refresh"));

        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"Password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }

    @Test
    @DisplayName("bad credentials surface as a 401 from the service, not a 500")
    void badCredentialsAreUnauthorized() throws Exception {
        given(userService.signIn(any())).willThrow(new UnauthorizedException("Invalid email or password"));

        mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"Wrong1234\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    /**
     * Registration is anonymous, so its DTO is the outermost input boundary. A mismatched
     * confirmation must be a 400 with the offending field named, never a 201.
     */
    @Test
    @DisplayName("signup validation failures return 400 with per-field messages")
    void signupValidationFailsCleanly() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\","
                                + "\"confirmPassword\":\"different\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.email").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.password").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.passwordConfirmed").isNotEmpty());
    }

    /** A role in the signup body binds onto the DTO and is passed through to the service. */
    @Test
    @DisplayName("a role supplied at signup reaches the service")
    void signupBindsClientSuppliedRole() throws Exception {
        given(userService.createUser(any())).willReturn("Registration Successful for a@b.com");

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"Password1\","
                                + "\"confirmPassword\":\"Password1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateUserDto> captor = ArgumentCaptor.forClass(CreateUserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("an omitted role binds as null and resolves to USER")
    void signupWithoutRoleBindsNull() throws Exception {
        given(userService.createUser(any())).willReturn("Registration Successful for a@b.com");

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"Password1\","
                                + "\"confirmPassword\":\"Password1\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateUserDto> captor = ArgumentCaptor.forClass(CreateUserDto.class);
        verify(userService).createUser(captor.capture());
        assertThat(captor.getValue().role()).isNull();
        assertThat(captor.getValue().roleOrDefault()).isEqualTo(Role.USER);
    }

    /**
     * The enum type closes the set of assignable roles: an unknown value cannot bind, so it is
     * rejected at the HTTP boundary and never reaches the service.
     */
    @Test
    @DisplayName("an unknown role at signup is a 400 and never reaches the service")
    void signupRejectsUnknownRole() throws Exception {
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"Password1\","
                                + "\"confirmPassword\":\"Password1\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).createUser(any());
    }

    @Test
    @DisplayName("the unauthenticated hello probe stays open")
    void helloIsAnonymous() throws Exception {
        given(userService.getHello()).willReturn("hello SpringBoot");

        mockMvc.perform(get("/users/hello")).andExpect(status().isOk());
    }
}
