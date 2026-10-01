# 02 — Factory Module

Status: **design finalized, implementation in progress**
Owner package: `com.zifiq.factory` (+ small real-persistence additions to `com.zifiq.settings` for Categories/Units/Suppliers, which this module depends on)
Depends on: `00-platform-foundation.md`, `01-user-tenant-access-control.md` (locations, permissions, `DocumentSequenceService`, `OtpChallengeService`, `CostVisibility`)

## 1. Scope

**In scope** (PRD §5.1, all mapped to the existing frontend screens under `zifiqqq/src/app/factory`): Stock Register, Stock Refill (finished product + raw material), Stock/Bin Card, Comprehensive Stock Report, Stock Movement Report, Quantity Adjustment (OTP-gated above threshold), Stock Transfer, Production Cost Calculator, In-House Recipes (register/production/report), Special Production, Material/Recipe Receive Report, Re-Order Report, Factory-to-Factory Trade, Total Cost for Selected Recipes.

**Explicitly deferred to the Account module pass** (these are Account-module concerns that happen to be scoped to a factory `location_id`, not Factory-module concerns — they need bank accounts/expense categories that don't exist yet): Cash Inflow/Outflow Statement (Factory), Funds Awaiting Reception, Factory SCI, Factory VAT Report (PRD §5.1.18–5.1.23).

**Side-effect dependency**: `stock_items` needs real `category_id`/`unit_of_measure_id` foreign keys, and `stock_receipts` needs real `supplier_id`/`manufacturer_id` foreign keys. `com.zifiq.settings`' `getCategories`/`addCategory`, `getUnitsOfMeasure`/`addUnitOfMeasure`, `getSuppliers`/`registerSupplier`, `getManufacturers`/`registerManufacturer` mocks are replaced with real persistence as part of this migration (small reference tables, owned by Settings per the README's module table, created here because Factory is their first real consumer — this includes all five Factory ▸ Setup screens: Factories, Suppliers, Manufacturers, Units of Measure, Categories). `SettingsService`'s remaining mock methods after this pass: none — company settings and locations are already real per doc 01, so this closes out `SettingsService` entirely.

## 1a. Bugs found and fixed during implementation (via live end-to-end testing)

Two real bugs surfaced only by actually running the full production/transfer/trade cycle against a real Postgres instance over HTTP, not by compiling or unit-testing in isolation:

1. **Comprehensive Stock Report / Re-Order Report showed nothing at a destination location after a transfer.** Both queries filtered/joined on `stock_items.location_id` (an item's *home* registration factory) instead of `stock_balances.location_id` (where it actually currently has stock). Since a stock item's home location and the locations it carries a balance at diverge the moment it's ever transferred or traded, this made received stock invisible in reports at the receiving location even though `stock_ledger_entries`/`stock_balances` were correct. Fixed in `StockItemService.comprehensiveStockReport`/`reOrderReport`: now join/filter on `StockBalancesTable.locationId`, switching to an `INNER JOIN` when a `locationId` filter is supplied (so the row only appears for locations where a balance actually exists) and `LEFT JOIN` when unfiltered (so a freshly registered, never-moved item still appears once, at zero).
2. **A finished product's cost stayed 0 in reports forever after being produced.** `stock_items.landing_cost` is set at registration time (often null/0 for a not-yet-produced finished good) and nothing updated it afterward, so the Comprehensive Stock Report kept showing 0 cost for produced finished goods even to Admin/Management, who are supposed to see it. Fixed in `ProductionService.commitProductionRun`: the target stock item's `landing_cost` is now updated to the run's `computed_unit_cost` in the same transaction as the ledger/balance writes.

Both were caught by an actual multi-location production → transfer → trade walkthrough (two factories, cross-factory transfer, cross-factory trade, OTP-gated adjustment) run over HTTP against a live database — see §5 for the same worked example.

## 2. Schema — migration `V4__factory_module.sql`

All tables below have `tenant_id uuid not null references tenants(id)`, `alter table ... enable row level security; ... force row level security;`, and a policy identical in shape to every existing table:
```sql
create policy <table>_scope on <table>
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```
Omitted below for brevity per table — assume every table has this unless noted.

### 2.1 Settings reference tables (Factory's dependency, owned by `com.zifiq.settings`)

```sql
create table categories (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(120) not null,
    type varchar(20) not null check (type in ('FINISHED_PRODUCT', 'RAW_MATERIAL', 'PACKAGING')),
    brand varchar(120),
    description varchar(300),
    created_at timestamptz not null default now(),
    unique (tenant_id, name)
);

create table units_of_measure (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,
    name varchar(80) not null,
    symbol varchar(10),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);

create table suppliers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(40) not null,
    company_name varchar(160) not null,
    contact_email varchar(320),
    phone varchar(40),
    address varchar(300),
    supplies varchar(300),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);

create table manufacturers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(40) not null,
    name varchar(160) not null,
    country varchar(80),
    contact_email varchar(320),
    phone varchar(40),
    produces varchar(300),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
```
`manufacturers` mirrors `suppliers` exactly (same RLS policy shape) — the distinction per the frontend's Factory ▸ Setup screens is that Suppliers are who you *buy raw materials from*, Manufacturers are who *makes* an outsourced/imported item (both are referenced from `stock_receipts`, see §2.11). Endpoints: `GET/POST /api/v1/settings/manufacturers`, gated the same as Suppliers (`factory.stock.manage` to create, open read). `code` auto-generates via `documentSequenceService.next("MFR")` when omitted, same pattern as Locations/Suppliers.

### 2.2 `stock_items` — product/material master
```sql
create table stock_items (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),      -- every factory has its own product range (PRD 5.1.3)
    item_type varchar(20) not null check (item_type in ('FINISHED_PRODUCT', 'RAW_MATERIAL', 'IN_HOUSE_RECIPE', 'OUTSOURCED')),
    code varchar(40) not null,
    name varchar(160) not null,
    category_id uuid references categories(id),
    brand varchar(120),
    unit_of_measure_id uuid not null references units_of_measure(id),
    reorder_level numeric(14,3) not null default 0,
    barcode varchar(64),
    landing_cost numeric(14,2),
    markup_cost numeric(14,2),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index stock_items_barcode_idx on stock_items (tenant_id, barcode) where barcode is not null;
```

### 2.3 `stock_balances` — materialized running total
```sql
create table stock_balances (
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    quantity_on_hand numeric(14,3) not null default 0,
    updated_at timestamptz not null default now(),
    primary key (location_id, stock_item_id)
);
```
**Invariant**: this table is written **only** inside the same Exposed transaction as the corresponding `stock_ledger_entries` insert(s) that produced the new total — `stock_balances.quantity_on_hand = stock_balances.quantity_on_hand + qty_in - qty_out`, via a single `UPDATE ... SET quantity_on_hand = quantity_on_hand + ?` (upserting a zero row first if absent). No code path updates this table independently of a ledger write. This is how the design guarantees the balance can never drift from the ledger's sum — it is a cache, not a second source of truth.

### 2.4 `stock_ledger_entries` — the one generic ledger every stock-affecting action writes to
```sql
create table stock_ledger_entries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    source_type varchar(30) not null check (source_type in
        ('REFILL', 'RECEIVE', 'PRODUCTION_RUN', 'TRANSFER_OUT', 'TRANSFER_IN',
         'QUANTITY_ADJUSTMENT', 'TRADE_OUT', 'TRADE_IN')),
    source_reference varchar(40) not null,
    qty_in numeric(14,3) not null default 0,
    qty_out numeric(14,3) not null default 0,
    unit_cost numeric(14,2),
    remark varchar(300),
    entry_date date not null,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create index stock_ledger_entries_item_idx on stock_ledger_entries (tenant_id, location_id, stock_item_id, entry_date);
```
Every Stock Card / Bin Card / Stock Movement report reads from this single table, filtered/grouped differently — no per-feature ledger table. `qty_in`/`qty_out` are never both non-zero on the same row (one row = one directional movement; a transfer's dispatch and a transfer's receipt are two separate rows, `TRANSFER_OUT` at source and `TRANSFER_IN` at destination).

### 2.5 `recipes` / `recipe_ingredients`
```sql
create table recipes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    code varchar(40) not null,
    name varchar(160) not null,
    description varchar(300),
    sourcing varchar(20) not null check (sourcing in ('IN_HOUSE_PRODUCTION', 'DIRECT_IMPORTATION')),
    yield_qty numeric(14,3) not null,
    yield_unit_id uuid not null references units_of_measure(id),
    shelf_life_days integer,
    reorder_level numeric(14,3),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);

create table recipe_ingredients (
    id uuid primary key default gen_random_uuid(),
    recipe_id uuid not null references recipes(id) on delete cascade,
    raw_material_stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null,
    unit_id uuid not null references units_of_measure(id)
);
-- RLS via EXISTS against recipes (same pattern as role_permissions -> tenant_roles in V1):
alter table recipe_ingredients enable row level security;
alter table recipe_ingredients force row level security;
create policy recipe_ingredients_scope on recipe_ingredients
    using (exists (select 1 from recipes r where r.id = recipe_ingredients.recipe_id
        and r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid))
    with check (exists (select 1 from recipes r where r.id = recipe_ingredients.recipe_id
        and r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid));
```

### 2.6 `production_costings` — Production Cost Calculator output
```sql
create table production_costings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    target_stock_item_id uuid not null references stock_items(id),
    recipe_id uuid references recipes(id),                 -- null when QC selects ad hoc materials instead of a registered recipe
    batch_size_qty numeric(14,3) not null,
    materials_snapshot jsonb not null,                       -- [{stockItemId, name, quantity, unitCost}]
    overhead_snapshot jsonb not null default '[]'::jsonb,    -- [{label, amount}] (labour, energy, packaging, overhead allocation)
    computed_unit_cost numeric(14,2) not null,
    computed_total_cost numeric(14,2) not null,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    computed_by_user_id uuid not null references app_users(id),   -- QC — never shown the resulting cost via the API
    approved_by_user_id uuid references app_users(id),
    approved_at timestamptz,
    rejection_reason varchar(300),
    created_at timestamptz not null default now()
);
```

### 2.7 `production_runs`
```sql
create table production_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    batch_ref varchar(40) not null,
    production_costing_id uuid not null references production_costings(id),
    quantity_produced numeric(14,3) not null,
    unit_landing_cost numeric(14,2) not null,
    total_landing_cost numeric(14,2) not null,
    materials_used jsonb not null,                            -- snapshot copied from the costing at commit time
    run_by_user_id uuid not null references app_users(id),
    run_at timestamptz not null default now(),
    unique (tenant_id, batch_ref)
);
```
A run may only reference a costing with `status = 'APPROVED'` — enforced in the service layer (Exposed doesn't do conditional FKs), not the schema.

### 2.8 `special_production_clients` / `special_production_runs`
```sql
create table special_production_clients (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(160) not null,
    terms varchar(300),
    created_at timestamptz not null default now()
);

create table special_production_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    order_ref varchar(40) not null,
    batch_ref varchar(40) not null,
    client_id uuid not null references special_production_clients(id),
    target_stock_item_id uuid references stock_items(id),
    product_description varchar(160),                          -- fallback when the special product isn't a registered stock_item
    quantity numeric(14,3) not null,
    unit_id uuid not null references units_of_measure(id),
    unit_cost numeric(14,2) not null,
    price numeric(14,2) not null,
    materials_snapshot jsonb not null default '[]'::jsonb,
    status varchar(20) not null default 'IN_PRODUCTION' check (status in ('IN_PRODUCTION', 'AWAITING_DISPATCH', 'DELIVERED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
```

### 2.9 `stock_transfers` / `stock_transfer_lines`
```sql
create table stock_transfers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    waybill_ref varchar(40) not null,
    source_location_id uuid not null references locations(id),
    destination_location_id uuid not null references locations(id),
    driver_name varchar(120),
    driver_vehicle varchar(60),
    dispatched_by_user_id uuid not null references app_users(id),
    dispatched_at timestamptz not null default now(),
    received_by_user_id uuid references app_users(id),
    received_at timestamptz,
    status varchar(20) not null default 'DISPATCHED' check (status in ('DISPATCHED', 'PARTIALLY_RECEIVED', 'RECEIVED')),
    unique (tenant_id, waybill_ref)
);

create table stock_transfer_lines (
    id uuid primary key default gen_random_uuid(),
    stock_transfer_id uuid not null references stock_transfers(id) on delete cascade,
    stock_item_id uuid not null references stock_items(id),
    qty_sent numeric(14,3) not null,
    qty_received numeric(14,3),
    qty_rejected numeric(14,3),
    unit_price numeric(14,2)                                    -- "Authorized Selling Price" per Stock Movement Report's Supplied Report
);
-- RLS via EXISTS against stock_transfers, same pattern as §2.5.
```
`destination_location_id` is always a `locations` row in this pass (factory→factory or factory→showroom); factory→dealer transfers are out of scope until the Customers module exists.

### 2.10 `quantity_adjustments`
```sql
create table quantity_adjustments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    ref varchar(40) not null,
    stock_item_id uuid not null references stock_items(id),
    direction varchar(10) not null check (direction in ('POSITIVE', 'NEGATIVE')),
    quantity numeric(14,3) not null,
    reason varchar(30) not null check (reason in
        ('STOCKTAKE_VARIANCE', 'DAMAGED_BROKEN', 'EXPIRED_WRITEOFF', 'SUSPECTED_THEFT',
         'MISCOUNT_CORRECTION', 'UNRECORDED_RETURN', 'OTHER')),
    note varchar(300),
    otp_challenge_id uuid references otp_challenges(id),
    performed_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
```

### 2.11 `stock_receipts` — Material/Recipe Receive Report + Raw Material Refill (same feature, one table)
```sql
create table stock_receipts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    ref varchar(40) not null,
    stock_item_id uuid not null references stock_items(id),
    supplier_id uuid references suppliers(id),
    manufacturer_id uuid references manufacturers(id),
    quantity numeric(14,3) not null,
    unit_cost numeric(14,2) not null,
    manufactured_date date,
    expiry_date date,
    verified_by_user_id uuid references app_users(id),
    offloaded_at timestamptz,
    condition varchar(30) not null default 'PENDING_VERIFICATION' check (condition in
        ('ACCEPTED', 'ACCEPTED_WITH_EXCEPTIONS', 'PENDING_VERIFICATION')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
```

### 2.12 `factory_trade_offers`
```sql
create table factory_trade_offers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(40) not null,
    from_location_id uuid not null references locations(id),
    to_location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null,
    reason varchar(20) not null check (reason in ('SURPLUS', 'EXPIRY', 'CHANGEOVER', 'SCHEDULE', 'DISCONTINUED')),
    offered_by_user_id uuid not null references app_users(id),
    offered_at timestamptz not null default now(),
    expires_at timestamptz not null,
    responded_at timestamptz,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING', 'OPEN', 'ACCEPTED', 'REJECTED', 'EXPIRED')),
    responded_by_user_id uuid references app_users(id),
    unique (tenant_id, ref)
);
```

### 2.13 `permission_catalog` additions
```sql
insert into permission_catalog (code, description) values
    ('factory.recipes.manage', 'Register and edit in-house recipes'),
    ('factory.production.manage', 'Run the production cost calculator and commit production runs'),
    ('factory.costing.read', 'View computed landing/unit cost figures'),
    ('factory.costing.approve', 'Approve or reject a production costing'),
    ('factory.transfers.manage', 'Dispatch and receive stock transfers'),
    ('factory.adjustments.manage', 'Post quantity adjustments'),
    ('factory.trade.manage', 'Offer and respond to factory-to-factory trades');
```
`factory.stock.read`/`factory.stock.manage` already exist (V1) and are reused unchanged: `.read` covers stock items/balances/ledger/comprehensive-report/re-order-report/movement-report (cost columns masked separately by `.costing.read`); `.manage` covers registering `stock_items`, posting finished-product refills, and posting/verifying `stock_receipts`. Update `RoleDefaults` (doc 01 §2) per the final matrix there.

## 3. Endpoints (`factory/api/FactoryInventoryController.kt`, split into focused controllers if the file grows past ~150 lines — e.g. `RecipeController`, `ProductionController`, `TransferController`, `TradeController` — all under `/api/v1/factory/**`)

Every endpoint: `@SecurityRequirement(name = "bearerAuth")`, returns `ApiEnvelope<T>`, `@PreAuthorize("@permissionAuthorizer.hasPermission(authentication, '<code>')")`, location-scoped per doc 01 §5 (a caller with `SINGLE_LOCATION` scope may only act on/see that `location_id`; `ALL_LOCATIONS` scope is unrestricted).

### 3.1 Stock Register
- `POST /api/v1/factory/stock-items` — perm `factory.stock.manage`. Req: `{ locationId, itemType, code?(auto RF-prefix-free "ITM-" if blank), name, categoryId?, brand?, unitOfMeasureId, reorderLevel?, barcode?, landingCost?, markupCost? }`. Resp: `StockItemSummary { id, locationId, itemType, code, name, categoryId, categoryName, brand, unitOfMeasureId, unitSymbol, reorderLevel, barcode, landingCost (masked), markupCost (masked), status, createdAt }`. Errors: `409 DUPLICATE_CODE`, `404 CATEGORY_NOT_FOUND`, `404 UNIT_NOT_FOUND`.
- `GET /api/v1/factory/stock-items?locationId=&itemType=&status=` — perm `factory.stock.read`. Resp: `List<StockItemSummary>`.
- `PUT /api/v1/factory/stock-items/{id}/status` — perm `factory.stock.manage`. Body `{ status: "ACTIVE"|"SUSPENDED" }` — backs the Recycle Bin's "suspend sales of a product" pattern for this module (full Recycle Bin history lives in the Process Guide module later; this is just the on/off switch here).

### 3.2 Stock Refill
- `POST /api/v1/factory/stock/refill/finished-product` — perm `factory.stock.manage`. Req: `{ locationId, stockItemId, quantity: Positive, note? }`. Logic: `ref = documentSequenceService.next("RF")`; insert one `stock_ledger_entries` row (`source_type=REFILL, qty_in=quantity, source_reference=ref`); update `stock_balances`. Resp: `RefillSummary { ref, stockItemId, productName, quantity, unit, performedBy, at }`. Error: `400 ITEM_SUSPENDED` if the stock item's status isn't ACTIVE.
- `POST /api/v1/factory/stock/refill/raw-material` — perm `factory.stock.manage`. Req: `{ locationId, stockItemId, supplierId?, manufacturerId?, quantity: Positive, unitCost: Positive, manufacturedDate?, expiryDate? }` — `manufacturerId` references the `manufacturers` register (§2.1), registered separately via `POST /api/v1/settings/manufacturers`. Logic: `ref = documentSequenceService.next("DL")`; insert `stock_receipts` (`condition='PENDING_VERIFICATION'`); insert linked `stock_ledger_entries` (`source_type=RECEIVE, qty_in=quantity, unit_cost, source_reference=ref`); update balance. Resp: `StockReceiptSummary` (see §3.9).

### 3.3 Stock/Bin Card
- `GET /api/v1/factory/stock/card/{stockItemId}?locationId=&from=&to=` — perm `factory.stock.read`. Resp: `List<StockCardRow> { sn, date, remark, qtyIn, qtyOut, runningBalance }` — one row per `stock_ledger_entries` record for that item/location, ordered by `entry_date, created_at`, `runningBalance` computed as a running sum (window function) over the filtered range starting from the balance immediately before `from`.
- `GET /api/v1/factory/stock/bin-card/{stockItemId}?locationId=&from=&to=` — perm `factory.stock.read`. Resp: `List<BinCardRow> { sn, date, qtyIn, qtyOut, balance }` — same source, grouped/summed **by day** instead of per-transaction (PRD 5.1.4–5.1.6 distinguishes Stock Card = per-transaction, Bin Card = daily totals).

### 3.4 Comprehensive Stock Report
- `GET /api/v1/factory/stock/comprehensive-report?locationId=&view=CATEGORY|GROUP` — perm `factory.stock.read`. `view=CATEGORY` (PRD Part 1, all item types): `List<ComprehensiveStockRow> { lineNumber, category, stockDetail, unitCost, quantityLeft, totalCost }`. `view=GROUP` (PRD Part 2, finished products only — reuses the same `category_id` grouping column, PRD's "Group" label is this same field scoped to `item_type='FINISHED_PRODUCT'`, no separate grouping table): same row shape with `category` renamed `group` in the JSON key. `unitCost`/`totalCost` = `costVisibility.maskIfUnauthorized(value, hasPermission('factory.costing.read'), BigDecimal.ZERO)` — **zeroed, not omitted**, for callers without `factory.costing.read` (PRD §6 rule 3).

### 3.5 Stock Movement Report
- `GET /api/v1/factory/stock/movement-report/production?locationId=&from=&to=` — perm `factory.stock.read`. Resp: `List<ProductionMovementRow> { productionDate, batchNumber, productDescription, quantityProduced, unitLandingCost, totalLandingCost, rawMaterialsUsed }` from `production_runs` (cost fields masked per `.costing.read`).
- `GET /api/v1/factory/stock/movement-report/supplied?locationId=&from=&to=` — perm `factory.stock.read`. Resp: `List<SuppliedMovementRow> { waybillNumber, driverName, distributionDate, receivedDate, productDescription, quantitySupplied, quantityReceived, quantityRejected, authorizedSellingPrice }` from `stock_transfers`/`stock_transfer_lines`.
- `GET /api/v1/factory/stock/movement-report/raw-materials?locationId=&stockItemId=&from=&to=` — perm `factory.stock.read`. Resp: `List<RawMaterialMovementRow> { openingQuantity, openingDate, quantityUsed, costOfMaterialsUsed, remainingQuantity, costOfRemainingMaterials, dailyArrivals }` derived from `stock_ledger_entries` for `item_type='RAW_MATERIAL'` items (qty_out aggregated as "used", qty_in from `RECEIVE` rows as "arrivals").

### 3.6 Quantity Adjustment (OTP-gated)
- `POST /api/v1/factory/quantity-adjustments` — perm `factory.adjustments.manage`. Req: `{ locationId, stockItemId, direction, quantity: Positive, reason, note?, otpChallengeId? }`. Logic: threshold is currently a flat constant (`50` units, `FactoryOperationsService.ADJUSTMENT_OTP_THRESHOLD`) — exact threshold is a tenant-configurable setting to be added when the Process Guide module's config screens land. If `quantity > threshold`: require `otpChallengeId` to reference an `otp_challenges` row with `status='USED'`, `purpose='QUANTITY_ADJUSTMENT'`, and `requested_by_user_id` equal to the calling actor (`OtpChallengeService.isVerified(challengeId, purpose)` — simplified from an earlier draft that also matched an arbitrary client-chosen `target_reference` string, which was fragile to coordinate across the request/issue/verify/adjust call sequence; requester-identity + purpose + `USED` status is sufficient, since the OTP still required Admin review and issuance) — else `403 OTP_REQUIRED`. On success: `ref = documentSequenceService.next("QA")`; insert `quantity_adjustments`; insert matching `stock_ledger_entries` (`source_type=QUANTITY_ADJUSTMENT`, `qty_in`/`qty_out` per `direction`); update balance. Resp: `QuantityAdjustmentSummary { ref, stockItemId, direction, quantity, reason, note, performedBy, at }`.
- `GET /api/v1/factory/quantity-adjustments?locationId=&from=&to=` — perm `factory.stock.read`.

### 3.7 Stock Transfer
- `POST /api/v1/factory/stock/transfers` — perm `factory.transfers.manage`. Req: `{ sourceLocationId, destinationLocationId, driverName?, driverVehicle?, lines: [{ stockItemId, quantity: Positive, unitPrice? }] }`. Logic: `ref = documentSequenceService.next("WB")`; validate each line's `quantity <= current stock_balances.quantity_on_hand` at source (else `400 INSUFFICIENT_STOCK`); insert `stock_transfers` + `stock_transfer_lines`; for each line, insert `stock_ledger_entries` (`source_type=TRANSFER_OUT, qty_out=quantity`) at the source and decrement its balance — **destination balance is not touched yet**. Resp: `StockTransferSummary`.
- `POST /api/v1/factory/stock/transfers/{id}/receive` — perm `factory.transfers.manage`, caller must be scoped to the transfer's `destination_location_id` (or `ALL_LOCATIONS`). Req: `{ lines: [{ stockTransferLineId, qtyReceived, qtyRejected? }] }`. Logic: update each line's `qty_received`/`qty_rejected`; insert `stock_ledger_entries` (`source_type=TRANSFER_IN, qty_in=qtyReceived`) at destination and increment its balance; set `stock_transfers.status` = `RECEIVED` if every line fully reconciled (`qty_received + qty_rejected == qty_sent` for all lines) else `PARTIALLY_RECEIVED`. Resp: `StockTransferSummary`.
- `GET /api/v1/factory/stock/transfers?locationId=&status=` — perm `factory.transfers.manage` or `factory.stock.read` (either permission suffices to view).

### 3.8 Production Cost Calculator / In-House Recipes / Production Runs
- `POST /api/v1/factory/recipes` — perm `factory.recipes.manage`. Req: `{ locationId, code?, name, description?, sourcing, yieldQty, yieldUnitId, shelfLifeDays?, reorderLevel?, ingredients: [{ rawMaterialStockItemId, quantity, unitId }] }`. Resp: `RecipeSummary { id, code, name, sourcing, yieldQty, ingredients: [...] }`.
- `GET /api/v1/factory/recipes?locationId=&status=` — perm `factory.stock.read`.
- `POST /api/v1/factory/production/cost-calculator` — perm `factory.production.manage` (QC runs this "blind"). Req: `{ locationId, targetStockItemId, recipeId?, batchSizeQty: Positive, materials: [{ stockItemId, quantity }] (defaults to recipe's ingredients scaled to batchSizeQty when recipeId is set and this is omitted), overheads: [{ label, amount }] }`. Logic: `computedUnitCost = (Σ materials[i].quantity * stock_items[i].landingCost + Σ overheads.amount) / batchSizeQty`; persist as `production_costings` with `status=PENDING`. Resp: `ProductionCostingSummary { id, targetStockItemId, batchSizeQty, status, computedByUserId, createdAt }` — **`computedUnitCost`/`computedTotalCost` are omitted from the response entirely for the calling QC user** (not zeroed — this endpoint's caller is definitionally QC, who the PRD says must complete costing "blind," never seeing the figure their own calculation produced).
- `GET /api/v1/factory/production/cost-calculator/{id}` — perm `factory.costing.read` (Management/Admin only — this is the one endpoint that reveals the figure QC just produced). Resp: full `ProductionCostingDetail` including `computedUnitCost`, `computedTotalCost`, `materialsSnapshot`, `overheadSnapshot`.
- `POST /api/v1/factory/production/cost-calculator/{id}/approve` — perm `factory.costing.approve`. Body `{}`. Sets `status=APPROVED, approved_by_user_id, approved_at`. Errors: `409 ALREADY_DECIDED`.
- `POST /api/v1/factory/production/cost-calculator/{id}/reject` — perm `factory.costing.approve`. Body `{ rejectionReason }`. Sets `status=REJECTED`.
- `POST /api/v1/factory/production/runs` — perm `factory.production.manage`. Req: `{ productionCostingId }`. Logic: costing must be `APPROVED` (else `409 COSTING_NOT_APPROVED`); `batchRef = documentSequenceService.next("PB")`; insert `production_runs` copying `quantity_produced=batchSizeQty, unit_landing_cost=computedUnitCost, total_landing_cost=computedTotalCost, materials_used=materialsSnapshot`; for each material line insert `stock_ledger_entries` (`source_type=PRODUCTION_RUN, qty_out`) and decrement its balance; insert one `stock_ledger_entries` row for `target_stock_item_id` (`qty_in=quantity_produced`) and increment its balance. Resp: `ProductionRunSummary { batchRef, targetStockItemId, quantityProduced, unitLandingCost (masked), totalLandingCost (masked), runAt }`.
- `GET /api/v1/factory/production/runs?locationId=&from=&to=` — perm `factory.stock.read` (cost fields masked per `.costing.read`).
- Recipe stock card: `GET /api/v1/factory/recipes/{id}/card` — perm `factory.stock.read`, same shape as §3.3, filtered to ledger entries where `stock_item_id` is the recipe's yielded `stock_item` (for in-house-produced recipes that are themselves tracked as a `stock_items` row with `item_type=IN_HOUSE_RECIPE`).
- `GET /api/v1/factory/production/total-cost?recipeIds=&from=&to=` — perm `factory.costing.read` ("Total Cost for Selected Recipes," PRD 5.1.27). Resp: `List<RecipeCostAggregate> { recipeId, recipeName, unit, qtyUsed, unitCost, qtyOnHand }` aggregated from `production_runs`/`recipe_ingredients` over the date range.

### 3.9 Material/Recipe Receive Report
- `GET /api/v1/factory/stock/receipts?locationId=&from=&to=&condition=` — perm `factory.stock.read`. Resp: `List<StockReceiptSummary> { ref, stockItemId, itemName, itemTypeLabel ("Raw material"|"In-house recipe"), supplierId, supplierName, manufacturerId, manufacturerName, quantity, unit, manufacturedDate, expiryDate, verifiedBy, offloadedAt, condition }`.
- `POST /api/v1/factory/stock/receipts/{id}/verify` — perm `factory.stock.manage`, caller must hold the Stock Keeper role (verification is explicitly a Stock Keeper action per PRD). Body `{ condition: "ACCEPTED"|"ACCEPTED_WITH_EXCEPTIONS" }`. Sets `verified_by_user_id`, `offloaded_at=now()`.

### 3.10 Re-Order Report
- `GET /api/v1/factory/stock/re-order-report?locationId=` — perm `factory.stock.read`. Resp: `List<ReOrderRow> { stockItemId, itemName, categoryName, quantityOnHand, reorderLevel, unit, supplierId?, supplierName? }` — rows where `stock_balances.quantity_on_hand <= stock_items.reorder_level`. When a row crosses this threshold (checked on every ledger-affecting write, not just report reads), the write's transaction also inserts a `notification_outbox` PUSH row addressed to holders of `factory.stock.read` scoped to that location (PRD notification table: "Stock reaches re-order point → Stock Personnel/Admin → In-app alert").

### 3.11 Factory-to-Factory Trade
- `POST /api/v1/factory/trades/offer` — perm `factory.trade.manage`. Req: `{ fromLocationId, toLocationId, stockItemId, quantity: Positive, reason, expiresAt }`. Logic: `ref = documentSequenceService.next("FT")`; insert `factory_trade_offers` (`status=PENDING`); write `notification_outbox` to the destination factory's members holding `factory.trade.manage`. Resp: `FactoryTradeOfferSummary`.
- `POST /api/v1/factory/trades/{id}/respond` — perm `factory.trade.manage`, caller scoped to `to_location_id`. Body `{ decision: "ACCEPTED"|"REJECTED" }`. On `ACCEPTED`: validate source still has sufficient balance; insert `stock_ledger_entries` `TRADE_OUT` at `from_location_id` and `TRADE_IN` at `to_location_id`, update both balances, in one transaction. Sets `status`, `responded_by_user_id`, `responded_at`.
- `GET /api/v1/factory/trades?locationId=&status=` — perm `factory.trade.manage`.
- A scheduled sweep (reuse `NotificationOutboxDispatcher`'s `@Scheduled` cadence or a second lightweight `@Scheduled` job) flips `PENDING`/`OPEN` offers past `expires_at` to `EXPIRED`.

### 3.12 Special Production
- `POST /api/v1/factory/special-production/clients` — perm `factory.production.manage`. Req: `{ name, terms? }`.
- `POST /api/v1/factory/special-production/runs` — perm `factory.production.manage`. Req: `{ locationId, clientId, targetStockItemId?, productDescription?, quantity, unitId, unitCost, price, materials: [{stockItemId, quantity}] }`. Logic: `orderRef = next("SO")`, `batchRef = next("SB")`; on materials consumption, same ledger-write pattern as §3.8's production run (`qty_out` per material). Resp: `SpecialProductionRunSummary`.
- `PUT /api/v1/factory/special-production/runs/{id}/status` — perm `factory.production.manage`. Body `{ status: "IN_PRODUCTION"|"AWAITING_DISPATCH"|"DELIVERED" }`.
- `GET /api/v1/factory/special-production/runs?locationId=&clientId=` — perm `factory.stock.read`.

## 4. Business rules carried into this module (source-traced)

- "No transactions against suspended/deleted/inactive products" — PRD §6 rule 1. Every write endpoint checks `stock_items.status = 'ACTIVE'` before posting a ledger entry; violation → `400 ITEM_SUSPENDED`.
- "No stock transfer/sale/adjustment exceeding available source quantity" — PRD §6 rule 2. Enforced in `stock/transfers` (source line quantities) and implicitly in `quantity-adjustments` with `direction=NEGATIVE` (quantity can't exceed current balance) → `400 INSUFFICIENT_STOCK`.
- "Landing cost/unit cost visible only to Admin/Management, others see zero" — PRD §6 rule 3, and specifically "QC completes costing blind" (PRD §5.1.11 narrative) — implemented via `CostVisibility` (zeroed in reports) vs. the Cost Calculator's stricter **field omission** for the QC caller specifically (§3.8) — two different masking strengths for two different situations, both sourced from the same PRD rule but applied at the precision the PRD actually describes (QC PRD text says QC "cannot see" the cost at all during entry, while other reports elsewhere explicitly say "display as zero").
- "Stock adjustments: Stock Keeper role only + documented reason required" — PRD §6 rule 4 — `factory.adjustments.manage` seeded only to Stock Keeper + Administrator (doc 01 §2); `reason` is a required enum field, `note` free text optional but recommended.
- "Every stock transfer generates a waybill with unique reference" — PRD §6 rule 6 — `stock_transfers.waybill_ref` via `DocumentSequenceService.next("WB")`, unique per tenant.
- "Wallet withdrawals & adjustments outside standard thresholds require Admin-issued OTP" — PRD §6 rule 8 — implemented for quantity adjustments per §3.6; the wallet-withdrawal half of this rule belongs to the Account/Customers module pass, reusing the same `otp_challenges` infrastructure from doc 01.
- "Location/role-scoped access unless elevated" — PRD §6 rule 12 — every list/read endpoint above filters by the caller's `user_location_assignments` scope (doc 01 §5) in addition to its permission-code check.
- Frontend-sourced detail: `Recipe.shelfLifeDays`, `Recipe.reorderLevel`, `sourcing` enum values, and the `StockCardRow`/`BinCardRow` column shapes are taken directly from `zifiqqq/src/app/sampleProcess.js`/`sampleShowroom.js`'s stock-card samples and the `In-House Recipes — Register` screen, not just the PRD narrative — confirming the exact field names above.

## 5. Worked example — full production cycle

1. **QC** registers a recipe (`POST /factory/recipes`) — "Premium Blend Base", yields 100 pcs from 3 raw materials.
2. **QC** runs the calculator (`POST /factory/production/cost-calculator`) for a batch of 500 pcs — response has **no cost fields**, just `{ id, status: "PENDING" }`.
3. **Management** (`factory.costing.read` + `factory.costing.approve`) fetches `GET /factory/production/cost-calculator/{id}` — sees `computedUnitCost: 42.10, computedTotalCost: 21050.00` — approves it: `POST /factory/production/cost-calculator/{id}/approve`.
4. **QC** or **Stock Keeper** (`factory.production.manage`) commits the run: `POST /factory/production/runs { productionCostingId }`. Response: `{ batchRef: "PB-1042", quantityProduced: 500, ... }` — `unitLandingCost`/`totalLandingCost` masked to `0` in this response since the caller lacks `factory.costing.read`.
5. `stock_balances` for the 3 raw materials each drop by their consumed quantity; `stock_balances` for the target finished product rises by 500. Three `TRANSFER`-style ledger rows (one per material, `qty_out`) plus one `qty_in` row, all `source_reference = "PB-1042"`, all in one Exposed transaction.
6. If any raw material's resulting balance falls at/below its `reorder_level`, a `notification_outbox` row is queued for the factory's stock personnel in the same transaction.
