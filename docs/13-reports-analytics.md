# 13 — Reports & Analytics Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.reports`
Depends on: `platform`, `common`, `inventory` (OPEN), and every `spi`: `customers`, `settings`, `account`, `invoicing`, `cashier`, `showroom`, `communication`
Depended on by: `dashboard`, `ai` (through `reports::spi`, read-only)
Migration: `V17__reports_module.sql`

---

## 1. Scope

### 1.1 What the sketch asks for

"Consolidated Analytics Hub: 14 categorized reporting streams (Sales, Stock, Factory, Showroom, Finance, Customers, Returns, Transfers, SCI, VAT, Pending Supply, Correspondent, Commission, Adjustments)."

### 1.2 What this module owns

| Feature | PRD | Screen |
|---|---|---|
| **Report catalogue**: every report in the system grouped into the 14 streams, filtered by what the caller may open | sketch | Reports hub (new) |
| **SCI Report** (company) and **SCI Factory / per-location SCI** | 5.7.7, 5.1.22 | `others/SCIReport.jsx`, factory nav "SCI Factory" |
| **Out-Source Stock Report** (sourced vs sold vs margin, per consignment) | 5.7.16 | `others/OutsourceStockReport.jsx` |
| **Sales analytics** (by product/category/location/customer type/correspondent, over time, with margin) | sketch "Sales" stream | hub |
| **Stock analytics** (value by location/category, slow-moving and dead stock, days of cover, near-expiry) | sketch "Stock" stream | hub |
| **Export engine**: CSV / XLSX / PDF for any catalogued report, synchronous when small, background jobs when large | every report screen's Export button | — |
| **Scheduled reports**: e-mail a report on a daily/weekly/monthly schedule | derived (management reporting) | hub |

### 1.3 What it does **not** own

The individual stream reports stay in the module that owns their data. Moving them would force this module to reimplement each module's rules, such as cost masking, location scope and status derivation. The catalogue points to them:

| Stream | Reports (owning module · endpoint) |
|---|---|
| Sales | Sales Report daily (cashier · `/cashier/reports/sales-daily`), Sales analytics (**reports**), Instant discounts (cashier), Invoice history (invoicing) |
| Stock | Comprehensive Stock (inventory), Stock Movement (inventory), Stock/Bin Card (inventory), Re-Order (inventory), Stock analytics (**reports**), Stock Movement History (history) |
| Factory | Production runs, Recipes report, Special production, Material/Recipe Receive, Factory trade, Total cost for selected recipes (factory/inventory), SCI Factory (**reports**) |
| Showroom | Showroom Stock Report, General Transfer Report, Direct entries, Outsource receipts (showroom), Out-Source Stock Report (**reports**) |
| Finance | Cash Inflow/Outflow, Bank statement, Petty cash statement and history, Running costs, Tenderable, Funds awaiting, Cash transfer history (account), Expenses report (history), Receivables, Debtors (invoicing) |
| Customers | Customer history, Customer contacts, Dealers update, Wallet ledger (customers), Customers/Distributors comprehensive (cashier), Wallet withdrawal history (history) |
| Returns | Return reports (showroom) |
| Transfers | Stock transfers, Waybill history (inventory, history) |
| SCI | SCI company, SCI per location (**reports**) |
| VAT | Showroom/Factory VAT report, VAT return (history/compliance) |
| Pending Supply | Pending supply (cashier) |
| Correspondent | Correspondents Report (communication) |
| Commission | Comms Report, allocation runs (communication) |
| Adjustments | Adjustments – Showroom / General (inventory) |

### 1.4 Decisions

