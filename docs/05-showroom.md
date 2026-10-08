# 05 — Showroom Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.showroom` (consumer module; exposes a read-only `com.zifiq.showroom.spi` for reports)
Also changes the OPEN `com.zifiq.inventory` module (price list, drivers, group stock cards, transfer-receipt reconciliation, new ledger source types) — Showroom is the first consumer of all of these, the same way Factory added Settings' reference tables in doc 02.
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi` (doc 04)
Migration: `V9__showroom_module.sql`
Replaces: the mock `ShowroomInventoryService`/`ShowroomInventoryController`/`ShowroomDtos.kt`, and `process`'s mock Showroom Register.

---

## 1. Scope

### 1.1 In scope

| Feature | PRD | Screen | Where it's implemented |
|---|---|---|---|
| Showroom register (create/edit/activate showrooms) + storage areas | 5.5.x | `process/ShowroomRegister.jsx` | showroom |
| Receive Product (confirm a transfer in, accept/reject per line) | 5.2.1 | `showroom/ReceiveProduct.jsx` | inventory (extended receive endpoint) |
| Stock Entry (Direct) — third-party goods not in the product register | 5.2.2 | `showroom/StockEntryDirect.jsx` | showroom |
| Quantity Adjustment (by delta or by counted quantity, per area) | 5.2.3 | `showroom/ShowroomAdjustment.jsx` | inventory (shipped endpoint, extended) |
| Stock/Bin Card — Group and Non-Group, per area | 5.2.4 | `showroom/ShowroomCards.jsx` | inventory (new group-card endpoints; single-item shipped) |
| Outsource Stock (registered outsourced products received) | 5.2.5 | `showroom/OutsourceStock.jsx` | showroom |
| Comprehensive Stock Report (showroom variant: source, area, selling price) | 5.2.6 | `showroom/ShowroomStockReport.jsx` | showroom |
| Showroom-to-Showroom Transfer | 5.2.7 | `showroom/ShowroomTransfer.jsx` | inventory (shipped, extended) |
| Returns — Dealer and Non-Dealer | 5.2.8 | `showroom/Returns.jsx` | showroom |
| General Transfer Report (Showroom→Showroom, Factory→Showroom) | 5.2.9 | `showroom/GeneralTransferReport.jsx` | showroom |
| Return Reports | 5.7.15 | `others/ReturnReports.jsx` | showroom |
| Adjustments Report (per showroom and general) | 5.7.5–6 | `others/AdjustmentsReport.jsx` | inventory (shipped list, extended) |
| Storage-area moves (Shop floor ⇄ Warehouse) | implied by 5.2.4's per-area cards | (no dedicated screen; used by the area selector) | showroom |
| Price List (retail + dealer price, effective-dated, margins) | 5.7.10 | `others/PriceList.jsx` | inventory |
| Drivers register (driver + vehicle used on waybills) | implied by `ShowroomTransfer.jsx`, `GenerateWaybill.jsx` driver selects | — | inventory |
| Transfer waybill PDF | PRD §6 rule 6 | `ShowroomTransfer.jsx` printable waybill | inventory |

### 1.2 Moved out (and why)

| Feature | Goes to | Why |
|---|---|---|
| Showroom VAT Report (5.2.10) | doc 10 Audit & Compliance | Output VAT comes from sales (Sales & POS, built later); the sketch also files it under Compliance |
| Out-Source Stock Report (`others/OutsourceStockReport.jsx`) | doc 13 Reports | "Revenue realised" and "margin realised" need sale prices from Sales & POS |
| Per-showroom VAT rate / enablement editing (`process/VATControl.jsx`) | doc 07 Account | Financial control; rates are effective-dated and read by Invoicing/Sales |

### 1.3 Decisions (and the evidence)

1. **Reuse Inventory's stock ledger — no parallel showroom stock tables.** `stock_items`, `stock_balances`, `stock_ledger_entries`, `stock_transfers` are already location-agnostic (`location_id` is a plain uuid), the transfer/adjustment services are already generic, and `showroom.stock.read`/`showroom.stock.manage` are already seeded in V1. A showroom is just a `locations` row with `type = 'SHOWROOM'`.
2. **Storage areas are child locations** (doc 03 §11.2). Showroom screens track "Shop floor" vs "Warehouse"; modelling areas as `STORAGE_AREA` locations means the shipped ledger, balances and `StockLedgerRepository.postEntry` need no change. Every showroom gets *Shop Floor* and *Warehouse* areas on registration unless told otherwise.
3. **Rejected goods go back to the source on the same transaction.** When a receiving showroom rejects part of a consignment, the rejected quantity is posted back into the source location (`TRANSFER_REJECTED_IN`) at receipt time — the driver carries it back. Shipped `receiveTransfer` records `qty_rejected` but credits it nowhere, so rejected stock silently vanishes from the books today; this fixes that. If the rejected goods are damaged, the source then posts a normal `DAMAGED` adjustment.
4. **Direct-entry goods are real `stock_items` rows** with a new `item_type = 'DIRECT_ENTRY'`, restricted to the showroom that entered them ("held against this showroom only and are not added to the company product register"). They get auto codes so they can be sold, counted and reported like anything else.
5. **Prices live in Inventory, effective-dated.** Nothing in the shipped schema has a selling price. Returns, POS, pro-formas, invoices, labels and commission all need one; Inventory is the module everyone already depends on. Showroom is the first consumer, so the price list is built here.
6. **Return value is credited at the price the customer type pays today** (dealer → dealer price, everyone else → retail price), per `ReturnReports.jsx`: "Return value is credited to the customer's wallet at the current selling price." Crediting the *original* sale price would need Sales/Invoicing data that doesn't exist when this module ships; see §11 open question.

---

## 2. Schema — migration `V9__showroom_module.sql`

All new tables: `tenant_id` + standard RLS (omitted). Money `numeric(14,2)`, quantities `numeric(14,3)`.

### 2.1 `locations` — showroom details (generic columns, factories may use them too)

```sql
alter table locations
    add column street_address varchar(300),
    add column city varchar(120),
    add column email varchar(320),
    add column manager_name varchar(160),                       -- free text; manager_user_id (V3) stays for a linked user
    add column date_opened date,
    add column storage_capacity varchar(80),
    add column operating_hours varchar(120),
    add column default_receiving_location_id uuid references locations(id),   -- for FACTORY/SHOWROOM: an own STORAGE_AREA or self
    add column default_selling_location_id uuid references locations(id);     -- for SHOWROOM: where POS deducts by default
-- "Pending opening": can receive stock but cannot transact (ShowroomRegister.jsx) — uses V3's existing 'PENDING' status.
create index locations_name_trgm on locations using gin (lower(name) gin_trgm_ops);
create unique index locations_name_uq on locations (tenant_id, type, lower(name)) where type <> 'STORAGE_AREA';
```

`state` (V3, varchar) keeps holding the state; new rows store the `nigeria_states.code` (e.g. `NG-KN`); existing free-text values are left as they are.

### 2.2 `stock_items` — direct entry

```sql
alter table stock_items drop constraint stock_items_item_type_check;
alter table stock_items add constraint stock_items_item_type_check
    check (item_type in ('FINISHED_PRODUCT','RAW_MATERIAL','IN_HOUSE_RECIPE','OUTSOURCED','DIRECT_ENTRY'));
alter table stock_items alter column hsn_section_id drop not null;
alter table stock_items alter column isic_section_id drop not null;
alter table stock_items add constraint stock_items_classification_required
    check (item_type = 'DIRECT_ENTRY' or (hsn_section_id is not null and isic_section_id is not null));
