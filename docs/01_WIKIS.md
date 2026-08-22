**Build Tools (Project)**

* **Maven:** Uses an XML file (`pom.xml`). It is the standard Java build tool with strict conventions, extreme stability, and universal support across corporate environments.
* **Gradle - Groovy:** Uses a Groovy-based scripting syntax (`build.gradle`). It builds faster than Maven due to incremental caching and flexible build logic.
* **Gradle - Kotlin:** Uses Kotlin (`build.gradle.kts`) for build scripts. It offers superior IDE auto-completion, compile-time type-checking, and easier refactoring than Groovy.

**Packaging**

* **JAR (Java Archive):** Bundles your compiled application along with an embedded web server (Tomcat) into a single executable file (`java -jar app.jar`). This is the modern default for microservices and cloud deployments.
* **WAR (Web Application Archive):** Designed to be deployed onto a separate, externally managed application server (such as WildFly or external Tomcat). Primarily used in legacy enterprise infrastructure.

**Configuration Formats**

* **Properties (`.properties`):** Uses a flat `key=value` structure (`spring.datasource.url=...`). Simple, but requires repeating prefixes for nested options.
* **YAML (`.yaml`):** Uses structured indentation. It eliminates repetitive prefixes, making complex configurations much cleaner to manage.

**Core Dependencies**

* **Spring Web:** The core library for REST APIs. Includes Spring MVC, automatic JSON translation, and an embedded web server.
* **Spring Data JPA:** Uses Hibernate behind the scenes to map Java classes (`@Entity`) directly to database tables, handling database operations without writing manual SQL queries.
* **PostgreSQL Driver:** The essential database connector that allows Spring Boot's JDBC framework to communicate with your PostgreSQL server.
* **Validation:** Provides data guardrails (like `@NotNull`, `@Size`, `@Email`) to automatically validate incoming API request bodies before processing them in your service layer.
* **Lombok:** A compile-time annotation processor that automatically generates getters, setters, constructors, and builders, keeping your Java files short and clean.