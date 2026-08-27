package com.tasked.modular.shared.exception;

import org.springframework.http.HttpStatus;

/** 429 — the caller tripped a rate limit on a credential-accepting endpoint. */
public class TooManyRequestsException extends ApiException {
    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
