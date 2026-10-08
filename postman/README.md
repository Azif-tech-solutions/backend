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
for real, the same way Factory, Inventory and Customers were.

## Setup

1. Import into Postman (File > Import):
   - `ZifIQ-API.postman_collection.json`
   - `ZifIQ-Local.postman_environment.json` — `baseUrl` = `http://localhost:8080`
   - `ZifIQ-Dev.postman_environment.json` — `baseUrl` = the dev server
     (`http://16.192.105.205:8080`), which always runs the latest `main`
2. Pick the environment in the top-right environment selector:
   **ZifIQ Local** to test your own machine (start Postgres and the app first,
   see the main `README.md` / `compose.yaml`), or **ZifIQ Dev (EC2)** to test
   the deployed server with nothing running locally.

## Authorization

Every request except the five public ones in **01 - Auth** (Register
Tenant, Login, Refresh Token, Forgot Password, Reset Password) has its
Authorization tab set to **Bearer Token** = `{{accessToken}}`.

`accessToken` is a collection variable (collection > **Variables** tab).
**Register Tenant**, **Login** and **Refresh Token** fill it in
automatically, so after running one of them every other request is signed
in. To use a token you got some other way, paste it into the
`accessToken` value on that Variables tab. Tokens expire after an hour; run
**Login** or **Refresh Token** again to get a new one (a `401` response
means it has expired).

## Running it

Run folders **01 through 15 in order**, either by hand or with the
Collection Runner. Each request's Tests script captures tokens and
created-entity IDs (tenant id, access token, factory id, stock item ids,
recipe id, costing id, customer ids, etc.) into collection variables that
later requests reuse automatically — nothing needs to be copy-pasted between
requests.

Every run starts with **01 - Auth > Register Tenant**, which creates a brand
new company with a unique admin e-mail, so each run works in its own empty
tenant and never collides with earlier data.

Some requests are deliberately order-sensitive and documented inline:
- **08 - Inventory Operations > Post Quantity Adjustment (above threshold,
  OTP-gated)** needs the **03 - OTP** folder (request → issue → verify) run
  first, or it returns `403 OTP_REQUIRED`.
- **10 - Factory Trading > Offer Trade** needs stock to actually exist at
  the *source* location, so run **07 - Inventory Stock** and
  **06 - Factory Setup > Create Second Factory** first.
- **04 - VAT Control > Select Tenant VAT Categories** needs to run before any
  **07 - Inventory Stock** registration request — a stock item's
  `vatCategoryId` must already be in the tenant's active set, or
  registration fails with `VAT_CATEGORY_NOT_ACTIVE`.

Every request also carries one saved example response showing the exact
`ApiEnvelope<T>` / `ApiErrorEnvelope` wire shape it returns, and a
description of what it does, its permission, and the optional
query/body fields it accepts.

## Testing the Customers module (folder 12)

Folder **12 - Customers** covers every Customers endpoint (64 requests). It
only needs a logged-in admin, so the quickest way to test it is:

1. Run **01 - Auth > Register Tenant** (this logs you in as the new company's
   Administrator, who holds every permission).
2. Run the **12 - Customers** folder top to bottom: right-click the folder >
   **Run folder**, or click through the requests in order.

The requests are lettered by area, and each one sets up what the next needs:

| Section | What it shows |
|---|---|
| **A** Setup | Creates an ACTIVE home location and stores `{{today}}` |
| **B** Dealer categories | The four defaults every company gets (Platinum, Gold, Silver, Bronze); create, edit, deactivate |
| **C** Correspondents | Register and assign in one step, register unassigned, assign, reassign, edit, detail with history, list, picker |
| **D** Customers | Register a dealer, ordinary, special and walk-in customer; get, edit, link a correspondent |
| **E** Search | `q` with facets and highlighting, phone search in any format, simple filters, filter metadata, stats, the picker, global search |
| **F** Wallet ledger | Admin adjustments, a deposit on a Special Customer, the ledger with opening/closing balances, search by ledger history, reversal |
| **G** Reclassification | Promote today, schedule a future change, cancel it, history and search |
| **H** Status | WATCH, SUSPENDED (blocks wallet movements), back to ACTIVE |
| **I** Cross-record rules | A category in use can't be deactivated; a correspondent's customers; de-assigning a correspondent |
| **J** Self-service | `/customers/me` and `/customers/me/ledger` for dealer logins |

Requests named **"(error: …)"** deliberately break a business rule and
assert the exact error code (for example `409 DUPLICATE_NAME`,
`400 INSUFFICIENT_WALLET_BALANCE`, `409 DEPOSIT_MUST_BE_SETTLED`), so a full
run is all green. The **Test Results** tab shows what each request checked.

