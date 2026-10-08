# 12 — Communication Module (Commissions & Correspondents)

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.communication`
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi`, `account :: spi`, `cashier :: spi`, `showroom :: spi`
Depended on by: nobody (leaf).
Migration: `V16__communication_module.sql`
Replaces: the misfiled mock in `process` (`GET/POST /api/v1/process/commissions/guide` — a flat per-*role* percentage gated by `iam.roles.manage`). That shape is wrong for the real requirement (per-product, per-unit or percentage) and is not carried forward.

The sketch names this module "Communication (Guides, Allocator, Correspondent & Reports)". The correspondent **register** (assign/reassign/de-assign, `process/Correspondent.jsx`) already lives in Customers (doc 04 §2.2), because sales and customers reference correspondents and Customers is a substrate. This module owns commissions and the two reports.

---

## 1. Scope

### 1.1 Features

| Feature | PRD | Screen |
|---|---|---|
| Comms. Guide — commission rate per finished product, per unit or percentage, effective-dated, active/inactive | 5.5.4 | `process/CommsGuide.jsx` |
| Comms Allocator — monthly allocation run from confirmed sales, ineligible lines surfaced, by-dealer / by-product views, approval, previous runs | 5.5.5 | `process/CommsAllocator.jsx` |
| Commission payments — partial or full payout against a period, to wallet / cash / bank | 5.5.5 "in preparation for commission payments or claims"; 5.7.12 earned vs paid | — (payment action on Comms Report) |
| Comms. Report — unpaid commission by agent and period | 5.7.12 | `others/CommsReport.jsx` |
| Correspondents Report — rep activity by customer: customers attended/brought in, units, revenue, share | 5.7.13 | `others/CorrespondentsReport.jsx` |

### 1.2 Decisions

1. **Two beneficiary types, one engine.** The PRD says rates drive "commissions earned by authorized dealers" (5.5.4), and the Allocator screen is "by dealer". The Comms Report lists "agents" carrying correspondent codes and regions (5.7.12). Both are real: dealers earn a per-unit commission on the products they buy and resell, and correspondents (sales reps) earn on the sales attributed to them. Every rate, run and payment carries `beneficiary_type` = `DEALER` | `CORRESPONDENT`. A tenant that only pays dealers simply never sets correspondent rates. The screens default to `DEALER`.
2. **The basis is completed, confirmed sales.** PRD §6 rule 11 says "Commission is calculated only against completed, confirmed sales transactions using the rates defined in the Comms. Guide". A sale line qualifies when the sale is `COMPLETED` (not voided) and **every receipt on it is `APPROVED` or `NOT_REQUIRED`**: no card/transfer still pending and none declined. The on-account (credit) part of a sale does not block qualification, because the sale is complete and collection is a separate matter. The setting `communication.requireInvoicePaid` (default `false`) makes on-account sales wait until their invoice is fully paid.
3. **Each sale line is allocated at most once, in the first run after it qualifies.** A run for period *P* takes every qualifying, not-yet-allocated sale line completed **on or before** the end of *P*. Most lines therefore land in their own month, and a card payment confirmed after month-end lands in the next run, flagged `late-confirmed (from Aug 2026)`. `commission_allocated_lines` enforces once-only allocation with a unique key.
4. **The rate is the one in force on the sale date, snapshotted into the run.** UI copy: "Changing a rate does not alter commissions already allocated for earlier periods". A percentage rate applies to the line's **actual net unit price** (after discounts), not the list price. The guide's `referencePrice` exists only for the "commission per unit" preview.
5. **Returns net off dealer commission.** Dealer returns of a product in the period (Showroom, doc 05) reduce that dealer's qualifying quantity for the product. If returns exceed the period's quantity, the line goes negative and offsets the dealer's other lines. A dealer whose run total would be negative is set to 0 and the shortfall is **carried forward** to their next run. This stops a dealer from being paid commission on goods they returned.
6. **Running ≠ paying.** A run is `DRAFT` (recalculable) → `APPROVED` (locked; the approver must differ from the runner) → payable. Payments are separate rows (partial payments allowed) that move money through Account (`CommissionPayouts`, doc 07 §5.6): dealers by wallet credit, cash or bank; correspondents by cash or bank. Outstanding = earned − paid, always computed.
7. **Only `ASSIGNED` correspondents earn** (doc 04: "Unassigned correspondents cannot be credited with sales or earn commission"). Sales store the correspondent captured at completion (doc 09 §1.2-6), so later reassignment never moves past commission.

