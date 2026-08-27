package com.tasked.modular.shared.exception;

import org.springframework.http.HttpStatus;

/** 409 — the request is well-formed but collides with existing state (duplicate email, …). */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
