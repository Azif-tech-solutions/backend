# 01 — User / Tenant / Access-Control / Locations

Status: **design finalized, implementation in progress**
Owner packages: `com.zifiq.platform.identity`, `com.zifiq.platform.tenancy`, `com.zifiq.platform.accesscontrol`, `com.zifiq.platform.security.otp`, `com.zifiq.settings`
Depends on: `00-platform-foundation.md` (Exposed, `TenantScopedTransaction`, `ApiEnvelope`, `DocumentSequenceService`, `OtpChallengeService`, notification outbox)

## 1. Scope

- Everything already built (register-tenant, login, refresh, switch-tenant, change/forgot/reset password, tenant-child creation, role create/list, role assignment) — kept behaviorally identical, just ported to Exposed per doc 00.
- **New**: proper member-invite flow (fixes a real bug — see §5), member status (active/suspended), per-user location scoping, default 8-role seeding matching the PRD's permission matrix, Locations (Factories/Showrooms/Branches) as real persisted entities, and the generic OTP-challenge endpoints.
- Frontend mapping: this module is the backend for `Onboarding.jsx`, `SignIn.jsx`, `SignUp.jsx`, `Branches.jsx`, Settings → Company/Factories, and Menu → User Register (`sampleMenu.js`'s `systemUsers`/`systemRoles`).

## 2. Roles & permission matrix (PRD §7)

Eight roles, seeded automatically for every new tenant (root, via `registerTenant`, and every child, via `TenantService.createChild`) as editable `tenant_roles` rows — an Admin can still rename/adjust them afterward via the existing `RoleManagementService`, this just replaces today's "one role with every permission" seed.

| Role | Permission codes granted at seed time | PRD capability (§7) |
|---|---|---|
| **Administrator** | *every code in `permission_catalog`* | View, Create, Edit, Approve, Delete |
| **Management** | `factory.stock.read`, `factory.costing.read`, `factory.costing.approve`, `showroom.stock.read`, `accounting.read`, `cashier.sales.read` | View (incl. cost), Approve — no create/edit/delete |
| **Accounts Personnel** | `accounting.read`, the granular `accounting.*` codes of doc 07 §3, `cashier.sales.read` | View, Create, Edit, Approve (financial) |
| **Stock Keeper** | `factory.stock.read`, `factory.stock.manage`, `factory.adjustments.manage`, `factory.transfers.manage`, `showroom.stock.read`, `showroom.stock.manage` | View, Create (stock entries/adjustments) — the only role allowed to post quantity adjustments |
| **Quality Control** | `factory.stock.read`, `factory.production.manage`, `factory.recipes.manage` | View (no cost — `factory.costing.read`/`factory.costing.approve` deliberately **not** granted), Create (production entries) |
| **Cashier** | `cashier.sales.read`, `cashier.sales.manage` | View (own transactions), Create (POS/invoices) |
| **Correspondent** | *(none yet)* | View (own customers) — codes land with the Customers module |
| **Dealer / Distributor** | *(none yet)* | View (own account), self-service — codes land with the Customers module |

Implementation: a static `platform/accesscontrol/RoleDefaults.kt` — `val ROLE_DEFAULTS: Map<String, Set<String>>` (role name → permission codes it should hold, `"*"` sentinel for Administrator meaning "every current catalog code"). Every future module that adds permission codes updates this map's relevant role entries as part of its own doc/migration — this file is never redesigned, only extended. Role-seeding logic (`AuthService.registerTenant`, `TenantService.createChild`) creates one `tenant_roles` row per map entry, resolves `"*"` against the live `permission_catalog` at seed time (so Administrator always gets newly-added codes from later modules automatically), and inserts `role_permissions` accordingly. The creator of a tenant is assigned the **Administrator** role.

## 2a. Bugs found and fixed during implementation (via live end-to-end testing, not just unit tests)

Three pre-existing, production-breaking bugs in the original scaffold were only caught by actually running the app against a real Postgres instance and exercising the HTTP API — none of them were visible from the original Mockito-mocked-`JdbcTemplate` unit tests, since those bypassed the web layer and RLS entirely. All three are fixed as part of this migration:

1. **`TenantContextInterceptor` blocked every `permitAll` endpoint.** It ran for all `/api/**` paths and unconditionally threw 401 if there was no `JwtAuthenticationToken` — including `register-tenant`, `login`, `refresh`, `forgot-password`, `reset-password`. Fixed in `platform/tenancy/TenantContextInterceptor.kt`: it now passes through (leaving `TenantContext` unset) when there's no JWT, trusting that Spring Security's filter chain (which runs before MVC dispatch) already rejected anything that actually required authentication.
2. **`tenants_scope`'s `WITH CHECK` made root-tenant self-registration impossible.** It required `parent_tenant_id = current app.tenant_id`, but a root tenant has `parent_tenant_id = null` and there's no tenant context yet at signup — `null = null` is never `true` in SQL, so `AuthService.registerTenant`'s `insert into tenants` always violated RLS. Separately, **no connection could ever enumerate tenants** (needed by `NotificationOutboxDispatcher`'s per-tenant sweep) because the old `USING` clause also required a tenant context first. V3 drops and recreates `tenants_scope` to allow both: reads with no tenant context at all (this table has no sensitive columns), and root-tenant inserts when `parent_tenant_id is null` and no context is set.
3. **`tenant_memberships_scope` made `login` itself always fail.** `AuthService.login` looks up which tenant(s) a user belongs to — necessarily *before* any tenant context exists, since discovering it is the point of the query. The original policy required a tenant context first, so this lookup always returned zero rows and login always failed with `NO_TENANT_MEMBERSHIP`, for every user, unconditionally. Same fix pattern as #2: `tenant_memberships_scope` now allows unscoped reads (writes remain strictly `WITH CHECK`-scoped).