---

## 2. Schema — migration `V16__communication_module.sql`

All tables: `tenant_id` + standard RLS (omitted).

### 2.1 `commission_rates`

```sql
create table commission_rates (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null references stock_items(id),   -- a finished product (FINISHED_PRODUCT, OUTSOURCED or DIRECT_ENTRY sold through POS)
    beneficiary_type varchar(14) not null check (beneficiary_type in ('DEALER','CORRESPONDENT')),
    rate_type varchar(10) not null check (rate_type in ('PER_UNIT','PERCENTAGE')),
    rate numeric(14,4) not null check (rate >= 0),           -- ₦ per unit, or % of net unit price
    reference_price numeric(14,2),                            -- price used for the preview (defaults to the current retail/dealer price)
    status varchar(10) not null check (status in ('ACTIVE','INACTIVE')),   -- INACTIVE row = "earns nothing from this date"
    effective_from date not null,
    effective_to date,                                        -- set when a later row supersedes this one
    note varchar(300),
    set_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint cr_pct_range check (rate_type <> 'PERCENTAGE' or rate <= 100),
    constraint cr_dates check (effective_to is null or effective_to >= effective_from),
    unique (tenant_id, stock_item_id, beneficiary_type, effective_from)
);
create index cr_lookup_idx on commission_rates (tenant_id, stock_item_id, beneficiary_type, effective_from desc);
```
The rate in force for (item, type) on date *d* is the row with the greatest `effective_from ≤ d`. No row, or an `INACTIVE` row, means **ineligible**. Rows are never updated except `effective_to` (set automatically) and future-dated rows (`effective_from > today`), which may be edited or withdrawn before they take effect.

### 2.2 `commission_runs` / `commission_run_lines` / `commission_allocated_lines`

```sql
create table commission_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                 -- CA-n
    beneficiary_type varchar(14) not null check (beneficiary_type in ('DEALER','CORRESPONDENT')),
    period_month date not null check (extract(day from period_month) = 1),   -- first day of the month
    location_id uuid references locations(id),               -- null = company-wide (default); a showroom-only run is allowed
    status varchar(12) not null check (status in ('DRAFT','APPROVED','CANCELLED')),
    total_commission numeric(14,2) not null default 0,
    beneficiary_count int not null default 0,
    eligible_lines int not null default 0,
    ineligible_lines int not null default 0,
    pending_confirmation_lines int not null default 0,        -- sales in the period not yet qualifying (info only)
    calculated_at timestamptz not null,
    run_by_user_id uuid not null references app_users(id),
    approved_by_user_id uuid references app_users(id),
    approved_at timestamptz,
    cancelled_reason varchar(300),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create unique index commission_runs_live_uq on commission_runs (tenant_id, beneficiary_type, period_month, coalesce(location_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where status <> 'CANCELLED';

create table commission_run_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null references commission_runs(id),
    beneficiary_id uuid not null,                             -- customers.id (DEALER) or correspondents.id (CORRESPONDENT)
    beneficiary_code varchar(20) not null,
    beneficiary_name varchar(200) not null,
    region varchar(60),                                       -- correspondent region / dealer's home showroom name
    stock_item_id uuid not null references stock_items(id),
    item_code varchar(40) not null,
    item_name varchar(200) not null,
    qty_sold numeric(14,3) not null,
    qty_returned numeric(14,3) not null default 0,            -- DEALER only
    net_qty numeric(14,3) not null,                           -- qty_sold − qty_returned (may be negative)
    sales_value numeric(14,2) not null,                       -- Σ line_net of the qualifying lines (context for the report)
    rate_id uuid references commission_rates(id),             -- null when ineligible
    rate_type varchar(10),
    rate numeric(14,4),
    unit_commission numeric(14,4) not null default 0,         -- PER_UNIT: rate; PERCENTAGE: Σ(line_net × rate%) / qty (weighted)
    commission numeric(14,2) not null,                        -- may be negative from returns
    eligible boolean not null,
    ineligible_reason varchar(30) check (ineligible_reason in ('NO_RATE','RATE_INACTIVE','CORRESPONDENT_NOT_ASSIGNED')),
    late_from_period date                                     -- set when lines from an earlier month qualified late
);
create index crl_run_idx on commission_run_lines (tenant_id, run_id, beneficiary_id);

create table commission_run_beneficiaries (                   -- per-beneficiary totals of a run (what is payable)
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null references commission_runs(id),
    beneficiary_id uuid not null,
    beneficiary_code varchar(20) not null,
    beneficiary_name varchar(200) not null,
    gross_commission numeric(14,2) not null,                  -- Σ lines (may be negative)
    carried_in numeric(14,2) not null default 0,              -- negative balance brought from the previous run (≤ 0)
    earned numeric(14,2) not null check (earned >= 0),        -- max(0, gross + carried_in)
    carried_out numeric(14,2) not null default 0,             -- min(0, gross + carried_in) carried to the next run
    unique (run_id, beneficiary_id)
);

create table commission_allocated_lines (                     -- once-only guard (§1.2-3)
    tenant_id uuid not null references tenants(id),
    beneficiary_type varchar(14) not null,
    sale_line_id uuid not null,                               -- cashier sale_lines.id; for returns: customer_return_lines.id
    source varchar(10) not null check (source in ('SALE','RETURN')),
    run_id uuid not null references commission_runs(id),
    primary key (tenant_id, beneficiary_type, source, sale_line_id)
);
```
`commission_allocated_lines` rows are written when a run is **approved**, not when it is calculated. Cancelling a `DRAFT` frees nothing because nothing was claimed. An `APPROVED` run cannot be cancelled once any payment exists (§4.3).

