# 11 — Barcode & QR Module

Status: **slice 1 implemented (codes); slices 2 (scan) and 3 (labels) pending**
Owner package: `com.zifiq.barcode`
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi` (dealer pricing on POS scans)
Depended on by: nobody (leaf). Scan-enabled screens in other modules call this module's HTTP API from the frontend, then submit the resolved `stockItemId` to their own endpoints.
Migrations: `V17__barcode_module.sql` (slice 1 — the code table, mirror column, backfill, permissions; the planned `V15` number was taken by the time this was built). `scan_events` (slice 2) and the label-print tables (slice 3) arrive in their own later migrations.
Replaces: the mock `BarcodeQrService` (`scanCode` returning a fixed item regardless of input; `generateLabels` returning a fixed list) — the mock `/scan` and `/labels` stay in place until slices 2 and 3 replace them.

**Slice 1 (V17) — implemented.** `stock_item_codes` (+ `code_normalized` generated column and the tenant-wide uniqueness index incl. retired codes) and the `stock_items.barcode_symbology` mirror live in OPEN `inventory` (`com.zifiq.inventory.codes`: `StockItemCodesTable`, `CheckDigits`, `BarcodeGenerator`, `BarcodeSettings`, `StockItemCodeService`). Registration (`POST /inventory/stock-items`) gains optional `barcode` / `barcodeSymbology` / `generateBarcode` and assigns the primary code there. The barcode module adds the Codes API (§4.1): list / generate / assign / replace-primary / retire, with the `barcode.*` permissions and settings. Full business rules enforced: GS1 mod-10 check digits, one active primary per item, carton codes never primary, duplicates (incl. retired) rejected, the primary mirrored to `stock_items.barcode`.

---

## 1. Scope

### 1.1 Features (PRD 5.11, `barcode/ScanAndLabel.jsx`)

| Feature | PRD requirement |
|---|---|
| Assign a barcode/QR code at registration (Stock Register, In-House Recipes – Register), or later | "generated and assigned to a product at the point of registration" |
| Generate codes: EAN-13 for goods, QR for in-house recipes; accept manufacturer codes for outsourced/bought-in goods | "Every registered product must be capable of having a barcode/QR code generated" |
| Extra codes per product (manufacturer code, carton/pack code with a quantity multiplier) | derived: one product often carries both a unit and a case barcode |
| Scan resolution for every scan point: Stock Refill, Raw-material receipt, Stock Transfer, Quantity Adjustment, Receive Product, POS, pro-forma, waybill, Direct Entry, Outsource receipt, Returns, plain lookup | "identify a product within Stock Refill, Stock Transfer, Quantity Adjustment, Receive Product, and POS" |
| Outcomes: Matched / Not recognised (prompt to register or enter manually) / Blocked (suspended, deleted, wrong kind for this screen) / Misread (bad check digit) | "never proceed … against an unrecognized code"; "clearly indicate when a scanned code does not match" |
| Camera-image decode fallback | "via a handheld scanner or a mobile device camera" |
| Scan log across all scan points | screen "Scan log — recent scans across every scan-enabled screen" |
| Label print runs: select items, size (40×25, 58×40, 100×50, A4 24-up), copies, generate missing codes, PDF (and ZPL for thermal printers) | "printed as a label" |
| Unlabelled-items report | screen callout "n registered items have no code assigned" |

### 1.2 Out of scope

- Live camera decoding happens in the browser. The frontend uses `@zxing/browser` (the maintained ZXing JavaScript port), which is stable, MIT/Apache licensed and needs no server round trip per video frame. The backend only receives the decoded text, or a single still image as a fallback (§4.3).
- Scanner hardware configuration (suffix keys, beep) is a per-device client setting kept in browser storage. The "Scanner settings" button opens a client-only panel.

### 1.3 Decisions

1. **Transactions never accept a raw code.** Every stock and sales endpoint already takes `stockItemId` (UUID). A scan resolves a code to an item through §4.2, and the screen then submits that id. Because no business endpoint accepts a barcode string, "a transaction must never be completed against an unrecognized or unregistered barcode/QR code" holds by construction, not by a check someone could forget. Manual entry remains the existing picker, so the fallback is automatic.
2. **One code table.** All codes live in `stock_item_codes` with a tenant-wide unique constraint, so a code can never point at two items. The shipped `stock_items.barcode` column stays as the item's **primary** code (kept in sync in the same transaction) so the shipped Inventory API and POS catalogue search keep working unchanged.
3. **Generated EAN-13 numbers stay out of the global GS1 space unless the tenant owns a prefix.** Without a GS1 company prefix, the system generates **restricted-circulation numbers** (GS1 prefix range `20`–`29`, which GS1 reserves for in-store use). These scan as valid EAN-13 on any scanner and can't collide with a real manufacturer's product. If the tenant enters its GS1 company prefix (e.g. `6151100`, Nigeria's GS1 range is `615`), numbers are generated from it instead.
4. **Scanning respects the permission of the screen it serves.** The resolve call carries a `context`. The caller must hold the permission that the context's manual screen requires (§3.2), and the result is filtered by what that screen may act on (kind, location, status). A cashier scanning a raw material at the POS gets `BLOCKED — not sellable`, not a match.
5. **Barcode symbols are rendered with ZXing** (`com.google.zxing:core` + `javase`, Apache-2.0, the de-facto standard Java library). They are drawn into the label PDFs through the shared Thymeleaf + OpenHTMLtoPDF stack (doc 03 §8.3), so labels look the same as every other document.

---

## 2. Schema — migration `V15__barcode_module.sql`

All tables: `tenant_id` + standard RLS (omitted). `stock_item_codes` is created by V15 but owned by `inventory` code (§4.1 "Registration hook"); the other tables belong to this module.

### 2.1 `stock_item_codes`

```sql
create table stock_item_codes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null references stock_items(id),
    code varchar(64) not null,                             -- exactly as encoded (digits for EAN/UPC; text for QR/Code 128)
    code_normalized varchar(64) generated always as (upper(regexp_replace(code, '\s', '', 'g'))) stored,
    symbology varchar(12) not null check (symbology in ('EAN_13','EAN_8','UPC_A','CODE_128','QR')),
    source varchar(14) not null check (source in ('GENERATED','MANUFACTURER','MANUAL')),
    is_primary boolean not null default false,             -- the label code; mirrored to stock_items.barcode
    pack_quantity numeric(14,3) not null default 1 check (pack_quantity > 0),   -- a carton code that means 24 units → 24
    pack_label varchar(40),                                -- 'Carton of 24'
    status varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
    retired_reason varchar(200),
    assigned_by_user_id uuid not null references app_users(id),
    assigned_at timestamptz not null default now(),
    retired_at timestamptz
);
create unique index stock_item_codes_code_uq on stock_item_codes (tenant_id, code_normalized);   -- retired codes stay reserved (never reused)
create unique index stock_item_codes_primary_uq on stock_item_codes (stock_item_id) where is_primary and status = 'ACTIVE';
create index stock_item_codes_item_idx on stock_item_codes (tenant_id, stock_item_id);
```
**Backfill** (in V15): one row per existing `stock_items.barcode` (`is_primary true`, `source MANUAL`, symbology inferred: 13 digits with a valid check digit → `EAN_13`, 8 → `EAN_8`, 12 → `UPC_A`, else `CODE_128`).

Retired codes are **never** reused (the unique index includes them): old printed labels might still be on shelves, and a re-used code would silently resolve to the wrong product.

```sql
alter table stock_items add column barcode_symbology varchar(12);   -- mirror of the primary code's symbology (for the shipped API)
```

### 2.2 `scan_events`

```sql
create table scan_events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    scanned_at timestamptz not null default now(),
    code varchar(200) not null,                            -- raw value received (truncated to 200)
    input_method varchar(10) not null check (input_method in ('SCANNER','CAMERA','IMAGE','MANUAL')),
    context varchar(24) not null,                          -- §3.2
    outcome varchar(16) not null check (outcome in ('MATCHED','NOT_RECOGNISED','BLOCKED','MISREAD')),
    block_reason varchar(30),                              -- §4.2 table
    stock_item_id uuid references stock_items(id),
    matched_code_id uuid references stock_item_codes(id),
    location_id uuid references locations(id),
    user_id uuid not null references app_users(id),
    client_ref varchar(60)                                 -- optional: the screen's draft/sale id, for correlating
);
create index scan_events_time_idx on scan_events (tenant_id, scanned_at desc);
create index scan_events_outcome_idx on scan_events (tenant_id, outcome, scanned_at desc);
create index scan_events_code_idx on scan_events (tenant_id, code);
```
High-volume operational log, so it is **not** written to `domain_events`. Retention is `barcode.scanLogRetentionDays` (default 180). A nightly job hard-deletes older rows; this is the one log exempt from doc 03 §13, because scans are lookups, not business records.

### 2.3 `label_print_runs` / `label_print_run_items`

```sql
create table label_print_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- LBL-n
    label_size varchar(10) not null check (label_size in ('40x25','58x40','100x50','A4_24')),
    output_format varchar(4) not null check (output_format in ('PDF','ZPL')),
    show_price boolean not null default false,
    price_basis varchar(10) check (price_basis in ('RETAIL','DEALER')),
    location_id uuid references locations(id),             -- price/stock context (optional)
    total_labels int not null,
    codes_generated int not null default 0,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create table label_print_run_items (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null references label_print_runs(id),
    stock_item_id uuid not null references stock_items(id),
    code_id uuid not null references stock_item_codes(id), -- the code printed (snapshot of which one)
    copies int not null check (copies between 1 and 1000),
    item_name varchar(200) not null,                        -- snapshots printed on the label
    unit_name varchar(60) not null,
    price numeric(14,2)
);
```

### 2.4 Module settings registered

| Key | Type | Default |
|---|---|---|
| `barcode.gs1CompanyPrefix` | string? (7–10 digits) | null → restricted-circulation numbers |
| `barcode.restrictedPrefix` | string (2 digits, `20`–`29`) | `20` |
| `barcode.defaultGoodsSymbology` | enum `EAN_13` \| `CODE_128` | `EAN_13` |
| `barcode.recipeSymbology` | enum `QR` \| `CODE_128` | `QR` |
| `barcode.autoGenerateOnRegister` | boolean | `true` |
| `barcode.defaultLabelSize` | enum | `58x40` |
| `barcode.scanLogRetentionDays` | int | 180 |

---

## 3. Permissions

### 3.1 New codes

| Code | Allows | Default roles |
|---|---|---|
| `barcode.lookup` | Resolve codes in `LOOKUP` context (the Scan & Label screen) | every operational role: Administrator, Management, Stock Keeper, Quality Control, Cashier, Accounts Personnel |
| `barcode.codes.manage` | Generate, assign, add/retire codes | Administrator, Stock Keeper |
| `barcode.labels.print` | Create label print runs, download PDFs/ZPL | Administrator, Stock Keeper |
| `barcode.scan-log.read` | Read the scan log | Administrator, Management |

### 3.2 Context → required permission → what may match

| `context` | Manual screen it serves | Caller must hold | Item must be |
|---|---|---|---|
| `LOOKUP` | Scan & Label | `barcode.lookup` | any status (BLOCKED reported, not hidden) |
| `STOCK_REFILL` | Stock Refill – Finished Product | `inventory.stock.manage` | `FINISHED_PRODUCT` or `IN_HOUSE_RECIPE`, ACTIVE |
| `RAW_MATERIAL_RECEIPT` | Stock Refill – Raw Material | `inventory.stock.manage` | `RAW_MATERIAL`, ACTIVE |
| `STOCK_TRANSFER` | Stock Transfer / Showroom transfer | `inventory.transfers.manage` | ACTIVE, not `DIRECT_ENTRY` of another location |
| `QUANTITY_ADJUSTMENT` | Quantity Adjustment | `inventory.adjustments.manage` | ACTIVE |
| `RECEIVE_PRODUCT` | Receive Product | `inventory.transfers.manage` | ACTIVE and on an in-transit transfer to `locationId` (else `NOT_ON_CONSIGNMENT`) |
| `POS` | POS | `cashier.sales.manage` | sellable (§4.2), with a price |
| `PRO_FORMA` | Pro-forma | `cashier.pro-forma.manage` | sellable, with a price |
| `WAYBILL` | Generate Waybill | `cashier.waybill.manage` | ACTIVE (any — the line match is done by the screen) |
| `DIRECT_ENTRY` | Stock Entry (Direct) | `showroom.intake.manage` | `DIRECT_ENTRY` at `locationId`; unknown → `NOT_RECOGNISED` with `canRegisterInline` |
| `OUTSOURCE_RECEIPT` | Outsource Stock | `showroom.intake.manage` | `OUTSOURCED`, ACTIVE |
| `CUSTOMER_RETURN` | Returns | `showroom.returns.manage` | `FINISHED_PRODUCT`/`OUTSOURCED`/`DIRECT_ENTRY` (any status — returned goods of a since-suspended product are still accepted, flagged) |

Lacking the context permission → `403 FORBIDDEN` (the same as opening the manual screen). Location-bound contexts also run `LocationAccessService.assertAccess(locationId)`.

---

## 4. Endpoints (all under `/api/v1/barcode`)

### 4.1 Codes — `/items/{stockItemId}/codes`

- **`GET /items/{stockItemId}/codes`** — perm `barcode.lookup`. `[ { id, code, symbology, source, isPrimary, packQuantity, packLabel, status, assignedBy, assignedAt } ]`.
- **`POST /items/{stockItemId}/codes/generate`** — perm `barcode.codes.manage`. `{ symbology? }` (default per item kind: `IN_HOUSE_RECIPE` → `barcode.recipeSymbology`, else `barcode.defaultGoodsSymbology`). Item already has an active primary → `409 PRIMARY_CODE_EXISTS` (use `replace-primary`). Generation:
  - **EAN-13**: `prefix` = `gs1CompanyPrefix` if set, else `restrictedPrefix`; item reference = `DocumentSequenceService.next("EAN")` numeric part, left-padded to `12 − len(prefix)` digits (exhausting the space → `409 CODE_SPACE_EXHAUSTED`); 13th digit = GS1 mod-10 check digit. `source GENERATED`.
  - **QR**: payload `ZIQ-<item code>-<4-digit sequence from next("QR")>` (e.g. `ZIQ-RC-01-0480`). It deliberately carries no URL or price, so a label stays valid when prices or domains change.
  - **CODE_128**: `ZIQ<item code>` (ASCII subset only, ≤ 48 chars).
  It is inserted as primary, mirrored to `stock_items.barcode`/`barcode_symbology`, and emits `barcode.code.assigned`.
- **`POST /items/{stockItemId}/codes`** — perm `barcode.codes.manage`. Assign an existing code: `{ code, symbology, source: MANUFACTURER|MANUAL, isPrimary?, packQuantity?, packLabel? }`.
  - `EAN_13`/`EAN_8`/`UPC_A`: digits only, right length, valid check digit → else `400 INVALID_CHECK_DIGIT`.
  - `QR`: ≤ 200 chars. `CODE_128`: printable ASCII ≤ 48.
  - Duplicate (including retired) → `409 DUPLICATE_BARCODE` (details: the owning item's code and name, shown only if the caller can see that item).
  - `isPrimary` when a primary exists → the old primary is demoted to a plain alias.
  - Carton codes: `packQuantity` > 1 and `isPrimary false` (a primary must be the unit code → `400 PRIMARY_MUST_BE_UNIT`).
- **`POST /items/{stockItemId}/codes/{codeId}/replace-primary`** — `{ reason }`. Makes `codeId` primary. The old primary stays ACTIVE as an alias, so old labels still scan. The response includes `reprintSuggested: true`.
- **`POST /items/{stockItemId}/codes/{codeId}/retire`** — `{ reason }`. Retiring the primary is only allowed if another active code is promoted in the same call (`{ reason, newPrimaryCodeId }`), or the item is `DELETED`/`PURGED`. A retired code resolves to `BLOCKED (CODE_RETIRED)` with the item named, never `MATCHED`.

**Registration hook:** the shipped `POST /api/v1/inventory/stock-items` and Factory's recipe registration gain optional `barcode`, `barcodeSymbology`, `generateBarcode` (default `barcode.autoGenerateOnRegister`). Registration happens in Inventory, and Inventory may not depend on the leaf `barcode` module (doc 03 §2). So the **code table and generator live in OPEN `inventory`** (`com.zifiq.inventory.codes`: `StockItemCodesTable`, `BarcodeGenerator`, `CheckDigits`), and this module owns the HTTP API, scan resolution, scan log and labels on top of them. With `barcode` supplied, the code is validated and assigned as above. With `generateBarcode`, one is generated. Otherwise the item gets no code and appears in the unlabelled report.

### 4.2 Resolve a scan — `POST /scan`

Perm per §3.2.
```json
{ "code": "6151100101012", "context": "POS", "locationId": "…", "customerId": "…",
  "inputMethod": "SCANNER", "clientRef": "sale:5c1…" }
