package com.tasked.modular.shared.exception;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Produces the 401 for requests that carry no token, or a token the filter chain rejected
 * (bad signature, wrong issuer/audience, expired, wrong secret).
 *
 * <p>This runs inside the security filter chain, <em>before</em> the dispatcher servlet, so
 * {@link GlobalExceptionHandler} cannot see the failure. Registering this handler is what
 * keeps unauthenticated responses in the same JSON shape as every other error instead of an
 * empty body with a bare {@code WWW-Authenticate} header.
 */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                ApiErrorResponse.of(HttpStatus.UNAUTHORIZED.value(),
                        HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                        "Authentication required. Provide a valid access token.",
                        request.getRequestURI()));
    }
}
