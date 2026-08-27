package com.tasked.modular.shared.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.tasked.modular.shared.exception.ApiErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Fixed-window rate limit on the two endpoints that accept credentials.
 *
 * <p>Without this, {@code POST /users/login} is an unmetered password-guessing oracle and
 * {@code POST /users/refresh-token} is an unmetered token-guessing oracle. BCrypt's work
 * factor slows an <em>offline</em> attack on a stolen hash; it does nothing to slow an online
 * one, which is what a rate limit is for.
 *
 * <p>Deliberately dependency-free and in-memory: the counters live in this JVM only. That is
 * correct for a single-instance deployment and honest about its limit — behind a load
 * balancer or in a multi-replica deployment, move this to a shared store (Redis + Bucket4j)
 * or to the gateway, otherwise the effective limit multiplies by the replica count.
 *
 * <p>Keyed on client IP only. Keying on IP + email would give sharper per-account protection
 * but requires buffering and parsing the request body before the handler reads it.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of("/users/login", "/users/refresh-token");
    private static final int MAX_ATTEMPTS_PER_WINDOW = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ObjectMapper objectMapper;
    private final Map<String, Window> counters = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String key = request.getRequestURI() + "|" + clientIp(request);
        if (!tryConsume(key)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            objectMapper.writeValue(response.getWriter(),
                    ApiErrorResponse.of(HttpStatus.TOO_MANY_REQUESTS.value(),
                            HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                            "Too many attempts. Try again later.", request.getRequestURI()));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tryConsume(String key) {
        Instant now = Instant.now();
        Window window = counters.compute(key, (k, existing) ->
                (existing == null || existing.isExpired(now)) ? new Window(now.plus(WINDOW)) : existing);
        if (counters.size() > 10_000) {
            counters.entrySet().removeIf(e -> e.getValue().isExpired(now));
        }
        return window.count.incrementAndGet() <= MAX_ATTEMPTS_PER_WINDOW;
    }

    /**
     * Honours {@code X-Forwarded-For} only for its first hop. Note that behind an untrusted
     * proxy this header is client-controlled and therefore trivially spoofable — configure
     * {@code server.forward-headers-strategy} and a trusted-proxy list before relying on it.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static final class Window {
        private final Instant expiresAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        private boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }
}
