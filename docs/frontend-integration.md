# ZifIQ Backend — Frontend Integration Guide

Everything needed to start wiring the frontend against the live dev API.

## Base URL

```
https://d3t9f2p14df2uo.cloudfront.net
```

HTTPS through AWS CloudFront in front of the dev EC2 box (no custom domain yet). Use this from the deployed frontend: browsers block calls from an HTTPS page to the plain-HTTP address `http://16.192.105.205:8080`, which still works for local tools (curl, Postman).

CORS allows `https://zifiq.vercel.app` and any `http://localhost:*` / `http://127.0.0.1:*` dev server. Another deployed origin (e.g. a Vercel preview URL) must be added on the backend (`CORS_ALLOWED_ORIGINS` in `compose.yaml`) — exact origins only, since requests may carry credentials. Allowed request headers: `Authorization`, `Content-Type`.

## API Docs (Swagger / OpenAPI)

- **Swagger UI** (interactive, browsable): `https://d3t9f2p14df2uo.cloudfront.net/swagger-ui.html`
- **Raw OpenAPI JSON** (for codegen, Postman import, etc.): `https://d3t9f2p14df2uo.cloudfront.net/v3/api-docs`

## Postman collection

The fastest way to see every endpoint with real request/response examples is the repo's own Postman collection, already verified against this exact deployment:

- `backend/postman/ZifIQ-API.postman_collection.json`
- `backend/postman/ZifIQ-Local.postman_environment.json` — update the `baseUrl` variable in this environment from `http://localhost:8080` to `http://16.192.105.205:8080` before running against the dev server.

## Test tenant (already registered on the dev server)

This tenant already exists on the dev box — log in with it directly, don't re-register (re-running `register-tenant` with this exact email will fail with `409 USER_EXISTS`).

```json
{
  "companyName": "Apex Industries",
  "adminEmail": "admin-962ed412-ca84-43cc-809b-4e6bec156dbe@apex.com",
  "password": "password123"
}
```

Admin display name: `Alice Admin`

## Getting an access token

```
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "admin-962ed412-ca84-43cc-809b-4e6bec156dbe@apex.com",
  "password": "password123"
}
```

Response:

```json
{
  "data": {
    "accessToken": "...",
    "refreshToken": "...",
    "tokenType": "Bearer",
    "expiresInSeconds": 3600,
    "activeTenantId": "...",
    "userId": "..."
  }
}
```

Every other endpoint needs the access token on every request:

```
Authorization: Bearer <accessToken>
```

Access tokens expire after 1 hour (3600s). When one expires, get a new pair via:

```
POST /api/v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "..." }
```

Refresh tokens are single-use — the old one is revoked the moment this succeeds, so always store the new `refreshToken` the response gives back.

## Response shape (every endpoint, no exceptions)

**Success** — the payload is always under `data`:

```json
{ "data": { "...": "..." } }
```

For list endpoints, `data` is an array directly (`{ "data": [...] }`), not a nested `items`/`results` field.

**Error** — same shape every time, never a bare error string or a different structure per endpoint:

```json
{
  "error": {
    "code": "SOME_ERROR_CODE",
    "message": "Human-readable description",
    "details": {}
  }
}
```

`code` is a fixed enum value (e.g. `INVALID_CREDENTIALS`, `VALIDATION_FAILED`, `STOCK_ITEM_NOT_FOUND`) — safe to switch/match on in frontend error handling, it will never change wording under you. `details` is sometimes empty, sometimes carries extra structured context (e.g. which fields failed validation).

## Lists, search and filters (Customers is the first module using them)

Every paged list takes `q`, simple filters as query parameters, `page` (0-based), `size` (≤ 200), `sort=field,asc|desc`, `facets=a,b`, `highlight=true`. The response `data` is `{ items, page, size, totalItems, totalPages, totalIsEstimate, facets, summary, meta: { ignoredQualifiers, suggestions, matches } }`.