```

`vat_category_id` stays NOT NULL for every type (a direct-entry item is still sold, and sale VAT needs a category). For `DIRECT_ENTRY` items, `location_id` is the **only** location (or its areas) that may hold their stock: `StockLedgerRepository.postEntry` rejects any other location with `400 ITEM_LOCATION_RESTRICTED`, and `GET /inventory/stock-items` hides them from other locations' pickers.

### 2.3 `stock_ledger_entries.source_type` — widened

```sql
alter table stock_ledger_entries drop constraint stock_ledger_entries_source_type_check;
alter table stock_ledger_entries add constraint stock_ledger_entries_source_type_check check (source_type in (
    'REFILL','RECEIVE','PRODUCTION_RUN','TRANSFER_OUT','TRANSFER_IN','QUANTITY_ADJUSTMENT','TRADE_OUT','TRADE_IN',   -- shipped
    'TRANSFER_REJECTED_IN',     -- rejected part of a consignment returned to source (§1.3-3)
    'DIRECT_ENTRY',             -- §4.4
    'OUTSOURCE_RECEIPT',        -- §4.5
    'CUSTOMER_RETURN',          -- §4.8 (resaleable/quarantine)
    'AREA_MOVE_OUT','AREA_MOVE_IN'   -- §4.7
));
```
(Sales & POS adds `POS_SALE`, `CUSTOMER_WAYBILL` in V13.) The display labels used by the Stock Movement History screen are: Stock Refill, Receive Product, Production run, Stock Transfer / Showroom transfer (by route), Factory trade, Quantity Adjustment, Rejected consignment, Direct entry, Outsource receipt, Customer return, Area move.

### 2.4 `stock_transfers` / `stock_transfer_lines` — receipt detail

```sql
alter table stock_transfers
    add column driver_id uuid references drivers(id),          -- created below; driver_name/driver_vehicle stay as snapshots
    add column transfer_date date not null default current_date,
    add column received_date date,
    add column receipt_note varchar(300);
alter table stock_transfer_lines
    add column from_location_id uuid references locations(id),          -- area dispatched from (null = source itself)
    add column received_into_location_id uuid references locations(id), -- area received into (null = destination itself)
    add column batch_no varchar(40),
    add column rejection_note varchar(300);
```

### 2.5 `drivers` (inventory-owned)

```sql
create table drivers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(160) not null,
    phone varchar(40),
    vehicle_number varchar(30),
    licence_number varchar(40),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create unique index drivers_name_vehicle_uq on drivers (tenant_id, lower(name), coalesce(vehicle_number,''));
create index drivers_name_trgm on drivers using gin (lower(name) gin_trgm_ops);
```

### 2.6 `stock_item_prices` (inventory-owned price list)

```sql
create table stock_item_prices (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null references stock_items(id),
    retail_price numeric(14,2) not null check (retail_price >= 0),
    dealer_price numeric(14,2) not null check (dealer_price >= 0),
    effective_from date not null,
    batch_ref varchar(20) not null,                        -- PL-n, one per publish
    note varchar(300),
    set_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint stock_item_prices_dealer_le_retail check (dealer_price <= retail_price),
    unique (tenant_id, stock_item_id, effective_from)
);
create index stock_item_prices_lookup_idx on stock_item_prices (tenant_id, stock_item_id, effective_from desc);
```

The **current price** of an item on date *d* is the row with the greatest `effective_from ≤ d`. Rows are never updated; a correction for the same date is a re-publish (`ON CONFLICT (tenant_id, stock_item_id, effective_from) DO UPDATE` is **not** used — publishing the same date twice is `409 PRICE_ALREADY_SET_FOR_DATE`; publish for tomorrow or change the existing future-dated row via §4.12's `PUT`, allowed only while `effective_from > today`).

### 2.7 `showroom_stock_entries` / `showroom_stock_entry_lines` (Direct entry and Outsource receipts)

```sql
create table showroom_stock_entries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- DE-n or OS-n
    kind varchar(20) not null check (kind in ('DIRECT_ENTRY','OUTSOURCE_RECEIPT')),
    location_id uuid not null references locations(id),   -- the showroom
    into_location_id uuid not null references locations(id),  -- showroom or one of its areas
    supplier_id uuid references suppliers(id),             -- DIRECT_ENTRY: registered external supplier (optional)
    supplier_name varchar(160),                            -- DIRECT_ENTRY: free text when not registered ("Independent local vendor")
    manufacturer_id uuid references manufacturers(id),     -- OUTSOURCE_RECEIPT: outsource partner (required)
    partner_reference varchar(60),                         -- supplier invoice no. / partner invoice or waybill no.
    payment_terms varchar(120),
    checked_by varchar(120),
    received_date date not null,
    total_cost numeric(14,2) not null,                     -- Σ line cost [cost]
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint sse_kind_party check (
        (kind = 'DIRECT_ENTRY' and manufacturer_id is null) or
        (kind = 'OUTSOURCE_RECEIPT' and manufacturer_id is not null and supplier_id is null and supplier_name is null))
);
create index sse_location_date_idx on showroom_stock_entries (tenant_id, location_id, received_date desc);

