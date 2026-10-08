# ZifIQ Postman Collection

Covers only the modules that have real persistence and business logic today:
Auth, Tenants & Members, OTP, VAT Control, Settings, Factory Setup, Factory
Production, Factory Trading, Inventory Stock, Inventory Operations, the
NRS Reference Data lookups that lay the foundation for the Electronic
Invoicing module, and Customers & Distributors.

The remaining scaffolded modules (Account, AI, Barcode, Cashier,
History, Menu, Process Guide, Showroom, and Electronic Invoicing's own
generate/deliver/history endpoints) still return hardcoded mock data
server-side with no real tables behind them, so they're intentionally left
out of this collection. They'll get added here once each one is built out
for real, the same way Factory and Inventory were.

## Setup

1. Start Postgres and the app locally (see the main `README.md` /
   `compose.yaml` at the repo root).
2. Import both files into Postman:
   - `ZifIQ-API.postman_collection.json`
   - `ZifIQ-Local.postman_environment.json`
3. Select the **ZifIQ Local** environment (defaults `baseUrl` to
   `http://localhost:8080`).

## Running it

Run folders **01 through 12 in order**, either by hand or with the
Collection Runner. Each request's Tests script captures tokens and
created-entity IDs (tenant id, access token, factory id, stock item ids,
recipe id, costing id, etc.) into collection variables that later requests
reuse automatically — nothing needs to be copy-pasted between requests.

Two requests are deliberately order-sensitive and documented inline:
- **08 - Inventory Operations > Post Quantity Adjustment (above threshold,
  OTP-gated)** needs the **03 - OTP** folder (request → issue → verify) run
  first, or it returns `403 OTP_REQUIRED`.
- **10 - Factory Trading > Offer Trade** needs stock to actually exist at
  the *source* location, so run **07 - Inventory Stock** and
  **06 - Factory Setup > Create Second Factory** first.

**04 - VAT Control > Select Tenant VAT Categories** needs to run before any
**07 - Inventory Stock** registration request — a stock item's
`vatCategoryId` must already be in the tenant's active set, or registration
fails with `VAT_CATEGORY_NOT_ACTIVE`.

Every request also carries one saved example response showing the exact
`ApiEnvelope<T>` / `ApiErrorEnvelope` wire shape it returns.

## Re-running without resetting the database

**Register Tenant** generates a unique admin email every send
(`admin-{{$guid}}@apex.com`) and its Tests script captures whatever email
actually got registered into the `registeredEmail`/`registeredPassword`
collection variables. **Login**, **Forgot Password**, and **Change
Password** all read those variables back instead of a hardcoded literal, so
the whole 01 - Auth chain stays internally consistent run after run without
needing to edit anything by hand or wipe Postgres between passes.

Every request's Tests script only reads `response.data` inside an
`if (pm.response.code is 2xx)` guard, so a single failed/conflicting request
(e.g. hitting an endpoint a second time against data a previous run already
created) fails that request's own tests cleanly instead of throwing an
uncaught script error that aborts the rest of the Collection Runner.

Requests past 01 - Auth are not similarly randomized (e.g. Register Factory,
Register Supplier) — running the full collection twice in a row against the
same database will hit real uniqueness constraints on those. For a clean
repeat run, drop and recreate the local Postgres database (or
`docker compose down -v` if you're using the root `compose.yaml`) before
re-running past folder 01.

## Running it headless (Newman)

The whole collection can also be run end-to-end from the command line with
[Newman](https://www.npmjs.com/package/newman), Postman's CLI runner —
useful for a quick self-check against a fresh backend without opening the
Postman app:

```sh
npx newman run ZifIQ-API.postman_collection.json -e ZifIQ-Local.postman_environment.json
```

A clean run reports `114/114` requests passing, `0` failures. Several requests are
*deliberately* run against a state that makes them error — not bugs, but
real error paths demonstrated on purpose — so each gets its own
status+error-code assertion instead of the generic one every other request
uses, documented inline on the request itself:
- **01 - Auth > Reset Password** — asserts `400 INVALID_RESET_TOKEN`, since
  the collection uses a placeholder token (`RST-482913`); the real one
  lives in `notification_outbox`.
- **09 - Factory Production > Reject Costing** — asserts `409
  ALREADY_DECIDED`, since it deliberately runs against the same costing
  **Approve Costing** just approved, to demonstrate the mutual-exclusion
  check.
- **12 - Customers** — *Register Correspondent (unknown region)* `400
  INVALID_REGION`, *Register Dealer (duplicate name)* `409 DUPLICATE_NAME`,
  *Wallet Adjustment (overdraw)* `400 INSUFFICIENT_WALLET_BALANCE`,
  *Reverse Ledger Entry (again)* `409 ALREADY_REVERSED` and *Set Customer
  Status (REMOVED)* `409 INVALID_STATUS_TRANSITION` each demonstrate a
  business rule.

**12 - Customers** creates its own ACTIVE home location first (folder 06
deliberately leaves `{{factoryId}}` SUSPENDED) and captures `{{today}}` in
the tenant's default timezone for the immediate reclassification.

## Editing the collection

The collection is generated — don't hand-edit the JSON. Add or change
requests in `generate_collection.py` (one `ep(...)` call per request: method,
path, body, example response, capture scripts, and `expect_error` for
deliberate error paths), then run:

```sh
python postman/generate_collection.py
```

It rewrites `ZifIQ-API.postman_collection.json` and
`ZifIQ-Local.postman_environment.json`. The Dev environment
(`ZifIQ-Dev.postman_environment.json`, pointing at the dev server) is
maintained by hand. CI runs the collection with Newman against the Local
environment on every push, so run Newman locally before committing.
