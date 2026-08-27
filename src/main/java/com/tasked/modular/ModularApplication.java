package com.tasked.modular;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point.
 *
 * <p>JPA auditing is enabled in {@link com.tasked.modular.shared.config.JpaConfig} rather than
 * here, so that web slice tests can bootstrap this class without a persistence layer.
 *
 * <p>{@code @ConfigurationPropertiesScan} is what registers
 * {@link com.tasked.modular.shared.auth.JwtProperties} as a bean. Without it the record is
 * never bound and {@code JwtTokenService} cannot be constructed - the scan is the reason the
 * JWT settings are validated at startup rather than discovered to be wrong at first login.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ModularApplication {

	public static void main(String[] args) {
		SpringApplication.run(ModularApplication.class, args);
	}

}
