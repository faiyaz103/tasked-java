# Authentication, Authorization & RBAC — Complete Flow Reference

**Project:** `com.tasked.modular` · Spring Boot 4.1.1 · Spring Security 7.1.1 · Java 25 · PostgreSQL
**Companion doc:** [`12_AUTH_IMPLEMENTATION.md`](12_AUTH_IMPLEMENTATION.md) — the build log, adaptations and traps

This document explains the pipeline from first principles: what every library, package, class and method does, why it is there, and what breaks without it. It assumes no prior Spring Security knowledge.

---

## Table of Contents

1. [The Three Words](#1-the-three-words)
2. [Why Tokens At All](#2-why-tokens-at-all)
3. [Anatomy of a JWT](#3-anatomy-of-a-jwt)
4. [The Two-Secret Design](#4-the-two-secret-design)
5. [Storing Secrets: Passwords and Tokens](#5-storing-secrets-passwords-and-tokens)
6. [Library and Package Reference](#6-library-and-package-reference)
7. [The Filter Chain — What Happens to Every Request](#7-the-filter-chain--what-happens-to-every-request)
8. [Authorization and RBAC](#8-authorization-and-rbac)
9. [Flow 1 — Registration](#9-flow-1--registration)
10. [Flow 2 — Login](#10-flow-2--login)
11. [Flow 3 — Authenticated Request](#11-flow-3--authenticated-request)
12. [Flow 4 — Refresh Rotation and Reuse Detection](#12-flow-4--refresh-rotation-and-reuse-detection)
13. [Flow 5 — Sign Out](#13-flow-5--sign-out)
14. [Flow 6 — Role Promotion](#14-flow-6--role-promotion)
15. [Error Handling](#15-error-handling)
16. [Rate Limiting](#16-rate-limiting)
17. [Configuration Reference](#17-configuration-reference)
18. [API Reference](#18-api-reference)
19. [Method Index](#19-method-index)
20. [Threat Model](#20-threat-model)

---

## 1. The Three Words

They are constantly conflated. They are three separate questions, answered at three separate points.

| Term | The question | Where answered | Failure |
| :--- | :--- | :--- | :--- |
| **Authentication** (authn) | *Who are you?* | The security filter chain, from the bearer token | `401 Unauthorized` |
| **Authorization** (authz) | *May you do this?* | `@PreAuthorize` / `authorizeHttpRequests` | `403 Forbidden` |
| **RBAC** | *How is "may you" decided?* | Roles carried as a token claim → granted authorities | — |

RBAC is one *model* of authorization: permissions attach to roles, roles attach to users, users get the union. The alternatives — ABAC (attribute-based), ReBAC (relationship-based), ACLs — answer the same question differently.

**401 vs 403 is the distinction to keep straight.** 401 means "I don't know who you are" (no token, expired token, bad signature). 403 means "I know exactly who you are, and you may not do this". Returning 401 for an authorization failure is a common and confusing bug.

### The model used here

Flat, single-role-per-user, role-carried-in-token:

- One role per user, stored in `users.role`, one of `USER` or `ADMIN`.
- The role is chosen at registration (defaulting to `USER`), then stamped into every token at mint time.
- Checks are made against the token's claim — **no database lookup per request**.
- There is no permission table and no role-permission join.

The trade-off is explicit: this is fast and stateless, but a role change does not affect tokens already issued. §14 shows how that window is closed.

Resource ownership is handled separately and implicitly. Every query is scoped by the user id taken from the token (`getCurrentUser(userId)`), so a user cannot address another user's row through these endpoints at all — there is no authorization *rule* to get wrong because there is no reachable path.

---

## 2. Why Tokens At All

The classical alternative is a **server-side session**: the server stores session state, hands the client an opaque cookie, and looks that cookie up on every request.

| | Session + cookie | JWT bearer token |
| :--- | :--- | :--- |
| Server state | Session store, must be shared across instances | None |
| Per-request cost | A store lookup | A signature verification (microseconds, in-process) |
| Scaling | Sticky sessions or a shared Redis | Any instance can serve any request |
| Revocation | Immediate — delete the session | **Not immediate** — valid until `exp` |
| CSRF | Vulnerable; browsers attach cookies automatically | Not vulnerable; browsers never attach an `Authorization` header on their own |

Revocation is the real cost of the token model, and it is why this design uses **two** tokens:

- **Access token** — short-lived (15 min), sent on every request, never stored server-side. Cannot be revoked, so its lifetime is kept short.
- **Refresh token** — long-lived (7 days), sent only to `/users/refresh-token`, its hash stored server-side. *Can* be revoked, because the server holds a record of it.

That split is what buys both statelessness and a revocation story. The access token is stateless and cheap; the refresh token is the single stateful anchor where session control lives.

---

## 3. Anatomy of a JWT

A JWT is three Base64URL segments joined by dots: `header.payload.signature`.

An actual access token from this system, decoded:

```json
// header
{ "kid": "LTTEw7WFQkYNDgMjJnL10ndow61ry4GKM5cChDQvg5s", "alg": "HS256" }

// payload
{
  "sub":   "4c24a1a1-2344-468f-9ae3-c3b8fcd6bf6f",
  "aud":   "tasked-app",
  "role":  "USER",
  "iss":   "tasked-api",
  "exp":   1787692288,
  "iat":   1787691388,
  "jti":   "740b720e-c936-4281-ae3c-9bd1ec323e1e",
  "email": "smoke@test.com"
}
```

| Claim | Meaning | Why it is here |
| :--- | :--- | :--- |
| `sub` | Subject — the user id | The identity. `@CurrentUserId` reads exactly this. |
| `iss` | Issuer | Rejects tokens minted by another system that happens to share the key. |
| `aud` | Audience | Rejects tokens minted for a different application. |
| `exp` | Expiry (epoch seconds) | The only thing bounding a stolen access token. |
| `iat` | Issued at | Audit; also lets you reject tokens older than a policy allows. |
| `jti` | Unique token id | Not used yet — it is what a revocation denylist would key on. |
| `role` | `USER` or `ADMIN` | The RBAC input. Flat name, deliberately. |
| `email` | Convenience | Saves a lookup for display purposes. |

### Three things about JWTs that bite people

**The payload is encoded, not encrypted.** Anyone holding the token can read every claim — paste it into jwt.io. Never put a secret in a claim. The signature guarantees *integrity* (nobody altered it), not *confidentiality*.

**`alg: none` and algorithm confusion.** A decoder that trusts the header's `alg` can be tricked into skipping verification entirely, or into verifying an RS256 token using the public key as an HMAC secret. This is why the decoder pins the algorithm:

```java
NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()
```

A token arriving with any other `alg` is rejected before its signature is examined.

**Claim-name portability.** The .NET source system emitted the role under `http://schemas.microsoft.com/ws/2008/06/identity/claims/role`, and .NET's handler remapped `sub` and `email` to similar URIs on the way in. Java performs no such remapping. This implementation emits a plain `role` and reads `sub` as `sub` — simpler, and interoperable with anything that is not .NET.

---

## 4. The Two-Secret Design

**This is the single most important decision in the pipeline.**

Access tokens are signed with `tasked.jwt.access-secret`. Refresh tokens are signed with `tasked.jwt.refresh-secret`. The two keys are independent.

The consequence is that the two token types are **cryptographically non-interchangeable**:

- An access token presented at `/users/refresh-token` fails signature verification. It is not "rejected by a rule"; it is mathematically invalid there.
- A refresh token presented as `Authorization: Bearer ...` fails the same way. Verified live: it returns `401`.

Compare the alternative — one key plus a `"type": "access"` claim. That works only as long as every code path remembers to check the claim. One forgotten check and a 7-day refresh token becomes a 7-day access token. With two keys there is no check to forget.

Three cryptographic objects implement it:

| Object | Key | Lives in | Used by |
| :--- | :--- | :--- | :--- |
| `accessEncoder` | access | `JwtTokenService` | minting access tokens |
| `refreshEncoder` | refresh | `JwtTokenService` | minting refresh tokens |
| `refreshDecoder` | refresh | `JwtTokenService` | rotation, called by application code |
| `accessTokenDecoder` bean | access | `SecurityConfig` | the filter chain, every request |

There is deliberately **no access decoder inside `JwtTokenService`** and **no refresh decoder in the filter chain**. Each key can only be used where it belongs.

The design is enforced at startup rather than trusted:

```java
public JwtProperties {
    if (accessSecret != null && accessSecret.equals(refreshSecret)) {
        throw new IllegalStateException(
            "tasked.jwt.access-secret and tasked.jwt.refresh-secret must be different keys. ...");
    }
}
```

Misconfiguring them to the same value silently collapses the entire property, so the application refuses to start.

### Validation applied to every token

Both decoders are built by `JwtTokenService.strictDecoder`:

```java
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(Duration.ZERO),      // zero clock skew
        new JwtIssuerValidator(props.issuer()),
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(props.audience()))));
```

| Validator | Rejects |
| :--- | :--- |
| signature (implicit) | Any token not signed with this exact key |
| `MacAlgorithm.HS256` pin | `alg: none`, algorithm confusion |
| `JwtTimestampValidator(Duration.ZERO)` | Anything past `exp`. **Zero skew** — the default allows 60 seconds of grace. |
| `JwtIssuerValidator` | Tokens from another issuer |
| `JwtClaimValidator` on `aud` | Tokens minted for another application |

`DelegatingOAuth2TokenValidator` composes them; all must pass.

---

## 5. Storing Secrets: Passwords and Tokens

Two different secrets, two different treatments, both using BCrypt but for different reasons.

### 5.1 Passwords — `BCryptPasswordEncoder(11)`

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(11);
}
```

**Hashed, never encrypted.** Encryption is reversible; there is no legitimate reason for the server to ever recover a plaintext password, so making it *impossible* removes a whole category of breach.

**Why BCrypt and not SHA-256?** SHA-256 is designed to be fast — billions of guesses per second on a GPU, which is precisely wrong for passwords. BCrypt is deliberately slow and its cost is tunable.

**Work factor 11** means 2¹¹ = 2048 internal iterations, roughly 100–200 ms per verification. Slow enough that brute-forcing a leaked table is expensive; fast enough that a user does not notice. Every increment doubles the cost — raise it as hardware improves.

**Salting is automatic.** BCrypt generates a random salt per hash and stores it in the output, so two users with the same password get different hashes and precomputed rainbow tables are worthless. This is why `passwordEncoder.matches(raw, stored)` exists and `encode(raw).equals(stored)` never works.

The output looks like `$2a$11$N9qo8uLOickgx2ZMRZoMye...` — algorithm, cost, then salt+hash.

### 5.2 Refresh tokens — `BCrypt(SHA256_HEX_UPPER(token))`

```java
public static String doubleHashToken(String rawToken) {
    return TOKEN_ENCODER.encode(sha256Hex(rawToken));   // BCryptPasswordEncoder(10)
}
```

**Why hash a token at all?** So that a database dump is not a set of working credentials. The server never stores anything replayable.

**Why SHA-256 first?** Because **BCrypt silently truncates its input at 72 bytes**. A refresh JWT is 300+ bytes. Hashing it directly would cover only the header and part of the payload — the signature would never be part of what is stored, and two tokens sharing a 72-byte prefix would be interchangeable. SHA-256 collapses the token to a fixed 64-character string that depends on every byte.

`TokenSecurityHelperTest.differencesBeyondBcryptTruncationPointStillMatter` proves this with two 110-character tokens identical for their first 100 characters.

**Uppercase hex is load-bearing.** The hex string is BCrypt's input, so changing its casing would invalidate every stored hash at once. `HexFormat.of().withUpperCase()` keeps it fixed.

**Work factor 10, not 11.** This runs on every rotation, and the pre-image is a 256-bit digest rather than a low-entropy human password — the brute-force resistance already comes from the token's own entropy.

### 5.3 Summary

| Secret | Stored as | Work factor | Reversible |
| :--- | :--- | :--- | :--- |
| Password | `BCrypt(password)` | 11 | no |
| Refresh token | `BCrypt(SHA256_HEX_UPPER(jwt))` | 10 | no |
| Access token | **not stored at all** | — | — |

---

## 6. Library and Package Reference

### Maven dependencies

| Dependency | What it provides | Without it |
| :--- | :--- | :--- |
| `spring-boot-starter-security` | `SecurityFilterChain`, `@EnableWebSecurity`, `@EnableMethodSecurity`, `@PreAuthorize`, `BCryptPasswordEncoder`, `SecurityContextHolder` | No security at all |
| `spring-boot-starter-security-oauth2-resource-server` | Bearer-token authentication, `NimbusJwtDecoder`/`NimbusJwtEncoder`, `JwtAuthenticationConverter`; pulls in `nimbus-jose-jwt` | No JWT support; you would hand-roll parsing and verification |
| `spring-boot-starter-validation` | Jakarta Bean Validation via Hibernate Validator — `@Valid`, `@NotBlank`, `@Email`, `@Pattern`, `@AssertTrue` | Manual `if` checks in every controller |
| `spring-boot-starter-data-jpa` | `JpaRepository`, `@Transactional`, `@Lock`, Hibernate | Hand-written JDBC |
| `spring-boot-starter-webmvc` | `@RestController`, `DispatcherServlet`, embedded Tomcat | No HTTP |
| `spring-boot-starter-security-test` | `SecurityMockMvcRequestPostProcessors` and friends | Harder security testing |
| `lombok` | `@RequiredArgsConstructor`, `@Getter/@Setter`, `@Builder`, `@Slf4j` | Boilerplate |

> **Boot 4 naming.** The starter is `spring-boot-starter-security-oauth2-resource-server`; `spring-boot-starter-oauth2-resource-server` is the legacy alias. Boot 4 also renamed `spring-boot-starter-web` to `spring-boot-starter-webmvc`.

### Key classes by package

**`org.springframework.security.oauth2.jwt`**

| Class | Role |
| :--- | :--- |
| `NimbusJwtEncoder` | Signs a claim set. Built via `withSecretKey(key).algorithm(HS256).build()`. |
| `NimbusJwtDecoder` | Parses and verifies. Built via `withSecretKey(key).macAlgorithm(HS256).build()`. |
| `JwtClaimsSet` | Builder for the payload — `issuer`, `audience`, `subject`, `claim`, `id`, `issuedAt`, `expiresAt`. |
| `JwsHeader` | The header. `JwsHeader.with(MacAlgorithm.HS256).build()`. |
| `JwtEncoderParameters` | Ties header + claims together for `encode`. |
| `Jwt` | A verified token. `getSubject()`, `getClaimAsString(name)`, `getId()`. |
| `JwtTimestampValidator` | Expiry check; takes the allowed clock skew. |
| `JwtIssuerValidator` | `iss` check. |
| `JwtClaimValidator<T>` | Generic claim predicate — used here for `aud`. |
| `JwtException` | Thrown on any verification failure. |

> `Jwt#getIssuer()` returns a `URL` and throws for a plain name like `tasked-api`. Use `getClaimAsString("iss")`. Validation is unaffected.

**`org.springframework.security.oauth2.server.resource`**

| Class | Role |
| :--- | :--- |
| `authentication.JwtAuthenticationToken` | The `Authentication` produced from a valid token. `getToken()` returns the `Jwt`. |
| `authentication.JwtAuthenticationConverter` | Turns a `Jwt` into an `Authentication`: which claim is the principal, which claims become authorities. |
| `web.authentication.BearerTokenAuthenticationFilter` | Reads the `Authorization: Bearer` header. Note the `.web.authentication` package in Security 7. |

**`org.springframework.security.config.annotation.web`**

| Symbol | Role |
| :--- | :--- |
| `@EnableWebSecurity` | Activates the filter-chain machinery. |
| `HttpSecurity` | The DSL: `csrf`, `cors`, `sessionManagement`, `authorizeHttpRequests`, `oauth2ResourceServer`, `exceptionHandling`, `addFilterBefore`. |
| `AbstractHttpConfigurer::disable` | Method reference used to switch a feature off. |
| `@EnableMethodSecurity` | Activates `@PreAuthorize`. **Without it every `@PreAuthorize` is silently ignored** — the most dangerous single omission possible here. |

**`org.springframework.security.core`**

| Symbol | Role |
| :--- | :--- |
| `SecurityContextHolder` | Thread-local holding the current `Authentication`. |
| `Authentication` | Who the caller is, plus their authorities. |
| `GrantedAuthority` / `SimpleGrantedAuthority` | A single permission string, e.g. `ROLE_ADMIN`. |

**Project packages**

```
com.tasked.modular.shared
├── auth/          tokens, hashing, @CurrentUserId, policy constants
├── config/        SecurityConfig, WebMvcConfig, JpaConfig
├── enums/         Role
├── exception/     the exception hierarchy and both error renderers
└── ratelimit/     AuthRateLimitFilter

com.tasked.modular.user
├── controller/  service/  repositories/  entities/  dtos/
```

`user` depends on `shared`. `shared` never depends on `user` — it holds no domain logic, only cross-cutting mechanism. Keeping that direction one-way is what lets a second module be added later without entangling it with users.

---

## 7. The Filter Chain — What Happens to Every Request

Spring Security is a chain of servlet filters that runs **before** the `DispatcherServlet`, and therefore before any controller, before `@Valid`, and before `@RestControllerAdvice`.

```
HTTP request
     │
     ▼
┌─────────────────────────────────────────────────────────────┐
│  SecurityFilterChain                                        │
│                                                             │
│  1. CorsFilter                    preflight / origin check  │
│  2. AuthRateLimitFilter           429 on /login,/refresh    │
│  3. BearerTokenAuthenticationFilter                         │
│        reads "Authorization: Bearer <token>"                │
│        → accessTokenDecoder.decode(token)                   │
│              signature · alg · exp · iss · aud              │
│        → JwtAuthenticationConverter                         │
│              sub   → principal name                         │
│              role  → ROLE_<ROLE> authority                  │
│        → SecurityContextHolder.setAuthentication(...)       │
│        ✗ any failure → JsonAuthenticationEntryPoint → 401   │
│                                                             │
│  4. AuthorizationFilter           authorizeHttpRequests      │
│        ✗ denied → JsonAccessDeniedHandler → 403             │
└─────────────────────────────────────────────────────────────┘
     │  authenticated and URL-authorized
     ▼
┌─────────────────────────────────────────────────────────────┐
│  DispatcherServlet                                          │
│    @PreAuthorize            method-level RBAC → 403          │
│    @Valid                   Bean Validation  → 400           │
│    CurrentUserIdArgumentResolver   sub → UUID parameter      │
│    UserController → UserService → UserRepo → PostgreSQL      │
│    @RestControllerAdvice    exceptions → JSON                │
└─────────────────────────────────────────────────────────────┘
```

### `SecurityConfig.securityFilterChain`, line by line

```java
.csrf(AbstractHttpConfigurer::disable)
```
CSRF attacks work because browsers attach cookies automatically to cross-site requests. An `Authorization` header is never attached automatically, so there is nothing to forge. With no session and no auth cookie, CSRF protection would only add a token no client could submit. **If you ever move the refresh token into a cookie, this decision must be revisited.**

```java
.cors(Customizer.withDefaults())
```
Activates the `CorsConfigurationSource` bean. Browsers block cross-origin reads unless the server opts in. Origins are listed explicitly because `allowCredentials(true)` is incompatible with `*`.

```java
.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```
Never create an `HttpSession`. Every request re-authenticates from its token. This is what allows any instance to serve any request with no sticky sessions.

```java
.formLogin(...disable).httpBasic(...disable).logout(...disable)
```
Turn off the servlet defaults being replaced. Without this, adding the security starter locks the app behind a generated-password login form.

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(HttpMethod.POST, "/users", "/users/login", "/users/refresh-token").permitAll()
    .requestMatchers(HttpMethod.GET, "/users/hello").permitAll()
    .requestMatchers("/actuator/health/**").permitAll()
    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
    .anyRequest().authenticated())
```
**First match wins, so order matters.** The three credential endpoints must be anonymous — you cannot present a token in order to obtain your first one. `OPTIONS` is open because CORS preflights carry no `Authorization` header by definition.

`.anyRequest().authenticated()` is **deny-by-default** and must be last: any endpoint added in future is protected unless someone deliberately opens it. The opposite default is how endpoints get shipped unprotected.

```java
.oauth2ResourceServer(oauth2 -> oauth2
    .jwt(jwt -> jwt.decoder(accessTokenDecoder)
                   .jwtAuthenticationConverter(jwtAuthenticationConverter))
    .authenticationEntryPoint(entryPoint)
    .accessDeniedHandler(deniedHandler))
```
The bearer-token machinery. Note it receives the **access** decoder only.

```java
.addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class);
```
Rate limiting runs before authentication, so a flood of bad credentials is rejected without paying for a BCrypt verification each time.

### Why the two error handlers exist

`@RestControllerAdvice` only sees exceptions that reach the `DispatcherServlet`. Authentication and URL-level authorization failures happen in the filter chain and **never get there**. Without these two handlers, a 401 returns an empty body while every other error in the API returns JSON.

| Handler | Produces | When |
| :--- | :--- | :--- |
| `JsonAuthenticationEntryPoint` | 401 + `ApiErrorResponse` | No token, bad signature, expired, wrong issuer/audience |
| `JsonAccessDeniedHandler` | 403 + `ApiErrorResponse` | Authenticated but lacking the required authority |

Both are registered twice — on `oauth2ResourceServer` and on `exceptionHandling` — to cover failures raised before the resource-server configurer runs.

---

## 8. Authorization and RBAC

### From claim to authority

```java
@Bean
public JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        String role = jwt.getClaimAsString("role");
        if (role == null || role.isBlank()) return List.<GrantedAuthority>of();
        return List.<GrantedAuthority>of(
                new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
    });
    converter.setPrincipalClaimName(JwtClaimNames.SUB);
    return converter;
}
```

`role: "ADMIN"` → authority `ROLE_ADMIN`. `setPrincipalClaimName(SUB)` makes `Authentication#getName()` the user id, which is what you want in audit logs.

### ⚠ The `ROLE_` prefix trap

**The single most common RBAC bug in Spring.**

`hasRole('ADMIN')` **implicitly prepends `ROLE_`** and looks for the authority `ROLE_ADMIN`. `hasAuthority('ADMIN')` does not prepend anything and looks for exactly `ADMIN`.

If the converter grants a bare `ADMIN` while the annotation says `hasRole('ADMIN')`, every check fails with a 403 that looks like a permissions problem rather than the naming problem it is. There is no warning and no log line.

Pick one convention and never mix:

| Granted authority | Correct expression |
| :--- | :--- |
| `ROLE_ADMIN` | `hasRole('ADMIN')` ← **this project** |
| `ADMIN` | `hasAuthority('ADMIN')` |

`UserControllerSecurityTest.adminTokenReachesAdminEndpoint` is the regression test.

### Named policies

```java
public final class Policies {
    public static final String AUTHENTICATED = "isAuthenticated()";
    public static final String ADMIN         = "hasRole('ADMIN')";
    public static final String USER          = "hasRole('USER')";
    public static final String ELEVATED      = "hasAnyRole('ADMIN','USER')";
}
```

Constants rather than literals, so widening a rule or renaming a role is one edit rather than a grep. They are `static final String` because `@PreAuthorize` requires a compile-time constant.

`ELEVATED` is **OR**, not AND — a user needs one of the listed roles.

### Two layers, on purpose

| Layer | Declared in | Good for |
| :--- | :--- | :--- |
| URL-level | `authorizeHttpRequests` | Broad strokes: what is anonymous, deny-by-default |
| Method-level | `@PreAuthorize` | Per-endpoint rules that should travel with the code |

The method-level annotations on authenticated-only endpoints are technically redundant given `.anyRequest().authenticated()`. They are kept because they state the requirement *at* the method: remap the controller to a different path and the guarantee moves with it instead of being left behind in a URL pattern.

### `@CurrentUserId` — identity that cannot be forged

```java
@GetMapping("me")
@PreAuthorize(Policies.AUTHENTICATED)
public UserResponse getCurrentUser(@CurrentUserId UUID userId) {
    return userService.getCurrentUser(userId);
}
```

`CurrentUserIdArgumentResolver` pulls `sub` from the verified token in `SecurityContextHolder`. The identity is **never** read from a path variable, query parameter or request body, so a client cannot reach another user's data by editing a payload. Notice there is no `GET /users/{id}` for ordinary users at all.

Registration is required — declaring the resolver as a `@Component` is not enough:

```java
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
```

---

## 9. Flow 1 — Registration

```
POST /users
{ "email": "a@b.com", "password": "Password1", "confirmPassword": "Password1", "role": "ADMIN" }
// "role" is optional; omit it and the account is created as USER
```

```
filter chain: permitAll, no token required
     ▼
@Valid CreateUserDto
     ├─ @Email, @Size(max = 100)
     ├─ @Size(min = 8, max = 72), @Pattern uppercase, @Pattern digit
     └─ @AssertTrue isPasswordConfirmed()
     ✗ → 400 with per-field messages
     ▼
UserService.createUser
     ├─ userRepo.existsByEmail(...)  →  ConflictException  →  409
     ├─ passwordEncoder.encode(password)          BCrypt wf 11
     ├─ role = dto.roleOrDefault()                as sent; null → USER
     └─ userRepo.save(user)
     ▼
201  "Registration Successful for a@b.com"
```

### The `role` field in `CreateUserDto`

`role` is an **optional** component of `CreateUserDto`, typed as the `Role` enum. Omit it and the account is created as `USER`; send `"role":"ADMIN"` and the account is created as an administrator.

The enum type closes the value set by construction — `"role":"SUPERUSER"` fails deserialization and becomes a `400`, so no unknown role can reach the database.

> ⚠️ **`POST /users` is anonymous, so this is a one-request privilege escalation.** Anybody who can reach the endpoint can register themselves as an `ADMIN`. This is the deliberate, instructed behaviour of this contract, not an oversight; admin signups are logged at `WARN` so they are at least visible in the audit trail.
>
> To restrict it, change one line in `UserService#createUser`:
>
> ```java
> Role role = Role.USER;              // was: dto.roleOrDefault()
> ```
>
> That leaves `PATCH /users/{id}/role` — guarded by `hasRole('ADMIN')` — as the only path to elevation.

Promotion happens only through `PATCH /users/{id}/role`, guarded by `hasRole('ADMIN')` (§14).

### Why `max = 72`

BCrypt truncates at 72 bytes. Without the ceiling, an 80-character password would have its tail silently ignored and the user could log in with only the first 72 characters. Rejecting the input is better than silently weakening it.

### `@AssertTrue` on a record

Records cannot carry a cross-field constraint on a component, so Bean Validation calls any `isXxx()` method annotated `@AssertTrue`:

```java
@AssertTrue(message = "Passwords do not match")
public boolean isPasswordConfirmed() {
    return password != null && password.equals(confirmPassword);
}
```

The reported field name derives from the method, so the client sees `passwordConfirmed`.

### No tokens are issued here

Registration and authentication stay separate. The client must call `/users/login` afterwards. That keeps exactly one code path for "how does a session begin" — the one that gets audited and rate-limited.

---

## 10. Flow 2 — Login

```
POST /users/login
{ "email": "a@b.com", "password": "Password1" }
```

```
AuthRateLimitFilter        10/min per IP  →  429
     ▼
@Valid SignInDto           @NotBlank @Email + @NotBlank
     ▼
UserService.signIn
     ├─ userRepo.findByEmail(...)
     │     empty → UnauthorizedException("Invalid email or password")
     ├─ passwordEncoder.matches(raw, stored)
     │     false → UnauthorizedException("Invalid email or password")   ← same message
     └─ issueAndStore(user)
           ├─ generateAccessToken   HS256, access secret,  15 min
           ├─ generateRefreshToken  HS256, refresh secret,  7 days
           ├─ refreshToken          = BCrypt(SHA256(raw))
           ├─ refreshTokenExpiresAt = now + 7 days
           └─ save
     ▼
200  { "accessToken": "...", "refreshToken": "..." }
```

### Why both failures return the same message

If "no such email" and "wrong password" differed, the endpoint becomes a **user-enumeration oracle**: an attacker harvests which addresses have accounts before guessing a single password. That list is valuable on its own and makes targeted phishing easy.

`UserServiceTest.loginFailuresAreIndistinguishable` asserts the two messages are equal. Verified live: both return `401` with `"Invalid email or password"`.

> A rigorous implementation also equalises *timing* — the unknown-email path skips BCrypt and returns measurably faster. Mitigate by verifying against a dummy hash when no user is found.

### Login does not re-validate password shape

`SignInDto` requires only `@NotBlank`, not the complexity rules from `CreateUserDto`. Applying them at login would leak the password policy to anyone who can hit the endpoint, and would lock out any account whose password predates the current rules. At login the only question is whether the credential verifies.

### The raw refresh token exists exactly once

The response carries the raw JWT. The server stores only `BCrypt(SHA256(...))`. From this moment the plaintext exists only on the client — which is what makes a database dump non-replayable.

---

## 11. Flow 3 — Authenticated Request

```
GET /users/me
Authorization: Bearer eyJraWQiOiJMVFRF...
```

```
BearerTokenAuthenticationFilter
     ├─ extract token
     ├─ accessTokenDecoder.decode(token)
     │      HS256 pinned · signature · exp (zero skew) · iss · aud
     │      ✗ → JsonAuthenticationEntryPoint → 401 JSON
     ├─ JwtAuthenticationConverter
     │      sub  → principal name
     │      role → ROLE_USER
     └─ SecurityContextHolder holds a JwtAuthenticationToken
     ▼
AuthorizationFilter          .anyRequest().authenticated()  ✓
     ▼
@PreAuthorize("isAuthenticated()")  ✓
     ▼
CurrentUserIdArgumentResolver       sub → UUID
     ▼
UserService.getCurrentUser(userId)  →  UserResponse
     ▼
200  { "id": "...", "email": "...", "role": "USER", "createdAt": "..." }
```

No database lookup was needed to *authenticate* — the signature is the proof. The single query is the one that fetches the resource being asked for.

Verified live:

| Request | Result |
| :--- | :--- |
| valid access token | `200` |
| no `Authorization` header | `401` JSON |
| garbage bearer value | `401` JSON |
| **refresh** token as bearer | `401` — wrong signing key |
| `USER` token on `GET /users` | `403` JSON |

---

## 12. Flow 4 — Refresh Rotation and Reuse Detection

**The most important flow in the system.** Everything else is standard; this is what makes a long-lived credential safe.

```
POST /users/refresh-token
{ "token": "<raw refresh JWT>" }
```

```
① tokenService.validateRefreshToken(token)
      signature (REFRESH secret) · alg · exp (zero skew) · iss · aud
      ✗ JwtException  →  401 "Refresh token is expired or invalid."
                          ← and NOTHING is modified. See "the forgery trap".
      ▼
② userId = UUID.fromString(jwt.getSubject())
      ✗ → 401 "Invalid token payload."
      ▼
③ user = userRepo.findByIdForUpdate(userId)          SELECT ... FOR UPDATE
      missing, or refresh_token is null
        → 401 "Access denied. Active session not found."
      ▼
④ refreshTokenExpiresAt in the past?
        → clear session, 401 "Access denied. Active session not found."
      ▼
⑤ verifyDoubleHashedToken(token, user.refreshToken)
      ✗  ►►  REUSE DETECTED
              refresh_token = NULL          (committed — see noRollbackFor)
              log.warn(...)
              401 "Security alert: Token reuse detected. Session revoked."
      ▼
⑥ issueAndStore(user)
      new access + new refresh
      refresh_token = BCrypt(SHA256(new))   ← the presented token is now dead
      ▼
200  { "accessToken": "...", "refreshToken": "..." }
```

### Why rotation makes theft detectable

Step ⑥ overwrites the stored hash, so **each refresh token is single-use**.

Now consider a stolen token. Both the attacker and the legitimate client hold a copy. Whoever uses it second presents a token that is cryptographically perfect — valid signature, correct issuer, not expired — but no longer matches what the server stores. There is only one way that can happen: someone kept a copy.

So the server treats it as theft and **destroys the session** rather than merely refusing the request. Both parties are forced back through a full login, which the attacker cannot complete without the password.

Verified live, in order:

| Step | Result |
| :--- | :--- |
| rotate `R1` | `200`, receives `R2`; both tokens differ from the originals |
| replay `R1` | `401 "Security alert: Token reuse detected. Session revoked."` |
| then use `R2` | `401 "Access denied. Active session not found."` — the whole session is gone |

### The forgery trap — why step ① touches nothing

It is tempting to be helpful on a validation failure: parse the token *without* verifying the signature, read `sub`, and clear that user's session "just in case".

**That is a remote, unauthenticated session-kill.** Anyone can hand-craft a JWT carrying a victim's `sub` — the payload is just Base64 — and log that victim out repeatedly, forever. A denial-of-service against any account whose id you can guess or observe.

This implementation returns `401` and modifies nothing. Only a **verified** reuse (step ⑤) revokes anything.

Verified live: a hand-forged token carrying a real user's `sub`, signed with a foreign key, returned `401` and left the victim's `refresh_token` untouched. `UserServiceTest.forgedTokenCannotRevokeAVictimSession` is the regression test.

### ⚠ `noRollbackFor` — why revocation would otherwise be cosmetic

```java
@Transactional(noRollbackFor = UnauthorizedException.class)
public TokenResponse rotateTokens(RotateTokenDto dto) { ... }
```

Steps ④ and ⑤ write `refresh_token = NULL` and *then* throw. **Spring rolls back on unchecked exceptions by default**, which would undo the revocation on the way out. The client would see the "session revoked" alert while the compromised session stayed fully alive.

This was a real defect in this implementation, caught by a live smoke test rather than by unit tests: after a detected replay, the rotated token still returned `200`. Declaring `UnauthorizedException` non-rollback lets the revoke commit while the request still fails. The paths that throw without writing commit nothing, so it is safe.

Doing the revoke in a `REQUIRES_NEW` transaction instead **deadlocks** — the outer transaction already holds a `FOR UPDATE` lock on the very row the inner one would need.

**Mockito cannot catch this.** A mocked repository mutates an in-memory object with no transaction semantics. `AuthFlowIntegrationTest.reuseDetectionRevocationIsDurable` runs against a real database and is deliberately **not** `@Transactional`, because a test-managed rollback would hide the exact behaviour under test.

### The concurrency lock

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select u from UserEntity u where u.id = :id")
Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id);
```

Without it, two requests replaying the same token can both pass step ⑤ before either writes. Both get a fresh session and the reuse goes undetected — the exact attack the design exists to catch. `SELECT ... FOR UPDATE` serialises the compare-and-swap so one wins and the other is correctly flagged.

`@Version` on `UserEntity` adds optimistic locking as a second line of defence for any *other* concurrent write to the row.

### Server-side expiry

`refresh_token_expires_at` duplicates the token's own `exp` on purpose. Without it the only expiry lives inside a token the server does not keep, so a stale hash lingers in the row indefinitely. Checking a column lets the server end a session without decoding anything, and gives a cleanup job something to sweep.

---

## 13. Flow 5 — Sign Out

```
POST /users/signout
Authorization: Bearer <access token>
```

```java
@Transactional
public void signOut(UUID userId) {
    userRepo.findById(userId).ifPresent(user -> {
        user.setRefreshToken(null);
        user.setRefreshTokenExpiresAt(null);
        userRepo.save(user);
    });
}
```

**Idempotent**, and a no-op if the row is gone — `ifPresent`, not `orElseThrow`. Signing out twice returns `200` both times. A logout that can fail is a logout users cannot trust.

### ⚠ The access token is *not* revoked

It stays valid until `exp` — up to 15 more minutes. This is the accepted cost of stateless authentication: checking a denylist on every request would reintroduce the per-request database lookup that bearer tokens exist to avoid.

Mitigations, in order of cost:
1. **Keep the access TTL short.** 15 minutes is the current bound.
2. **A `jti` denylist in Redis**, with a TTL equal to the token's remaining lifetime. The `jti` claim is already emitted for exactly this.
3. **Drop to sessions** if immediate revocation is a hard requirement — that is what they are for.

Verified live: sign out, sign out again → `200`, `200`; refresh afterwards → `401 "Active session not found."`

---

## 14. Flow 6 — Role Promotion

```
PATCH /users/{id}/role
Authorization: Bearer <ADMIN access token>
{ "role": "ADMIN" }
```

```java
@PatchMapping("{id}/role")
@PreAuthorize(Policies.ADMIN)
public UserResponse updateRole(@PathVariable UUID id, @Valid @RequestBody UpdateRoleDto dto) {
    return userService.updateRole(id, dto.role());
}
```

This is the **guarded** way to change a role after the fact: the caller must already hold `ROLE_ADMIN`, and the change produces a log line. Note that signup also accepts a role (§9), so this is not the only route to an admin account — it is the only route that requires being one already.

The set of assignable roles is **closed by construction**: `UpdateRoleDto` takes a `Role` enum, so anything outside it fails deserialization and becomes a 400.

### Why the session is cleared

```java
Role previous = user.getRole();
user.setRole(newRole);
clearSession(user);
```

A role change does not retroactively alter tokens already issued — the demoted user's current access token still carries `role: "ADMIN"` until it expires. Clearing the refresh slot means their next refresh fails and they must log in again, picking up the new role. With a 15-minute access TTL the stale-privilege window is bounded by that TTL.

This is the inherent trade-off of carrying the role in the token. The alternative — reading the role from the database on every request — closes the window at the cost of a query per request.

Verified live:

| Request | Result |
| :--- | :--- |
| `PATCH .../role` as `USER` | `403` |
| `PATCH .../role` as `ADMIN` | `200`, role updated |
| `{"role":"SUPERUSER"}` | `400` |
| non-UUID path variable | `400` |

---

## 15. Error Handling

Every failure returns the same envelope:

```json
{
  "timestamp": "2026-08-26T03:02:51.748",
  "status": 401,
  "error": "Unauthorized",
  "message": "Security alert: Token reuse detected. Session revoked.",
  "path": "/users/refresh-token"
}
```

Validation failures add `fieldErrors` (omitted elsewhere via `@JsonInclude(NON_NULL)`):

```json
{
  "timestamp": "...", "status": 400, "error": "Bad Request",
  "message": "Request validation failed.", "path": "/users",
  "fieldErrors": {
    "email": "Email must be a valid format",
    "password": "Password must be between 8 and 72 characters",
    "passwordConfirmed": "Passwords do not match"
  }
}
```

### The exception hierarchy

Services throw; they never return status codes. `ApiException` carries its own `HttpStatus`, so the handler needs no `instanceof` ladder.

| Exception | Status | Raised when |
| :--- | ---: | :--- |
| `UnauthorizedException` | 401 | Bad credentials, invalid/expired/reused token |
| `ConflictException` | 409 | Duplicate email |
| `NotFoundException` | 404 | User does not exist |
| `TooManyRequestsException` | 429 | Rate limit |

### `GlobalExceptionHandler` coverage

| Handles | → | Why it is needed |
| :--- | :--- | :--- |
| `ApiException` | its own status | The main path |
| `MethodArgumentNotValidException` | 400 | `@Valid` failure, with per-field messages |
| `HttpMessageNotReadableException` | 400 | Malformed JSON, unparsable enum |
| `MethodArgumentTypeMismatchException` | 400 | Non-UUID path variable — otherwise Spring's *default* body, the one response that would not match the envelope |
| `DataIntegrityViolationException` | 409 | The unique index rejected a write; stops a raw DB error surfacing as 500 |
| `OptimisticLockingFailureException` | 409 | Concurrent modification |
| `AccessDeniedException` | 403 | `@PreAuthorize` denial (method-level denials *do* reach the dispatcher) |
| `JwtException` | 401 | Token decoding outside the filter chain |

### ⚠ The scope limit

`@RestControllerAdvice` **cannot see** exceptions raised inside the security filter chain — they never reach the `DispatcherServlet`. That is why `SecurityConfig` separately registers `JsonAuthenticationEntryPoint` and `JsonAccessDeniedHandler` (§7). Forgetting them is why so many APIs return a clean JSON error for everything *except* 401.

---

## 16. Rate Limiting

BCrypt's work factor slows an **offline** attack on a stolen hash. It does nothing to slow an **online** one. Without a rate limit, `/users/login` is an unmetered password-guessing oracle and `/users/refresh-token` an unmetered token-guessing oracle.

`AuthRateLimitFilter` — fixed window, **10 requests per minute per IP per path**, in-memory:

```java
@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    return !HttpMethod.POST.matches(request.getMethod())
            || !LIMITED_PATHS.contains(request.getRequestURI());
}
```

`OncePerRequestFilter` guarantees it runs exactly once even across internal forwards. Over-limit requests get `429` with a `Retry-After` header and the standard error envelope.

Verified live: 12 rapid login attempts from one IP → `401`×9, then `429`×3.

**Two honest limitations:**

1. **Counters are per-JVM.** Correct for a single instance; behind a load balancer the effective limit multiplies by replica count. Move to Redis + Bucket4j, or to the gateway.
2. **`X-Forwarded-For` is trusted for its first hop.** Behind an untrusted proxy that header is client-controlled and spoofable. Configure `server.forward-headers-strategy` and a trusted-proxy list before relying on it.

Keying on IP + email would give sharper per-account protection but requires buffering the request body before the handler reads it.

---

## 17. Configuration Reference

```yaml
tasked:
  jwt:
    issuer:         ${JWT_ISSUER:tasked-api}
    audience:       ${JWT_AUDIENCE:tasked-app}
    access-secret:  ${JWT_ACCESS_SECRET:dev-only-...}
    refresh-secret: ${JWT_REFRESH_SECRET:dev-only-...}
    access-ttl:     ${JWT_ACCESS_TTL:PT15M}
    refresh-ttl:    ${JWT_REFRESH_TTL:P7D}

cors:
  allowed-origins:  ${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}
```

| Property | Default | Notes |
| :--- | :--- | :--- |
| `issuer` | `tasked-api` | Must match what the decoders expect |
| `audience` | `tasked-app` | Same |
| `access-secret` | dev value | ≥32 bytes; **must differ from `refresh-secret`** |
| `refresh-secret` | dev value | ≥32 bytes |
| `access-ttl` | `PT15M` | ISO-8601 `Duration`. Bounds a stolen access token. |
| `refresh-ttl` | `P7D` | How long a user stays logged in |

`JwtProperties` is a `@Validated` record, so `@NotBlank`, `@Size(min = 32)` and `@NotNull` are **boot-time** checks. A missing or short secret aborts startup rather than producing tokens nobody can verify. It reaches the container through `@ConfigurationPropertiesScan` on `ModularApplication`.

### ⚠ Secrets

The inline defaults exist so a fresh clone runs. **Every one must be overridden in any deployed environment.** A secret committed to source control is a secret that has already leaked. Consider removing the defaults entirely so a missing secret fails at boot.

### Production TTL guidance

| Setting | Development | Production |
| :--- | :--- | :--- |
| `access-ttl` | `PT1M` to exercise rotation | `PT15M` |
| `refresh-ttl` | `PT2M` | `P7D` – `P30D` |

Very short dev values are a good way to make sure the client's refresh logic is actually exercised rather than accidentally never used.

---

## 18. API Reference

| Method | Path | Auth | Body | Success | Failures |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `GET` | `/users/hello` | none | — | `200` | — |
| `POST` | `/users` | none | `CreateUserDto` (incl. optional `role`) | `201` text | `400`, `409` |
| `POST` | `/users/login` | none | `SignInDto` | `200` `TokenResponse` | `400`, `401`, `429` |
| `POST` | `/users/refresh-token` | none¹ | `RotateTokenDto` | `200` `TokenResponse` | `400`, `401`, `429` |
| `POST` | `/users/signout` | authenticated | — | `200` `{message}` | `401` |
| `GET` | `/users/me` | authenticated | — | `200` `UserResponse` | `401`, `404` |
| `GET` | `/users` | **ADMIN** | — | `200` `UserResponse[]` | `401`, `403` |
| `PATCH` | `/users/{id}/role` | **ADMIN** | `UpdateRoleDto` | `200` `UserResponse` | `400`, `401`, `403`, `404` |

¹ Anonymous at the URL level because the caller's access token has usually expired by then. The refresh token in the body **is** the credential, verified inside the service.

---

## 19. Method Index

### `JwtTokenService`

| Method | Does |
| :--- | :--- |
| `generateAccessToken(id, email, role)` | HS256 with the access secret, `accessTtl` |
| `generateRefreshToken(id, email, role)` | HS256 with the refresh secret, `refreshTtl` |
| `validateRefreshToken(token)` | Verifies against the refresh secret; throws `JwtException` |
| `refreshTokenTtl()` | Configured lifetime, so the service can persist `refreshTokenExpiresAt` |
| `generate(...)` *(private)* | Builds the claim set and signs it |
| `strictDecoder(secret, props)` *(static)* | Decoder with HS256 pinned, zero skew, `iss` + `aud`. Shared with `SecurityConfig`. |

### `TokenSecurityHelper` (static)

| Method | Does |
| :--- | :--- |
| `doubleHashToken(raw)` | `BCrypt(SHA256_HEX_UPPER(raw))`, work factor 10 |
| `verifyDoubleHashedToken(raw, stored)` | Re-hashes and compares; false for null/blank |
| `sha256Hex(value)` | 64 uppercase hex characters |

### `UserService`

| Method | Transaction | Does |
| :--- | :--- | :--- |
| `createUser(dto)` | `@Transactional` | Duplicate check, BCrypt, role from the DTO (`null` → `USER`) |
| `signIn(dto)` | `@Transactional` | Verify, mint pair, store hash |
| `signOut(userId)` | `@Transactional` | Clear the slot; idempotent |
| `rotateTokens(dto)` | `@Transactional(noRollbackFor = UnauthorizedException.class)` | The six-step rotation and reuse detection |
| `getCurrentUser(userId)` | `readOnly` | The caller's own record |
| `listUsers()` | `readOnly` | Admin listing |
| `updateRole(id, role)` | `@Transactional` | Admin promotion; clears the session |
| `issueAndStore(user)` *(private)* | — | Mint pair, store hash + expiry |
| `clearSession(user)` *(private)* | — | Null the slot and the expiry |

### `SecurityConfig`

| Bean | Does |
| :--- | :--- |
| `securityFilterChain` | The whole chain (§7) |
| `accessTokenDecoder` | Validates bearer tokens with the **access** secret |
| `jwtAuthenticationConverter` | `role` claim → `ROLE_*` authority; `sub` → principal |
| `passwordEncoder` | `BCryptPasswordEncoder(11)` |
| `corsConfigurationSource` | Explicit origin allowlist |

---

## 20. Threat Model

| Threat | Defence | Residual risk |
| :--- | :--- | :--- |
| Password brute force, online | Rate limit 10/min/IP; BCrypt wf 11 | Distributed attack across many IPs |
| Password brute force, offline (DB leak) | BCrypt wf 11, per-row salt | Weak passwords still fall eventually |
| Rainbow tables | BCrypt's automatic per-hash salt | none |
| User enumeration | Identical message for both login failures | Timing difference remains |
| Token forgery | HS256 signature, algorithm pinned | Key compromise |
| `alg: none` / algorithm confusion | `macAlgorithm(HS256)` on the decoder | none |
| Access token replay after theft | 15-minute TTL | Valid within the window |
| Refresh token replay after theft | Rotation + reuse detection → session destroyed | Attacker wins the race until the real client next refreshes |
| Refresh token theft from the database | Only `BCrypt(SHA256(token))` is stored | none |
| Cross-token replay | Two independent signing keys | none |
| Unauthenticated session-kill (DoS) | Unverified tokens modify nothing | none |
| Privilege escalation at signup | **Not mitigated** — `role` is accepted and honoured on an anonymous endpoint. Enum closes the value set; admin signups logged at `WARN`. | **Anyone can self-register as `ADMIN`.** Deliberate; see §9 for the one-line fix. |
| Privilege escalation via API | `PATCH .../role` is `hasRole('ADMIN')`; enum closes the value set | A compromised admin account |
| Horizontal access (reading another user's data) | Identity only from `sub`; no `/users/{id}` for users | none on these endpoints |
| CSRF | Bearer header is never auto-attached; stateless | Would return if tokens move to cookies |
| Concurrent-rotation race | `SELECT ... FOR UPDATE` + `@Version` | none |
| Stale privileges after demotion | Session cleared; bounded by access TTL | Up to 15 minutes |
| XSS exfiltrating tokens | *(not mitigated)* — tokens are in the JSON body | Consider `HttpOnly` cookie for refresh |
| Clock skew abuse | `Duration.ZERO` | Server clock drift — run NTP |

### The two accepted risks, stated plainly

1. **Sign-out does not revoke the access token.** Bounded by the 15-minute TTL. Fix with a `jti` denylist if that is too wide.
2. **Tokens are delivered in the JSON body**, so a browser client stores them where JavaScript — and therefore XSS — can reach. Fix by delivering the refresh token as an `HttpOnly; Secure; SameSite=Strict` cookie and keeping the access token in memory only. That is a client-contract change, and it brings CSRF back into scope.

---

## Appendix — Complete Request Journey

`GET /users` with an `ADMIN` token, end to end:

```
1.  Browser/client sends:  GET /users
                           Authorization: Bearer eyJraWQiOi...

2.  CorsFilter                     origin allowed
3.  AuthRateLimitFilter            shouldNotFilter → true (not a limited path)
4.  BearerTokenAuthenticationFilter
      a. extract "eyJraWQiOi..."
      b. accessTokenDecoder.decode()
           · Base64 decode header/payload
           · alg is HS256 as pinned          ✓
           · HMAC-SHA256 over "header.payload" with the ACCESS secret
             equals the signature            ✓
           · exp is in the future (0 skew)   ✓
           · iss == "tasked-api"             ✓
           · aud contains "tasked-app"       ✓
      c. JwtAuthenticationConverter
           · role "ADMIN" → SimpleGrantedAuthority("ROLE_ADMIN")
           · principal name ← sub
      d. SecurityContextHolder ← JwtAuthenticationToken
5.  AuthorizationFilter            .anyRequest().authenticated()   ✓
6.  DispatcherServlet → UserController#listUsers
7.  @PreAuthorize("hasRole('ADMIN')")
      hasRole prepends ROLE_ → looks for "ROLE_ADMIN"
      the authority is exactly that                                 ✓
8.  UserService.listUsers()        @Transactional(readOnly = true)
9.  UserRepo.findAll()             SELECT ... FROM users
10. map to UserResponse            password and refreshToken never leave the entity
11. Jackson serializes             200 OK, application/json
```

Any failure at 4b → `JsonAuthenticationEntryPoint` → `401`.
A failure at 7 with a `USER` token → `AccessDeniedException` → `403`, rendered by `GlobalExceptionHandler` (method-level) or `JsonAccessDeniedHandler` (URL-level).
