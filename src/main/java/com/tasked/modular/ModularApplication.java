package com.tasked.modular;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class ModularApplication {

	public static void main(String[] args) {
		SpringApplication.run(ModularApplication.class, args);
	}

}
