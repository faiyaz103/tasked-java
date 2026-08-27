# Auth Pipeline — Reference Spec & Spring Boot Port Guide

**Source system:** `Tasked` — .NET 10 modular monolith (this repo)
**Target system:** Java 21 / Spring Boot 3.x modular monolith + PostgreSQL
**Purpose:** This document is the *specification of record* for the authentication, authorization and RBAC pipeline as it is actually implemented in this repo, plus a component-by-component mapping to Spring Boot so the same behaviour can be rebuilt in Java.

> ⚠️ `docs/08_AUTH.md` describes an **earlier, abandoned design** (auth fields on `Profile`, opaque random refresh tokens, a single JWT secret, `GetPrincipalFromExpiredToken`). The shipped code diverged substantially. **Use this document, not `08_AUTH.md`.**

---

## Table of Contents

1. [Part 1 — Current Project Analysis](#part-1--current-project-analysis)
2. [Part 2 — The Auth Pipeline As Implemented](#part-2--the-auth-pipeline-as-implemented)
3. [Part 3 — Known Gaps & Security Findings](#part-3--known-gaps--security-findings)
4. [Part 4 — Spring Boot Port Guide](#part-4--spring-boot-port-guide)
5. [Part 5 — Implementation Order & Test Checklist](#part-5--implementation-order--test-checklist)

---

# Part 1 — Current Project Analysis

## 1.1 Solution Layout

```
Tasked.slnx
└── src/
    ├── Host/
    │   └── WebApi/                 ← composition root, the only runnable project
    │       ├── Program.cs          ← JWT bearer setup, policies, module registration, middleware
    │       └── appsettings.json    ← connection string + JwtSettings
    ├── Modules/
    │   ├── Users/                  ← classlib: identity, credentials, sessions, profiles
    │   │   ├── UsersModule.cs      ← AddUserModule(IServiceCollection, IConfiguration)
    │   │   ├── Controllers/UsersController.cs
    │   │   ├── Services/UserService.cs
    │   │   ├── Dtos/{UserDto,ProfileDto}.cs   ← records + FluentValidation validators, same file
    │   │   ├── Entities/{UserEntity,ProfileEntity,UserDbContext}.cs
    │   │   ├── Entities/Configuration/*.cs    ← IEntityTypeConfiguration<T>
    │   │   └── Migrations/                    ← module-owned EF migrations
    │   └── Tasks/                  ← classlib: task groups (same internal shape)
    └── Shared/
        └── Shared.Infra/           ← classlib: cross-cutting only
            ├── Auth/{ITokenService,TokenService,TokenSecurityHelper,ClaimsPrincipalExtensions}.cs
            └── Enums/Enums.cs      ← Roles, Gender
```

## 1.2 Architectural Rules In Force

| Rule | How it is enforced today |
| :--- | :--- |
| **Modules never reference each other** | `Users.csproj` and `Tasks.csproj` reference *only* `Shared.Infra`. The host references all three. |
| **No cross-module foreign keys** | `taskgroups.user_id` is a plain indexed `uuid` with **no FK** to `users`. Migration `20260815162557_RemoveUserForeignKeyFromTaskGroups` deliberately dropped it. Referential integrity across a module boundary is an application concern, not a DB concern. |
| **One database, one DbContext per module** | `UsersDbContext` and `TasksDbContext` both point at `DefaultConnection` (`taskedapp`), each owning its own tables and migration history. |
| **Modules self-register** | Each module exposes one static extension method (`AddUserModule`, `AddTasksModule`) that registers its DbContext, services and validators. `Program.cs` calls them; it knows nothing of module internals. |
| **snake_case in the DB, PascalCase in code** | `EFCore.NamingConventions` → `.UseSnakeCaseNamingConvention()` on every context. |
| **Enums stored as strings** | `HasConversion<string>()` + `HasMaxLength(10)` on `users.role` and `profiles.gender`. Migration `20260815171723` converted gender from int to string. |
| **Shared.Infra holds no domain logic** | Only token generation/validation, hashing helpers, claim extraction, and shared enums. |

## 1.3 Database Schema (PostgreSQL, database `taskedapp`)

```sql
-- owned by the Users module
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(100) NOT NULL,
    password      varchar(256) NOT NULL,     -- BCrypt hash of the plaintext password
    role          varchar(10)  NOT NULL,     -- 'Admin' | 'User'
    refresh_token text NULL,                 -- BCrypt(SHA256HEX(refresh_jwt)); NULL = no active session
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);
CREATE UNIQUE INDEX ix_users_email ON users (email);

CREATE TABLE profiles (
    id         uuid PRIMARY KEY,
    first_name varchar(100) NOT NULL,
    last_name  varchar(100) NOT NULL,
    phone      text         NOT NULL,
    gender     varchar(10)  NOT NULL,        -- 'Male' | 'Female' | 'Other'
    user_id    uuid         NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT fk_profiles_users_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ix_profiles_user_id ON profiles (user_id);   -- enforces 1:1
CREATE UNIQUE INDEX ix_profiles_phone   ON profiles (phone);
CREATE INDEX ix_profiles_first_name     ON profiles (first_name);
CREATE INDEX ix_profiles_last_name      ON profiles (last_name);

-- owned by the Tasks module
CREATE TABLE taskgroups (
    id         uuid PRIMARY KEY,
    title      varchar(100) NOT NULL,
    user_id    uuid         NOT NULL,        -- NO foreign key: crosses a module boundary
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);
CREATE INDEX ix_taskgroups_title   ON taskgroups (title);
CREATE INDEX ix_taskgroups_user_id ON taskgroups (user_id);
```

Note: `profiles.phone` is `text` in the DB — the `MaximumLength(20)` exists only in the FluentValidation rule, not in `ProfileEntityConfig`.

## 1.4 Request Pipeline (`Program.cs`, in order)

```
CreateBuilder
  ├─ AddAuthentication(JwtBearer default) → AddJwtBearer(TokenValidationParameters)
  ├─ AddAuthorization(3 named policies)
  ├─ AddControllers()
  ├─ AddScoped<ITokenService, TokenService>()
  ├─ AddUserModule(config)     → UsersDbContext + IUserService + validators
  ├─ AddTasksModule(config)    → TasksDbContext + ITaskService + validators
  └─ AddCors("AllowFrontend": localhost:3000, localhost:5173)
Build
  ├─ startup DB reachability ping (CanConnectAsync, logged, non-fatal)
  ├─ UseCors("AllowFrontend")
  ├─ UseHttpsRedirection()  [non-Development only]
  ├─ UseAuthentication()    ← must precede UseAuthorization
  ├─ UseAuthorization()
  └─ MapControllers()
```

There is **no** global exception handler and **no** validation action filter registered, despite `docs/04` and `docs/05` describing both. Validation is invoked manually inside each controller action; exceptions are caught per-action with `try/catch` and mapped to status codes by hand.

---

# Part 2 — The Auth Pipeline As Implemented

## 2.1 Configuration

`appsettings.json`:

```json
"JwtSettings": {
  "Issuer": "your-app-issuer",
  "Audience": "your-app-audience",
  "AccessSecret":  "your-super-secure-access-token-secret-key-must-be-long-enough",
  "RefreshSecret": "your-super-secure-refresh-token-secret-key-must-be-long-enough",
  "AccessExpiresInMinutes": 1,
  "RefreshExpiresInMinutes": 2
}
```

**Two independent HS256 secrets.** An access token can never be replayed at the refresh endpoint and vice versa — the signature simply will not verify. This is the single most important design decision to preserve in the port. The 1/2-minute lifetimes are deliberately tiny development values for exercising rotation; production values belong around 15 min / 7–30 days.

## 2.2 Token Shape — verified on the wire

Both tokens are produced by the same private `GenerateJwt(userId, email, role, secret, expiresIn)` in `Shared.Infra/Auth/TokenService.cs`, differing only in secret and lifetime. Claims added: `JwtRegisteredClaimNames.Sub`, `JwtRegisteredClaimNames.Email`, `ClaimTypes.Role`, `JwtRegisteredClaimNames.Jti`.

The **actual decoded payload** is:

```json
{
  "sub":   "c4a8ba74-5a6b-4b0d-86e1-eb16b1db7e0c",
  "email": "a@b.com",
  "http://schemas.microsoft.com/ws/2008/06/identity/claims/role": "Admin",
  "jti":   "c2270265-05ec-4d1d-966d-9d223d99085e",
  "exp":   1787689198,
  "iss":   "your-app-issuer",
  "aud":   "your-app-audience"
}
```

> 🔴 **Critical porting fact.** `ClaimTypes.Role` is **not** shortened on the way out — the role lands in the payload under the literal key `http://schemas.microsoft.com/ws/2008/06/identity/claims/role`. `sub` and `email` *do* stay short outbound, but on the way **in** `JwtSecurityTokenHandler` remaps them (`MapInboundClaims` defaults to `true`):
>
> | Wire claim | Claim type inside `ClaimsPrincipal` |
> | :--- | :--- |
> | `sub` | `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier` |
> | `email` | `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress` |
> | `http://.../claims/role` | unchanged (already the long form) |
>
> This is exactly why `ClaimsPrincipalExtensions.ExtractUserId()` looks up `ClaimTypes.NameIdentifier` **first** and falls back to `JwtRegisteredClaimNames.Sub` — after ASP.NET Core's middleware validates the token, the `sub` claim no longer exists under that name. **Do not carry the Microsoft URI into the Java implementation.** Emit a plain `role` claim; Java has no inbound remapping and the URI buys nothing.

`ClockSkew = TimeSpan.Zero` is set both in the middleware and in `ValidateRefreshToken`, so a token expires at exactly `exp` rather than 5 minutes later.

## 2.3 Credential Storage

| Secret | Algorithm | Where |
| :--- | :--- | :--- |
| Password | `BCrypt.HashPassword(pw)` — **work factor 11** (BCrypt.Net-Next default), `$2a$` prefix | `users.password` |
| Refresh token | `BCrypt(SHA256_HEX_UPPER(refresh_jwt), workFactor: 10)` | `users.refresh_token` |

**Why the double hash** (`TokenSecurityHelper`): BCrypt silently truncates its input at **72 bytes**. A refresh JWT is ~300+ bytes, so hashing it directly would cover only the header and part of the payload — the signature would never be verified. Pre-hashing with SHA-256 yields a fixed **64-character uppercase hex** string (`Convert.ToHexString` is uppercase) that fits comfortably under the limit and covers the entire token. Any port must reproduce the same two steps, and must keep the hex casing internally consistent.

Access tokens are never stored anywhere.

## 2.4 Endpoint Inventory

| Method | Route | Protection | Body → Response |
| :--- | :--- | :--- | :--- |
| `POST` | `/users` | anonymous | `CreateUserRequest` → `201` + `"Registration Successful for {email}"` / `409` on duplicate email |
| `POST` | `/users/login` | anonymous | `SignInUserRequest` → `200` + `TokenResponse` / `401` |
| `POST` | `/users/refresh-token` | anonymous (the token is in the body) | `RotateTokenRequest` → `200` + `TokenResponse` / `401` |
| `POST` | `/users/signout` | `[Authorize]` | — → `200` |
| `POST` | `/users/profile` | `[Authorize]` | `CreateProfileRequest` → `201` / `409` |
| `GET` | `/users/profile` | `[Authorize]` | — → `200` / `404` |
| `PATCH`| `/users/profile` | `[Authorize]` | `UpdateProfileRequest` → `200` / `404` / `409` |
| `GET` | `/tasks/hello` | **none** | → `200` |
| `POST` | `/tasks` | **none** | `CreateTaskRequest` → `201` / `500` |

The authenticated identity is *never* taken from the request body — every protected action calls `User.ExtractUserId()` to read it from the validated token.

## 2.5 Flow: Registration (`POST /users`)

1. `CreateUserRequestValidator` (FluentValidation, invoked manually) — email non-empty ≤100; password ≥8 with ≥1 uppercase and ≥1 digit; `ConfirmPassword` must equal `Password`; `Role`, **if supplied**, must be a valid `Roles` enum name (case-insensitive).
2. `UserService.CreateUserAsync`: `AnyAsync(u => u.Email == request.Email)` → `InvalidOperationException` → `409 Conflict`.
3. `BCrypt.HashPassword(password)`.
4. Role: `string.IsNullOrWhiteSpace(request.Role) ? Roles.User : Enum.Parse<Roles>(request.Role, ignoreCase: true)`.
5. Insert. **No tokens are issued on registration** — the client must call `/users/login`.

## 2.6 Flow: Login (`POST /users/login`)

1. Validate DTO.
2. Look up by email; `null` → `UnauthorizedAccessException("Invalid email or password")`.
3. `BCrypt.Verify(request.Password, user.Password)`; false → **the same message** (no user-enumeration leak).
4. Generate access + refresh tokens (wrapped in `Task.Run` + `Task.WhenAll` — see finding F-9).
5. `TokenSecurityHelper.DoubleHashToken(refreshToken)` → write to `user.RefreshToken`, `SaveChangesAsync`.
6. Return `{ accessToken, refreshToken }` — the **raw** refresh JWT, which the server never keeps.

## 2.7 Flow: Refresh Token Rotation (`POST /users/refresh-token`) — the core of the design

`users.refresh_token` is a **single slot**: one active session per user, replaced on every rotation.

```
Client POSTs { token: <raw refresh JWT> }
│
├─1. tokenService.ValidateRefreshToken(token)
│      HS256 vs RefreshSecret + iss + aud + lifetime, ClockSkew.Zero
│      ✗ throws ─► InvalidateSessionIfPossibleAsync(token)
│                    · ReadJwtToken WITHOUT signature verification
│                    · pull `sub`, if parseable → set that user's refresh_token = NULL
│                  ─► 401 "Refresh token is expired or invalid. Session revoked."
│      ✓
├─2. userId ← claims: NameIdentifier ?? Sub      (see §2.2 remapping)
│      unparseable ─► 401 "Invalid token payload."
│
├─3. load user by id
│      user missing OR user.RefreshToken null/empty ─► 401 "Access denied. Active session not found."
│
├─4. VerifyDoubleHashedToken(token, user.RefreshToken)
│      ✗ ─► REUSE DETECTED: refresh_token = NULL, save
│           ─► 401 "Security alert: Token reuse detected. Session revoked."
│      ✓
├─5. mint NEW access + NEW refresh token
├─6. user.RefreshToken = DoubleHashToken(newRefresh); save   ← rotation: the old token is now dead
└─7. 200 { accessToken, refreshToken }
```

**The reuse-detection property.** Because step 6 overwrites the stored hash, a previously-rotated refresh token still passes step 1 (its JWT signature and `exp` are fine) but fails step 4. That is only possible if the token was captured and replayed — so the server treats it as theft and nukes the session, forcing a full re-login for both the attacker and the legitimate client. This is the behaviour the port most needs to reproduce faithfully.

## 2.8 Flow: Sign Out (`POST /users/signout`)

`[Authorize]` → `User.ExtractUserId()` → set `refresh_token = NULL`. Idempotent, and silently succeeds if the user row is gone. **The access token is not revoked** and stays valid until `exp` (see finding F-6).

## 2.9 Authorization & RBAC

Roles are a fixed enum, `Shared.Infra/Enums/Enums.cs`:

```csharp
public enum Roles { Admin, User }
```

Three named policies are declared in `Program.cs`:

```csharp
options.AddPolicy("RequireAdminRole",      p => p.RequireRole(nameof(Roles.Admin)));
options.AddPolicy("RequireUserRole",       p => p.RequireRole(nameof(Roles.User)));
options.AddPolicy("RequireElevatedAccess", p => p.RequireRole(nameof(Roles.Admin), nameof(Roles.User)));  // OR, not AND
```

`RequireRole` matches against `ClaimTypes.Role`, which is the claim `GenerateJwt` emits — verified working (`principal.IsInRole("Admin") == True`).

**Current reality:** *none of these three policies is applied to any endpoint.* Every protected action uses bare `[Authorize]` (authentication only). The RBAC machinery is fully wired and functional but not yet exercised. The port should build the same machinery and then actually attach it.

**Model:** flat, single-role-per-user, role-claim-in-token RBAC. There is no permission table, no role-permission join, and no resource-level ownership check. Ownership is instead implicit — every query is scoped by the `userId` pulled from the token (`p => p.UserId == id`), so a user physically cannot address another user's row through these endpoints.

---

# Part 3 — Known Gaps & Security Findings

Carry these into the Java build as *decisions*, not as bugs to be copied.

| # | Finding | Impact | Recommended fix in the port |
| :--- | :--- | :--- | :--- |
| **F-1** | **Privilege escalation at registration.** `CreateUserRequest.Role` is client-supplied and honoured verbatim — `POST /users {"role":"Admin"}` mints an admin. | Critical | Drop `role` from the public signup DTO; always assign `USER`. Promote via a separate `PATCH /users/{id}/role` guarded by `hasRole('ADMIN')`. |
| **F-2** | **Unauthenticated session-kill / DoS.** `InvalidateSessionIfPossibleAsync` reads `sub` from a token whose **signature was never verified** and nulls that user's `refresh_token`. Anyone can hand-craft a JWT carrying a victim's `sub` and forcibly log them out, repeatedly. | High | Delete this path. On a failed signature/expiry check, return `401` and touch nothing. Only revoke on **verified** reuse (step 4). |
| **F-3** | **Single refresh-token slot.** One session per user; logging in on a phone silently kills the laptop session. | Medium | Model sessions as a child table (`refresh_tokens`: id, user_id, token_hash, expires_at, revoked_at, user_agent, created_at) with a unique index on `token_hash`. Revocation then targets one session, and reuse detection can revoke the whole family. |
| **F-4** | **No server-side refresh expiry.** The only expiry is `exp` inside the JWT; the DB row has no `expires_at`, so a stored hash lingers forever. | Medium | Add `expires_at`, check it server-side, and add a cleanup job. |
| **F-5** | **Secrets committed to `appsettings.json`**, including the DB password. | High | Externalise to environment variables / Spring Cloud Config / Vault. Never commit. |
| **F-6** | **Logout does not revoke the access token.** It stays valid for the rest of its lifetime. | Low (mitigated by the 1-min TTL) | Acceptable with short TTLs. If stronger revocation is needed, keep a `jti` denylist in Redis until `exp`. |
| **F-7** | **Tasks module is entirely unprotected**, and `CreateTaskAsync` never populates `TaskGroup.UserId` — every row gets `Guid.Empty`. | High | In the port, protect the tasks controller and thread the authenticated user id into the service on create *and* filter every read by it. |
| **F-8** | Neither the global exception handler (`docs/05`) nor the validation action filter (`docs/04`) is registered; every action repeats `try/catch` + manual validation. | Maintainability | Use `@RestControllerAdvice` + Bean Validation from day one. |
| **F-9** | `Task.Run(...)` + `Task.WhenAll` around token generation. HMAC signing takes microseconds — this adds thread-pool scheduling overhead for negative benefit. (The BCrypt calls *are* genuinely CPU-bound, so offloading *those* is defensible.) | Perf/clarity | Generate tokens inline. |
| **F-10** | No rate limiting on `/users/login` or `/users/refresh-token`. | High | Add bucket4j or gateway-level limiting keyed on IP + email. |
| **F-11** | Tokens are returned in the JSON body, so an SPA must store them in JS-reachable storage (XSS-exfiltratable). | Medium | Consider delivering the refresh token as an `HttpOnly; Secure; SameSite=Strict` cookie and keeping only the access token in memory. |
| **F-12** | `UpdateProfileAsync` checks phone uniqueness with a read-then-write (TOCTOU). The unique index saves correctness but surfaces a raw `DbUpdateException`. | Low | Catch the constraint violation and map it to `409`. |

---

# Part 4 — Spring Boot Port Guide

## 4.1 Target Layout

Mirror the .NET module boundaries exactly. Maven multi-module (Gradle works identically):

```
tasked-parent/                     (pom: <packaging>pom</packaging>, dependencyManagement)
├── shared-infra/                  ← jar; == Shared.Infra
│   └── com.tasked.shared
│       ├── auth/   TokenService, JwtTokenService, TokenSecurityHelper,
│       │           CurrentUserId, CurrentUserIdArgumentResolver, JwtProperties
│       ├── enums/  Role, Gender
│       └── web/    ApiExceptionHandler, exceptions
├── modules/
│   ├── users/                     ← jar; == Modules/Users
│   │   └── com.tasked.users
│   │       ├── UsersModuleConfig.java      (== UsersModule.cs)
│   │       ├── web/       UsersController
│   │       ├── service/   UserService (iface) + UserServiceImpl
│   │       ├── dto/       records + jakarta.validation annotations
│   │       ├── domain/    UserEntity, ProfileEntity
│   │       ├── repo/      UserRepository, ProfileRepository
│   │       └── resources/db/migration/users/V1_001__*.sql
│   └── tasks/                     ← jar; == Modules/Tasks
└── host-api/                      ← spring-boot-starter-web; == Host/WebApi
    └── com.tasked.host
        ├── TaskedApplication.java
        ├── config/SecurityConfig.java      (== the Program.cs auth section)
        └── resources/application.yml
```

Dependency direction is identical to .NET: `users` → `shared-infra`, `tasks` → `shared-infra`, `host-api` → all three, and **`users` ⇄ `tasks` never**.

Enforce it mechanically — either with **Spring Modulith** (`spring-modulith-starter-core` plus a `ModularityTests` verification test) or with an **ArchUnit** rule. Maven module boundaries already make an illegal import a compile error, which is the strongest guarantee and matches how `.csproj` references work today.

## 4.2 Dependency Mapping

| .NET | Java / Spring |
| :--- | :--- |
| `Microsoft.AspNetCore.Authentication.JwtBearer` | `spring-boot-starter-oauth2-resource-server` (brings Nimbus JOSE+JWT) |
| `System.IdentityModel.Tokens.Jwt` | `com.nimbusds:nimbus-jose-jwt` (already transitive) |
| `BCrypt.Net-Next` | `spring-security-crypto` → `BCryptPasswordEncoder` |
| `Microsoft.EntityFrameworkCore` + `Npgsql` | `spring-boot-starter-data-jpa` + `org.postgresql:postgresql` |
| EF Core Migrations | **Flyway** (`flyway-core` + `flyway-database-postgresql`) |
| `EFCore.NamingConventions` (snake_case) | Hibernate `CamelCaseToUnderscoresNamingStrategy` (Spring Boot's default) |
| `FluentValidation` | `spring-boot-starter-validation` (Jakarta Bean Validation / Hibernate Validator) |
| `[Authorize]` / policies | `@PreAuthorize` / `SecurityFilterChain` `authorizeHttpRequests` |
| `IServiceCollection` extension per module | `@Configuration` class per module, imported by the host |
| `Microsoft.AspNetCore.OpenApi` | `springdoc-openapi-starter-webmvc-ui` |

`pom.xml` essentials for `host-api`:

```xml
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-oauth2-resource-server</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
<dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
```

## 4.3 Configuration (`application.yml`)

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/taskedapp}
    username: ${DB_USER:postgres}
    password: ${DB_PASSWORD}          # F-5: no default, fail fast if unset
  jpa:
    hibernate.ddl-auto: validate      # Flyway owns the schema, never Hibernate
    open-in-view: false               # avoid lazy loading in the view layer
    properties.hibernate.jdbc.time_zone: UTC
  jackson:
    mapper.accept-case-insensitive-enums: true   # == Enum.Parse(..., ignoreCase: true)
  flyway:
    enabled: true
    locations:                        # each module owns its own migration folder
      - classpath:db/migration/users
      - classpath:db/migration/tasks

tasked:
  jwt:
    issuer: tasked-api
    audience: tasked-app
    access-secret:  ${JWT_ACCESS_SECRET}    # ≥32 bytes for HS256
    refresh-secret: ${JWT_REFRESH_SECRET}   # MUST differ from access-secret
    access-ttl:  PT15M                      # java.time.Duration (was 1 min in dev .NET)
    refresh-ttl: P7D                        # (was 2 min in dev .NET)

cors:
  allowed-origins: http://localhost:3000,http://localhost:5173
```

```java
@ConfigurationProperties(prefix = "tasked.jwt")
@Validated
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotBlank @Size(min = 32) String accessSecret,
        @NotBlank @Size(min = 32) String refreshSecret,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshTtl) {}
```

## 4.4 Token Service — `TokenService.cs` → Nimbus

Emit `role` as a **plain claim name**, not the Microsoft URI (§2.2).

```java
public interface TokenService {
    String generateAccessToken(UUID userId, String email, Role role);
    String generateRefreshToken(UUID userId, String email, Role role);
    Jwt validateRefreshToken(String token);          // throws JwtException — == ValidateRefreshToken
}
```

```java
@Service
public class JwtTokenService implements TokenService {

    private final JwtProperties props;
    private final JwtEncoder accessEncoder, refreshEncoder;
    private final JwtDecoder refreshDecoder;

    public JwtTokenService(JwtProperties props) {
        this.props = props;
        this.accessEncoder  = encoder(props.accessSecret());
        this.refreshEncoder = encoder(props.refreshSecret());
        this.refreshDecoder = decoder(props.refreshSecret(), props);
    }

    @Override public String generateAccessToken(UUID id, String email, Role role) {
        return generate(id, email, role, accessEncoder, props.accessTtl());
    }

    @Override public String generateRefreshToken(UUID id, String email, Role role) {
        return generate(id, email, role, refreshEncoder, props.refreshTtl());
    }

    /** Mirrors the private GenerateJwt(...) in TokenService.cs. */
    private String generate(UUID id, String email, Role role, JwtEncoder enc, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .audience(List.of(props.audience()))
                .subject(id.toString())
                .claim("email", email)
                .claim("role", role.name())              // ← plain 'role', NOT the MS URI
                .id(UUID.randomUUID().toString())        // jti
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return enc.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** == ValidateRefreshToken: signature + iss + aud + lifetime, zero clock skew. */
    @Override public Jwt validateRefreshToken(String token) {
        return refreshDecoder.decode(token);   // JwtException on any failure
    }

    private static JwtEncoder encoder(String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }

    private static JwtDecoder decoder(String secret, JwtProperties p) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withSecretKey(key(secret))
                .macAlgorithm(MacAlgorithm.HS256).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),        // == ClockSkew.Zero
                new JwtIssuerValidator(p.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(p.audience()))));
        return d;
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
```

> **Do not port `ExtractUserIdFromUnvalidatedToken`.** It exists solely to feed the DoS-prone `InvalidateSessionIfPossibleAsync` (F-2). Leave both out.

## 4.5 Hashing — `TokenSecurityHelper.cs` → Java

Faithful port, including the uppercase hex:

```java
public final class TokenSecurityHelper {

    private static final PasswordEncoder TOKEN_ENCODER = new BCryptPasswordEncoder(10);

    private TokenSecurityHelper() {}

    /** BCrypt(SHA256_HEX_UPPER(token)) — SHA-256 first because BCrypt truncates at 72 bytes. */
    public static String doubleHashToken(String rawToken) {
        return TOKEN_ENCODER.encode(sha256Hex(rawToken));
    }

    public static boolean verifyDoubleHashedToken(String rawToken, String storedHash) {
        if (!StringUtils.hasText(rawToken) || !StringUtils.hasText(storedHash)) return false;
        return TOKEN_ENCODER.matches(sha256Hex(rawToken), storedHash);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            // Convert.ToHexString in .NET is UPPERCASE — keep it identical.
            return HexFormat.of().withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

Passwords use a separate bean. .NET's default is work factor **11**, so match it (Spring's own default is 10):

```java
@Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(11); }
```

Both produce `$2a$`-prefixed hashes, so existing .NET-generated hashes verify unchanged under Spring — useful if you ever migrate the data.

## 4.6 Security Configuration — the `Program.cs` auth block → `SecurityFilterChain`

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity                       // enables @PreAuthorize
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtProperties props,
                                    AuthenticationEntryPoint entryPoint,
                                    AccessDeniedHandler deniedHandler) throws Exception {
        http
          .csrf(AbstractHttpConfigurer::disable)              // stateless bearer API
          .cors(Customizer.withDefaults())
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .authorizeHttpRequests(auth -> auth
              .requestMatchers(HttpMethod.POST,
                   "/users", "/users/login", "/users/refresh-token").permitAll()
              .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
              .anyRequest().authenticated())                  // == [Authorize] as the default
          .oauth2ResourceServer(oauth -> oauth
              .jwt(jwt -> jwt.decoder(accessTokenDecoder(props))
                             .jwtAuthenticationConverter(jwtAuthConverter()))
              .authenticationEntryPoint(entryPoint)           // 401 as JSON
              .accessDeniedHandler(deniedHandler))            // 403 as JSON
          .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)
                                   .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    /** == AddJwtBearer(TokenValidationParameters). Note: ACCESS secret only. */
    @Bean
    JwtDecoder accessTokenDecoder(JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withSecretKey(new SecretKeySpec(
                props.accessSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),          // ClockSkew = TimeSpan.Zero
            new JwtIssuerValidator(props.issuer()),
            new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(props.audience()))));
        return decoder;
    }

    /** Maps the flat `role` claim to a ROLE_-prefixed authority. */
    @Bean
    JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
        });
        converter.setPrincipalClaimName(JwtClaimNames.SUB);    // principal name == userId
        return converter;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowedMethods(List.of("*"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
```

> 🔑 **The `ROLE_` prefix is the biggest RBAC trap in the port.** Spring's `hasRole('ADMIN')` implicitly prepends `ROLE_` before comparing against the granted authority. If the converter grants a bare `ADMIN`, every role check silently fails with `403`. Either grant `ROLE_ADMIN` (as above) and use `hasRole`, or grant `ADMIN` and use `hasAuthority('ADMIN')` — but never mix the two.

**Two decoders, two secrets.** `accessTokenDecoder` is registered with the resource server and only ever sees access tokens; `JwtTokenService.refreshDecoder` is used manually inside the rotation service. This preserves the .NET property that a refresh token presented as a bearer token is rejected outright.

## 4.7 Policies → Method Security

| .NET policy | Spring equivalent |
| :--- | :--- |
| `[Authorize]` | `.anyRequest().authenticated()` (default), or `@PreAuthorize("isAuthenticated()")` |
| `[Authorize(Policy = "RequireAdminRole")]` | `@PreAuthorize("hasRole('ADMIN')")` |
| `[Authorize(Policy = "RequireUserRole")]` | `@PreAuthorize("hasRole('USER')")` |
| `[Authorize(Policy = "RequireElevatedAccess")]` | `@PreAuthorize("hasAnyRole('ADMIN','USER')")` |
| `[AllowAnonymous]` | `.permitAll()` on the matcher, or `@PreAuthorize("permitAll()")` |

Prefer expressing coarse rules in `authorizeHttpRequests` (URL-level, like `Program.cs`) and fine-grained ones with `@PreAuthorize` on the controller method (like the attribute). For named reuse, extract constants:

```java
public final class Policies {
    public static final String ADMIN    = "hasRole('ADMIN')";
    public static final String USER     = "hasRole('USER')";
    public static final String ELEVATED = "hasAnyRole('ADMIN','USER')";
}
// @PreAuthorize(Policies.ADMIN)
```

Enums, matching `Shared.Infra/Enums/Enums.cs`:

```java
public enum Role { ADMIN, USER }      // stored via @Enumerated(EnumType.STRING)
public enum Gender { MALE, FEMALE, OTHER }
```

Note the case shift: .NET stores `"Admin"`, Java's enum constants are `ADMIN`. Pick one and keep the DB consistent — if you migrate existing rows, `UPDATE users SET role = upper(role);` and likewise for `gender`.

## 4.8 `User.ExtractUserId()` → `@CurrentUserId`

The .NET extension method reads `NameIdentifier ?? Sub` because of inbound remapping. Java has no remapping — `sub` is `sub` — so this collapses to a one-liner. Wrap it in an argument resolver to keep controllers as clean as they are today:

```java
@Target(ElementType.PARAMETER) @Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {}

@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override public boolean supportsParameter(MethodParameter p) {
        return p.hasParameterAnnotation(CurrentUserId.class)
            && UUID.class.equals(p.getParameterType());
    }

    @Override public Object resolveArgument(MethodParameter p, ModelAndViewContainer m,
                                            NativeWebRequest r, WebDataBinderFactory b) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            try {
                return UUID.fromString(jwtAuth.getToken().getSubject());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new UnauthorizedException("Invalid user token payload.");
            }
        }
        throw new UnauthorizedException("Invalid user token payload.");
    }
}
```

Register it via `WebMvcConfigurer#addArgumentResolvers`. Usage — compare to `Guid userId = User.ExtractUserId();`:

```java
@GetMapping("/profile")
public ProfileResponse getProfile(@CurrentUserId UUID userId) { ... }
```

## 4.9 Entities & Repositories

```java
@Entity @Table(name = "users")
public class UserEntity {
    @Id @GeneratedValue private UUID id;
    @Column(nullable = false, length = 100, unique = true) private String email;
    @Column(nullable = false, length = 256) private String password;      // BCrypt hash
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Role role = Role.USER;
    @Column(name = "refresh_token", columnDefinition = "text") private String refreshToken;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private ProfileEntity profile;
    // getters/setters
}

@Entity @Table(name = "profiles")
public class ProfileEntity {
    @Id @GeneratedValue private UUID id;
    @Column(name = "first_name", nullable = false, length = 100) private String firstName;
    @Column(name = "last_name",  nullable = false, length = 100) private String lastName;
    @Column(nullable = false, columnDefinition = "text") private String phone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Gender gender = Gender.OTHER;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
}
```

```java
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}

public interface ProfileRepository extends JpaRepository<ProfileEntity, UUID> {
    Optional<ProfileEntity> findByUserId(UUID userId);
    boolean existsByUserId(UUID userId);
    boolean existsByPhone(String phone);
}
```

**Timestamps:** .NET writes `DateTime.UtcNow` into `timestamptz`. In Java use `Instant` (not `LocalDateTime`) so the UTC instant round-trips correctly, and set `hibernate.jdbc.time_zone: UTC`. Consider `@CreationTimestamp` / `@UpdateTimestamp` to replace the manual `profile.UpdatedAt = DateTime.UtcNow` line in `UpdateProfileAsync`.

