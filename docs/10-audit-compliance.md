# 10 — Audit & Compliance Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.history` (package name kept; module display name "Audit & Compliance")
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi`, `account :: spi`, `invoicing :: spi`, `cashier :: spi`, `showroom :: spi`
Depended on by: nobody (leaf). Reports (doc 13) and Dashboard (doc 14) read compliance figures through their own queries over the same sources, not through this module.
Migration: `V14__audit_compliance_module.sql`
Replaces: the mock `HistoryService` (single hard-coded rows for waybill/expense/stock-movement/fraud/wallet history) and the `process` module's mock Error Handler and Recycle Bin.

---

## 1. Scope

### 1.1 Features

| Feature | PRD | Screen | Data source |
|---|---|---|---|
| Waybill History (supply history of a sales invoice) | 5.6.1 | `history/WaybillHistory.jsx` | `cashier::spi.SalesQueries.supplyHistory` |
| Expenses/Payment Reports | 5.6.2 | `history/ExpensesReport.jsx` | `account::spi.AccountQueries.expenses` |
| Stock Movement History (long-range, all locations) | 5.6.3 | `history/StockMovementHistory.jsx` | inventory ledger (OPEN) |
| Violations/Fraud History (record, investigate, conclude) | 5.6.4 | `history/ViolationsHistory.jsx` | own tables |
| Wallet Withdrawal History (+ audit review, integrity check) | 5.6.5 | `history/WalletWithdrawalHistory.jsx` | `account::spi.AccountQueries.withdrawals` + own reviews |
| Showroom VAT Report / Factory VAT Report (output vs input VAT) | 5.2.10, 5.1.23 | `showroom/ShowroomVATReport.jsx`, factory nav | sales, credit notes, purchases (§4.6) |
| VAT return summary (period, per location and company) | sketch "VAT compliance" | — (new API; screen later) | same as above |
| Error Handler (product data-quality flags and corrections) | 5.5.6 | `process/ErrorHandler.jsx` | own tables + inventory |
| Recycle Bin (deleted products: restore/purge; suspended from sale: resume/reprice) | 5.5.7 | `process/RecycleBin.jsx` | inventory (OPEN) + own log |
| Activity log (who did what, incl. cost-data access) | doc 03 §7; PRD §9 "audit access to cost fields" | — (new API; screen later) | `domain_events` |
| Audit reviews (mark any record reviewed/flagged) | `WalletWithdrawalHistory.jsx` "Under review — awaiting audit" | — | own table |
| Integrity checks (ledger/balance consistency) | PRD §5.6.4 "All financial entries must be reflected consistently"; `WalletWithdrawalHistory.jsx` "one record breaches this" | — | §4.10 |

### 1.2 Decisions

1. **A read layer with three owned record types.** History reads every other module's data through their `spi` (or the OPEN inventory module) and owns only what nobody else does: violation records, data-quality flags, audit reviews, integrity-check results, and the recycle-bin log. It never writes another module's tables except inventory's (OPEN) stock-item status/name, through inventory's own service methods.
2. **Corrections never rewrite issued documents.** The Error Handler corrects the *master* record (stock item name, brand, unit, category). Screens and reports that join to the master show the corrected text. Issued documents (invoices, credit notes, waybills, receipts, pro-formas) keep the text they were issued with, because PRD rule 14 makes issued invoices immutable and the same principle applies to every legal document. The screen's note "historic transactions … display the corrected description" is therefore implemented for reports and stock cards, not for issued documents; the correction response states how many documents keep the original text.
3. **Spell checking without a dictionary.** Product names are full of brand words, sizes and codes ("Malt Classic 33cl"), so a language dictionary would flag almost everything. Flags come from the tenant's own vocabulary using two stable PostgreSQL contrib extensions: `pg_trgm` (already enabled, doc 03 §4) and `fuzzystrmatch` (`levenshtein`). A word that appears in very few products and sits within edit distance 1–2 of a word used in many products is a likely misspelling ("Drnk" → "Drink"). Near-identical whole names are naming inconsistencies ("Citrus Drink 50 cl" vs "Citrus Drink 50cl"). Users can also flag manually. Details are in §4.8.
4. **Deleting a product is a status, never a row delete** (doc 03 §13). `DELETED` products sit in the Recycle Bin for a retention period with their stock frozen, and can be restored. Purge sets `PURGED`: the item leaves the bin and every picker, but its history stays intact. Any stock still held is written off at purge with an explicit confirmation.
5. **Violation records are confidential audit entries, not accusations.** The UI wording is "Outcome, not accusation". Only holders of `history.violations.*` see them, the subject is never notified automatically, and nothing is ever deleted. A record is closed with an outcome (`SUBSTANTIATED`, `RESOLVED`, `DISMISSED`) and a written conclusion. System detectors propose **alerts**; a person decides whether an alert becomes a record (PRD §6 rule 13's principle of human confirmation, applied to accusations).
6. **VAT figures come from the same rows the transactions wrote.** Output VAT is Σ `sale_lines.vat_amount` minus credit-note VAT. Input VAT is Σ the input VAT stored on purchase documents. So the VAT report reconciles exactly with the Sales Report (doc 09 §4.6) and the invoices.

---

## 2. Schema — migration `V14__audit_compliance_module.sql`

All tables: `tenant_id` + standard RLS (omitted).

### 2.1 Extensions and inventory changes

```sql
create extension if not exists fuzzystrmatch;      -- levenshtein(); PostgreSQL contrib, ships with the postgres:16 image

-- product lifecycle for the Recycle Bin (inventory-owned table, OPEN module)
alter table stock_items drop constraint stock_items_status_check;
alter table stock_items add constraint stock_items_status_check check (status in ('ACTIVE','SUSPENDED','DELETED','PURGED'));
alter table stock_items
    add column status_reason varchar(300),
    add column status_changed_at timestamptz,
    add column status_changed_by_user_id uuid references app_users(id),
    add column suspension_review_on date,          -- SUSPENDED: date to revisit (Recycle Bin "Review on")
    add column deleted_retention_until date;       -- DELETED: purge-eligible after this date

-- input VAT on factory raw-material deliveries (shipped stock_receipts, V4)
alter table stock_receipts
    add column supplier_invoice_ref varchar(60),
    add column input_vat_rate numeric(5,2) not null default 0 check (input_vat_rate between 0 and 100),
    add column input_vat_amount numeric(14,2) not null default 0 check (input_vat_amount >= 0);