create table showroom_stock_entry_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    entry_id uuid not null references showroom_stock_entries(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    description varchar(200) not null,                     -- snapshot of the item name as entered
    quantity numeric(14,3) not null check (quantity > 0),
    unit_of_measure_id uuid not null references units_of_measure(id),
    unit_cost numeric(14,2) not null check (unit_cost >= 0),   -- [cost]
    line_cost numeric(14,2) not null,                          -- [cost] VAT-exclusive
    input_vat_rate numeric(5,2) not null default 0,            -- VAT charged by the supplier on this line (0 = none)
    input_vat_amount numeric(14,2) not null default 0,         -- line_cost × input_vat_rate / 100, 2 dp; feeds doc 10's input VAT
    batch_no varchar(40),
    expiry_date date,
    selling_price numeric(14,2),                           -- DIRECT_ENTRY: retail price published for the new item
    unique (entry_id, line_no)
);
create index ssel_description_trgm on showroom_stock_entry_lines using gin (lower(description) gin_trgm_ops);
```

### 2.8 `customer_returns` / `customer_return_lines`

```sql
create table customer_returns (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- RT-n (one sequence for dealer and non-dealer)
    return_type varchar(20) not null check (return_type in ('DEALER','NON_DEALER')),
    location_id uuid not null references locations(id),   -- the showroom
    customer_id uuid not null,                             -- customers.id (cross-module: plain uuid, no FK — doc 00 rule)
    customer_code varchar(20) not null,                    -- snapshot
    customer_name varchar(200) not null,                   -- snapshot
    customer_type varchar(30) not null,                    -- snapshot
    temporary_customer boolean not null,                   -- snapshot: credited to a temporary wallet
    original_document_ref varchar(40),                     -- original receipt no. (non-dealer) / invoice no. (dealer), free text
    return_date date not null,
    total_value numeric(14,2) not null,                    -- credited to wallet
    write_off_value numeric(14,2) not null,                -- Σ value of DAMAGED_WRITE_OFF lines ("net cost of returns")
    wallet_entry_id uuid not null,                         -- customer_ledger_entries.id of the credit
    wallet_balance_after numeric(14,2) not null,
    note varchar(300),
    status varchar(20) not null default 'POSTED' check (status in ('POSTED','REVERSED')),
    reversed_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    reversed_at timestamptz,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create index cr_location_date_idx on customer_returns (tenant_id, location_id, return_date desc);
create index cr_customer_idx on customer_returns (tenant_id, customer_id);
create index cr_customer_name_trgm on customer_returns using gin (lower(customer_name) gin_trgm_ops);

create table customer_return_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    return_id uuid not null references customer_returns(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null check (quantity > 0),
    unit_price numeric(14,2) not null,                     -- price credited per unit (dealer or retail, §1.3-6)
    line_value numeric(14,2) not null,
    reason varchar(40) not null check (reason in (
        'DAMAGED_IN_TRANSIT','EXPIRED_OR_NEAR_EXPIRY','WRONG_ITEM_SUPPLIED',
        'PRODUCT_DEFECT','UNSOLD_AGREED_RETURN','CUSTOMER_DISSATISFACTION')),
    condition varchar(20) not null check (condition in ('RESALEABLE','DAMAGED_WRITE_OFF','QUARANTINE')),
    into_location_id uuid references locations(id),        -- where RESALEABLE/QUARANTINE stock went; null for write-off
    note varchar(300),
    unique (return_id, line_no)
);
```

### 2.9 `storage_area_moves` / `storage_area_move_lines`

```sql
create table storage_area_moves (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- AM-n
    root_location_id uuid not null references locations(id),   -- the showroom/factory both areas belong to
    from_location_id uuid not null references locations(id),
    to_location_id uuid not null references locations(id),
    moved_date date not null,
    note varchar(300),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint sam_distinct check (from_location_id <> to_location_id)
);
create table storage_area_move_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    move_id uuid not null references storage_area_moves(id),
    stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null check (quantity > 0)
);
```

### 2.10 Module settings registered

| Key | Type | Default | Meaning |
|---|---|---|---|
| `showroom.defaultAreas` | object[] | `[{"name":"Shop Floor","kind":"SHOP_FLOOR"},{"name":"Warehouse","kind":"WAREHOUSE"}]` | Areas auto-created on showroom registration |
| `inventory.receive.requireFullReconciliation` | boolean | `true` | Accepted + rejected must equal the outstanding quantity on every line confirmed (`ReceiveProduct.jsx`) |

---

## 3. Permission codes

Existing, reused: `showroom.stock.read` (showroom reports, cards, areas), `showroom.stock.manage` (area moves, area setup), `inventory.stock.read`, `inventory.stock.manage`, `inventory.transfers.manage`, `inventory.adjustments.manage`.

New (V9):

| Code | Allows | Default roles |
|---|---|---|
| `showroom.register.manage` | Register/edit showrooms, change status, manage areas and defaults | (Administrator) |
| `showroom.intake.manage` | Post Direct entries and Outsource receipts | Stock Keeper |
| `showroom.returns.manage` | Post / reverse customer returns | Stock Keeper, Cashier |
| `showroom.returns.read` | Return Reports | Management, Accounts Personnel, Stock Keeper, Cashier |
| `inventory.pricing.read` | Price list (cost/margin columns still gated by `factory.costing.read`) | Management, Accounts Personnel, Stock Keeper, Cashier |
| `inventory.pricing.manage` | Publish prices | (Administrator) |
| `inventory.drivers.read` | List drivers | Stock Keeper, Cashier, Management |
| `inventory.drivers.manage` | Register/edit drivers | Stock Keeper |

Location scope everywhere through `LocationAccessService` (assert on writes, filter-set on reads), which per doc 03 §11.3 lets a user assigned to a showroom act on its areas.

---

## 4. Endpoints

### 4.1 Showroom register — `/api/v1/showroom/showrooms`

**`POST`** — perm `showroom.register.manage`.

| Field | Type | Rules |
|---|---|---|
| `name` | string | required, ≤160, unique among showrooms → `409 DUPLICATE_NAME` |
| `stateCode` | string | required, in `nigeria_states` → `400 INVALID_STATE_CODE` |
| `streetAddress` | string | required, ≤300 |
| `city` | string | required, ≤120 |
| `phone` | string | required |
| `email` | string? | e-mail |
| `managerName` | string? | ≤160 |
| `managerUserId` | uuid? | active member |
| `dateOpened` | date? | |
| `storageCapacity` | string? | ≤80 |
| `operatingHours` | string? | ≤120 |
| `active` | boolean | default `true`; `false` → status `PENDING` ("Pending opening") |
| `createDefaultAreas` | boolean | default `true` |

Logic: code `next("SHR")` (immutable, "branch codes are generated by the system and cannot be edited"); VAT columns take V3 defaults (7.50 %, enabled) — changed later in Account's VAT Control (doc 07); if `createDefaultAreas`, insert one `STORAGE_AREA` per `showroom.defaultAreas` entry with code `<SHR-n>-SF` / `-WH` (others `-A<k>`), and set `default_receiving_location_id` = Warehouse, `default_selling_location_id` = Shop Floor. Emits `showroom.showroom.registered`.

Response `ShowroomDetail { id, code, name, stateCode, stateName, streetAddress, city, phone, email, managerName, managerUserId, dateOpened, storageCapacity, operatingHours, status, vatRate, vatEnabled, vatRegistrationNumber, areas: [ { id, code, name, kind, status } ], defaultReceivingLocationId, defaultSellingLocationId, createdAt }`.

**`GET`** — perm `showroom.stock.read`. Unpaged. Query `status`, `stateCode`, `q` (code CODE, name/city TRIGRAM). Row: `{ id, code, name, streetAddress, stateName, managerName, phone, dateOpened, vatRate, status, areaCount }`. Header stats `{ active, pendingOpening, suspended }`.

**`GET /{id}`**, **`PUT /{id}`** (same fields minus `active`/`createDefaultAreas`; code immutable), **`PUT /{id}/status`** `{ status: ACTIVE|PENDING|SUSPENDED, reason }` — `PENDING→ACTIVE` is "open for trading". Suspending a showroom with stock on hand is allowed (stock just can't move) but returns `warnings: ["Showroom holds 1,240 units"]`.

Status meaning (enforced by the modules that transact): `PENDING` → can receive transfers and direct/outsource entries, cannot sell, transfer *out*, or take returns (`409 LOCATION_NOT_TRADING`); `SUSPENDED` → nothing moves (`409 LOCATION_SUSPENDED`).

**Areas** — perm `showroom.register.manage` (also usable for factories):
- `POST /{id}/areas` `{ name, kind: SHOP_FLOOR|WAREHOUSE|RECEIVING|QUARANTINE|OTHER }` → code `<root code>-<suffix>`.
- `PUT /areas/{areaId}` `{ name, kind }`; `PUT /areas/{areaId}/status` `{ status }` — deactivating an area that holds stock → `409 AREA_HAS_STOCK` (move it first, §4.7).
- `PUT /{id}/defaults` `{ defaultReceivingLocationId, defaultSellingLocationId }` — each must be the showroom itself or one of its ACTIVE areas → `400 INVALID_LOCATION`; the selling default can't be a `QUARANTINE` area.

### 4.2 Receive Product — extends shipped `POST /api/v1/inventory/stock/transfers/{id}/receive`

Perm `inventory.transfers.manage` (shipped). `LocationAccessService.assertAccess(destination)` (shipped).

Request (new fields marked ✚):
```json
{ "receivedDate": "2026-10-08",                 ✚
  "note": "Two cartons crushed",                ✚
  "lines": [
    { "stockTransferLineId": "…", "qtyReceived": 48, "qtyRejected": 2,
      "rejectionNote": "Crushed in transit",    ✚ required when qtyRejected > 0
      "receivedIntoLocationId": "…"              ✚ optional: destination or one of its areas; default = destination's default_receiving_location_id, else destination
    } ] }