1. **Reports read, never copy.** No reporting warehouse or nightly snapshot tables exist this phase. Every figure is computed from the owning modules' rows at request time, so "report data must accurately reflect the underlying transactional records at the time of generation" (PRD 5.x acceptance criteria) holds by construction. Heavy aggregations are served by indexes the owning docs already define. If a query proves slow in production, a materialised view can be added later behind the same `spi` without changing the API.
2. **The export engine lives in `platform`, and the HTTP surface and scheduling live here.** Every module's report must be exportable, but Reports may not be called by those modules, and Reports cannot reach into their internals. So `platform.reporting.ReportProvider` is an interface that each module implements for its own reports (dependency inversion, as with `EventConsumer` in doc 03 §7.6). This module discovers all providers as Spring beans, catalogues them, and runs exports and schedules through them. Each provider applies its own module's permission, location scope and cost masking. An export is therefore exactly what the user would see on screen.
3. **Stable libraries only.** XLSX: **Apache POI** `poi-ooxml` with the streaming `SXSSFWorkbook` (bounded memory for large sheets). CSV: **Apache Commons CSV**. PDF: the shared Thymeleaf + OpenHTMLtoPDF stack (doc 03 §8.3). All are Apache-2.0 and widely used.
4. **SCI is an operational management statement, not a statutory one.** It is built from the system's records: sales, cost of goods sold at landing cost, expenses, commissions allocated and stock values. Items the system does not record (depreciation, payroll outside running costs, financing costs) are not invented; they appear only if they are recorded as running costs under an expense category, and the "Basis of preparation" text says so.

---

## 2. Schema — migration `V17__reports_module.sql`

All tables: `tenant_id` + standard RLS (omitted).

### 2.1 Factory change: labour vs overhead in costings

The SCI's cost-of-sales split ("Raw materials consumed / Direct labour / Factory overhead") needs to know which costing overhead lines are labour. The shipped `overhead_snapshot` stores free `{label, amount}` pairs. Shipped `OverheadLineInput` gains an optional `kind: LABOUR | OVERHEAD` (default `OVERHEAD`), stored in the snapshot as `"kind"`. Existing snapshots without `kind` read as `OVERHEAD`. There is no schema change (JSON).

### 2.2 `report_exports`

```sql
create table report_exports (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- RPT-n
    report_key varchar(80) not null,                         -- provider key, e.g. 'inventory.comprehensive-stock'
    params jsonb not null,                                   -- the exact filter the user applied
    format varchar(4) not null check (format in ('CSV','XLSX','PDF')),
    status varchar(10) not null check (status in ('QUEUED','RUNNING','DONE','FAILED','EXPIRED')),
    row_count int,
    file_name varchar(200),
    content_type varchar(100),
    size_bytes bigint,
    error_message varchar(500),
    requested_by_user_id uuid not null references app_users(id),
    schedule_id uuid,                                        -- set when produced by a schedule
    requested_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    expires_at timestamptz,                                  -- DONE + 7 days
    unique (tenant_id, ref)
);
create index report_exports_user_idx on report_exports (tenant_id, requested_by_user_id, requested_at desc);
create index report_exports_queue_idx on report_exports (status, requested_at) where status = 'QUEUED';

create table report_export_files (                           -- separate so listing exports never loads file bytes
    export_id uuid primary key references report_exports(id),
    tenant_id uuid not null references tenants(id),
    content bytea not null                                   -- ≤ 50 MB (larger → FAILED 'EXPORT_TOO_LARGE')
);
```
A nightly job deletes `report_export_files` rows whose export has expired (status → `EXPIRED`; the metadata row stays). Generated files are disposable copies, so this delete is permitted under doc 03 §13. Storage moves to S3 when the deployment adds a bucket, behind the same `ExportFileStore` interface.

### 2.3 `report_schedules`

