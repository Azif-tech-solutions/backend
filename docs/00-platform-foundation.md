# 00 — Platform Foundation

Status: **design finalized, implementation in progress**
Owner package: `com.zifiq.platform.*`
Depends on: nothing (this is the base every other module builds on)

## 1. Purpose

This document specifies the cross-cutting platform layer every business module (`factory`, `showroom`, `account`, `cashier`, `menu`, `settings`, `process`, `history`, `customers`, `invoicing`, `barcode`, `ai`) depends on:

- how the app talks to Postgres (Exposed, replacing the original `JdbcTemplate` approach)
- the tenant-scoped-transaction / Row-Level-Security contract
- the response envelope and global error shape
- the notification system (channels + outbox + dispatcher)
- shared utilities every module reuses instead of re-implementing: document numbering, OTP challenges, cost-field masking

It does **not** cover identity/tenancy/RBAC/locations schema (see `01-user-tenant-access-control.md`) or any business module.

## 2. Module layout convention (applies to every module, platform and business)

```
<module>/
  api/         REST controllers. @RestController, @RequestMapping("/api/v1/<module>"),
               @Tag/@Operation/@SecurityRequirement(name = "bearerAuth") for OpenAPI,
               @PreAuthorize("@permissionAuthorizer.hasPermission(authentication, '<code>')")
               on every endpoint, returns ApiEnvelope<T>.
  dto/         Request/response data classes only. Validation via jakarta.validation annotations
               (@NotBlank, @Email, @Positive, etc.) on request DTOs.
  model/       Exposed `Table` objects, one file per table (or grouped by aggregate),
               named `<Thing>Table.kt`, mirrors the Flyway-managed schema exactly.
  repository/  Exposed DSL query functions only — no permission checks, no business rules,
               no DTO construction. Takes/returns domain values or row data classes.
  service/     Business logic: permission checks (via PermissionAuthorizer), tenant/location
               scoping, calls into repository, builds DTOs, applies CostVisibility masking,
               writes to notification_outbox, calls DocumentSequenceService for reference numbers.
```

Controllers never touch Exposed directly. Repositories never check permissions. Services are the only layer allowed to throw `ApiException`.

## 3. Database access: Exposed

### 3.1 Gradle changes (`backend/build.gradle.kts`)

Remove:
```kotlin
kotlin("plugin.jpa") version "2.1.10"                                   // plugin block
implementation("org.springframework.boot:spring-boot-starter-data-jpa") // dependency
```

