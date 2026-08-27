package com.tasked.modular.user.dtos;

import com.tasked.modular.shared.enums.Role;

import jakarta.validation.constraints.NotNull;

/**
 * Admin-only role change - the sanctioned counterpart to having removed {@code role} from
 * {@link CreateUserDto}. Escalation is still possible, but only for a caller who already
 * holds {@code ROLE_ADMIN}, and it leaves an obvious audit point.
 *
 * <p>A value outside the {@link Role} enum fails deserialization and is mapped to a 400 by
 * the global handler, so the set of assignable roles is closed by construction.
 */
public record UpdateRoleDto(

        @NotNull(message = "Role is required")
        Role role) {
}