### 2.3 `commission_payments`

```sql
create table commission_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                 -- CP-n
    run_id uuid not null references commission_runs(id),
    beneficiary_type varchar(14) not null,
    beneficiary_id uuid not null,
    beneficiary_name varchar(200) not null,
    amount numeric(14,2) not null check (amount > 0),
    method varchar(10) not null check (method in ('WALLET','CASH','BANK')),
    cash_account_id uuid,                                     -- account ids (plain uuids, cross-module)
    bank_account_id uuid,
    paid_via varchar(120),                                    -- 'Transfer to GTB 0123…' (last 4 digits only in events)
    account_cash_transaction_id uuid,
    account_bank_transaction_id uuid,
    customer_ledger_entry_id uuid,
    paid_at timestamptz not null default now(),
    paid_by_user_id uuid not null references app_users(id),
    note varchar(300),
    status varchar(10) not null default 'PAID' check (status in ('PAID','REVERSED')),
    reversed_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    unique (tenant_id, ref)
);
create index cp_beneficiary_idx on commission_payments (tenant_id, beneficiary_type, beneficiary_id, run_id);
```

### 2.4 Module settings registered

| Key | Type | Default |
|---|---|---|
| `communication.requireInvoicePaid` | boolean | false |
| `communication.allowLocationRuns` | boolean | false (company-wide runs only, so one period has one run) |
| `communication.minimumPayout` | decimal | 0.00 (payments below it are rejected; small balances accumulate) |

---

## 3. Permission codes

The PRD restricts the Comms. Guide and the Allocator to the Administrator.

| Code | Allows | Default roles |
|---|---|---|
| `communication.guide.read` | View rates | Administrator, Management, Accounts Personnel |
| `communication.guide.manage` | Set/withdraw rates | Administrator |
| `communication.allocation.read` | View runs and lines | Administrator, Management, Accounts Personnel |
| `communication.allocation.run` | Calculate / recalculate / cancel a DRAFT run | Administrator |
| `communication.allocation.approve` | Approve a run (≠ runner) | Administrator, Management |
| `communication.payments.manage` | Pay / reverse commission | Accounts Personnel |
| `communication.reports.read` | Comms. Report, Correspondents Report | Administrator, Management, Accounts Personnel |