```sql
create table report_schedules (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(120) not null,
    report_key varchar(80) not null,
    params jsonb not null,                                   -- relative dates allowed: {"from":"{{periodStart}}","to":"{{periodEnd}}"}
    format varchar(4) not null check (format in ('CSV','XLSX','PDF')),
    frequency varchar(10) not null check (frequency in ('DAILY','WEEKLY','MONTHLY')),
    day_of_week int check (day_of_week between 1 and 7),     -- WEEKLY (1 = Monday)
    day_of_month int check (day_of_month between 1 and 28), -- MONTHLY
    send_at time not null default '07:00',                   -- tenant time
    recipients jsonb not null,                               -- [userId…] — tenant members only, never free e-mail addresses
    run_as_user_id uuid not null references app_users(id),   -- the owner; data is produced with THEIR permissions and scope
    status varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE','PAUSED')),
    last_run_at timestamptz,
    next_run_at timestamptz not null,
    created_at timestamptz not null default now()
);
create index report_schedules_due_idx on report_schedules (next_run_at) where status = 'ACTIVE';
```
Relative placeholders: `{{periodStart}}`/`{{periodEnd}}` mean the previous complete day, week (Mon–Sun) or month for the frequency; `{{today}}` is the run date.

### 2.4 Module settings registered

| Key | Type | Default |
|---|---|---|
| `reports.syncExportRowLimit` | int | 5000 |
| `reports.exportRetentionDays` | int | 7 |
| `reports.slowMovingDays` | int | 60 |
| `reports.deadStockDays` | int | 120 |
| `reports.nearExpiryDays` | int | 30 |

---

## 3. Platform contract — `platform.reporting`

```kotlin
interface ReportProvider {
    val key: String                       // '<module>.<report>' — unique
    val stream: ReportStream              // SALES, STOCK, FACTORY, SHOWROOM, FINANCE, CUSTOMERS, RETURNS, TRANSFERS, SCI, VAT,
                                          // PENDING_SUPPLY, CORRESPONDENT, COMMISSION, ADJUSTMENTS
    val title: String
    val description: String
    val screenPath: String?               // frontend route, for the catalogue link
    val endpoint: String                  // the module's JSON endpoint (documentation/link)
    val parameters: List<ReportParameter> // name, type (DATE, DATE_RANGE, LOCATION, ITEM, CUSTOMER, ENUM, TEXT), required, options
    val supportsPdf: Boolean
    fun canRun(principal: AuthPrincipal): Boolean            // the module's own permission check
    fun columns(params: Map<String, Any?>, costVisible: Boolean): List<ReportColumn>   // key, label, type (TEXT, MONEY, QTY, DATE, PCT), cost flag
    fun rows(params: Map<String, Any?>, sink: (Map<String, Any?>) -> Unit)            // streams rows; applies location scope and cost masking itself
    fun summary(params: Map<String, Any?>): List<ReportSummaryLine>                    // totals/stat lines printed above the table
    fun estimateRows(params: Map<String, Any?>): Long                                  // cheap count(*) used to choose sync vs queued export
}
```
- Providers run inside a `TenantScopedTransaction` opened by the export engine with the requesting (or schedule-owner) user as the current actor, so `LocationAccessService`, `CostVisibility` and `PermissionAuthorizer` behave exactly as they do on the screen.
- Every module doc's reports register a provider when implemented. Docs 02 and 05 (shipped and Showroom) add providers for their existing report endpoints in the V17 code change.
- `rows` must stream (Exposed `fetchSize` / keyset paging) and must not load everything into memory.

---

## 4. Endpoints (all under `/api/v1/reports`)

### 4.1 Catalogue — `GET /catalog`

Authenticated users. Returns the 14 streams in the sketch's order, each with the reports whose provider `canRun` the caller: `{ streams: [ { key: "SALES", title: "Sales", reports: [ { key, title, description, screenPath, endpoint, parameters, formats: ["CSV","XLSX","PDF"] } ] } ] }`. Streams with no runnable report are omitted. `GET /catalog/{reportKey}` returns one entry. `q` searches title and description (in memory; the catalogue is small).

### 4.2 SCI — `GET /sci`

Perm `reports.sci.read`. Query `scope` = `COMPANY` (default) | `LOCATION`, `locationId` (required for `LOCATION`: a factory or showroom; areas roll up), `from`, `to` (≤ 366 days), `compare` = `PRIOR_PERIOD` (same length immediately before; default) | `SAME_PERIOD_LAST_YEAR` | `NONE`. For `COMPANY` scope the screen's factory/showroom filters map to `locationIds` (an optional subset). Location scope: users restricted to one location can only run `LOCATION` scope for it (`COMPANY` → `403 LOCATION_ACCESS_DENIED`).