```
Rules:
- `qtyReceived ≥ 0`, `qtyRejected ≥ 0`, and `qtyReceived + qtyRejected ≤ outstanding` (outstanding = sent − already received − already rejected) → else `400 RECEIPT_EXCEEDS_SENT` with details `{ line, outstanding, attempted }`.
- If `inventory.receive.requireFullReconciliation` is true: each line in the request must satisfy `received + rejected == outstanding` → else `400 RECEIPT_NOT_RECONCILED` (`"Accepted plus rejected exceeds the 50 dispatched by 2"` / `"2 of the 50 dispatched is unaccounted for"`). Lines not included stay outstanding.
- Posting: `TRANSFER_IN` of `qtyReceived` at `receivedIntoLocationId`; **`TRANSFER_REJECTED_IN` of `qtyRejected` at the line's `from_location_id ?: source_location_id`** with remark `"Rejected by <destination> — <rejectionNote>"`.
- Header status: all lines reconciled → `RECEIVED`, else `PARTIALLY_RECEIVED` (shipped logic). Sets `received_date`, `receipt_note`.
- Emits `inventory.transfer.received` (shipped) — payload now includes `rejectedQty`; when rejected > 0 also notifies in-app the holders of `inventory.transfers.manage` at the **source**: "WB-9014: 2 cartons rejected by Showroom Ikeja — returned to Factory A".

"Incoming consignments awaiting receipt" = shipped `GET /api/v1/inventory/stock/transfers?locationId=<showroom>&status=DISPATCHED,PARTIALLY_RECEIVED` — `status` becomes repeatable/comma-separated, and each row gains `{ sourceLocationName, sourceLocationType (FACTORY|SHOWROOM), driverName, driverVehicle, transferDate, lines: [ {…, batchNo, outstanding} ] }`.

### 4.3 Showroom-to-Showroom Transfer — extends shipped `POST /api/v1/inventory/stock/transfers`

Perm `inventory.transfers.manage` (shipped). New request fields: `driverId` (optional; when given, `driverName`/`driverVehicle` are filled from the driver and any values sent are ignored), `transferDate` (default today), per line `fromLocationId` (the source or one of its areas — "Dispatch from area"; default source) and `batchNo`. Rules added: source and destination must not be the same root location → `400 SAME_LOCATION` (use an area move, §4.7); destination must not be `SUSPENDED` → `409 LOCATION_SUSPENDED`; a `PENDING` source can't dispatch → `409 LOCATION_NOT_TRADING`; available quantity is checked at the line's `fromLocationId` (shipped check, now per area). The waybill number stays `next("WB")`; the transfer is registered in the document registry (`doc_type WAYBILL`, doc 03 §8).

**Waybill PDF (new):** `GET /api/v1/inventory/stock/transfers/{id}/waybill.pdf` — perm `inventory.stock.read`. Renders "Showroom Transfer Waybill" / "Stock Transfer Waybill" by route: issuer block (source name/address/phone/VAT reg.), destination block, meta `[Driver, Vehicle no., Dispatch date]`, lines `[product, area, qty, unit]`, totals `[Total lines, Total quantity]`, stamp (`In transit` / `Received` / `Partially received`), signature boxes. "DUPLICATE COPY" watermark once reprinted (doc 03 §8.2).

### 4.4 Stock Entry (Direct) — `POST /api/v1/showroom/direct-entries`

Perm `showroom.intake.manage`. `assertAccess(locationId)`.

| Field | Type | Rules |
|---|---|---|
| `locationId` | uuid | an ACTIVE or PENDING showroom |
| `intoLocationId` | uuid? | the showroom or one of its areas; default its receiving default |
| `supplierId` | uuid? | ACTIVE supplier |
| `supplierName` | string? | required if `supplierId` absent ("Independent local vendor") |
| `supplierInvoiceNo` | string? | ≤60 |
| `paymentTerms` | string? | ≤120 |
| `receivedDate` | date | ≤ today |
| `vatCategoryId` | uuid? | default: the tenant's active `STANDARD_RATE` category; must be active for the tenant → `400 VAT_CATEGORY_NOT_ACTIVE` (shipped error) |
| `lines` | array (1–100) | |
| `lines[].description` | string | required, ≤200 — **free text, not a product pick** |
| `lines[].quantity` | decimal | > 0 |
| `lines[].unitOfMeasureId` | uuid | ACTIVE unit |
| `lines[].unitCost` | decimal | ≥ 0, VAT-exclusive |
| `lines[].inputVatRate` | decimal? | 0–100, default 0 — VAT the supplier charged on this line (stored with the computed `input_vat_amount`; the outsource-receipt endpoint §4.5 accepts the same field) |
| `lines[].sellingPrice` | decimal | ≥ 0 |
| `lines[].dealerPrice` | decimal? | ≤ sellingPrice; default = sellingPrice |

Logic, one transaction:
1. `ref = next("DE")`.
2. For each line: find an existing `DIRECT_ENTRY` item at this showroom with the same `lower(name)` and unit; if none, create one (`code next("ITM")`, `item_type DIRECT_ENTRY`, `location_id` = showroom, `name` = description, `landing_cost` = unitCost, `vat_category_id`, no HSN/ISIC, `category_id` null — shown as "Ungrouped").
3. Publish a price row effective `receivedDate` (or today if later) `retail = sellingPrice`, `dealer = dealerPrice`, `batch_ref = next("PL")`, note "Direct entry DE-n" — skipped if the item already has the same prices current.
4. Update the item's `landing_cost` to this line's `unitCost` (latest-cost method, matching how Production updates finished-product cost in doc 02 §1a).
5. `StockLedgerRepository.postEntry(DIRECT_ENTRY, qty_in, unit_cost, sourceReference = DE-n, remark "Direct entry — <supplier>")` at `intoLocationId`.
6. Insert `showroom_stock_entries` (kind `DIRECT_ENTRY`) + lines; emit `showroom.direct-entry.posted`.

Response `StockEntrySummary { id, ref, kind, locationName, intoLocationName, supplierName, partnerReference, receivedDate, lines: [ { lineNo, stockItemId, code, description, quantity, unit, unitCost[cost], lineCost[cost], sellingPrice, batchNo, expiryDate } ], totalCost[cost], enteredBy, createdAt, costVisible }`.

`GET /api/v1/showroom/direct-entries` [paged] — perm `showroom.stock.read`. Query `locationId`, `supplierId`, `from`, `to`, `q` (ref CODE, line description TRIGRAM, supplier name TRIGRAM). Row = one per line: `{ date, ref, product, supplier, invoiceNo, qty, unit, unitCost[cost], enteredBy }` + `costVisible`.

### 4.5 Outsource Stock — `POST /api/v1/showroom/outsource-receipts`

Perm `showroom.intake.manage`. Request `{ locationId, intoLocationId?, manufacturerId (required, the outsource partner), partnerReference?, checkedBy?, receivedDate, lines: [ { stockItemId, quantity, batchNo?, expiryDate? } ] }`.
Rules: each item must be `item_type = OUTSOURCED` → `400 ITEM_NOT_OUTSOURCED` ("For third-party goods the company has not registered, use Stock Entry (Direct) instead"), `ACTIVE` → `400 ITEM_SUSPENDED` (shipped), have `landing_cost` set → `400 OUTSOURCE_COST_NOT_SET`; `expiryDate` (if given) > `receivedDate` → else `400 INVALID_EXPIRY`. Unit cost is **the item's registered landing cost**, not user input (screen shows it read-only). Logic: `ref = next("OS")`; per line `postEntry(OUTSOURCE_RECEIPT, qty_in, unit_cost = landing_cost)`; insert header (kind `OUTSOURCE_RECEIPT`) + lines; emit `showroom.outsource-receipt.posted`. Response `StockEntrySummary`.
`GET /api/v1/showroom/outsource-receipts` [paged] — `locationId`, `manufacturerId`, `stockItemId`, `from`, `to`, `q`. Row: `{ date, ref, product, partner, batchNo, expiryDate, qty, unitCost[cost], receivedBy }`.

### 4.6 Quantity Adjustment — extends shipped `POST /api/v1/inventory/quantity-adjustments`

Perm `inventory.adjustments.manage` (shipped; Stock Keeper only per PRD rule 4). OTP gate unchanged except the threshold now comes from `inventory.adjustment.otpThresholdQty` (doc 03 §10).
New optional request fields: `mode` (`DELTA` default | `COUNTED`) and `countedQuantity`. With `COUNTED`, the server locks the balance at `locationId` (which may be an area), computes `variance = counted − on hand`; variance 0 → `400 NO_ADJUSTMENT_NEEDED`; positive → `POSITIVE` adjustment of `variance`, negative → `NEGATIVE` of `|variance|` — so the UI's "Enter counted quantity" mode can't race a concurrent sale. The response gains `{ recordedQuantity, newQuantity }`.

**Adjustments Report** — extends shipped `GET /api/v1/inventory/quantity-adjustments` (now [paged]): query `locationId` (null = all locations in scope — the "general" report; a showroom id includes its areas), `direction`, `reason`, `stockItemId`, `from`, `to`, `q` (ref CODE, product TRIGRAM). Row `{ id, date, ref, locationName, rootLocationName, productCode, productName, direction, quantity, unit, reason, note, adjustedBy }`; header stats `{ count, qtyAdded, qtyRemoved, netEffect }`.

### 4.7 Storage-area moves — `POST /api/v1/showroom/area-moves`

Perm `showroom.stock.manage`. Request `{ fromLocationId, toLocationId, movedDate?, note?, lines: [ { stockItemId, quantity } ] }`. Both must be the same root location or its areas → else `400 NOT_SAME_LOCATION`; distinct → `400 SAME_LOCATION`; target `QUARANTINE` allowed, moving *out* of quarantine into a selling area requires `note`. Available quantity checked at `fromLocationId` → `400 INSUFFICIENT_STOCK` (shipped error). Logic: `ref = next("AM")`, per line `AREA_MOVE_OUT` + `AREA_MOVE_IN` pair, insert header/lines, emit `showroom.area-move.posted`. No waybill (goods don't leave the premises). `GET /api/v1/showroom/area-moves` [paged] — `locationId`, `from`, `to`.

### 4.8 Returns — `POST /api/v1/showroom/returns`

Perm `showroom.returns.manage`. `assertAccess(locationId)`. Showroom must be `ACTIVE` → `409 LOCATION_NOT_TRADING`.

```json
{ "returnType": "NON_DEALER",
  "locationId": "…",
  "customerId": null,                       // DEALER: required (an AUTHORISED_DEALER). NON_DEALER: optional (a registered non-dealer)
  "walkIn": { "name": "Ada Nwosu", "phone": "0809 111 2222" },   // NON_DEALER without customerId: required
  "originalDocumentRef": "RCT-552",
  "returnDate": "2026-10-08",
  "note": null,
  "lines": [ { "stockItemId": "…", "quantity": 2, "reason": "PRODUCT_DEFECT", "condition": "RESALEABLE", "intoLocationId": null, "note": null } ] }
