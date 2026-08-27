package com.tasked.modular.shared.exception;

import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error envelope every failed request returns — including the 401/403 produced
 * inside the security filter chain, which never reaches a {@code @RestControllerAdvice}.
 *
 * <p>{@code fieldErrors} is populated only for validation failures and omitted otherwise, so
 * clients can branch on its presence.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiErrorResponse of(int status, String error, String message, String path) {
        return new ApiErrorResponse(LocalDateTime.now(), status, error, message, path, null);
    }

    public static ApiErrorResponse validation(int status, String error, String message,
                                              String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(LocalDateTime.now(), status, error, message, path, fieldErrors);
    }
}
