# 09 — Sales & POS Module

Status: **in progress** — slice 1 (till sessions, the POS cart and checkout, void, receipt, invoice on request; V15) is implemented. Slice 2: deliver-later (stock reservations, customer waybills, Pending Supply). Slice 3: Instant Discount, pro-formas, the reports and search of §4.6/§9.

**Implementation notes (slice 1), where the build differs from or adds to the design below:**
- Migration is `V15__sales_pos_tills_and_sales.sql` (V13 was taken by the scale pass). It adds the four new stock-ledger source types for all slices, `till_sessions`, `sales`, `sale_lines`, `sale_payments`, tenant-scoped composite foreign keys (V14_9) and the slice-1 permissions (`cashier.sales.read-all`, `.void`, `cashier.credit.override`, `cashier.till.supervise`).
- **Tills sell at showrooms only.** Invoices are issued by a showroom (doc 08), so a till must be a TILL cash account at a SHOWROOM (`400 INVALID_TILL` otherwise).
- **No SSE.** The live-sale stream (§4.3) would need `SseEmitter`, which `NoSseFragmentsArchitectureTest` bans (the CVE-2026-47890 exception depends on it). The till polls `GET /sales/{id}` and compares `version` — the design's own fallback. Slice 3 builds Instant Discount on that.
- **Goods leave from the showroom's default selling area** (`locations.default_selling_location_id`, set to the shop floor when a showroom is registered), or the showroom itself when none; `fromLocationId` on completion picks another area. Stock is checked twice under row locks: what the showroom has (all its areas) and what is on hand where the goods leave from.
- Completion is collect-now in this slice: every line is supplied at the till (`supply_status NOT_APPLICABLE`); the `supply` field arrives with slice 2.
- `PRICES_CHANGED`: the re-priced cart is saved in its own transaction before the 409, so the cashier reloads it and confirms.
- The same line arithmetic serves the sale and its invoice: `common.tax.LineAmounts` (invoicing now uses it too), so the two agree to the kobo — checked at issue.
- Responses carry references, never bare ids (`till`, `location`, `cashier`, `customer.customer`, `invoice`, payment `receipt`, …).
- An hourly sweep abandons carts untouched for `cashier.sale.abandonAfterHours` (default 12); `cashier.sale.voidWindowHours` defaults to 24.
- The invoicing sandbox endpoint (`POST /api/v1/invoicing/sandbox/invoices`) stays until pro-formas convert in slice 3.

Owner package: `com.zifiq.cashier` (package name kept; module display name "Sales & POS")
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi`, `settings :: spi`, `account :: spi`, `invoicing :: spi`
Depended on by: `history`, `reports`, `dashboard`, `communication` (commission reads completed sales), `ai` — all through `cashier::spi` or domain events.
Migration: `V15__sales_pos_tills_and_sales.sql` (slice 1); later slices add V15_1, V15_2
Replaces: the mock `CashierService` (`getSalesReport`, `generateProForma`, `generateWaybill`, `processPosCheckout`) and the POS part of `menu` (`MenuController`'s `cashier.sales.manage` endpoint).

---

## 1. Scope

### 1.1 Features

| Feature | PRD | Screen |
|---|---|---|
| Till sessions (open with float, close with cash count, variance) | 5.3.6 Tenderable (cashier reconciliation); POS header "Till POS-IKJ-01" | `menu/POS.jsx` header |
| POS sale: catalogue with search/scan/category, server-side cart, customer pricing, VAT, split payment, on-account credit, collect-now or deliver-later, invoice + receipt | 5.8.2 | `menu/POS.jsx` |
| Instant Discount on a live sale (Admin), ceiling approval, real-time push to the till | 5.7.11 | `others/InstantDiscount.jsx` |
| Pro-forma invoices (quotation): compose, line discounts, validity, send, decline, expire, convert to invoice | 5.4.2 | `cashier/ProFormaInvoice.jsx`, `invoicing/InvoiceGeneration.jsx` (pro-forma tab) |
| Generate (customer) Waybill: release outstanding goods against an invoice/sale, driver, partial supplies, supply history, acknowledgement | 5.4.3 | `cashier/GenerateWaybill.jsx` |
| Sales Report (daily) | 5.4.1 | `cashier/SalesReport.jsx` |
| Pending Supply Reports | 5.7.19 | `others/PendingSupplyReports.jsx` |
| Customers/Distributors Comprehensive — Distributors / No Credit / Special | 5.7.2–5.7.4 | `others/CustomersComprehensive.jsx` |
| Sale void (within window) | PRD §6 rule 14 (void-and-reissue), derived | — |
| Invoice on request for a walk-in sale; invoice generation from a POS sale | 5.10 | `invoicing/InvoiceGeneration.jsx` (POS tab) |

### 1.2 Decisions

1. **The cart lives on the server.** Instant Discount must reach "an ongoing transaction being processed by a specific cashier … in real time", so an open sale is a persisted row (`status OPEN`) that the till edits and an Administrator can see and discount. It holds no stock and moves no money until completion.
2. **Stock: reserve on sale, deduct when goods physically leave.** The stock ledger records physical movement. Goods collected at the till → `POS_SALE` out at completion. Goods sold but left for later delivery/collection → a **reservation** (inventory `stock_reservations`), deducted as `CUSTOMER_WAYBILL` out when a waybill releases them. *Available* = on hand − open reservations; every sale, transfer and trade checks available, so sold-but-uncollected goods can't be sold twice ("Stock cannot be … sold … in a quantity greater than what is currently available", rule 2), while a stocktake still counts them because they are physically there.
3. **Every sale with a registered customer issues an invoice at completion** (PRD 5.8.2 "enables the generation of invoices", 5.10 "automatic at POS checkout"). Walk-in sales issue a sale receipt; an invoice is issued for a walk-in only on request (at completion or later) with the buyer's details.
4. **Pro-forma conversion creates an order without a till.** A confirmed pro-forma becomes a `sales` row of channel `PRO_FORMA_ORDER` (same TXN number space), fully reserved, invoiced on account (or as a draft invoice), with goods released by waybills and payments recorded against the invoice (doc 08 §4.6). One model serves "Pending supply", "Generate waybill" and the reports for both origins.
5. **Payment rails.** POS methods: `CASH`, `POS` (card), `FUND_TRANSFER`, `WALLET`, `DEPOSIT`, plus `ON_ACCOUNT` (credit). Wallet is allowed for **any** customer with a wallet balance — walk-in return credits must be spendable or rule 10 ("temporary wallet removed once its balance reaches zero") could never trigger; the prototype's dealer-only restriction is a simplification. Deposit is Special Customers only. On-account needs a registered, non-temporary customer with a credit limit, within limit (doc 04 C10).
6. **The correspondent is captured at sale time** from `Correspondents.creditableFor(customer)` (PRD 5.8.2 "automatically assigns each transaction to the respective correspondent") and stored on the sale, so later reassignment doesn't move commission for past sales.
7. **Prices come from the inventory price list** (doc 05 §2.6): `dealer_price` for `AUTHORISED_DEALER`, `retail_price` for everyone else, effective on the sale date. The cashier cannot type a price. Price concessions are line discounts on pro-formas or an Instant Discount on a live sale.

---

## 2. Schema — migration `V13__sales_pos_module.sql`

All tables: `tenant_id` + standard RLS (omitted). Money `numeric(14,2)`, quantities `numeric(14,3)`.

### 2.1 Inventory changes (OPEN module, made here as its first consumer)

```sql
alter table stock_ledger_entries drop constraint stock_ledger_entries_source_type_check;
alter table stock_ledger_entries add constraint stock_ledger_entries_source_type_check check (source_type in (
    'REFILL','RECEIVE','PRODUCTION_RUN','TRANSFER_OUT','TRANSFER_IN','QUANTITY_ADJUSTMENT','TRADE_OUT','TRADE_IN',
    'TRANSFER_REJECTED_IN','DIRECT_ENTRY','OUTSOURCE_RECEIPT','CUSTOMER_RETURN','AREA_MOVE_OUT','AREA_MOVE_IN',   -- doc 05
    'POS_SALE',                 -- collected at the till (out)
    'CUSTOMER_WAYBILL',         -- released later against a sale (out)
    'CUSTOMER_WAYBILL_CANCEL',  -- a cancelled waybill puts the goods back (in)
    'SALE_VOID'                 -- a voided sale puts collected goods back (in)
));