```
Rules:
- `DEALER`: customer must be `AUTHORISED_DEALER` → `400 NOT_A_DEALER`. `NON_DEALER` with `customerId`: must **not** be a dealer → `400 USE_DEALER_RETURN`.
- Customer must be `ACTIVE`/`WATCH` → `409 CUSTOMER_NOT_ACTIVE` (from spi).
- Lines: item `ACTIVE` (suspended products can't be transacted, PRD rule 1 → `400 ITEM_SUSPENDED`); `DIRECT_ENTRY` items only at their own showroom.
- `unit_price` = current `dealer_price` (dealer) or `retail_price` (everyone else) at `returnDate`; no price set → `400 PRICE_NOT_SET` (a return must have a value to credit).
- Stock effect per line by `condition`:
  - `RESALEABLE` → `CUSTOMER_RETURN` qty_in at `intoLocationId ?: showroom.default_receiving_location_id ?: showroom` ("Resaleable goods re-enter stock").
  - `QUARANTINE` → `CUSTOMER_RETURN` qty_in at the showroom's `QUARANTINE` area — created automatically (`<code>-QR`, "Quarantine") on first use. Quarantine stock is never sellable (doc 09 excludes `QUARANTINE` areas from POS availability).
  - `DAMAGED_WRITE_OFF` → no stock entry ("recorded as a loss"); counted in `write_off_value`.
- Wallet: `customerId` resolved (or `TemporaryCustomers.findOrCreate(walkIn.name, walkIn.phone, locationId)` from doc 04 §5.3); then `WalletLedger.credit(WALLET, RETURN_CREDIT, total_value, reference = RT-n, sourceModule "showroom", sourceType "CUSTOMER_RETURN")`. **Every** line is credited, including write-offs — the customer is compensated for all goods returned; the business absorbs write-offs as a loss.
- Insert `customer_returns` + lines (snapshots); emit `showroom.return.posted`. A return is not a money receipt, so it is **not** added to the document registry; it is listed in Return Reports (§4.9).

Response `ReturnSummary { id, ref, returnType, locationName, customer: { id, code, name, type, temporary }, originalDocumentRef, returnDate, lines: [ { lineNo, stockItemId, code, name, quantity, unit, unitPrice, lineValue, reason, condition, intoLocationName, note } ], totalValue, writeOffValue, walletBalanceBefore, walletCredit, walletBalanceAfter, processedBy, createdAt }`.

**Reverse a return** — `POST /api/v1/showroom/returns/{id}/reverse` — perm `showroom.returns.manage` + `customers.wallet.adjust`. Body `{ reason }`. Only while the wallet still holds the credited amount (calls `WalletLedger.reverse`, which fails with `400 INSUFFICIENT_WALLET_BALANCE` if already spent) and the returned stock is still on hand at the posting location (`400 INSUFFICIENT_STOCK`); posts opposite `CUSTOMER_RETURN` qty_out entries; status `REVERSED`; emits `showroom.return.reversed`.

### 4.9 Return Reports — `GET /api/v1/showroom/returns` [paged]

Perm `showroom.returns.read`. Query `locationId`, `returnType`, `condition`, `reason`, `customerId`, `status` (default `POSTED`), `from`, `to`, `q` (ref/original doc CODE, customer name TRIGRAM).
Row (one per line, as the screen shows): `{ returnId, date, ref, originalDocumentRef, customerName, customerType, productCode, productName, quantity, unitPrice, lineValue, reason, condition, locationName, creditedTo ('Dealer wallet'|'Temporary wallet'|'Customer wallet') }`.
Header: `{ totals: { returns, units, valueCredited, resaleableValue, quarantineValue, writeOffValue (= "Net cost of returns") }, byReason: [ { reason, lines, units, value } ] }`.

### 4.10 Stock/Bin Card — Group (new) and Non-Group (shipped)

Non-group: shipped `GET /api/v1/inventory/stock/card/{stockItemId}` and `/bin-card/{stockItemId}` — `locationId` now accepts a showroom (aggregates it and its areas) or a single area (`includeAreas` default `true` when a root is passed).

**Group (new):** `GET /api/v1/inventory/stock/group-card` and `GET /api/v1/inventory/stock/group-bin-card` — perm `inventory.stock.read` or `showroom.stock.read`.
Query: `groupBy` = `CATEGORY` | `PARENT_PRODUCT` (variants of one product, doc 03 §12.2 — this is the "Members: 50cl, 1L, 75cl" view), `groupId` (category id or parent stock item id), `locationId` (root or area), `from`, `to`.
Stock card rows: every ledger entry of any member item in range: `{ sn, date, itemCode, itemName, remark, qtyIn, qtyOut, qtyRemain }` (running group total). Bin card rows: daily totals `{ sn, date, qtyIn, qtyOut, balance }`. Header: `{ group: { type, id, name, brand }, members: [ { stockItemId, code, name, unit } ], openingBalance, totalReceived, totalIssued, closingBalance }`. Mixed units in one group (e.g. Cartons and Pieces) → `400 GROUP_MIXED_UNITS` with details (summing them would be meaningless). This endpoint also serves Factory's "Stock Card (GP)" / "Bin Card (GP)" nav items (doc 02 shipped only the single-item card).

### 4.11 Comprehensive Stock Report (showroom) — `GET /api/v1/showroom/reports/comprehensive-stock` [paged]

Perm `showroom.stock.read`. Query: `locationId` (showroom; required unless the caller is ALL_LOCATIONS, in which case null = every showroom), `areaId`, `source` (`FINISHED_PRODUCT`|`OUTSOURCED`|`DIRECT_ENTRY`, repeatable), `categoryId`, `asAt` (date; default today), `includeZero` (default false), `q`.
`asAt = today` reads `stock_balances`; an earlier date sums `stock_ledger_entries` up to that date (same as the Stock Card opening balance).
Row (one per item × area): `{ ln, stockItemId, code, detail (name), group (category name or "Ungrouped"), source ('Finished Product'|'Outsource'|'Direct entry'), area (area name or "Unallocated"), qty, unit, unitCost[cost], totalCost[cost], sellingPrice (current retail), retailValue }`.
Header: `{ costVisible, lineItems, totalQuantity, totalStockValue[cost], retailValue, storageAreas }`.

### 4.12 Price List (inventory) — `/api/v1/inventory/price-list`

- **`GET`** [paged] — perm `inventory.pricing.read`. Query `asAt` (default today), `itemType`, `categoryId`, `locationId` (only matters for `DIRECT_ENTRY` items), `hasPrice` (bool), `belowCost` (bool, ignored when the caller can't see cost), `q`. Row: `{ stockItemId, code, product, group, unit, retailPrice, dealerPrice, effectiveFrom, nextChange: { effectiveFrom, retailPrice, dealerPrice } | null, landingCost[cost], margin[cost] (= retail − cost), marginPct[cost], dealerMargin[cost] (= dealer − cost), belowCost[cost] }` + `costVisible`. Items with no price have `retailPrice: null`.
- **`POST /publish`** — perm `inventory.pricing.manage`. Body `{ effectiveFrom (≥ today), note?, entries: [ { stockItemId, retailPrice, dealerPrice } ] (1–1000) }`. Rules: item exists and isn't a variant parent (`400 ITEM_HAS_VARIANTS` — price the variants); `dealerPrice ≤ retailPrice` → `400 DEALER_PRICE_ABOVE_RETAIL`; a row already exists for that item and date → `409 PRICE_ALREADY_SET_FOR_DATE`. One `batch_ref = next("PL")` for the publish. Emits `inventory.price-list.published` (payload: item count, effectiveFrom — not the prices). Response `{ batchRef, effectiveFrom, count, belowCostCount[cost] }`.
- **`PUT /entries/{priceId}`** — change a **future-dated** row only (`effective_from > today`) → else `409 PRICE_ALREADY_EFFECTIVE`.
- **`GET /api/v1/inventory/stock-items/{id}/prices`** — full history for one item.
- Shared reader for other modules (inventory is OPEN): `inventory.repository.PriceListRepository.currentPrice(stockItemId, date): PriceRef?` and `.currentPrices(ids, date)`.

### 4.13 Drivers — `/api/v1/inventory/drivers`

`GET` (perm `inventory.drivers.read`, query `status`, `q`), `POST` / `PUT /{id}` (perm `inventory.drivers.manage`, body `{ name, phone?, vehicleNumber?, licenceNumber? }`, `409 DUPLICATE_NAME` on same name+vehicle), `PUT /{id}/status`.

### 4.14 General Transfer Report — `GET /api/v1/showroom/reports/transfers` [paged]

Perm `showroom.stock.read`. Query: `route` = `SHOWROOM_TO_SHOWROOM` | `FACTORY_TO_SHOWROOM` | `SHOWROOM_TO_FACTORY` | `ALL` (default `ALL`), `sourceLocationId`, `destinationLocationId`, `driverId`, `status` (`IN_TRANSIT` = DISPATCHED|PARTIALLY_RECEIVED, `RECEIVED`), `from`, `to` (on `transfer_date`), `q` (waybill CODE, product TRIGRAM).
Route is derived from the root types of source and destination. Location scope: a single-location user sees transfers where either end is their location.
Row (one per transfer line): `{ transferId, date, waybillRef, source, sourceType, destination, destinationType, driver, vehicle, productCode, product, qtySent, qtyReceived, qtyRejected, unit, receivedDate | null ("Not yet"), status ('In transit'|'Partially received'|'Received') }`.
Header: `{ transfers (distinct waybills), totalQuantity, inTransit, locationsInvolved }`.

---

## 5. Exposed to other modules — `com.zifiq.showroom.spi` (read-only)

```kotlin
interface ShowroomQueries {
    fun returns(filter: ReturnFilter): List<ReturnLineRow>             // doc 10, doc 13
    fun stockEntries(filter: StockEntryFilter): List<StockEntryLineRow> // doc 10 input-VAT, doc 13 outsource report
    fun showroom(id: UUID): ShowroomRef                                  // name/address/VAT for documents
}
```

Everything stock-related other modules need (balances, ledger, prices, drivers, transfers) is read directly from OPEN `inventory`.

---

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| S1 | Stock isn't deducted from the source unless the transfer is dispatched; destination is credited only on confirmed receipt | PRD 5.2.1/5.2.7 acceptance criteria | shipped transfer flow |
| S2 | Accepted + rejected must reconcile with what was dispatched before receipt is confirmed | `ReceiveProduct.jsx` | §4.2 |
| S3 | Rejected goods return to the source; nothing vanishes from the books | derived (shipped gap) | §4.2 `TRANSFER_REJECTED_IN` |
| S4 | Every transfer has a unique waybill | PRD §6 rule 6 | shipped `next("WB")` + registry |
| S5 | No transfer/adjustment/return beyond available stock; no adjustment below zero | PRD §6 rule 2; `ShowroomAdjustment.jsx` | shipped checks, per area |
| S6 | Adjustments only by Stock Keeper, with a reason; above threshold needs Admin OTP | PRD §6 rules 4, 8 | shipped |
| S7 | Direct-entry goods are held against their showroom only, not added to the company register | `StockEntryDirect.jsx` note | §2.2, §4.4 |
| S8 | Outsource Stock accepts only registered outsourced products; unit cost is the registered cost | PRD 5.2.5; `OutsourceStock.jsx` | §4.5 |
| S9 | Non-dealer return with no record → temporary customer + wallet, removed when spent | PRD 5.2.8, §6 rule 10 | §4.8 → doc 04 |
| S10 | Return value credited at the current selling price; resaleable re-enters stock, write-offs are a loss | `ReturnReports.jsx` footnote | §4.8 |
| S11 | Wallets never go negative | PRD §6 rule 5 | doc 04 |
| S12 | Showroom cost columns hidden from unauthorised roles (and zeroed in the API) | PRD 5.2.6, §6 rule 3 | doc 03 §14 |
| S13 | A showroom can't be created with missing fields or a duplicate name; codes are system-generated and immutable | `ShowroomRegister.jsx` footnote | §4.1 |
| S14 | A Pending showroom can receive stock but cannot transact | `ShowroomRegister.jsx` callout | §4.1 status meaning |
| S15 | Quarantined returns are never sellable | derived from condition "Quarantine" | §4.8, doc 09 |
| S16 | Users act only on their assigned showroom (and its areas) | PRD §6 rule 12 | `LocationAccessService` |
| S17 | Dealer price never exceeds retail price | derived (a dealer price is a discount) | DB check + §4.12 |

---

## 7. Errors added

`RECEIPT_EXCEEDS_SENT` 400 · `RECEIPT_NOT_RECONCILED` 400 · `SAME_LOCATION` 400 · `NOT_SAME_LOCATION` 400 · `LOCATION_NOT_TRADING` 409 · `LOCATION_SUSPENDED` 409 · `AREA_HAS_STOCK` 409 · `ITEM_LOCATION_RESTRICTED` 400 · `ITEM_NOT_OUTSOURCED` 400 · `OUTSOURCE_COST_NOT_SET` 400 · `INVALID_EXPIRY` 400 · `NO_ADJUSTMENT_NEEDED` 400 · `NOT_A_DEALER` 400 · `USE_DEALER_RETURN` 400 · `PRICE_NOT_SET` 400 · `RETURN_NOT_FOUND` 404 · `RETURN_ALREADY_REVERSED` 409 · `GROUP_MIXED_UNITS` 400 · `DEALER_PRICE_ABOVE_RETAIL` 400 · `PRICE_ALREADY_SET_FOR_DATE` 409 · `PRICE_ALREADY_EFFECTIVE` 409 · `SHOWROOM_NOT_FOUND` 404 · `DRIVER_NOT_FOUND` 404.

---

## 8. Event catalog

| Event | When | Notifies |
|---|---|---|
| `showroom.showroom.registered` / `.updated` / `.status-changed` | §4.1 | — |
| `showroom.area.created` / `.updated` / `.status-changed` | §4.1 | — |
| `inventory.transfer.received` (payload gains `rejectedQty`) | §4.2 | rejected > 0 → in-app holders of `inventory.transfers.manage` at the source |
| `showroom.direct-entry.posted` | §4.4 | — |
| `showroom.outsource-receipt.posted` | §4.5 | — |
| `showroom.area-move.posted` | §4.7 | — |
| `showroom.return.posted` | §4.8 | write-off value > 0 → in-app holders of `showroom.returns.read` with Management role scope ("RT-458: ₦18,000 written off at Ikeja") |
| `showroom.return.reversed` | §4.8 | — |
| `inventory.price-list.published` | §4.12 | in-app holders of `inventory.pricing.read` at every showroom ("New prices effective 12 Oct — 140 items") |
| `inventory.driver.registered` / `.updated` | §4.13 | — |

---

## 9. Search & filters

Implements doc 03 §4–§5 (advanced `POST …/search`, `/filters` metadata, facets, highlighting, saved views, exports with identical filters). Location filters on a showroom include its storage areas; every list is intersected with the caller's location scope. `[cost]` fields are never filterable, sortable or searchable for callers without `factory.costing.read` (doc 03 §5.6).

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /showroom/showrooms` (unpaged) · `showroom.register` | `code` REFERENCE, `name`/`city`/`manager_name` TRIGRAM, `phone` PHONE | `status` multi, `stateCode` multi, `hasAreas`, `dateOpened` range, `vatEnabled` | `status`, `state` | `name`; `code`, `dateOpened` | `{ active, pendingOpening, suspended }` |
| Incoming consignments = `GET /inventory/stock/transfers` · `inventory.transfers` | `waybill_ref` REFERENCE, source location name TRIGRAM, driver name TRIGRAM, line product name TRIGRAM (collection `EXISTS`) | `toLocationId`/`fromLocationId` LOCATION, `status` multi, `sourceType` (FACTORY/SHOWROOM), `driverId`, `transferDate` range, `hasRejections`, `lines.stockItemId` (advanced: "consignments containing item X ≥ n") | `status`, `source` | `transferDate desc`; `waybillRef`, `receivedDate` | `{ consignments, unitsOutstanding }` |
| `GET /showroom/direct-entries` [paged] · `showroom.direct-entries` | `ref` REFERENCE, `partner_reference` CODE, line `description` TRIGRAM, `supplier_name` TRIGRAM | `locationId`, `supplierId`, `receivedDate` range/`period`, `enteredBy`, `hasBatch`, `expiryDate` range, `inputVatRate` | `supplier`, `location` | `receivedDate desc`; `ref` | `{ entries, lines, units, totalCost[cost], inputVat }` |
| `GET /showroom/outsource-receipts` [paged] · `showroom.outsource` | `ref` REFERENCE, `partner_reference` CODE, product name TRIGRAM, partner name TRIGRAM, `batch_no` CODE | `locationId`, `manufacturerId`, `stockItemId`, `receivedDate` range, `expiryDate` range (`expiryDate.lte=` = expiring soon), `checkedBy` | `partner`, `location` | `receivedDate desc`; `expiryDate` | `{ receipts, units, totalCost[cost] }` |
| Adjustments = `GET /inventory/quantity-adjustments` [paged] · `inventory.adjustments` | `ref` REFERENCE, product code/name CODE/TRIGRAM, `note` FULLTEXT | `locationId` (root = "general" report), `direction`, `reason` multi, `stockItemId`, `performedBy`, `otpVerified`, `quantity` range, `createdAt` range | `reason`, `direction`, `location` | `createdAt desc`; `quantity` | `{ count, qtyAdded, qtyRemoved, netEffect }` |
| `GET /showroom/returns` [paged] · `showroom.returns` | `ref` REFERENCE, `original_document_ref` REFERENCE, customer code REFERENCE, customer name TRIGRAM, customer phone PHONE, product name TRIGRAM, `note` FULLTEXT | `locationId`, `returnType`, `condition` multi, `reason` multi, `customerId`, `customerType`, `status`, `lineValue` range, `createdAt` range, `creditedTo` | `reason`, `condition`, `returnType` | `date desc`; `lineValue` | `{ returns, units, valueCredited, resaleableValue, quarantineValue, writeOffValue }` + `byReason` |
| `GET /showroom/reports/comprehensive-stock` [paged] · `showroom.stock-report` | item `code` CODE, `barcode` CODE, `name` TRIGRAM | `locationId`, `areaId`, `source` multi, `categoryId` multi, `asAt`, `includeZero`, `belowReorder`, `qty` range, `hasPrice` | `source`, `group`, `area` | `group, detail`; `qty`, `retailValue`, `totalCost[cost]` | `{ lineItems, totalQuantity, totalStockValue[cost], retailValue, storageAreas }` |
| `GET /inventory/price-list` [paged] · `inventory.price-list` | `code` CODE, `name` TRIGRAM, `batch_ref` REFERENCE | `asAt`, `itemType` multi, `categoryId` multi, `locationId`, `hasPrice`, `retailPrice`/`dealerPrice` ranges, `effectiveFrom` range, `hasNextChange`, `belowCost`[cost] | `itemType`, `group` | `product`; `retailPrice`, `effectiveFrom`, `marginPct[cost]` | `{ items, priced, unpriced, belowCost[cost] }` |
| `GET /inventory/drivers` (unpaged) · `inventory.drivers` | `name` TRIGRAM, `vehicle_number` CODE, `phone` PHONE, `licence_number` CODE | `status` | `status` | `name` | — |
| `GET /showroom/reports/transfers` [paged] · `showroom.transfers` | `waybill_ref` REFERENCE, product TRIGRAM, driver TRIGRAM, vehicle CODE | `route`, `sourceLocationId`, `destinationLocationId`, `driverId`, `status`, `transferDate` range, `hasRejections`, `qtyRejected.gt` | `route`, `status` | `date desc`; `waybillRef` | `{ transfers, totalQuantity, inTransit, locationsInvolved }` |
| Group cards (`/inventory/stock/group-card`, `group-bin-card`) | — (picked by group) | `groupBy`, `groupId`, `locationId`, `from`/`to`/`period` | — | fixed chronological | header totals |