## 4.10 Migrations

EF migrations are per-module; Flyway mirrors this with per-module `locations` (§4.3). Because those locations share one history table by default, prefix versions per module to avoid collisions (`V1_001__` for users, `V2_001__` for tasks) — or give each module its own schema plus its own `flyway.schemas`/history table. The DDL to start from is §1.3 verbatim.

**Do not** let Hibernate generate the schema: `ddl-auto: validate`, always.

## 4.11 Validation — FluentValidation → Bean Validation

The .NET DTOs are records with a validator class in the same file, invoked manually. In Java, annotate the record and let `@Valid` trigger it in the filter chain — this also fixes F-8.

```java
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Size(min = 8)
        @Pattern(regexp = ".*[A-Z].*", message = "Password must contain at least one uppercase letter.")
        @Pattern(regexp = ".*[0-9].*", message = "Password must contain at least one number.")
        String password,
        @NotBlank String confirmPassword) {
        // F-1: `role` deliberately removed from the public signup contract.

    @AssertTrue(message = "Passwords do not match.")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(confirmPassword);
    }
}

public record CreateProfileRequest(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 20) @Pattern(regexp = "^\\+?[0-9]{7,15}$") String phone,
        Gender gender) {}                    // null → OTHER, mirroring the .NET default

/** PATCH semantics: every field optional, null means "leave unchanged". */
public record UpdateProfileRequest(
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Size(max = 20) @Pattern(regexp = "^\\+?[0-9]{7,15}$") String phone,
        Gender gender) {}

public record SignInUserRequest(@NotBlank @Email String email, @NotBlank String password) {}
public record RotateTokenRequest(@NotBlank String token) {}
public record TokenResponse(String accessToken, String refreshToken) {}
public record ProfileResponse(String firstName, String lastName, String phone, String gender) {}
```

