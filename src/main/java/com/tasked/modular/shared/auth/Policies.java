package com.tasked.modular.shared.auth;

/**
 * Named authorization policies, referenced from {@code @PreAuthorize}.
 *
 * <p>These constants are the Spring equivalent of named policies registered on an
 * authorization builder: they keep the SpEL expression in exactly one place, so renaming a
 * role or widening a rule is a single edit rather than a grep across every controller.
 * {@code @PreAuthorize} requires a compile-time constant, which is why these are
 * {@code static final String} rather than an enum.
 *
 * <p><strong>The {@code ROLE_} trap.</strong> {@code hasRole('ADMIN')} silently prepends
 * {@code ROLE_} before comparing against the granted authority, while {@code hasAuthority}
 * does not. This codebase grants {@code ROLE_ADMIN} / {@code ROLE_USER} (see
 * {@code SecurityConfig#jwtAuthenticationConverter}) and therefore uses {@code hasRole}
 * everywhere. Never mix the two conventions — the mismatch fails open into a silent 403.
 */
public final class Policies {

    /** Any caller presenting a valid, unexpired access token. */
    public static final String AUTHENTICATED = "isAuthenticated()";

    /** Administrators only. */
    public static final String ADMIN = "hasRole('ADMIN')";

    /** Ordinary users only — deliberately excludes admins. */
    public static final String USER = "hasRole('USER')";

    /** Either role. OR, not AND: a user needs just one of them. */
    public static final String ELEVATED = "hasAnyRole('ADMIN','USER')";

    private Policies() {
    }
}
