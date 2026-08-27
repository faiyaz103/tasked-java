# Tasked — To-Do List Backend

A REST API backend for a to-do list application, built as a **modular monolith** on Spring Boot 4
with stateless JWT authentication, refresh-token rotation, and role-based access control.

> **Current state:** the `shared/` (cross-cutting) and `user/` (identity, auth, RBAC) modules are
> complete. The task/to-do module is the next feature package to land — it will follow the same
> `controller / service / entities / repositories / dtos` layout described below.

---

## Architecture

| Aspect | Choice | Notes |
| --- | --- | --- |
| Style | **Modular monolith** (package-by-feature) | One deployable JAR; boundaries enforced at the package level |
| Module layout | Each feature owns `controller` / `service` / `entities` / `repositories` / `dtos` | A module can be lifted into its own service later without touching siblings |
| Cross-cutting code | `shared/` package | `auth`, `config`, `exception`, `enums`, `ratelimit` — the only package siblings may depend on |
| Layering | Controller → Service → Repository → Entity | Controllers stay thin: no `try/catch`, no manual validation, no `ResponseEntity` in services |
| Boundary rule | Entities never cross the HTTP boundary | DTO records in, DTO records out — `password` / `refreshToken` cannot leak through a serializer |
| Session model | **Stateless** (`SessionCreationPolicy.STATELESS`) | No `HttpSession`, no sticky sessions, horizontally scalable |
| Identity propagation | `@CurrentUserId UUID` resolved from the token's `sub` claim | Identity is never read from a path variable, query param, or body |
| Error model | `@RestControllerAdvice` + filter-chain handlers | One JSON envelope (`ApiErrorResponse`) for *every* failure, including 401/403 raised before the dispatcher |
| Config binding | Type-safe `@ConfigurationProperties` records, validated at boot | Misconfiguration aborts startup instead of failing at first login |

---

## Tools & Technologies

| Category | Technology | Detail / Purpose |
| --- | --- | --- |
| Language | **Java 25** | Records, pattern matching, `HexFormat` |
| Framework | **Spring Boot 4.1.1** | Auto-configuration, dependency management, embedded server |
| Web layer | **Spring Web MVC** (`spring-boot-starter-webmvc`) | REST controllers on the servlet stack |
| Server | **Embedded Tomcat** | Port `8080` |
| Build | **Maven** + Maven Wrapper (`./mvnw`) | `spring-boot-maven-plugin` produces an executable fat JAR |
| Database | **PostgreSQL** | Primary datastore, `runtime`-scoped driver |
| Data access | **Spring Data JPA** / **Hibernate** | `JpaRepository`, derived queries, `@Query` |
| Connection pool | **HikariCP** | Spring Boot default |
| Schema | `ddl-auto: update` | ⚠️ Development convenience — production should use `validate` + Flyway |
| Auditing | `@EnableJpaAuditing` + `AuditingEntityListener` | `created_at` / `updated_at` populated automatically |
| Concurrency | `@Version` (optimistic) + `PESSIMISTIC_WRITE` (`SELECT … FOR UPDATE`) | Serialises the compare-and-swap in refresh-token rotation |
| Validation | **Jakarta Bean Validation** (`spring-boot-starter-validation`) | `@Valid` on DTOs → `MethodArgumentNotValidException` → 400 with `fieldErrors` |
| Security | **Spring Security** | Filter chain, `@PreAuthorize` method security, `BCryptPasswordEncoder` (cost 11) |
| Tokens | **OAuth2 Resource Server** + Nimbus JOSE | Stateless HS256 JWT bearer validation, strict issuer/audience/`exp`, zero clock skew |
| JSON | **Jackson 3** | Bundled with Boot 4; injected into the filter-chain error handlers |
| Boilerplate | **Project Lombok** | `@Getter/@Setter`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` |
| Logging | **SLF4J + Logback** | Auth events at INFO; admin signup and token reuse at WARN |
| Observability | **Spring Boot Actuator** | Only `/actuator/health` exposed, `show-details: never` |
| Testing | **JUnit 5**, **AssertJ**, **Mockito**, **MockMvc** | `@WebMvcTest` slices + `@SpringBootTest` integration flow |