Login validation note: the .NET `CreateUserSignInRequestValidator` applies the *full password complexity rules at login*, which leaks the password policy and would reject legacy passwords. The port above drops that to `@NotBlank` — verify the credential, don't re-validate its shape.

## 4.12 Auth Service — the rotation flow in Java

Direct port of §2.7, **with F-2 fixed** (no unverified-token session kill):

```java
@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;

    /** == CreateUserAsync */
    public String createUser(CreateUserRequest req) {
        if (users.existsByEmail(req.email()))
            throw new ConflictException("This email already exists");
        UserEntity u = new UserEntity();
        u.setEmail(req.email());
        u.setPassword(passwordEncoder.encode(req.password()));
        u.setRole(Role.USER);                                   // F-1: never client-supplied
        users.save(u);
        return "Registration Successful for " + req.email();
    }

    /** == CreateUserSignInAsync */
    public TokenResponse signIn(SignInUserRequest req) {
        UserEntity user = users.findByEmail(req.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        if (!passwordEncoder.matches(req.password(), user.getPassword()))
            throw new UnauthorizedException("Invalid email or password");   // identical message
        return issueAndStore(user);
    }

    /** == SignOutAsync — idempotent */
    public void signOut(UUID userId) {
        users.findById(userId).ifPresent(u -> {
            u.setRefreshToken(null);
            users.save(u);
        });
    }

    /** == RotateTokensAsync */
    public TokenResponse rotateTokens(RotateTokenRequest req) {
        // 1. signature + iss + aud + lifetime against the REFRESH secret
        Jwt jwt;
        try {
            jwt = tokens.validateRefreshToken(req.token());
        } catch (JwtException e) {
            // F-2 fix: reject only. Do NOT touch any session based on an unverified token.
            throw new UnauthorizedException("Refresh token is expired or invalid.");
        }

        // 2. subject → userId
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnauthorizedException("Invalid token payload.");
        }

        // 3. an active session must exist
        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Access denied. Active session not found."));
        if (!StringUtils.hasText(user.getRefreshToken()))
            throw new UnauthorizedException("Access denied. Active session not found.");

        // 4. the token must be THE current one — otherwise it is a replay of a rotated token
        if (!TokenSecurityHelper.verifyDoubleHashedToken(req.token(), user.getRefreshToken())) {
            user.setRefreshToken(null);                          // revoke the whole session
            users.save(user);
            throw new UnauthorizedException("Security alert: Token reuse detected. Session revoked.");
        }

        // 5-7. mint a new pair and rotate the stored hash
        return issueAndStore(user);
    }

    private TokenResponse issueAndStore(UserEntity user) {
        String access  = tokens.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refresh = tokens.generateRefreshToken(user.getId(), user.getEmail(), user.getRole());
        user.setRefreshToken(TokenSecurityHelper.doubleHashToken(refresh));   // F-9: no Task.Run
        users.save(user);
        return new TokenResponse(access, refresh);
    }
}
```