**Lines** (each computed for the current and comparison period):

| Section | Line | Source / rule |
|---|---|---|
| Revenue | Showroom sales | `SalesQueries.linesFor`: completed, non-voided sales at SHOWROOM locations to customers that are **not** `AUTHORISED_DEALER`; Σ `line_net` (VAT-exclusive, after discounts) |
| | Dealer and distributor sales | same, customer type `AUTHORISED_DEALER`, any location |
| | Factory direct sales | same, sales at FACTORY locations to non-dealers (shown only when non-zero) |
| | Special production revenue | `special_production_runs` `DELIVERED` in period (inventory/factory, OPEN): `price × quantity` |
| | By-product and other income | `AccountQueries.otherIncome` (APPROVED/NOT_REQUIRED), one line per `income_category` |
| | *Less:* sales returns and credit notes | −(dealer/non-dealer return credit value from `ShowroomQueries.returns` + `InvoiceQueries.creditNoteLines` net) |
| Cost of sales [cost] | Raw materials consumed | COGS of manufactured items × their latest approved costing's materials share |
| | Direct labour | × costing's `LABOUR` overhead share (§2.1) |
| | Factory overhead | × costing's `OVERHEAD` share |
| | Outsourced and bought-in goods | COGS of `OUTSOURCED`/`DIRECT_ENTRY` items |
| | *Less:* resaleable returns back into stock | −(returned qty × the cost it left at) |
| | Stock losses and write-offs | negative quantity adjustments, damaged-return write-offs, purge write-offs, at ledger cost |
| Gross profit | Revenue − cost of sales | with margin % |
| Operating expenses | one line per expense category | `AccountQueries.expenses` APPROVED (running costs + petty cash payments) in period |
| | Commissions | `CommissionQueries.allocatedFor(periods overlapping the range)` earned, "at the amount allocated for the period, whether or not it has been paid" (screen basis). For a partial-month range the month's allocation is pro-rated by days and marked `prorated: true` |
| Net profit | Gross profit − operating expenses | with margin % |
| Stock values at period end [cost] | Factory stock / Showroom stock | inventory: quantity as at `to` (Σ ledger ≤ `to`) × the item's current landing cost — the same basis as the Comprehensive Stock Report (§1.4-4; see open item 1) |

**COGS** = Σ over `POS_SALE` and `CUSTOMER_WAYBILL` ledger entries in the period of `qty_out × unit_cost` (the cost the ledger recorded when the goods left), minus `SALE_VOID` and `CUSTOMER_WAYBILL_CANCEL` reversals. Revenue is recognised at sale completion and COGS when goods leave, so goods sold but not yet delivered at period end show revenue without COGS. The basis note says so, and a line "Goods sold awaiting delivery (at cost)" under Stock values shows that reserved stock's cost so the reader can reconcile.

The materials/labour/overhead split for a manufactured item uses the shares of its most recent `APPROVED` production costing on or before the sale date. Items with no costing go to "Raw materials consumed" with `allocationBasis: "unsplit"` noted.