```
Processing:
1. Normalise: trim, strip whitespace and control characters (scanner suffixes), upper-case for lookup. Codes > 200 chars → `MISREAD`.
2. 8/12/13 digits with a bad check digit → `MISREAD` ("Check digit invalid — rescan"). Nothing is looked up, because a misread could otherwise match a different real code.
3. Look up `code_normalized` among the tenant's codes (UPC-A also tried as EAN-13 with a leading 0, and vice versa).
4. Apply the context rules in §3.2 and the status rules below.
5. Write a `scan_events` row (every outcome, including misreads).

| Outcome | `blockReason` | When |
|---|---|---|
| `MATCHED` | — | allowed by context and status |
| `NOT_RECOGNISED` | — | no code row. Response `{ canRegister (caller holds inventory.stock.manage), canRegisterInline (DIRECT_ENTRY context) }`. The screens show "Register this item" / "Enter manually instead" / "Scan again" |
| `BLOCKED` | `SUSPENDED`, `DELETED`, `PURGED`, `CODE_RETIRED`, `NOT_SELLABLE` (wrong kind for POS/pro-forma), `WRONG_KIND` (e.g. raw material in Stock Refill – Finished Product), `NO_PRICE`, `NOT_AT_LOCATION` (direct-entry item of another showroom), `NOT_ON_CONSIGNMENT`, `VARIANT_PARENT` (scan a variant's code instead) | recognised but not usable here. The item is still named so the user knows what it is |
| `MISREAD` | — | check-digit failure / garbage |

Response:
```json
{ "data": {
  "outcome": "MATCHED", "blockReason": null, "code": "6151100101012",
  "item": { "stockItemId": "…", "code": "ZF-101", "name": "Citrus Drink 50cl", "kind": "FINISHED_PRODUCT",
            "unit": "Cartons", "status": "ACTIVE", "symbology": "EAN_13", "category": "Citrus Drink" },
  "packQuantity": 1, "packLabel": null,
  "price": { "basis": "DEALER", "unitPrice": 4100.00, "retailPrice": 4500.00 },
  "stock": { "locationId": "…", "location": "Showroom Ikeja", "onHand": 640, "available": 560 },
  "message": "Citrus Drink 50cl — ready to use"
} }
```
- `price` only for `POS`/`PRO_FORMA`/`LOOKUP` (by the customer's basis when `customerId` is given).
- `stock` when `locationId` is given (available per doc 09 §2.1).
- `packQuantity` tells the screen to add that many units (a carton code adds 24).
- **Cost is never returned here.** The Scan & Label screen's "Unit price" is the selling price, not the landing cost.

**Batch** — `POST /scan/batch` `{ context, locationId, codes: [ … ≤ 200 ] }` → one result per code (for stocktake-style rapid scanning and offline-captured queues). Each code is logged.

### 4.3 Decode an image — `POST /decode`

Perm `barcode.lookup`. `multipart/form-data`: `image` (JPEG/PNG/WebP, ≤ 5 MB, ≤ 4000 px on either side), `context`, `locationId?`, `customerId?`. The server decodes with ZXing `MultiFormatReader` (hints: EAN-13, EAN-8, UPC-A, Code 128, QR; `TRY_HARDER`). Nothing found → `422 NO_CODE_IN_IMAGE`. Otherwise the decoded text goes through §4.2 with `inputMethod IMAGE`. Several codes in one image → all are resolved (`results: [...]`). The image is not stored.

### 4.4 Scan log — `GET /scan-events` [paged]

Perm `barcode.scan-log.read`; a user without it can still read **their own** scans (`mine=true`, perm `barcode.lookup`). Query `outcome`, `context`, `userId`, `locationId`, `stockItemId`, `code`, `from`, `to`. Row: `{ time, code, result, resolvedTo ("ZF-101 · Citrus Drink 50cl" | "—"), scannedIn (context label), scannedBy, location, note (block reason text) }`. Stats: `{ scans, matchRate, notRecognised, blocked, misread, topUnrecognised: [ { code, count } ] }`. Repeated unrecognised codes are candidates to register: each `topUnrecognised` entry carries `canRegister`.

### 4.5 Labels — `/labels`

- **`GET /items`** — the label picker. Perm `barcode.labels.print`. Query `hasCode` (true/false/all), `kind`, `locationId` (items with stock there), `status` (default ACTIVE), `q` (code CODE, name TRIGRAM). Row `{ stockItemId, code, name, kind, unit, primaryCode, symbology, packCodes: n }`. Header `{ total, withCode, withoutCode }`. `hasCode=false` is the "registered items with no code assigned" callout.
- **`POST /labels/preview`** — `{ stockItemId, size, showPrice?, priceBasis?, locationId? }` → a PNG of one label (rendered the same way as the PDF) for the preview panel. Items without a code preview the code that *would* be generated (marked `GENERATED_ON_SAVE`) without consuming a sequence number.
- **`POST /labels/runs`** — perm `barcode.labels.print` (+ `barcode.codes.manage` when `generateMissing` is true and some items lack codes → else `403`).
  ```json
  { "size": "58x40", "outputFormat": "PDF", "showPrice": true, "priceBasis": "RETAIL", "locationId": "…",
    "generateMissing": true,
    "items": [ { "stockItemId": "…", "copies": 24, "codeId": null }, { "stockItemId": "…", "copies": 6 } ] }
  ```
  `codeId` defaults to the primary. An item without a code and `generateMissing: false` → `409 ITEM_HAS_NO_CODE`. With `showPrice`, an item without a price → `409 NO_PRICE_SET`. Total copies ≤ 5,000 per run → `400 TOO_MANY_LABELS`. It generates the missing codes (§4.1) in the same transaction, `ref = next("LBL")`, and stores the run. Response: `{ ref, totalLabels, codesGenerated, downloadUrl }`.
- **`GET /labels/runs/{id}/labels.pdf`** — renders. Label layout (all sizes): product name (wrapped, ≤ 2 lines), `code · unit`, the symbol (EAN-13 with human-readable digits; QR with the payload under it), optional price (`₦4,500.00`). `40x25` drops the unit line and uses QR only when the payload is ≤ 20 chars. `A4_24` = 3 × 8 grid of 70 × 37 mm on A4 with standard label-sheet margins (compatible with common 24-up sheets). Symbols are rendered with ZXing at 300 dpi and embedded as PNG data URIs. EAN-13 respects the GS1 minimum quiet zones and X-dimension ≥ 0.264 mm at the 58×40 size (80 % magnification), and shrinks to no less than 0.8× on 40×25 (the PDF footer warns if it had to go smaller).
- **`GET /labels/runs/{id}/labels.zpl`** — when `outputFormat ZPL`: Zebra ZPL II text, one `^XA…^XZ` per label copy, `^BE` for EAN-13, `^BC` for Code 128, `^BQ` for QR, at 203 dpi for the chosen size. ZPL is plain text, so no library is needed.
- **`GET /labels/runs`** [paged] — history: `{ ref, createdAt, createdBy, size, items, totalLabels, codesGenerated }`.

---

## 5. Exposed to other modules

None (leaf). The code table and generator are in OPEN `inventory` (§4.1), so any module can read codes directly. The POS catalogue (doc 09 §4.2) searches `stock_item_codes.code_normalized` exactly before falling back to name search.

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| B1 | Never transact against an unrecognised code | PRD 5.11 acceptance | §1.3-1 (endpoints only take ids) + §4.2 outcomes |
| B2 | Clear "not recognised" with register/manual prompts | PRD 5.11 | `NOT_RECOGNISED` payload |
| B3 | Manual entry always available | PRD 5.11 | unchanged pickers; scanning only fills them |
| B4 | Every registered product can get a code and label | PRD 5.11 | §4.1 generate, §4.5 runs with `generateMissing` |
| B5 | Scanning respects the manual screen's role access | PRD 5.11 | §3.2 |
| B6 | Outsourced vs direct-entry distinction respected | PRD 5.11 | `OUTSOURCE_RECEIPT` / `DIRECT_ENTRY` contexts |
| B7 | No transaction against suspended/deleted/inactive products | PRD §6 rule 1 | `BLOCKED` outcomes |
| B8 | A code identifies exactly one product, forever | derived | unique index incl. retired codes |
| B9 | No cost data in scan responses | rule 3 | §4.2 |

## 7. Errors added

`PRIMARY_CODE_EXISTS` 409 · `PRIMARY_MUST_BE_UNIT` 400 · `INVALID_CHECK_DIGIT` 400 · `INVALID_CODE_FORMAT` 400 · `CODE_SPACE_EXHAUSTED` 409 · `CODE_NOT_FOUND` 404 · `CANNOT_RETIRE_PRIMARY` 409 · `ITEM_HAS_NO_CODE` 409 · `TOO_MANY_LABELS` 400 · `NO_CODE_IN_IMAGE` 422 · `IMAGE_TOO_LARGE` 400 · `UNSUPPORTED_IMAGE_TYPE` 400. (`DUPLICATE_BARCODE`, `NO_PRICE_SET` reused.)

## 8. Event catalog

| Event | Notifies |
|---|---|
| `barcode.code.assigned` / `.replaced` / `.retired` | — |
| `barcode.labels.printed` (per run; payload: ref, item count, label count) | — |

Scans are not domain events (§2.2).

## 9. Search & filters

Implements doc 03 §4–§5. Scan resolution (§4.2) is an exact lookup, not a search. It uses `code_normalized` equality (unique btree) with the UPC-A↔EAN-13 variants, so it is O(1) and never fuzzy: a fuzzy match on a barcode would select the wrong product.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /items` (label picker) [paged] · `barcode.items` | item code CODE, primary/alias barcode CODE (prefix allowed here: picking, not transacting), name TRIGRAM | `hasCode`, `kind` multi, `symbology` multi, `source` (GENERATED/MANUFACTURER/MANUAL), `hasPackCodes`, `locationId` (items with stock there), `status`, `categoryId`, `printedSince`/`neverPrinted` (via `label_print_run_items`) | `kind`, `hasCode`, `symbology` | `name`; `code`, `lastPrintedAt` | `{ total, withCode, withoutCode }` |
| `GET /items/{id}/codes` | — | `status` | — | `isPrimary desc, assignedAt` | — |
| `GET /scan-events` [paged, keyset] · `barcode.scan-log` | `code` CODE (exact or prefix), resolved item code/name | `outcome` multi, `context` multi, `blockReason` multi, `inputMethod`, `userId` (`mine=true` forced without `barcode.scan-log.read`), `locationId`, `stockItemId`, `from`/`to`/`period` | `outcome`, `context`, `blockReason` | `scannedAt desc` | `{ scans, matchRate, notRecognised, blocked, misread, topUnrecognised }` |
| `GET /labels/runs` [paged] · `barcode.label-runs` | `ref` REFERENCE, item code/name (via run items) | `createdBy`, `labelSize`, `outputFormat`, `from`/`to`, `stockItemId` | `labelSize` | `createdAt desc` | `{ runs, labels, codesGenerated }` |