alter table stock_ledger_entries drop constraint stock_ledger_entries_source_type_check;
alter table stock_ledger_entries add constraint stock_ledger_entries_source_type_check check (source_type in (
    'REFILL','RECEIVE','PRODUCTION_RUN','TRANSFER_OUT','TRANSFER_IN','QUANTITY_ADJUSTMENT','TRADE_OUT','TRADE_IN',
    'TRANSFER_REJECTED_IN','DIRECT_ENTRY','OUTSOURCE_RECEIPT','CUSTOMER_RETURN','AREA_MOVE_OUT','AREA_MOVE_IN',
    'POS_SALE','CUSTOMER_WAYBILL','CUSTOMER_WAYBILL_CANCEL','SALE_VOID',
    'PURGE_WRITE_OFF'           -- stock still held when a deleted product is purged (§4.9)
));
```
Inventory's raw-material receipt endpoint (shipped `POST /api/v1/inventory/stock/receipts`) gains optional `supplierInvoiceRef`, `inputVatRate` (0–100). `input_vat_amount = quantity × unit_cost × rate / 100` (half-up, 2 dp), with `unit_cost` VAT-exclusive.

**Status semantics** (enforced in `StockLedgerRepository.postEntry` and every picker):

| Status | Sellable | Any stock movement | In pickers | Shown in |
|---|---|---|---|---|
| `ACTIVE` | yes | yes | yes | everywhere |
| `SUSPENDED` | no | no — PRD §6 rule 1 ("cannot process a transaction against a suspended … product") | hidden (filter `includeSuspended`) | Recycle Bin › Suspended; stock reports |
| `DELETED` | no | no | hidden | Recycle Bin › Deleted; stock reports (flagged) |
| `PURGED` | no | no (except the purge write-off itself) | hidden | historical documents/reports only |

Shipped `PUT /api/v1/inventory/stock-items/{id}/status` keeps `ACTIVE ↔ SUSPENDED` only. `DELETED`/`PURGED` are reachable only through §4.9.

### 2.2 `violation_records` / `violation_record_events`

```sql
create table violation_records (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- VN-n
    subject_user_id uuid not null references app_users(id),   -- the staff member
    location_id uuid references locations(id),             -- showroom/factory where it occurred
    category varchar(30) not null check (category in (
        'DEALER_FUNDS_TAMPERING','UNAUTHORISED_WITHDRAWAL','CASH_SHORTAGE','STOCK_DISCREPANCY',
        'UNAUTHORISED_DISCOUNT','RECEIPT_MANIPULATION','DATA_MANIPULATION','POLICY_BREACH','OTHER')),
    description varchar(2000) not null,                    -- "Record" column
    amount_involved numeric(14,2) check (amount_involved is null or amount_involved >= 0),
    severity varchar(10) not null check (severity in ('LOW','MEDIUM','HIGH')),
    status varchar(20) not null default 'UNDER_INVESTIGATION'
        check (status in ('UNDER_INVESTIGATION','SUBSTANTIATED','RESOLVED','DISMISSED')),
    occurred_on date,
    raised_by_user_id uuid not null references app_users(id),
    raised_at timestamptz not null default now(),
    source varchar(10) not null default 'MANUAL' check (source in ('MANUAL','ALERT')),
    audit_alert_id uuid,                                   -- the alert it was opened from
    linked_refs jsonb not null default '[]'::jsonb,        -- [{ "type": "RECEIPT", "ref": "RCT-552" }, { "type": "WALLET_WITHDRAWAL", "ref": "WW-31" }]
    conclusion varchar(2000),                              -- required to leave UNDER_INVESTIGATION
    concluded_by_user_id uuid references app_users(id),
    concluded_at timestamptz,
    search_vector tsvector generated always as (to_tsvector('simple', ref || ' ' || description)) stored,
    unique (tenant_id, ref),
    constraint vr_concluded check ((status = 'UNDER_INVESTIGATION') = (conclusion is null))
);
create index vr_subject_idx on violation_records (tenant_id, subject_user_id, raised_at desc);
create index vr_location_idx on violation_records (tenant_id, location_id, status);
create index vr_search_idx on violation_records using gin (search_vector);

