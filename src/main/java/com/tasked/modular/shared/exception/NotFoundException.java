package com.tasked.modular.shared.exception;

import org.springframework.http.HttpStatus;

/** 404 — the addressed resource does not exist. */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
