package com.tasked.modular.user.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.tasked.modular.shared.auth.CurrentUserId;
import com.tasked.modular.shared.auth.Policies;
import com.tasked.modular.user.dtos.CreateUserDto;
import com.tasked.modular.user.dtos.RotateTokenDto;
import com.tasked.modular.user.dtos.SignInDto;
import com.tasked.modular.user.dtos.TokenResponse;
import com.tasked.modular.user.dtos.UpdateRoleDto;
import com.tasked.modular.user.dtos.UserResponse;
import com.tasked.modular.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * HTTP surface of the users module.
 *
 * <p>Every method is thin by design: {@code @Valid} handles input validation before the body
 * runs, {@code @PreAuthorize} handles authorization, {@code @CurrentUserId} supplies identity,
 * and {@code GlobalExceptionHandler} maps failures to status codes. There is no
 * {@code try/catch} and no manual validation call anywhere in this class.
 *
 * <p><strong>Two layers of authorization, on purpose.</strong> {@code SecurityConfig} already
 * denies unauthenticated requests to anything not explicitly permitted, so the
 * {@code @PreAuthorize} annotations below are not strictly required for the authenticated-only
 * endpoints. They are kept because they state the requirement at the method that has it: if
 * this controller is ever remapped to a different path, the guarantee travels with the code
 * rather than staying behind in a URL pattern.
 */
@RestController
@RequestMapping("users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** Unauthenticated liveness probe, explicitly permitted in {@code SecurityConfig}. */
    @GetMapping("hello")
    @PreAuthorize(Policies.USER)
    public String getHello() {
        return userService.getHello();
    }

    // ── Anonymous: the three endpoints that mint or exchange credentials ────────────────

    /**
     * Registers an account. The optional {@code role} in the body is honoured as sent, and
     * defaults to {@code USER} when omitted; an unknown value is a 400.
     *
     * <p>This endpoint is anonymous, so a caller can register themselves as an {@code ADMIN}.
     * See {@code UserService#createUser} for the note on restricting that.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public String createUser(@Valid @RequestBody CreateUserDto dto) {
        return userService.createUser(dto);
    }

    @PostMapping("login")
    public TokenResponse login(@Valid @RequestBody SignInDto dto) {
        return userService.signIn(dto);
    }

    /**
     * Rotates a refresh token. Anonymous at the URL level because the caller's access token
     * has, by definition, usually expired by the time they get here; the refresh token in the
     * body <em>is</em> the credential and is verified inside the service.
     */
    @PostMapping("refresh-token")
    public TokenResponse refreshToken(@Valid @RequestBody RotateTokenDto dto) {
        return userService.rotateTokens(dto);
    }

    // ── Authenticated ──────────────────────────────────────────────────────────────────

    @PostMapping("signout")
    @PreAuthorize(Policies.AUTHENTICATED)
    public Map<String, String> signOut(@CurrentUserId UUID userId) {
        userService.signOut(userId);
        return Map.of("message", "Successfully signed out.");
    }

    /**
     * The caller's own profile. Note there is no {@code /users/{id}} equivalent for ordinary
     * users: identity comes from the token, so a client cannot ask for someone else's record
     * by changing a path segment.
     */
    @GetMapping("me")
    @PreAuthorize(Policies.AUTHENTICATED)
    public UserResponse getCurrentUser(@CurrentUserId UUID userId) {
        return userService.getCurrentUser(userId);
    }

    // ── Admin only: where RBAC is actually exercised ───────────────────────────────────

    /**
     * Lists every account. A {@code USER} token reaching this method gets a JSON 403 from
     * {@code JsonAccessDeniedHandler} - authenticated, but not authorized.
     */
    @GetMapping
    @PreAuthorize(Policies.ADMIN)
    public List<UserResponse> listUsers() {
        return userService.listUsers();
    }

    /**
     * Promotes or demotes an account. This is the deliberate, guarded replacement for the
     * client-supplied {@code role} that was kept out of the signup contract.
     */
    @PatchMapping("{id}/role")
    @PreAuthorize(Policies.ADMIN)
    public UserResponse updateRole(@PathVariable UUID id, @Valid @RequestBody UpdateRoleDto dto) {
        return userService.updateRole(id, dto.role());
    }
}
