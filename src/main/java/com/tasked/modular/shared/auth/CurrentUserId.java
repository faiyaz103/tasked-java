package com.tasked.modular.shared.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated user's id, read from the {@code sub} claim of the validated
 * access token, into a controller method parameter of type {@link java.util.UUID}.
 *
 * <p>This is the <em>only</em> sanctioned way for a handler to learn who is calling. The
 * identity is never read from a path variable, query parameter or request body, so a client
 * cannot address another user's data by editing a payload.
 *
 * @see CurrentUserIdArgumentResolver
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
}
