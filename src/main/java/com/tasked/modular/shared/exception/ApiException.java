package com.tasked.modular.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for exceptions that carry their own HTTP status.
 *
 * <p>Services throw these instead of returning status codes, which keeps the business layer
 * free of {@code ResponseEntity} and lets {@link GlobalExceptionHandler} do the single,
 * centralised translation to JSON.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