create table stock_reservations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),      -- the selling FACTORY/SHOWROOM (root, never an area)
    stock_item_id uuid not null references stock_items(id),
    source_module varchar(30) not null,                      -- 'cashier'
    source_type varchar(30) not null check (source_type in ('SALE_LINE')),
    source_id uuid not null,                                 -- sale_lines.id
    source_ref varchar(30) not null,                         -- TXN-n
    quantity_reserved numeric(14,3) not null check (quantity_reserved > 0),
    quantity_released numeric(14,3) not null default 0 check (quantity_released >= 0),
    status varchar(10) not null default 'OPEN' check (status in ('OPEN','CLOSED','CANCELLED')),
    created_at timestamptz not null default now(),
    closed_at timestamptz,
    constraint stock_reservations_released_le check (quantity_released <= quantity_reserved),
    unique (source_type, source_id)
);
create index stock_reservations_open_idx on stock_reservations (tenant_id, location_id, stock_item_id) where status = 'OPEN';
```

`inventory` additions (code): `StockAvailability.available(locationRootId, stockItemId, forUpdate: Boolean): AvailableQty` = Σ `stock_balances.quantity_on_hand` over the root and its `STORAGE_AREA` children − Σ open `(quantity_reserved − quantity_released)` at the root; `StockReservations.reserve/release/cancel` (release posts the ledger out in the same call). Shipped transfer dispatch and factory trade dispatch switch their sufficiency check from on-hand to available (V13 code change). Quantity adjustments keep checking on-hand (a stocktake counts reserved goods, which are still physically there) but reject a negative adjustment that would leave on-hand below the open reservations → `409 STOCK_RESERVED` (details `{ reserved }`).

The Comprehensive Stock Report and the Showroom Stock Report gain a `reserved` and an `available` column next to `onHand` (no other shape change).

### 2.2 `till_sessions`

```sql
create table till_sessions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    cash_account_id uuid not null,                         -- account cash_accounts.id, kind TILL (plain uuid, cross-module)
    till_code varchar(30) not null,                        -- snapshot 'TILL-IKJ-01'
    location_id uuid not null references locations(id),    -- the till's location
    cashier_user_id uuid not null references app_users(id),
    status varchar(10) not null check (status in ('OPEN','CLOSED')),
    opened_at timestamptz not null default now(),
    opening_float numeric(14,2) not null check (opening_float >= 0),
    closed_at timestamptz,
    closed_by_user_id uuid references app_users(id),       -- the cashier, or a supervisor force-closing
    counted_cash numeric(14,2),
    expected_cash numeric(14,2),                           -- opening_float + netCashBetween(till, opened_at, closed_at).net
    variance numeric(14,2),                                -- counted − expected
    close_note varchar(300),
    force_closed boolean not null default false
);
create unique index till_sessions_open_till_uq on till_sessions (tenant_id, cash_account_id) where status = 'OPEN';
create unique index till_sessions_open_cashier_uq on till_sessions (tenant_id, cashier_user_id) where status = 'OPEN';
```

### 2.3 `sales` / `sale_lines` / `sale_payments`

```sql
create table sales (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20),                                       -- TXN-n, assigned at completion (null while OPEN/ABANDONED)
    channel varchar(20) not null check (channel in ('POS','PRO_FORMA_ORDER')),
    status varchar(12) not null check (status in ('OPEN','COMPLETED','ABANDONED','VOIDED')),
    version int not null default 0,                        -- optimistic lock; bumped on every change (till/discount sync)
    location_id uuid not null references locations(id),    -- selling showroom/factory (root)
    till_session_id uuid references till_sessions(id),     -- POS only
    cashier_user_id uuid not null references app_users(id),
    pro_forma_id uuid,                                     -- pro_formas.id when converted / loaded from one
    -- customer
    customer_id uuid,                                      -- null = walk-in
    customer_code varchar(20),
    customer_name varchar(200) not null default 'Walk-in customer',
    customer_type varchar(30),                             -- AUTHORISED_DEALER / SPECIAL_CUSTOMER / ORDINARY_CUSTOMER / NO_CREDIT_WALKIN (snapshot)
    dealer_category varchar(60),
    price_basis varchar(10) not null default 'RETAIL' check (price_basis in ('RETAIL','DEALER')),
    correspondent_id uuid,                                 -- captured at completion (§1.2-6)
    correspondent_name varchar(160),
    -- money (all derived from lines; stored for reports)
    gross_amount numeric(14,2) not null default 0,         -- Σ qty × unit_price
    line_discount_total numeric(14,2) not null default 0,  -- Σ line discounts (pro-forma terms)
    instant_discount_total numeric(14,2) not null default 0,
    net_amount numeric(14,2) not null default 0,           -- gross − discounts
    vat_enabled boolean not null default true,
    vat_rate numeric(5,2) not null default 0,
    vat_amount numeric(14,2) not null default 0,
    total numeric(14,2) not null default 0,
    paid_now numeric(14,2) not null default 0,             -- Σ payments at completion (excl. ON_ACCOUNT)
    on_account numeric(14,2) not null default 0,
    cash_tendered numeric(14,2),
    change_given numeric(14,2),
    credit_override_reason varchar(300),                   -- set when a credit-limit override was used
    credit_override_by_user_id uuid references app_users(id),
    invoice_id uuid,                                       -- invoices.id (plain uuid)
    invoice_number varchar(20),
    supply_status varchar(20) not null default 'NOT_APPLICABLE'
        check (supply_status in ('NOT_APPLICABLE','FULLY_SUPPLIED','PARTIALLY_SUPPLIED','NOT_SUPPLIED')),
    sale_date date,                                        -- business date at completion (tenant tz)
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    abandoned_at timestamptz,
    voided_at timestamptz,
    voided_by_user_id uuid references app_users(id),
    void_reason varchar(300),
    search_vector tsvector generated always as (to_tsvector('simple',
        coalesce(ref,'') || ' ' || coalesce(invoice_number,'') || ' ' || customer_name || ' ' || coalesce(customer_code,''))) stored,
    constraint sales_ref_when_done check ((status in ('OPEN','ABANDONED')) = (ref is null)),
    constraint sales_settled check (status <> 'COMPLETED' or abs(paid_now + on_account - total) < 0.005)
);
create unique index sales_ref_uq on sales (tenant_id, ref) where ref is not null;
create index sales_open_idx on sales (tenant_id, location_id) where status = 'OPEN';
create index sales_date_idx on sales (tenant_id, location_id, sale_date) where status = 'COMPLETED';
create index sales_customer_idx on sales (tenant_id, customer_id, sale_date);
create index sales_cashier_idx on sales (tenant_id, cashier_user_id, sale_date);
create index sales_invoice_idx on sales (tenant_id, invoice_number);
create index sales_name_trgm on sales using gin (lower(customer_name) gin_trgm_ops);
create index sales_search_idx on sales using gin (search_vector);

create table sale_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    sale_id uuid not null references sales(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    item_code varchar(40) not null,
    item_name varchar(200) not null,
    unit_name varchar(60) not null,
    category_name varchar(120),                            -- for the POS category filter and reports
    quantity numeric(14,3) not null check (quantity > 0),
    unit_price numeric(14,2) not null check (unit_price >= 0),
    price_basis varchar(10) not null check (price_basis in ('RETAIL','DEALER','PRO_FORMA')),
    price_list_ref varchar(20),                            -- PL-n the price came from
    line_discount numeric(14,2) not null default 0,        -- from pro-forma terms
    instant_discount_share numeric(14,2) not null default 0,   -- allocated share of the Instant Discount (§4.3)
    line_net numeric(14,2) not null,
    vat_category_code varchar(30) not null,
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    supply_now_qty numeric(14,3) not null default 0,       -- collected at the till
    supplied_qty numeric(14,3) not null default 0,         -- supply_now_qty + Σ waybill releases
    from_location_id uuid references locations(id),        -- area the at-till collection left from
    reservation_id uuid,                                   -- inventory stock_reservations.id for the deliver-later part
    unique (sale_id, line_no),
    constraint sale_lines_supplied_le check (supplied_qty <= quantity),
    constraint sale_lines_discount_le check (line_discount + instant_discount_share <= quantity * unit_price)
);
create index sale_lines_item_idx on sale_lines (tenant_id, stock_item_id);

create table sale_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    sale_id uuid not null references sales(id),
    method varchar(20) not null check (method in ('CASH','POS','FUND_TRANSFER','WALLET','DEPOSIT')),
    amount numeric(14,2) not null check (amount > 0),
    payment_receipt_id uuid not null,                      -- account payment_receipts.id
    receipt_ref varchar(20) not null,
    customer_ledger_entry_id uuid,                         -- WALLET/DEPOSIT debit
    terminal_ref varchar(40),
    payer_reference varchar(80),
    receiving_bank_account_id uuid,
    created_at timestamptz not null default now()
);
create index sale_payments_sale_idx on sale_payments (tenant_id, sale_id);
```

Line arithmetic (per line, half-up to 2 dp): `value = quantity × unit_price`; `line_net = value − line_discount − instant_discount_share`; `vat_amount = line_net × vat_rate / 100` where `vat_rate` = location rate for `STANDARD_RATE` items and 0 for `ZERO_RATED`/`EXEMPT` or when the location's VAT is disabled (`VatRates.forLocation(location, saleDate)`); `line_total = line_net + vat_amount`. Header fields are sums of lines. The same per-line figures are passed to `InvoiceIssuer`, so the invoice equals the sale to the kobo.

### 2.4 `instant_discounts`

```sql
create table instant_discounts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- IDS-n
    sale_id uuid not null references sales(id),
    mode varchar(10) not null check (mode in ('PERCENT','AMOUNT')),
    value numeric(14,4) not null check (value > 0),        -- % or ₦ as entered
    discount_amount numeric(14,2) not null check (discount_amount > 0),
    base_amount numeric(14,2) not null,                    -- sale gross − line discounts at the time (VAT-exclusive)
    pct_of_base numeric(7,4) not null,
    reason varchar(20) not null check (reason in ('VOLUME','BULK','LOYALTY','GOODWILL','CLEARANCE')),
    note varchar(300),
    status varchar(20) not null check (status in ('PENDING_APPROVAL','APPLIED','REJECTED','WITHDRAWN','LAPSED')),
    applied_by_user_id uuid not null references app_users(id),    -- the Administrator
    approved_by_user_id uuid references app_users(id),             -- Management, above the ceiling
    decided_at timestamptz,
    rejection_reason varchar(300),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create unique index instant_discounts_one_live_uq on instant_discounts (sale_id) where status in ('PENDING_APPROVAL','APPLIED');