- `GET <list>/filters` describes every filter (type, operators, options, lookup endpoint, sortable, facetable) — build filter UIs from it instead of hard-coding.
- `POST <list>/search` takes `{ q, filter: { all|any|not: [...] }, params, sort: [{field, dir}], page, size, facets, highlight }` for advanced filters.
- `q` understands `"exact phrase"`, `-exclude`, `OR`, `field:value` qualifiers (`type:dealer status:watch`), references (`DLR-42` or bare `42`) and phone numbers in any format.
- An unknown filter name or value is a `400 INVALID_FILTER` with the allowed values in `details` — never a silently unfiltered list.
- With `highlight=true`, `meta.matches[rowId]` holds HTML-escaped snippets with `<mark>` around the matched words, safe to render as HTML.
- Header search box: `GET /api/v1/search?q=` returns hits grouped by type with a `route` for each; an exact reference returns just that record.

Customers endpoints live under `/api/v1/customers` (register, list/search, stats, lookup picker, ledger, wallet adjustments, reclassifications, dealer categories, correspondents, `/me` self-service) — see Swagger tags "Customers*" and the Postman folder "12 - Customers".

## File uploads

`POST /api/v1/files` as `multipart/form-data` with `file` and `purpose` (`GET /api/v1/files/purposes` lists purposes with accepted types and size limits). The response is the file record; keep its `id`. To download, call `GET /api/v1/files/{id}/download` and open the returned `url` (valid for a few minutes) — the browser fetches it straight from storage. Errors: `FILE_TYPE_NOT_ALLOWED` (checked from the file's content, not its name), `FILE_TOO_LARGE`, `STORAGE_NOT_CONFIGURED` (server has no storage yet).

## Recent API changes

- `GET /api/v1/inventory/quantity-adjustments` is now **paged** (`data.items`, `data.summary` with `qtyAdded`/`qtyRemoved`/`netEffect`) and searchable, like the other lists. Rows carry product code/name, location names, mode and before/after quantities.
- Stock transfers: dispatch accepts `driverId`, `transferDate` and per-line `fromLocationId`/`batchNo`; receiving needs accepted + rejected to equal what's outstanding, `rejectionNote` for rejections, and returns rejected goods to the source. Transfer rows now include location names, driver, dates, per-line outstanding quantities and `waybillUrl` (a PDF).
- New: Showroom module under `/api/v1/showroom` — showrooms and storage areas (`/showrooms`), direct entries, outsource receipts, area moves, returns (`/returns`) and reports (`/reports/comprehensive-stock`, `/reports/transfers`). The old mock `/api/v1/showroom/stock`, `/stock/direct`, `/transfers` and `/vat-report` endpoints are gone (transfers are `/api/v1/inventory/stock/transfers`).
- New: drivers (`/api/v1/inventory/drivers`), price list (`/api/v1/inventory/price-list`), group stock/bin cards (`/api/v1/inventory/stock/group-card`, `group-bin-card`), documents registry (`/api/v1/documents`), file uploads (`/api/v1/files`).
- Finance & Accounting, slice 1 (backend `docs/implemented/07-finance-accounting.md` §12, Postman folder "17 - Finance & Accounting"): bank accounts (`/api/v1/account/bank-accounts`, with `/{id}/manual-entries` and the paged `/{id}/statement`), cash accounts (`/api/v1/account/cash-accounts`, with `/{id}/cash-counts`), the paged Cash Inflow/Outflow Statement (`/api/v1/account/cash-flow`), expense categories (`/api/v1/account/expense-categories`) and exchange rates (`/api/v1/account/exchange-rates`, `/publish`, `/history`). **Removed:** the mock `GET /api/v1/account/cashflow`, `GET /api/v1/account/bank-statement/{accountNumber}`; `POST /api/v1/account/bank-accounts` now takes the real register body. The petty-cash and operating-cost endpoints are still mocks until slice 2.
- Finance & Accounting, slice 2 (doc 07 §13): petty cash (`/api/v1/account/petty-cash/fundings`, `/payments`, `/payments/history`, `/statement`, `/allocations` with `/{id}/accept|reject`), running costs (`/api/v1/account/running-costs` — drafts, `/{id}/submit|approve|reject|reverse`, `DELETE` drafts), funds awaiting (`/api/v1/account/fund-disbursements`, `/{id}/confirm|reject`) and cash lodgements (`/api/v1/account/cash-lodgements`, `/{id}/confirm|reject`). **Removed:** the mock `POST /api/v1/account/petty-cash/top-up`, `POST /api/v1/account/operating-costs` and `GET/POST /api/v1/process/exchange-rates` (use `/api/v1/account/exchange-rates`). `GET /api/v1/account/petty-cash/statement` is now real, with a new response shape.
- Finance & Accounting, slice 3 (doc 07 §14): receipts and the POS / Fund Transfer confirmation queue (`/api/v1/account/receipts` with `?confirmationStatus=PENDING`, `/confirmation-stats`, `/{id}/approve|decline|void`, `/{id}/receipt.pdf`), wallet and deposit funding (`/api/v1/account/wallet-fundings`), withdrawals and deposit refunds (`/api/v1/account/wallet-withdrawals`, `/{id}/audit-status`, `/{id}/reverse`), Tenderable (`/api/v1/account/tenderable/payable|receipts|wallet-funds|general-report`), other income (`/api/v1/account/other-income`) and VAT per location (`/api/v1/account/vat-control/locations`, `POST /{locationId}`, `/{locationId}/history`). **OTP:** `POST /api/v1/otp/challenges` now takes `context`; a wallet-withdrawal OTP must carry `targetReference` = the customer id and `context.amount` = the withdrawal amount.
- Settings is real (backend `docs/implemented/06-settings.md`, Postman folder "05 - Settings"). **Breaking:** `GET/PUT /api/v1/settings/company` now uses the company-profile shape (`tradingName`, `legalName`, `tin`, `phone`, `streetAddress`, `stateCode`/`lgaCode`, `documentFooter`, … plus `hasLogo` and `nrsReadiness`); the old mock fields (`companyName`, `primaryEmail`, `primaryPhone`, `taxRegistrationNumber`, `headOfficeAddress`) are gone. New: logo (`PUT` multipart `file` / `GET` image / `DELETE` at `/api/v1/settings/company/logo`), onboarding (`GET /api/v1/settings/onboarding`, `POST …/complete` with `skippedSteps`), custom fields (`/api/v1/settings/custom-fields`), settings search (`GET /api/v1/settings/search?q=`, also in the header search as type `setting`). Module settings groups now carry `editable`, and the list takes `q`, `module`, `type`, `modified`.
- `GET /api/v1/inventory/stock-items` rows now include `quantityOnHand`: the stock at `locationId` (including its storage areas), or across every location you can see when no `locationId` is given. Registering an item or changing its status returns it too.
- Electronic Invoicing, slice 1 (backend `docs/implemented/08-electronic-invoicing.md` §13, Postman folder "18 - Electronic Invoicing"): invoice history (`/api/v1/invoicing/invoices`, with the derived statuses `PARTIALLY_PAID`, `PAID` and `OVERDUE` as filters; `POST /search`, `/filters`, `/voided`, `/lookup`, `/lookup-source?ref=`), detail (`/{id}`), PDF (`/{id}/invoice.pdf`), drafts (`PUT /{id}`, `DELETE /{id}`, `POST /{id}/issue`), void and void-and-reissue (`POST /{id}/void`), and NRS classification on stock items (`GET`/`PATCH /api/v1/inventory/stock-items/{id}/classification`). Until Sales & POS exists, create invoices with `POST /api/v1/invoicing/sandbox/invoices`; it is temporary and goes away when POS ships. **Removed:** the mock `POST /api/v1/invoicing/generate`, `POST /api/v1/invoicing/deliver` and `GET /api/v1/invoicing/history`. The NRS reference lists (`/api/v1/invoicing/reference/*`) now need `invoicing.invoice.read` or `accounting.vat.manage`.
- Electronic Invoicing, slice 2 (doc 08 §14): payments (`POST /api/v1/invoicing/invoices/{id}/payments`, `GET …/payments`, `POST …/payments/{paymentId}/reverse`), credit notes (`POST /api/v1/invoicing/invoices/{id}/credit-notes`; `/api/v1/invoicing/credit-notes` list, detail and `/{id}/credit-note.pdf`), e-mail (`POST /api/v1/invoicing/invoices/{id}/deliveries`, `GET …/deliveries`, `/api/v1/invoicing/deliveries`), bulk export (`GET /api/v1/invoicing/invoices/export.zip`), receivables (`/api/v1/invoicing/receivables`), the Debtors List (`/api/v1/invoicing/debtors`, `/debtors/{customerId}`) and the NRS preview (`GET /api/v1/invoicing/invoices/{id}/nrs-preview`). Invoice detail now fills `linked.payments`, `linked.creditNotes` and `totals.pendingConfirmation`, and `delivery` carries `code` (SENT, FAILED, NO_EMAIL, NOT_SENT) and `attempts`. History rows' `delivery` and the `delivery` filter reflect real e-mails. **Removed:** the mock `GET /api/v1/menu/debtors`.
- **Breaking — these lists are now paged** (`?page=&size=`, response `data.items` / `page` / `size` / `totalItems` / `totalPages`, as on every other paged list): `GET /api/v1/inventory/stock-items` (also takes `q`: code prefix or name), `/inventory/stock/receipts`, `/inventory/stock/card/{id}`, `/inventory/stock/bin-card/{id}`, `/inventory/stock/comprehensive-report` (totals in `summary`), `/inventory/stock/movement-report`, `/inventory/stock/re-order-report`, `/inventory/stock/transfers`, `/inventory/drivers`, `/inventory/stock-items/{id}/prices`, `/factory/recipes`, `/factory/production/runs` (`from`/`to` now applied; newest first), `/factory/special-production/runs`, `/factory/trades`. The group stock/bin cards keep `header` and make `rows` a page; `header.members` lists the first 20 and `header.memberCount` the total. Default page size is 50 (max 200); a page that starts past the first 10,000 rows answers `400 PAGE_TOO_DEEP` — narrow the filters. The wallet-funds tenderable report's `method` now uses the same labels as everywhere else (`Cash`, `POS (card)`).
- **Breaking — two more lists are paged** (`?page=&size=`, response `data.items` / `page` / `size` / `totalItems` / `totalPages`): `GET /api/v1/customers/correspondents` (filters and sort unchanged), and the `transactions` of `GET /api/v1/invoicing/debtors/{customerId}` (now `transactions.items`, newest first). The Debtors List (`GET /api/v1/invoicing/debtors`) keeps its shape; with `q` and no `sort` it now ranks best matches first.
- **Breaking — more lists are paged** (`?page=&size=`, response `data.items` / `page` / `size` / `totalItems` / `totalPages`): `GET /api/v1/tenants/members` (also takes `q` — name or e-mail — and `status`), `GET /api/v1/account/tenderable/payable` (newest day first), and the `ledger` of `GET /api/v1/account/petty-cash/statement` (now `ledger.items`; the statement block still covers the whole period). Bank statement, cash flow and petty cash balances are unchanged in value; they are now computed with one window sum per request.
- **New error codes:** `405 METHOD_NOT_ALLOWED` (details `supported`: the methods the route takes) and `415 UNSUPPORTED_MEDIA_TYPE` (details `supported`: the content types it takes — e.g. an upload sent without a multipart body). Both used to come back as `500 INTERNAL_ERROR`; a multipart body the server can't read is now `400 VALIDATION_FAILED`.
- **Breaking — company logo now lives in S3:** `PUT /api/v1/settings/company/logo` accepts PNG, JPEG, WebP, AVIF and SVG (≤ 1 MB; unsafe SVG → `400 FILE_UNSAFE`, other types → `400 FILE_TYPE_NOT_ALLOWED`). The company profile returns `logoUrl` (use it directly as `<img src>`; it expires after an hour, at `logoUrlExpiresAt` — re-read the profile), `logoContentType` and `logoInDocuments` (false for AVIF/WebP, which PDFs can't draw — show a hint suggesting PNG/SVG for invoices). `GET /api/v1/settings/company/logo` is removed. Also: `GET /api/v1/factory/production/total-cost` answers `404 RECIPE_NOT_FOUND` for an id that isn't the tenant's recipe (was 500).

## Notes

- This is a **dev** server (small EC2 instance behind CloudFront for HTTPS, redeploys whenever `main` gets pushed) — don't treat any data stored here as durable long-term.