All three were verified fixed by running the actual Spring Boot app against a real Postgres container and exercising `register-tenant` → `login` → `switch-tenant` → `refresh` → `forgot-password` (outbox drains to `SENT`) → locations → member invite → OTP request/issue/verify, plus a basic two-tenant RLS isolation check, end to end over HTTP.

## 3. Schema — migration `V3__locations_and_rbac_expansion.sql`

### 3.1 `tenant_memberships` — add a status column (missing from V1)
```sql
alter table tenant_memberships
    add column status varchar(20) not null default 'ACTIVE'
    check (status in ('ACTIVE', 'SUSPENDED'));
```

### 3.2 `locations`
```sql
create table locations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    type varchar(20) not null check (type in ('FACTORY', 'SHOWROOM')),
    code varchar(40) not null,
    name varchar(160) not null,
    address varchar(300),
    state varchar(80),
    phone varchar(40),
    manager_user_id uuid references app_users(id),
    vat_rate numeric(5,2) not null default 7.50,
    vat_enabled boolean not null default true,
    vat_registration_number varchar(60),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'PENDING', 'SUSPENDED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
alter table locations enable row level security;
alter table locations force row level security;
create policy locations_scope on locations
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```
`code` format when auto-generated: `FAC-###` for `type=FACTORY`, `BR-###` for `type=SHOWROOM`, via `DocumentSequenceService.next("FAC")` / `.next("BR")`.

### 3.3 `document_sequences`
```sql
create table document_sequences (
    tenant_id uuid not null references tenants(id),
    prefix varchar(10) not null,
    next_value bigint not null default 1,
    primary key (tenant_id, prefix)
);
alter table document_sequences enable row level security;
alter table document_sequences force row level security;
create policy document_sequences_scope on document_sequences
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```
`DocumentSequenceService.next(prefix)`:
```sql
insert into document_sequences (tenant_id, prefix, next_value) values (?, ?, 1)
    on conflict (tenant_id, prefix) do update set next_value = document_sequences.next_value + 1
    returning next_value;
```
Formats as `"$prefix-$value"` (no zero-padding — matches frontend samples like `RF-2482`, `DL-1141`, four-plus free-running digits).

### 3.4 `user_location_assignments`
```sql
create table user_location_assignments (
    tenant_id uuid not null,
    user_id uuid not null,
    location_id uuid references locations(id),
    access_scope varchar(20) not null check (access_scope in ('SINGLE_LOCATION', 'ALL_LOCATIONS')),
    assigned_by_user_id uuid not null references app_users(id),
    assigned_at timestamptz not null default now(),
    primary key (tenant_id, user_id),
    foreign key (tenant_id, user_id) references tenant_memberships(tenant_id, user_id) on delete cascade,
    constraint single_location_requires_id check (
        (access_scope = 'SINGLE_LOCATION' and location_id is not null) or
        (access_scope = 'ALL_LOCATIONS' and location_id is null)
    )
);
alter table user_location_assignments enable row level security;
alter table user_location_assignments force row level security;
create policy user_location_assignments_scope on user_location_assignments
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```
One row per `(tenant_id, user_id)` — a user's scope is either exactly one location or "all locations" (Admin/Management default), matching the frontend's `location: 'All locations' | <showroom name>` field. Every future business-module query that needs to enforce "users only see their assigned location's records unless elevated" joins against this table.