```
`LAPSED` = the sale was abandoned or voided while the discount was pending/applied.

### 2.5 `pro_formas` / `pro_forma_lines`

```sql
create table pro_formas (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- PF-n, assigned at creation (a quotation is a document from its first save)
    status varchar(20) not null check (status in ('DRAFT','PENDING_APPROVAL','SENT','CONVERTED','DECLINED','EXPIRED','CANCELLED')),
    location_id uuid not null references locations(id),
    customer_id uuid,                                      -- registered customer, or null for a prospect
    prospect_name varchar(200),                            -- required when customer_id is null
    prospect_address varchar(300),
    prospect_contact varchar(200),                         -- phone / e-mail free text
    prospect_email varchar(320),
    customer_name varchar(200) not null,                   -- snapshot for lists
    customer_type varchar(30),
    price_basis varchar(10) not null check (price_basis in ('RETAIL','DEALER')),
    issue_date date not null,
    valid_until date not null,
    payment_terms varchar(300),
    delivery_terms varchar(300),
    note varchar(500),
    gross_amount numeric(14,2) not null default 0,
    discount_total numeric(14,2) not null default 0,
    net_amount numeric(14,2) not null default 0,
    vat_enabled boolean not null,
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null default 0,
    total numeric(14,2) not null default 0,
    prepared_by_user_id uuid not null references app_users(id),
    discount_approved_by_user_id uuid references app_users(id),
    sent_at timestamptz,
    decided_at timestamptz,
    decline_reason varchar(300),
    converted_sale_id uuid references sales(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', ref || ' ' || customer_name)) stored,
    unique (tenant_id, ref),
    constraint pro_formas_party check (customer_id is not null or prospect_name is not null),
    constraint pro_formas_validity check (valid_until >= issue_date)
);
create index pro_formas_status_idx on pro_formas (tenant_id, location_id, status, issue_date desc);
create index pro_formas_name_trgm on pro_formas using gin (lower(customer_name) gin_trgm_ops);
create index pro_formas_search_idx on pro_formas using gin (search_vector);

create table pro_forma_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    pro_forma_id uuid not null references pro_formas(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    item_name varchar(200) not null,
    unit_name varchar(60) not null,
    quantity numeric(14,3) not null check (quantity > 0),
    unit_price numeric(14,2) not null,                     -- the price-list price on issue_date (honoured until valid_until)
    line_discount numeric(14,2) not null default 0 check (line_discount >= 0),
    line_net numeric(14,2) not null,
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    unique (pro_forma_id, line_no),
    constraint pfl_discount_le check (line_discount <= quantity * unit_price)
);
```

### 2.6 `customer_waybills` / `customer_waybill_lines`

```sql
create table customer_waybills (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- WB-n (shared tenant-wide waybill space with stock transfers, doc 03 §6)
    sale_id uuid not null references sales(id),
    invoice_number varchar(20),
    location_id uuid not null references locations(id),
    release_method varchar(12) not null check (release_method in ('DELIVERY','COLLECTION')),
    release_date date not null,
    driver_id uuid references drivers(id),                 -- required for DELIVERY
    driver_name varchar(160),
    vehicle_number varchar(30),
    collected_by varchar(160),                             -- required for COLLECTION
    delivery_address varchar(300),
    status varchar(12) not null check (status in ('ISSUED','DELIVERED','CANCELLED')),
    received_by varchar(160),                              -- signature name on acknowledgement
    received_at timestamptz,
    acknowledgement_note varchar(300),
    cancelled_reason varchar(300),
    cancelled_by_user_id uuid references app_users(id),
    issued_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create index customer_waybills_sale_idx on customer_waybills (tenant_id, sale_id);
create index customer_waybills_date_idx on customer_waybills (tenant_id, location_id, release_date desc);

create table customer_waybill_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    waybill_id uuid not null references customer_waybills(id),
    sale_line_id uuid not null references sale_lines(id),
    from_location_id uuid not null references locations(id),   -- area the goods left from
    quantity numeric(14,3) not null check (quantity > 0),
    outstanding_after numeric(14,3) not null                    -- printed "Still outstanding"
);
```

### 2.7 Module settings

Registered in doc 03 §10: `cashier.instantDiscount.ceilingPct` (10), `cashier.sale.voidWindowHours` (24), `cashier.sale.abandonAfterHours` (12), `cashier.proForma.defaultValidityDays` (7).

---

## 3. Permission codes

Shipped V1 codes `cashier.sales.read` and `cashier.sales.manage` are kept with these meanings; new codes added in V13:

| Code | Allows | Default roles |
|---|---|---|
| `cashier.sales.read` | Own sales, own till sessions, sale detail/receipt | Cashier, Accounts Personnel, Management |
| `cashier.sales.read-all` | All sales in location scope, Sales Report, comprehensive reports | Accounts Personnel, Management |
| `cashier.sales.manage` | Open/close own till, build and complete sales | Cashier |
| `cashier.sales.void` | Void a completed sale within the window | Accounts Personnel |
| `cashier.credit.override` | Complete an on-account sale beyond the credit limit (with reason) | Management |
| `cashier.till.supervise` | Force-close another cashier's till session | Accounts Personnel, Management |
| `cashier.discount.apply` | Instant Discount (PRD: "exclusively accessible to the Admin") | Administrator |
| `cashier.discount.approve` | Approve discounts above the ceiling — on live sales and on pro-formas | Management |
| `cashier.pro-forma.manage` | Create/edit/send/decline/cancel/convert pro-formas | Cashier, Accounts Personnel |
| `cashier.waybill.manage` | Generate, acknowledge, cancel customer waybills | Cashier, Stock Keeper |
| `cashier.supply.read` | Pending Supply Reports, supply history | Cashier, Stock Keeper, Accounts Personnel, Management |

Location scope via `LocationAccessService` on every endpoint (selling location). "Own" for Cashier: `cashier_user_id = me` unless `cashier.sales.read-all`.

The mock-era reuse of `cashier.sales.*` by `customers`, `invoicing`, `history` and `ai` controllers is replaced by those modules' own codes as each is rebuilt (docs 04, 08, 10, 16).

---

## 4. Endpoints (all under `/api/v1/cashier`)

### 4.1 Till sessions — `/till-sessions`

- **`POST /till-sessions`** — perm `cashier.sales.manage`. `{ cashAccountId, openingFloat }`. The cash account must be an ACTIVE `TILL` (`CashAccounts.get`) at a location the user may access → `400 INVALID_TILL`; the till already open → `409 TILL_ALREADY_OPEN` (details `{ openedBy, openedAt }`); the user already has an open session → `409 CASHIER_SESSION_OPEN`. Response `{ id, till, location, openedAt, openingFloat }`. Emits `cashier.till.opened`.
- **`GET /till-sessions/current`** — the caller's open session or `404 NO_OPEN_TILL_SESSION`. Includes running figures `{ salesCount, cashTaken, cardTaken, transferTaken, walletTaken, depositTaken, onAccount }`.
- **`POST /till-sessions/{id}/close`** — perm `cashier.sales.manage` (own) or `cashier.till.supervise` (any → `force_closed = true`). `{ countedCash, note? }`. Open sales in the session must be completed or abandoned first → `409 OPEN_SALES_EXIST` (details: refs/ids). `expected_cash = opening_float + CashAccounts.netCashBetween(till, opened_at, now).net` (cash receipts, cash refunds, lodgements from the till — everything Account posted to that till). `variance = counted − expected`. Non-zero variance → `cashier.till.variance` event and in-app notification to holders of `accounting.receipts.confirm` at the location. The variance is a recorded fact; Accounts resolves it with a manual cash entry in Account if needed — no automatic posting. Response: the closed session with `{ expectedCash, countedCash, variance }`.
- **`GET /till-sessions`** [paged] — perm `cashier.sales.read` (own) / `cashier.sales.read-all`. Query `locationId`, `cashierUserId`, `from`, `to`, `withVarianceOnly`.

A daily 23:59 job flags sessions still open (notification to the cashier and `cashier.till.supervise` holders); it never closes them automatically — the cash count must be a human count.

### 4.2 POS sale — `/sales`

**Catalogue** — `GET /catalogue?locationId=&q=&categoryId=&customerId=` — perm `cashier.sales.manage`. Sellable items at the location: `ACTIVE`, not a variant parent, with a current price, `available > 0` by default (`includeOutOfStock=true` to show them disabled). `q` uses `SearchSpec`: code/barcode CODE (exact barcode scan returns that one item first), name TRIGRAM. Row `{ stockItemId, code, barcode, name, category, unit, price (dealer or retail per the customer), retailPrice, dealerPrice, available, lowStock (available < reorderLevel) }`. Category chips come from `GET /catalogue/categories?locationId=` (categories having sellable items, plus "Ungrouped").

**Lifecycle**

| Step | Call | Notes |
|---|---|---|
| Start | `POST /sales { customerId? }` | Requires the caller's open till session (`409 NO_OPEN_TILL_SESSION`); location = till's location. Response: the sale (`status OPEN`, `version 0`). |
| Change customer | `PUT /sales/{id}/customer { customerId \| null, version }` | Re-prices every line (dealer ↔ retail), recomputes; an applied Instant Discount is kept as an amount and re-validated against the new base (exceeds → `LAPSED`). |
| Set lines | `PUT /sales/{id}/lines { version, lines: [ { stockItemId, quantity } ] }` | Full replace. Prices taken from the price list now; quantity > available → line kept but flagged `insufficient: true` with `available` (soft check — the hard check is at completion). Unknown/suspended item → `400 ITEM_NOT_SELLABLE` (rule 1). No price → `409 NO_PRICE_SET`. |
| Load a pro-forma | `POST /sales/{id}/load-pro-forma { proFormaId, version }` | Allowed for `SENT` pro-formas still valid (`valid_until ≥ today` → else `409 PRO_FORMA_EXPIRED`); copies customer, lines, quoted prices (`price_basis PRO_FORMA`) and line discounts. Completion marks the pro-forma `CONVERTED`. |
| Live view | `GET /sales/{id}`, `GET /sales/{id}/stream` (SSE) | §4.3 |
| Abandon | `POST /sales/{id}/abandon { version }` | "Clear sale". No stock or money effect. Open sales untouched for `cashier.sale.abandonAfterHours` are abandoned by a job. |
| Complete | `POST /sales/{id}/complete` | below |

Every mutating call carries `version`; a mismatch → `409 SALE_CHANGED` with the current sale in `details` (e.g. an Instant Discount arrived between the cashier's view and click). Only the sale's cashier may mutate it → `403 FORBIDDEN`.

**Sale response** (used by all the above):
```json
{ "data": {
  "id": "…", "ref": null, "status": "OPEN", "version": 4, "channel": "POS",
  "till": "TILL-IKJ-01", "location": { "id": "…", "name": "Showroom Ikeja" }, "cashier": "F. Adeniyi",
  "customer": { "id": "…", "code": "DLR-42", "name": "Okafor & Sons Distribution", "type": "AUTHORISED_DEALER",
                "category": "Gold", "correspondent": "B. Eze", "walletBalance": 120000.00, "depositBalance": null,
                "creditLimit": 500000.00, "outstanding": 210000.00, "creditAvailable": 290000.00, "overLimit": false },
  "priceBasis": "DEALER",
  "lines": [ { "lineNo": 1, "stockItemId": "…", "code": "ZF-101", "name": "Citrus Drink 50cl", "unit": "Cartons",
               "quantity": 12, "unitPrice": 4100.00, "lineDiscount": 0, "instantDiscountShare": 0,
               "lineNet": 49200.00, "vatRate": 7.50, "vatAmount": 3690.00, "lineTotal": 52890.00,
               "available": 640, "insufficient": false } ],
  "totals": { "gross": 49200.00, "lineDiscounts": 0, "instantDiscount": 0, "net": 49200.00,
              "vat": { "enabled": true, "rate": 7.50, "amount": 3690.00 }, "total": 52890.00 },
  "instantDiscount": null,
  "allowedMethods": ["CASH","POS","FUND_TRANSFER","WALLET","ON_ACCOUNT"]
} }
```
`allowedMethods`: `WALLET` when wallet balance > 0; `DEPOSIT` when `SPECIAL_CUSTOMER` with deposit > 0; `ON_ACCOUNT` when registered, non-temporary, `credit_limit > 0`, status `ACTIVE`; `CASH` needs the session's till; `POS` needs a till card terminal or a bank account; `FUND_TRANSFER` always.

**Complete — `POST /sales/{id}/complete`** — perm `cashier.sales.manage`.
```json
{ "version": 4,
  "payments": [
    { "method": "CASH", "amount": 30000.00, "tendered": 30000.00 },
    { "method": "POS", "amount": 22890.00, "terminalRef": "T-55", "payerReference": "AUTH 448812" } ],
  "onAccount": 0,
  "supply": [ { "lineNo": 1, "supplyNow": 12, "fromLocationId": null } ],
  "invoice": { "request": false, "buyer": null, "emailTo": null },
  "creditOverrideReason": null }
```
Field rules:
- `payments[].method` ∈ `allowedMethods`; amount > 0; at most one entry per method except `POS`/`FUND_TRANSFER` (several cards/transfers allowed).
- `CASH.tendered` ≥ `amount`; `change = tendered − amount` (only cash produces change).
- `FUND_TRANSFER` needs `receivingBankAccountId` + `payerReference`; `POS` needs `terminalRef` or the till's terminal is used.
- Σ payments + `onAccount` must equal `total` (±0.005) → else `400 PAYMENT_NOT_SETTLED` (details `{ total, allocated, remaining }`). Over-allocation is the same error with negative `remaining`.
- `supply` defaults to "everything now". `supplyNow` ≤ quantity. Anything not supplied now is reserved for later waybills. Deliver-later for a walk-in requires an invoice request or a phone on the receipt (`409 WALKIN_DELIVERY_NEEDS_CONTACT`) — someone must be able to claim the goods.
- `invoice.request` (walk-ins only; registered customers always get one) with `buyer { name, email?, phone?, tin?, address?, stateCode? }`.

Processing, in one `TenantScopedTransaction`:
1. Lock the sale (`FOR UPDATE`), check `status = OPEN`, `version`, the till session still `OPEN`.
2. Lock the customer (`CustomerDirectory.getForUpdate`); status must be `ACTIVE`/`WATCH` → else `409 CUSTOMER_NOT_ACTIVE`.
3. Re-price at today's price list. Any price differs from the cart → `409 PRICES_CHANGED` with the re-priced sale (version bumped) for the cashier to re-confirm. Pro-forma-loaded lines keep quoted prices while the pro-forma is valid.
4. Instant Discount `PENDING_APPROVAL` → `409 DISCOUNT_PENDING_APPROVAL` (the till must wait for approval/rejection or ask the Administrator to withdraw it).
5. Stock: for each line lock the balance rows and the open reservations for `(location, item)`; `available ≥ quantity` → else `409 INSUFFICIENT_STOCK` (details `[ { code, requested, available } ]`, nothing written). The at-till part also needs on-hand at `fromLocationId` (default: the showroom's single `SHOP_FLOOR` area if exactly one exists, else the showroom itself).
6. Credit: if `onAccount > 0`: `Receivables.outstandingFor(customer) + onAccount ≤ credit_limit` → else `409 CREDIT_LIMIT_EXCEEDED` (details `{ creditLimit, outstanding, requested }`), unless `creditOverrideReason` is given **and** the caller has `cashier.credit.override` (cashiers get Management to complete it from their own session via a supervisor login on the till — no shared-credential path is built).
7. `ref = next("TXN")`; `sale_date` = today (tenant tz); `correspondent` = `Correspondents.creditableFor(customer)`.
8. Stock postings: `supplyNow` → `POS_SALE` out (ref TXN, `qty_out`, unit cost = current landing cost for valuation); remainder → `StockReservations.reserve(SALE_LINE …)`.
9. Wallet/deposit: `WalletLedger.debit(PURCHASE_DEBIT, balanceType WALLET|DEPOSIT, amount, reference TXN, sourceModule cashier, sourceType POS_SALE, sourceId sale)` → `400 INSUFFICIENT_WALLET_BALANCE` / `INSUFFICIENT_DEPOSIT_BALANCE` (rule 5; the till pre-checks too).
10. Receipts: one `MoneyLedger.recordReceipt` per payment (`sourceType POS_SALE`, `cashAccountId` = the till for CASH/POS) → `RCT-n` each; `sale_payments` rows.
11. Invoice: registered customer **or** `invoice.request` → `InvoiceIssuer.issue(InvoiceRequest(sourceType POS_SALE, sourceId sale, sourceRef TXN, proFormaRef?, lines = sale lines with their discounts as line discount amounts, paymentTermsDays = default when onAccount > 0 else 0))` then `attachPayment` per receipt. `sales.invoice_id/number` set. E-mail sent per invoicing's auto-send setting or `invoice.emailTo`.
12. `CustomerActivity.recordSale(customer, total, now)`.
13. `supply_status`: everything supplied → `NOT_APPLICABLE` when nothing was deferred, else `NOT_SUPPLIED`/`PARTIALLY_SUPPLIED`.
14. Register `SALE_RECEIPT` (ref TXN) in the document registry; pro-forma (if loaded) → `CONVERTED`, `converted_sale_id`.
15. Emit `cashier.sale.completed` (payload: sale id/ref, location, customer, correspondent, net, total, payments by method with receipt confirmation status) — Communication computes commission from it once every receipt is confirmed (rule 11); Dashboard/Reports/AI consume it.

Response: `{ sale (with ref, payments [{ method, amount, receiptRef, confirmationStatus }], changeGiven), invoice: { id, number } | null, receiptUrl: "/api/v1/cashier/sales/{id}/receipt.pdf", pendingSupply: [ { lineNo, outstanding } ] }`.

**Read**
- `GET /sales` [paged] — perm `cashier.sales.read` (own) / `read-all`. Query `locationId`, `cashierUserId`, `customerId`, `channel`, `status` (default `COMPLETED`), `supplyStatus`, `from`, `to`, `q` (TXN/INV CODE, customer name TRIGRAM, FULLTEXT). Row `{ id, ref, date, time, location, cashier, customer, customerType, items, units, net, vat, total, methods: ["Cash","POS"], onAccount, invoiceNumber, supplyStatus, status }`.
- `GET /sales/{id}` — full sale incl. payments, waybills, instant discount, void info.
- `GET /sales/{id}/receipt.pdf` — itemised till receipt: header (company + showroom from `CompanyProfileProvider` + location), TXN, date/time, till, cashier, customer, lines, discounts, VAT, total, each payment with its RCT and "pending confirmation" mark for card/transfer, change, invoice number, correspondent, "Goods outstanding: n — present this receipt or invoice to collect" when deferred. "DUPLICATE COPY" from the 2nd print (doc 03 §8).
- `POST /sales/{id}/invoice` — walk-in sale without an invoice, invoice requested afterwards. Perm `cashier.sales.manage` or `invoicing.invoice.issue`. `{ buyer {…}, emailTo? }` → `InvoiceIssuer.issue` with the sale's lines + `attachPayment` for its receipts. Already invoiced → `409 INVOICE_EXISTS_FOR_SOURCE`.

**Void — `POST /sales/{id}/void`** — perm `cashier.sales.void`. `{ reason (≥10 chars) }`. Allowed when `COMPLETED`, within `cashier.sale.voidWindowHours` of completion (`409 VOID_WINDOW_PASSED` — use a customer return (doc 05) and a credit note (doc 08) instead), no `ISSUED`/`DELIVERED` customer waybill (cancel them first, §4.5) and no customer return referencing the sale. Effects, one transaction: `SALE_VOID` in for each at-till collection (back to `from_location_id`); cancel open reservations; `MoneyLedger.voidReceipt` for each receipt (Account reverses cash/bank postings); `WalletLedger.reverse` for wallet/deposit debits; `InvoiceIssuer.voidForSource(POS_SALE, sale, reason)`; `CustomerActivity.reverseSale`; an `APPLIED` instant discount → `LAPSED`; document registry `markVoided(TXN)`. Emits `cashier.sale.voided` (Communication drops/reverses commission). Response: the voided sale.

### 4.3 Live sales & Instant Discount

- **`GET /sales/live`** — perm `cashier.discount.apply` or `cashier.discount.approve`. Open POS sales in scope with ≥1 line: `{ id, version, till, cashier, customer, customerType, items, subtotal (gross − line discounts), startedAt, instantDiscount }`. **`GET /sales/live/stream`** — SSE (`text/event-stream`), events `sale-opened`, `sale-updated`, `sale-closed` (completed/abandoned) with `{ id, version }`.
- **`GET /sales/{id}/stream`** — SSE for the till (sale's cashier) and discount users: `sale-updated { version }`, `discount-pending`, `discount-applied`, `discount-rejected`, `discount-withdrawn`. The client re-fetches `GET /sales/{id}` on each event. Fallback: poll `GET /sales/{id}` every 3 s comparing `version`.
  Implementation: Spring MVC `SseEmitter` (built into the stack; no extra dependency), published **after commit** (`TransactionSynchronization.afterCommit`) by an in-process `SaleUpdatePublisher`; heartbeat comment every 20 s; emitter timeout 30 min (client reconnects). Multi-instance deployments switch the publisher to Postgres `LISTEN/NOTIFY` on channel `sale_updates` (payload `tenantId:saleId:version`) — same interface, no client change. The EC2 deployment is single-instance today.
- **`POST /sales/{id}/instant-discount`** — perm `cashier.discount.apply`.
  ```json
  { "mode": "PERCENT", "value": 5, "reason": "LOYALTY", "note": "Long-standing dealer, MD approved", "version": 4 }
  ```
  Rules: sale `OPEN` with lines; `base = gross − line discounts` (VAT-exclusive); `discount = PERCENT ? base × value / 100 : value`, rounded 2 dp; `discount > base` → `400 DISCOUNT_EXCEEDS_TRANSACTION`; a live discount exists → `409 DISCOUNT_ALREADY_ACTIVE` (withdraw first). `pct_of_base ≤ ceilingPct` → `APPLIED` immediately; else if the caller also holds `cashier.discount.approve` → `APPLIED` with `approved_by` = caller; otherwise `PENDING_APPROVAL` + in-app notification to `cashier.discount.approve` holders ("Discounts over 10% require a second authorisation from Management"). `ref = next("IDS")`.
  **Allocation** to lines (for VAT and the invoice): pro-rata by each line's `value − line_discount`, half-up to 2 dp, rounding remainder added to the largest line; VAT recomputed per line on the reduced net. If the cart changes afterwards, an `AMOUNT` discount is re-allocated over the new lines and a `PERCENT` discount is recomputed on the new base; if the new base < the amount → `LAPSED` + till notification.
- **`POST /instant-discounts/{id}/approve`** / **`/reject { reason }`** — perm `cashier.discount.approve`; approver ≠ the applier unless the applier also holds the permission (already auto-applied above). Only `PENDING_APPROVAL` → `409 ALREADY_DECIDED`.
- **`POST /instant-discounts/{id}/withdraw`** — perm `cashier.discount.apply`; `PENDING_APPROVAL`/`APPLIED` on an `OPEN` sale.
- **`GET /instant-discounts`** [paged] — perm `cashier.discount.apply` or `cashier.sales.read-all`. "Recent discounts": `{ ref, date, cashier, customer, original (base), discount, pct, reason, appliedBy, approvedBy, status, saleRef }` + total discount.

Every change bumps the sale `version` and pushes `sale-updated` so "the customer sees the revised total before payment is taken". Instant discounts appear on the Sales Report in the Discount column (deduction from gross).

### 4.4 Pro-forma invoices — `/pro-formas`

- **`POST /pro-formas`** — perm `cashier.pro-forma.manage`.
  ```json
  { "locationId": "…", "customerId": "…", "prospect": null,
    "issueDate": "2026-10-08", "validUntil": "2026-10-15",
    "paymentTerms": "50% deposit, balance on delivery", "deliveryTerms": "Delivered to customer warehouse", "note": null,
    "lines": [ { "stockItemId": "…", "quantity": 200, "lineDiscount": 16000.00 } ] }
  ```
  `customerId` or `prospect { name (required), address?, contact?, email? }` — prospects are quote-only parties and are not created as customers until they buy. Prices: price list on `issueDate` by the customer's basis (prospects → retail). `validUntil` defaults to `issueDate + cashier.proForma.defaultValidityDays`, must be ≥ `issueDate`. Line discount ≤ line value → `400 DISCOUNT_EXCEEDS_LINE`. Stock is **not** checked or committed ("no stock is committed until the customer confirms"); lines with `available < quantity` are flagged `availabilityWarning` in the response. VAT at the location's rate on `issueDate`. `ref = next("PF")`. Status `DRAFT` ("Save draft"), or `?send=true` → validated as for send below.
  Total line discounts > `ceilingPct` of gross and the preparer lacks `cashier.discount.approve` → status `PENDING_APPROVAL` (notification to approvers) instead of `DRAFT`/`SENT`; approval returns it to `DRAFT`. Approve/reject: `POST /pro-formas/{id}/approve-discount`, `/reject-discount { reason }` (perm `cashier.discount.approve`; rejection → `DRAFT` with the reason, discounts must be reduced).
- **`PUT /pro-formas/{id}`** — same body; only `DRAFT`. Re-prices at the (possibly new) issue date.
- **`POST /pro-formas/{id}/send`** — `DRAFT` → `SENT` (registers `doc_type PRO_FORMA`; optional `{ emailTo, message }` queues an e-mail with the PDF through the notification outbox, same mechanism as invoices). Re-sending a `SENT` one only re-queues the e-mail.
- **`POST /pro-formas/{id}/decline`** `{ reason }` — `SENT` → `DECLINED` (customer said no). **`POST /pro-formas/{id}/cancel`** `{ reason }` — `DRAFT`/`PENDING_APPROVAL`/`SENT` → `CANCELLED` (company withdraws it).
- **Expiry job** daily 00:05: `SENT` and `valid_until < today` → `EXPIRED` (emits `cashier.pro-forma.expired`). An expired pro-forma can be **re-issued**: `POST /pro-formas/{id}/reissue` → a new `DRAFT` PF copying lines at today's prices.
- **`POST /pro-formas/{id}/convert`** — perm `cashier.pro-forma.manage` + `invoicing.invoice.issue`. The order path ("raised manually from a confirmed pro-forma", `InvoiceGeneration.jsx`).
  ```json
  { "issueAs": "ISSUED", "paymentTermsDays": 30, "paymentTermsNote": "50% deposit, balance on delivery",
    "creditOverrideReason": null }
  ```
  Requires `SENT`, valid (`409 PRO_FORMA_EXPIRED`), a registered customer (`409 PROSPECT_MUST_REGISTER` — register the prospect in Customers first and `PUT` the pro-forma to that customer). Credit check as §4.2 step 6 on the full total (an order converted to an invoice is on account until paid). Stock: `available ≥ quantity` per line (`409 INSUFFICIENT_STOCK`) → reserve everything. Creates a `sales` row `channel PRO_FORMA_ORDER`, `status COMPLETED`, `ref next("TXN")`, `on_account = total`, quoted prices and discounts, `supply_status NOT_SUPPLIED`, no till session. Then `InvoiceIssuer.issue(sourceType PRO_FORMA, sourceId = pro-forma id, sourceRef PF-n)` for `issueAs ISSUED`, or `createDraft(...)` for `issueAs DRAFT` (Accounts reviews and issues it in Invoicing; stock stays reserved meanwhile; a discarded draft emits `invoicing.invoice.discarded`, which the `cashier.pro-forma-draft-discard` consumer handles by voiding the order and cancelling its reservations). Pro-forma → `CONVERTED`. Emits `cashier.sale.completed` (channel `PRO_FORMA_ORDER`) and `cashier.pro-forma.converted`. Payments then arrive through `POST /api/v1/invoicing/invoices/{id}/payments`.
  The till alternative — customer pays on the spot — is `POST /sales/{id}/load-pro-forma` (§4.2).
- **`GET /pro-formas`** [paged] — perm `cashier.pro-forma.manage` or `cashier.sales.read-all`. Query `status`, `locationId`, `customerId`, `from`, `to`, `q` (PF CODE, customer TRIGRAM). Row `{ id, ref, date, customer, items, value (total), validUntil, status, preparedBy, convertedTo (TXN/INV) }`. Header for "Quotations this period": `{ issued, converted, conversionRate, valueQuoted }`.
- **`GET /pro-formas/{id}`**, **`GET /pro-formas/{id}/pro-forma.pdf`** — "Pro-Forma Invoice" document with a "QUOTATION" stamp, meta (Issue date, Valid until, Prepared by), lines (description, qty, unit, unit price, discount, amount), subtotal, discount, VAT, total payable, payment/delivery terms, footer "This pro-forma invoice is an estimate of the proposed transaction and is not a demand for payment. Prices hold until the validity date shown above, subject to stock availability at the time of confirmation."

### 4.5 Customer waybills — `/waybills`

- **`GET /supply/lookup?ref=`** — perm `cashier.waybill.manage` or `cashier.supply.read`. `ref` = an invoice number (INV) or sale ref (TXN). Not found / voided → `404 SALE_NOT_FOUND`. Response (the "Invoice position"):
  ```json
  { "data": { "saleId": "…", "saleRef": "TXN-4502", "invoice": "INV-31240", "invoiceDate": "2026-08-20",
    "customer": "Okafor & Sons Distribution", "customerType": "AUTHORISED_DEALER", "address": "14 Ikorodu Rd, Lagos", "contact": "0803…",
    "invoiceValue": 820000.00, "invoiceBalance": 0.00,
    "lines": [ { "saleLineId": "…", "product": "Citrus Drink 50cl", "unit": "Cartons", "purchased": 200, "supplied": 120, "outstanding": 80 } ],
    "totals": { "purchased": 200, "supplied": 120, "outstanding": 80, "progressPct": 60 },
    "history": [ { "waybill": "WB-9101", "date": "2026-08-21", "product": "Citrus Drink 50cl", "qty": 120, "driver": "Musa Bello", "receivedBy": "K. Okafor", "status": "DELIVERED" } ],
    "waybillCount": 1, "fullySupplied": false } }
  ```
- **`POST /waybills`** — perm `cashier.waybill.manage`.
  ```json
  { "saleId": "…", "releaseMethod": "DELIVERY", "releaseDate": "2026-10-08",
    "driverId": "…", "vehicleNumber": "LSR-442-XA", "collectedBy": null, "deliveryAddress": null,
    "lines": [ { "saleLineId": "…", "quantity": 50, "fromLocationId": "…" } ] }
  ```
  Rules: sale `COMPLETED` and not voided; each `quantity ≤ outstanding` → `400 RELEASE_EXCEEDS_OUTSTANDING` (details per line, "Only 80 cartons remain outstanding on this line"); at least one line > 0; `DELIVERY` needs `driverId` (ACTIVE driver, doc 05 §2.5) — vehicle defaults to the driver's; `COLLECTION` needs `collectedBy`; `fromLocationId` = the selling location or one of its areas with enough on-hand (`409 INSUFFICIENT_STOCK_AT_AREA`); `releaseDate` not in the future and not before the sale date. Effects: `ref = next("WB")`; `StockReservations.release(reservation, qty, fromLocation)` → `CUSTOMER_WAYBILL` ledger out (ref WB); `sale_lines.supplied_qty += qty`; recompute `supply_status`; `InvoiceWaybillLinks.link(invoice, waybill, ref, date)` when invoiced; register `doc_type WAYBILL`. Last outstanding quantity released → reservation `CLOSED`. Emits `cashier.waybill.issued` (and `cashier.sale.fully-supplied` when it completes the sale). Response: waybill with lines `{ product, qtyReleased, unit, stillOutstanding }`.
- **`GET /waybills/{id}/waybill.pdf`** — "Customer Waybill": issuer, party (customer, address, contact), meta (Against invoice, Driver, Vehicle, Release date, Release method / Collected by), lines (product, qty released, unit, still outstanding), totals (lines released, total quantity), "FOR RELEASE" stamp, signature block (Released by / Driver / Received by — name, signature, date), footer "This waybill authorises release of the goods listed against the invoice shown. The receiving party must verify quantities on collection or delivery and sign before the goods leave the premises."
- **`POST /waybills/{id}/acknowledge`** — `{ receivedBy, receivedAt, note? }` → `DELIVERED` (the signed copy came back). Only `ISSUED`.
- **`POST /waybills/{id}/cancel`** — `{ reason }`; only `ISSUED` and same business day as `release_date` (goods didn't leave) → `409 WAYBILL_NOT_CANCELLABLE`. `CUSTOMER_WAYBILL_CANCEL` in to `from_location_id`, re-opens the reservation quantity, `supplied_qty −=`, registry `markVoided`. Emits `cashier.waybill.cancelled`.
- **`GET /waybills`** [paged] — perm `cashier.supply.read`. Query `locationId`, `customerId`, `driverId`, `status`, `from`, `to`, `q` (WB/INV/TXN CODE, customer TRIGRAM).

### 4.6 Reports

All: perm `cashier.sales.read-all` (Sales Report also `cashier.sales.read` → limited to own sales), location-scoped, `from`/`to` required (max 366 days → `400 RANGE_TOO_LARGE`), voided and abandoned sales excluded, empty result → empty rows (not an error, PRD test case 2). No cost fields — nothing to mask (response still carries `costVisible` per doc 03 §14 for consistency: `true`).

**Sales Report — `GET /reports/sales-daily`** — query `locationId`, `cashierUserId`, `from`, `to`. One row per (sale_date, location): `{ date, showroom, transactions, units, gross, discount (line + instant), net, vat, avgSale (net / transactions) }`; totals row; stats `{ netSales, transactions, units, averageTransaction, bestDay { date, net } }`. Footnote data: VAT per location rate — it reconciles with the Showroom VAT Report (doc 10) because both read the same `sale_lines.vat_amount`. Both channels (`POS`, `PRO_FORMA_ORDER`) counted, by completion date; `channel` filter optional.

**Pending Supply — `GET /reports/pending-supply`** — perm `cashier.supply.read`. Query `invoice` (ref), `customerId`, `status` (`NOT_SUPPLIED`|`PARTIALLY_SUPPLIED`|`FULLY_SUPPLIED`), `locationId`, `from`, `to` (sale date), `view` = `summary` (default) | `detail`.
- summary row (per sale with deferred goods): `{ invoice, saleRef, customer, invoiceDate, lines, ordered, supplied, outstanding, progressPct, deliveries (waybill count), lastSupply, status }`;
- detail row (per sale line × waybill, plus a "Not raised" row per line with outstanding qty): `{ invoice, customer, product, ordered, supplied (this waybill), outstanding (line), waybill | null, supplyDate, status ('Delivered'|'Issued'|'Pending') }`;
- stats `{ invoicesPending, invoicesTotal, unitsInvoiced, unitsSupplied, fulfilledPct, unitsOutstanding }`; `outstandingAtAGlance: [ { invoice, status, customer, outstanding, deliveries } ]`.
Sales fully collected at the till never appear (they were never pending).

**Customers/Distributors Comprehensive — `GET /reports/customer-activity`** — query `kind` = `DISTRIBUTORS` | `NO_CREDIT` | `SPECIAL`, `customerId`, `locationId`, `from`, `to`. Customer type filter: `AUTHORISED_DEALER` / (`NO_CREDIT_WALKIN` + walk-ins without a customer) / `SPECIAL_CUSTOMER`.
- `DISTRIBUTORS` row per sale: `{ date, invoice, saleRef, customer, products: [ { name, qty } ], qtySupplied, value (total), cash, pos, transfer, wallet, onAccount, waybills: [refs] | "Pending" }` — payment columns sum to `value`; header `{ activeDistributors, unitsSupplied, value, averageTransaction }`; `walletBalances: [ { customer, wallet } ]` (from `CustomerDirectory`).
- `SPECIAL`: same row with `drawnFromDeposit` replacing the channel columns (other channels still summed in `otherPayments`); header `depositsHeld`; `deposits: [ { customer, code, showroom, since, deposit } ]`.
- `NO_CREDIT` row per sale: `{ date, receipt (TXN), customer, items, units, method ("Cash" | "POS" | "Split"), cashier, value }`; header `{ transactions, units, value, averageTransaction }`.

---

## 5. Exposed to other modules — `com.zifiq.cashier.spi`

```kotlin
interface SalesQueries {
    fun get(saleId: UUID): SaleView
    fun byRef(ref: String): SaleView?
    fun linesFor(filter: SalesLineFilter): List<SaleLineRow>    // doc 10 (output VAT, waybill history), doc 13 (sales analytics, outsource margin), doc 16
    fun dailyTotals(filter: SalesTotalsFilter): List<DailySalesTotal>   // doc 14 dashboard
    fun completedFor(customerId: UUID, from: LocalDate, to: LocalDate): List<SaleView>   // doc 04 customer history
    fun supplyHistory(filter: SupplyHistoryFilter): List<SupplyHistoryRow>   // doc 10 Waybill History: one row per sale line × waybill
                                                                            // (+ one "not raised" row per line with outstanding qty)
}
```
`SalesLineFilter` carries `{ locationIds?, from?, to (completed on/before), customerId?, customerTypes?, correspondentId?, stockItemIds?, channel?, qualifyingForCommission?: Boolean }`. `SaleLineRow` carries `{ saleLineId, saleId, saleRef, saleDate, locationId, customerId, customerType, correspondentId, stockItemId, quantity, unitPrice, lineNet, vatRate, vatAmount, lineTotal, landingCostAtSale [cost], receiptsConfirmed (every receipt APPROVED/NOT_REQUIRED), invoicePaid }`. `qualifyingForCommission = true` returns only lines whose sale has `receiptsConfirmed` (and `invoicePaid` when Communication's `communication.requireInvoicePaid` is passed as `requireInvoicePaid = true`) — doc 12 §1.2-2. `landingCostAtSale` is the ledger unit cost of the `POS_SALE`/`CUSTOMER_WAYBILL` entries and is masked by the consuming report via `CostVisibility`.

Communication (doc 12) uses `SalesQueries` + `cashier.sale.completed` / `cashier.sale.voided` for commission; Showroom returns (doc 05) look up the original sale by TXN/INV via `SalesQueries.byRef` when a return names one. (Doc 05 is built before this module; its return form takes the reference as free text until V13, then validates it.)

---

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| S1 | No sale of suspended/inactive/deleted products | PRD §6 rule 1 | §4.2 set lines + completion |
| S2 | Never sell more than available | rule 2 | §2.1 available, §4.2 step 5 |
| S3 | Wallet/deposit never negative; balance updates immediately | rule 5; 5.8.2 | §4.2 step 9 (same transaction) |
| S4 | Card/transfer payments need Accounts confirmation before final | rule 7 | Account receipt rules; the receipt prints "pending confirmation" |
| S5 | VAT per showroom rate/enablement | rule 9 | §2.3 line arithmetic |
| S6 | Commission only on completed, confirmed sales | rule 11 | event payload; doc 12 |
| S7 | Location/role scoping; cashier sees own transactions | rule 12; §7 | §3 |
| S8 | Invoice numbering/immutability | rule 14 | delegated to doc 08 |
| S9 | Every sale assigned to the customer's correspondent | 5.8.2 | §1.2-6 |
| S10 | Payment split must settle the total exactly | `POS.jsx` guard | §4.2 complete |
| S11 | A release never exceeds what is outstanding | `GenerateWaybill.jsx` | §4.5 |
| S12 | Line discount ≤ line value | `ProFormaInvoice.jsx` | §4.4 |
| S13 | Instant discount ≤ transaction; above ceiling → second authorisation | `InstantDiscount.jsx` | §4.3 |
| S14 | Instant Discount is Admin-only and real-time | 5.7.11 | §3, §4.3 |
| S15 | Pro-forma commits no stock | `ProFormaInvoice.jsx` note | §4.4 |
| S16 | Credit sales within credit limit | doc 04 C10 | §4.2 step 6, §4.4 convert |
| S17 | Every sale-related release has a unique waybill number | rule 6 (transfers) applied to customer releases | §4.5 |

## 7. Errors added

`NO_OPEN_TILL_SESSION` 409 · `TILL_ALREADY_OPEN` 409 · `CASHIER_SESSION_OPEN` 409 · `INVALID_TILL` 400 · `OPEN_SALES_EXIST` 409 · `SALE_NOT_FOUND` 404 · `SALE_NOT_OPEN` 409 · `SALE_CHANGED` 409 · `ITEM_NOT_SELLABLE` 400 · `NO_PRICE_SET` 409 · `PRICES_CHANGED` 409 · `INSUFFICIENT_STOCK` 409 · `INSUFFICIENT_STOCK_AT_AREA` 409 · `STOCK_RESERVED` 409 · `PAYMENT_NOT_SETTLED` 400 · `PAYMENT_METHOD_NOT_ALLOWED` 400 · `CREDIT_LIMIT_EXCEEDED` 409 · `CUSTOMER_NOT_ACTIVE` 409 · `WALKIN_DELIVERY_NEEDS_CONTACT` 409 · `DISCOUNT_PENDING_APPROVAL` 409 · `DISCOUNT_ALREADY_ACTIVE` 409 · `DISCOUNT_EXCEEDS_TRANSACTION` 400 · `VOID_WINDOW_PASSED` 409 · `SALE_HAS_DELIVERIES` 409 · `PRO_FORMA_NOT_FOUND` 404 · `PRO_FORMA_EXPIRED` 409 · `PRO_FORMA_NOT_EDITABLE` 409 · `PROSPECT_MUST_REGISTER` 409 · `RELEASE_EXCEEDS_OUTSTANDING` 400 · `WAYBILL_NOT_CANCELLABLE` 409 · `RANGE_TOO_LARGE` 400. (`DISCOUNT_EXCEEDS_LINE`, `INVOICE_EXISTS_FOR_SOURCE`, `ALREADY_DECIDED` reused.)

## 8. Event catalog

| Event | Notifies / consumed by |
|---|---|
| `cashier.till.opened` / `.closed` | — |
| `cashier.till.variance` | in-app holders of `accounting.receipts.confirm` at the location |
| `cashier.till.left-open` | in-app the cashier + `cashier.till.supervise` holders |
| `cashier.sale.completed` | Communication (commission), Dashboard, AI anomaly scorer |
| `cashier.sale.voided` | in-app Management; Communication |
| `cashier.sale.fully-supplied` | — |
| `cashier.discount.applied` / `.pending-approval` / `.approved` / `.rejected` / `.withdrawn` / `.lapsed` | pending → `cashier.discount.approve` holders; decided → the applier and the till (SSE) |
| `cashier.pro-forma.created` / `.sent` / `.converted` / `.declined` / `.expired` / `.cancelled` | pending discount approval → approvers |
| `cashier.waybill.issued` / `.delivered` / `.cancelled` | — |

## 9. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search`, `/filters` metadata, facets, `highlight`, saved views, and identical-filter exports. Cashiers holding only `cashier.sales.read` always have `cashierUserId = me` applied before any user filter (doc 03 §5.6).

### Till catalogue search (latency-critical)
`GET /catalogue?q=` is tuned for the till rather than the generic list contract. Matching order:
1. exact barcode/pack code (`stock_item_codes.code_normalized`, doc 11), which adds the item at once (`packQuantity` honoured);
2. exact item code;
3. code prefix;
4. name trigram with typo tolerance.
Results are capped at 60, with a 300 ms statement timeout. Filters: `categoryId` (chips), `inStockOnly` (default true), `priceBasis`. Sort: relevance, then name. Response includes `available` per item. "Did you mean" suggestions on zero results.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /sales` [paged, keyset] · `cashier.sales` | `ref` REFERENCE (`TXN-4502`, bare `4502`), `invoice_number` REFERENCE, `customer_name` TRIGRAM, customer code REFERENCE, customer phone PHONE, `total` AMOUNT, receipt refs (`RCT-…`, via payments) REFERENCE, line item code/name (`lines` collection) | `status` multi, `channel`, `locationId`, `tillSessionId`, `cashierUserId`, `customerId`, `customerType` multi, `correspondentId`, `paymentMethod` multi (via `payments`), `hasOnAccount`, `onAccount` range, `total`/`net` ranges, `supplyStatus` multi, `hasInstantDiscount`, `hasInvoice`, `saleDate` range/`period`, `completedAt` time-of-day range (`hourFrom`/`hourTo`, for "after-hours sales"), `lines.stockItemId` + `lines.quantity` (advanced) | `status`, `channel`, `paymentMethod`, `customerType`, `supplyStatus`, `cashier` | `completedAt desc`; `total`, `ref`, `customerName` | `{ count, gross, discounts, net, vat, total, onAccount, byMethod }` |
| `GET /sales/live` · — | customer name TRIGRAM, cashier TRIGRAM | `locationId`, `cashierUserId`, `subtotal.gte`, `hasDiscount` | — | `startedAt` | — |
| `GET /till-sessions` [paged] · `cashier.till-sessions` | till code CODE, cashier name TRIGRAM | `locationId`, `cashAccountId`, `cashierUserId`, `status`, `withVarianceOnly`, `variance` range, `forceClosed`, `openedAt` range | `status`, `till` | `openedAt desc`; `variance` | `{ sessions, totalVariance, sessionsWithVariance }` |
| `GET /instant-discounts` [paged] · `cashier.instant-discounts` | `ref` REFERENCE, sale ref REFERENCE, customer TRIGRAM, `note` FULLTEXT | `status` multi, `reason` multi, `mode`, `appliedBy`, `approvedBy`, `cashierUserId`, `pctOfBase` range, `discountAmount` range, `from`/`to` | `status`, `reason` | `createdAt desc`; `discountAmount` | `{ count, totalDiscount, aboveCeilingCount }` |
| `GET /pro-formas` [paged] · `cashier.pro-formas` | `ref` REFERENCE, `customer_name` TRIGRAM, prospect contact PHONE/CODE, `note`/terms FULLTEXT, `total` AMOUNT, line item names (`lines`) | `status` multi, `locationId`, `customerId`, `isProspect`, `preparedBy`, `issueDate`/`validUntil` ranges, `expiringWithinDays`, `total` range, `hasDiscount`, `converted` | `status` | `issueDate desc`; `validUntil`, `total` | `{ issued, converted, conversionRate, valueQuoted }` |
| `GET /waybills` [paged] · `cashier.waybills` | `ref` REFERENCE, invoice/sale ref REFERENCE, customer TRIGRAM, driver TRIGRAM, vehicle CODE, `collected_by`/`received_by` TRIGRAM | `locationId`, `customerId`, `driverId`, `status` multi, `releaseMethod`, `releaseDate` range, `unacknowledgedOlderThanDays` | `status`, `releaseMethod`, `driver` | `releaseDate desc` | `{ waybills, unitsReleased, awaitingAcknowledgement }` |
| `GET /supply/lookup?ref=` | INV / TXN reference (exact or numeric suffix) | — | — | — | invoice position |
| `GET /reports/pending-supply` · `cashier.pending-supply` | invoice/sale ref REFERENCE, customer TRIGRAM, product TRIGRAM | `status` multi, `customerId`, `locationId`, `from`/`to`, `outstanding.gte`, `oldestOutstandingDays.gte` | `status` | `invoiceDate asc` (oldest owed first) | stats block |
| `GET /reports/customer-activity` · `cashier.customer-activity` | customer code/name, invoice/receipt ref | `kind`, `customerId`, `locationId`, `paymentMethod`, `from`/`to`, `value` range | `paymentMethod` | `date desc` | header block |

**Lookups**: `GET /sales/lookup?q=&customerId=&completedOnly=true` (for returns, invoices on request); `GET /pro-formas/lookup?q=&status=SENT&customerId=` (load into till); `GET /tills/lookup?locationId=` (tills free to open).

**Global search providers**: `sale` (title `TXN-n`, subtitle `customer · ₦total · date`), `pro-forma` (`PF-n`, customer · status · valid until), `customer-waybill` (`WB-n`, invoice · customer · status). Cashier-only users get their own sales only.

**Indexes (V13)**: those in §2.3–§2.6 (sales ref, open, date, customer, cashier, invoice, name trigram, `search_vector`; pro-forma status/name/search; waybill sale/date), plus `sale_payments (tenant_id, method, sale_id)`, `sale_lines (tenant_id, stock_item_id)` (exists), `sales (tenant_id, correspondent_id, sale_date)`, `sales (tenant_id, supply_status) where supply_status in ('NOT_SUPPLIED','PARTIALLY_SUPPLIED')`, `instant_discounts (tenant_id, status, created_at desc)`, `till_sessions (tenant_id, cashier_user_id, opened_at desc)`, `customer_waybills (tenant_id, status, release_date)`, trigram on `customer_waybills(collected_by)` and on pro-forma prospect contact.

## 10. Worked example

1. 08:02 F. Adeniyi opens `TILL-IKJ-01` with ₦20,000 float.
2. 10:15 Dealer DLR-42 (Gold, wallet ₦120,000, limit ₦500,000, owing ₦210,000). Cart: 200 × Citrus 50cl at dealer ₦4,100 → gross ₦820,000. The Administrator sees the live sale and applies 5 % LOYALTY → ₦41,000, within the 10 % ceiling → `APPLIED` (`IDS-88`); the till refreshes via SSE: net ₦779,000, VAT 7.5 % ₦58,425, total ₦837,425.
3. Complete: wallet ₦120,000 + transfer ₦300,000 (GTB, ref GTB/1188) + on account ₦417,425; supply now 120, later 80. Credit: 210,000 + 417,425 = 627,425 > 500,000 → `409 CREDIT_LIMIT_EXCEEDED`. The dealer adds ₦200,000 by card: wallet 120,000 + transfer 300,000 + POS 200,000 + on account 217,425 → 427,425 ≤ 500,000 ✓.
4. Result: `TXN-4502`; `POS_SALE` out 120; reservation 80; wallet debit 120,000 (`PURCHASE_DEBIT`); `RCT-601` WALLET, `RCT-602` FUND_TRANSFER (pending), `RCT-603` POS (pending); `INV-31240` issued, paid ₦620,000 (₦500,000 pending confirmation), balance ₦217,425, due in 30 days; correspondent B. Eze captured; receipt prints "Goods outstanding: 80".
5. Next day the stock keeper looks up `INV-31240` → 80 outstanding → `WB-9168` DELIVERY, driver Musa Bello, 50 cartons from `SHR-IKJ-WH` → `CUSTOMER_WAYBILL` out 50; 30 still reserved; Pending Supply shows 85 % fulfilled.
6. 18:30 close till: counted ₦19,950 vs expected ₦20,000 (no cash taken today) → variance −₦50, Accounts notified.

## 11. Open items

1. **Cash rounding / change in coins** — none modelled (₦ kobo amounts accepted). Confirm the business doesn't round cash totals.
2. **Self-service dealer portal** (PRD §7 "Dealer … own account, if applicable") — not in this phase; the `cashier.sales.read` "own" filter would extend to `customer_id = me` when dealer logins exist.
3. **Receipt printers** — PDFs only (80 mm thermal template variant `?format=thermal` returns a 80 mm-wide PDF). ESC/POS direct printing is a frontend/bridge concern.

## 12. Verification

- Two tills selling the last 5 units concurrently: one succeeds, the other gets `INSUFFICIENT_STOCK`; available never negative.
- Deliver-later sale: on-hand unchanged, available reduced; a stock transfer of the reserved quantity is rejected; a negative adjustment below the reservation → `STOCK_RESERVED`.
- Payment split ≠ total → 400; wallet over balance → 400 and nothing written (no TXN number consumed).
- Instant discount over the ceiling: till completion blocked until approved; SSE event received by the till within 1 s of approval.
- Price published for today between cart and completion → `PRICES_CHANGED`, re-confirm succeeds.
- Void within window reverses stock, receipts, wallet and invoice; after window → 409.
- Pro-forma convert as DRAFT then discard the draft in Invoicing → order voided, reservation cancelled (consumer).
- Sales Report totals = Σ completed sale lines; equals the doc 10 Showroom VAT output VAT for the same range.
- Newman folder "16 - Sales & POS": till open → sale → discount → complete → waybill → reports → close.