Response:
```json
{ "data": {
  "scope": "COMPANY", "period": { "from": "2026-08-01", "to": "2026-08-31" }, "comparison": { "from": "2026-07-01", "to": "2026-07-31", "basis": "PRIOR_PERIOD" },
  "columns": ["This period", "Prior period"],
  "sections": [
    { "title": "Revenue", "lines": [ { "label": "Showroom sales", "values": [25881000.00, 22140000.00], "changePct": 16.9 } ],
      "subtotal": { "label": "Total revenue", "values": [44966000.00, 38850000.00], "changePct": 15.7 } },
    { "title": "Cost of sales", "cost": true, "lines": [ … ], "subtotal": { … } },
    { "title": "Gross profit", "lines": [ { "label": "Revenue less cost of sales", "values": […], "note": "62.5% margin" } ] },
    { "title": "Operating expenses", "lines": [ … ], "subtotal": { … } },
    { "title": "Stock values at period end", "cost": true, "lines": [ … ], "subtotal": { … } } ],
  "result": { "label": "Net profit for the period", "values": [24224820.00, 19643800.00], "changePct": 23.3, "netMarginPct": 53.9, "priorNetMarginPct": 50.6 },
  "stats": { "totalRevenue": …, "grossProfit": …, "grossMarginPct": …, "netProfit": …, "netMarginPct": …, "closingStockValue": … },
  "basisOfPreparation": "Revenue consolidates showroom counter sales, dealer and distributor sales, special production and by-product income, net of VAT, returns and credit notes. Cost of sales is the landing cost recorded when goods left stock …",
  "costVisible": true
} }
```
Callers without `factory.costing.read` get every `cost: true` section zeroed, gross profit and net profit **null** (`"values": [null, null]`) and `costVisible: false`. A zeroed cost would otherwise produce a profit figure that looks real but isn't, and the doc 03 §14 rule exists to prevent leaking or misleading. The revenue and expense sections stay visible. PDF/CSV/XLSX via the export engine (`report_key reports.sci`).

### 4.3 Out-Source Stock Report — `GET /outsource-stock`

Perm `reports.outsource.read`. Query `manufacturerId` (external vendor), `locationId`, `status` (`IN_STOCK`|`SOLD_OUT`|`SUSPENDED`), `from`, `to` (receipt date).
Rows: one per outsource receipt line (`ShowroomQueries.stockEntries(kind OUTSOURCE_RECEIPT)`): `{ date, ref, product, vendor, showroom, qtySourced, qtySold, remaining, sellThroughPct, unitCost [cost], sellingPrice (average realised net unit price), marginRealised [cost], status }`.
**Attribution of sales to consignments**: FIFO per (item, showroom). Sales of the item at that showroom (`SalesQueries.linesFor`, by completion date, less returns) consume receipt lines oldest first. `qtySold` and realised revenue are the portion allocated to that receipt line. `marginRealised = revenue allocated − qtySold × unitCost`. Status: item `SUSPENDED` → Suspended; `remaining = 0` → Sold out; else In stock.
Stats: `{ costOfGoodsSourced [cost], revenueRealised, marginRealised [cost], marginPctOfRevenue [cost], unitsUnsold }`. Footnote: only company-registered outsourced products (`OUTSOURCED`) appear; direct entries are excluded, matching the PRD distinction.

### 4.4 Sales analytics — `GET /sales/analytics`

Perm `reports.sales.read`. Query `from`, `to`, `groupBy` (one or two of `PRODUCT`, `CATEGORY`, `LOCATION`, `CUSTOMER_TYPE`, `CUSTOMER`, `CORRESPONDENT`, `CASHIER`, `CHANNEL`), `interval` (`NONE`|`DAY`|`WEEK`|`MONTH`), `locationIds`, `categoryIds`, `customerTypes`, `top` (n, default 20, max 200), `compare` (as SCI).
Row: `{ group: { key, label }, interval?: "2026-08", quantity, transactions, gross, discounts, net, vat, cogs [cost], grossMargin [cost], grossMarginPct [cost], avgSellingPrice, shareOfNetPct, comparison?: { net, changePct } }`. Totals row. Source: `SalesQueries.linesFor` + the ledger cost of each sold line (`landingCostAtSale`, doc 09 §5). Voided sales are excluded. Returns are an optional deduction (`netOfReturns=true`).

### 4.5 Stock analytics — `GET /stock/analytics`