**Lookups**: `GET /api/v1/showroom/showrooms/lookup?q=&status=ACTIVE` (with areas as children); `GET /api/v1/inventory/drivers/lookup?q=`; `GET /api/v1/inventory/stock-items/lookup?q=&locationId=&itemTypes=&sellable=` (code/barcode exact first, then name; shows available qty at `locationId`; disabled rows for suspended/deleted items with the reason).

**Global search providers**: `showroom` (title name, subtitle code · city · status), `return` (title `RT-n`, subtitle customer · value · date), `direct-entry` (`DE-n`), `outsource-receipt` (`OS-n`), plus inventory's `stock-item` and `transfer` (by waybill).

**Indexes (V9)**: trigram on `locations(name)`, `showroom_stock_entry_lines(description)` (already), `customer_returns(customer_name)`, `drivers(name)` (already); btree `(tenant_id, location_id, received_date desc)` on entries; `(tenant_id, location_id, created_at desc)` and `(tenant_id, customer_id)` on returns; `(tenant_id, reason)` on return lines; `(tenant_id, original_document_ref)`; `(tenant_id, stock_item_id, effective_from desc)` on prices (already); `stock_transfers (tenant_id, to_location_id, status, transfer_date desc)` and `(tenant_id, from_location_id, transfer_date desc)`; full-text generated column on `customer_returns(note)`.

