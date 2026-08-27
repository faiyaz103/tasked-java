package com.tasked.modular.user.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

import com.tasked.modular.shared.enums.Role;

/**
 * Outbound view of a user.
 *
 * <p>A dedicated response record rather than the entity, so that {@code password} and
 * {@code refreshToken} cannot leak through a serializer by accident. The rule to keep: an
 * entity never crosses the HTTP boundary in either direction.
 */
public record UserResponse(
        UUID id,
        String email,
        Role role,
        LocalDateTime createdAt) {
}
