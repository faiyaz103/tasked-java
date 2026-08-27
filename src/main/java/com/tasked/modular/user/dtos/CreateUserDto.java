package com.tasked.modular.user.dtos;

import com.tasked.modular.shared.enums.Role;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Signup contract.
 *
 * <p>{@code role} is optional: omit it (or send {@code null}) and the account is created as
 * {@link Role#USER}. Because the component is typed as the enum rather than a {@code String},
 * the set of assignable values is closed by construction - anything outside {@code USER} /
 * {@code ADMIN} fails deserialization and is mapped to a 400 by the global handler, so no
 * unknown role can ever reach the database.
 *
 * <p><strong>Security note.</strong> {@code POST /users} is an anonymous endpoint, so a
 * client-supplied role here means any caller can register themselves as an {@code ADMIN} in a
 * single request. This is the deliberate, documented behaviour of this contract. If you later
 * want registration open but promotion restricted, the change is one line in
 * {@code UserService#createUser}: ignore {@code dto.role()} and assign {@link Role#USER},
 * leaving {@code PATCH /users/&#123;id&#125;/role} (guarded by {@code hasRole('ADMIN')}) as the
 * only way to elevate an account.
 *
 * <p>The 72-character password ceiling is not arbitrary. BCrypt silently truncates its input
 * at 72 bytes, so a longer password would have its tail ignored, and a user who typed 80
 * characters would be able to log in with only the first 72 - a surprising and undocumented
 * behaviour. Rejecting the input is better than silently weakening it.
 */
public record CreateUserDto(

        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Email must be a valid format")
        @Size(max = 100, message = "Email must be at most 100 characters")
        String email,

        @NotBlank(message = "Password cannot be blank")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain at least one uppercase letter")
        @Pattern(regexp = ".*[0-9].*", message = "Password must contain at least one number")
        String password,

        @NotBlank(message = "Password confirmation cannot be blank")
        String confirmPassword,

        /**
         * Optional. {@code null} means "not specified" and resolves to {@link Role#USER} in
         * the service - deliberately not defaulted here, so the service stays the single place
         * that decides what an unspecified role becomes.
         */
        Role role) {

    /**
     * Cross-field check. Bean Validation calls any {@code isXxx()} method annotated with
     * {@code @AssertTrue}, which is how a record expresses a rule that spans two components.
     * The reported field name is derived from the method name, so the client sees
     * {@code passwordConfirmed}.
     */
    @AssertTrue(message = "Passwords do not match")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(confirmPassword);
    }

    /** The role to persist, resolving an omitted value to the safe default. */
    public Role roleOrDefault() {
        return role == null ? Role.USER : role;
    }
}
