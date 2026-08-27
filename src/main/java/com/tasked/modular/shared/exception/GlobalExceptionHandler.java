package com.tasked.modular.shared.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * One place where every exception thrown inside a controller or service becomes an HTTP
 * status and a JSON body, replacing per-action {@code try/catch}.
 *
 * <p><strong>Scope limit worth knowing.</strong> {@code @RestControllerAdvice} only sees
 * exceptions that reach the {@code DispatcherServlet}. Authentication and authorization
 * failures are raised <em>earlier</em>, inside the security filter chain, and never get here —
 * that is why {@code SecurityConfig} additionally registers a
 * {@link JsonAuthenticationEntryPoint} and a {@link JsonAccessDeniedHandler} that emit the
 * same envelope. Without them a 401 would come back with an empty body while every other
 * error returned JSON.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Covers ConflictException (409), UnauthorizedException (401), NotFoundException (404), … */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest req) {
        HttpStatus status = ex.getStatus();
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), status.getReasonPhrase(),
                        ex.getMessage(), req.getRequestURI()));
    }

    /** Bean Validation failure on a {@code @Valid @RequestBody} — field name to first message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                             HttpServletRequest req) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.validation(HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Request validation failed.", req.getRequestURI(), fieldErrors));
    }

    /** Malformed JSON, or an unparsable enum value such as {@code "role": "SUPERUSER"}. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                             HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Malformed request body or unsupported field value.", req.getRequestURI()));
    }

    /**
     * A path variable or query parameter that will not convert to its declared type, such as
     * {@code /users/not-a-uuid/role}. Without this handler Spring answers with its own default
     * error body, which is the one response in the API that would not match
     * {@link ApiErrorResponse}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                               HttpServletRequest req) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        "Invalid value for '" + ex.getName() + "'.", req.getRequestURI()));
    }

    /**
     * A unique index rejected the write — the read-then-write uniqueness check lost a race.
     * The index is what actually guarantees correctness; this handler stops the resulting
     * database error surfacing as a 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraint(DataIntegrityViolationException ex,
                                                             HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(),
                        HttpStatus.CONFLICT.getReasonPhrase(),
                        "A record with these details already exists.", req.getRequestURI()));
    }

    /** Two concurrent refresh rotations collided; the loser must retry with its new token. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleLockFailure(OptimisticLockingFailureException ex,
                                                              HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT.value(),
                        HttpStatus.CONFLICT.getReasonPhrase(),
                        "Concurrent modification detected. Please retry.", req.getRequestURI()));
    }

    /**
     * {@code @PreAuthorize} rejections thrown by method security. Method-level denials are
     * raised inside the dispatcher (unlike URL-level ones), so they do land here.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                               HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(HttpStatus.FORBIDDEN.value(),
                        HttpStatus.FORBIDDEN.getReasonPhrase(),
                        "You do not have permission to perform this action.", req.getRequestURI()));
    }

    /** A refresh token that failed decoding outside the filter chain. */
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<ApiErrorResponse> handleJwt(JwtException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiErrorResponse.of(HttpStatus.UNAUTHORIZED.value(),
                        HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                        "Invalid or expired token.", req.getRequestURI()));
    }
}