A Correspondent-role user holding `customers.read.own` (doc 04) may read **their own** Correspondents Report rows and their own commission position via `mine=true` (perm `communication.reports.read` is not required for `mine=true`; the filter is forced to their linked correspondent record).

---

## 4. Endpoints (all under `/api/v1/communication`)

### 4.1 Comms. Guide — `/rates`

- **`GET /rates`** — perm `communication.guide.read`. One row per product × beneficiary type, showing the rate in force **today** (plus `nextChange` when a future-dated row exists). Query `beneficiaryType` (default `DEALER`), `categoryId` (group), `rateType`, `status`, `q` (code CODE, name TRIGRAM), `includeUnrated` (default true: finished products with no rate appear with `status: "Not set"`).
  Row: `{ stockItemId, code, product, group, unit, sellingPrice (current retail; dealer price for DEALER rows), rateType, rate, commissionPerUnit, effectiveFrom, status ('Active'|'Inactive'|'Not set'), nextChange: { effectiveFrom, rateType, rate, status } | null }`.
  `commissionPerUnit` = `rate` for PER_UNIT; `sellingPrice × rate / 100` for PERCENTAGE (a preview figure; runs use actual net prices, §1.2-4).
  Header: `{ productsRated, activeRates, perUnitRates, averagePerUnit (over active rates) }`.
- **`GET /rates/{stockItemId}/history?beneficiaryType=`** — every row for the product, newest first.
- **`POST /rates`** — perm `communication.guide.manage`.
  ```json
  { "stockItemId": "…", "beneficiaryType": "DEALER", "rateType": "PER_UNIT", "rate": 45.00,
    "referencePrice": 4100.00, "effectiveFrom": "2026-11-01", "status": "ACTIVE", "note": "Q4 dealer push" }
  ```
  Rules: the item must be sellable-type (`FINISHED_PRODUCT`, `OUTSOURCED`, `DIRECT_ENTRY`, `IN_HOUSE_RECIPE` sold as product) and not a variant parent (`400 ITEM_HAS_VARIANTS`); `effectiveFrom ≥ today` → else `400 RATE_CANNOT_BE_BACKDATED` (a backdated rate would change sales that may already be allocated); a row already exists for that date → `409 RATE_ALREADY_SET_FOR_DATE`. The previous row's `effective_to` = `effectiveFrom − 1`. Emits `communication.rate.set`.
  **Bulk**: `POST /rates/bulk` `{ beneficiaryType, effectiveFrom, rows: [ { stockItemId, rateType, rate, status } ] }` (≤ 500) → `{ created, skipped: [ { row, reason } ] }` (doc 03 §12.1 `BulkImporter`).
- **`PUT /rates/{id}`** — only rows with `effective_from > today`: change `rateType`/`rate`/`status`/`effectiveFrom`/`note`. **`DELETE /rates/{id}`** — withdraw a future row (it was never in force, so this is not a business-record delete); the predecessor's `effective_to` is restored to null.

### 4.2 Allocation runs — `/runs`

- **`POST /runs`** — perm `communication.allocation.run`. `{ beneficiaryType, periodMonth: "2026-09", locationId? (only if communication.allowLocationRuns) }`.
  - `periodMonth` must have ended (`409 PERIOD_NOT_CLOSED`) — a run for the current month would miss sales still to come.
  - A non-cancelled run already exists for the key → `409 RUN_EXISTS` (recalculate it instead).
  - Runs must be approved in month order per beneficiary type → an earlier `DRAFT` run blocks → `409 EARLIER_RUN_NOT_APPROVED`. Carry-forwards and once-only allocation depend on this order.
  Calculation, in one transaction:
  1. **Sales basis**: `SalesQueries.linesFor(completedTo = end of period, qualifying = true, notAllocatedFor = beneficiaryType)`, which returns sale lines with `{ saleId, saleDate, customerId, customerType, correspondentId, stockItemId, quantity, lineNet, receiptsConfirmed, invoicePaid }`. Qualifying means §1.2-2. Lines from sales completed in the period that don't qualify yet are counted into `pending_confirmation_lines` (not allocated).
  2. **Beneficiary**: `DEALER` → `customerId` where `customerType = AUTHORISED_DEALER` at sale time (other customers earn nothing; their lines are simply not part of a dealer run). `CORRESPONDENT` → `correspondentId`. If that correspondent is not `ASSIGNED` today (`Correspondents.get`), the line is `ineligible: CORRESPONDENT_NOT_ASSIGNED`.
  3. **Returns** (`DEALER` only): `ShowroomQueries.returns(customerType DEALER, to = end of period, notAllocated)` → returned quantity per dealer × product.
  4. Group by (beneficiary, product). Pick the rate in force on each line's **sale date** (lines of one group can straddle a rate change; the line stores the rate of the majority quantity, and `commission` is the exact sum). PER_UNIT → `qty × rate`; PERCENTAGE → `Σ line_net × rate / 100`. Returns are valued at the same unit commission. Half-up, 2 dp per group.
  5. No rate / inactive → `eligible false`, `commission 0`, `ineligible_reason`.
  6. Per beneficiary: `gross = Σ lines`; `carried_in` = the previous approved run's `carried_out` for them; `earned = max(0, gross + carried_in)`; `carried_out = min(0, gross + carried_in)`.
  7. `ref = next("CA")`, status `DRAFT`.
  Response: the run (§ below).
