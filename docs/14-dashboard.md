# 14 — Dashboard Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.dashboard`
Depends on: `platform`, `common`, `inventory` (OPEN), every `spi` (`customers`, `settings`, `account`, `invoicing`, `cashier`, `showroom`, `communication`) and `reports::spi`
Depended on by: nobody (leaf).
Migration: `V18__dashboard_module.sql`

---

## 1. Scope

### 1.1 Features

| Feature | Source | Screen |
|---|---|---|
| Quick actions, setup-aware ("Create your first factory" until one exists) | `Overview.jsx` | Overview |
| "Where the business stands today" KPIs (locations, factories, products, raw materials, plus operating KPIs once data exists) | `Overview.jsx`; sketch "Dashboard" | Overview |
| **Pending actions**: one queue of everything waiting on the caller (approvals, confirmations, receipts of goods, OTP requests…) with counts and deep links | PRD §8 notification triggers; doc 07 `account.receipt.recorded` note ("surfaced as a count on the Dashboard's pending actions") | Overview |
| **Alerts**: re-order items, overdue invoices, open audit alerts, integrity findings, tills left open, stale exchange rates, near-expiry stock | PRD §8; docs 08, 09, 10, 13 | Overview |
| **Trends**: sales today vs yesterday / this week vs last, 30-day daily sales, top products, cash position, receivables ageing | sketch "Dashboard aggregates everything" | Overview |
| **Onboarding checklist** (company profile, first factory, first showroom, VAT settings, users, prices, tills) | `Onboarding` (doc 06), `Overview.jsx` empty state | Overview |
| Per-user widget preferences (hide/reorder) | derived | Overview "Customise" |

### 1.2 Decisions

1. **No new business data.** The dashboard composes figures the owning modules already compute (through their `spi`, `reports::spi`, and OPEN `inventory`). It owns only user preferences. Every number on it matches the screen it links to, because both come from the same code path.
2. **Role-aware by permission, not by role name.** A widget or pending-action source is shown only if the caller holds the permission of the screen it links to. Its figures are computed in the caller's location scope with cost masking (doc 03 §14). A Cashier sees their own till and receipts; Management sees company-wide figures.
3. **One request, partial failure tolerated.** `GET /dashboard` computes every visible widget in parallel (bounded pool of 6, each with a 2-second timeout). A widget that fails or times out returns `{ status: "UNAVAILABLE" }` instead of failing the whole page, so a slow report never blanks the home screen.
4. **Short cache.** Results are cached per `(tenant, user)` for 60 seconds with **Caffeine** (`com.github.ben-manes.caffeine`, the standard JVM cache, wired through Spring Cache). `?refresh=true` bypasses the cache. Pending-action counts are cached for only 15 seconds, because they drive work.

---

## 2. Schema — migration `V18__dashboard_module.sql`

```sql
create table dashboard_preferences (
    tenant_id uuid not null references tenants(id),
    user_id uuid not null references app_users(id),
    hidden_widgets jsonb not null default '[]'::jsonb,       -- ["trend.top-products", …]
    widget_order jsonb not null default '[]'::jsonb,         -- ordered widget keys; unknown/new keys appended
    default_location_id uuid references locations(id),       -- for multi-location users: which location the KPIs open on (null = all)
    pinned_views jsonb not null default '[]'::jsonb,         -- ≤ 6 saved_views ids shown as live cards (§9)
    updated_at timestamptz not null default now(),
    primary key (tenant_id, user_id)
);
-- + standard RLS
```

Module setting registered: `dashboard.staleFxHours` (int, default 24). After this many hours without a published rate, the "stale exchange rates" alert shows.

---

## 3. Widget catalogue