## 10. Worked example

1. Admin registers **Showroom Kano**: `POST /showrooms { name: "Showroom Kano", stateCode: "NG-KN", streetAddress: "48 Murtala Mohammed Way", city: "Kano", phone: "+234 803 000 1111", active: false }` → `SHR-4`, status `PENDING`, areas `SHR-4-SF` (Shop Floor, selling default) and `SHR-4-WH` (Warehouse, receiving default).
2. Factory A dispatches `WB-9014`: 50 cartons Citrus Drink 50cl to `SHR-4` (allowed while PENDING). Factory A's balance drops by 50 (shipped).
3. Kano receives: `POST /inventory/stock/transfers/{WB-9014}/receive { receivedDate, lines: [ { qtyReceived: 48, qtyRejected: 2, rejectionNote: "Crushed in transit" } ] }` → 48 into `SHR-4-WH` (`TRANSFER_IN`), 2 back into Factory A (`TRANSFER_REJECTED_IN`, remark "Rejected by Showroom Kano — Crushed in transit"), status `RECEIVED`; Factory A stock keepers get an in-app alert.
4. Admin activates Kano (`PUT /showrooms/{id}/status { status: ACTIVE }`) and publishes prices: `POST /inventory/price-list/publish { effectiveFrom: today, entries: [ { stockItemId: <Citrus 50cl>, retailPrice: 4500, dealerPrice: 4100 } ] }` → `PL-31`.
5. Kano moves 20 cartons to the shop floor: `POST /showroom/area-moves { fromLocationId: SHR-4-WH, toLocationId: SHR-4-SF, lines: [ { qty 20 } ] }` → `AM-7`.
6. A walk-in returns 2 cartons (one defective but resaleable, one crushed): `POST /showroom/returns { returnType: NON_DEALER, locationId: SHR-4, walkIn: { name: "Ada Nwosu", phone: "08091112222" }, lines: [ { qty 1, reason PRODUCT_DEFECT, condition RESALEABLE }, { qty 1, reason DAMAGED_IN_TRANSIT, condition DAMAGED_WRITE_OFF } ] }` → temporary customer `CU-96` created, wallet credited ₦9,000 (2 × ₦4,500), 1 carton back into `SHR-4-WH`, `write_off_value` ₦4,500, `RT-458`.
7. `GET /showroom/reports/comprehensive-stock?locationId=SHR-4` as a Stock Keeper → `costVisible: false`, cost columns 0; rows: Citrus 50cl / Warehouse / 29, Citrus 50cl / Shop Floor / 20, selling price ₦4,500.

