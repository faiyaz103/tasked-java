# Tech Stack & Architecture Reference

A single-page inventory of everything this project is built from: architecture style, framework
and build tooling, persistence, request validation, JWT, authentication, authorization/RBAC,
error handling, testing, and the full package layout.

> Generated from the state of the `authn-authz` branch. The source of truth is always the code —
> `pom.xml`, `src/main/resources/application.yaml`, and `shared/config/SecurityConfig.java`.

---

## 1. Architecture

| Aspect | Choice | Where it lives | Notes |
| --- | --- | --- | --- |
| Architectural style | **Modular monolith** (package-by-feature) | `com.tasked.modular.*` | One deployable JAR; modularity enforced at the package level, not by separate Maven modules |
| Module boundary | A feature package owns its own `controller` / `service` / `entities` / `repositories` / `dtos` | `user/` | A module can be lifted out into a service later without touching siblings |
| Cross-cutting layer | `shared/` package | `shared/auth`, `shared/config`, `shared/exception`, `shared/enums`, `shared/ratelimit` | The only place other modules are allowed to depend on |
| Layering | Controller → Service → Repository → Entity | `user/` | Controllers are thin: no `try/catch`, no manual validation, no `ResponseEntity` in services |
| Boundary rule | **Entities never cross the HTTP boundary** | `UserResponse`, `CreateUserDto`, … | DTO records in / DTO records out, so `password` and `refreshToken` cannot leak through a serializer |
| Session model | **Stateless** — `SessionCreationPolicy.STATELESS` | `SecurityConfig` | No `HttpSession`, no sticky sessions, horizontally scalable |
| Error model | Centralised translation via `@RestControllerAdvice` + filter-chain handlers | `shared/exception` | One JSON envelope (`ApiErrorResponse`) for *every* failure, including 401/403 raised before the dispatcher |
| Identity propagation | `@CurrentUserId UUID` resolved from the token's `sub` claim | `shared/auth` | Identity is never read from a path variable, query parameter, or request body |
| Config binding | Type-safe `@ConfigurationProperties` record, validated at boot | `JwtProperties` | Misconfiguration aborts startup instead of failing at first login |
| Entry point | `ModularApplication` (`@SpringBootApplication`, `@ConfigurationPropertiesScan`) | `ModularApplication.java` | JPA auditing is deliberately kept off the main class so web-slice tests can boot it |

---

## 2. Framework, Language & Build

