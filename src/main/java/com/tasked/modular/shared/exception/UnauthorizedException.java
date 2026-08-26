package com.tasked.modular.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * 401 — the caller is not authenticated, or the credential presented is invalid, expired or
 * revoked.
 *
 * <p>Used for every failure inside the login and refresh-rotation flows. The messages are
 * deliberately coarse ("Invalid email or password") so the response cannot be used to
 * enumerate which emails are registered.
 */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