Perm `reports.stock.read`. Query `view`:
- `VALUE` — value by `groupBy` = `LOCATION`|`CATEGORY`|`ITEM_TYPE` at `asOf` (default today): `{ group, quantity, value [cost], shareOfValuePct [cost] }`.
- `SLOW_MOVING` — items with stock on hand whose last outbound movement (sale, waybill or transfer out) is older than `reports.slowMovingDays`; `DEAD` beyond `reports.deadStockDays`: `{ item, location, onHand, value [cost], lastOutboundAt, daysSinceOutbound }`.
- `DAYS_OF_COVER` — `available ÷ average daily units sold over the last 30 days` per item × location: `{ item, location, available, avgDailySales, daysOfCover, reorderLevel, belowReorder }`. Items with no sales show `daysOfCover: null` ("no recent sales").
- `NEAR_EXPIRY` — batches from receipts with `expiry_date` within `reports.nearExpiryDays` (raw-material receipts, outsource receipts and direct-entry lines that carry expiry), with the quantity still on hand estimated FIFO: `{ item, location, batch, expiryDate, daysToExpiry, estimatedQtyRemaining }`.
All views are location-scoped and cost-masked per doc 03 §14.

### 4.6 Exports — `/exports`

- **`POST /exports`** — `{ reportKey, params, format }`. The provider's `canRun` must pass → `403`; `PDF` on a provider without `supportsPdf` → `400 FORMAT_NOT_SUPPORTED`; params are validated against the provider's declared parameters → `400 VALIDATION_FAILED`. `ref = next("RPT")`.
  - **Synchronous** when the provider's estimated row count (`estimateRows(params)`, a `count(*)` the provider implements) ≤ `reports.syncExportRowLimit` and `?wait=true` (the default for screen export buttons): the file is streamed back directly (`Content-Disposition: attachment; filename="<Title> <from>–<to>.xlsx"`) and the export row is recorded as `DONE` with no stored file.
  - Otherwise it is `QUEUED` → `202 { ref, status }`. A worker (`@Scheduled`, 2 concurrent jobs per instance, `FOR UPDATE SKIP LOCKED` queue pick) runs it and stores the file. The requester gets an in-app notification "Your export RPT-88 is ready" with a download link.
  File layout: a title row, a filter summary row ("Showroom Ikeja · 1–31 Aug 2026 · generated 8 Oct 2026 14:02 by F. Adeniyi"), then summary lines, then the column header and rows. XLSX uses typed cells (numbers as numbers with ₦ format, dates as dates), a frozen header and auto-filter. A cost-hidden export contains the zeroed values and the note "Cost columns hidden for your role".
- **`GET /exports`** [paged] — the caller's exports (Administrators: `?all=true`). Row `{ ref, report, format, status, rowCount, sizeBytes, requestedAt, finishedAt, expiresAt }`.
- **`GET /exports/{ref}/file`** — the requester, or a schedule recipient, only → `403`. `EXPIRED` → `410 EXPORT_EXPIRED`.

### 4.7 Scheduled reports — `/schedules`

Perm `reports.schedules.manage` (Administrator, Management) — plus `canRun` for the report.
- `POST /schedules` `{ name, reportKey, params, format, frequency, dayOfWeek?, dayOfMonth?, sendAt?, recipients: [userIds] }`. Every recipient must be an active tenant member **who could run the report themselves** → else `400 RECIPIENT_NOT_PERMITTED` (details: user). Data is produced as the owner (`run_as_user_id`), and a schedule must not leak cost data or another location's figures to someone who couldn't see them. When the recipients' scopes differ, the owner's scope must be the narrowest; otherwise `400 RECIPIENT_SCOPE_NARROWER` with the suggestion to create one schedule per location.
- `PUT /schedules/{id}`, `POST /schedules/{id}/pause` / `resume`, `POST /schedules/{id}/run-now`, `GET /schedules`, `GET /schedules/{id}/history` (its exports).
- Runner: every 5 minutes, due schedules → create an export (`schedule_id` set) → on `DONE`, an e-mail per recipient through the notification outbox with the file attached (attachment resolver `REPORT_EXPORT`, doc 08 §2.6 pattern) and an in-app notification. A failure notifies the owner. If the owner loses permission or is deactivated, the schedule auto-pauses and the event `reports.schedule.paused` notifies Administrators.

---

## 5. Exposed to other modules — `com.zifiq.reports.spi` (read-only)