Add:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-jdbc")     // DataSource/Hikari autoconfig, no Hibernate
implementation("org.jetbrains.exposed:exposed-core:0.61.0")
implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
implementation("org.jetbrains.exposed:exposed-java-time:0.61.0") // Instant/LocalDate columns
implementation("org.jetbrains.exposed:exposed-json:0.61.0")      // jsonb columns (materials_snapshot etc.)
```
Pinned to `0.61.0` deliberately — the last release before Exposed 1.0's `org.jetbrains.exposed.v1.*` package restructuring (which splits JDBC/R2DBC). `0.61.0` uses the stable, long-established `org.jetbrains.exposed.sql.*` package layout (`Database.connect`, `transaction { }`, `Table`, `insert { }`, `selectAll().where { }`, etc.) and is fully compatible with Kotlin 2.1.10 as a normal JVM library dependency — no Kotlin-version coupling. Revisit the 1.x line once it stabilizes past its first releases.

Test dependencies, add:
```kotlin
testImplementation("org.testcontainers:postgresql:<latest-stable>")
testImplementation("org.testcontainers:junit-jupiter:<latest-stable>")
```

### 3.2 Database bootstrap

New `platform/config/ExposedConfiguration.kt`:
- `@Configuration` class with a `@Bean` that calls `Database.connect(dataSource)` using the Spring-managed `DataSource` (autoconfigured from `spring.datasource.*` in `application.yml` via `spring-boot-starter-jdbc`).
- Runs **after** Flyway has migrated (Flyway's own `DataSource`-consuming auto-config already runs before application startup completes; Exposed just needs the same `DataSource` bean, no explicit ordering needed since both are Spring-managed singletons resolved lazily).
- No Exposed DDL (`SchemaUtils.create`) is ever called — Flyway is the only schema authority. Exposed `Table` objects exist purely as typed query targets.

### 3.3 `TenantScopedTransaction` (rewritten)

File: `platform/tenancy/TenantScopedTransaction.kt`

```kotlin
@Component
class TenantScopedTransaction(
    private val database: Database,
    private val tenantContext: TenantContext,
) {
    fun <T> execute(block: Transaction.() -> T): T = transaction(database) {
        exec("select set_config('app.tenant_id', ?, true)", listOf(VarCharColumnType() to tenantContext.requireTenantId().toString()))
        block()
    }
}
```

Contract (unchanged from the original `JdbcTemplate` version, now enforced through Exposed):
- `set_config(..., true)` — the `true` third argument makes the setting **transaction-local**. It is reset automatically when the Exposed `transaction { }` block commits or rolls back. Never use `false` (session-level) — that would leak tenant context across pooled connections.
- This is the **only** way any code may set `app.tenant_id`. No service calls `exec("set_config...")` directly.
- Every service method that touches the database wraps its body in `tenantScopedTransaction.execute { ... }`.
- Spring's `@Transactional` is **not** used anywhere in `platform.*` or any business module — Exposed's own `transaction()` is the single transaction boundary, to avoid two competing transaction managers wrapping the same JDBC connection.

### 3.4 Exposed `Table` objects for the existing schema

One file per table under `platform/<owning-subpackage>/model/`:

**`platform/tenancy/model/TenantsTable.kt`**
```kotlin
object TenantsTable : UUIDTable("tenants") {
    val parentTenantId = reference("parent_tenant_id", TenantsTable).nullable()
    val name = varchar("name", 160)
    val createdAt = timestampWithTimeZone("created_at")
}
```
(Self-reference requires `TenantsTable` declared once and referenced lazily — use Exposed's documented pattern for self-referencing tables, e.g. declaring the FK column after the object body via `init { }` or `reference` with `disableCreateAssociate`.)

**`platform/identity/model/AppUsersTable.kt`**
```kotlin
object AppUsersTable : UUIDTable("app_users") {
    val email = varchar("email", 320).uniqueIndex()
    val displayName = varchar("display_name", 160)
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at")
}
```

**`platform/tenancy/model/TenantMembershipsTable.kt`** — composite PK `(tenant_id, user_id)`: `tenantId`, `userId`, `createdByUserId` (nullable ref), `createdAt`.

**`platform/accesscontrol/model/PermissionCatalogTable.kt`** — `id (uuid)`, `code (varchar 160, unique)`, `description (varchar 300)`.

**`platform/accesscontrol/model/TenantRolesTable.kt`** — `id`, `tenantId`, `name (varchar 100)`, `description (varchar 300, nullable)`, `createdByUserId`, `createdAt`. Unique `(tenant_id, name)` and `(tenant_id, id)`.

**`platform/accesscontrol/model/RolePermissionsTable.kt`** — composite PK `(role_id, permission_id)`, both `ON DELETE CASCADE`.

**`platform/accesscontrol/model/UserRoleAssignmentsTable.kt`** — composite PK `(tenant_id, user_id, role_id)`, `grantedByUserId`, `grantedAt`. Composite FKs to `tenant_memberships(tenant_id, user_id)` and `tenant_roles(tenant_id, id)` — Exposed models these via `foreignKey(...)` block referencing the composite columns (not a single-column `reference`).

**`platform/notifications/model/NotificationOutboxTable.kt`** — `id`, `tenantId`, `channel (varchar 20)`, `recipient (varchar 320)`, `subject (varchar 300, nullable)`, `body (text)`, `metadata (jsonb, default '{}')`, `status (varchar 20, default PENDING)`, `attempts (integer, default 0)`, `createdAt`, `sentAt (nullable)`.

**`platform/identity/model/RefreshTokensTable.kt`** — `id`, `tokenHash (varchar 255, unique)`, `userId`, `tenantId`, `expiresAt`, `createdAt`, `revoked (boolean, default false)`.

**`platform/identity/model/PasswordResetTokensTable.kt`** — `id`, `tokenHash (unique)`, `userId`, `expiresAt`, `usedAt (nullable)`, `createdAt`.

All of the above already exist as tables via `V1__platform_foundation.sql` / `V2__auth_user_tenant_management.sql` — no new migration needed for this document. Exact column types/constraints/RLS policies are defined there; this doc's `Table` objects must match that SQL exactly (column lengths, nullability, defaults).

### 3.5 Files rewritten from `JdbcTemplate` to Exposed (behavior unchanged, implementation only)

| File | What changes |
|---|---|
| `platform/identity/AuthService.kt` | Every `jdbcTemplate.query/update/queryForObject` call becomes an Exposed DSL call inside `tenantScopedTransaction.execute { }` (registration itself sets tenant context mid-flow exactly as today — create tenant row first, `set_config`, then insert membership/role/assignment in the same Exposed transaction). `@Transactional` annotation removed. |
| `platform/identity/UserDirectory.kt` | `getCurrentUser`/`findInCurrentTenant`/`listTenantMembers`/`searchTenantMembers` become Exposed `select` joins on `AppUsersTable`/`TenantMembershipsTable`. |
| `platform/tenancy/TenantService.kt` | `listChildren`/`createChild` become Exposed inserts/selects on `TenantsTable` etc. |
| `platform/security/PermissionAuthorizer.kt` | The permission-check join (`user_role_assignments` ⋈ `tenant_roles` ⋈ `role_permissions` ⋈ `permission_catalog`) becomes one Exposed query with `.exists()` or a `.count() > 0` check. |
| `platform/accesscontrol/RoleAssignmentService.kt`, `RoleManagementService.kt` | Same translation pattern. |

None of these services' public method signatures change — callers (controllers, other services) are unaffected.

## 4. Response envelope & error handling (already correct, unchanged)

- `ApiEnvelope<T>(val data: T)` — every successful controller response wraps its payload in `data: {...}` (or `data: [...]` for lists), per `platform/api/ApiEnvelope.kt`.
- `ApiErrorEnvelope(val data: Nothing? = null, val error: ApiError)` where `ApiError(code, message, details: Map<String,String>)`.
- `GlobalExceptionHandler` (`platform/api/GlobalExceptionHandler.kt`) maps `ApiException` → its own status/code, `MethodArgumentNotValidException`/`ConstraintViolationException` → 400 `VALIDATION_FAILED`, `AccessDeniedException` → 403 `FORBIDDEN`, anything else → 500 `INTERNAL_ERROR`. Every module throws `ApiException(status, code, message, details)` for domain errors and lets validation annotations handle field-level errors — no module writes its own exception handler.

## 5. Notification system

### 5.1 Today

- `NotificationChannelType` enum: `PUSH, EMAIL, WHATSAPP, TELEGRAM`.
- `NotificationChannel` interface (`val type`, `fun send(message: NotificationMessage)`), one `@Component` implementation per type (`EmailNotificationAdapter`, `PushNotificationAdapter`, `WhatsAppNotificationAdapter`, `TelegramNotificationAdapter`) — all currently log-only stubs; swapping in a real provider (SendGrid, FCM, Twilio/WhatsApp Business API, Telegram Bot API) later only touches these four files.
- `NotificationDispatcher` — `fun send(channel: NotificationChannelType, message: NotificationMessage)`, routes to the matching adapter.
- `notification_outbox` table exists; nothing drains it. `AuthService.forgotPassword` currently calls `NotificationDispatcher.send` **synchronously inline** instead of writing to the outbox.

### 5.2 Target design

- Every module that needs to notify someone **writes a row to `notification_outbox`** (status `PENDING`) inside its own tenant-scoped transaction, instead of calling `NotificationDispatcher` directly. This makes notification delivery transactional with the business action that triggered it (e.g. a quantity-adjustment-above-threshold OTP request and its outbox row commit or roll back together).
- New `platform/notifications/NotificationOutboxDispatcher.kt`:
  ```kotlin
  @Component
  class NotificationOutboxDispatcher(
      private val database: Database,
      private val notificationDispatcher: NotificationDispatcher,
  ) {
      @Scheduled(fixedDelay = 5000)
      fun drain() {
          // For each tenant with PENDING rows (query across all tenants requires either
          // a superuser/bypass-RLS connection scoped to this one background job, or iterating
          // tenants and running tenantScopedTransaction per tenant) — see open question below.
      }
  }
  ```
- **Resolved**: the outbox table is RLS-protected on `tenant_id`, so a cross-tenant poll needs the list of tenant IDs first — but `tenants` itself was *also* unreadable with no tenant context set, which silently made the dispatcher iterate zero tenants forever (caught via live testing, not unit tests; see doc 01 §2a item 2). Fixed by carving out an explicit "no tenant context ⇒ allow read" case in `tenants_scope`'s `USING` clause (`V3__locations_and_rbac_expansion.sql`) — safe because `tenants` has no sensitive columns (just `id`/`parent_tenant_id`/`name`/`created_at`). The dispatcher does one `TenantScopedTransaction`-style re-scoped pass per known tenant (`select id from tenants`, then iterate); this keeps the "only `zifiq_app`, never a bypass-RLS role" security posture intact. It's O(tenant count) queries every 5s, fine at this stage — revisit with a dedicated bypass-RLS worker role only if tenant count makes that scan pattern a real bottleneck.
- On success: `status = 'SENT'`, `sent_at = now()`. On failure: `attempts += 1`, keep `status = 'PENDING'` until a max-attempts cap (e.g. 5) flips it to `FAILED`.
- `AuthService.forgotPassword` is updated to write to the outbox instead of calling `NotificationDispatcher` inline — this becomes the reference example every business module's service copies.

## 6. Shared utilities (introduced here, first consumed by Part B / `01-user-tenant-access-control.md`)

### 6.1 `platform/numbering/DocumentSequenceService.kt`

```kotlin
@Service
class DocumentSequenceService(private val tenantScopedTransaction: TenantScopedTransaction) {
    fun next(prefix: String): String = tenantScopedTransaction.execute {
        // atomic: insert row if absent (next_value=1), else UPDATE ... SET next_value = next_value + 1 RETURNING next_value
        // formats as "<prefix>-<padded-or-raw-integer>", e.g. next("RF") -> "RF-2483"
    }
}
```
Backs `document_sequences(tenant_id, prefix, next_value)` — see `01-user-tenant-access-control.md` §3.3 for the table definition. Single atomic `UPDATE ... RETURNING` (no separate `SELECT ... FOR UPDATE` step) avoids lock contention under concurrent posting. Every module that needs a reference number (`RF-`, `DL-`, `WB-`, `INV-`, `QA-`, `FT-`, `RT-`, `WD-`, `CA-`, `BF-`, `PPC-`, `TX-`, `LDG-`, `DE-`, `OS-`, `DS-`, `AN-`, `OCR-`, `BR-`, `VN-`, `ER-`, `CU-`, `DL-`, `PF-`, `SO-`/`SB-`, `FD-`, `PCF-`/`PCP-`, `RCP-`, `OTP-`) calls `documentSequenceService.next("<PREFIX>")` — no module re-implements numbering.

### 6.2 `platform/security/otp/OtpChallengeService.kt`

```kotlin
@Service
class OtpChallengeService(
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val notificationOutboxRepository: NotificationOutboxRepository, // writes the outbox row that surfaces the request to an Admin
) {
    fun request(purpose: OtpPurpose, targetReference: String): OtpChallengeSummary   // status=AWAITING, notifies Admins in-app
    fun issue(challengeId: UUID): Unit                                               // Admin manually reviews + triggers code generation; status=SENT
    fun verify(challengeId: UUID, code: String): Boolean                             // status=USED on success; throws ApiException on expired/mismatched
}
```
Models the PRD's actual flow precisely: an Admin **manually reviews a verbal/in-app request and issues a one-time code** — this is not an automated SMS-OTP service. `code_hash` stores a BCrypt hash of the issued code (never the plaintext), same `PasswordEncoder` bean already used for passwords. Backs `otp_challenges` — see `01-user-tenant-access-control.md` §3.4.

### 6.3 `platform/security/CostVisibility.kt`

```kotlin
@Component
class CostVisibility {
    fun <T> maskIfUnauthorized(value: T, visible: Boolean, zero: T): T = if (visible) value else zero
}
```
Called explicitly at DTO-construction time in service methods, e.g.:
```kotlin
val canSeeCost = permissionAuthorizer.hasPermission(actor.userId, "factory.costing.read")
FactoryStockItem(..., unitCost = costVisibility.maskIfUnauthorized(row.unitCost, canSeeCost, BigDecimal.ZERO))
```
Deliberately explicit per call site (not a Jackson annotation/serializer) so every masked field is visible in a code review, matching the PRD's repeated rule: restricted cost/value fields **display as zero**, they are never simply omitted from the payload.

### 6.4 Approval column convention (no shared code, just a shape)

Any table representing an approvable action includes exactly these four columns, named exactly this way, so every module's approve/reject endpoint looks the same to a reader:
```sql
status                 varchar(20) not null default 'PENDING',   -- module-specific enum incl. PENDING
approved_by_user_id    uuid references app_users(id),
approved_at            timestamptz,
rejection_reason       varchar(300)
```

## 7. Cleanup items bundled into this pass

- Remove `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` from `application.yml` — the app never used it (`SecurityConfiguration` builds its own local-secret `NimbusJwtDecoder`, ignoring this property entirely). Remove the matching "Set `JWT_JWK_SET_URI`..." line from `README.md`'s "Run locally" steps and replace with: "Set `jwt.secret` (env var `JWT_SECRET` if externalized) — ZifIQ issues and verifies its own HS256 JWTs locally; there is no external identity provider in this phase."
- `README.md`'s "Initial root-tenant and first-user provisioning belongs to the deployment/identity-provider bootstrap process" line is inaccurate — `AuthService.registerTenant` (`POST /api/v1/auth/register-tenant`) is the actual self-service root-tenant bootstrap path today and stays that way. Update the line to describe what's actually implemented.
- Update the module layout section of `README.md` to reference this `docs/` folder as the source of truth for schema/endpoint detail per module, rather than duplicating it inline.

## 8. Testing

- Replace Mockito-mocked-`JdbcTemplate` unit tests (`AuthServiceTest.kt`, `ModuleCoverageTest.kt`) with Testcontainers-backed integration tests: spin up `postgres:16-alpine` (matching `compose.yaml`), run the real Flyway migrations against it via `@DynamicPropertySource`, connect Exposed to the container's `DataSource`, exercise real service methods.
- Add one dedicated **RLS isolation test** in `platform` — not module-specific — asserting that a query scoped to tenant B via `TenantScopedTransaction` returns zero rows for data inserted under tenant A, across every RLS-protected table added from here on. This becomes the template test copied (with new table names) into every future module's test suite.
- `ModularityTests.kt` (`ApplicationModules.of(...).verify()`) must keep passing — package restructuring (`model`/`repository` additions) happens inside each module's existing top-level package, so Spring Modulith's boundary rules are unaffected.