### 3.5 `otp_challenges`
```sql
create table otp_challenges (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    requested_by_user_id uuid not null references app_users(id),
    purpose varchar(30) not null check (purpose in ('WALLET_WITHDRAWAL', 'QUANTITY_ADJUSTMENT')),
    target_reference varchar(60) not null,
    code_hash varchar(255),
    status varchar(20) not null default 'AWAITING' check (status in ('AWAITING', 'SENT', 'USED', 'EXPIRED')),
    issued_by_user_id uuid references app_users(id),
    expires_at timestamptz,
    created_at timestamptz not null default now()
);
alter table otp_challenges enable row level security;
alter table otp_challenges force row level security;
create policy otp_challenges_scope on otp_challenges
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```
`code_hash` uses the existing `PasswordEncoder` (BCrypt) bean — plaintext code is returned once, in the `issue` response, and pushed via `notification_outbox` to the requester; it is never stored or logged in plaintext. Expiry: 15 minutes from `issue` (same window as password-reset tokens).

### 3.6 `permission_catalog` additions
```sql
insert into permission_catalog (code, description) values
    ('iam.users.manage', 'Create and manage tenant member accounts (beyond role assignment)'),
    ('settings.manage', 'Manage company profile settings'),
    ('settings.locations.manage', 'Create and manage factories/showrooms/branches'),
    ('otp.issue', 'Review and issue OTP challenges for gated actions');
```
(Factory-specific codes — `factory.recipes.manage`, `factory.production.manage`, `factory.costing.read`, `factory.costing.approve`, `factory.transfers.manage`, `factory.adjustments.manage`, `factory.trade.manage` — are added by `V4__factory_module.sql`, documented in `02-factory.md`, and folded into `RoleDefaults` at that point; the Management/QC/Stock Keeper rows in §2 above already assume those codes exist, since role seeding always resolves against whatever the full catalog is at tenant-creation time.)

`otp.issue` is granted **only** to Administrator by default (PRD: an Admin manually reviews and issues the code) — add it to `RoleDefaults["Administrator"]` (covered automatically by the `"*"` sentinel) and explicitly **not** to any other seeded role.

## 4. Endpoints

All under `@SecurityRequirement(name = "bearerAuth")`, all return `ApiEnvelope<T>`, all validated request DTOs use `jakarta.validation` annotations.

