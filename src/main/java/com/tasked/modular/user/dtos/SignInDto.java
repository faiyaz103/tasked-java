package com.tasked.modular.user.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login contract.
 *
 * <p>Note what is <em>not</em> here: the password complexity rules from {@link CreateUserDto}.
 * Re-validating password shape at login would leak the password policy to anyone who can hit
 * the endpoint, and would lock out any account whose password predates the current rules. At
 * login the only question is whether the credential verifies.
 */
public record SignInDto(

        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Email must be a valid format")
        String email,

        @NotBlank(message = "Password cannot be blank")
        String password) {
}