Things to try by hand after a run (all ids are already in the collection
variables):
- **E1 Search Customers**: change `q` to a misspelling such as `okafr`, a bare
  code number such as `1`, a qualifier such as `type:special`, or
  `"okafor & sons"` in quotes.
- **E3 Filter Customers**: combine filters such as `status=ACTIVE`,
  `hasCorrespondent=true`, `walletBalance.gt=0` or `period=THIS_MONTH`.
  **E5 Customer Filter Metadata** lists every filter you can use.
- **F5 Customer Ledger**: add `from`/`to` dates to see the opening and
  closing balances change.
- **G1 Reclassify Customer**: set `effectiveFrom` to a future date to
  schedule instead of applying now.

Not covered here, because they need another module or a second login:
- **Temporary walk-in customers** are created automatically by Showroom
  returns (not built yet); D12 shows the convert endpoint refusing a customer
  that's already registered.
- **What other roles can see** (a correspondent seeing only their own
  customers, single-location staff seeing only their location, a dealer
  seeing only their own record) needs extra users. Invite one in folder 02,
  log in as them, and call the Customers list. These rules are also covered
  by the backend's automated tests.

## Documents and files (folders 13 and 14)

**13 - Documents** lists the waybill that folder 08 dispatched in the
Transaction Receipts registry, records a reprint, and searches/filters the
registry.

**14 - Files** uploads to S3 storage. The server needs storage configured:
locally start S3Mock (see the main `README.md`, "Run locally" step 3) or the
uploads answer `503 STORAGE_NOT_CONFIGURED`. In the Postman app, choose any
PDF, image or CSV in **Upload File**'s `file` field (Body > form-data); Newman
uses `postman/fixtures/sample-attachment.pdf` and runs from the repository
root. **Upload File (error: disguised type)** sends a ZIP archive renamed to
`.pdf` to show that the type is checked from the file's content. **Get
Download Link** returns a short-lived signed link; against S3Mock it points at
a Docker-internal host name, so open links only against the dev server.

## Inventory extensions (folder 15)

Drivers, the transfer receipt rules, waybill PDFs, counted adjustments, the
paged adjustments report, the price list and group stock cards (backend
`docs/05-showroom.md` §4.2–§4.13). It uses the factories, products and
category created in folders 06–07, so run it after them. Highlights:
**Receive Transfer (with a rejection)** accepts 4 and rejects 1 — the
rejected unit goes straight back to the sending factory; **Transfer Waybill
PDF** returns the printable waybill (use *Send and Download* to save it);
**Counted Adjustment** sends the counted quantity and the server posts the
difference. Folder 06 now reactivates the factory after testing the
suspension, because a suspended location can't move stock.

## Running it headless (Newman)

The whole collection can also be run end-to-end from the command line with
[Newman](https://www.npmjs.com/package/newman), Postman's CLI runner —
useful for a quick self-check without opening the Postman app:

```sh
# everything, against your machine
npx newman run postman/ZifIQ-API.postman_collection.json -e postman/ZifIQ-Local.postman_environment.json

# just Customers (Auth first, for the token), against the dev server
npx newman run postman/ZifIQ-API.postman_collection.json -e postman/ZifIQ-Dev.postman_environment.json \
  --folder "01 - Auth" --folder "12 - Customers"
```

A clean full run reports `182/182` requests and `216/216` assertions
passing. Several requests are *deliberately* run against a state that makes
them fail. These are not bugs: they demonstrate real error paths on purpose,
so each gets its own status + error-code assertion instead of the generic
one every other request uses, documented inline on the request itself:
- **01 - Auth > Reset Password** — asserts `400 INVALID_RESET_TOKEN`, since
  the collection uses a placeholder token (`RST-482913`); the real one
  lives in `notification_outbox`.
- **09 - Factory Production > Reject Costing** — asserts `409
  ALREADY_DECIDED`, since it deliberately runs against the same costing
  **Approve Costing** just approved, to demonstrate the mutual-exclusion
  check.
- **12 - Customers** — every request named "(error: …)".

## Editing the collection

The collection is generated — don't hand-edit the JSON. Add or change
requests in `generate_collection.py` (one `ep(...)` call per request: method,
path, body, example response, capture scripts, and `expect_error` for
deliberate error paths; folder descriptions live in `FOLDER_DESCRIPTIONS`),
then run:

```sh
python postman/generate_collection.py
```

It rewrites `ZifIQ-API.postman_collection.json` and
`ZifIQ-Local.postman_environment.json`. The Dev environment
(`ZifIQ-Dev.postman_environment.json`, pointing at the dev server) is
maintained by hand. CI runs the collection with Newman against the Local
environment on every push, so run Newman locally before committing.