---

## 11. Open questions (for the business, not blocking implementation)

1. **Return credit value** — current selling price (implemented, per the screen's footnote) or the price on the original receipt/invoice? Once Sales & POS and Invoicing ship, `originalDocumentRef` can be resolved and the original line price used; switching is a change confined to §4.8's price lookup.
2. **Who absorbs a write-off** — today only the wallet credit and `write_off_value` are recorded; if Finance wants it as an expense line, doc 07 can add a "Stock write-off" expense category posted automatically from `showroom.return.posted`.
3. **Direct-entry payables** — supplier invoice no. and payment terms are captured, but there is no accounts-payable module in the PRD; paying the supplier is a Running Cost entry in doc 07.

---

## 12. Verification

- Testcontainers: dispatch 50 → receive 48/2 → source balance −50+2, destination +48, ledger sums equal balances at every location.
- Receive 47/2 with full reconciliation on → `400 RECEIPT_NOT_RECONCILED`; setting off → `PARTIALLY_RECEIVED`.
- Direct entry twice with the same description → one `DIRECT_ENTRY` item restocked, not two; posting it from another showroom → `400 ITEM_LOCATION_RESTRICTED`.
- Return reversal after the wallet was spent → `400 INSUFFICIENT_WALLET_BALANCE`, nothing changes.
- Group card over a category with mixed units → `400 GROUP_MIXED_UNITS`; over a variant parent → members listed, running totals correct.
- Stock Keeper vs Administrator on the comprehensive report → identical row shape, `costVisible` false/true, cost values 0/real.
- Single-location user (Kano) → sees Kano and its areas, denied on Ikeja's areas.
- Newman folder "12 - Showroom": register, areas, receive with rejection, direct entry, outsource receipt (and the not-outsourced error), area move, counted adjustment, dealer and non-dealer returns, reversal, price publish, transfers report, group cards, waybill PDF returns `application/pdf`.