### 4.1 Existing (behavior unchanged — ported to Exposed per doc 00 §3.5)
`POST /api/v1/auth/register-tenant`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/switch-tenant`, `POST /api/v1/auth/change-password`, `POST /api/v1/auth/forgot-password`, `POST /api/v1/auth/reset-password`, `GET /api/v1/me`, `GET /api/v1/tenants` (list children), `POST /api/v1/tenants` (create child), `GET /api/v1/tenants/roles`, `POST /api/v1/tenants/roles`, `PUT /api/v1/tenants/members/{userId}/roles/{roleId}`. DTOs unchanged from `platform/identity/dto/AuthDtos.kt`, `platform/accesscontrol/*`.

### 4.2 New — `platform/identity/api/MembersController.kt` (or extend `IdentityController`)

**`POST /api/v1/tenants/members`** — invite a member. Permission: `iam.users.manage`.
Request:
```kotlin
data class InviteMemberRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val displayName: String,
    val phone: String?,
    @field:NotNull val roleId: UUID,
    val locationId: UUID?,          // null => ALL_LOCATIONS
)
```
Response:
```kotlin
data class MemberSummary(
    val userId: UUID, val email: String, val displayName: String, val phone: String?,
    val status: String,             // ACTIVE | SUSPENDED
    val role: RoleRef,              // { id, name }
    val location: LocationRef?,     // { id, name, type } — null when ALL_LOCATIONS
    val invitedAt: Instant,
)
```
Logic: normalize email lower-case. If `app_users` has no row for that email, create one (`password_hash = null`). If a row exists but the user is **already** a member of this tenant → `409 USER_ALREADY_MEMBER`. Insert `tenant_memberships(tenant_id, user_id, created_by_user_id = actor.userId, status='ACTIVE')`. Call the existing `RoleAssignmentService.assignRole(userId, roleId)` (its "creator or `iam.users.assign` holder" check passes automatically since `created_by_user_id` is the inviter). Insert `user_location_assignments` (`SINGLE_LOCATION`+`locationId` if provided, else `ALL_LOCATIONS`+`null`). Generate a `password_reset_tokens` row (reusing the existing forgot-password mechanism — no new token type) and write a `notification_outbox` EMAIL row: subject "You've been invited to ZifIQ", body containing the reset-token link. This fixes the discovery bug where `MenuService.registerUserAndAssignRole` assigned a role to a random `UUID` with no backing `app_users`/`tenant_memberships` rows (would violate FK constraints against a real database).
Errors: `409 USER_ALREADY_MEMBER`, `404 ROLE_NOT_FOUND`, `404 LOCATION_NOT_FOUND`, `403 ROLE_ASSIGNMENT_FORBIDDEN` (bubbled from `RoleAssignmentService`).

**`GET /api/v1/tenants/members`** — list members with role + location + status (backs the frontend's User Register list, `systemUsers`). Permission: `iam.users.manage`. Response: `List<MemberSummary>`.

**`PUT /api/v1/tenants/members/{userId}/status`** — Body `{ status: "ACTIVE" | "SUSPENDED" }`. Permission: `iam.users.manage`. Errors: `404 MEMBER_NOT_FOUND`, `400 VALIDATION_FAILED` (invalid status value).

**`PUT /api/v1/tenants/members/{userId}/location`** — Body `{ locationId: UUID? }` (null ⇒ ALL_LOCATIONS). Permission: `iam.users.manage`. Upserts `user_location_assignments`. Errors: `404 MEMBER_NOT_FOUND`, `404 LOCATION_NOT_FOUND`.

`com.zifiq.menu`'s user-registration endpoint (`MenuController`/`MenuService`) is simplified to a thin pass-through that calls this new platform endpoint's underlying service directly — the menu module stops owning any identity persistence logic, per the "global user query pattern every module inherits" rule.

### 4.3 New — `platform/security/otp/api/OtpController.kt`

**`POST /api/v1/otp/challenges`** — any authenticated user. Request: `{ purpose: "WALLET_WITHDRAWAL" | "QUANTITY_ADJUSTMENT", targetReference: String }`. Response: `{ challengeId, status: "AWAITING", expiresAt: null }`. Writes a `notification_outbox` PUSH/in-app row addressed to every tenant member holding `otp.issue` (i.e. Administrators), per the PRD notification table ("Wallet withdrawal or qty adjustment requested → Admin → In-app request").

**`POST /api/v1/otp/challenges/{id}/issue`** — Permission: `otp.issue`. Generates a 6-digit numeric code, stores its BCrypt hash, sets `status='SENT'`, `issued_by_user_id`, `expires_at = now() + 15m`. Response includes the **plaintext code once**: `{ challengeId, code, expiresAt }`. Also writes a `notification_outbox` row (PUSH/in-app) to the original requester containing the code. Errors: `404 CHALLENGE_NOT_FOUND`, `409 CHALLENGE_NOT_AWAITING` (already issued/used/expired).

**`POST /api/v1/otp/challenges/{id}/verify`** — must be called by `requested_by_user_id`. Body `{ code: String }`. On match: `status='USED'`, returns `{ verified: true }`. On mismatch: `400 INVALID_OTP_CODE` (does not change status, allows retry until expiry). On expiry: `410 OTP_EXPIRED`. Errors: `403 NOT_CHALLENGE_OWNER`.
Consumers (e.g. Factory's Quantity Adjustment, doc 02) require a `USED` challenge with matching `purpose`, requested by the same actor now attempting the gated action (`OtpChallengeService.isVerified(challengeId, purpose)`), before permitting the write — see doc 02 §3.6 for why this is simpler than also matching `target_reference`.

### 4.4 New — `com.zifiq.settings` Locations CRUD (replaces the `getBranches`/`createBranch` mock)

**`GET /api/v1/settings/locations?type=FACTORY|SHOWROOM`** — any authenticated tenant member (reference data for dropdowns; no special permission). Response: `List<LocationSummary>` (`id, type, code, name, address, state, phone, managerUserId, vatRate, vatEnabled, vatRegistrationNumber, status`).

**`POST /api/v1/settings/locations`** — Permission: `settings.locations.manage`. Request: `{ type, code: String? (auto-generated if blank), name, address?, state?, phone?, vatRate? (default 7.50), vatEnabled? (default true), vatRegistrationNumber? }`. Response: `LocationSummary`. Backs the frontend's Onboarding steps 2–3 (first branch/first factory, optional/skippable) and the standalone Settings → Branches/Factories screens — same endpoint, no separate "onboarding" API.

**`PUT /api/v1/settings/locations/{id}`** — Permission: `settings.locations.manage`. Same body shape (all fields optional/patch-like except those already set). Errors: `404 LOCATION_NOT_FOUND`.

**`PUT /api/v1/settings/locations/{id}/status`** — Body `{ status: "ACTIVE" | "PENDING" | "SUSPENDED" }`. Permission: `settings.locations.manage`.

## 5. Business rules carried into this module (source-traced)

- "Users will be assigned exactly one primary role" — PRD §12 Assumptions. Enforced structurally: `user_role_assignments` PK is `(tenant_id, user_id, role_id)` allowing multiple technically, but the invite flow (`InviteMemberRequest.roleId`, singular) and every UI path only ever assigns one at creation time; changing role is a distinct future "reassign role" action, not additive multi-role assignment.
- "Users can only access records associated with their assigned showroom, factory, or role, unless granted elevated (Admin/Management) access" — PRD §6 Business Rules. Implemented via `user_location_assignments`; every business-module repository query from `02-factory.md` onward filters by the caller's assigned `location_id` unless their role holds a read-all-locations permission (Administrator/Management's seeded permission set doesn't get a location filter bypass automatically — the location filter is orthogonal to RBAC permission codes, so even an Administrator scoped to `SINGLE_LOCATION` is location-filtered; `ALL_LOCATIONS` scope, not role name, is what removes the filter).
- "Admin manually reviews a verbal request and issues a one-time code" — PRD §6 rule 8, confirmed against the frontend's `Wallet Withdrawal OTP` screen shape (`{ref, requested, requester, role, purpose, target, amount, code, status, expires}`) — modeled as `otp_challenges` + the three-step request/issue/verify flow in §4.3, not an automated SMS provider.
- Password policy: PRD §10 flags a legacy "6-character maximum" password constraint as needing reconciliation. **Decision**: do not carry this forward — `AuthDtos.kt`'s existing `UserRegistrationRequest`/password DTOs keep a sane minimum-length validation (`@Size(min = 8)`, already effectively what `SignUp.jsx` enforces client-side) and no maximum. Flagged here as an explicit deviation from the literal PRD text, since the PRD itself calls the 6-char cap out as a problem, not a requirement to preserve.
- Branch/showroom auto-generated unique code — PRD §5.5.3 ("Showroom Register... auto-generates unique branch code") — implemented via `DocumentSequenceService.next("BR")` / `.next("FAC")` when `code` is omitted from the create-location request.

## 6. Worked example — invite + first login

1. Administrator (already holding `iam.users.manage` via the Administrator seed) calls `POST /api/v1/tenants/members`:
   ```json
   { "email": "keeper@zifiq.com", "displayName": "Amaka Obi", "phone": "+234...", "roleId": "<Stock Keeper role id>", "locationId": "<FAC-001 id>" }
   ```
2. Response:
   ```json
   { "data": { "userId": "...", "email": "keeper@zifiq.com", "displayName": "Amaka Obi", "phone": "+234...",
                "status": "ACTIVE", "role": {"id": "...", "name": "Stock Keeper"},
                "location": {"id": "...", "name": "Factory A", "type": "FACTORY"}, "invitedAt": "2026-09-30T..." } }
   ```
3. A `notification_outbox` EMAIL row is queued; `NotificationOutboxDispatcher` (doc 00 §5.2) drains it, invoking `EmailNotificationAdapter` with the password-set link.
4. Amaka follows the link → `POST /api/v1/auth/reset-password` with the token + her chosen password → can now `POST /api/v1/auth/login`.
