package com.tasked.modular.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables {@code @CreatedDate} / {@code @LastModifiedDate} population on entities.
 *
 * <p>Deliberately a separate {@code @Configuration} rather than an annotation on
 * {@code ModularApplication}. Anything declared on the main class is applied by <em>every</em>
 * test that bootstraps it, including web slices such as {@code @WebMvcTest} that have no
 * persistence layer - and JPA auditing then fails to start with "JPA metamodel must not be
 * empty". Keeping it here lets slice tests leave it out, because they do not component-scan
 * ordinary configuration classes.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