Each widget has a key, the permission required to see it, its source, and its response shape. Location-scoped widgets honour `locationId` (default: the user's preference, else their assigned location, else all).

### 3.1 Setup and quick actions (always shown)

| Key | Content |
|---|---|
| `setup.quick-actions` | `[ { key, title, description, route, urgent } ]`. "Create your first factory" (`urgent` when no factory exists; perm `factory.setup.manage`), "Register stock" (`inventory.stock.manage`), "View stock records" (`inventory.stock.read`), "Manage branches/showrooms" (`showroom.register.manage`). Then role-driven actions: "Open till" (`cashier.sales.manage` with no open session), "New sale" (open session), "Record receipt confirmation" (`accounting.receipts.confirm`), "Issue OTP" (`otp.issue` with pending requests). Max 6, ordered: urgent first, then the most-used routes |
| `setup.onboarding` | Shown to Administrators until complete or dismissed: `{ steps: [ { key, label, done, route } ], completedPct }`. Steps: company profile (`CompanyProfileProvider`), first factory, first showroom, VAT settings confirmed (any `location_vat_settings` row changed from the default), first price list published, first till registered, at least one user other than the creator. Dismiss = `PUT /preferences { hiddenWidgets += "setup.onboarding" }` |
| `setup.counts` | The `Overview.jsx` tiles: `{ locations, factories, showrooms, finishedProducts, rawMaterials, users }` with the same "none yet — create one to begin" foot copy when 0 |

### 3.2 Operating KPIs

| Key | Permission | Content (current vs comparison) |
|---|---|---|
| `kpi.sales-today` | `cashier.sales.read-all` (or own with `cashier.sales.read`) | net sales, transactions and units today vs the same weekday last week; for a Cashier: their own session |
| `kpi.sales-month` | `reports.sales.read` | month-to-date net sales vs the same days of last month; gross margin % [cost] |
| `kpi.cash-position` | `accounting.read` | Σ cash accounts (by kind) + Σ bank balances in scope; pending (unconfirmed) inflows shown separately |
| `kpi.receivables` | `invoicing.receivables.read` | outstanding total, overdue total and count, ageing buckets (≤30 / 31–60 / >60), over-limit customers |
| `kpi.stock-value` | `reports.stock.read` | stock value at landing cost by location type [cost]; slow-moving value [cost] |
| `kpi.wallets` | `customers.ledger.read` | Σ dealer wallet balances, Σ special-customer deposits (liabilities to customers) |
| `kpi.production` | `factory.production.manage` or `factory.costing.read` | batches and units produced this month; costings awaiting approval |
| `kpi.commission` | `communication.allocation.read` | last approved run total, unpaid outstanding |

Each KPI returns `{ key, label, value, unit ("NGN"|"count"|"pct"), comparison: { label, value, changePct } | null, foot, route, costVisible }`.

### 3.3 Pending actions — `pending.*`

One list. Each row is `{ key, label, count, oldestAt, route, severity }`, and a row is shown only when `count > 0` and the caller holds the permission:

| Key | Waiting item | Permission | Source |
|---|---|---|---|
| `pending.receipts-to-confirm` | POS/transfer/cheque receipts awaiting Accounts (PRD §8) | `accounting.receipts.confirm` | account |
| `pending.running-costs` | running costs awaiting approval (not submitted by the caller) | `accounting.running-costs.approve` | account |
| `pending.petty-cash-allocations` | petty cash sent to the caller's branch (PRD §8) | `accounting.petty-cash.respond` | account |
| `pending.funds-to-confirm` | funds disbursed to the caller's showroom (PRD §8) | `accounting.funds.confirm` | account |
| `pending.lodgements` | cash lodgements awaiting bank confirmation | `accounting.lodgements.confirm` | account |
| `pending.wallet-fundings` | wallet fundings awaiting confirmation | `accounting.receipts.confirm` | account |
| `pending.otp-requests` | wallet withdrawal / adjustment OTP requests (PRD §8) | `otp.issue` | platform |
| `pending.costings` | production costings awaiting approval | `factory.costing.approve` | inventory/factory (OPEN) |
| `pending.transfers-to-receive` | transfers dispatched to the caller's location | `inventory.transfers.manage` | inventory |
| `pending.trade-offers` | factory trade offers to respond to | `factory.trade.manage` | factory |
| `pending.instant-discounts` | discounts above the ceiling awaiting approval | `cashier.discount.approve` | cashier |
| `pending.pro-forma-discounts` | pro-formas awaiting discount approval | `cashier.discount.approve` | cashier |
| `pending.draft-invoices` | draft invoices from pro-forma conversions | `invoicing.invoice.issue` | invoicing |
| `pending.deliveries-failed` | invoice e-mails that failed | `invoicing.delivery.manage` | invoicing |
| `pending.waybills-to-acknowledge` | customer waybills issued > 2 days ago, not acknowledged | `cashier.waybill.manage` | cashier |
| `pending.supply-outstanding` | sales with goods still owed | `cashier.supply.read` | cashier |
| `pending.commission-runs` | runs awaiting approval (not run by the caller) | `communication.allocation.approve` | communication |
| `pending.commission-unpaid` | approved runs with outstanding payments | `communication.payments.manage` | communication |
| `pending.audit-alerts` | open audit alerts | `history.violations.read` | `dashboard_counters` projection (§3.5) |
| `pending.data-quality` | open data-quality flags | `history.data-quality.manage` | `dashboard_counters` projection (§3.5) |
| `pending.my-tasks` | open/in-progress workflow tasks assigned to or claimed by the caller | (own) | `dashboard_counters` key `workflow.open-tasks:<userId>` from `workflow.task.*` events (doc 15 §5.5) |
| `pending.recycle-bin-reviews` | suspended products due for review | `history.recycle-bin.manage` | inventory (status/review date, OPEN) |

Each source module's `spi` gains a `pendingCounts(principal): List<PendingCount>` method (read-only, scope-aware). This is the only spi addition the dashboard needs: `account::spi.AccountQueries`, `invoicing::spi.InvoiceQueries`, `cashier::spi.SalesQueries`, `communication::spi.CommissionQueries`. Platform/inventory/factory counts are read directly (OPEN modules).

### 3.4 Alerts — `alert.*`

| Key | Condition | Permission |
|---|---|---|
| `alert.reorder` | items at/below re-order level in scope (count + top 5) — PRD §8 | `inventory.stock.read` |
| `alert.overdue-invoices` | overdue invoices (count, value) | `invoicing.receivables.read` |
| `alert.over-limit-customers` | customers beyond credit limit | `invoicing.receivables.read` |
| `alert.till-left-open` | till sessions open from a previous day | `cashier.till.supervise` |
| `alert.stale-fx` | no exchange-rate publication within `dashboard.staleFxHours` | `accounting.fx.manage` |
| `alert.near-expiry` | batches expiring within `reports.nearExpiryDays` | `reports.stock.read` |
| `alert.integrity` | CRITICAL findings in the latest integrity run | `history.integrity.read` (§3.5) |
| `alert.vat-disabled` | locations with VAT collection disabled | `accounting.vat.manage` |

Alerts return `{ key, severity ('INFO'|'WARNING'|'CRITICAL'), title, detail, count, route }`.

### 3.5 Reading Audit & Compliance figures

`history` is a leaf with no `spi`, and the dashboard may not call it. Its three counts (open audit alerts, open data-quality flags, latest integrity findings) are published as **domain events**, which the dashboard folds into a tiny projection:
```sql
create table dashboard_counters (                            -- V18; maintained by consumer 'dashboard.counters'
    tenant_id uuid not null references tenants(id),
    counter_key varchar(60) not null,                        -- 'history.open-alerts', 'history.open-dq-flags', 'history.integrity-critical'
    location_id uuid,                                        -- null = tenant-wide
    value bigint not null,
    updated_at timestamptz not null default now(),
    primary key (tenant_id, counter_key, coalesce(location_id, '00000000-0000-0000-0000-000000000000'::uuid))
);
```
History includes the new absolute count in the payload of `history.alert.created/opened/dismissed`, `history.data-quality.flagged/corrected/dismissed` and `audit.integrity.finding` / run-completed events (doc 10 §8 payload addition: `{ openCount }`). The consumer simply stores the latest value, which keeps it idempotent. Lag is one consumer cycle (~15 s).

### 3.6 Trends — `trend.*`

| Key | Content | Permission |
|---|---|---|
| `trend.daily-sales` | last 30 days: `[ { date, net, transactions } ]` + 7-day moving average | `reports.sales.read` |
| `trend.top-products` | top 10 products by net this month with quantity and margin % [cost] | `reports.sales.read` |
| `trend.sales-by-location` | this month net per showroom/factory with change vs last month | `reports.sales.read` |
| `trend.receivables-ageing` | buckets over the last 6 month-ends | `invoicing.receivables.read` |
| `trend.cash-flow` | last 30 days daily inflow/outflow across cash and bank accounts | `accounting.read` |

---

## 4. Endpoints (under `/api/v1/dashboard`)

- **`GET /dashboard`** — authenticated. Query `locationId?`, `refresh?`. Returns every visible widget in the user's order:
  ```json
  { "data": {
    "generatedAt": "2026-10-08T09:14:03+01:00", "location": { "id": null, "name": "All locations" },
    "quickActions": [ … ], "onboarding": { … } | null, "counts": { … },
    "kpis": [ { "key": "kpi.sales-today", "label": "Sales today", "value": 1284500.00, "unit": "NGN",
                "comparison": { "label": "vs last Thursday", "value": 1102300.00, "changePct": 16.5 },
                "foot": "62 transactions · 4,210 units", "route": "/cashier/sales-report", "costVisible": true } ],
    "pending": [ { "key": "pending.receipts-to-confirm", "label": "Receipts awaiting confirmation", "count": 14,
                   "oldestAt": "2026-10-07T15:02:00+01:00", "route": "/account/tenderable?tab=confirm", "severity": "WARNING" } ],
    "alerts": [ … ], "trends": [ … ],
    "unavailable": [ "trend.cash-flow" ]
  } }
  ```
  `severity` for pending items: `WARNING` when the oldest item is > 24 h old, `CRITICAL` > 72 h, otherwise `INFO`.
- **`GET /dashboard/widgets/{key}`** — one widget (lazy loading / refresh of a single card). Unknown or not permitted → `404 WIDGET_NOT_FOUND`. Not permitted returns 404, not 403, so the response doesn't reveal which widgets exist.
- **`GET /dashboard/pending`** — just the pending list (15 s cache). The frontend polls it every 60 s for the header badge, alongside `GET /api/v1/notifications/unread-count` (doc 03 §9).
- **`GET /dashboard/preferences`**, **`PUT /dashboard/preferences`** `{ hiddenWidgets, widgetOrder, defaultLocationId }`. Unknown keys are dropped silently. `defaultLocationId` must be accessible → `403 LOCATION_ACCESS_DENIED`.
- **`GET /dashboard/catalog`** — the widgets the caller *could* show (for the "Customise" panel): `[ { key, title, group, hidden } ]`.

No permission code of its own: every widget is gated by its source's permission (§3).

## 5. Exposed to other modules

None (leaf).

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| D1 | Users see only data for their assigned location/role unless elevated | PRD §6 rule 12 | §1.2-2 |
| D2 | Cost figures zeroed/hidden for unauthorised roles | rule 3 | widget `costVisible` |
| D3 | Figures equal the linked screen | derived | §1.2-1 |
| D4 | PRD §8 in-app triggers have a persistent counterpart (counts don't disappear when a notification is read) | PRD §8 | §3.3 |

## 7. Errors added

`WIDGET_NOT_FOUND` 404. (`LOCATION_ACCESS_DENIED` reused.)

## 8. Event catalog

Emits none. Consumes `history.alert.*`, `history.data-quality.*`, `audit.integrity.*`, `workflow.task.*` (§3.5).

## 9. Search & filters

The dashboard has no lists of its own, but it is the main entry point into every module's search and filters:

- **Header search box**: the global search of doc 03 §4.7 (`GET /api/v1/search`). It shows grouped hits across every module the user can access, jumps straight to an exact reference, and has a "See all n" link per group that opens that module's list with `q` pre-applied.
- **Deep links carry filters.** Every pending action, alert and KPI `route` is a list route with its filter already encoded, e.g. `/account/receipts?confirmationStatus=PENDING&channel=POS,FUND_TRANSFER,CHEQUE&locationId=<id>&sort=receivedAt,asc` or `/invoicing/receivables?overdueOnly=true&ageingBucket=OVER_60`. Clicking through shows exactly the rows that were counted, and the user can refine or save it as a view (doc 03 §5.8). Counts and lists come from the same `FilterSpec`, so they always agree.
- **Saved views on the dashboard**: users can pin up to 6 saved views as dashboard cards (`dashboard_preferences.pinned_views jsonb`, an array of `saved_views.id`). Each card shows the view's live row count and the first summary figure (e.g. "Overdue dealers – Ikeja: 7 · ₦1.42m"), computed through the owning list's normal search endpoint with `size=0&summary=true` and cached for 60 s.
- **Widget catalogue search**: `GET /dashboard/catalog?q=` filters widgets by title (TRIGRAM) and `group`, for the "Customise" panel.

## 10. Worked example

- **New tenant, Administrator**: `setup.quick-actions` shows "Create your first factory" (urgent). `onboarding` is at 14 % (company profile done). Counts are all 0 with the "none yet" copy. No KPIs, because their sources have no data and return a value of 0 with foot "No sales yet".
- **Cashier at Ikeja, mid-shift**: quick actions "New sale" and "Close till"; `kpi.sales-today` shows their own session's ₦412,300 (38 sales); pending shows nothing they can act on.
- **Accounts Personnel, Ikeja**: pending shows "Receipts awaiting confirmation 14 (oldest 18 h)", "Funds to confirm 1", "Draft invoices 2"; alerts show "3 overdue invoices ₦640,000"; KPI cash position is ₦1.9m tills + ₦38.4m bank.
- **Management**: all KPIs company-wide with margin, trends, and "Open audit alerts 2" from the counter projection.

## 11. Open items

1. **Real-time push** of pending counts (SSE) is not needed this phase; polling every 60 s is enough. The doc 09 SSE pattern can be reused later.

## 12. Verification

- A Stock Keeper's dashboard contains no `kpi.cash-position`, and `GET /dashboard/widgets/kpi.cash-position` → 404.
- A widget whose source throws returns in `unavailable`; the rest render.
- Pending receipts count equals the Account confirmation queue length for the same user and location.
- Cost-masked user: `kpi.stock-value` value 0 with `costVisible: false`.
- Counter projection: creating and dismissing an audit alert updates `pending.audit-alerts` within one consumer cycle.
- Newman folder "21 - Dashboard".
