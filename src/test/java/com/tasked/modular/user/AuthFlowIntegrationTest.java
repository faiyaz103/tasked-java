package com.tasked.modular.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.tasked.modular.shared.enums.Role;
import com.tasked.modular.user.entities.UserEntity;
import com.tasked.modular.user.repositories.UserRepo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end auth flow against the real datasource.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. A test-managed transaction that
 * rolls back at the end would hide exactly the class of defect this test exists to catch: a
 * revocation that is written and then undone when the request's own transaction rolls back.
 * Rows are cleaned up explicitly instead.
 *
 * <p>Requires a reachable PostgreSQL instance, like {@code ModularApplicationTests}. Swap the
 * datasource for Testcontainers to make it self-contained in CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private String email;

    @AfterEach
    void cleanUp() {
        if (email != null) {
            userRepo.findByEmail(email).ifPresent(userRepo::delete);
        }
    }

    private String uniqueEmail() {
        email = "flow-" + UUID.randomUUID() + "@test.com";
        return email;
    }

    private JsonNode register_thenLogin() throws Exception {
        String address = uniqueEmail();

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1","confirmPassword":"Password1"}
                                """.formatted(address)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1"}
                                """.formatted(address)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    private JsonNode rotate(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/users/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("token", refreshToken))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("registration without a role stores a USER with a hashed password and no session")
    void registrationDefaultsToUserRole() throws Exception {
        String address = uniqueEmail();

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1","confirmPassword":"Password1"}
                                """.formatted(address)))
                .andExpect(status().isCreated());

        UserEntity saved = userRepo.findByEmail(address).orElseThrow();

        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getPassword()).isNotEqualTo("Password1").startsWith("$2");
        assertThat(saved.getRefreshToken()).isNull();
    }

    /**
     * A role sent at signup is persisted verbatim, all the way through to a token that
     * satisfies {@code hasRole('ADMIN')}. This endpoint is anonymous, so this test also
     * documents that anyone can self-register as an administrator.
     */
    @Test
    @DisplayName("a role sent at signup is persisted and lands in the issued token")
    void registrationPersistsSuppliedRole() throws Exception {
        String address = uniqueEmail();

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1","confirmPassword":"Password1","role":"ADMIN"}
                                """.formatted(address)))
                .andExpect(status().isCreated());

        assertThat(userRepo.findByEmail(address).orElseThrow().getRole()).isEqualTo(Role.ADMIN);

        // the role survives into the access token, so the account really is an admin
        String body = mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1"}
                                """.formatted(address)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(body).get("accessToken").asString();

        mockMvc.perform(get("/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown role at signup is a 400 and creates nothing")
    void registrationRejectsUnknownRole() throws Exception {
        String address = uniqueEmail();

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1","confirmPassword":"Password1","role":"SUPERUSER"}
                                """.formatted(address)))
                .andExpect(status().isBadRequest());

        assertThat(userRepo.findByEmail(address)).isEmpty();
    }

    @Test
    @DisplayName("login stores only a hash, never the refresh token itself")
    void loginStoresOnlyAHash() throws Exception {
        JsonNode tokens = register_thenLogin();
        String refreshToken = tokens.get("refreshToken").asString();

        UserEntity saved = userRepo.findByEmail(email).orElseThrow();

        assertThat(saved.getRefreshToken()).isNotNull().isNotEqualTo(refreshToken);
        assertThat(saved.getRefreshTokenExpiresAt()).isNotNull();
    }

    /**
     * The regression test for the revocation-rollback defect. Reuse detection writes
     * {@code refresh_token = NULL} and then throws; if the exception rolled the transaction
     * back, the row below would still hold a hash and the "revoked" session would keep
     * working - the alert would be cosmetic.
     */
    @Test
    @DisplayName("replaying a rotated token revokes the session, and the revocation is committed")
    void reuseDetectionRevocationIsDurable() throws Exception {
        JsonNode first = register_thenLogin();
        String originalRefresh = first.get("refreshToken").asString();

        JsonNode second = rotate(originalRefresh);
        String rotatedRefresh = second.get("refreshToken").asString();
        assertThat(rotatedRefresh).isNotEqualTo(originalRefresh);

        // replay the token that was already exchanged
        mockMvc.perform(post("/users/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("token", originalRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "Security alert: Token reuse detected. Session revoked."));

        // the revocation survived the failed request
        assertThat(userRepo.findByEmail(email).orElseThrow().getRefreshToken()).isNull();

        // and the newest token is dead too: the whole session is gone
        mockMvc.perform(post("/users/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("token", rotatedRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Access denied. Active session not found."));
    }

    @Test
    @DisplayName("sign out clears the stored session and is idempotent")
    void signOutClearsSession() throws Exception {
        JsonNode tokens = register_thenLogin();
        String accessToken = tokens.get("accessToken").asString();
        String refreshToken = tokens.get("refreshToken").asString();

        mockMvc.perform(post("/users/signout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/users/signout").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertThat(userRepo.findByEmail(email).orElseThrow().getRefreshToken()).isNull();

        mockMvc.perform(post("/users/refresh-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("token", refreshToken))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Promotion is the guarded replacement for a client-supplied role at signup. It also ends
     * the target's session, so their next login carries the new role.
     */
    @Test
    @DisplayName("an admin can promote a user, which also ends that user's session")
    void adminCanPromoteAndSessionIsEnded() throws Exception {
        JsonNode tokens = register_thenLogin();
        String userAccessToken = tokens.get("accessToken").asString();
        UserEntity user = userRepo.findByEmail(email).orElseThrow();
        assertThat(user.getRefreshToken()).isNotNull();

        // a USER token cannot reach the admin endpoint
        mockMvc.perform(get("/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + userAccessToken))
                .andExpect(status().isForbidden());

        // promote directly in the database to obtain a genuine admin, then log that admin in
        user.setRole(Role.ADMIN);
        userRepo.save(user);

        String adminBody = mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminToken = objectMapper.readTree(adminBody).get("accessToken").asString();

        // the same account, now carrying role=ADMIN in its token, is allowed through
        mockMvc.perform(get("/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // demote through the API and confirm the session was ended
        mockMvc.perform(patch("/users/{id}/role", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));

        assertThat(userRepo.findByEmail(email).orElseThrow().getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("an unparsable role value is a 400, not a 500")
    void unknownRoleValueIsRejected() throws Exception {
        JsonNode tokens = register_thenLogin();
        UserEntity user = userRepo.findByEmail(email).orElseThrow();
        user.setRole(Role.ADMIN);
        userRepo.save(user);

        String adminBody = mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Password1"}
                                """.formatted(email)))
                .andReturn().getResponse().getContentAsString();
        String adminToken = objectMapper.readTree(adminBody).get("accessToken").asString();

        mockMvc.perform(patch("/users/{id}/role", user.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());

        assertThat(tokens).isNotNull();
    }
}
