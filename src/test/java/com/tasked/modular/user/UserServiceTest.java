package com.tasked.modular.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tasked.modular.shared.auth.JwtProperties;
import com.tasked.modular.shared.auth.JwtTokenService;
import com.tasked.modular.shared.auth.TokenSecurityHelper;
import com.tasked.modular.shared.enums.Role;
import com.tasked.modular.shared.exception.ConflictException;
import com.tasked.modular.shared.exception.UnauthorizedException;
import com.tasked.modular.user.dtos.CreateUserDto;
import com.tasked.modular.user.dtos.RotateTokenDto;
import com.tasked.modular.user.dtos.SignInDto;
import com.tasked.modular.user.dtos.TokenResponse;
import com.tasked.modular.user.entities.UserEntity;
import com.tasked.modular.user.repositories.UserRepo;
import com.tasked.modular.user.service.UserService;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * The rotation state machine, exercised against a mocked repository so the assertions are
 * about behaviour rather than about SQL. Token minting and hashing are the real
 * implementations - substituting them would test nothing worth testing.
 */
class UserServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "a@b.com";
    private static final String PASSWORD = "Password1";

    private UserRepo userRepo;
    private JwtTokenService tokenService;
    private PasswordEncoder passwordEncoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepo = Mockito.mock(UserRepo.class);
        tokenService = new JwtTokenService(new JwtProperties(
                "tasked-api", "tasked-app",
                "service-test-access-secret-0123456789abcdef",
                "service-test-refresh-secret-0123456789abcdef",
                Duration.ofMinutes(15), Duration.ofDays(7)));
        // Work factor 4: the lowest BCrypt allows. Production uses 11; the cost is the point
        // there and pure overhead here.
        passwordEncoder = new BCryptPasswordEncoder(4);
        userService = new UserService(userRepo, tokenService, passwordEncoder);

        given(userRepo.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
    }

    private UserEntity persistedUser() {
        return UserEntity.builder()
                .id(USER_ID)
                .email(EMAIL)
                .password(passwordEncoder.encode(PASSWORD))
                .role(Role.USER)
                .build();
    }

    // ── Registration ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registering an existing email is a 409, not a duplicate row")
    void duplicateEmailIsRejected() {
        given(userRepo.existsByEmail(EMAIL)).willReturn(true);

        assertThatThrownBy(() -> userService.createUser(
                new CreateUserDto(EMAIL, PASSWORD, PASSWORD, null)))
                .isInstanceOf(ConflictException.class);

        verify(userRepo, never()).save(any());
    }

    @Test
    @DisplayName("an omitted role defaults to USER, and the password is stored hashed")
    void registrationDefaultsToUserRole() {
        given(userRepo.existsByEmail(EMAIL)).willReturn(false);

        userService.createUser(new CreateUserDto(EMAIL, PASSWORD, PASSWORD, null));

        UserEntity saved = captureSavedUser();

        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getPassword()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPassword())).isTrue();
        // Registration does not start a session.
        assertThat(saved.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("an explicit USER role is persisted")
    void registrationHonoursExplicitUserRole() {
        given(userRepo.existsByEmail(EMAIL)).willReturn(false);

        userService.createUser(new CreateUserDto(EMAIL, PASSWORD, PASSWORD, Role.USER));

        assertThat(captureSavedUser().getRole()).isEqualTo(Role.USER);
    }

    /**
     * The signup contract honours a client-supplied role verbatim, so this endpoint can create
     * an administrator. Asserted explicitly so the behaviour is a deliberate, visible choice
     * rather than something that could drift unnoticed.
     */
    @Test
    @DisplayName("an explicit ADMIN role at signup is persisted as ADMIN")
    void registrationHonoursExplicitAdminRole() {
        given(userRepo.existsByEmail(EMAIL)).willReturn(false);

        userService.createUser(new CreateUserDto(EMAIL, PASSWORD, PASSWORD, Role.ADMIN));

        assertThat(captureSavedUser().getRole()).isEqualTo(Role.ADMIN);
    }

    private UserEntity captureSavedUser() {
        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepo).save(captor.capture());
        return captor.getValue();
    }

    // ── Login ───────────────────────────────────────────────────────────────────────────

    /**
     * Both branches must be indistinguishable to the client, otherwise the endpoint reveals
     * which email addresses have accounts.
     */
    @Test
    @DisplayName("unknown email and wrong password fail identically")
    void loginFailuresAreIndistinguishable() {
        given(userRepo.findByEmail("nobody@b.com")).willReturn(Optional.empty());
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(persistedUser()));

        Throwable unknownEmail = org.assertj.core.api.Assertions.catchThrowable(
                () -> userService.signIn(new SignInDto("nobody@b.com", PASSWORD)));
        Throwable wrongPassword = org.assertj.core.api.Assertions.catchThrowable(
                () -> userService.signIn(new SignInDto(EMAIL, "Wrong1234")));

        assertThat(unknownEmail).isInstanceOf(UnauthorizedException.class);
        assertThat(wrongPassword).isInstanceOf(UnauthorizedException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    @DisplayName("login stores a hash of the refresh token, never the token itself")
    void loginStoresOnlyAHash() {
        UserEntity user = persistedUser();
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(user));

        TokenResponse tokens = userService.signIn(new SignInDto(EMAIL, PASSWORD));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(user.getRefreshToken()).isNotEqualTo(tokens.refreshToken());
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(
                tokens.refreshToken(), user.getRefreshToken())).isTrue();
        assertThat(user.getRefreshTokenExpiresAt()).isNotNull();
    }

    // ── Rotation ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rotation returns a brand-new pair and re-points the stored hash at it")
    void rotationIssuesANewPair() {
        UserEntity user = persistedUser();
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(user));
        given(userRepo.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

        TokenResponse first = userService.signIn(new SignInDto(EMAIL, PASSWORD));
        TokenResponse second = userService.rotateTokens(new RotateTokenDto(first.refreshToken()));

        assertThat(second.accessToken()).isNotEqualTo(first.accessToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        // The stored hash now matches only the new token.
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(
                second.refreshToken(), user.getRefreshToken())).isTrue();
        assertThat(TokenSecurityHelper.verifyDoubleHashedToken(
                first.refreshToken(), user.getRefreshToken())).isFalse();
    }

    /**
     * The property the whole design exists for. The replayed token is cryptographically
     * flawless - correct signature, correct issuer, not expired - and is still refused,
     * because it is no longer the token the server is holding. Only a copy could produce it,
     * so the session dies.
     */
    @Test
    @DisplayName("replaying a rotated refresh token is treated as theft and kills the session")
    void reuseDetectionRevokesTheSession() {
        UserEntity user = persistedUser();
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(user));
        given(userRepo.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

        TokenResponse first = userService.signIn(new SignInDto(EMAIL, PASSWORD));
        TokenResponse second = userService.rotateTokens(new RotateTokenDto(first.refreshToken()));

        // the attacker replays the token that was already exchanged
        assertThatThrownBy(() -> userService.rotateTokens(new RotateTokenDto(first.refreshToken())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("reuse detected");

        assertThat(user.getRefreshToken()).isNull();

        // and the legitimate client's newest token is dead too - the whole session is gone
        assertThatThrownBy(() -> userService.rotateTokens(new RotateTokenDto(second.refreshToken())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Active session not found");
    }

    /**
     * Regression test for the session-kill hole. A token that fails signature verification
     * must never be allowed to influence any account - otherwise anyone could forge a payload
     * carrying a victim's id and log them out at will, repeatedly.
     */
    @Test
    @DisplayName("a forged refresh token is refused without touching the victim's session")
    void forgedTokenCannotRevokeAVictimSession() {
        UserEntity victim = persistedUser();
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(victim));

        userService.signIn(new SignInDto(EMAIL, PASSWORD));
        String liveSession = victim.getRefreshToken();
        assertThat(liveSession).isNotNull();

        // signed with the wrong key but carrying the victim's id in `sub`
        JwtTokenService attacker = new JwtTokenService(new JwtProperties(
                "tasked-api", "tasked-app",
                "attacker-access-secret-0123456789abcdefgh",
                "attacker-refresh-secret-0123456789abcdefgh",
                Duration.ofMinutes(15), Duration.ofDays(7)));
        String forged = attacker.generateRefreshToken(USER_ID, EMAIL, Role.ADMIN);

        assertThatThrownBy(() -> userService.rotateTokens(new RotateTokenDto(forged)))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(victim.getRefreshToken()).isEqualTo(liveSession);
        verify(userRepo, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("an access token cannot be exchanged at the refresh endpoint")
    void accessTokenIsNotAcceptedForRotation() {
        String accessToken = tokenService.generateAccessToken(USER_ID, EMAIL, Role.USER);

        assertThatThrownBy(() -> userService.rotateTokens(new RotateTokenDto(accessToken)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("rotation fails once the session has been signed out")
    void rotationFailsWithoutAnActiveSession() {
        UserEntity user = persistedUser();
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(user));
        given(userRepo.findById(USER_ID)).willReturn(Optional.of(user));
        given(userRepo.findByIdForUpdate(USER_ID)).willReturn(Optional.of(user));

        TokenResponse tokens = userService.signIn(new SignInDto(EMAIL, PASSWORD));
        userService.signOut(USER_ID);

        assertThat(user.getRefreshToken()).isNull();
        assertThatThrownBy(() -> userService.rotateTokens(new RotateTokenDto(tokens.refreshToken())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Active session not found");
    }

    @Test
    @DisplayName("signing out twice is not an error")
    void signOutIsIdempotent() {
        UserEntity user = persistedUser();
        given(userRepo.findById(USER_ID)).willReturn(Optional.of(user));

        userService.signOut(USER_ID);
        userService.signOut(USER_ID);

        assertThat(user.getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("signing out a user who no longer exists is a no-op")
    void signOutTolerantOfMissingUser() {
        given(userRepo.findById(USER_ID)).willReturn(Optional.empty());

        userService.signOut(USER_ID);

        verify(userRepo, never()).save(any());
    }

    // ── RBAC ────────────────────────────────────────────────────────────────────────────

    /**
     * A role change must not leave the user holding a session that still asserts the old role.
     * Clearing the refresh slot forces a fresh login, which mints tokens carrying the new one.
     */
    @Test
    @DisplayName("promoting a user ends their session so the new role takes effect")
    void roleChangeEndsTheSession() {
        UserEntity user = persistedUser();
        given(userRepo.findByEmail(EMAIL)).willReturn(Optional.of(user));
        given(userRepo.findById(USER_ID)).willReturn(Optional.of(user));

        userService.signIn(new SignInDto(EMAIL, PASSWORD));
        assertThat(user.getRefreshToken()).isNotNull();

        var response = userService.updateRole(USER_ID, Role.ADMIN);

        assertThat(response.role()).isEqualTo(Role.ADMIN);
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.getRefreshToken()).isNull();
    }
}
