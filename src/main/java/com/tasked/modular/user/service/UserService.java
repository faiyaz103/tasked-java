package com.tasked.modular.user.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.tasked.modular.shared.auth.TokenSecurityHelper;
import com.tasked.modular.shared.auth.TokenService;
import com.tasked.modular.shared.enums.Role;
import com.tasked.modular.shared.exception.ConflictException;
import com.tasked.modular.shared.exception.NotFoundException;
import com.tasked.modular.shared.exception.UnauthorizedException;
import com.tasked.modular.user.dtos.CreateUserDto;
import com.tasked.modular.user.dtos.RotateTokenDto;
import com.tasked.modular.user.dtos.SignInDto;
import com.tasked.modular.user.dtos.TokenResponse;
import com.tasked.modular.user.dtos.UserResponse;
import com.tasked.modular.user.entities.UserEntity;
import com.tasked.modular.user.repositories.UserRepo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The whole authentication pipeline: registration, login, sign-out and refresh rotation, plus
 * the two administrative reads that exercise RBAC.
 *
 * <p>Every method here receives the caller's identity as a {@code UUID} argument that the
 * controller obtained from the validated token. Nothing in this class trusts an identifier
 * that arrived in a request body.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepo userRepo;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public String getHello() {
        return "hello SpringBoot";
    }

    // ── Registration ────────────────────────────────────────────────────────────────────

    /**
     * Creates an account with the role supplied in the request, defaulting to {@link Role#USER}
     * when none is given.
     *
     * <p>The role reaches the database exactly as the client sent it. Because
     * {@code CreateUserDto#role} is typed as the {@link Role} enum, only {@code USER} and
     * {@code ADMIN} can get this far - an unknown value fails deserialization and never
     * reaches this method.
     *
     * <p><strong>{@code POST /users} is anonymous</strong>, so honouring a client-supplied role
     * means any caller can register as an {@code ADMIN}. That is the intended behaviour of this
     * contract; admin creations are logged at {@code WARN} so they are at least visible. To
     * close it later, replace {@code dto.roleOrDefault()} below with {@link Role#USER} and let
     * {@code PATCH /users/&#123;id&#125;/role} - which requires {@code hasRole('ADMIN')} - be
     * the only path to elevation.
     *
     * <p>No tokens are issued here. Registration and authentication are separate steps: the
     * client must call {@code POST /users/login} afterwards. That keeps a single code path
     * for "how does a session begin", which is the path that gets audited and rate-limited.
     */
    @Transactional
    public String createUser(CreateUserDto dto) {
        if (userRepo.existsByEmail(dto.email())) {
            throw new ConflictException("This email already exists");
        }

        Role role = dto.roleOrDefault();

        UserEntity user = UserEntity.builder()
                .email(dto.email())
                // Hash, never encrypt: there is no legitimate reason to ever recover the
                // plaintext, and BCrypt embeds a per-row random salt in the output.
                .password(passwordEncoder.encode(dto.password()))
                .role(role)
                .build();

        userRepo.save(user);

        if (role == Role.ADMIN) {
            log.warn("Registered new user {} with ADMIN role via public signup", user.getId());
        } else {
            log.info("Registered new user {} with role {}", user.getId(), role);
        }
        return "Registration Successful for " + dto.email();
    }

    // ── Login ───────────────────────────────────────────────────────────────────────────

    /**
     * Verifies credentials and starts a session.
     *
     * <p>Both failure branches throw the <em>same</em> message. A response that distinguished
     * "no such email" from "wrong password" would be a user-enumeration oracle: an attacker
     * could harvest which addresses are registered before ever guessing a password.
     */
    @Transactional
    public TokenResponse signIn(SignInDto dto) {
        UserEntity user = userRepo.findByEmail(dto.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(dto.password(), user.getPassword())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        log.info("User {} signed in", user.getId());
        return issueAndStore(user);
    }

    // ── Sign out ────────────────────────────────────────────────────────────────────────

    /**
     * Ends the session by clearing the stored refresh hash. Idempotent, and a no-op if the
     * row is already gone.
     *
     * <p><strong>The access token is not revoked</strong> and stays usable until its {@code exp}.
     * That is the accepted cost of stateless authentication: checking a denylist on every
     * request would reintroduce the per-request database lookup that bearer tokens exist to
     * avoid. A short access TTL bounds the exposure; if that is not tight enough, keep a
     * {@code jti} denylist in Redis with a TTL equal to the remaining token lifetime.
     */
    @Transactional
    public void signOut(UUID userId) {
        userRepo.findById(userId).ifPresent(user -> {
            user.setRefreshToken(null);
            user.setRefreshTokenExpiresAt(null);
            userRepo.save(user);
            log.info("User {} signed out", userId);
        });
    }

    // ── Refresh rotation ────────────────────────────────────────────────────────────────

    /**
     * Exchanges a refresh token for a brand-new pair, invalidating the one presented.
     *
     * <p>The steps, and why each exists:
     * <ol>
     *   <li><b>Cryptographic validation</b> against the refresh secret. An access token or a
     *       forged token dies here. On failure we return 401 and <em>touch nothing</em>:
     *       acting on an unverified token would let anyone forge a {@code sub} and forcibly
     *       terminate a stranger's session at will.</li>
     *   <li><b>Subject extraction.</b> The user id comes from the signed payload, never the
     *       request body.</li>
     *   <li><b>Active-session check</b> under a pessimistic row lock, so a concurrent replay
     *       cannot slip between the check and the write. A cleared slot means the session was
     *       already ended by sign-out or by an earlier reuse detection.</li>
     *   <li><b>Server-side expiry.</b> Checked independently of the token's own {@code exp} so
     *       a session can be ended without decoding anything.</li>
     *   <li><b>Reuse detection.</b> The presented token must be <em>the</em> current one. A
     *       token that is cryptographically valid but no longer the stored one has already
     *       been rotated, which means someone kept a copy and replayed it. The session is
     *       destroyed rather than merely refused, so both the attacker and the legitimate
     *       client are forced back through a full login.</li>
     *   <li><b>Rotation.</b> A new pair is minted and the stored hash overwritten; the token
     *       just used is dead from this moment on.</li>
     * </ol>
     *
     * <p><strong>Why {@code noRollbackFor}.</strong> Steps 4 and 5 revoke the session and
     * <em>then</em> throw. Spring rolls back on unchecked exceptions by default, which would
     * undo the revocation on its way out and leave the compromised session alive - reuse would
     * be reported to the caller but never actually enforced. Declaring
     * {@link UnauthorizedException} non-rollback lets the revoke commit while the request
     * still fails. The paths that throw without writing commit nothing, so this is safe.
     *
     * <p>Doing the revoke in a {@code REQUIRES_NEW} transaction instead would deadlock: this
     * transaction already holds a {@code FOR UPDATE} lock on the very row the inner
     * transaction would need to update.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public TokenResponse rotateTokens(RotateTokenDto dto) {
        // 1. signature + issuer + audience + expiry, against the REFRESH secret
        Jwt jwt;
        try {
            jwt = tokenService.validateRefreshToken(dto.token());
        } catch (JwtException e) {
            throw new UnauthorizedException("Refresh token is expired or invalid.");
        }

        // 2. identity from the signed payload
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnauthorizedException("Invalid token payload.");
        }

        // 3. an active session must exist; the row is locked for the rest of the transaction
        UserEntity user = userRepo.findByIdForUpdate(userId)
                .orElseThrow(() -> new UnauthorizedException("Access denied. Active session not found."));

        if (!StringUtils.hasText(user.getRefreshToken())) {
            throw new UnauthorizedException("Access denied. Active session not found.");
        }

        // 4. server-side expiry, independent of the token's own exp
        if (user.getRefreshTokenExpiresAt() != null
                && user.getRefreshTokenExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            clearSession(user);
            throw new UnauthorizedException("Access denied. Active session not found.");
        }

        // 5. reuse detection
        if (!TokenSecurityHelper.verifyDoubleHashedToken(dto.token(), user.getRefreshToken())) {
            clearSession(user);
            log.warn("Refresh token reuse detected for user {} - session revoked", userId);
            throw new UnauthorizedException("Security alert: Token reuse detected. Session revoked.");
        }

        // 6. rotate
        return issueAndStore(user);
    }

    // ── Reads ───────────────────────────────────────────────────────────────────────────

    /** The caller's own record. The id comes from the token, so there is nothing to authorize. */
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return userRepo.findById(userId)
                .map(UserService::toResponse)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Administrative listing. Guarded at the controller by {@code hasRole('ADMIN')}. */
    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepo.findAll().stream().map(UserService::toResponse).toList();
    }

    /**
     * The only sanctioned way to change a role, and the reason it is safe to keep {@code role}
     * out of the signup contract.
     *
     * <p>Changing a role does not retroactively change tokens already issued to that user:
     * their current access token still carries the old {@code role} claim until it expires.
     * The session is therefore cleared so the next refresh fails and the user must log in
     * again, picking up the new role. With a short access TTL this closes the window quickly.
     */
    @Transactional
    public UserResponse updateRole(UUID targetUserId, Role newRole) {
        UserEntity user = userRepo.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        Role previous = user.getRole();
        user.setRole(newRole);
        clearSession(user);

        log.info("Role for user {} changed from {} to {}", targetUserId, previous, newRole);
        return toResponse(user);
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────

    /**
     * Mints a pair and persists the refresh hash. The single point where a session begins or
     * is renewed, so the rules about what gets stored live in exactly one place.
     */
    private TokenResponse issueAndStore(UserEntity user) {
        String accessToken = tokenService.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = tokenService.generateRefreshToken(user.getId(), user.getEmail(), user.getRole());

        user.setRefreshToken(TokenSecurityHelper.doubleHashToken(refreshToken));
        user.setRefreshTokenExpiresAt(
                LocalDateTime.now(ZoneOffset.UTC).plus(tokenService.refreshTokenTtl()));
        userRepo.save(user);

        return new TokenResponse(accessToken, refreshToken);
    }

    private void clearSession(UserEntity user) {
        user.setRefreshToken(null);
        user.setRefreshTokenExpiresAt(null);
        userRepo.save(user);
    }

    private static UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
