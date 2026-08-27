# Auth Implementation Guide — How This Was Built, Step by Step

**Project:** `com.tasked.modular` — Spring Boot 4.1.1 · Spring Security 7.1.1 · Java 25 · PostgreSQL 17
**Scope:** authentication, authorization and RBAC, implemented per [`11_AUTH_PIPELINE_SPEC_AND_SPRING_PORT.md`](11_AUTH_PIPELINE_SPEC_AND_SPRING_PORT.md)
**Companion doc:** [`13_AUTH_FLOW_REFERENCE.md`](13_AUTH_FLOW_REFERENCE.md) — the conceptual reference (what every library, class and method does and why)

This document is the *build log*: the order to write things in, the decision made at each fork, and the traps that actually bit during implementation. Read `13` first if you want to understand the design; read this if you want to rebuild it.

---

## Table of Contents

1. [What Was Already Here](#1-what-was-already-here)
2. [Where the Spec Was Adapted](#2-where-the-spec-was-adapted)
3. [Final File Map](#3-final-file-map)
4. [Build Order — 9 Steps](#4-build-order--9-steps)
5. [Traps Hit During Implementation](#5-traps-hit-during-implementation)
6. [Required Database Migration](#6-required-database-migration)
7. [Verification](#7-verification)
8. [Known Limits and What to Do Next](#8-known-limits-and-what-to-do-next)

---

## 1. What Was Already Here

The starting point was a working but auth-less skeleton:

| File | State before |
| :--- | :--- |
| `pom.xml` | Boot 4.1.1, Java 25, actuator, data-jpa, validation, webmvc, postgresql, lombok. **No security dependency.** |
| `ModularApplication.java` | `@SpringBootApplication` + `@EnableJpaAuditing` |
| `shared/enums/Role.java` | `enum Role { User, Admin }` |
| `user/entities/UserEntity.java` | id, email, password, role, refreshToken, createdAt, updatedAt |
| `user/repositories/UserRepo.java` | `findByEmail`, `findByRefreshToken` |
| `user/service/UserService.java` | Two stubs returning strings |
| `user/controller/UserController.java` | `GET /users/hello`, `POST /users` (echoed the DTO back) |
| `user/dtos/CreateUserDto.java` | email + password, `@Size(min=8,max=16)` |
| `application.yaml` | Hardcoded datasource, `ddl-auto: update` |

The entity already had a `refresh_token` column, which is the shape the spec's rotation design needs — so the persistence model needed extending, not replacing.

---

## 2. Where the Spec Was Adapted

The spec (`docs/11`) was written for a **four-module Maven build** (`shared-infra`, `modules/users`, `modules/tasks`, `host-api`) ported from a .NET solution. This project is a **single Maven module with package-level boundaries**, which is the layout `docs/00_PROJECT_STRUCTURE.md` chose. Every adaptation below follows from that, or from Spring Boot 4 being newer than the spec assumed.

| Spec says | This project does | Why |
| :--- | :--- | :--- |
| Maven multi-module, `@Import(UsersModuleConfig.class)` | Single module, package-by-feature under `com.tasked.modular` | Matches `docs/00`. The dependency direction (`user` → `shared`, never the reverse) is preserved by package discipline. |
| Flyway with per-module `locations`, `ddl-auto: validate` | Kept `ddl-auto: update` | Introducing Flyway means baselining the existing database and rewriting the dev workflow — a bigger change than auth itself. `docs/03` already flags Flyway as the intended future step. **See §6: this choice has a sharp edge.** |
| Profile entity, `/users/profile` endpoints | Not implemented | No `ProfileEntity` exists in this project, and profiles are not part of authn/authz/RBAC. Out of scope. |
| Tasks module: protect it, thread `@CurrentUserId` through | Not implemented | There is no tasks module here. The pattern to copy is in `UserController`. |
| `new NimbusJwtEncoder(new ImmutableSecret<>(key))` | `NimbusJwtEncoder.withSecretKey(key).algorithm(HS256).build()` | Spring Security 7 added a builder API. Same object, pinned algorithm, less ceremony. |
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` | Boot 4 renamed the starter. The old id still resolves but is the legacy alias. |
| Refresh sessions as a child table (F-3) | Single `refresh_token` slot on `users` | The spec's own Java reference (§4.12) uses the single slot; the child table is listed under "hardening". Kept parity with the reference, added `refresh_token_expires_at` to close F-4. Upgrade path in §8. |
| Rate limiting via bucket4j (F-10) | `AuthRateLimitFilter`, in-memory, no new dependency | F-10 is rated High. A fixed-window counter over `ConcurrentHashMap` covers the single-instance case honestly; §8 says what to do for multi-instance. |
| Drop `role` from the public signup DTO (F-1) | **`role` kept in `CreateUserDto`**, persisted as sent, defaults to `USER` | Explicit project requirement. Diverges from the spec; see the F-1 note below. |
| Role values `Admin` / `User` (.NET) | `ADMIN` / `USER` | Spec §5.3 mandates the uppercase form. Required because `hasRole('ADMIN')` compares against `ROLE_ADMIN`. |

Findings from the spec's Part 3 that were fixed by construction: **F-2** (a token that fails verification touches nothing), **F-4** (`refresh_token_expires_at`), **F-5** (every secret env-overridable), **F-8** (`@RestControllerAdvice` from day one), **F-9** (tokens generated inline), **F-10** (rate limit), **F-12** (constraint violation → 409). **F-6** (logout does not revoke the access token) is accepted and documented rather than fixed — see `13`.

> **F-1 is deliberately not fixed.** On explicit instruction, `role` is part of `CreateUserDto` and is
> persisted as sent, defaulting to `USER` when omitted. Because `POST /users` is anonymous, **any caller
> can register themselves as an `ADMIN` in one request.** The value set is closed by the enum (an unknown
> role is a 400) and admin signups are logged at `WARN`, but the escalation path is open by design.
> To close it, change one line in `UserService#createUser` — use `Role.USER` instead of
> `dto.roleOrDefault()` — leaving `PATCH /users/{id}/role` as the only route to elevation.

---

## 3. Final File Map

New files are marked `+`, modified `~`.

```
src/main/java/com/tasked/modular/
~ ModularApplication.java              @ConfigurationPropertiesScan; auditing moved out
  shared/
    auth/
+     JwtProperties.java               bound + validated tasked.jwt.*
+     TokenService.java                interface
+     JwtTokenService.java             HS256 mint/verify, two keys
+     TokenSecurityHelper.java         BCrypt(SHA256_HEX_UPPER(token))
+     CurrentUserId.java               parameter annotation
+     CurrentUserIdArgumentResolver.java
+     Policies.java                    @PreAuthorize SpEL constants
    config/
+     SecurityConfig.java              filter chain, decoder, authorities, CORS, encoder
+     WebMvcConfig.java                registers the argument resolver
+     JpaConfig.java                   @EnableJpaAuditing, moved off the main class
    enums/
~     Role.java                        User/Admin  ->  USER/ADMIN
    exception/
+     ApiException.java                base, carries HttpStatus
+     ConflictException.java           409
+     NotFoundException.java           404
+     UnauthorizedException.java       401
+     TooManyRequestsException.java    429
+     ApiErrorResponse.java            the one error envelope
+     GlobalExceptionHandler.java      @RestControllerAdvice
+     JsonAuthenticationEntryPoint.java  401 raised inside the filter chain
+     JsonAccessDeniedHandler.java       403 raised inside the filter chain
    ratelimit/
+     AuthRateLimitFilter.java         fixed window on /login and /refresh-token
  user/
~   entities/UserEntity.java           + refreshTokenExpiresAt, + @Version
~   repositories/UserRepo.java         + existsByEmail, + findByIdForUpdate; - findByRefreshToken
~   service/UserService.java           the whole pipeline
~   controller/UserController.java     7 endpoints
    dtos/
~     CreateUserDto.java               + confirmPassword, complexity, no role
+     SignInDto.java  RotateTokenDto.java  UpdateRoleDto.java
+     TokenResponse.java  UserResponse.java

src/test/java/com/tasked/modular/
+ shared/auth/TokenSecurityHelperTest.java     5 tests
+ shared/auth/JwtTokenServiceTest.java         8 tests
+ user/UserServiceTest.java                   12 tests
+ user/UserControllerSecurityTest.java        11 tests  (@WebMvcTest, real filter chain)
+ user/AuthFlowIntegrationTest.java            6 tests  (@SpringBootTest, real Postgres)
```

`findByRefreshToken` was **deleted**, not kept. The column stores `BCrypt(SHA256(token))` and BCrypt embeds a random salt, so no lookup by raw token value can ever match. Leaving the method would invite someone to write a broken query.

---

## 4. Build Order — 9 Steps

Each step compiles and is testable before the next begins.

### Step 1 — Dependencies

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security-test</artifactId>
  <scope>test</scope>
</dependency>
```

Adding `spring-boot-starter-security` alone locks the whole app behind HTTP Basic with a generated password. That is expected and disappears at Step 5.

### Step 2 — `Role` becomes uppercase

`User, Admin` → `USER, ADMIN`. This is a **breaking schema change** on an existing database; see §6 before running the app.

### Step 3 — Configuration properties

`JwtProperties` as a `@Validated` record, plus the `tasked.jwt` block in `application.yaml`, plus `@ConfigurationPropertiesScan` on `ModularApplication`.

Two guards belong here, not in a service:
- `@Size(min = 32)` on each secret — HS256 rejects shorter keys, and a boot failure beats a runtime one.
- A compact-constructor check that the two secrets differ. If they are equal the whole two-key design silently collapses and nothing else would notice.

### Step 4 — Shared auth primitives

`TokenService` + `JwtTokenService` + `TokenSecurityHelper`. **Unit-test these before any HTTP exists** — they are pure functions and every security property of the pipeline is provable here:

```
mvn test -Dtest='JwtTokenServiceTest,TokenSecurityHelperTest'
```

Write the "access token is rejected by the refresh decoder" test first. If it passes, the key separation is real.

### Step 5 — Security configuration

`SecurityConfig` (filter chain, `accessTokenDecoder`, `jwtAuthenticationConverter`, `passwordEncoder`, `corsConfigurationSource`), the two JSON error handlers, `CurrentUserIdArgumentResolver` and `WebMvcConfig`.

Order inside `authorizeHttpRequests` matters — first match wins — and `.anyRequest().authenticated()` must be **last** so that any endpoint added later is protected by default.

### Step 6 — Exception handling

`ApiException` hierarchy, `ApiErrorResponse`, `GlobalExceptionHandler`. Do this *before* the service so the service can throw instead of returning status codes, and no `try/catch` ever gets written into a controller.

### Step 7 — DTOs and entity

Extend `CreateUserDto` (confirmation, complexity, `max = 72`), add the remaining records, extend `UserEntity` with `refreshTokenExpiresAt` and `@Version`, extend `UserRepo`.

### Step 8 — Service and controller

`UserService` in this order: `createUser` → `signIn` → `signOut` → `rotateTokens`. Rotation depends on login already working, and debugging rotation without a known-good login is miserable.

### Step 9 — Apply RBAC for real

Add `@PreAuthorize(Policies.ADMIN)` to `GET /users` and `PATCH /users/{id}/role`. A role system that is wired but never attached to an endpoint is untested machinery — the admin endpoints exist specifically so the `ROLE_` prefix wiring is exercised by a test.

---

## 5. Traps Hit During Implementation

These were found by running the code, not by reading the spec. Each cost real debugging time.

### 5.1 Revocation silently rolled back — the serious one

Reuse detection wrote `refresh_token = NULL` and then threw `UnauthorizedException`. Spring rolls back on unchecked exceptions by default, **so the revocation was undone on the way out**. The client saw `401 "Token reuse detected. Session revoked."` while the session stayed fully alive — the alert was cosmetic.

This is not hypothetical: the live smoke test caught it. After a detected replay, the rotated token still returned `200` with a fresh pair.

```java
@Transactional(noRollbackFor = UnauthorizedException.class)
public TokenResponse rotateTokens(RotateTokenDto dto) { ... }
```

The obvious alternative — doing the revoke in a `REQUIRES_NEW` transaction — **deadlocks**. The outer transaction already holds a `SELECT ... FOR UPDATE` lock on that user row, and the inner transaction would block forever waiting for it.

> The spec's own §4.12 Java sample has this defect. `users.save(user)` followed by `throw` inside `@Transactional` does not persist the save.

**Mockito cannot catch this.** A mocked repository mutates an in-memory object with no transaction semantics, so `UserServiceTest` passed throughout. `AuthFlowIntegrationTest.reuseDetectionRevocationIsDurable` is the test with teeth — it runs against the real database and is deliberately **not** `@Transactional`, because a test-managed rollback would hide the very behaviour under test. Reverting the fix makes it fail.

### 5.2 A stale `CHECK` constraint from the old enum spelling

Renaming `Role.User` → `Role.USER` produced `409 Conflict` on **every** registration, including brand-new emails.

Hibernate generates a check constraint for `@Enumerated(EnumType.STRING)` columns:

```sql
users_role_check CHECK (role IN ('User', 'Admin'))
```

`ddl-auto: update` adds new columns but **never rewrites an existing check constraint**, so inserting `'USER'` violated it. The `DataIntegrityViolationException` was then correctly mapped to 409 by the global handler — which made a schema problem look like a duplicate-email problem.

Fix in §6.

### 5.3 `@EnableJpaAuditing` on the main class breaks web slice tests

`@WebMvcTest` bootstraps the `@SpringBootConfiguration` class, and anything annotated there applies — including JPA auditing, which then fails with `JPA metamodel must not be empty` because a web slice has no entities.

Moved to `shared/config/JpaConfig.java`. Slice tests do not component-scan ordinary `@Configuration` classes, so they now leave it out. `WebMvcConfig` is imported explicitly for the same reason.

### 5.4 Spring Security 7 package move

`BearerTokenAuthenticationFilter` is in `...oauth2.server.resource.web.authentication`, not `...oauth2.server.resource.web`. The rest of the DSL (`csrf`, `cors`, `sessionManagement`, `authorizeHttpRequests`, `oauth2ResourceServer`, `exceptionHandling`, `AbstractHttpConfigurer::disable`) is unchanged from Security 6.

### 5.5 Jackson 3 in Spring Boot 4

Boot 4 ships **Jackson 3**: `ObjectMapper` is `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind.ObjectMapper`. Anything injecting a mapper directly — here, the two error handlers and the rate-limit filter — must import the new package.

Annotations stayed put: `@JsonInclude` is still `com.fasterxml.jackson.annotation.JsonInclude`, and Jackson 3 honours it.

### 5.6 Test-only JWT API details

- `Jwt#getIssuer()` coerces the claim to a `URL` and throws for a plain name like `tasked-api`. Assert on `getClaimAsString("iss")`. Validation is unaffected — `JwtIssuerValidator` compares strings.
- `JwtClaimsSet.builder()` refuses `expiresAt` at or before `issuedAt`, so an expired token cannot be produced by passing a negative TTL. Sign a backdated claim set directly (see `JwtTokenServiceTest.expiredTokenIsRejected`).

---

## 6. Required Database Migration

**Run this once against any database created before the `Role` rename.** Without it every registration fails with a misleading 409 (§5.2).

```sql
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
UPDATE users SET role = upper(role);
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN'));
```

The constraint name is Hibernate-generated; confirm it first:

```sql
SELECT con.conname, pg_get_constraintdef(con.oid)
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
WHERE rel.relname = 'users';
```

The two new columns (`refresh_token_expires_at`, `version`) are added by `ddl-auto: update` automatically. Note that `version bigint NOT NULL` on a table with existing rows can fail — if it does, add it nullable, backfill `0`, then set `NOT NULL`.

**This is the argument for Flyway.** A versioned migration would have carried the constraint change with the code instead of leaving it to be discovered at runtime.

---

## 7. Verification

### Test suite — 49 tests, all passing

```
mvn test
```

| Suite | Tests | Needs Postgres | Covers |
| :--- | ---: | :--- | :--- |
| `TokenSecurityHelperTest` | 5 | no | hex shape, salting, the 72-byte truncation property |
| `JwtTokenServiceTest` | 8 | no | key separation, zero skew, iss/aud, tampering, jti uniqueness |
| `UserServiceTest` | 14 | no | register/login/rotate/reuse/signout/promote logic |
| `UserControllerSecurityTest` | 13 | no | real filter chain: 401/403 JSON, `ROLE_` prefix, refresh-as-bearer |
| `AuthFlowIntegrationTest` | 8 | **yes** | committed state: reuse revocation durability, promotion |
| `ModularApplicationTests` | 1 | **yes** | full context boots |

The two Postgres-backed suites follow the precedent already set by `ModularApplicationTests`. Point them at Testcontainers to make CI self-contained.

### Live smoke test

Every one of these was run against the running application:

| Check | Result |
| :--- | :--- |
| `POST /users` with no `role` | `201`, account created as `USER` |
| `POST /users` with `"role":"ADMIN"` | `201`, account created as **`ADMIN`**; its token satisfies `hasRole('ADMIN')` |
| `POST /users` with `"role":"SUPERUSER"` | `400`, nothing created |
| duplicate email | `409` |
| invalid signup payload | `400` with per-field messages |
| wrong password vs unknown email | both `401`, **identical** message |
| decoded access token | `{sub, aud, role, iss, exp, iat, jti, email}` — flat `role`, no Microsoft URI |
| `GET /users/me` with access token | `200` |
| `GET /users/me` with no token | `401` JSON |
| `GET /users/me` with the **refresh** token as bearer | `401` |
| `GET /users` as `USER` | `403` JSON |
| rotate | `200`, both tokens differ from the originals |
| replay the rotated token | `401` reuse detected |
| the newest token after a detected replay | `401` session gone |
| forged JWT with a victim's `sub`, foreign key | `401`, victim's session **untouched** |
| sign out, then sign out again | `200`, `200` |
| refresh after sign out | `401` |
| 12 rapid logins from one IP | `401`×9 then `429`×3 |

---

## 8. Known Limits and What to Do Next

Ordered by value.

1. **One session per user.** The single `refresh_token` slot means signing in on a phone ends the laptop session. Move to a `refresh_tokens` child table (`id, user_id, token_hash, expires_at, revoked_at, user_agent, created_at`) with a unique index on `token_hash`. Reuse detection then revokes the whole *family* descended from the replayed token rather than the whole account, which is both safer and less disruptive.

2. **Flyway.** Own the schema in versioned SQL and switch to `ddl-auto: validate`. §6 exists only because the schema is not under version control.

3. **Rate limiting is per-JVM.** Correct for one instance, wrong behind a load balancer — the effective limit multiplies by replica count. Move to Redis + Bucket4j, or to the gateway. Also consider keying on IP + email so one noisy IP cannot lock out an account.

4. **Secrets have development defaults.** `application.yaml` ships working values so a fresh clone runs. Every one is env-overridable; set `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET` and `DB_PASSWORD` in any deployed environment and consider removing the inline defaults entirely so a missing secret fails at boot.

5. **Sign-out does not revoke the access token** (spec F-6). Accepted: the 15-minute TTL bounds it. If that is too wide, keep a `jti` denylist in Redis with a TTL equal to the token's remaining lifetime — the `jti` claim is already emitted for exactly this purpose.

6. **Tokens are returned in the JSON body**, so a browser client must store them where JavaScript — and therefore XSS — can reach. Consider `HttpOnly; Secure; SameSite=Strict` cookie delivery for the refresh token with the access token held in memory only. This is a client-contract change, not a server-only one.

7. **No audit trail.** Reuse detection and role changes are logged at `WARN`/`INFO` but not persisted. A security-events table would make incident review possible.

8. **`X-Forwarded-For` is trusted for its first hop** in the rate-limit filter. Behind an untrusted proxy that header is client-controlled and spoofable — configure `server.forward-headers-strategy` and a trusted-proxy list before relying on it.