- **`POST /runs/{id}/recalculate`** — `DRAFT` only (picks up rates/confirmations that changed since). Same ref.
- **`POST /runs/{id}/approve`** — perm `communication.allocation.approve`; approver ≠ `run_by` → `403 APPROVER_MUST_DIFFER`. Recalculates first, and fails if the totals changed since the caller last viewed them (`{ expectedTotal }` in the body; mismatch → `409 RUN_CHANGED` with the new totals). Writes `commission_allocated_lines` for every eligible and ineligible sale/return line (ineligible lines are also consumed, so a later rate change can't retroactively award them; "Set a rate before allocating if they should qualify"). Status `APPROVED`. Emits `communication.run.approved`.
- **`POST /runs/{id}/cancel`** — `{ reason }`. `DRAFT` → `CANCELLED`. An `APPROVED` run with no payments → `CANCELLED`, and its `commission_allocated_lines` are deleted (they were bookkeeping guards, not business records) so the lines can be re-run. With payments → `409 RUN_HAS_PAYMENTS`.
- **`GET /runs`** [paged] — perm `communication.allocation.read`. Query `beneficiaryType`, `status`, `from`/`to` (period). Row `{ id, ref, period ("September 2026"), beneficiaryType, beneficiaries, total, paid, outstanding, status ('Draft'|'Approved'|'Paid'|'Part paid'|'Cancelled' — Paid/Part paid derived from payments), runOn, runBy, approvedBy }` = the "Previous runs" cards.
- **`GET /runs/{id}?view=beneficiary|line&beneficiaryId=`** — perm `communication.allocation.read`.
  - `view=beneficiary` (screen "By dealer"): rows `{ beneficiaryId, code, name, region, products (distinct eligible items), units (net), commission (earned), sharePct, carriedIn, carriedOut, paid, outstanding }`.
  - `view=line` (screen "By product line"): rows `{ beneficiary, code, product, qtySold, qtyReturned, netQty, rateType, unitRate, commission, eligible ('Yes'|'No rate'|'Inactive'|'Rep not assigned'), lateFromPeriod }`.
  - Header: `{ ref, period, status, beneficiaries, units, commissionPayable, topEarner { name, amount }, ineligible: { lines, products: [names] }, pendingConfirmation: { lines, value } }` — the warning callout "n sales lines earned no commission".
- **`GET /runs/{id}/export.csv?view=`** — the export button.

### 4.3 Payments — `/payments`

- **`POST /payments`** — perm `communication.payments.manage`.
  ```json
  { "runId": "…", "beneficiaryId": "…", "amount": 40000.00, "method": "BANK",
    "bankAccountId": "…", "cashAccountId": null, "paidVia": "Transfer to Access 0123456789", "note": "Part payment" }
  ```
  Rules: run `APPROVED` → else `409 RUN_NOT_APPROVED`. `amount ≤ outstanding` (earned − Σ PAID payments) for that beneficiary in that run → `400 PAYMENT_EXCEEDS_OUTSTANDING` (details `{ outstanding }`). `amount ≥ communication.minimumPayout`. `WALLET` only for `DEALER`. Calls `CommissionPayouts.payOut` (doc 07 §5.6) in the same transaction (cash/bank sufficiency is enforced there). `ref = next("CP")`. Emits `communication.commission.paid`.
  **Pay a whole run**: `POST /runs/{id}/pay-all { method: WALLET|BANK|CASH, bankAccountId?, cashAccountId? }` pays every beneficiary's outstanding in one transaction (WALLET for a correspondent run → `400`). It returns per-beneficiary refs, and fails as a whole if the paying account can't cover the total.
- **`POST /payments/{id}/reverse`** — `{ reason }`; perm `communication.payments.manage`. Same business day only, otherwise `409 PAYMENT_NOT_REVERSIBLE` (then record a correcting entry in Account). Reverses through Account (WALLET → `WalletLedger.reverse`, which fails if the wallet was already spent; CASH/BANK → reversing entries). Emits `communication.commission.payment-reversed`.
- **`GET /payments`** [paged] — query `beneficiaryType`, `beneficiaryId`, `runId`, `method`, `from`, `to`.

### 4.4 Comms. Report — `GET /reports/commissions`

Perm `communication.reports.read` (or `mine=true`). Query `beneficiaryType` (default `CORRESPONDENT` — the screen says "agents"), `month`, `year` (or `from`/`to` periods), `region`, `status` (`UNPAID`|`PART_PAID`|`SETTLED`), `beneficiaryId`.
Row per beneficiary × period (approved runs only): `{ agent (name), code, region, period ("August 2026"), sales (Σ sales_value), earned, paid, outstanding, dealers (DEALER: 1; CORRESPONDENT: distinct customers in the run's lines), status ('Unpaid'|'Part paid'|'Settled') }`.
Stats: `{ agents, earned, paid, outstanding, unpaidAgents }`. Rows with `outstanding = 0` are included only when `status=SETTLED` or no status filter is given. The report's purpose is "unpaid commissions", so the frontend defaults to `UNPAID,PART_PAID`.

### 4.5 Correspondents Report — `GET /reports/correspondents`

Perm `communication.reports.read` (or `mine=true`). Query `locationId` (showroom; required unless the caller has ALL_LOCATIONS), `from`, `to`, `correspondentId`, `view` = `rep` (default) | `customer`. Source: `SalesQueries.linesFor` (completed, non-voided sales with a `correspondentId`, in range). Unlike commission, this report shows activity regardless of receipt confirmation, with a `pendingConfirmationValue` column so the two can be reconciled.
- `view=customer` rows: `{ rep, repCode, showroom, customer, customerCode, newCustomer, transactions, units, revenue (Σ line_net), pendingConfirmationValue }`. **`newCustomer`** ("brought in") = the customer's first-ever completed sale falls inside the range and was attributed to this rep.
- `view=rep` rows: `{ rep, repCode, region, status, customers, newCustomers, transactions, units, revenue, sharePct }`.
- Stats: `{ reps, customersServed, newCustomers, revenue, topRep { name, revenue } }`.
Revenue is VAT-exclusive (line net), the same basis as commission percentage rates.

---

## 5. Exposed to other modules — `com.zifiq.communication.spi` (read-only)

```kotlin
interface CommissionQueries {
    fun allocatedFor(filter: CommissionFilter): List<CommissionTotal>   // approved runs: { periodMonth, beneficiaryType, locationId?, earned, paid }
}
```
Used by Reports (doc 13 SCI "Commissions" line, at the amount allocated for the period whether or not it is paid), Dashboard and AI. Like `cashier::spi`, it never exposes a write.

This module itself uses `SalesQueries.linesFor` — the doc 09 `SalesLineFilter` gains `qualifyingForCommission: Boolean`, `notAllocatedFor: BeneficiaryType?` and the row gains `receiptsConfirmed`/`invoicePaid`, computed by cashier from `Receipts.bySource` / `Receivables`. The "not allocated" filter is applied by this module after the call (cashier doesn't read communication tables); `linesFor` is paged internally.

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| C1 | Commission only on completed, confirmed sales at Comms. Guide rates | PRD §6 rule 11 | §1.2-2, §4.2 step 1 |
| C2 | Per-product rates, per unit or % of price | `CommsGuide.jsx` | §2.1 |
| C3 | Rate changes don't alter earlier allocations | `CommsGuide.jsx` note | effective dating + run snapshot + no backdating |
| C4 | Inactive / unrated products earn nothing and are shown as ineligible | `CommsAllocator.jsx` | §4.2 step 5, header `ineligible` |
| C5 | Unassigned correspondents earn nothing | doc 04 / `Correspondent.jsx` | §4.2 step 2 |
| C6 | Monthly computation | PRD 5.5.4 | `period_month` |
| C7 | Allocation ≠ payment; partial payments; outstanding = earned − paid | `CommsReport.jsx` | §2.3, §4.3 |
| C8 | Admin-only guide and allocator | PRD 5.5.4/5.5.5 | §3 |
| C9 | A sale line earns once per beneficiary type | derived | `commission_allocated_lines` PK |
| C10 | Returned goods don't earn | derived | §1.2-5 |
| C11 | Wallet credits update the wallet immediately and never make it negative on reversal | rule 5 | doc 04 `WalletLedger` |

## 7. Errors added

`RATE_CANNOT_BE_BACKDATED` 400 · `RATE_ALREADY_SET_FOR_DATE` 409 · `RATE_NOT_EDITABLE` 409 · `PERIOD_NOT_CLOSED` 409 · `RUN_EXISTS` 409 · `EARLIER_RUN_NOT_APPROVED` 409 · `RUN_NOT_DRAFT` 409 · `RUN_NOT_APPROVED` 409 · `RUN_CHANGED` 409 · `RUN_HAS_PAYMENTS` 409 · `APPROVER_MUST_DIFFER` 403 · `PAYMENT_EXCEEDS_OUTSTANDING` 400 · `PAYMENT_BELOW_MINIMUM` 400 · `PAYMENT_NOT_REVERSIBLE` 409 · `WALLET_NOT_AVAILABLE_FOR_PAYEE` 400 (raised by Account). (`ITEM_HAS_VARIANTS` reused.)

## 8. Event catalog

| Event | Notifies |
|---|---|
| `communication.rate.set` / `.withdrawn` | — |
| `communication.run.calculated` | in-app holders of `communication.allocation.approve` ("CA-91 September 2026 ready for approval — ₦2.14m") |
| `communication.run.approved` | in-app holders of `communication.payments.manage` |
| `communication.run.cancelled` | — |
| `communication.commission.paid` / `.payment-reversed` | — |

Consumes (doc 03 §7.6, consumer `communication.sale-watch`): `cashier.sale.voided`. If the voided sale's lines were already allocated in an approved run, it creates a **negative adjustment line** in the beneficiary's next run (`ineligible_reason` null, `commission` negative, `late_from_period` = original period) instead of touching the approved run.

## 9. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search`, `/filters` metadata, facets, saved views, and identical-filter exports.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /rates` · `communication.rates` | product code CODE, name TRIGRAM | `beneficiaryType`, `categoryId` multi, `rateType`, `status` (Active/Inactive/Not set), `commissionPerUnit` range, `effectiveFrom` range, `hasNextChange`, `includeUnrated` | `rateType`, `status`, `group` | `product`; `commissionPerUnit`, `effectiveFrom` | `{ productsRated, activeRates, perUnitRates, averagePerUnit }` |
| `GET /runs` [paged] · `communication.runs` | `ref` REFERENCE | `beneficiaryType`, `status` multi incl. derived `PAID`/`PART_PAID`, `periodMonth` range, `runBy`, `approvedBy`, `total` range | `status`, `beneficiaryType` | `periodMonth desc` | `{ runs, total, paid, outstanding }` |
| `GET /runs/{id}?view=` (lines) · `communication.run-lines` | beneficiary code REFERENCE, name TRIGRAM, product code/name | `beneficiaryId`, `eligible`, `ineligibleReason`, `stockItemId`, `late` (has `lateFromPeriod`), `commission` range, `netQty` range, `region` | `eligible`, `ineligibleReason`, `region` | `commission desc`; `units`, `name` | header block |
| `GET /payments` [paged] · `communication.payments` | `ref` REFERENCE, beneficiary name TRIGRAM, `paid_via` TRIGRAM, `amount` AMOUNT | `beneficiaryType`, `beneficiaryId`, `runId`, `method` multi, `status`, `paidBy`, `amount` range, `from`/`to` | `method`, `status` | `paidAt desc` | `{ count, total by method }` |
| `GET /reports/commissions` · `communication.comms-report` | agent code REFERENCE, name TRIGRAM | `beneficiaryType`, `month`/`year` or `from`/`to` periods, `region` multi, `status` multi, `beneficiaryId`, `outstanding` range | `status`, `region` | `outstanding desc`; `earned`, `agent` | `{ agents, earned, paid, outstanding, unpaidAgents }` |
| `GET /reports/correspondents` · `communication.correspondents-report` | rep code/name, customer code/name/phone | `locationId`, `from`/`to`/`period`, `correspondentId`, `view`, `newCustomersOnly`, `revenue` range, `customerType` | `rep`, `customerType` | `revenue desc`; `transactions`, `units` | `{ reps, customersServed, newCustomers, revenue, topRep }` |

**Lookups**: `GET /rates/lookup?q=&beneficiaryType=` (product picker showing its current rate); beneficiary pickers use `customers/lookup?types=AUTHORISED_DEALER` and `customers/correspondents/lookup`.

**Global search provider**: `commission-run` (title `CA-n`, subtitle `period · beneficiary type · ₦total · status`) and `commission-payment` (`CP-n`).

**Indexes (V16)**: `cr_lookup_idx` (exists) plus `(tenant_id, beneficiary_type, status, effective_from)`; `commission_runs (tenant_id, beneficiary_type, period_month desc)`; `crl_run_idx` (exists) plus `(tenant_id, beneficiary_id)`; `cp_beneficiary_idx` (exists) plus trigram on `lower(beneficiary_name)` for payments and run lines.

## 10. Worked example

1. Admin sets DEALER rates from 1 Sep: Citrus 50cl PER_UNIT ₦45; Malt 33cl PERCENTAGE 2 %; Ginger 50cl INACTIVE.
2. September: DLR-42 buys 1,200 Citrus (all receipts approved) and 300 Malt at net ₦4,000 (one ₦600,000 card receipt still pending on 30 Sep), and returns 20 Citrus. DLR-17 buys 80 Ginger.
3. 2 Oct Admin runs `CA-91` (DEALER, Sep): DLR-42 Citrus net 1,180 × ₦45 = ₦53,100; Malt not qualifying (pending card) → counted in `pendingConfirmation`; DLR-17 Ginger ineligible (Inactive) → callout. Total ₦53,100.
4. Management approves (`expectedTotal 53,100`). Accounts pays DLR-42 ₦53,100 to the wallet → `CP-40`, `COMMISSION_CREDIT`.
5. The card receipt is approved on 3 Oct. The October run `CA-95` includes the Malt line: 300 × 4,000 × 2 % = ₦24,000, flagged `late-confirmed (from Sep 2026)`.

## 11. Open items

1. **Dealer commission basis**: this design pays dealers on what they *buy* from the company (the only sales the system records). If the business means the dealer's own onward sales (not captured), commission needs a dealer-reported sales feed, which is out of scope.
2. **Withholding tax** on commission payouts (Nigerian WHT, typically 5–10 % on commissions): not modelled. If required, add `wht_rate`/`wht_amount` to payments and a WHT liability posting in Account.

## 12. Verification

- Rate effective 1 Oct doesn't change a September run, even when recalculated in October.
- A sale with a pending card receipt is excluded, then included in the next run after approval; never twice (try running both periods again → no duplicate lines).
- Returns exceeding purchases → dealer earned 0, carry-forward appears in the next run's `carriedIn`.
- Approve as the runner → 403; pay more than outstanding → 400; pay-all with an under-funded bank → whole call fails, nothing paid.
- Voiding an allocated sale yields a negative adjustment in the next run.
- Comms Report totals = Σ approved runs' earned − Σ PAID payments.
- Newman folder "19 - Communication".
