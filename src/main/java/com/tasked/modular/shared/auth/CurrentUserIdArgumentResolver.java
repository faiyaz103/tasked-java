package com.tasked.modular.shared.auth;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.tasked.modular.shared.exception.UnauthorizedException;

/**
 * Resolves {@link CurrentUserId}-annotated {@code UUID} parameters from the security context.
 *
 * <p>By the time this runs the token has already been verified by the filter chain, so the
 * {@code sub} claim is trustworthy. The only remaining failure mode is a well-signed token
 * whose subject is not a UUID — treated as a 401 rather than a 500.
 *
 * <p>Registered in {@code WebMvcConfig}; without that registration Spring MVC would try to
 * bind the parameter as a request parameter instead.
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String subject = jwtAuth.getToken().getSubject();
            try {
                return UUID.fromString(subject);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new UnauthorizedException("Invalid user token payload.");
            }
        }
        throw new UnauthorizedException("Invalid user token payload.");
    }
}
