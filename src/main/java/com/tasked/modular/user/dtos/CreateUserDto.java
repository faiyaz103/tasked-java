package com.tasked.modular.user.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserDto(
    @NotBlank(message = "email can not be blank")
    @Email(message = "must be valid email")
    String email,

    @NotBlank(message = "password can not be blank")
    @Size(min = 8, max = 16)
    String password
) {}