create table violation_record_events (                    -- append-only investigation trail
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    violation_record_id uuid not null references violation_records(id),
    kind varchar(20) not null check (kind in ('RAISED','NOTE','SEVERITY_CHANGED','AMOUNT_CHANGED','CONCLUDED','REOPENED')),
    from_value varchar(60),
    to_value varchar(60),
    note varchar(2000),
    by_user_id uuid not null references app_users(id),
    at timestamptz not null default now()
);
```

### 2.3 `audit_alerts`

```sql
create table audit_alerts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    rule_code varchar(40) not null,                        -- §4.4 detector list
    subject_user_id uuid references app_users(id),
    location_id uuid references locations(id),
    summary varchar(300) not null,
    amount numeric(14,2),
    evidence jsonb not null default '{}'::jsonb,           -- event ids, refs, figures that triggered it
    status varchar(20) not null default 'OPEN' check (status in ('OPEN','OPENED_AS_RECORD','DISMISSED')),
    decided_by_user_id uuid references app_users(id),
    decided_at timestamptz,
    dismiss_reason varchar(300),
    dedupe_key varchar(120) not null,                      -- rule_code + source ref; the same trigger never alerts twice
    created_at timestamptz not null default now(),
    unique (tenant_id, dedupe_key)
);
create index audit_alerts_open_idx on audit_alerts (tenant_id, status, created_at desc);
```

### 2.4 `audit_reviews`

```sql
create table audit_reviews (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    record_type varchar(30) not null check (record_type in (
        'WALLET_WITHDRAWAL','RUNNING_COST','PETTY_CASH_PAYMENT','PAYMENT_RECEIPT','QUANTITY_ADJUSTMENT',
        'STOCK_TRANSFER','CUSTOMER_RETURN','INSTANT_DISCOUNT','SALE','INVOICE','CREDIT_NOTE')),
    record_id uuid not null,
    record_ref varchar(40) not null,
    status varchar(12) not null check (status in ('UNDER_REVIEW','REVIEWED','FLAGGED')),
    note varchar(1000),
    reviewer_user_id uuid not null references app_users(id),
    updated_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    unique (tenant_id, record_type, record_id)
);
```
One current review state per record. Changes are recorded as `history.review.updated` events, so the trail lives in the activity log.

### 2.5 `data_quality_flags`

```sql
create table data_quality_flags (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- ER-n
    entity_type varchar(20) not null check (entity_type in ('STOCK_ITEM','UNIT','CATEGORY')),
    entity_id uuid not null,
    entity_code varchar(40),                               -- item code; null for units/categories
    field varchar(20) not null check (field in ('NAME','BRAND','UNIT_NAME','CATEGORY_NAME')),
    current_value varchar(200) not null,
    suggested_value varchar(200),
    kind varchar(10) not null check (kind in ('SPELLING','NAMING')),
    source varchar(10) not null check (source in ('SYSTEM','USER')),
    flagged_by_user_id uuid references app_users(id),      -- null for SYSTEM
    status varchar(12) not null default 'OPEN' check (status in ('OPEN','CORRECTED','DISMISSED')),
    applied_value varchar(200),
    resolved_by_user_id uuid references app_users(id),
    resolved_at timestamptz,
    dismiss_reason varchar(300),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create unique index dqf_open_uq on data_quality_flags (tenant_id, entity_type, entity_id, field) where status = 'OPEN';
create table data_quality_ignores (                        -- "the spelling is intentional": never flag this word/value again
    tenant_id uuid not null references tenants(id),
    token varchar(120) not null,                           -- lower-case word or whole value
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    primary key (tenant_id, token)
);
```

### 2.6 `recycle_bin_log`

```sql
create table recycle_bin_log (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null references stock_items(id),
    action varchar(12) not null check (action in ('SUSPENDED','RESUMED','DELETED','RESTORED','PURGED','REPRICED')),
    reason varchar(300),
    quantity_held numeric(14,3),                           -- Σ on hand across locations at the time
    value_held numeric(14,2),                              -- [cost] quantity × landing cost at the time
    review_on date,
    retention_until date,
    write_off_quantity numeric(14,3),                      -- PURGED with stock
    by_user_id uuid not null references app_users(id),
    at timestamptz not null default now()
);
create index rbl_item_idx on recycle_bin_log (tenant_id, stock_item_id, at desc);
```

### 2.7 `integrity_check_runs` / `integrity_check_findings`

```sql
create table integrity_check_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    trigger varchar(10) not null check (trigger in ('SCHEDULED','MANUAL')),
    triggered_by_user_id uuid references app_users(id),
    checks_run int not null default 0,
    findings_count int not null default 0,
    status varchar(10) not null check (status in ('RUNNING','PASSED','FINDINGS','FAILED'))
);
create table integrity_check_findings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null references integrity_check_runs(id),
    check_code varchar(40) not null,
    severity varchar(10) not null check (severity in ('WARNING','CRITICAL')),
    record_type varchar(40) not null,
    record_ref varchar(60) not null,
    expected varchar(120),
    actual varchar(120),
    message varchar(300) not null
);
```

### 2.8 Module settings registered

| Key | Type | Default |
|---|---|---|
| `history.recycleBin.retentionDays` | int | 30 ("Held until" = deleted date + 30) |
| `history.dataQuality.scanEnabled` | boolean | true |
| `history.alerts.tillVarianceThreshold` | decimal | 5000.00 |
| `history.alerts.repeatedDeclinesPerWeek` | int | 3 |
| `history.alerts.adjustmentValueThreshold` | decimal | 100000.00 [cost basis] |

---

## 3. Permission codes

| Code | Allows | Default roles |
|---|---|---|
| `history.waybills.read` | Waybill History | Management, Accounts Personnel, Stock Keeper |
| `history.expenses.read` | Expenses/Payment Reports | Management, Accounts Personnel |
| `history.stock-movements.read` | Stock Movement History | Management, Stock Keeper, Accounts Personnel |
| `history.wallet-withdrawals.read` | Wallet Withdrawal History | Management, Accounts Personnel |
| `history.violations.read` | Violations/Fraud History, audit alerts | Administrator, Management |
| `history.violations.manage` | Raise, annotate, conclude records; open/dismiss alerts | Administrator, Management |
| `history.reviews.manage` | Set audit-review status on any record | Administrator, Management |
| `compliance.vat.read` | Showroom/Factory VAT Reports, VAT return summary | Management, Accounts Personnel |
| `history.data-quality.manage` | Error Handler: flag, correct, dismiss | Administrator |
| `history.recycle-bin.manage` | Suspend/resume, delete/restore, reprice shortcut | Administrator |
| `history.recycle-bin.purge` | Purge (irreversible) | Administrator |
| `history.integrity.read` | Integrity check runs/findings; trigger a manual run | Administrator, Management |
| `platform.activity.read` (doc 03) | Activity log | Administrator, Management |

There is no default "Auditor" role; PRD 5.6 names "Management / Auditor". A tenant creates one with Users & Roles (shipped) by granting the `history.*`, `compliance.vat.read` and `platform.activity.read` codes. **Restriction:** a user never sees violation records where they are the subject, even with `history.violations.read`; such rows are filtered out and do not count in stats.

Location scope (`LocationAccessService`) applies to every report through the record's location.

---

## 4. Endpoints (under `/api/v1/history`, `/api/v1/compliance`, `/api/v1/audit`)

All report endpoints: `from`/`to` required (≤ 366 days, otherwise `400 RANGE_TOO_LARGE`); an empty result is an empty list (PRD test case 2); cost fields follow doc 03 §14 (zeroed + `costVisible: false`).

### 4.1 Waybill History — `GET /history/waybills`

Perm `history.waybills.read`. Query `invoice` (INV or TXN ref), `customerId`, `driverId`, `locationId`, `status` (`DELIVERED`|`OUTSTANDING`), `from`, `to` (supply date; for the outstanding rows, the sale date). Source `SalesQueries.supplyHistory`.

Response:
```json
{ "data": {
  "invoice": { "number": "INV-31240", "saleRef": "TXN-4502", "date": "2026-08-20", "customer": "Okafor & Sons Distribution" },
  "stats": { "quantityInvoiced": 200, "quantitySupplied": 170, "fulfilledPct": 85, "stillOutstanding": 30, "waybillsRaised": 2, "deliveryPattern": "Multi-delivery" },
  "rows": [
    { "waybill": "WB-9101", "supplyDate": "2026-08-21", "product": "Citrus Drink 50cl", "ordered": 200, "qtySupplied": 120, "outstanding": 80, "driver": "Musa Bello", "receivedBy": "K. Okafor", "status": "Delivered" },
    { "waybill": "WB-9168", "supplyDate": "2026-10-09", "product": "Citrus Drink 50cl", "ordered": 200, "qtySupplied": 50, "outstanding": 30, "driver": "Musa Bello", "receivedBy": null, "status": "Issued" },
    { "waybill": null, "supplyDate": null, "product": "Citrus Drink 50cl", "ordered": 200, "qtySupplied": 0, "outstanding": 30, "driver": null, "receivedBy": null, "status": "Outstanding" } ]
} }
```
`outstanding` on each waybill row is the line's balance **after** that waybill (a running balance). Without `invoice` the endpoint returns all invoices in range, grouped (`invoices: [ { invoice…, rows… } ]`, paged by invoice).

### 4.2 Expenses/Payment Reports — `GET /history/expenses`

Perm `history.expenses.read`. Query `categoryId`, `method` (`PETTY_CASH`|`CASH`|`BANK_TRANSFER`|`CHEQUE`), `locationId`, `status` (`APPROVED`|`PENDING_APPROVAL`|`REJECTED`|`REVERSED`; default all), `from`, `to`, `q` (ref CODE, payee/purpose TRIGRAM). Source `AccountQueries.expenses` (running costs + petty cash payments).
Row: `{ date, ref, purpose, category, payee, method, approvedBy, status, amount, inputVat }`.
Stats: `{ totalExpenditure (approved only), approvedEntries, categoriesUsed, largestCategory { name, total }, pendingApproval { count, amount } }`.
Groups: `byCategory: [ { category, count, total } ]` and `byMethod: [ { method, count, total } ]`, each summing to `totalExpenditure`.
Footnote rule: only `APPROVED` entries count toward totals. Pending, rejected and reversed entries are listed but excluded, matching the Cash Inflow/Outflow Statement.

### 4.3 Stock Movement History — `GET /history/stock-movements` [paged]

Perm `history.stock-movements.read`. Query `stockItemId`, `locationId` (factory/showroom root includes its areas), `sourceType` (repeatable; labels below), `direction` (`IN`|`OUT`), `from`, `to`, `q` (ref CODE, item name TRIGRAM). Reads `stock_ledger_entries` directly. Default sort `entryDate desc, createdAt desc`.
Row: `{ date, ref, product, productCode, location, area, source (label), direction, quantity (signed), unit, recordedBy, unitCost [cost], value [cost] }`.
Labels: Stock Refill (`REFILL`), Raw-material receipt (`RECEIVE`), Production run, Stock transfer out/in, Transfer rejected (returned), Quantity adjustment, Factory trade out/in, Direct entry, Outsource receipt, Customer return, Area move out/in, POS sale, Customer waybill, Waybill cancelled, Sale void, Purge write-off.
Stats (whole filtered set, not the page): `{ movements, quantityIn, quantityOut, adjustments }`.

### 4.4 Violations/Fraud History

**Records** (`/history/violations`):
- `GET /history/violations` [paged] — perm `history.violations.read`. Query `subjectUserId`, `locationId`, `status`, `category`, `severity`, `from`, `to` (raised date), `q`. Row: `{ id, ref, dateRaised, subject, category, description, amountInvolved, severity, raisedBy, outcome (status) }`.
- `GET /history/violations/staff/{userId}` — the staff panel: `{ staff: { name, role, location, joined }, stats: { recordsOnFile, underInvestigation, substantiated, amountInvolved (open + substantiated only), clearedWithoutCase (dismissed + resolved) }, records: [ … most recent first, each with its events ] }`. Staff with no records get `records: []` (screen: "has a clean record").
- `POST /history/violations` — perm `history.violations.manage`. `{ subjectUserId, locationId?, category, description (≥20 chars), amountInvolved?, severity, occurredOn?, linkedRefs?: [ { type, ref } ] }`. The subject must be a tenant member and must not be the caller (`400 CANNOT_RAISE_AGAINST_SELF`). `ref = next("VN")`. Emits `history.violation.raised` with a payload that contains **no** description text, only `{ ref, category, severity }`.
- `POST /history/violations/{id}/notes` — `{ note }` adds a `NOTE` event.
- `PUT /history/violations/{id}` — `{ severity?, amountInvolved?, note (required) }` adds `SEVERITY_CHANGED`/`AMOUNT_CHANGED` events. Allowed only while `UNDER_INVESTIGATION`.
- `POST /history/violations/{id}/conclude` — `{ outcome: SUBSTANTIATED|RESOLVED|DISMISSED, conclusion (≥20 chars) }`; only from `UNDER_INVESTIGATION`. The concluder must differ from the raiser, or must hold `history.violations.manage` **and** be an Administrator (a two-person rule for an outcome against a member of staff). Emits `history.violation.concluded`.
- `POST /history/violations/{id}/reopen` — `{ reason }`, back to `UNDER_INVESTIGATION` (new evidence). The previous conclusion is kept in the events trail.

**Alerts** (`/history/audit-alerts`): system detectors run as event consumers (doc 03 §7.6, consumer `history.audit-detector`). They create `OPEN` alerts and notify holders of `history.violations.read`. Detectors:

| `rule_code` | Trigger | Subject |
|---|---|---|
| `TILL_VARIANCE` | `cashier.till.variance` with \|variance\| ≥ `history.alerts.tillVarianceThreshold` | the cashier |
| `REPEATED_DECLINES` | ≥ `history.alerts.repeatedDeclinesPerWeek` `account.receipt.declined` for one cashier in 7 days | the cashier |
| `WALLET_REVERSAL` | `customers.wallet.entry-reversed` or `customers.wallet.adjusted` on a dealer wallet | the actor |
| `WITHDRAWAL_WITHOUT_OTP` | a dealer-initiated `account.wallet.withdrawn` without a verified OTP (should be impossible; defensive) | the processor |
| `WITHDRAWAL_FLAGGED` | `account.wallet.withdrawal-flagged` | the processor |
| `LARGE_ADJUSTMENT` | `inventory.adjustment.posted` NEGATIVE with value ≥ `history.alerts.adjustmentValueThreshold` | the stock keeper |
| `SALE_VOID_AFTER_PAYMENT` | `cashier.sale.voided` where a receipt had been `APPROVED` | the voider |
| `DISCOUNT_ABOVE_CEILING_REJECTED_THEN_REAPPLIED` | a rejected instant discount followed by another on the same sale | the applier |
| `AI_ANOMALY` | `ai.anomaly.confirmed` (doc 16 §4.5 — a person confirmed the AI flag and referred it to audit) | the anomaly's subject |

- `GET /history/audit-alerts` [paged] — perm `history.violations.read`. Query `status` (default `OPEN`), `ruleCode`, `subjectUserId`, `locationId`, `from`, `to`.
- `POST /history/audit-alerts/{id}/open-record` — creates a violation record pre-filled from the alert (`source ALERT`, `linked_refs` from the evidence). The body takes the same fields as `POST /history/violations` and overrides the prefill. The alert becomes `OPENED_AS_RECORD`.
- `POST /history/audit-alerts/{id}/dismiss` — `{ reason }`.

### 4.5 Wallet Withdrawal History — `GET /history/wallet-withdrawals`

Perm `history.wallet-withdrawals.read`. Query `customerId`, `initiatedBy` (`DEALER`|`COMPANY`), `status` (doc 07 statuses plus `UNDER_REVIEW` from `audit_reviews`), `locationId`, `from`, `to`. Source `AccountQueries.withdrawals`, joined in memory to `audit_reviews` (record_type `WALLET_WITHDRAWAL`).
Row: `{ ref, date, dealer, dealerCode, initiatedBy, reason, balanceBefore, amount, balanceAfter, method, otp ('Verified' | 'Not required — Management authorised' | 'Missing'), processedBy, authorisedBy, status, auditReview ('Under review' | 'Reviewed' | 'Flagged' | null), breach (balanceAfter < 0) }`.
Stats: `{ totalWithdrawn, count, dealerInitiated { amount, count }, companyInitiated { amount, count }, underReview }`. Groups: subtotals by initiator.
Integrity flag: any row with `balanceAfter < 0` or a dealer-initiated row without a verified OTP is marked `breach: true`, and the footnote text is chosen accordingly ("one record breaches this and requires investigation"). The same condition is integrity check `WALLET_NEGATIVE` / `WITHDRAWAL_OTP_MISSING` (§4.10).

### 4.6 VAT compliance — `/compliance/vat`

**Showroom / Factory VAT Report** — `GET /compliance/vat/report?locationId=&from=&to=&type=` — perm `compliance.vat.read`. `locationId` is required, and must be a FACTORY or SHOWROOM (areas roll up to their parent). `type` = `OUTPUT`|`INPUT`|both (default).

| Line type | Source | Taxable amount | VAT |
|---|---|---|---|
| OUTPUT — sale | `SalesQueries.linesFor(location, from, to)` grouped per sale (completed, not voided) | Σ `line_net` | Σ `vat_amount` |
| OUTPUT — credit note (negative) | `InvoiceQueries.creditNoteLines(location, from, to)` | −Σ `line_net` | −Σ `vat_amount` |
| OUTPUT — special production delivery (factories) | inventory/factory `special_production_runs` with status `DELIVERED` in range | `price × quantity` | at the factory's VAT rate on the delivery date, from `VatRates.forLocation`; marked `derived: true` because Factory does not store VAT on special runs |
| INPUT — direct entry / outsource receipt (showrooms) | `ShowroomQueries.stockEntries` lines | `line_cost` | `input_vat_amount` |
| INPUT — raw-material receipt (factories) | `stock_receipts` | `quantity × unit_cost` | `input_vat_amount` |
| INPUT — running cost | `AccountQueries.inputVat(location)` | `amount − input_vat_amount` | `input_vat_amount` |

Rows: `{ date, document (ref), counterparty, counterpartyTin, description, type ('Output'|'Input'), taxableAmount, rate, vat }`.
Response header: `{ location, vatEnabled, currentRate, rateChanges: [ { rate, effectiveFrom } ] (within the period), outputVat, outputBase, inputVat, inputBase, netVatPayable (output − input; negative = credit carried forward), costVisible }`.
Subtotals: total output VAT and total input VAT.
Rules:
- A location with VAT collection disabled for the period reports zero output VAT. The note text is "A showroom with VAT collection disabled reports zero output VAT for the period."
- The rate column shows each line's own rate, so a mid-period rate change is visible.
- Input lines are [cost]-bearing documents. Their `taxableAmount` is a cost figure, so for callers without `factory.costing.read` the input taxable amounts are zeroed and `costVisible: false`. **VAT amounts are not masked**: VAT is a tax liability, not a cost price, and Accounts must see it.

**VAT return summary** — `GET /compliance/vat/return?period=2026-09` (`YYYY-MM`) — perm `compliance.vat.read`. One row per location with activity plus a company total: `{ location, tin/vatRegistrationNumber, outputBase, outputVat, creditNoteVat, inputVat, netPayable, salesCount, zeroRatedSales, exemptSales }`. `zeroRatedSales`/`exemptSales` are the taxable amounts of sale lines in those categories (VAT returns report them separately). Also `GET /compliance/vat/return.csv?period=` with the same columns.

### 4.7 Activity log — `GET /audit/activity` [paged]

Perm `platform.activity.read`. Reads `domain_events`. Query `module` (prefix of `event_type`), `eventType`, `actorUserId`, `aggregateType`, `ref` (`aggregate_ref` exact), `locationId`, `from`, `to` (`occurred_at`), `q` (FULLTEXT on `search_vector`), `costAccessOnly` (= `event_type = 'security.cost-data.viewed'`, which answers PRD §9's "audit access to cost fields").
Row: `{ occurredAt, eventType, summary, ref, aggregateType, location, actor (name or system label), payload }`.
- `GET /audit/activity/entity?aggregateType=&aggregateId=` returns the full timeline of one record (every event on it, oldest first). The detail screens across modules use it as a "History" tab.
- Events about `history.violation.*` are excluded unless the caller also holds `history.violations.read`.

### 4.8 Error Handler — `/history/data-quality`

- **Scanner**: nightly at 02:00, plus `POST /history/data-quality/scan` (perm `history.data-quality.manage`). Only when `history.dataQuality.scanEnabled`. Over `ACTIVE`/`SUSPENDED` stock items, units and categories:
  1. **Vocabulary**: tokenise names on spaces and punctuation, lower-case, ignore tokens with digits (sizes, codes) and tokens of length < 4, and drop tokens in `data_quality_ignores`. Count the products using each token.
  2. **SPELLING**: a token used by ≤ 2 products that has a candidate token used by ≥ 3 products and ≥ 3× as often, with `levenshtein(token, candidate) ≤ (length ≥ 7 ? 2 : 1)` and `similarity ≥ 0.4`. Flag the name and suggest it with the token replaced, preserving the original casing pattern.
  3. **NAMING**: two values whose lower-cased, whitespace- and punctuation-normalised forms are equal ("50 cl" vs "50cl", "Citrus-Drink" vs "Citrus Drink"), or whose trigram similarity is ≥ 0.9 within the same category. Suggest the form used by more products, or by the older record on a tie.
  4. Skip any entity/field that already has an `OPEN` flag. Every new flag gets `ref = next("ER")`, `source SYSTEM`.
  Implemented as SQL over `pg_trgm` + `fuzzystrmatch` with the GIN trigram indexes from doc 03 §5.12. It runs per tenant in its own `TenantScopedTransaction` and is idempotent.
- **`POST /history/data-quality/flags`** — manual flag. `{ entityType, entityId, field, suggestedValue?, note? }` → `source USER`.
- **`GET /history/data-quality/flags`** [paged] — query `status` (default `OPEN`), `kind`, `entityType`, `q`. Row: `{ id, ref, code, field, currentValue, suggestedValue, recordsAffected, documentsKeepingOriginal, kind ('Spelling'|'Naming'), flaggedBy ('System spell check' | user), flaggedOn }`.
  - `recordsAffected` = stock-ledger entries + open sale/pro-forma lines that display the master name (live references).
  - `documentsKeepingOriginal` = issued invoice lines, credit-note lines, waybill lines and receipts carrying a snapshot (counted through `InvoiceQueries`/`SalesQueries` by `stock_item_id`).
  Header: `{ totalFlagged, spelling, naming, recordsAffected }`.
- **`POST /history/data-quality/flags/{id}/apply`** — perm `history.data-quality.manage`. `{ value }` (defaults to the suggestion; must differ from the current value, 1–200 chars).
  - Re-reads the entity; if the current value has changed since the flag was raised → `409 FLAG_STALE`.
  - Applies the change through the owning service: stock item name/brand → inventory `PUT /stock-items/{id}` logic (doc 03 §12.4, which emits `inventory.stock-item.updated`); unit/category name → factory-setup update (doc 03 §12.1), so `409 DUPLICATE_NAME` can surface.
  - Sets the flag to `CORRECTED` with `applied_value`.
  - Response `{ flag, recordsUpdated (live references now showing the new text), documentsKeepingOriginal }`.
- **`POST /history/data-quality/flags/{id}/dismiss`** — `{ reason, ignoreToken?: true }`. With `ignoreToken`, the flagged token (SPELLING) or the whole value (NAMING) is added to `data_quality_ignores` ("the spelling is intentional").

### 4.9 Recycle Bin — `/history/recycle-bin`

**Deleted items**
- `GET /history/recycle-bin/deleted` — perm `history.recycle-bin.manage`. Items with status `DELETED`. Query `q`. Row: `{ stockItemId, code, product, quantityHeld (Σ on hand, all locations), unitCost [cost], valueHeld [cost], reason, deletedBy, deletedOn, heldUntil (deleted_retention_until), purgeEligible }`. Stats: `{ itemsDeleted, quantityHeld, valueRecoverable [cost], oldestDeletion { date, purgesOn } }`.
- `POST /history/recycle-bin/deleted` — delete a product. `{ stockItemId, reason (required) }`.
  - Item must be `ACTIVE` or `SUSPENDED`.
  - Blocked by open reservations (`409 STOCK_RESERVED`), transfers in transit containing it (`409 ITEM_IN_TRANSIT`), a live variant child (delete the variants first, `409 ITEM_HAS_ACTIVE_VARIANTS`), or an open sale/pro-forma line (`409 ITEM_IN_OPEN_DOCUMENTS`, with details listing the refs).
  - Sets `DELETED`, `deleted_retention_until = today + history.recycleBin.retentionDays`, logs `quantity_held`/`value_held`. Stock stays where it is and is frozen ("Deleted products retain their quantity and value while held"). Emits `inventory.stock-item.deleted`.
- `POST /history/recycle-bin/deleted/{stockItemId}/restore` — `{ note? }`: `DELETED` → `ACTIVE`. Code, barcode and stock come back unchanged. If the barcode was reused by another item meanwhile → `409 DUPLICATE_BARCODE` (edit one first). Emits `inventory.stock-item.restored`.
- `POST /history/recycle-bin/deleted/{stockItemId}/purge` — perm `history.recycle-bin.purge`. `{ confirmCode: "<item code>", writeOffConfirmed: bool, reason }`.
  - `confirmCode` must equal the item code (the "cannot be undone" guard).
  - If any stock is on hand: `writeOffConfirmed` must be true (else `409 PURGE_REQUIRES_WRITE_OFF`), and a `PURGE_WRITE_OFF` out entry is posted per location at the current landing cost.
  - Sets `PURGED` (barcode released: set to null and kept in `status_reason` text), logs, and emits `inventory.stock-item.purged`.
  - Allowed before `heldUntil` too, since an explicit purge overrides retention.
- Retention job, daily: `DELETED` items past `heldUntil` with **zero** stock are auto-purged (`by` = system). Items still holding stock are never auto-purged; their deleter and the purge-permission holders get a notification instead.

**Suspended from sale**
- `GET /history/recycle-bin/suspended` — `SUSPENDED` items. Row: `{ stockItemId, code, product, stockHeld, unitCost [cost], sellingPrice (retail, current), dealerPrice, valueHeld [cost], reason, suspendedOn, reviewOn }`. Stats: `{ productsSuspended, stockHeldBack, valueHeldBack [cost], nextReview { date, product } }`. The callout "₦x of stock is held back from sale" uses `valueHeldBack`, so it is zeroed for non-cost users.
- `POST /history/recycle-bin/suspended` — `{ stockItemId, reason (required), reviewOn? }` → `SUSPENDED` (same blockers as delete except in-transit transfers, which may still be received).
- `POST /history/recycle-bin/suspended/{stockItemId}/resume` — `{ note? }` → `ACTIVE`.
- `PUT /history/recycle-bin/suspended/{stockItemId}/review` — `{ reviewOn }`.
- **Reprice** is not a separate engine: it publishes a price through doc 05's price list (`POST /api/v1/inventory/price-list/publish`), and this screen links to it with the item preselected. `POST /history/recycle-bin/suspended/{stockItemId}/reprice-and-resume` `{ retailPrice, dealerPrice, effectiveFrom (≥ today) }` does both in one transaction for the common case (perm also requires the price-list permission). It logs `REPRICED` + `RESUMED`.
- Daily job: items whose `reviewOn` is today notify the suspender and `history.recycle-bin.manage` holders ("Next review: Cocoa Wafer 40g").

### 4.10 Integrity checks — `/audit/integrity`

A nightly job (03:00) per tenant, plus `POST /audit/integrity/runs` (perm `history.integrity.read`; at most one run at a time per tenant → `409 RUN_IN_PROGRESS`). Checks:

| `check_code` | Assertion | Severity |
|---|---|---|
| `STOCK_BALANCE_MATCHES_LEDGER` | every `stock_balances.quantity_on_hand` = Σ ledger in − out for (location, item) | CRITICAL |
| `STOCK_NOT_NEGATIVE` | no balance < 0 | CRITICAL |
| `RESERVATION_WITHIN_STOCK` | open reservations ≤ on hand at each root location | WARNING |
| `WALLET_CHAIN` | each customer's `customer_ledger_entries.balance_after` chain is consistent and ends at `customers.wallet_balance` / `deposit_balance` | CRITICAL |
| `WALLET_NEGATIVE` | no wallet/deposit balance < 0 (rule 5) | CRITICAL |
| `WITHDRAWAL_OTP_MISSING` | every dealer-initiated withdrawal has a verified OTP (rule 8) | CRITICAL |
| `CASH_ACCOUNT_MATCHES_LEDGER` | each cash account balance = opening + Σ cash transactions | CRITICAL |
| `BANK_ACCOUNT_MATCHES_LEDGER` | same for bank accounts | CRITICAL |
| `INVOICE_TOTALS` | invoice header totals = Σ lines; `amount_paid` = Σ active payments | CRITICAL |
| `INVOICE_SEQUENCE_GAPLESS` | INV numbers form 1..n with no gaps or duplicates (rule 14) | CRITICAL |
| `SALE_SETTLED` | each completed sale's payments + on-account = total | CRITICAL |
| `RECEIPT_POSTED` | each APPROVED/NOT_REQUIRED receipt has its cash/bank posting | WARNING |

The checks run through each owning module's read methods (`AccountQueries`, `InvoiceQueries`, `SalesQueries`, `CustomerQueries`) or inventory directly. Findings are stored; any CRITICAL finding emits `audit.integrity.finding` and notifies Administrators. `GET /audit/integrity/runs` [paged], `GET /audit/integrity/runs/{id}` (findings).

### 4.11 Audit reviews — `/audit/reviews`

- `PUT /audit/reviews` — perm `history.reviews.manage`. `{ recordType, recordId, recordRef, status: UNDER_REVIEW|REVIEWED|FLAGGED, note? }` upserts the record's single review state. `FLAGGED` requires a note.
- `GET /audit/reviews` [paged] — query `recordType`, `status`, `reviewerUserId`, `from`, `to`.
- `GET /audit/reviews/lookup?recordType=&recordIds=` — batch lookup, used by report endpoints and screens to show an "Under review" badge.

---

## 5. Exposed to other modules

None (leaf). Other modules link to the activity timeline via the URL in §4.7.

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| H1 | Reports reflect the underlying records at generation time | PRD 5.6 acceptance criteria | §1.2-1 (live reads, no copies) |
| H2 | Restricted (cost) columns zeroed/hidden | PRD 5.6 acceptance criteria; rule 3 | doc 03 §14; §4.3, §4.6, §4.9 |
| H3 | Role-restricted access to violation records | PRD 5.6.4 | §3, including the self-exclusion |
| H4 | All financial entries consistent across statements | PRD 5.6.4 | §4.10 integrity checks |
| H5 | Wallet never negative; withdrawals OTP-verified | rules 5, 8 | §4.5 breach flag, §4.10 |
| H6 | VAT per location rate/enablement | rule 9 | §4.6 |
| H7 | Issued documents immutable, including name corrections | rule 14 | §1.2-2 |
| H8 | Deleted products recoverable until purge; purge irreversible | PRD 5.5.7 | §4.9 |
| H9 | Suspended products cannot be transacted | rule 1 | §2.1 status table |
| H10 | AI flags never act on their own; a person opens a record | rule 13 | §4.4 alerts |
| H11 | Cost-field access is audited | PRD §9 | §4.7 `costAccessOnly` |
| H12 | Invoice numbers sequential and gap-free | rule 14 | §4.10 `INVOICE_SEQUENCE_GAPLESS` |

## 7. Errors added

`RANGE_TOO_LARGE` 400 (shared with doc 09) · `VIOLATION_NOT_FOUND` 404 · `CANNOT_RAISE_AGAINST_SELF` 400 · `VIOLATION_ALREADY_CONCLUDED` 409 · `CONCLUDER_MUST_DIFFER` 403 · `ALERT_NOT_OPEN` 409 · `FLAG_STALE` 409 · `FLAG_NOT_OPEN` 409 · `ITEM_IN_TRANSIT` 409 · `ITEM_HAS_ACTIVE_VARIANTS` 409 · `ITEM_IN_OPEN_DOCUMENTS` 409 · `ITEM_NOT_DELETED` 409 · `ITEM_NOT_SUSPENDED` 409 · `PURGE_REQUIRES_WRITE_OFF` 409 · `PURGE_CONFIRMATION_MISMATCH` 400 · `RUN_IN_PROGRESS` 409. (`STOCK_RESERVED`, `DUPLICATE_BARCODE`, `DUPLICATE_NAME` reused.)

## 8. Event catalog

| Event | Notifies |
|---|---|
| `history.violation.raised` / `.updated` / `.concluded` / `.reopened` | in-app other holders of `history.violations.read` (never the subject) |
| `history.alert.created` | in-app holders of `history.violations.read` at the location |
| `history.alert.opened` / `.dismissed` | — |
| `history.review.updated` | — |
| `history.data-quality.flagged` (batched per scan: one event with the count) / `.corrected` / `.dismissed` | scan → in-app holders of `history.data-quality.manage` when new flags > 0 |
| `inventory.stock-item.deleted` / `.restored` / `.purged` / `.suspended` / `.resumed` | purge-due with stock → deleter + purge holders; review-due → suspender |
| `audit.integrity.finding` / `audit.integrity.run-completed` | finding → in-app Administrators (CRITICAL only) |

Every `history.alert.*`, `history.data-quality.*` and `audit.integrity.run-completed` event carries the new absolute count in its payload (`{ openCount }`, or `{ criticalCount }` for runs), plus `locationId` where relevant. The Dashboard keeps its counters from these (doc 14 §3.5) without calling this module.

## 9. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search`, `/filters` metadata, facets, `highlight`, saved views, and identical-filter exports. Audit users need to find the needle, so every list here supports the full filter grammar and keyset paging where the source is a ledger or log. Confidential rows (violations whose subject is the caller) are removed **before** search, facets and counts.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /history/waybills` · `history.waybills` | invoice/sale ref REFERENCE, waybill ref REFERENCE, customer TRIGRAM, product TRIGRAM, driver TRIGRAM | `invoice`, `customerId`, `driverId`, `locationId`, `status` (DELIVERED/ISSUED/OUTSTANDING), `from`/`to`, `outstanding.gt` | `status`, `driver` | `supplyDate asc` per invoice | stats block |
| `GET /history/expenses` · `history.expenses` | ref REFERENCE, payee TRIGRAM, purpose FULLTEXT, invoice/receipt no CODE, amount AMOUNT | `categoryId` multi, `method` multi, `locationId`, `status` multi, `approvedBy`, `submittedBy`, `amount` range, `from`/`to`/`period`, `hasInputVat`, `exceedsMonthlyCap` | `category`, `method`, `status` | `date desc`; `amount` | `{ totalExpenditure, approvedEntries, categoriesUsed, largestCategory, pendingApproval }` + groups |
| `GET /history/stock-movements` [paged, keyset] · `history.stock-movements` | ledger ref REFERENCE, item code CODE, item name TRIGRAM, `remark` FULLTEXT | `stockItemId` multi, `categoryId`, `itemType`, `locationId` (incl. areas), `sourceType` multi, `direction`, `recordedBy`, `quantity` range, `from`/`to`/`period` | `sourceType`, `location`, `direction` | `entryDate desc, createdAt desc` | `{ movements, quantityIn, quantityOut, adjustments }` |
| `GET /history/violations` [paged] · `history.violations` | `ref` REFERENCE, `search_vector` FULLTEXT (description), linked refs REFERENCE (`RCT-552` finds records linking it), subject name TRIGRAM | `subjectUserId`, `locationId`, `status` multi, `category` multi, `severity` multi, `source`, `raisedBy`, `amountInvolved` range, `raisedAt`/`occurredOn` ranges | `status`, `category`, `severity` | `raisedAt desc`; `severity`, `amountInvolved` | `{ records, underInvestigation, substantiated, amountInvolved }` |
| `GET /history/audit-alerts` [paged] · `history.audit-alerts` | summary FULLTEXT, evidence refs REFERENCE, subject TRIGRAM | `status`, `ruleCode` multi, `subjectUserId`, `locationId`, `amount` range, `from`/`to` | `ruleCode`, `status` | `createdAt desc` | `{ open, byRule }` |
| `GET /history/wallet-withdrawals` · `history.wallet-withdrawals` | ref REFERENCE, dealer code/name, amount AMOUNT, paid-via TRIGRAM | `customerId`, `initiatedBy`, `status` multi incl. `UNDER_REVIEW`, `locationId`, `otp` (VERIFIED/MISSING/NOT_REQUIRED), `breach`, `amount` range, `from`/`to` | `initiatedBy`, `status`, `auditReview` | `date desc`; `amount` | stats + subtotals |
| `GET /compliance/vat/report` (rows) · `compliance.vat-report` | document ref REFERENCE, counterparty TRIGRAM, counterparty TIN CODE, description FULLTEXT | `locationId` (required), `type` (OUTPUT/INPUT), `lineSource` multi (sale, credit note, special production, direct entry, raw-material receipt, running cost), `rate` multi, `taxableAmount`/`vat` ranges, `from`/`to`/`period` | `type`, `lineSource`, `rate` | `date asc` | header block |
| `GET /audit/activity` [paged, keyset] · `audit.activity` | `aggregate_ref` REFERENCE, `search_vector` FULLTEXT (summary), actor name TRIGRAM | `module` multi, `eventType` multi, `actorUserId`, `aggregateType`, `locationId`, `costAccessOnly`, `systemOnly` (actor null), `from`/`to`/`period`, `hourFrom`/`hourTo` | `module`, `eventType`, `actor` | `occurredAt desc` | `{ events }` |
| `GET /history/data-quality/flags` [paged] · `history.data-quality` | `ref` REFERENCE, entity code CODE, `current_value`/`suggested_value` TRIGRAM | `status`, `kind`, `entityType`, `field`, `source`, `recordsAffected.gte`, `createdAt` range | `kind`, `entityType`, `status` | `recordsAffected desc`; `createdAt` | `{ totalFlagged, spelling, naming, recordsAffected }` |
| `GET /history/recycle-bin/deleted` · `history.recycle-deleted` | code CODE, name TRIGRAM, reason FULLTEXT | `deletedBy`, `deletedOn` range, `purgeEligible`, `hasStock`, `itemType` | `itemType` | `deletedOn asc` (oldest purge first) | stats block |
| `GET /history/recycle-bin/suspended` · `history.recycle-suspended` | code CODE, name TRIGRAM, reason FULLTEXT | `suspendedOn` range, `reviewOn` range (`reviewDue=true`), `itemType`, `hasStock` | `itemType` | `reviewOn asc` | stats block |
| `GET /audit/integrity/runs/{id}` (findings) | record ref REFERENCE, message FULLTEXT | `checkCode` multi, `severity` | `checkCode`, `severity` | `severity desc` | counts |
| `GET /audit/reviews` [paged] · `audit.reviews` | record ref REFERENCE, note FULLTEXT | `recordType` multi, `status` multi, `reviewerUserId`, `updatedAt` range | `recordType`, `status` | `updatedAt desc` | counts |

**Cross-record trace**: `GET /audit/trace?ref=<any reference>` (perm `platform.activity.read`) resolves any document reference (`TXN-`, `INV-`, `RCT-`, `WB-`, `RT-`, `QA-`, `WW-`, …) to its record and returns the linked chain: sale → invoice → receipts → waybills → returns → credit notes → commission run, each with its activity timeline. It is built from the document registry (doc 03 §8), the reference columns, and `domain_events` by `aggregate_ref`. Every hop is permission- and scope-checked, and hops the caller can't open are shown as "restricted" with no details. This is the auditor's "follow the money" search.

**Global search providers**: `violation` (perm `history.violations.read`, never returning records about the caller), `data-quality-flag`, `audit-alert`. Activity events are not in global search (too many); use `/audit/activity`.

**Indexes (V14)**: `violation_records` (subject, location/status, search_vector — in §2.2) plus GIN `linked_refs jsonb_path_ops`; `audit_alerts (tenant_id, status, created_at desc)` (exists) + GIN on `evidence`; `data_quality_flags` trigram on `current_value` (`dqf_value_trgm`) + `(tenant_id, status, kind)`; `recycle_bin_log (tenant_id, stock_item_id, at desc)` (exists); `stock_items (tenant_id, status, suspension_review_on)`; `audit_reviews (tenant_id, record_type, status, updated_at desc)`; `domain_events` indexes from V7 plus `(tenant_id, actor_user_id, occurred_at desc)` and `(tenant_id, aggregate_ref)`.

## 10. Worked example

1. Night scan: tenant has 14 products containing "drink" and one "Citrus Drnk 33cl". `levenshtein('drnk','drink') = 1` → `ER-17` SPELLING, suggested "Citrus Drink 33cl", recordsAffected 342 ledger entries, documentsKeepingOriginal 58 invoice lines.
2. The Administrator applies it. The stock item is renamed; Stock Card, Stock Movement History and Sales Report now show "Citrus Drink 33cl". INV-4031 still prints "Citrus Drnk 33cl" because issued documents are immutable.
3. Cashier F. Adeniyi closes the till with −₦8,000 variance → `TILL_VARIANCE` alert (threshold ₦5,000). Management opens `VN-9` (CASH_SHORTAGE, MEDIUM, ₦8,000, linked TILL session) → Under investigation. A week later a different manager concludes `RESOLVED` with "Miscount; cash found in vault bag, lodged as LDG-80".
4. Admin suspends "Cocoa Wafer 40g" (exchange-rate pressure) with review 30 Aug; on 30 Aug they reprice ₦1,250 → ₦1,400 and resume in one call.
5. September VAT return for Ikeja: output ₦1,284,000 on ₦17.12m sales, credit-note VAT −₦2,190, input VAT ₦96,300 (direct entries ₦41,000 + running costs ₦55,300) → net payable ₦1,185,510.

## 11. Open items

1. **Special-production VAT** is derived at report time because Factory doesn't store VAT on special runs. If special production becomes a real sales channel with invoices, route it through Sales & POS (or add VAT columns to `special_production_runs`) so it stops being derived.
2. **NRS VAT return format**: the CSV mirrors the summary columns. When FIRS/NRS publishes a machine-readable VAT return schema for the regulatory phase, add a serializer like doc 08 §4.12.
3. **Data-quality scope**: customer/supplier names are not scanned this phase (the PRD scopes the Error Handler to product information).

## 12. Verification

- Waybill History for an invoice with two waybills plus an outstanding remainder: the running `outstanding` column ends at the line's outstanding quantity in doc 09.
- Expenses report totals = Σ APPROVED running costs + petty cash payments = the outflow side of the Cash Inflow/Outflow Statement (doc 07) for the same filters.
- VAT report output VAT for a showroom and range = Σ Sales Report VAT (doc 09) − credit-note VAT; VAT disabled → 0.
- A user who is the subject of `VN-9` and holds `history.violations.read` does not see it in any list or count.
- Delete an item with an open reservation → `STOCK_RESERVED`; delete → restore round-trip keeps stock and barcode; purge with stock writes off and the ledger shows `PURGE_WRITE_OFF`.
- Corrupt a `stock_balances` row in a test DB → next integrity run reports `STOCK_BALANCE_MATCHES_LEDGER` CRITICAL and notifies Administrators.
- Spell scan is idempotent: running it twice creates no duplicate flags; a dismissed token with `ignoreToken` never reappears.
- Newman folder "17 - Audit & Compliance".
