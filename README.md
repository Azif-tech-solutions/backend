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

1. Start PostgreSQL with `docker compose up -d postgres` (published on port 5434); the init script creates the non-superuser `zifiq_app` role used by the application.
2. Optionally set `JWT_SECRET` — ZifIQ issues and verifies its own HS256 JWTs locally (see `platform/security/SecurityConfiguration.kt`); there is no external identity provider in this phase. A dev default is baked in if unset.
3. Optional, for file uploads: start S3Mock (an S3-compatible server) with `docker compose -f compose.yaml -f compose.local-storage.yaml up -d s3mock`. Without it the app runs normally and file endpoints answer `503 STORAGE_NOT_CONFIGURED`.
4. Run the app from this directory:
   ```sh
   DATABASE_URL=jdbc:postgresql://localhost:5434/zifiq ./gradlew bootRun
   # with file storage (step 3):
   DATABASE_URL=jdbc:postgresql://localhost:5434/zifiq ZIFIQ_STORAGE_BUCKET=zifiq-local      ZIFIQ_STORAGE_ENDPOINT=http://localhost:9090 AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local ./gradlew bootRun
   ```
   Or run the whole stack in Docker: `docker compose -f compose.yaml -f compose.local-storage.yaml up --build -d` (this is what CI does).
5. Open `/swagger-ui.html`; OpenAPI JSON is at `/v3/api-docs`.

Database, JWT and storage settings are environment-overridable in `src/main/resources/application.yml`. Flyway installs the platform schema and enables RLS policies at startup. Persistence is via Exposed (`org.jetbrains.exposed`), not JPA/Hibernate — see `docs/00-platform-foundation.md` §3.

## File storage (S3)

Uploaded files are stored in Amazon S3 (`docs/03-cross-module-conventions.md` §8.4). The server reads:

| Variable | Meaning |
|---|---|
| `ZIFIQ_STORAGE_BUCKET` | Bucket name. Empty = storage off (file endpoints return `503 STORAGE_NOT_CONFIGURED`). |
| `ZIFIQ_STORAGE_REGION` | Bucket region, default `eu-north-1` (same region as the dev EC2 instance). |
| `ZIFIQ_STORAGE_KEY_PREFIX` | Optional prefix so environments can share a bucket, e.g. `dev/`. |
| `ZIFIQ_STORAGE_ENDPOINT` | Only for S3-compatible servers (S3Mock locally); leave empty for AWS. |

**Credentials are never configured in the app.** On EC2 they come from the instance's IAM role through the
instance metadata service; locally S3Mock accepts any keys.

**Dev server setup (one-off, in the AWS console or CLI):**
1. Create a bucket in `eu-north-1` with **Block all public access** on, default encryption SSE-S3, and
   versioning on (optional but recommended).
2. Create an IAM role for EC2 with this policy (replace the bucket name) and attach it to the instance
   (EC2 > Instance > Actions > Security > Modify IAM role):
   ```json
   { "Version": "2012-10-17",
     "Statement": [ { "Effect": "Allow",
       "Action": ["s3:PutObject", "s3:GetObject"],
       "Resource": "arn:aws:s3:::YOUR-BUCKET/dev/*" } ] }
   ```
3. Let containers reach the instance metadata service: set the instance's metadata **hop limit to 2**
   (EC2 > Instance > Actions > Instance settings > Modify instance metadata options), since Docker adds a hop.
4. On the server, create `~/backend/.env` (deploys keep it) and restart:
   ```sh
   ZIFIQ_STORAGE_BUCKET=YOUR-BUCKET
   ZIFIQ_STORAGE_REGION=eu-north-1
   ZIFIQ_STORAGE_KEY_PREFIX=dev/
   ```
   `cd ~/backend && docker compose up -d`

## Deployment (dev server)

Every push to `main` runs `.github/workflows/ci.yml`. The `docker-build-and-scan` job builds the image once,
scans it with Trivy, and publishes it to GitHub Container Registry as
`ghcr.io/azif-tech-solutions/backend:<commit sha>`. Once every check is green, the `deploy` job:

1. rsyncs the repo to `~/backend` on the server (the server's own `.env` is never overwritten);
2. logs in to ghcr.io with the run's short-lived `GITHUB_TOKEN`, pulls that image, and logs out;
3. writes `ZIFIQ_IMAGE=<image>` into `~/backend/.env` and runs `docker compose up -d --no-build`;
4. fails the run unless `/actuator/health` reports `UP` within 5 minutes;
5. removes images older than a week and any build cache.

The server never compiles anything. **Rolling back** means re-running the `deploy` job of an earlier
successful run, or, on the server, setting `ZIFIQ_IMAGE` in `.env` to an earlier tag and running
`docker compose up -d --no-build`.

Repo secrets used: `EC2_HOST`, `EC2_USER`, `EC2_SSH_PRIVATE_KEY`. No registry credential is stored on the server.
