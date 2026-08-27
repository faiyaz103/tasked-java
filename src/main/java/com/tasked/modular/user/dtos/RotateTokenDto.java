package com.tasked.modular.user.dtos;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh-token exchange contract.
 *
 * <p>The token travels in the request body rather than the {@code Authorization} header on
 * purpose: the header is reserved for access tokens, and the resource-server filter chain
 * would reject a refresh token there anyway (different signing key). Keeping them in
 * different transport slots also makes accidental misuse by a client obvious.
 */
public record RotateTokenDto(

        @NotBlank(message = "Refresh token cannot be blank")
        String token) {
}
