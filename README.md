# ZifIQ Backend

Kotlin / Spring Boot modular monolith for the ZifIQ operations platform. The initial contract is derived from the screens and navigation in `../zifiqqq/src/app`.

## Architecture

- Feature ownership lives under `com.zifiq.<feature>`; each feature owns its `api`, `service`, `repository`, and `model` packages. These direct subpackages are Spring Modulith modules and must not reach into another feature's internal packages.
- Shared cross-cutting capabilities live in `com.zifiq.platform`: API envelopes/errors, identity, tenancy, access control, and notifications.
- PostgreSQL Row-Level Security is the final tenant data boundary. Tenant-scoped services use `TenantScopedTransaction`, which sets transaction-local `app.tenant_id`; never use a session-level setting.
- Tenant identity comes from the verified JWT `tenant_id` claim, not an untrusted request header. Tenant switching must be authorized before issuing a token for that tenant.
- `POST /api/v1/tenants` creates a direct child tenant, then provisions the creator as that tenant's administrator. `POST /api/v1/auth/register-tenant` is the self-service root-tenant bootstrap path (creates the first user, the root tenant, and assigns that user the Administrator role) — there is no separate deployment-time bootstrap step.
- Authenticated user details are exposed through `CurrentActorProvider` and `UserDirectory`; feature APIs should not independently query user identity.
- Roles are tenant-owned and composed from reusable permissions. Tenant administrators can assign roles to members they created, and users with `iam.users.assign` can administer assignments within their tenant.
- Controllers return `ApiEnvelope<T>`. `GlobalExceptionHandler` maps failures to the same envelope shape and a stable error code.
- Springdoc scans Spring MVC controllers automatically. Add an annotated controller and its OpenAPI annotations; no separate endpoint registry is required.
- Notification delivery is port-based. Add provider adapters for push, email, WhatsApp, and Telegram without coupling feature services to vendors.

## Product modules

| Package | Prototype areas |
| --- | --- |
| `com.zifiq.factory` | stock, raw materials, recipes, production, transfers, cost, VAT, trade |
| `com.zifiq.showroom` | receiving, stock, transfers, returns, VAT |
| `com.zifiq.account` | cash flow, bank, petty cash, expenses, funds |
| `com.zifiq.cashier` | sales, pro-forma invoices, waybills, POS |
| `com.zifiq.process` | exchange rates, locations, commissions, VAT, recycle bin |
| `com.zifiq.history` | waybills, expenses, movement, violations, withdrawals |
| `com.zifiq.customers` | customers, distributors, dealers, contacts, balances |
| `com.zifiq.menu` | dealer registration and point of sale |
| `com.zifiq.invoicing` | invoice generation, delivery, status, history |
| `com.zifiq.barcode` | barcode and QR scan/label workflows |
| `com.zifiq.ai` | assistant, forecasting, optimization, anomaly detection, OCR |
| `com.zifiq.settings` | company, factories, suppliers, units, categories, manufacturers |

Keep reports and history queries within their owning business module unless they become an independent bounded context.

See `docs/` for the exhaustive, per-module design reference (schema, endpoints, permission codes, business rules traced to source): `docs/00-platform-foundation.md`, `docs/01-user-tenant-access-control.md`, `docs/02-factory.md`, with one `docs/NN-<module>.md` added per module as it's built.

## Run locally

Requirements: JDK 17+, Gradle 8.10+, and Docker. Connect the application with a non-superuser PostgreSQL role; superusers bypass RLS regardless of table policies.

1. Start PostgreSQL with `docker compose up -d postgres`; the init script creates the non-superuser `zifiq_app` role used by the application.
2. Optionally set `JWT_SECRET` — ZifIQ issues and verifies its own HS256 JWTs locally (see `platform/security/SecurityConfiguration.kt`); there is no external identity provider in this phase. A dev default is baked in if unset.
3. Run `gradle bootRun` from this directory.
4. Open `/swagger-ui.html`; OpenAPI JSON is at `/v3/api-docs`.

Database and JWT settings are environment-overridable in `src/main/resources/application.yml`. Flyway installs the platform schema and enables RLS policies at startup. Persistence is via Exposed (`org.jetbrains.exposed`), not JPA/Hibernate — see `docs/00-platform-foundation.md` §3.
