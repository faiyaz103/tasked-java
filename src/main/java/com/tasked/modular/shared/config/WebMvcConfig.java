package com.tasked.modular.shared.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.tasked.modular.shared.auth.CurrentUserIdArgumentResolver;

import lombok.RequiredArgsConstructor;

/**
 * Registers {@link CurrentUserIdArgumentResolver} with Spring MVC.
 *
 * <p>Declaring the resolver as a {@code @Component} is not enough on its own: MVC only
 * consults resolvers it has been told about. Without this class a
 * {@code @CurrentUserId UUID userId} parameter would fall through to the default resolver and
 * be treated as a missing request parameter.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