```kotlin
interface FinancialSummary { fun sci(scope: SciScope, from: LocalDate, to: LocalDate): SciResult }          // dashboard KPIs, AI briefings
interface SalesAnalytics  { fun aggregate(q: SalesAnalyticsQuery): List<SalesAggregateRow> }
interface StockAnalytics  { fun slowMoving(...); fun daysOfCover(...); fun valueBy(...) }
```
These are the same services the endpoints use. Callers pass their own user's cost visibility (the result is masked accordingly).

## 6. Permission codes

| Code | Allows | Default roles |
|---|---|---|
| `reports.sci.read` | SCI company/location | Administrator, Management, Accounts Personnel; Stock Keeper & Quality Control for `LOCATION` scope of their factory (PRD 5.1.22 user story; costs masked) |
| `reports.outsource.read` | Out-Source Stock Report | Administrator, Management, Stock Keeper |
| `reports.sales.read` | Sales analytics | Administrator, Management, Accounts Personnel |
| `reports.stock.read` | Stock analytics | Administrator, Management, Stock Keeper |
| `reports.schedules.manage` | Scheduled reports | Administrator, Management |

The catalogue and exports require no permission of their own; each provider enforces its report's permission.

## 7. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| R1 | Reports reflect underlying records at generation time | PRD acceptance criteria (all reports) | §1.4-1 |
| R2 | Restricted columns zeroed/hidden for unauthorised roles | PRD acceptance criteria; rule 3 | providers + §4.2 null profit |
| R3 | Empty result → empty state, not an error | PRD test case 2 (all reports) | all endpoints |
| R4 | Date-range filtering | PRD (all reports) | all endpoints |
| R5 | Location scoping | rule 12 | providers run as the user |
| R6 | Commissions shown at the amount allocated, paid or not | `SCIReport.jsx` basis | §4.2 |
| R7 | Stock values at landing cost, reconciling to the Comprehensive Stock Report | `SCIReport.jsx` basis | §4.2 |
| R8 | Exports equal the screen | derived | §1.4-2 |
| R9 | Scheduled reports never widen access | derived | §4.7 recipient rules |

## 8. Errors added