**Unrecognised-code workflow**: `GET /scan-events/unrecognised?from=&to=` groups `NOT_RECOGNISED` codes by value with counts, first/last seen and the contexts they were scanned in. Each row carries `canRegister` and a deep link to Stock Register with the code pre-filled. This is how frequently-scanned unknown codes are found and registered.

**Global search provider**: `barcode`. An exact code typed into the header search resolves to its item (title item name, subtitle `code · symbology · status`) via the same exact lookup.

**Indexes (V15)**: `stock_item_codes_code_uq` (exact), `stock_item_codes_item_idx`, `scan_events` time/outcome/code indexes (in §2.2) plus `(tenant_id, user_id, scanned_at desc)` and `(tenant_id, context, scanned_at desc)`; `label_print_run_items (tenant_id, stock_item_id)`.

## 10. Worked example

1. Stock Keeper registers "Cocoa Powder" (`RM-06`, raw material) with `generateBarcode: true`; no GS1 prefix set → `next("EAN")` = 418 → `20` + `0000000418` → check digit 3 → `2000000004183`, primary, EAN-13.
2. The manufacturer's sacks also carry `5901234123457`. Added as `MANUFACTURER`, alias. A 25-bag pallet code `5901234123464` is added with `packQuantity 25`.
3. At Factory B, Stock Refill – Raw Material scans `5901234123464` → `MATCHED`, `packQuantity 25` → the screen adds 25 bags of RM-06.
4. At the Ikeja POS a cashier scans `2000000004183` → `BLOCKED (NOT_SELLABLE)` "Cocoa Powder is a raw material".
5. A cashier scans `6151100420010` (Cocoa Wafer, suspended) → `BLOCKED (SUSPENDED)` with "Review in Recycle Bin".
6. A torn label reads `6151100101013` → check digit should be 2 → `MISREAD`, nothing looked up.
7. Admin prints `LBL-12`: 24 × ZF-101 + 6 × RM-06 on 58×40 with retail price → 30-label PDF.

## 11. Open items

1. **GS1 membership**: if the business sells through retailers that require GS1-registered EANs, it must obtain a GS1 Nigeria company prefix and set `barcode.gs1CompanyPrefix`. Codes generated before that remain valid internally and can be replaced via `replace-primary`.
2. **Batch/expiry in codes** (GS1-128 / GS1 DataMatrix with AI 10/17): not this phase. Batches and expiry are captured on receipts, not encoded on labels.

## 12. Verification

- Generated EAN-13s pass an independent check-digit test and decode with ZXing from the rendered PDF at 58×40 and 40×25.
- Assign the same code to two items → second is `409 DUPLICATE_BARCODE`; a retired code can't be re-assigned.
- Each §3.2 context: a user lacking its permission gets 403; wrong-kind items are `BLOCKED` with the right reason.
- POS context returns the dealer price for a dealer `customerId`, retail otherwise; no cost field anywhere in the JSON.
- Image decode of a photographed label resolves the same as the typed code.
- Scan log rows exist for every outcome; the retention job removes rows older than the setting.
- Newman folder "18 - Barcode".
