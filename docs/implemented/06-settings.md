# 06 — Settings Module

Status: **implemented** (V10, 2026-10-09) — differences from the original design are recorded inline and in §11
Owner package: `com.zifiq.settings` (substrate module — exposes `com.zifiq.settings.spi`)
Depends on: `platform`, `common` only.
Depended on by: `invoicing` (issuer party on every invoice), `cashier` (receipt/pro-forma/waybill headers), `reports`, `dashboard`, `ai` (briefing header).
Migration: `V10__settings_module.sql`
Replaces: the mock `SettingsService.getCompanySettings` / `updateCompanySettings` (the last mock methods in this package — Factory Setup and Locations already moved out to `factory`/`platform`).

---

## 1. Scope

| Feature | Source | Status after this doc |
|---|---|---|
| Company profile (name, address, phone, e-mail, tax identifiers, branding) used "across every document, report, and invoice" | `settings/CompanySettings.jsx`; `Onboarding.jsx` step 1; PRD §5.10 ("company details/branding") | real |
| Company logo | PRD §5.10 "company branding" on invoices/PDFs | real |
| NRS issuer identifiers (TIN, NRS `business_id`, address with state/LGA codes) reserved for e-invoicing | NRS Invoice Schema `accounting_supplier_party`; PRD §9 "reserve regulatory identifier fields now" | real (fields stored; not transmitted this phase) |
| Onboarding progress (company → branch → factory → done) | `Onboarding.jsx` | real |
| Module settings screen (per-module configurable thresholds) | sketch "Settings › Module Settings"; doc 03 §10 | real (screen over platform storage) |
| Custom field builder (per entity) | sketch "Settings › Custom Fields — Interactive Field Builder scoped per module"; `StockRegistration.jsx` "Add custom field" | real (screen over the platform table from doc 03 §12.2) |
| Branches | `Branches.jsx`, Overview "Manage branches" | **not here** — a branch is a showroom; the screen calls doc 05's Showroom Register |
| Exchange rate | sketch lists it under Settings › Company **and** Finance › Financial Controls | **not here** — doc 07 (transactions snapshot it; it's a financial control) |
| Workflow defaults (retry policy, timeouts, vault) | sketch "Settings › Workflow Defaults" | keys registered by doc 15 and shown on this module's Module Settings screen; secrets ("vault") stored by doc 15 |
| Process guide content, Error Handler, Recycle Bin | sketch lists them under Settings | Error Handler & Recycle Bin → doc 10; "Process Guide" as help content has no specification anywhere and is out of scope |

---

## 2. Schema — migration `V10__settings_module.sql`

```sql
create table company_profiles (
    tenant_id uuid primary key references tenants(id),
    trading_name varchar(200) not null,                -- "Company name" on CompanySettings.jsx; printed on documents
    legal_name varchar(200),                           -- registered name if different (e.g. "... Limited")
    registration_number varchar(40),                   -- CAC RC/BN number
    tin varchar(30),                                   -- Tax Identification Number (NRS accounting_supplier_party.tin)
    vat_registration_number varchar(60),               -- company-level default; each location may override (locations.vat_registration_number)
    nrs_business_id uuid,                              -- NRS-issued business UUID; reserved, null until the tenant registers on the NRS e-invoicing platform
    business_description varchar(200),                -- NRS party.business_description
    email varchar(320),
    phone varchar(40) not null,
    phone_e164 varchar(20),
    website varchar(200),
    street_address varchar(300) not null,
    city varchar(120),
    postal_code varchar(20),                           -- NRS postal_address.postal_zone
    state_code varchar(5) references nigeria_states(code),
    lga_code varchar(10) references nigeria_lgas(code),
    country_code varchar(2) not null default 'NG',
    logo bytea,                                        -- ≤ 512 KB PNG/JPEG
    logo_content_type varchar(20) check (logo_content_type is null or logo_content_type in ('image/png','image/jpeg')),
    logo_updated_at timestamptz,
    document_footer varchar(500),                      -- printed at the foot of invoices/receipts/waybills
    financial_year_start_month int not null default 1 check (financial_year_start_month between 1 and 12),
    onboarding_completed_at timestamptz,
    onboarding_skipped_steps jsonb not null default '[]'::jsonb,   -- ["BRANCH","FACTORY"] when skipped
    updated_by_user_id uuid references app_users(id),
    updated_at timestamptz not null default now()
);
-- standard RLS policy on tenant_id

-- backfill one row per existing tenant from the tenant's name, so every tenant has a profile
insert into company_profiles (tenant_id, trading_name, phone, street_address)
select id, name, '', '' from tenants
on conflict do nothing;
```

`AuthService.registerTenant` / `TenantService.createChild` create the row through the `TenantProvisioner` hook (`trading_name` = the tenant's name, other fields empty), in the same transaction that seeds roles.

Why the logo is a `bytea` column even though S3 file storage now exists (doc 03 §8.4): a logo is one small file per tenant; keeping it in Postgres keeps it under RLS and in the database backup, and lets every PDF render it without a network call or a dependency on storage being configured. Two extra checks back the API rules: `(logo is null) = (logo_content_type is null)` and `octet_length(logo) <= 524288`. If it ever needs to move to S3, it moves behind the same endpoints.

The backfill runs **before** RLS is enabled on the table, so it reaches every tenant whatever role runs the migration. New tenants get their row from `CompanyProfileService`, a `TenantProvisioner` (platform calls every provisioner inside the tenant-creation transaction) — platform itself never references this table.

---

## 3. Permission codes

Reused: `settings.manage` (V3 — edit company profile, logo, onboarding), `settings.module-settings.manage`, `settings.custom-fields.manage` (V7, doc 03 §16). Reading the company profile and custom-field definitions needs only an authenticated tenant member (every screen shows the company name; nothing sensitive is in it). Reading module settings needs any `*.manage` permission of the owning module **or** `settings.module-settings.manage`.

---

## 4. Endpoints

### 4.1 Company profile — `/api/v1/settings/company`

**`GET`** — any authenticated member. Response:
```json
{ "data": {
  "tradingName": "Zif Manufacturing Ltd", "legalName": "Zif Manufacturing Limited",
  "registrationNumber": "RC-1849302", "tin": "33467982-0001", "vatRegistrationNumber": "NG-VAT-4471029",
  "nrsBusinessId": null, "businessDescription": "Beverage manufacturing and distribution",
  "email": "info@zif.ng", "phone": "+234 1 270 0000", "website": "https://zif.ng",
  "streetAddress": "32 Owonikoko Street", "city": "Ikeja", "postalCode": "100271",
  "stateCode": "NG-LA", "stateName": "Lagos", "lgaCode": null, "lgaName": null, "countryCode": "NG",
  "hasLogo": true, "logoUpdatedAt": "2026-10-08T10:01:12Z",
  "documentFooter": "Goods sold in good condition are not returnable except under our returns policy.",
  "financialYearStartMonth": 1,
  "nrsReadiness": { "ready": false, "missing": ["nrsBusinessId", "lgaCode"] },
  "updatedBy": "Alice Admin", "updatedAt": "2026-10-08T10:01:12Z"
} }
```
`nrsReadiness` lists which NRS issuer fields are still empty (`tin`, `email`, `streetAddress`, `stateCode`, `lgaCode`, `nrsBusinessId`, `businessDescription`) — informational; nothing is blocked by it in this phase (PRD §5.10: no NRS transmission yet).

**`PUT`** — perm `settings.manage`. Body: every field above except `hasLogo`, `logoUpdatedAt`, `nrsReadiness`, `updatedBy`, `updatedAt`. Validation:

| Field | Rule |
|---|---|
| `tradingName` | required, 2–200 |
| `phone` | required; normalised with `common.phone.PhoneNumbers` (the doc 04 §4.8 algorithm, moved to `common` so Customers and Settings share it) |
| `streetAddress` | required, ≤300 |
| `email` | valid e-mail |
| `tin` | 6–30 chars of `A-Z 0-9 -` → else `400 VALIDATION_FAILED` |
| `nrsBusinessId` | UUID |
| `stateCode` / `lgaCode` | as doc 04 (`INVALID_STATE_CODE`, `INVALID_LGA_CODE`; LGA must belong to the state) |
| `website` | `https://` or `http://` URL |
| `financialYearStartMonth` | 1–12 |
| `documentFooter` | ≤500, plain text (rendered escaped in PDFs) |

Emits `settings.company.updated` (payload: changed field names only). "Changes apply immediately across the console" — documents rendered after the update use the new values; documents already issued keep the issuer snapshot they were issued with (doc 08 snapshots the issuer onto each invoice).

### 4.2 Logo

- `PUT /api/v1/settings/company/logo` — perm `settings.manage`. `multipart/form-data`, part `file`. Accepted: PNG or JPEG, ≤ 512 KB; type verified from the file's magic bytes, not the declared content type → else `400 INVALID_LOGO`. SVG is rejected (scriptable). Emits `settings.company.logo-updated`.
- `GET /api/v1/settings/company/logo` — any member. Streams the image with its content type and `Cache-Control: private, max-age=300`; `404 LOGO_NOT_SET` if none.
- `DELETE /api/v1/settings/company/logo` — perm `settings.manage`.

### 4.3 Onboarding — `/api/v1/settings/onboarding`

- `GET` — any member. `{ completed: bool, completedAt, steps: [ { key: "COMPANY", done: bool }, { key: "BRANCH", done: bool, skipped: bool }, { key: "FACTORY", done: bool, skipped: bool } ] }`. `COMPANY` is done when `tradingName`, `phone`, `streetAddress` are set; `BRANCH` when the tenant has ≥1 showroom; `FACTORY` when it has ≥1 factory (both read from `platform` `locations`, so Settings needs no dependency on Showroom/Factory).
- `POST /complete` — perm `settings.manage`. Body `{ skippedSteps: ["BRANCH"|"FACTORY"] }`. Sets `onboarding_completed_at`. **Every unfinished step must be listed in `skippedSteps`** → else `409 ONBOARDING_INCOMPLETE` (details `missing`); the company step can't be skipped (`400 VALIDATION_FAILED` if listed) and so must be done. Calling it again keeps the first `completedAt` and only updates which steps were skipped; `settings.onboarding.completed` is recorded once. Each step in the response carries `skipped` (true only for an unfinished step that was skipped). The wizard's step 2/3 forms call `POST /api/v1/showroom/showrooms` (doc 05) and `POST /api/v1/factory/setup/factories` (shipped) directly.

### 4.4 Module settings — `/api/v1/settings/module-settings`

- `GET` — returns every registered key the caller may see, grouped by owning module:
```json
{ "data": [ { "module": "inventory", "settings": [
  { "key": "inventory.adjustment.otpThresholdQty", "label": "Adjustment quantity above which an OTP is required",
    "type": "DECIMAL", "value": 50, "default": 50, "min": 1, "max": 100000, "options": null,
    "updatedBy": null, "updatedAt": null } ] } ] }
```
  Visibility: holders of `settings.module-settings.manage` see all; others see only modules where they hold a `<module>.….manage` permission, read-only. Each group carries `editable` (true only for holders of `settings.module-settings.manage`). Query parameters: `q` (every word must appear in the key, label or module name, accent- and case-insensitive), `module`, `type`, `modified` (true = changed from the default). Settings are declared in code, so this list is filtered in memory rather than through `FilterSpec`; there is no `/filters` endpoint for it.
- `PUT /{key}` — perm `settings.module-settings.manage`. Body `{ value }`. Unknown key → `400 SETTING_KEY_UNKNOWN`; wrong type / outside min-max / not in options → `400 SETTING_VALUE_INVALID` (details `{ key, reason }`). Emits `settings.module-setting.changed` (payload `{ key, from, to }` — settings hold no secrets; secret-typed settings, if any, live in doc 15's vault, never here).
- `DELETE /{key}` — reset to default.

### 4.5 Custom fields — `/api/v1/settings/custom-fields`

Over `custom_field_definitions` (doc 03 §12.2).
- `GET ?entityType=STOCK_ITEM|CUSTOMER|SUPPLIER&status=` — any member (forms need them). Row `{ id, entityType, fieldKey, label, dataType, options, required, status, sortOrder, usageCount, createdAt }` (`usageCount` = rows whose `custom_attributes` has the key). Also `GET /{id}`.
  *Usage without a module dependency:* each module that stores `custom_attributes` implements platform's `CustomAttributeUsage` (`counts()`, `countWithValue(key, value)`, `countMissing(key)`); Settings receives all of them. Today only Customers stores values (`CustomerAttributeUsage`); stock items get the column with product variants (doc 03 §12.2) and suppliers have none yet, so their counts are 0 and nothing blocks their edits.
- `POST` — perm `settings.custom-fields.manage`. Body `{ entityType, label (2–80), dataType, options? (SELECT only, 1–50 distinct values, ≤ 60 chars each, compared case-insensitively), required (default false), sortOrder? (default: last + 10) }`. `fieldKey` = slug of the label (accents dropped, non-alphanumerics → `_`, a leading digit gets `f_`), made unique per entity type by `_2`, `_3` … (keys of inactive fields stay reserved); immutable afterwards. Creating a **required** field is refused with `409 FIELD_HAS_BLANKS` when existing records would be left without a value. Duplicate label (case-insensitive) for the entity → `409 DUPLICATE_NAME` ("Add custom field" in `StockRegistration.jsx` reuses an existing field instead — the frontend can match on the 409's details `{ existingId }`).
- `PUT /{id}` — label, options (only *adding* options is allowed once in use → removing an in-use option `409 OPTION_IN_USE`), required (turning `required` on is allowed only when every existing active row already has a value → else `409 FIELD_HAS_BLANKS` with the count), sortOrder. `dataType` and `entityType` are immutable.
- `PUT /{id}/status` — `INACTIVE` hides the field from forms; existing values are kept and still displayed read-only.
Emits `settings.custom-field.created` / `.updated` / `.status-changed`.

---

## 5. Exposed to other modules — `com.zifiq.settings.spi`

```kotlin
interface CompanyProfileProvider {
    fun get(): CompanyProfile     // runs in the caller's transaction (doc 03 §2.4)
}
data class CompanyProfile(
    val tradingName: String, val legalName: String?, val registrationNumber: String?,
    val tin: String?, val vatRegistrationNumber: String?, val nrsBusinessId: UUID?, val businessDescription: String?,
    val email: String?, val phone: String, val website: String?,
    val streetAddress: String, val city: String?, val postalCode: String?,
    val stateCode: String?, val stateName: String?, val lgaCode: String?, val countryCode: String,
    val logoPng: ByteArray?, val logoContentType: String?, val documentFooter: String?)
```
Invoicing (doc 08) snapshots this onto each invoice at issue time; Cashier (doc 09) uses it for receipts, pro-formas and customer waybills.

---

## 6. Business rules

| # | Rule | Source |
|---|---|---|
| ST1 | Every tenant always has exactly one company profile | derived — documents can't render without an issuer |
| ST2 | Profile changes apply to new documents immediately and never alter already-issued documents | `CompanySettings.jsx` "Changes apply immediately"; PRD rule 14 (issued invoices immutable) |
| ST3 | Regulatory issuer fields are reserved now but not transmitted in this phase | PRD §5.10 scope, §9 risk mitigation |
| ST4 | Custom-field keys and types never change once created; values are never silently dropped | derived — `custom_attributes` would otherwise orphan data |

## 7. Errors added

`INVALID_LOGO` 400 · `LOGO_NOT_SET` 404 · `ONBOARDING_INCOMPLETE` 409 · `OPTION_IN_USE` 409 · `FIELD_HAS_BLANKS` 409 · `CUSTOM_FIELD_NOT_FOUND` 404.

## 8. Event catalog

`settings.company.updated`, `settings.company.logo-updated`, `settings.onboarding.completed`, `settings.module-setting.changed`, `settings.custom-field.created` / `.updated` / `.status-changed`. None notify anyone (the actor is the only person who needs to know).

## 9. Search & filters

Settings lists are small and bounded, so they stay unpaged (doc 03 §5.1), but they still use the shared `SearchSpec`/`FilterSpec`, so `q`, qualifiers and `/filters` metadata behave the same as everywhere else.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Sort |
|---|---|---|---|
| `GET /settings/module-settings` · `settings.module-settings` | `key` CODE, `label` TRIGRAM (accent-insensitive; the French labels are searched too), owning-module name TRIGRAM | `module` multi, `type` (`DECIMAL`/`INT`/`BOOLEAN`/`ENUM`/`STRING`/`ARRAY`), `modified` (value ≠ default), `updatedBy`, `updatedAt` range | `module, label`; `updatedAt` |
| `GET /settings/custom-fields` · `settings.custom-fields` | `field_key` CODE, `label` TRIGRAM, SELECT `options` TRIGRAM | `entityType` multi, `dataType` multi, `status`, `required`, `inUse` (`usageCount > 0`) | `sortOrder`; `label`, `usageCount` |

**Settings search box**: `GET /api/v1/settings/search?q=` searches module-setting labels and keys, custom-field labels and keys, and the fixed list of settings screens (Company profile, Company logo, Onboarding, Module settings, Custom fields, VAT Control). It returns up to 20 `{ type: screen|module-setting|custom-field, label, detail, route }` so a user can type "otp" and jump to the adjustment-threshold setting; only what the caller may see is returned (module settings by the visibility rule above, VAT Control needs `accounting.read`). Routes: `/settings/company`, `/settings/company/logo`, `/settings/onboarding`, `/settings/module-settings?key=<key>`, `/settings/custom-fields/<id>`, `/settings/vat-control`. It is also registered as the global-search provider `setting`. Workflow Defaults joins the list when doc 15 is built.

**Custom-field filtering in other modules**: every entity with custom fields (stock items, customers, suppliers) exposes them as filters `attr.<field_key>` typed by `dataType` (TEXT → `contains`/`eq`, NUMBER/DATE → ranges, BOOLEAN, SELECT → `in`). The fields appear automatically in that list's `/filters` metadata, so a field created here becomes filterable everywhere without code changes. Inactive fields stay filterable (historic data) but are marked `inactive: true`.

**Indexes (V10)**: none beyond the primary keys. Lists are a few hundred rows, filtered in SQL with the btree on `(tenant_id, entity_type, status)` for `custom_field_definitions`. The `custom_attributes` GIN indexes live on the owning tables (doc 03 §12.2, doc 04 §9).

## 10. Verification

- New tenant registration → a `company_profiles` row exists with the registration's company name.
- Upload a renamed `.svg` as `logo.png` → `400 INVALID_LOGO` (magic-byte check).
- Change the trading name, render an invoice issued before the change (doc 08) → old name; a new invoice → new name.
- Module setting out of range → `400 SETTING_VALUE_INVALID`; inventory's OTP threshold change takes effect on the next adjustment without restart.
- Custom field: create "Color" (SELECT), use it on a stock item, try to remove the used option → `409 OPTION_IN_USE`.
- Newman folder "05 - Settings".

## 11. Implementation notes (V10)

- **Logo in file storage (V14_7, supersedes the `bytea` design above).** The logo is a stored file (S3, doc 03 §8.4) under purpose `settings.company-logo`: PNG, JPEG, WebP, AVIF or SVG, ≤ 1 MB, recognised by content. An SVG is accepted only when it has no script, `on*` handler, `foreignObject`/`iframe`/`embed`/`object`, DOCTYPE or entity, `javascript:` value, or link/CSS `url()` to anything outside the file (`platform.files.SvgSafety`, which every SVG upload goes through) — otherwise `400 FILE_UNSAFE`. `company_profiles.logo_file_id` points at the file; a replaced or removed logo's file is marked deleted. The profile returns `logoUrl` (a presigned S3 link with `Content-Disposition: inline`, valid 1 h — read the profile again for a fresh one), `logoUrlExpiresAt`, `logoContentType` and `logoInDocuments`. `GET /company/logo` (bytes) is gone; `PUT` and `DELETE` stay. Errors are the file service's: `FILE_TYPE_NOT_ALLOWED`, `FILE_TOO_LARGE`, `FILE_UNSAFE`, `STORAGE_NOT_CONFIGURED` (`INVALID_LOGO` / `LOGO_NOT_SET` are retired). Logos stored in the old column were not carried over.
- **Logos on documents.** PDFs embed the logo read from storage (cached per tenant and file in a 32 MB byte-bounded cache; a stored file never changes). PNG and JPEG are embedded as images, SVG is drawn as vectors (openhtmltopdf-svg-support, Batik 1.19). AVIF and WebP can't be drawn by the PDF engine: `logoInDocuments` is false and documents print the trading name. If drawing a logo fails, the document is rendered without it (`PdfRenderer`) — a logo never stops an invoice.
- The spi carries `hasLogo` and `logoDataUri()` (loaded from storage only when a document renders), not the logo bytes.
- `PUT /company` replaces the whole profile: optional fields left out are cleared. `tin` is stored upper-case, `email` lower-case, `phone_e164` via `PhoneNumbers`. An LGA without a state is `400 INVALID_LGA_CODE`.
- `settings.company.updated` carries `{ fields: [changed field names] }` and is recorded only when something changed. Removing the logo records `settings.company.logo-updated` with `{ removed: true }`.
- `PermissionAuthorizer.permissions(userId)` (new) returns all of a user's permission codes; module-settings visibility uses it.
- Tests: `SettingsIntegrationTest` (7 tests against PostgreSQL with RLS).