| Category | Tool / Technology | Version / Detail | Purpose |
| --- | --- | --- | --- |
| Language | **Java** | 25 (`<java.version>25</java.version>`) | Records, `HexFormat`, pattern matching for `instanceof` |
| Framework | **Spring Boot** | 4.1.1 (`spring-boot-starter-parent`) | Auto-configuration, dependency management, embedded server |
| Web layer | `spring-boot-starter-webmvc` | Servlet stack (Tomcat) | REST controllers, `DispatcherServlet`, argument resolvers |
| Build tool | **Maven** (+ Maven Wrapper) | Wrapper 3.3.4 → Maven 3.9.16 | `./mvnw` for reproducible builds |
| Packaging | `spring-boot-maven-plugin` | — | Executable fat JAR |
| Boilerplate reduction | **Project Lombok** | `optional`, wired as an annotation processor in `maven-compiler-plugin` | `@Getter/@Setter`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j` |
| JSON | **Jackson 3** (`tools.jackson.databind.ObjectMapper`) | Bundled with Boot 4 | Serialization; injected into the filter-chain error handlers |
| Logging | SLF4J via Lombok `@Slf4j` + Logback | `logging.level.org.springframework.security: INFO` | Auth events at INFO; admin signup and token reuse at WARN |
| Observability | `spring-boot-starter-actuator` | Only `health` exposed, `show-details: never` | `/actuator/health/**` is `permitAll` and leaks no infrastructure detail |
| Server | Embedded Tomcat | Port `8080` | — |
| CORS | Spring `CorsConfigurationSource` | Explicit origin list, `allowCredentials(true)` | A wildcard is impossible with credentials; origins come from `CORS_ALLOWED_ORIGINS` |

---

## 3. Database & Persistence

| Category | Tool / Technology | Detail | Purpose |
| --- | --- | --- | --- |
| RDBMS | **PostgreSQL** | `org.postgresql:postgresql`, `runtime` scope | Primary datastore |
| Driver | `org.postgresql.Driver` | `jdbc:postgresql://localhost:5432/tasked_java` (env-overridable) | JDBC connectivity |
| Data access | **Spring Data JPA** (`spring-boot-starter-data-jpa`) | `UserRepo extends JpaRepository<UserEntity, UUID>` | Derived queries, CRUD, `@Query` |
| ORM | **Hibernate** | Boot-managed | JPA provider |
| Connection pool | **HikariCP** | Boot default | Pooling |
| Schema management | `spring.jpa.hibernate.ddl-auto: update` | ⚠️ Development convenience | Production should move to `validate` with **Flyway** owning the DDL |
| Lazy-loading policy | `spring.jpa.open-in-view: false` | — | No lazy loading after the transaction closes; forces explicit fetching |
| Time zone | `hibernate.jdbc.time_zone: UTC` | — | Timestamps round-trip as UTC regardless of server locale |
| SQL visibility | `spring.jpa.show-sql: true` | Development | — |
| Auditing | `@EnableJpaAuditing` in `JpaConfig` + `AuditingEntityListener` | `@CreatedDate`, `@LastModifiedDate` | `created_at` / `updated_at` populated automatically; kept off the main class so `@WebMvcTest` slices don't fail with "JPA metamodel must not be empty" |
| Transactions | `@Transactional` (Spring) | `readOnly = true` on reads; `noRollbackFor = UnauthorizedException.class` on rotation | The `noRollbackFor` is what lets a reuse-detection revoke **commit** while the request still fails |
| Pessimistic locking | `@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE` | `UserRepo#findByIdForUpdate` | Serialises the compare-and-swap in refresh rotation so a concurrent replay cannot win twice |
| Optimistic locking | `@Version private Long version` | `UserEntity` | Hibernate appends `WHERE version = ?`; a collision raises `OptimisticLockingFailureException` → 409 |
| ID strategy | `@GeneratedValue(strategy = GenerationType.UUID)` | `UserEntity.id` | Non-enumerable identifiers |
| Enum storage | `@Enumerated(EnumType.STRING)` | `users.role` | Stores `USER` / `ADMIN` as text, not ordinals |

### 3.1 `users` table

| Column | Java type | Constraints | Purpose |
| --- | --- | --- | --- |
| `id` | `UUID` | PK, `updatable = false`, `nullable = false` | Identity; also the JWT `sub` claim |
| `email` | `String` | `nullable = false`, **`unique = true`** | Login key; the unique index is the real uniqueness guarantee |
| `password` | `String` | `nullable = false`, `TEXT` | BCrypt (cost 11) digest — never plaintext, never encrypted |
| `role` | `Role` enum | `nullable = false`, default `USER` | RBAC source of truth |
| `refresh_token` | `String` | `TEXT`, nullable | `BCrypt(SHA256_HEX_UPPER(refresh_jwt))` of the one currently-valid refresh token; `null` = no active session |
| `refresh_token_expires_at` | `LocalDateTime` | nullable | Server-side session expiry, independent of the JWT's own `exp` |
| `version` | `Long` | `nullable = false`, default `0` | Optimistic-lock counter |
| `created_at` | `LocalDateTime` | `nullable = false`, `updatable = false` | `@CreatedDate` |
| `updated_at` | `LocalDateTime` | nullable | `@LastModifiedDate` |

> **Single-session design:** one refresh slot per row means signing in on a second device silently
> ends the first. Lifting that requires a child `refresh_tokens` table with one row per session.

---

## 4. Request Validation

| Category | Tool / Technology | Detail | Purpose |
| --- | --- | --- | --- |
| Specification | **Jakarta Bean Validation** | `spring-boot-starter-validation` | Declarative constraints |
| Implementation | **Hibernate Validator** | Boot-managed | Constraint engine |
| Trigger — body | `@Valid @RequestBody` on controller methods | `UserController` | Runs *before* the method body; there is no manual validation call anywhere |
| Trigger — config | `@Validated` on `@ConfigurationProperties` | `JwtProperties` | Turns constraints into **boot-time** checks |
| Field constraints used | `@NotBlank`, `@NotNull`, `@Email`, `@Size`, `@Pattern` | `CreateUserDto`, `SignInDto`, `RotateTokenDto`, `UpdateRoleDto` | Shape of each input |
| Cross-field constraint | `@AssertTrue public boolean isPasswordConfirmed()` | `CreateUserDto` | The record-friendly way to express a rule spanning two components; reported as field `passwordConfirmed` |
| Type-closure validation | Component typed as the `Role` **enum**, not `String` | `CreateUserDto`, `UpdateRoleDto` | An unknown role fails deserialization → 400, so no invalid role can reach the database |
| Failure mapping | `MethodArgumentNotValidException` → 400 with a `fieldErrors` map | `GlobalExceptionHandler` | One message per field, first-wins |
| DTO style | Java `record` | all of `user/dtos` | Immutable, no Lombok needed |

### 4.1 Validation rules by DTO

| DTO | Field | Rules |
| --- | --- | --- |
| `CreateUserDto` | `email` | `@NotBlank`, `@Email`, `@Size(max = 100)` |
| | `password` | `@NotBlank`, `@Size(min = 8, max = 72)`, `@Pattern` uppercase, `@Pattern` digit |
| | `confirmPassword` | `@NotBlank` plus the `@AssertTrue` cross-field match |
| | `role` | Optional; `null` → `USER` via `roleOrDefault()` |
| `SignInDto` | `email`, `password` | `@NotBlank` (plus `@Email`); **no** complexity rules — re-validating at login would leak the policy and lock out legacy passwords |
| `RotateTokenDto` | `token` | `@NotBlank` |
| `UpdateRoleDto` | `role` | `@NotNull` (enum-closed) |
| `JwtProperties` | `issuer`, `audience` | `@NotBlank` |
| | `accessSecret`, `refreshSecret` | `@NotBlank`, `@Size(min = 32)` plus a compact-constructor "must differ" check |
| | `accessTtl`, `refreshTtl` | `@NotNull` (ISO-8601 `Duration`) |

> The **72-character password ceiling** is not arbitrary: BCrypt silently truncates its input at
> 72 bytes, so a longer password would have its tail ignored. Rejecting the input beats silently
> weakening it.

---

## 5. JWT

| Aspect | Choice | Detail |
| --- | --- | --- |
| Library | **Nimbus JOSE + JWT**, via `spring-boot-starter-security-oauth2-resource-server` | `NimbusJwtEncoder` / `NimbusJwtDecoder` |
| Algorithm | **HS256** (`MacAlgorithm.HS256`), pinned on both encoder and decoder | Pinning closes the `alg: none` and algorithm-confusion attack classes |
| Key material | **Two independent secrets** — `access-secret` and `refresh-secret` | Startup fails if they are equal or shorter than 32 bytes |
| Access token TTL | `tasked.jwt.access-ttl`, default `PT1M` (dev) | A short TTL bounds the damage of a stolen access token, since sign-out cannot revoke one |
| Refresh token TTL | `tasked.jwt.refresh-ttl`, default `PT2M` (dev) | Long-lived in production; safe because every use rotates it |
| Claims emitted | `sub`, `email`, `role`, `jti`, `iss`, `aud`, `iat`, `exp` | Flat `role` claim name — read directly by `JwtAuthenticationConverter`, no inbound remapping |
| `jti` | `UUID.randomUUID()` per token | Unique per token; enables a denylist later |
| Validation (both types) | `JwtTimestampValidator(Duration.ZERO)` + `JwtIssuerValidator` + `JwtClaimValidator(aud)` | **Zero clock skew** — the default 60-second leeway is removed, so a token is dead at exactly `exp` |
| Access-token verification | `JwtDecoder accessTokenDecoder` bean, wired into the resource server | The filter chain verifies before any controller runs |
| Refresh-token verification | `JwtTokenService#validateRefreshToken`, called by hand inside rotation | Never reaches the filter chain |
| Transport — access | `Authorization: Bearer <jwt>` header | — |
| Transport — refresh | JSON request body (`RotateTokenDto.token`) | A different transport slot makes client misuse obvious |
| Storage — refresh | `BCrypt(SHA256_HEX_UPPER(token))` in `users.refresh_token` | A database dump cannot be replayed |
| Config source | `JwtProperties` record bound to `tasked.jwt.*`, registered by `@ConfigurationPropertiesScan` | Every value is env-overridable (`JWT_ISSUER`, `JWT_ACCESS_SECRET`, …) |

> **Why two keys is the central design decision:** because each token type is signed with its own
> key, an access token replayed at `/users/refresh-token` — or a refresh token presented as a
> `Bearer` credential — fails *signature verification* before any application code runs. The two
> token types are non-interchangeable **cryptographically**, not merely by convention.

---

## 6. Authentication (AuthN)

| Component | Technology | Responsibility |
| --- | --- | --- |
| Starter | `spring-boot-starter-security` | Filter chain, `BCryptPasswordEncoder`, method security |
| Starter | `spring-boot-starter-security-oauth2-resource-server` | Stateless Bearer-token validation |
| Filter chain | `SecurityFilterChain` bean in `SecurityConfig` | CSRF disabled, CORS on, `STATELESS`; form login, HTTP Basic and logout all disabled |
| Bearer filter | `BearerTokenAuthenticationFilter` (auto-wired by `oauth2ResourceServer`) | Extracts and verifies the access token |
| Password hashing | `BCryptPasswordEncoder(11)` exposed as `PasswordEncoder` | ~100–200 ms per verification; per-row random salt; returned as the interface so Argon2 can be swapped in |
| Refresh-token hashing | `TokenSecurityHelper` — `BCrypt` (cost 10) over `SHA-256` uppercase hex | The SHA-256 pre-hash exists because BCrypt truncates at 72 bytes; the hex casing is part of the input and must stay uppercase |
| Identity injection | `@CurrentUserId` + `CurrentUserIdArgumentResolver` (registered by `WebMvcConfig`) | Reads `sub` from the validated token; a non-UUID subject is a 401, not a 500 |
| Rate limiting | `AuthRateLimitFilter` — dependency-free, in-memory, fixed window | 10 attempts / 1 min, keyed on `path + client IP`, applied to `POST /users/login` and `POST /users/refresh-token`; runs **before** `BearerTokenAuthenticationFilter` so a flood never pays for a BCrypt verify |
| 401 rendering | `JsonAuthenticationEntryPoint` | Filter-chain failures never reach `@RestControllerAdvice`; this keeps the JSON envelope consistent |
| 403 rendering | `JsonAccessDeniedHandler` | The same, for authorization denials |
| CSRF | **Disabled**, deliberately | No cookies and no session: the credential is a header the browser never attaches automatically |

### 6.1 Session lifecycle

| Flow | Endpoint | What happens |
| --- | --- | --- |
| Register | `POST /users` | Unique-email pre-check → BCrypt(11) the password → save. **No tokens issued** — registration and authentication stay separate code paths |
| Login | `POST /users/login` | Look up by email → `passwordEncoder.matches` → mint a pair → store the refresh hash and server-side expiry. Both failure branches return the *same* message, so the endpoint is not a user-enumeration oracle |
| Refresh rotation | `POST /users/refresh-token` | 1) verify against the refresh secret → 2) take `sub` from the signed payload → 3) load the row `FOR UPDATE` and require an active session → 4) check server-side expiry → 5) **reuse detection** → 6) rotate |
| Reuse detection | — | A cryptographically valid token that is no longer *the stored one* has already been rotated, which means someone kept a copy. The whole session is **destroyed**, not merely refused, forcing both parties back through login |
| Sign out | `POST /users/signout` | Clears `refresh_token` and `refresh_token_expires_at`. Idempotent. **The access token is not revoked** and stays usable until `exp` — the accepted cost of stateless auth |

---

## 7. Authorization & RBAC

| Aspect | Choice | Detail |
| --- | --- | --- |
| Model | **Flat, single role per user** | `Role` enum: `USER`, `ADMIN` |
| Enum as single source of truth | One constant drives three things | the value in `users.role`, the JWT `role` claim, and the Spring authority suffix |
| Method security | `@EnableMethodSecurity` + `@PreAuthorize` | RBAC is stated at the method that has the requirement, so it survives a path remap |
| Policy constants | `Policies.AUTHENTICATED` / `.ADMIN` / `.USER` / `.ELEVATED` | `static final String` (SpEL must be a compile-time constant); keeps each expression in exactly one place |
| Claim → authority | `JwtAuthenticationConverter` bean grants `ROLE_ + role.toUpperCase()` | A missing or blank claim grants **no** authorities |
| Principal name | `converter.setPrincipalClaimName(SUB)` | `Authentication#getName()` returns the user id — convenient for audit logging |
| URL-level rules | `authorizeHttpRequests` with **deny-by-default** (`anyRequest().authenticated()`) | A newly added endpoint is protected unless someone explicitly opts out |
| Defence in depth | URL rules **and** `@PreAuthorize` on the same endpoints | Intentional redundancy |
| Denial semantics | 401 = "I do not know who you are"; 403 = "I know who you are and you may not do this" | `JsonAuthenticationEntryPoint` vs `JsonAccessDeniedHandler` |
| Role change propagation | `updateRole` clears the session | Already-issued access tokens keep the old `role` claim until `exp`; clearing the refresh slot forces a re-login that picks up the new role |

> **The `ROLE_` trap.** `hasRole('ADMIN')` silently prepends `ROLE_` before comparing; `hasAuthority`
> does not. This codebase grants `ROLE_ADMIN` / `ROLE_USER` and uses `hasRole` **everywhere**.
> Mixing the two conventions fails open into a silent 403 that looks like a permissions bug.

### 7.1 Endpoint authorization matrix

| Method | Path | URL rule | Method rule | Rate-limited |
| --- | --- | --- | --- | --- |
| `GET` | `/users/hello` | `permitAll` | — | — |
| `POST` | `/users` | `permitAll` | — | — |
| `POST` | `/users/login` | `permitAll` | — | ✅ 10 / min / IP |
| `POST` | `/users/refresh-token` | `permitAll` | — | ✅ 10 / min / IP |
| `POST` | `/users/signout` | authenticated | `@PreAuthorize(AUTHENTICATED)` | — |
| `GET` | `/users/me` | authenticated | `@PreAuthorize(AUTHENTICATED)` | — |
| `GET` | `/users` | authenticated | `@PreAuthorize(ADMIN)` | — |
| `PATCH` | `/users/{id}/role` | authenticated | `@PreAuthorize(ADMIN)` | — |
| `GET` | `/actuator/health/**` | `permitAll` | — | — |
| `OPTIONS` | `/**` | `permitAll` | — | A CORS preflight carries no `Authorization` header by definition |

> ⚠️ **Known, documented behaviour:** `POST /users` is anonymous *and* honours a client-supplied
> `role`, so any caller can register themselves as `ADMIN` (admin signups are logged at `WARN`).
> To close it, replace `dto.roleOrDefault()` with `Role.USER` in `UserService#createUser`, leaving
> `PATCH /users/{id}/role` as the only elevation path.

---

## 8. Error Handling

| Component | Type | Covers |
| --- | --- | --- |
| `ApiErrorResponse` | `record` + `@JsonInclude(NON_NULL)` | The single envelope: `timestamp, status, error, message, path, fieldErrors?` |
| `ApiException` | Abstract base carrying an `HttpStatus` | Services throw these instead of returning status codes |
| `ConflictException` | 409 | Duplicate email |
| `UnauthorizedException` | 401 | Bad credentials; invalid, expired or revoked token; token reuse |
| `NotFoundException` | 404 | Missing resource |
| `TooManyRequestsException` | 429 | Rate-limit trip |
| `GlobalExceptionHandler` | `@RestControllerAdvice` | `ApiException`; `MethodArgumentNotValidException` → 400; `HttpMessageNotReadableException` → 400; `MethodArgumentTypeMismatchException` → 400; `DataIntegrityViolationException` → 409; `OptimisticLockingFailureException` → 409; `AccessDeniedException` → 403; `JwtException` → 401 |
| `JsonAuthenticationEntryPoint` | `AuthenticationEntryPoint` | 401s raised **inside the filter chain**, which `@RestControllerAdvice` cannot see |
| `JsonAccessDeniedHandler` | `AccessDeniedHandler` | 403s raised inside the filter chain |

---

## 9. Testing

| Category | Tool / Technology | Detail |
| --- | --- | --- |
| Test runner | **JUnit 5** (Jupiter) | `@Test`, `@DisplayName`, `@BeforeEach`, `@AfterEach` |
| Assertions | **AssertJ** | `assertThat`, `assertThatThrownBy`, `assertThatCode` |
| Mocking | **Mockito** + `@MockitoBean` (Spring bean override) | `ArgumentCaptor` used to assert what actually gets persisted |
| Web slice | `@WebMvcTest` + `MockMvc` (`org.springframework.boot.webmvc.test.autoconfigure`) | `UserControllerSecurityTest` |
| Full context | `@SpringBootTest` + `@AutoConfigureMockMvc` | `AuthFlowIntegrationTest` |
| Security testing | `spring-boot-starter-security-test` | Authority and role assertions against the filter chain |
| JPA testing | `spring-boot-starter-data-jpa-test` | — |
| Validation testing | `spring-boot-starter-validation-test` | — |
| Property overrides | `@TestPropertySource`, `@EnableConfigurationProperties` | Deterministic JWT secrets and TTLs in tests |
| Test classes | `JwtTokenServiceTest`, `TokenSecurityHelperTest`, `UserServiceTest`, `UserControllerSecurityTest`, `AuthFlowIntegrationTest`, `ModularApplicationTests` | — |

---

## 10. Configuration Reference

| Property | Env var | Default (dev) | Meaning |
| --- | --- | --- | --- |
| `server.port` | — | `8080` | HTTP port |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/tasked_java` | JDBC URL |
| `spring.datasource.username` | `DB_USERNAME` | `postgres` | Database user |
| `spring.datasource.password` | `DB_PASSWORD` | `1234` | Database password |
| `spring.jpa.hibernate.ddl-auto` | — | `update` | ⚠️ Move to `validate` + Flyway in production |
| `spring.jpa.open-in-view` | — | `false` | No post-transaction lazy loading |
| `tasked.jwt.issuer` | `JWT_ISSUER` | `tasked-api` | `iss` claim, validated on decode |
| `tasked.jwt.audience` | `JWT_AUDIENCE` | `tasked-app` | `aud` claim, validated on decode |
| `tasked.jwt.access-secret` | `JWT_ACCESS_SECRET` | dev placeholder | HS256 key for access tokens (≥ 32 bytes) |
| `tasked.jwt.refresh-secret` | `JWT_REFRESH_SECRET` | dev placeholder | HS256 key for refresh tokens (≥ 32 bytes, **must differ**) |
| `tasked.jwt.access-ttl` | `JWT_ACCESS_TTL` | `PT1M` | ISO-8601 `Duration` |
| `tasked.jwt.refresh-ttl` | `JWT_REFRESH_TTL` | `PT2M` | ISO-8601 `Duration` |
| `cors.allowed-origins` | `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Explicit list; a wildcard is impossible with credentials |
| `management.endpoints.web.exposure.include` | — | `health` | Only the health endpoint is exposed |

> Every inline default is a **development** value and must be overridden in any deployed
> environment. A secret committed to source control is a secret that has already leaked.

---

## 11. Project Structure

```text
f:\modular
│
├── pom.xml                                   # Maven build — Boot 4.1.1 parent, Java 25
├── mvnw / mvnw.cmd / .mvn/                   # Maven Wrapper (3.9.16)
├── HELP.md
│
├── docs/
│   ├── 00_PROJECT_STRUCTURE.md               # Modular-monolith conventions
│   ├── 01_WIKIS.md
│   ├── 02_VALIDATION.md                      # Bean Validation notes
│   ├── 03_DATABASE.md                        # JPA / PostgreSQL notes
│   ├── 11_AUTH_PIPELINE_SPEC_AND_SPRING_PORT.md
│   ├── 12_AUTH_IMPLEMENTATION.md
│   ├── 13_AUTH_FLOW_REFERENCE.md
│   └── 14_TECH_STACK_AND_ARCHITECTURE.md     # <- this file
│
└── src/
    ├── main/
    │   ├── java/com/tasked/modular/
    │   │   │
    │   │   ├── ModularApplication.java        # @SpringBootApplication + @ConfigurationPropertiesScan
    │   │   │
    │   │   ├── shared/                        # ---- Cross-cutting concerns ----------------
    │   │   │   ├── auth/
    │   │   │   │   ├── CurrentUserId.java                  # @interface — parameter marker
    │   │   │   │   ├── CurrentUserIdArgumentResolver.java  # sub claim -> UUID
    │   │   │   │   ├── JwtProperties.java                  # @ConfigurationProperties record, @Validated
    │   │   │   │   ├── JwtTokenService.java                # HS256 encoders + strict decoder
    │   │   │   │   ├── TokenService.java                   # mint / verify interface
    │   │   │   │   ├── TokenSecurityHelper.java            # BCrypt(SHA-256 hex) for refresh tokens
    │   │   │   │   └── Policies.java                       # @PreAuthorize SpEL constants
    │   │   │   │
    │   │   │   ├── config/
    │   │   │   │   ├── SecurityConfig.java                 # filter chain, decoder, converter, CORS, encoder
    │   │   │   │   ├── WebMvcConfig.java                   # registers the argument resolver
    │   │   │   │   └── JpaConfig.java                      # @EnableJpaAuditing (kept off the main class)
    │   │   │   │
    │   │   │   ├── enums/
    │   │   │   │   └── Role.java                           # USER | ADMIN
    │   │   │   │
    │   │   │   ├── exception/
    │   │   │   │   ├── ApiErrorResponse.java               # the single JSON error envelope
    │   │   │   │   ├── ApiException.java                   # abstract, carries HttpStatus
    │   │   │   │   ├── ConflictException.java              # 409
    │   │   │   │   ├── NotFoundException.java              # 404
    │   │   │   │   ├── UnauthorizedException.java          # 401
    │   │   │   │   ├── TooManyRequestsException.java       # 429
    │   │   │   │   ├── GlobalExceptionHandler.java         # @RestControllerAdvice
    │   │   │   │   ├── JsonAuthenticationEntryPoint.java   # filter-chain 401
    │   │   │   │   └── JsonAccessDeniedHandler.java        # filter-chain 403
    │   │   │   │
    │   │   │   └── ratelimit/
    │   │   │       └── AuthRateLimitFilter.java            # in-memory fixed window, 10/min/IP
    │   │   │
    │   │   └── user/                          # ---- MODULE 1: User domain -----------------
    │   │       ├── controller/
    │   │       │   └── UserController.java                 # @RestController("users")
    │   │       ├── service/
    │   │       │   └── UserService.java                    # register / login / signout / rotate / admin reads
    │   │       ├── entities/
    │   │       │   └── UserEntity.java                     # @Entity -> users
    │   │       ├── repositories/
    │   │       │   └── UserRepo.java                       # JpaRepository + FOR UPDATE query
    │   │       └── dtos/
    │   │           ├── CreateUserDto.java                  # in  — signup
    │   │           ├── SignInDto.java                      # in  — login
    │   │           ├── RotateTokenDto.java                 # in  — refresh
    │   │           ├── UpdateRoleDto.java                  # in  — admin role change
    │   │           ├── TokenResponse.java                  # out — access + refresh pair
    │   │           └── UserResponse.java                   # out — safe user view
    │   │
    │   └── resources/
    │       └── application.yaml               # datasource, JPA, tasked.jwt.*, cors, actuator
    │
    └── test/java/com/tasked/modular/
        ├── ModularApplicationTests.java
        ├── shared/auth/
        │   ├── JwtTokenServiceTest.java
        │   └── TokenSecurityHelperTest.java
        └── user/
            ├── UserServiceTest.java                        # unit, Mockito
            ├── UserControllerSecurityTest.java             # @WebMvcTest slice
            └── AuthFlowIntegrationTest.java                # @SpringBootTest end-to-end
```

### 11.1 Package responsibilities

| Package | Responsibility | May depend on |
| --- | --- | --- |
| `shared.auth` | Token minting and verification, identity injection, policy constants | `shared.enums`, `shared.exception` |
| `shared.config` | Composition root — security, MVC and JPA wiring | `shared.auth`, `shared.ratelimit` |
| `shared.enums` | Domain-wide enumerations (`Role`) | — (leaf) |
| `shared.exception` | Error envelope, exception hierarchy, handlers | — |
| `shared.ratelimit` | Pre-auth throttling filter | `shared.exception` |
| `user.controller` | HTTP mapping, `@Valid`, `@PreAuthorize`, `@CurrentUserId` | `user.dtos`, `user.service`, `shared.auth` |
| `user.service` | Business logic, transactions, token lifecycle | `user.repositories`, `user.dtos`, `shared.*` |
| `user.repositories` | Data access | `user.entities` |
| `user.entities` | JPA mapping | `shared.enums` |
| `user.dtos` | HTTP contracts (records) | `shared.enums` |

---

## 12. Known Limitations / Production Checklist

| Item | Current state | Production move |
| --- | --- | --- |
| Schema management | `ddl-auto: update` | `validate` plus **Flyway** migrations |
| Secrets | Dev defaults inline in `application.yaml` | Inject `JWT_*` / `DB_*` from a secret manager |
| Token TTLs | `PT1M` / `PT2M` (deliberately tiny for testing) | e.g. `PT15M` / `P7D` |
| Rate limiting | In-memory, per-JVM, IP-keyed | Redis + Bucket4j, or push it to the gateway — behind a load balancer the effective limit multiplies by the replica count |
| `X-Forwarded-For` | First hop trusted unconditionally | Configure `server.forward-headers-strategy` and a trusted-proxy list |
| Sessions per user | One (single `refresh_token` column) | Child `refresh_tokens` table, one row per device |
| Access-token revocation | Not possible before `exp` | `jti` denylist in Redis with TTL equal to the remaining lifetime |
| Token transport | Both tokens in the JSON body, reachable by XSS | Access token in memory only; refresh token as an `HttpOnly; Secure; SameSite=Strict` cookie |
| Public admin signup | `POST /users` honours a client-supplied `role` | Force `Role.USER`; elevate only via `PATCH /users/{id}/role` |