`FORMAT_NOT_SUPPORTED` 400 · `REPORT_NOT_FOUND` 404 · `EXPORT_NOT_FOUND` 404 · `EXPORT_EXPIRED` 410 · `EXPORT_TOO_LARGE` (stored as the job's failure reason; also 400 on synchronous requests) · `RECIPIENT_NOT_PERMITTED` 400 · `RECIPIENT_SCOPE_NARROWER` 400 · `SCHEDULE_NOT_FOUND` 404. (`RANGE_TOO_LARGE`, `LOCATION_ACCESS_DENIED` reused.)

## 9. Event catalog

| Event | Notifies |
|---|---|
| `reports.export.completed` / `.failed` | in-app the requester (async only) |
| `reports.schedule.created` / `.updated` / `.paused` / `.resumed` | paused-by-system → in-app Administrators |
| `reports.schedule.delivered` | — |
| `security.cost-data.viewed` (doc 03 §7.3) | — (written once per cost-visible report request and per export run) |

## 10. Search & filters

Reports is where the shared filter framework pays off. Every catalogued report's provider (§3) **declares its parameters as a doc 03 `FilterSpec`**, so the hub, exports and schedules accept exactly the same filters as the screen, including `period` presets, multi-value filters, the advanced filter body and `q`.

| Endpoint · list_key | `q` fields | Filters | Sort |
|---|---|---|---|
| `GET /catalog` | report title/description TRIGRAM (in-memory, accent-insensitive, typo-tolerant; synonyms such as "P&L"/"profit"/"income statement" → SCI, "debtors"/"receivables", "bin card"/"stock card") | `stream` multi, `supportsPdf`, `module` | stream order, then title |
| `GET /sales/analytics` | item code/name, customer code/name, correspondent name (resolve to the `groupBy` key filters) | everything in §4.4 + `paymentMethod`, `channel`, `priceBasis`, `hasDiscount`, `net` range; advanced body supported | any output column; default `net desc` |
| `GET /stock/analytics` | item code/name | `view`, `locationId` multi, `categoryId` multi, `itemType`, `asOf`, `daysSinceOutbound` range, `daysOfCover` range, `belowReorder` | per view (value desc, days desc, days of cover asc, days to expiry asc) |
| `GET /outsource-stock` | ref REFERENCE, product TRIGRAM, vendor TRIGRAM | §4.3 filters + `sellThroughPct` range, `remaining.gt`, `marginPct` range [cost] | `date desc`; `sellThroughPct`, `marginRealised`[cost] |
| `GET /exports` [paged] · `reports.exports` | `ref` REFERENCE, report title TRIGRAM | `status` multi, `format`, `reportKey`, `scheduleId`, `requestedAt` range, `all` (admins) | `requestedAt desc` |
| `GET /schedules` · `reports.schedules` | `name` TRIGRAM, report title TRIGRAM | `status`, `frequency`, `reportKey`, `ownerUserId`, `recipientUserId` | `nextRunAt` |

SCI and the statement-style reports take filters (scope, locations, period, comparison) rather than `q`.

**Global search provider**: `report` (title report name, subtitle stream, route to the screen). Typing "vat return" or "debtors" in the header search jumps to the report; only reports the caller can run are returned.

**Indexes (V17)**: `report_exports_user_idx`, `report_exports_queue_idx` (exist); `report_schedules_due_idx` (exists); trigram on `report_schedules(name)`.

## 11. Worked example

1. Management opens SCI for August with prior-period comparison: revenue ₦44.97m (showroom ₦25.88m, dealers ₦14.68m, special production ₦3.18m, by-product ₦1.27m), less returns/credit notes ₦0.04m; cost of sales ₦17.03m split by costing shares; operating expenses ₦3.95m including commissions allocated ₦391,180 (CA-88, paid or not); net profit ₦24.0m.
2. A Stock Keeper opens SCI Factory B for August: revenue lines visible, cost sections zeroed, profit null, `costVisible: false`.
3. Accounts exports the Comprehensive Stock Report (42,000 rows) to XLSX → queued `RPT-88`, ready in 40 s, notified, downloaded. The file expires on 15 Oct.
4. Management schedules "Weekly sales by showroom" (sales analytics, `groupBy LOCATION`, `{{periodStart}}`–`{{periodEnd}}`) every Monday 07:00 to three managers → each Monday an XLSX of the previous Mon–Sun arrives by e-mail.

## 12. Open items

1. **Inventory costing method.** Stock values use the item's *current* landing cost (as the Comprehensive Stock Report does), so a historical as-at value is re-priced at today's cost. Moving to weighted-average or FIFO costing would need a cost layer in the inventory ledger (a separate design decision affecting docs 02 and 05).
2. **Non-recorded expenses** (depreciation, payroll not paid as running costs, finance costs) don't appear unless recorded. A "journal adjustment" facility in Account would be needed for a statutory-grade SCI.
3. **Large-scale analytics.** If tenants grow to millions of sale lines, add materialised daily aggregates (`sales_daily_facts`) refreshed by an event consumer, behind `SalesAnalytics`.

## 13. Verification

- SCI revenue (showroom + dealer + factory lines) = Σ Sales Report net (doc 09) for the same range; VAT excluded.
- SCI COGS for a period with only at-till sales = Σ `POS_SALE` ledger `qty_out × unit_cost`.
- SCI stock value at period end = Comprehensive Stock Report value for that date.
- Non-cost user: cost sections zeroed, profit null, `costVisible: false`, and an export of the same report contains no cost value.
- Export of 4,999 rows streams synchronously; 5,001 rows is queued; download by another user → 403; after 7 days → 410.
- Schedule with a recipient lacking the report's permission → 400; owner deactivated → schedule pauses.
- Out-source FIFO: two receipts (100 then 50) and 120 sold → first fully sold, second 20 sold.
- Newman folder "20 - Reports".