**Concurrency note absent from the .NET version:** two simultaneous refreshes with the same token can both pass step 4 before either writes. Guard it with `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a dedicated `findByIdForUpdate` query, or with optimistic locking (`@Version`) plus a retry. Under the .NET code this race silently issues two valid sessions where one should have been flagged as reuse.

## 4.13 Controller — `[Authorize]` → `@PreAuthorize`

```java
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UsersController {

    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public String createUser(@Valid @RequestBody CreateUserRequest req) {
        return userService.createUser(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody SignInUserRequest req) {
        return userService.signIn(req);
    }

    @PostMapping("/refresh-token")
    public TokenResponse refresh(@Valid @RequestBody RotateTokenRequest req) {
        return userService.rotateTokens(req);
    }

    @PostMapping("/signout")
    @PreAuthorize("isAuthenticated()")
    public Map<String, String> signOut(@CurrentUserId UUID userId) {
        userService.signOut(userId);
        return Map.of("message", "Successfully signed out.");
    }

    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public ProfileResponse createProfile(@CurrentUserId UUID userId,
                                         @Valid @RequestBody CreateProfileRequest req) {
        return userService.createProfile(userId, req);
    }

    @GetMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ProfileResponse getProfile(@CurrentUserId UUID userId) {
        return userService.getProfile(userId);            // throws NotFoundException → 404
    }

    @PatchMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ProfileResponse updateProfile(@CurrentUserId UUID userId,
                                         @Valid @RequestBody UpdateProfileRequest req) {
        return userService.updateProfile(userId, req);
    }
}
```

Note how thin this is compared to `UsersController.cs` — the manual validator calls and the per-action `try/catch` blocks are gone, replaced by `@Valid` and a single advice.

## 4.14 Exception → Status Mapping

The .NET code maps exceptions to statuses inline in each action. Centralise it:

| .NET exception | Status | Java exception |
| :--- | :--- | :--- |
| `InvalidOperationException` | `409` | `ConflictException` |
| `UnauthorizedAccessException` | `401` | `UnauthorizedException` |
| `KeyNotFoundException` | `404` | `NotFoundException` |
| FluentValidation failure | `400` | `MethodArgumentNotValidException` |

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    /** == validationResult.ToDictionary(): field -> [messages] */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, List<String>>> invalid(MethodArgumentNotValidException e) {
        Map<String, List<String>> errors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.groupingBy(FieldError::getField,
                         Collectors.mapping(FieldError::getDefaultMessage, Collectors.toList())));
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)   // F-12
    ResponseEntity<Map<String, String>> constraint(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "A record with these details already exists."));
    }
}
```

`@RestControllerAdvice` does **not** cover failures thrown inside the security filter chain — those never reach the dispatcher servlet. That is why §4.6 also registers an `AuthenticationEntryPoint` and an `AccessDeniedHandler`: without them Spring returns an empty-bodied 401/403 while every other error returns JSON.

## 4.15 Module Registration — `AddUserModule` → `@Configuration`

```java
@Configuration
@EnableJpaRepositories(basePackages = "com.tasked.users.repo")
@EntityScan(basePackages = "com.tasked.users.domain")
@ComponentScan(basePackages = "com.tasked.users")
public class UsersModuleConfig {}
```

The host imports the modules explicitly, mirroring the two `AddXModule` calls in `Program.cs`:

```java
@SpringBootApplication(scanBasePackages = "com.tasked.host")
@Import({ UsersModuleConfig.class, TasksModuleConfig.class, SharedInfraConfig.class })
@ConfigurationPropertiesScan
public class TaskedApplication {
    public static void main(String[] args) { SpringApplication.run(TaskedApplication.class, args); }
}
```

Explicit `@Import` over broad component scanning keeps the dependency direction visible and makes accidental cross-module wiring obvious.

Unlike .NET — where each module gets its own `DbContext` — a single `DataSource`/`EntityManagerFactory` with per-module `@EnableJpaRepositories` packages is the right default here. It gives the same package-level isolation without the cost of multiple persistence units, and a transaction still spans a single module. Only split into separate `EntityManagerFactory` beans if you actually need per-module transaction isolation.

## 4.16 Feature Parity Checklist

| Behaviour | .NET | Spring |
| :--- | :--- | :--- |
| Separate access/refresh secrets | ✅ | ✅ two `JwtDecoder`/`JwtEncoder` pairs |
| Zero clock skew | ✅ `ClockSkew.Zero` | ✅ `new JwtTimestampValidator(Duration.ZERO)` |
| Issuer + audience validated | ✅ | ✅ `JwtIssuerValidator` + aud `JwtClaimValidator` |
| Password BCrypt wf 11 | ✅ | ✅ `new BCryptPasswordEncoder(11)` |
| Refresh SHA-256 → BCrypt wf 10 | ✅ | ✅ `TokenSecurityHelper` |
| Refresh rotation on every use | ✅ | ✅ |
| Reuse detection → session revoke | ✅ | ✅ |
| Revoke on *unverified* token | ⚠️ yes (F-2) | ❌ removed by design |
| Single session per user | ✅ | ✅ (child table recommended, F-3) |
| Identity from token, never body | ✅ | ✅ `@CurrentUserId` |
| Role in token claim | ✅ (MS URI) | ✅ plain `role` → `ROLE_*` authority |
| Named role policies | ✅ declared, unused | ✅ `Policies` constants — **apply them** |
| Client-chosen role at signup | ⚠️ yes (F-1) | ❌ removed by design |
| Rate limiting | ❌ | ➕ add (F-10) |
| Global exception handling | ❌ | ➕ `@RestControllerAdvice` (F-8) |
| Tasks endpoints protected | ❌ (F-7) | ➕ protect + scope by user id |

---

# Part 5 — Implementation Order & Test Checklist

## 5.1 Build Order

1. **Skeleton** — parent pom, four modules, dependency direction, `TaskedApplication` boots with an empty `SecurityFilterChain`.
2. **Persistence** — Flyway migrations from §1.3, `UserEntity`/`ProfileEntity`, repositories, `ddl-auto: validate` passing against a Testcontainers Postgres.
3. **Shared auth primitives** — `JwtProperties`, `JwtTokenService`, `TokenSecurityHelper`, `Role`/`Gender`. Unit-test these in isolation before any HTTP exists.
4. **Security config** — `SecurityFilterChain`, decoder, authorities converter, entry point / denied handler, `@CurrentUserId` resolver.
5. **Users module** — DTOs + validation, `UserServiceImpl` (register → login → signout → rotate, in that order), `UsersController`.
6. **Exception handling** — `@RestControllerAdvice`, then delete every leftover `try/catch` from the controllers.
7. **RBAC** — apply `@PreAuthorize` for real; add an admin-only endpoint (e.g. role promotion) to prove the wiring.
8. **Tasks module** — port it, protect it, and thread `@CurrentUserId` through create *and* every read (fixes F-7).
9. **Hardening** — rate limiting (F-10), refresh-session child table (F-3, F-4), secret externalisation (F-5), pessimistic lock on rotation (§4.12).

## 5.2 Tests That Must Pass

**Token / hashing unit tests**
- [ ] An access token is **rejected** by the refresh decoder, and vice versa.
- [ ] A token expired by 1 second fails validation (proves zero clock skew).
- [ ] Wrong `iss` fails; wrong `aud` fails.
- [ ] `doubleHashToken` output verifies via `verifyDoubleHashedToken`; a different token does not.
- [ ] Two ~500-char tokens differing only after byte 72 produce **different** stored hashes (proves the SHA-256 pre-hash actually matters).
- [ ] `sha256Hex` returns 64 uppercase hex characters.

**Security integration tests (`@SpringBootTest` + `MockMvc` + Testcontainers)**
- [ ] No `Authorization` header on `GET /users/profile` → `401` with a JSON body.
- [ ] Malformed / wrong-signature bearer → `401`.
- [ ] Expired access token → `401`.
- [ ] Valid token, insufficient role → `403` with a JSON body.
- [ ] `hasRole('ADMIN')` passes for a token carrying `role: "ADMIN"` (catches the `ROLE_` prefix trap).

**Flow tests**
- [ ] Register → duplicate email → `409`.
- [ ] Register never yields an admin, even if the client sends `role`/`authorities` in the body (F-1 regression test).
- [ ] Login with a bad password and login with an unknown email return the **same** status and message.
- [ ] Login → `refresh-token` → `200` with a *different* access **and** refresh token.
- [ ] Replaying the **previous** refresh token after a rotation → `401 "Token reuse detected"` **and** `users.refresh_token` is now `NULL`.
- [ ] After reuse detection, the *newest* refresh token also fails → the whole session is dead.
- [ ] Sign out → the refresh token no longer works; `refresh_token` is `NULL`.
- [ ] Sign out twice → still `200` (idempotent).
- [ ] A forged, unsigned JWT carrying a victim's `sub` sent to `/users/refresh-token` returns `401` and leaves the victim's `refresh_token` **untouched** (F-2 regression test).
- [ ] Profile create/get/update always resolve the identity from the token — a body-supplied `userId` is ignored.
- [ ] Creating a second profile for the same user → `409`.
- [ ] Duplicate phone on create and on update → `409`, not `500`.
- [ ] Concurrent refresh with the same token: exactly one succeeds (§4.12 locking).

## 5.3 Quick Reference — Constants To Keep Identical

| Thing | Value |
| :--- | :--- |
| JWT algorithm | `HS256` |
| Password BCrypt work factor | `11` |
| Refresh-token BCrypt work factor | `10` |
| Refresh pre-hash | `SHA-256`, uppercase hex, 64 chars |
| Clock skew | `0` |
| Access token claim set | `sub`, `email`, `role`, `jti`, `iss`, `aud`, `exp`, `iat` |
| `refresh_token` column | `text`, nullable — `NULL` means no active session |
| Role values in DB | `ADMIN` / `USER` (was `Admin` / `User` in .NET) |
| Gender values in DB | `MALE` / `FEMALE` / `OTHER` (was `Male` / `Female` / `Other`) |
