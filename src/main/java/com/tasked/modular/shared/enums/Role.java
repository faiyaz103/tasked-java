package com.tasked.modular.shared.enums;

/**
 * Flat, single-role-per-user RBAC.
 *
 * <p>The constant name is the single source of truth for three things at once:
 * <ul>
 *   <li>the value persisted in {@code users.role} ({@code @Enumerated(EnumType.STRING)}),</li>
 *   <li>the value emitted in the {@code role} claim of every JWT,</li>
 *   <li>the suffix of the Spring Security authority ({@code ROLE_ADMIN} / {@code ROLE_USER}).</li>
 * </ul>
 *
 * <p>Constants are UPPER CASE because Spring's {@code hasRole('ADMIN')} compares against
 * {@code ROLE_ADMIN}. If you are upgrading a database that was written by the earlier
 * {@code User}/{@code Admin} spelling, run once:
 * <pre>UPDATE users SET role = upper(role);</pre>
 */
public enum Role {
    USER,
    ADMIN
}
