# 03 — Cross-Module Conventions & Platform Foundation II

Status: **design finalized, not yet implemented** — must be implemented *before* `04-customers-distributors.md`, because every later module doc assumes the conventions, shared tables, and refactors below already exist.
Owner packages: `com.zifiq.platform` (new sub-packages `events`, `documents`, `notifications.inbox`, `settings`), `com.zifiq.common` (new sub-package `search`, `paging`, `importing`)
Depends on: `00-platform-foundation.md`, `01-user-tenant-access-control.md`, `02-factory.md`
Migration: `V7__platform_foundation_ii.sql`

This doc exists because the remaining thirteen modules are **not independent of each other** the way Factory was. A single POS checkout must, in one database transaction, move stock (Inventory), debit a dealer wallet (Customers), record money received (Account), and issue an invoice (Invoicing). The rule set in doc 00 — "business modules depend only on `platform`/`common`/`inventory`, never on a peer business module" — cannot express that. This doc replaces that rule with an explicit, verifiable dependency graph, and fixes every other cross-cutting concern (search, paging, numbering, notifications, audit trail, documents, settings, bulk import) **once**, so each module doc can reference it instead of re-deciding it.

Every statement below that says "already exists" was verified against the code on 2026-10-08, not assumed.

---

## 1. Build order (revised) and doc/migration allocation

The order in the original roadmap (Showroom first) is wrong once the cross-module writes are mapped. Each module may only depend on modules above it in this table.

| Order | Doc | Module (sketch name) | Package | Migration | Why it sits here |
|---|---|---|---|---|---|
| 0 | `03` (this doc) | Platform Foundation II | `platform`, `common` | V7 | Everything below uses it |
| 1 | `04` | Customers & Distributors | `customers` | V8 | Parties, wallets, correspondents — Showroom returns, Account withdrawals, Invoicing bill-to and Sales all need them |
| 2 | `05` | Showroom | `showroom` | V9 | Needs Customers (dealer returns credit the wallet); adds the price list to Inventory |
| 3 | `06` | Settings | `settings` | V10 | Company profile + module settings; Invoicing prints the company's TIN/address from here |
| 4 | `07` | Finance & Accounting | `account` | V11 | Money substrate (bank, cash, petty cash, receipts, confirmations, FX); needs Customers for wallets |
| 5 | `08` | Electronic Invoicing | `invoicing` | V12 | Needs Customers (bill-to), Settings (issuer), Account (payments post to bank/cash) |
| 6 | `09` | Sales & POS | `cashier` | V13 | Orchestrator: needs Inventory, Customers, Account, Invoicing |
| 7 | `10` | Audit & Compliance | `history` | V14 | Read layer over 1–6 + violations, recycle bin, data quality, VAT compliance reports |
| 8 | `11` | Barcode & QR | `barcode` | V15 | Lookup/label service over Inventory + prices |
| 9 | `12` | Communication | `communication` (new) | V16 | Commissions need confirmed sales (Sales & POS) |
| 10 | `13` | Reports & Analytics | `reports` (new) | V17 | SCI and cross-module reports need 1–9 real |
| 11 | `14` | Dashboard | `dashboard` (new) | V18 | Aggregates everything |
| 12 | `15` | Workflow & Automation | `workflow` (new) | V19 | Consumes the domain-event stream every module emits |
| 13 | `16` | AI & Automation | `ai` | V20 | Reads everything; needs a provider decision first |

Migration numbers are **tentative** — whoever implements a module takes the next free `V<n>` at that time. The order of modules must not change without updating §2's dependency graph.

**Administration** (sketch module 15) has no doc: it is already real in `platform` (User Register, Users & Roles, Access Control, Change Password). The only Administration work left is the OTP console listing endpoint (§9.4), which lives in `platform`.

---

## 2. Module dependency rules (replaces doc 00's "never depend on a peer" rule)

### 2.1 The rule

A business module may depend on:
1. the **OPEN** modules `platform`, `common`, `inventory` (unchanged), and
2. the **`spi` named interface** of a *substrate* module that sits **earlier** in §1's build order — and nothing else from that module.

The four new substrate modules are `customers`, `settings`, `account`, `invoicing`. Each exposes exactly one package, `com.zifiq.<module>.spi`, containing the Kotlin interfaces/classes other modules may call (e.g. `WalletLedger`, `CompanyProfileProvider`, `MoneyLedger`, `InvoiceIssuer`). Everything else in those modules (`api`, `dto`, `model`, `repository`, `service`) stays internal.

`showroom`, `cashier`, `history`, `barcode`, `communication`, `reports`, `dashboard`, `workflow`, `ai` are **leaf/consumer** modules: nobody depends on them, so they expose no `spi`. (Read-only consumers such as `history` and `reports` that need data from a leaf module — e.g. customer waybills owned by `cashier` — get it through that leaf's `spi` package; see each doc's "Exposed to other modules" section. Where a leaf exposes an `spi`, it is listed in §2.3.)

### 2.2 How it is declared (Spring Modulith 1.4)

Substrate `package-info.java` (example for Customers):

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = { "platform", "common", "inventory" }
)
package com.zifiq.customers;
```

and its `spi/package-info.java`:

```java
@org.springframework.modulith.NamedInterface("spi")
package com.zifiq.customers.spi;
```

Consumer `package-info.java` (example for Sales & POS):

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "platform", "common", "inventory",
        "customers :: spi", "account :: spi", "invoicing :: spi", "settings :: spi"
    }
)
package com.zifiq.cashier;
```

`ModularityTests` (`ApplicationModules.of(ZifiqApplication::class.java).verify()`) then fails the build if any module reaches past an `spi` package, reaches a module not listed, or forms a cycle. OPEN modules are listed explicitly in `allowedDependencies` so the declaration is self-documenting; confirm at implementation time that listing OPEN modules is accepted by `verify()` on 1.4.13 (if `verify()` rejects it, drop them from the list — OPEN modules are reachable regardless).

### 2.3 The full graph (each arrow = "may call the `spi` of")

```
platform ◄── everything        common ◄── everything        inventory ◄── factory, showroom, cashier, barcode, history, reports, ai

customers  ──► (platform, common, inventory only)
showroom   ──► customers::spi
settings   ──► (platform, common only)
account    ──► customers::spi
invoicing  ──► customers::spi, settings::spi, account::spi
cashier    ──► customers::spi, settings::spi, account::spi, invoicing::spi          exposes cashier::spi (read-only: sales & customer waybills)
history    ──► customers::spi, account::spi, invoicing::spi, cashier::spi, showroom::spi
barcode    ──► customers::spi                       (dealer vs retail price on POS scans, doc 11)
communication ──► customers::spi, account::spi, cashier::spi, showroom::spi   (showroom: dealer returns net off commission, doc 12)   exposes communication::spi (read-only: commission totals)
reports    ──► every spi above
dashboard  ──► every spi above + reports::spi
workflow   ──► platform events only (no spi calls)
ai         ──► every spi above + reports::spi + factory::spi   (factory exposes a read-only spi for recipes, tolerances, costings, runs — doc 16 §5)
```

`showroom::spi`, `cashier::spi`, `communication::spi`, `reports::spi` are **read-only** query interfaces for downstream reporting; they never expose a write method. Only the four substrates expose writes.

### 2.4 Transaction rule for `spi` calls

Every `spi` method **runs inside the caller's transaction and never opens its own** — identical to how `inventory.repository.StockLedgerRepository.postEntry` and `platform.security.LocationAccessService` already behave. The caller wraps the whole use case in one `TenantScopedTransaction.execute { }`; a failure anywhere (insufficient wallet balance, insufficient stock, invoice numbering) rolls back every module's writes together. `spi` methods therefore must not call `tenantScopedTransaction.execute` themselves; they assume an active Exposed transaction with `app.tenant_id` already set. Each `spi` interface's KDoc must state this.

### 2.5 Why not Spring application events

Spring Modulith's `@ApplicationModuleListener` is `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + `REQUIRES_NEW` on Spring's transaction manager. This codebase deliberately runs every transaction through Exposed's own `transaction {}` (doc 00 §A), not Spring's `PlatformTransactionManager`, so after-commit listeners would never fire reliably, and the PRD's invariants (a wallet never goes negative *and* the sale either fully happens or doesn't) need the writes in **one** transaction anyway. Asynchronous fan-out that genuinely should happen after commit (notifications, workflow rules, AI scoring) goes through the `domain_events` table in §7 instead.

---

## 3. Module retirements and screen ownership

### 3.1 Packages dissolved

`com.zifiq.process` and `com.zifiq.menu` hold only mock code and no tables. Their content moves as follows; once the receiving module ships, the old package is deleted (no shim — per the "no backwards-compatibility hacks" rule).

| Mock thing today | Moves to |
|---|---|
| `process` Exchange Rates | `account` (Financial Controls) — doc 07 |
| `process` VAT Control screen | already real in `account` (`VatControlApi`) |
| `process` Commission rules (Comms Guide), Comms Allocator | `communication` — doc 12 |
| `process` Correspondent register | `customers` — doc 04 (Customers needs the FK first) |
| `process` System errors (Error Handler), Recycle Bin | `history` (Audit & Compliance) — doc 10 |
| `process` Showroom Register | `showroom` — doc 05 |
| `process` Change Password | already real in `platform` (`POST /api/v1/auth/change-password`) |
| `menu` Dealer Register | `customers` — doc 04 |
| `menu` Debtors List | `invoicing` — doc 08 |
| `menu` POS | `cashier` — doc 09 |
| `menu` User Register | already real in `platform` (`/api/v1/tenants/members`) |

### 3.2 Every remaining frontend screen → owning backend module

Route prefix in brackets. "Shipped" = already real.

| Frontend screen (`zifiqqq/src/app/...`) | PRD | Owner | Doc |
|---|---|---|---|
| `showroom/ReceiveProduct` | 5.2.1 | inventory endpoints, showroom reconciliation rule `[/api/v1/showroom]` | 05 |
| `showroom/StockEntryDirect` | 5.2.2 | showroom | 05 |
| `showroom/ShowroomAdjustment` | 5.2.3 | inventory (shipped) + area dimension | 05 |
| `showroom/ShowroomCards` (Group/Non-Group) | 5.2.4 | inventory (new group-card endpoints) | 05 |
| `showroom/OutsourceStock` | 5.2.5 | showroom | 05 |
| `showroom/ShowroomStockReport` | 5.2.6 | showroom | 05 |
| `showroom/ShowroomTransfer` | 5.2.7 | inventory transfers (shipped) + showroom validation | 05 |
| `showroom/Returns` (Dealer/Non-Dealer) | 5.2.8 | showroom | 05 |
| `showroom/GeneralTransferReport` | 5.2.9 | showroom | 05 |
| `showroom/ShowroomVATReport` | 5.2.10 | history (Compliance) | 10 |
| `process/ShowroomRegister` | 5.5.x | showroom | 05 |
| `others/OutsourceStockReport` (needs revenue/margin realised → sales data) | 5.7.x | reports | 13 |
| `process/VATControl` (per-showroom/per-factory rate, enablement, registration no., effective-dated) | 5.5.x | account (Financial Controls) | 07 |
| `others/AdjustmentsReport` | 5.7.5/6 | inventory (shipped endpoint + richer row) | 05 |
| `others/PriceList` | 5.7.10 | inventory price list (built in the Showroom pass) | 05 |
| `others/CustomerContacts` | 5.7.1 | customers | 04 |
| `others/CustomerHistory` | 5.7.x | customers | 04 |
| `others/CustomersComprehensive` (Distributors / No-Credit / Special) | 5.7.2–5.7.4 | cashier (sales data) | 09 |
| `others/DealersUpdate` | 5.7.14 | customers | 04 |
| `others/WalletWithdrawalOTP` | 5.7.18 | platform OTP console | 03 §9.4 |
| `others/ReturnReports` | 5.7.15 | showroom | 05 |
| `others/PendingSupplyReports` | 5.7.19 | cashier | 09 |
| `others/InstantDiscount` | 5.7.11 | cashier | 09 |
| `others/CashTransferHistory` | 5.7.x | account | 07 |
| `others/SCIReport` | 5.1.22 / 5.7.x | reports | 13 |
| `others/TransactionReceipts` | 5.7.x | platform document registry | 03 §8 |
| `others/CommsReport`, `others/CorrespondentsReport` | 5.7.12–13 | communication | 12 |
| `menu/DealerRegister` | 5.8.1 | customers | 04 |
| `menu/POS` | 5.8.2 | cashier | 09 |
| `menu/DebtorsList` | 5.8.3 | invoicing | 08 |
| `menu/UserRegister` | 5.8.4 | platform (shipped) | — |
| `account/*` (13 screens) | 5.3.1–5.3.14 | account | 07 |
| `process/ExchangeRate` | 5.5.x | account | 07 |
| factory nav: Cash Inflow/Outflow, Funds Awaiting Reception (deferred by doc 02) | 5.1.18–19 | account (location filter includes factories) | 07 |
| factory nav: SCI Factory (deferred by doc 02) | 5.1.22 | reports | 13 |
| factory nav: Factory VAT Report (deferred by doc 02) | 5.1.23 | history (Compliance) | 10 |
| factory nav: Stock Refill › Saved Drafts | 5.1.1–2 | inventory (gap in shipped module, §12.3) | 03 |
| `invoicing/*` | 5.10 | invoicing | 08 |
| `cashier/*` | 5.4.1–5.4.3 | cashier | 09 |
| `history/*` | 5.6.1–5.6.5 | history | 10 |
| `process/ErrorHandler`, `process/RecycleBin` | 5.5.x | history | 10 |
| `barcode/ScanAndLabel` | 5.11 | barcode | 11 |
| `process/CommsGuide`, `process/CommsAllocator` | 5.5.4–5 | communication | 12 |
| `process/Correspondent` | 5.5.8 | customers | 04 |
| `settings/CompanySettings`, `Onboarding` step 1 | — | settings | 06 |
| `StockRegistration` custom fields, `ProductVariation` | — | settings (field builder) + inventory (variants) | 06 |
| `settings/*` registers + `RegisterTools` bulk upload/edit | — | factory setup (gap in shipped module, §12.1) | 03 |
| `Overview` | — | dashboard | 14 |
| `ai/*` | 5.9 | ai | 16 |

---

## 4. Search — one stable, in-database engine for every module

The requirement: every module that lists records has **robust, sophisticated search and filtering**, built on a **stable package** rather than hand-rolled `LIKE` strings scattered across services. Decision: **PostgreSQL's own search features** (`pg_trgm`, `unaccent`, built-in full-text search), wrapped once in `com.zifiq.common.search` and `com.zifiq.common.filter`. Every module declares *what* is searchable and filterable; the shared code decides *how*.

### 4.1 What is used

| Need | Postgres feature | Why it is the stable choice |
|---|---|---|
| Names, codes, phone numbers, references (short fields): substring, typo-tolerant | **`pg_trgm`**: `ILIKE '%q%'` accelerated by a GIN `gin_trgm_ops` index, plus `word_similarity(q, field)` / the `<%` operator for fuzzy ranking | Contrib module shipped with every PostgreSQL distribution since 9.1 (including the `postgres:16-alpine` image this project runs). Marked **trusted** since PG 13, so the application's own DB role can `create extension` in a Flyway migration, exactly as V1 already does for `pgcrypto` |
| Accent-insensitive matching (French rollout, PRD 5.9: "Société" = "societe", "Thiès" = "thies") | **`unaccent`** contrib extension, wrapped in an `IMMUTABLE` SQL function `f_unaccent(text)` so it can be used in index expressions | Contrib, trusted since PG 13, ships with the same image |
| Longer free text (descriptions, notes, narratives, audit summaries) | Built-in full-text search: a `tsvector` **stored generated column** + GIN index, queried with `websearch_to_tsquery('simple', q)`; snippets with `ts_headline` | Core PostgreSQL since 8.3. `websearch_to_tsquery` (PG 11+) safely accepts raw user input (quotes, `-exclude`, `OR`) without query-syntax errors. The `'simple'` dictionary is used, not `'english'`, because product and customer names are largely Nigerian/Senegalese proper nouns and brand names that stemming would mangle |
| Typo-tolerant "did you mean" when nothing matched | `pg_trgm` `similarity()` over a per-entity vocabulary (§4.6) | same extension |

V7 adds `create extension if not exists unaccent;` and:
```sql
create or replace function f_unaccent(text) returns text
    language sql immutable parallel safe strict
    as $$ select public.unaccent('public.unaccent'::regdictionary, $1) $$;
```

### 4.2 Why not a search engine library/service

Evaluated and rejected **for now**:
- **Elasticsearch / OpenSearch**: a JVM service needing 1–2 GB heap on its own, while the dev box is a 1 GB `t3.micro`. It needs a sync pipeline from Postgres, and tenant isolation would have to be re-implemented on every query. Postgres RLS does not apply to an external index, so a single missed filter would leak another tenant's customers.
- **Meilisearch / Typesense**: lighter, but still a second stateful service to deploy, back up and keep in sync, with the same tenant-isolation problem.
- **Hibernate Search / embedded Lucene**: Hibernate Search requires JPA, which this codebase deliberately removed (doc 00 §A). An embedded Lucene index is per-process, so it breaks the moment a second app instance runs, and needs its own durability.

Postgres-native search runs **inside the same transaction and the same RLS policy** as every other query, so tenant isolation is automatic and results are never stale. Upgrade path if volume ever demands it: publish `domain_events` (§7) to an OpenSearch indexer. Clients see no API change, because every endpoint goes through §4.3–§5.

### 4.3 The free-text query `q`

Every list endpoint that searches takes **`q`** (optional, trimmed, max 200 chars). `common.search.QueryParser` parses it into a small AST before anything touches SQL:

| Syntax | Meaning | Example |
|---|---|---|
| words | all words must match (AND), each against any configured field | `citrus 50cl` |
| `"…"` | exact phrase | `"okafor & sons"` |
| `-word` | exclude | `malt -classic` |
| `OR` | either side | `ikeja OR lekki` |
| `field:value` | **qualifier**: the value is applied as a filter on a whitelisted field (§5.2), not as free text | `status:overdue customer:okafor` |
| `field:>n`, `field:<n`, `field:>=n`, `field:n..m` | numeric/date range qualifier | `total:>500000` `date:2026-09-01..2026-09-30` |
| a bare reference (`INV-4031`, `TXN-4502`, `WB-9168`, `DLR-42`) | exact reference match, ranked first | `INV-4031` |
| a bare number | matches references by numeric suffix (`4031` finds `INV-4031`) **and** amount fields declared `AMOUNT` (`52890` finds the ₦52,890.00 invoice) | `4031` |
| a phone-like value (≥ 7 digits, `+`, spaces) | normalised with `PhoneNumbers.normalise` (doc 04 §4.8) and matched against `PHONE` fields | `0803 123 4567` |

Qualifier names are the endpoint's filter keys and their aliases (`cust:` = `customer:`). Unknown qualifiers are treated as plain words, never as an error, so a typo still searches. A qualifier on a field the caller may not filter (cost fields, §5.6) is ignored and reported in `meta.ignoredQualifiers`.

**Matching per term**, implemented once in `SearchSpec`. A term matches when **any** configured field matches:
- `CODE` fields: case-insensitive exact, or prefix when the term is ≥ 2 chars, or numeric-suffix match for reference fields.
- `TRIGRAM` fields: `f_unaccent(lower(field)) ILIKE '%' || f_unaccent(lower(term)) || '%'`, or `field <% term` (word similarity ≥ 0.5) for typo tolerance on terms ≥ 4 chars.
- `FULLTEXT` fields: `search_vector @@ websearch_to_tsquery('simple', f_unaccent(term))`.
- `PHONE` fields: the normalised digits equal, or are a suffix of, the stored normalised digits (≥ 7 digits).
- `AMOUNT` fields: numeric equality to 2 dp.

A term of 1 character only prefix-matches `CODE` fields, so it never triggers a full scan. All values are bound parameters; `q` is never concatenated into SQL, and `%`/`_`/`\` are escaped before building `ILIKE` patterns.

**Ranking** when `q` is present and no explicit `sort` is given:
1. exact reference/code
2. prefix match on `CODE`
3. `greatest(word_similarity(...))` over `TRIGRAM` fields
4. `ts_rank_cd` over `FULLTEXT`
5. the endpoint's default sort
6. `id` (stable tiebreaker)

**Highlighting**: `highlight=true` adds `meta.matches` per row: `{ field, snippet }`. Snippets use `ts_headline` with `StartSel=<mark>` for full-text fields, and a server-side substring wrap for trigram fields. Snippet text is HTML-escaped before the marks are inserted, so it is safe to render.

### 4.4 Implementation shape

Exposed 0.61 has no built-in `pg_trgm`/`tsvector`/`unaccent` operators. `SearchSpec` adds them as small `CustomOperator`/`CustomFunction` expressions (`word_similarity`, `<%`, `@@`, `websearch_to_tsquery`, `ts_rank_cd`, `ts_headline`, `f_unaccent`). They are written once and unit-tested against Testcontainers Postgres.

```kotlin
// common/search
data class SearchField(val column: Expression<String?>, val kind: Kind, val label: String) {
    enum class Kind { CODE, REFERENCE, TRIGRAM, FULLTEXT, PHONE, AMOUNT }
}
class SearchSpec(val fields: List<SearchField>, val qualifiers: FilterSpec) {
    fun condition(q: ParsedQuery): Op<Boolean>?
    fun rank(q: ParsedQuery): List<Pair<Expression<*>, SortOrder>>
    fun highlights(q: ParsedQuery, row: ResultRow): List<Match>
}
```

### 4.5 Index recipe (copy into each module's migration)

```sql
-- trigram, case- and accent-insensitive
create index <table>_<col>_trgm on <table> using gin (f_unaccent(lower(<col>)) gin_trgm_ops);
-- full text (generated column, kept in sync by Postgres itself)
alter table <table> add column search_vector tsvector
    generated always as (to_tsvector('simple', f_unaccent(coalesce(<col1>,'') || ' ' || coalesce(<col2>,'')))) stored;
create index <table>_search_vector_idx on <table> using gin (search_vector);
-- every filterable column used for equality/range gets a btree (composite with tenant_id first, matching the endpoint's default sort)
create index <table>_<filter>_idx on <table> (tenant_id, <filter_col>, <default_sort_col> desc);
```
Trigram expressions in queries must use exactly `f_unaccent(lower(<col>))` to hit the index. The V7 code change re-creates the shipped trigram indexes in this form.

### 4.6 "Did you mean"

When a search with `q` returns zero rows, the endpoint returns `meta.suggestions` (≤ 3). These are the closest values of the entity's main `TRIGRAM` field by `similarity(f_unaccent(lower(field)), term) ≥ 0.3`, restricted to rows the caller can see (the same query, with `q` replaced). This costs one extra indexed query, only on empty results.

### 4.7 Global search (the header search box)

`GET /api/v1/search?q=&types=&limit=5` (authenticated). `platform.search.SearchProvider` is an interface each module implements for its main entities (the same dependency inversion as `ReportProvider`, doc 13 §3):
```kotlin
interface SearchProvider {
    val type: String                 // 'invoice', 'customer', 'stock-item', …
    val label: String                // 'Invoices'
    fun canSearch(principal: AuthPrincipal): Boolean
    fun search(q: ParsedQuery, limit: Int): List<SearchHit>   // runs the module's own SearchSpec, scope and permission
}
data class SearchHit(val type: String, val id: UUID, val title: String, val subtitle: String?, val ref: String?, val route: String, val score: Double)
```
- Providers run in parallel (pool of 6, 1.5 s timeout each; a slow provider is skipped and listed in `meta.timedOut`).
- An exact reference (`INV-4031`) short-circuits to that record as the single top hit.
- The response groups hits by type: `{ groups: [ { type, label, hits: [...] , more: true, moreRoute } ] }`.
- Providers registered by the module docs: stock items, recipes, suppliers, manufacturers, locations, users (platform/factory/inventory); customers, correspondents (04); transfers and waybills (inventory/09); returns, direct entries (05); receipts, running costs, bank transactions (07); invoices, credit notes (08); sales, pro-formas, customer waybills (09); violation records, data-quality flags (10, permission-gated); commission runs (12); reports, the catalogue only (13); workflow rules, tasks (15); anomalies, OCR jobs, briefings (16). Each provider's fields are its module's `q` fields.

### 4.8 Pickers (typeahead)

Every entity used in a form picker exposes `GET …/lookup?q=&limit=10&<context filters>`. It returns a light shape `[ { id, code, label, sublabel, status, disabled, disabledReason } ]` using the same `SearchSpec`, is limited to selectable rows by default (`status=ACTIVE`, the caller's locations), and has a 300 ms statement timeout. The module docs list their lookups. The frontend debounces at 250 ms.

---

## 5. Filtering, sorting, paging, saved views

### 5.1 Paging

Unbounded lists (ledgers, receipts, invoices, logs, sales, customers, …) take:
- `page` (0-based, default 0);
- `size` (default 50, max 200; larger → `400 PAGE_SIZE_TOO_LARGE`);
- or, for append-only ledgers and logs, **keyset paging**: `after=<cursor>` with an opaque base64 cursor of the sort key + id. This stays fast on page 10,000, where `OFFSET` would not.

Response:
```json
{ "data": { "items": [ … ], "page": 0, "size": 50, "totalItems": 1834, "totalPages": 37, "totalIsEstimate": false,
            "nextCursor": "eyJ…", "facets": { … }, "summary": { … },
            "meta": { "appliedFilters": [ … ], "ignoredQualifiers": [], "suggestions": [], "matches": {} } } }
```
`totalItems` is exact up to 10,000. Beyond that it is the planner's estimate with `totalIsEstimate: true`, so a count never becomes the slow part of a search. `common.paging.PageRequest` / `PageResult<T>` implement this once. Every paged endpoint is marked **[paged]** in its module doc.

Small, bounded reference lists (categories, units, bank accounts, expense categories, dealer categories, VAT categories, NRS reference data, locations) stay unpaged: `{"data": [...]}`, as today. Shipped endpoints keep their response shape. When the first paged endpoint ships, add a "Paged responses" section to `frontend-integration.md`.

### 5.2 Simple filters (query parameters)

Each endpoint declares a **`FilterSpec`**: the whitelisted filterable fields with type and operators. Simple filters use query parameters following one convention:

| Field type | Parameter forms | Notes |
|---|---|---|
| `ENUM` / `UUID` / `REF` | `status=OVERDUE` · repeated or comma-separated for OR: `status=ISSUED,OVERDUE` · `status!=VOID` | unknown enum value → `400 INVALID_FILTER` |
| `TEXT` | `customerName=okafor` (contains, accent-insensitive) · `customerName.eq=` · `customerName.prefix=` | |
| `NUMBER` / `MONEY` | `total.gte=50000&total.lte=100000` · `total.eq=` · also `totalMin=`/`totalMax=` aliases | money compared at 2 dp |
| `DATE` / `DATETIME` | `from`/`to` for the endpoint's primary date (inclusive, tenant timezone; doc 06) · `<field>.from`/`<field>.to` for others · `period=` preset | |
| `BOOLEAN` | `overdue=true` | |
| null checks | `<field>.isNull=true|false` (e.g. `waybill.isNull=true` = "not raised") | |
| `LOCATION` | `locationId=` (repeatable). A showroom/factory **includes its storage areas** (§11). Always intersected with the caller's scope | |

**Period presets** (`period=`), resolved in the tenant timezone: `TODAY`, `YESTERDAY`, `THIS_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `LAST_MONTH`, `THIS_QUARTER`, `LAST_QUARTER`, `THIS_YEAR`, `LAST_YEAR`, `LAST_7_DAYS`, `LAST_30_DAYS`, `LAST_90_DAYS`, `MONTH_TO_DATE`, `YEAR_TO_DATE`. Weeks start on Monday. An explicit `from`/`to` overrides the preset. The response echoes the resolved dates in `meta.appliedFilters`, so the screen shows "1–30 Sep 2026".

### 5.3 Advanced filters (filter builder)

For anything beyond simple parameters, every paged list also accepts **`POST <list-endpoint>/search`** with a JSON body. This avoids URL-length limits and keeps complex filters out of server logs:
```json
{ "q": "citrus",
  "filter": { "all": [
      { "field": "status", "op": "in", "value": ["ISSUED","PARTIALLY_PAID"] },
      { "field": "total", "op": "gte", "value": 500000 },
      { "any": [ { "field": "customerType", "op": "eq", "value": "AUTHORISED_DEALER" },
                 { "field": "dueDate", "op": "lt", "value": "2026-10-01" } ] },
      { "not": { "field": "waybills", "op": "is_empty" } } ] },
  "sort": [ { "field": "dueDate", "dir": "asc" }, { "field": "total", "dir": "desc" } ],
  "page": 0, "size": 50, "facets": ["status","showroom"], "highlight": true }
```
- **Grammar**: groups `all` / `any` / `not` (depth ≤ 4, ≤ 30 leaves → `400 FILTER_TOO_COMPLEX`).
- **Operators** by type:
  - `eq`, `ne`, `in`, `not_in` (ENUM/UUID/REF/TEXT);
  - `contains`, `starts_with`, `not_contains` (TEXT, accent-insensitive);
  - `gt`, `gte`, `lt`, `lte`, `between` (NUMBER/MONEY/DATE);
  - `is_null`, `is_not_null`;
  - `is_empty`, `is_not_empty` (collections such as waybills or payments);
  - `in_period` with a preset (DATE).
  This is the same operator vocabulary as Workflow conditions (doc 15 §4.1), so the frontend uses one builder for both.
- **Compiled by `common.filter.FilterCompiler`** to Exposed expressions against the endpoint's `FilterSpec`. A field not in the spec → `400 INVALID_FILTER` (details: field, allowed fields). An operator invalid for the type → `400 INVALID_FILTER`. Values are type-checked and bound.
- **Collection fields** (`waybills`, `payments`, `lines.item`) compile to `EXISTS (subquery)`, so line-level filters work: "invoices containing item ZF-101 with qty ≥ 100" is `{ "field": "lines.item", "op": "eq", "value": "<id>" }` + `{ "field": "lines.quantity", "op": "gte", "value": 100 }` inside a `{ "lines": { "all": [...] } }` group, meaning the same line satisfies both.

### 5.4 Filter metadata (so the frontend builds filters generically)

`GET <list-endpoint>/filters` returns the endpoint's `FilterSpec` for the caller:
```json
{ "data": {
  "fields": [
    { "key": "status", "label": "Status", "type": "ENUM", "operators": ["eq","ne","in","not_in"],
      "options": [ { "value": "ISSUED", "label": "Issued" }, … ], "facetable": true, "sortable": true, "qualifierAliases": ["st"] },
    { "key": "customer", "label": "Customer", "type": "UUID", "lookup": "/api/v1/customers/lookup", "operators": ["eq","in"] },
    { "key": "total", "label": "Total (₦)", "type": "MONEY", "operators": ["eq","gt","gte","lt","lte","between"], "sortable": true } ],
  "searchFields": [ { "label": "Invoice no.", "kind": "REFERENCE" }, { "label": "Customer name", "kind": "TRIGRAM" } ],
  "defaultSort": [ { "field": "issuedAt", "dir": "desc" } ],
  "periodPresets": ["TODAY", "…"] } }
```
Cost fields appear only for callers who can see cost (§5.6). Enum options are localised (en/fr) from the same message bundle as the UI.

### 5.5 Facets and summaries

- **Facets**: `facets=status,showroom` (or `"facets"` in the body) returns counts per value of each requested facetable field for the current result set. Each facet is computed with **its own filter removed**, so the chips show how many rows each choice would give. At most 20 values per facet (top by count) plus `"other"`, at most 6 facets per request.
- **Summary**: endpoints that show header totals (e.g. "Total outstanding") compute them over the **whole filtered set**, not the page. The module docs list them as `summary`.

### 5.6 Security rules for search and filters

1. Filters and sorts are only ever **narrowing**: location scope, RLS, "own records" limits and record-level confidentiality (violation subjects, doc 10) are applied *before* user filters and can't be overridden by them.
2. **Cost fields are neither filterable nor sortable nor searchable** for callers without `factory.costing.read`. A filter like `unitCost > 5000` on zeroed values would otherwise reveal hidden costs by elimination. Such fields are absent from `/filters`, rejected in `/search` (`400 INVALID_FILTER`), and ignored as qualifiers.
3. Facet counts obey the same scope (a count is data).
4. **Search guardrails**: each search query runs with `SET LOCAL statement_timeout = '5s'` (`408 SEARCH_TIMEOUT` with a hint to narrow the filters); `q` ≤ 200 chars; `in` lists ≤ 200 values.

### 5.7 Sorting

`sort=field,asc|desc` (repeatable) or the body's `sort` array, limited to the spec's `sortable` fields (`400 INVALID_SORT_FIELD`). `id` is always appended as a tiebreaker so paging is stable. Null ordering: `NULLS LAST` for both directions. Text sorts use the database collation on `f_unaccent(lower(col))`.

### 5.8 Saved views

Users save a list's filters, sort and visible columns as a named view. This is the screen's "Saved filters" menu, e.g. "Overdue dealers – Ikeja".
```sql
create table saved_views (                                   -- V7, platform
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    list_key varchar(80) not null,                           -- e.g. 'invoicing.invoices', 'cashier.sales'
    name varchar(80) not null,
    owner_user_id uuid not null references app_users(id),
    visibility varchar(10) not null check (visibility in ('PRIVATE','SHARED')),   -- SHARED: visible to members who hold the list's permission
    definition jsonb not null,                               -- { q, filter, sort, columns, period }
    is_default boolean not null default false,               -- the owner's default view for this list
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, owner_user_id, list_key, name)
);
-- + standard RLS
```
Endpoints (platform): `GET /api/v1/views?listKey=` (own + shared), `POST /api/v1/views`, `PUT /api/v1/views/{id}`, `DELETE /api/v1/views/{id}` (a user preference, so it is not a business record), `POST /api/v1/views/{id}/default`. Sharing requires `settings.views.share` (Administrator, Management by default). The definition is re-validated against the list's current `FilterSpec` when used. Fields that no longer exist are dropped and reported in `meta.ignoredFilters`. A saved view never stores the owner's scope: a shared view applied by another user runs in *that* user's scope.

### 5.9 Exports use the same filters

Every list's export (doc 13 §4.6) accepts the exact same `q`/filter/sort body, so "export what I'm looking at" is always exact.

### 5.10 Dates

Every list with a time dimension accepts `from`/`to` (ISO `yyyy-MM-dd`, inclusive, interpreted in the tenant's timezone, default `Africa/Lagos`) and `period`. The PRD repeats "allow users to filter by date range" on almost every feature; this is the one way it is done.

### 5.11 What each module doc must state

Each module doc has a **"Search & filters"** section listing, per list endpoint:
- **`q` fields** with kind (`REFERENCE`/`CODE`/`TRIGRAM`/`FULLTEXT`/`PHONE`/`AMOUNT`);
- **filters** with type, which are also the qualifiers;
- **facets**;
- **sortable fields** and the default sort;
- **summary** totals;
- the **saved-view `list_key`**;
- the **lookup** endpoints;
- the **global-search provider** and its hit shape;
- the **indexes** its migration creates.
`ModularityTests`-style `SearchSpecTests` assert that every `FilterSpec` field has a supporting index (by name convention) and that cost fields are flagged `cost = true`.

### 5.12 Searches added to already-shipped modules (V7 adds the indexes and specs)

| Endpoint | `q` fields | Filters (also qualifiers) | Facets | Sort (default first) |
|---|---|---|---|---|
| `GET /api/v1/inventory/stock-items` | code CODE, barcode CODE, name TRIGRAM, brand TRIGRAM | itemType, categoryId, locationId, status, vatCategoryId, hasBarcode, belowReorder, hasVariants, unitId | itemType, category, status | name; code, createdAt |
| `GET /api/v1/inventory/stock` (balances) | item code CODE, name TRIGRAM | locationId, categoryId, itemType, belowReorder, onHand.gte/lte, zeroStock | category, itemType | name; onHand, value[cost] |
| `GET /api/v1/inventory/stock/movements` (ledger) [paged, keyset] | sourceReference REFERENCE, item name TRIGRAM, remark FULLTEXT | stockItemId, locationId, sourceType, direction, createdBy, from/to | sourceType, location | entryDate desc |
| `GET /api/v1/inventory/stock/transfers` | waybill REFERENCE, driver name TRIGRAM, item name (via lines) TRIGRAM | fromLocationId, toLocationId, status, from/to, driverId, hasRejections | status, route | dispatchedAt desc |
| `GET /api/v1/inventory/quantity-adjustments` | ref REFERENCE, item name TRIGRAM, note FULLTEXT | locationId, stockItemId, direction, reason, performedBy, otpRequired, from/to | reason, direction | createdAt desc |
| `GET /api/v1/factory/setup/{suppliers,manufacturers,categories,units}` | code CODE, name TRIGRAM, contact person TRIGRAM, phone PHONE | status | status | name |
| `GET /api/v1/factory/recipes` | code CODE, name TRIGRAM, description FULLTEXT | sourcing, status, ingredient (lines) | sourcing | name |
| `GET /api/v1/factory/production-runs`, `/costings`, `/special-production`, `/trade-offers` | batch/ref REFERENCE, item/recipe/client name TRIGRAM | locationId, status, recipeId, stockItemId, from/to, aiSuggestionRef.isNull | status | createdAt desc |
| `GET /api/v1/tenants/members` | email CODE, display name TRIGRAM, phone PHONE | roleId, locationId, status | role, status | displayName |

---

## 6. Document numbering registry

`platform.numbering.DocumentSequenceService.next(prefix)` (shipped) returns `"$prefix-$n"`, per tenant, gap-free **as long as it is called inside the same transaction as the insert that uses the number** (the `document_sequences` row is locked `FOR UPDATE`, so a rollback also rolls back the increment). Every number below must be generated that way; PRD rule 14 ("invoice numbers unique, sequential, non-reusable") depends on it.

Every prefix in the system, so no two modules collide:

| Prefix | Document | Module | Status |
|---|---|---|---|
| `FAC` | Factory location code | factory | shipped |
| `SUP` | Supplier code | factory setup | shipped |
| `MFR` | Manufacturer code | factory setup | shipped |
| `ITM` | Stock item code | inventory | shipped |
| `RCP` | Recipe code | factory | shipped |
| `RF` | Finished-product refill | inventory | shipped |
| `DL` | Raw-material delivery / stock receipt | inventory | shipped |
| `QA` | Quantity adjustment | inventory | shipped |
| `WB` | Waybill — stock transfer **and** customer waybill (one tenant-wide waybill space, per PRD rule 6) | inventory, cashier | shipped / doc 09 |
| `PB` | Production batch | factory | shipped |
| `SO`, `SB` | Special production order / batch | factory | shipped |
| `FT` | Factory trade offer | factory | shipped |
| `CU` | Customer code (all non-dealer types) | customers | doc 04 |
| `DLR` | Dealer code (Authorised Dealer) — **not `DL`, which is taken** | customers | doc 04 |
| `CR` | Correspondent code | customers | doc 04 |
| `WLT` | Wallet/deposit ledger entry reference (when no business document drives it) | customers | doc 04 |
| `SHR` | Showroom location code | showroom | doc 05 |
| `DE` | Direct stock entry | showroom | doc 05 |
| `OS` | Outsource stock receipt | showroom | doc 05 |
| `RT` | Customer return | showroom | doc 05 |
| `AM` | Storage-area move | showroom | doc 05 |
| `PL` | Price list publication batch | inventory (Showroom pass) | doc 05 |
| `BA` | Bank account code | account | doc 07 |
| `EX` | Expense category code | account | doc 07 |
| `PCF` | Petty cash funding | account | doc 07 |
| `PCP` | Petty cash payment | account | doc 07 |
| `PPC` | Petty cash allocation to branch | account | doc 07 |
| `RC` | Running cost / operating cost | account | doc 07 |
| `BF` | Branch fund disbursement (Funds Awaiting) | account | doc 07 |
| `LDG` | Cash lodgement (cash transfer to bank) | account | doc 07 |
| `RCT` | Payment receipt (money received at a till/desk) | account | doc 07 |
| `WF` | Wallet funding | account | doc 07 |
| `WW` | Wallet withdrawal | account | doc 07 |
| `FX` | Exchange-rate publication batch | account | doc 07 |
| `MAN` | Manual bank entry (charges, interest, reconciling items) | account | doc 07 |
| `INV` | Invoice | invoicing | doc 08 |
| `CN` | Credit note | invoicing | doc 08 |
| `TXN` | POS sale | cashier | doc 09 |
| `PF` | Pro-forma invoice | cashier | doc 09 |
| `IDS` | Instant discount | cashier | doc 09 |
| `VN` | Violation record | history | doc 10 |
| `ER` | Data-quality flag (Error Handler) | history | doc 10 |
| `LBL` | Label print run | barcode | doc 11 |
| `EAN` | EAN-13 item-reference counter (numeric part only is used; never printed as `EAN-n`) | inventory codes (Barcode pass) | doc 11 |
| `QR` | QR payload counter for in-house recipe codes (`ZIQ-<code>-<nnnn>`) | inventory codes (Barcode pass) | doc 11 |
| `CA` | Commission allocation run | communication | doc 12 |
| `CP` | Commission payment | communication | doc 12 |
| `RPT` | Report export job | reports | doc 13 |
| `WR` | Workflow rule | workflow | doc 15 |
| `WH` | Inbound webhook endpoint | workflow | doc 15 |
| `WO` | Outbound webhook endpoint | workflow | doc 15 |
| `TSK` | Workflow task | workflow | doc 15 |
| `AN` | AI anomaly flag | ai | doc 16 |
| `OCR` | AI OCR job | ai | doc 16 |
| `BR` | AI executive briefing | ai | doc 16 |

A module doc that needs a new prefix adds it to this table in the same change.

---

## 7. Domain events (activity log + workflow/AI feed)

### 7.1 Why

Four later needs converge on "a reliable record of what happened, by whom, when": the Audit & Compliance activity log, Workflow & Automation triggers, AI anomaly detection, and the Dashboard's pending-actions/alerts. One table serves all four instead of each module inventing its own log.

### 7.2 Table (V7)

```sql
create table domain_events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    event_type varchar(80) not null,          -- '<module>.<aggregate>.<past-tense-verb>', e.g. 'account.running-cost.approved'
    aggregate_type varchar(60) not null,      -- 'running_cost', 'stock_transfer', 'invoice', ...
    aggregate_id uuid not null,
    aggregate_ref varchar(60),                -- human ref if any: 'RC-112', 'INV-4031'
    location_id uuid references locations(id),
    actor_user_id uuid references app_users(id),   -- null for system/scheduled actions
    actor_label varchar(120),                 -- 'System: overdue sweep' when actor_user_id is null
    occurred_at timestamptz not null default now(),
    payload jsonb not null default '{}'::jsonb,     -- small, non-sensitive summary; never cost fields, never secrets
    summary varchar(300) not null,            -- one human sentence, shown in the activity log
    search_vector tsvector generated always as (to_tsvector('simple', coalesce(aggregate_ref,'') || ' ' || summary)) stored,
    seq bigint generated always as identity   -- strictly increasing insert order; consumers checkpoint on it (§7.6)
);
create index domain_events_tenant_time_idx on domain_events (tenant_id, occurred_at desc);
create index domain_events_aggregate_idx on domain_events (tenant_id, aggregate_type, aggregate_id);
create index domain_events_type_idx on domain_events (tenant_id, event_type, occurred_at desc);
create index domain_events_seq_idx on domain_events (seq);
create index domain_events_search_idx on domain_events using gin (search_vector);
-- + standard RLS policy
```

### 7.3 Writing events

`platform.events.DomainEvents.record(type, aggregateType, aggregateId, aggregateRef, locationId, summary, payload)` — runs in the caller's transaction (same rule as §2.4), so an event exists **iff** the business change committed. The actor is taken from `CurrentActorProvider`; scheduled jobs pass an explicit system label.

**What must emit an event:** every create/approve/reject/void/confirm/decline/post/cancel/status-change of a business record, every OTP request/issue/verify/decline/revoke, every cost-bearing report fetched by a user who *can* see cost (`security.cost-data.viewed`, one event per request, aggregate = the report name) — this implements PRD §9's "audit access to cost fields". Plain reads do not emit events. Each module doc ends with an **Event catalog** table listing its event types.

**What must never go into `payload`:** landing/unit cost values, OTP codes or hashes, passwords/tokens, full card or account numbers (last 4 digits only).

### 7.4 Backfill for shipped modules

V7's companion code change adds `DomainEvents.record` calls to the already-shipped Factory/Inventory write paths (refill, receive, verify receipt, transfer dispatch/receive, adjustment, costing approve/reject, production run, special production, trade offer/respond) and to platform (member invite, role assignment, OTP). Event types: `inventory.refill.posted`, `inventory.receipt.posted`, `inventory.receipt.verified`, `inventory.transfer.dispatched`, `inventory.transfer.received`, `inventory.adjustment.posted`, `factory.costing.created`, `factory.costing.approved`, `factory.costing.rejected`, `factory.production-run.committed`, `factory.special-run.created`, `factory.special-run.status-changed`, `factory.trade.offered`, `factory.trade.accepted`, `factory.trade.rejected`, `platform.member.invited`, `platform.member.status-changed`, `platform.role.assigned`, `platform.otp.requested`, `platform.otp.issued`, `platform.otp.verified`, `platform.otp.declined`, `platform.otp.revoked`.

### 7.5 Retention

`domain_events` is append-only and never updated. Retention: keep 7 years (financial-record norm); a later ops task can move rows older than 2 years to a partitioned archive table. No deletion endpoint exists.

### 7.6 Consuming events (cross-module reactions without a dependency)

Some modules must react to something an *earlier* module did, where the earlier module may not call them (doc 03 §2.3). Example: Account declines a POS receipt; Invoicing must recompute the invoice that receipt paid. Mechanism:

```sql
create table event_consumer_checkpoints (
    consumer varchar(60) primary key,          -- 'invoicing.receipt-reconciler', 'workflow.dispatcher', 'ai.anomaly-scorer'
    last_seq bigint not null default 0,
    updated_at timestamptz not null default now()
);
```

`platform.events.EventConsumerRunner` runs each registered `EventConsumer` (an interface with `consumer: String`, `eventTypes: Set<String>`, `handle(event)`) on a `@Scheduled` fixed delay (default 15 s): it reads `domain_events` with `seq > last_seq` and `event_type in eventTypes` across all tenants (system-level read, same pattern as `NotificationOutboxDispatcher`'s documented tenant iteration), calls `handle` for each inside a `TenantScopedTransaction` for that event's tenant, and advances `last_seq` after each successful event. Handlers must be idempotent (an event can be delivered again after a crash between handling and checkpointing). This table has no `tenant_id` (it's system bookkeeping) and no RLS. Latency is seconds, not milliseconds — consumers are for derived state and notifications, never for enforcing an invariant (invariants are enforced synchronously in the owning transaction).

---

## 8. Document registry (Transaction Receipts) and rendering

### 8.1 Registry (V7)

PRD §5.10 requires every invoice to be "retrievable from both Invoice History and Transaction Receipts, consistently", and `others/TransactionReceipts.jsx` lists invoices, waybills, receipts and pro-formas side by side with reprint counts. One registry, written by whichever module issues a document:

```sql
create table issued_documents (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(40) not null,                  -- 'INV-4031', 'WB-9014', 'RCT-552', 'PF-2215'
    doc_type varchar(20) not null check (doc_type in ('INVOICE','CREDIT_NOTE','WAYBILL','RECEIPT','SALE_RECEIPT','PRO_FORMA')),   -- RECEIPT = an account RCT; SALE_RECEIPT = the itemised till receipt of a POS sale (doc 09)
    source_module varchar(30) not null,        -- 'invoicing', 'cashier', 'inventory', 'account'
    source_id uuid not null,                   -- id of the owning row (invoices.id, stock_transfers.id, ...)
    location_id uuid references locations(id), -- issuing location
    party_name varchar(200),                   -- snapshot: customer/dealer/destination name
    party_customer_id uuid,                    -- customers.id when the party is a registered customer (no FK: platform must not depend on customers)
    value_amount numeric(14,2),                -- null for documents with no money value (e.g. transfer waybill)
    issued_by_user_id uuid not null references app_users(id),
    issued_at timestamptz not null default now(),
    status varchar(20) not null default 'ISSUED' check (status in ('ISSUED','VOIDED')),
    reprint_count int not null default 0,
    search_vector tsvector generated always as (to_tsvector('simple', ref || ' ' || coalesce(party_name,''))) stored,
    unique (tenant_id, ref)
);
create index issued_documents_type_time_idx on issued_documents (tenant_id, doc_type, issued_at desc);
create index issued_documents_party_trgm on issued_documents using gin (lower(party_name) gin_trgm_ops);
create index issued_documents_search_idx on issued_documents using gin (search_vector);

create table document_reprints (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    issued_document_id uuid not null references issued_documents(id),
    reprinted_by_user_id uuid not null references app_users(id),
    reprinted_at timestamptz not null default now(),
    reason varchar(200)
);
-- + standard RLS on both
```

`platform.documents.DocumentRegistry.register(...)` / `.markVoided(ref)` run in the caller's transaction. Shipped Inventory's `dispatchTransfer` gets a `register(doc_type=WAYBILL, source_module='inventory')` call in the V7 code change.

### 8.2 Endpoints (platform)

- `GET /api/v1/documents` **[paged]** — perm: caller must hold the read permission for at least one `doc_type`; rows of types the caller can't read are filtered out (`INVOICE`/`CREDIT_NOTE` → `invoicing.invoice.read`; `RECEIPT` → `accounting.receipts.read`; `WAYBILL` → `inventory.stock.read` or `cashier.waybill.read`; `PRO_FORMA` → `cashier.proforma.read`). Query: `type`, `locationId` (through `LocationAccessService.resolveFilter`), `from`, `to`, `q` (ref CODE, party_name TRIGRAM). Row: `{ id, ref, docType, sourceModule, sourceId, locationId, locationName, partyName, valueAmount, issuedBy, issuedAt, status, reprintCount }`. Value is masked (`CostVisibility`-style zeroing is **not** applied — document values are selling values, not costs).
- `POST /api/v1/documents/{ref}/reprints` — perm: same read permission as the type. Body `{ reason? }`. Increments `reprint_count`, inserts `document_reprints`, emits `platform.document.reprinted`. Returns `{ ref, reprintCount, pdfUrl }` where `pdfUrl` is the owning module's PDF endpoint; the PDF is rendered with a "DUPLICATE COPY" watermark when `reprint_count > 0` (PRD: "a reprinted document carries the same reference as the original and is marked as a duplicate copy").

### 8.3 PDF rendering (one stack for invoices, waybills, receipts, pro-formas, labels, report exports)

- Templates: **Thymeleaf** (`spring-boot-starter-thymeleaf`, version managed by the Spring Boot BOM) rendering XHTML.
- HTML→PDF: **OpenHTMLtoPDF**, the maintained `io.github.openhtmltopdf:openhtmltopdf-pdfbox` fork (Apache PDFBox underneath). Pin the latest release at implementation time and run Trivy on it like every other dependency.
- Barcodes/QR inside documents: **ZXing** `com.google.zxing:core` (Apache 2.0, the de-facto JVM barcode library) rendered to PNG and embedded as a data URI.
- `platform.documents.PdfRenderer.render(templateName, model): ByteArray` is the single entry point. Fonts (Inter + a mono face) are bundled in `resources/fonts` so output doesn't depend on the host OS. Company branding (name, address, logo) comes from Settings (`settings::spi.CompanyProfileProvider`) — modules pass it in the model; platform does not depend on settings.
- PDFs are rendered on demand and streamed (`application/pdf`), not stored, because the source data is immutable once issued. Bulk exports (invoices ZIP, labels) are produced by the report-export job runner (doc 13) to avoid request timeouts.

---

## 9. Notifications

### 9.1 In-app inbox (new, V7)

PRD §8 sends almost every alert as an **"In-app alert"**, but the shipped notification channels are PUSH/EMAIL/WHATSAPP/TELEGRAM adapters that only log. Add a real in-app channel:

```sql
create table user_notifications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    user_id uuid not null references app_users(id),
    category varchar(30) not null check (category in ('APPROVAL_REQUIRED','ALERT','INFO','CONFIRMATION')),
    title varchar(160) not null,
    body varchar(600) not null,
    link_type varchar(40),                    -- frontend route key, e.g. 'account.running-costs'
    link_ref varchar(60),                     -- record ref/id to open
    source_event_id uuid references domain_events(id),
    created_at timestamptz not null default now(),
    read_at timestamptz,
    dismissed_at timestamptz
);
create index user_notifications_user_idx on user_notifications (tenant_id, user_id, created_at desc) where dismissed_at is null;
-- + standard RLS
```

`NotificationChannelType` gains `IN_APP`. The outbox dispatcher delivers `IN_APP` rows by inserting `user_notifications` (one per recipient) and marking the outbox row SENT. Endpoints (platform, perm: authenticated, only ever the caller's own rows):
- `GET /api/v1/me/notifications?unreadOnly=&category=` **[paged]**
- `GET /api/v1/me/notifications/unread-count` → `{ count }`
- `POST /api/v1/me/notifications/{id}/read`, `POST /api/v1/me/notifications/read-all`, `POST /api/v1/me/notifications/{id}/dismiss`

### 9.2 Recipient resolution (fixes a shipped defect)

Shipped Inventory/Factory write `recipient = "stock-managers"` / `"factory-trade-managers"` — literal strings that match no user, so those alerts reach nobody. New helper `platform.notifications.Recipients.withPermission(code, locationId?)` returns active members of the tenant who hold `code` and whose location assignment is ALL_LOCATIONS or equals `locationId` (or its parent, §11). Every notification in every module doc names its recipient as "holders of `<permission>` at `<location>`". The V7 code change replaces the two placeholder strings: re-order alerts → holders of `inventory.stock.manage` at the location; trade offers → holders of `factory.trade.manage` at the destination factory.

### 9.3 Notification helper

`platform.notifications.Notify.inApp(recipients, category, title, body, linkType, linkRef)` and `.email(...)` both write `notification_outbox` rows in the caller's transaction (so a rolled-back action never notifies). Email is sent only where a module doc says so; everything else is in-app only.

### 9.4 OTP console endpoints (Administration, platform)

`others/WalletWithdrawalOTP.jsx` is the Admin console over *all* OTP challenges (wallet withdrawals **and** quantity adjustments). Shipped `OtpChallengeService` has request/issue/verify only. Add:
- `GET /api/v1/otp/challenges?status=&purpose=` **[paged]** — perm `otp.issue`. Row: `{ id, ref (=target_reference), requestedAt, requesterName, requesterRole, purpose, targetReference, targetLabel, amount, status, expiresAt, codeMasked }`. `amount`/`targetLabel` come from a new nullable `otp_challenges.context jsonb` column (V7) that the requesting module fills (`{ "amount": 120000, "targetLabel": "Tunde Okafor (DLR-12)" }`). The code itself is never returned here.
- `POST /api/v1/otp/challenges/{id}/decline` — perm `otp.issue`, body `{ reason }`, only from `AWAITING` → new status `DECLINED`.
- `POST /api/v1/otp/challenges/{id}/revoke` — perm `otp.issue`, only from `SENT` → new status `REVOKED`.
- V7 widens `otp_challenges.status` check to `('AWAITING','SENT','USED','EXPIRED','DECLINED','REVOKED')`. The `purpose` check is unchanged: `WALLET_WITHDRAWAL`, `QUANTITY_ADJUSTMENT` are the only OTP purposes in the PRD (Instant Discount escalation uses a Management approval, not an OTP — doc 09).
- Stats for the console: `GET /api/v1/otp/challenges/stats` → `{ awaiting, valuePending, live, expiredUnused }`.

---

## 10. Module settings (configurable thresholds)

Several PRD rules say "outside standard thresholds" without a number, and the code hard-codes them (`ADJUSTMENT_OTP_THRESHOLD = 50` in `InventoryOperationsService`). These become tenant settings owned by `platform` (so every module, including OPEN `inventory`, can read them without depending on `settings`). Settings (doc 06) owns the *screen*; the storage and typed reader live here.

```sql
create table module_settings (
    tenant_id uuid not null references tenants(id),
    setting_key varchar(80) not null,
    value jsonb not null,
    updated_by_user_id uuid not null references app_users(id),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, setting_key)
);
-- + standard RLS
```

`platform.settings.ModuleSettings.get(key): T` returns the stored value or the registered default. Keys are registered in code (`ModuleSettingKeys`) with type, default, min/max, and owning module — an unregistered key cannot be written. Initial registry (each module doc adds its own):

| Key | Type | Default | Used by |
|---|---|---|---|
| `inventory.adjustment.otpThresholdQty` | decimal | 50 | inventory (replaces the constant) |
| `customers.wallet.withdrawalOtpRequired` | boolean | true | account (dealer-requested withdrawals) |
| `cashier.instantDiscount.ceilingPct` | decimal | 10 | cashier |
| `cashier.sale.voidWindowHours` | int | 24 | cashier |
| `cashier.sale.abandonAfterHours` | int | 12 | cashier |
| `cashier.proForma.defaultValidityDays` | int | 7 | cashier |
| `invoicing.defaultPaymentTermsDays` | int | 30 | invoicing |
| `invoicing.paidTolerance` | decimal | 1.00 | invoicing |
| `tenant.timezone` | string | `Africa/Lagos` | everyone |
| `tenant.currency` | string | `NGN` | everyone |

---

## 11. Locations: refactor + storage areas

### 11.1 Move `LocationsTable` to platform

`LocationsTable` currently lives in `com.zifiq.factory.model`, yet the `locations` table was created by platform migration V3 and is needed by every module (Showroom especially, which must not depend on Factory). V7's code change moves the Exposed object to `com.zifiq.platform.tenancy.model.LocationsTable` and updates the two importers (`factory.service.FactorySetupService`, `platform.tenancy.model.UserLocationAssignmentsTable` — whose KDoc about avoiding a platform→factory reference becomes obsolete and is updated). No schema change.

### 11.2 Storage areas (needed by Showroom; applies to factories too)

Showroom screens track stock per operational area (Shop floor / Warehouse). Model each area as its own location row so the shipped stock ledger needs no change:

```sql
alter table locations add column parent_location_id uuid references locations(id);
alter table locations drop constraint locations_type_check;
alter table locations add constraint locations_type_check check (type in ('FACTORY','SHOWROOM','STORAGE_AREA'));
alter table locations add constraint locations_area_parent_check
    check ((type = 'STORAGE_AREA') = (parent_location_id is not null));
alter table locations add column area_kind varchar(20)
    check (area_kind is null or area_kind in ('SHOP_FLOOR','WAREHOUSE','RECEIVING','QUARANTINE','OTHER'));
```

Rules: a `STORAGE_AREA` always has a `FACTORY`/`SHOWROOM` parent; areas never nest; VAT, address, manager come from the parent. Stock may sit on the parent location itself (shown as area "Unallocated") or on any of its areas. "All areas" views filter `location_id in (parent, children…)`.

### 11.3 `LocationAccessService` change

A user assigned to location P may access P **and all `STORAGE_AREA` children of P**. `restriction()` returns the assigned id; `assertAccess(x)` passes if `x == assigned` or `x.parent == assigned`; `resolveFilter(requested)` returns a *set* (`assigned` + its children) and list queries use `location_id in (...)`. The signature change (`resolveFilter` → `resolveFilterSet: Set<UUID>?`) is applied to every shipped caller in the V7 code change.

---

## 12. Gaps in already-shipped modules (fixed in the V7 pass)

These were found by comparing the frontend against shipped code. They are small and cross-cutting enough to fix here rather than wait for a module doc.

### 12.1 Factory Setup registers: edit, deactivate, bulk import

`settings/{Categories,Factories,Manufacturers,Suppliers,Units}.jsx` all offer **Edit**, **Remove**, and **Bulk upload** (`RegisterTools.jsx`: paste/CSV, columns in a fixed order, only the first column required, header row auto-detected, rows whose name already exists are skipped and reported). Shipped `FactorySetupController` has create+list for all five and edit only for factories.

Schema additions (V7):
```sql
alter table suppliers add column contact_person varchar(160);
alter table manufacturers add column contact_person varchar(160);
alter table units_of_measure add column note varchar(300);
alter table units_of_measure add column status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE'));
alter table categories add column status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE'));
create unique index suppliers_name_uq on suppliers (tenant_id, lower(company_name));
create unique index manufacturers_name_uq on manufacturers (tenant_id, lower(name));
create unique index units_name_uq on units_of_measure (tenant_id, lower(name));
```

Endpoints added to `/api/v1/factory/setup` (perm `factory.setup.manage`):
- `PUT /suppliers/{id}`, `PUT /manufacturers/{id}`, `PUT /units/{id}`, `PUT /categories/{id}` — full replace of editable fields; `409 DUPLICATE_NAME` on name collision.
- `PUT /{suppliers|manufacturers|units|categories}/{id}/status` — `{ status: ACTIVE|INACTIVE }`. **"Remove" in the UI maps to INACTIVE**, never a hard delete (§13). Inactive records stay on historical rows but are excluded from pickers (`?status=ACTIVE` default on list endpoints).
- `POST /{factories|suppliers|manufacturers|units|categories}/bulk` — body `{ rows: [ { <field>: value, ... } ] }` (max 500 rows; the frontend parses CSV, the backend receives JSON) → `{ created: n, skipped: [ { row: 3, name: "...", reason: "DUPLICATE_NAME" | "MISSING_REQUIRED" } ] }`. All-or-nothing per row, not per batch: valid rows are created, invalid ones reported. Codes are generated as for single create. Shared helper `common.importing.BulkImporter`.

### 12.2 Product custom fields and variants

`StockRegistration.jsx` lets the tenant define a reusable custom-field template for finished products ("Color", "Weight", "SKU"); each product marks each field **fixed** (one value for the product) or **variant** (a value per variation); `ProductVariation.jsx` then adds variations, each a stock line of the base product. Shipped `stock_items` is flat. Design (custom-field definitions owned by Settings doc 06 §5; schema added here because Inventory is OPEN and Settings must not be required by Inventory):

```sql
create table custom_field_definitions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    entity_type varchar(30) not null check (entity_type in ('STOCK_ITEM','CUSTOMER','SUPPLIER')),
    field_key varchar(60) not null,           -- slug of label, immutable
    label varchar(80) not null,
    data_type varchar(20) not null check (data_type in ('TEXT','NUMBER','DATE','BOOLEAN','SELECT')),
    options jsonb,                            -- for SELECT: ["Red","Blue"]
    required boolean not null default false,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE')),
    sort_order int not null default 0,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, entity_type, field_key)
);
alter table stock_items add column parent_stock_item_id uuid references stock_items(id);
alter table stock_items add column has_variants boolean not null default false;
alter table stock_items add column custom_attributes jsonb not null default '{}'::jsonb;  -- { field_key: value }
alter table stock_items add column variant_field_keys jsonb not null default '[]'::jsonb; -- on a parent: which keys vary
create index stock_items_attrs_idx on stock_items using gin (custom_attributes);
```

Rules: a parent with `has_variants = true` is a catalogue entry only — `StockLedgerRepository.postEntry` rejects it (`400 ITEM_HAS_VARIANTS`, post to a variant instead). Each variant is a normal `stock_items` row (own code, own barcode, own balance) with `parent_stock_item_id` set and `custom_attributes` holding the parent's fixed values **plus** its own variant values; the variant's `name` is generated `"<parent name> — <v1> / <v2>"`. Endpoints (inventory, perm `inventory.stock.manage`): `POST /api/v1/inventory/stock-items/{id}/variants` `{ attributes: { size: "50cl" }, code?, barcode?, reorderLevel? }`, `GET /api/v1/inventory/stock-items/{id}/variants`. `StockItemRequest` gains `customAttributes` and `variantFieldKeys`; values are validated against active definitions (type, required, select options) → `400 INVALID_CUSTOM_FIELD`.

### 12.3 Refill drafts ("Saved Drafts")

Factory nav has *Stock Refill › Saved Drafts — "every unposted refill and delivery, in full"*. Shipped refill endpoints post immediately. Add (V7):

```sql
create table stock_refill_drafts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    refill_type varchar(20) not null check (refill_type in ('FINISHED_PRODUCT','RAW_MATERIAL')),
    payload jsonb not null,                   -- the exact request body of the refill endpoint
    label varchar(160),
    created_by_user_id uuid not null references app_users(id),
    updated_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);
```
Endpoints (perm `inventory.stock.manage`, location-scoped): `POST/GET /api/v1/inventory/stock/refill-drafts`, `PUT/DELETE /api/v1/inventory/stock/refill-drafts/{id}` (drafts are the one thing hard-deleted — they are not records), `POST /api/v1/inventory/stock/refill-drafts/{id}/post` → runs the normal refill with the stored payload, deletes the draft in the same transaction, returns the refill result.

### 12.4 Stock items can't be edited

Shipped Inventory has `POST /stock-items` (register) and `PUT /stock-items/{id}/status` only — a typo in a product name, a wrong category, or a changed reorder level can't be corrected, and the Error Handler (doc 10) needs a sanctioned way to apply name corrections. Add `PUT /api/v1/inventory/stock-items/{id}` — perm `inventory.stock.manage`, location-scoped on the item's home location. Editable: `name`, `categoryId`, `brand`, `reorderLevel`, `barcode` (unique per tenant → `409 DUPLICATE_BARCODE`), `vatCategoryId` (must be active for the tenant), `hsnSectionId`, `isicSectionId`, `customAttributes`, and (cost-authorised callers only) `landingCost`, `markupCost`. **Not** editable: `code`, `itemType`, `unitOfMeasureId` (changing the unit would reinterpret every ledger quantity), `locationId`. Emits `inventory.stock-item.updated` with changed field names; historic documents keep their snapshots.

### 12.5 Doc drift to correct

`02-factory.md` prose names permissions `factory.transfers.manage` / `factory.adjustments.manage`; the shipped V4 migration and code use `inventory.transfers.manage` / `inventory.adjustments.manage`. The code is correct; fix the doc text in the V7 pass. Also `StockItemsTable`'s KDoc references a non-existent `docs/03-inventory.md` — point it at `02-factory.md` and this doc.

---

## 13. Records are never hard-deleted

Business records (anything with a reference number, money, stock, or a party) are never `DELETE`d. "Remove"/"Delete" in any UI maps to a status (`INACTIVE`, `VOID`, `DE_ASSIGNED`, `DELETED` for products in the Recycle Bin — doc 10). The only hard deletes in the system: refill drafts (§12.3) and the caller's own dismissed notifications after 90 days (housekeeping job). Temporary walk-in customers whose wallet reaches zero (PRD rule 10, "automatically removed") are set to status `REMOVED`, not row-deleted, because their ledger and return rows reference them (doc 04 §4.6).

---

## 14. Cost visibility — one rule for zero vs hidden

PRD §6 rule 3 says unauthorised roles see cost fields "as zero"; PRD §5.2.6 and the Showroom/Price List screens say cost columns are "hidden". Both are satisfied by one API rule: **cost-bearing responses always zero the values for unauthorised callers (shipped `CostVisibility.maskIfUnauthorized`) and additionally carry `"costVisible": false`** at the top level of the report response, so the UI can hide the columns. The API never omits a field conditionally (stable response shape), and never leaks a real value. Authorised = holds `factory.costing.read` (the shipped cost-visibility permission). Every module doc marks cost-bearing fields **[cost]**.

---

## 15. Errors added in V7

Added to `ErrorCode` / `ApiErrors` (single catalog, per doc 00): `DUPLICATE_NAME` (409), `ITEM_HAS_VARIANTS` (400), `INVALID_CUSTOM_FIELD` (400, details `{field, reason}`), `CHALLENGE_NOT_SENT` (409, revoke on non-SENT), `INVALID_SORT_FIELD` (400), `PAGE_SIZE_TOO_LARGE` (400), `SETTING_KEY_UNKNOWN` (400), `SETTING_VALUE_INVALID` (400), `DOCUMENT_NOT_FOUND` (404), `DUPLICATE_BARCODE` (409), `INVALID_FILTER` (400, details `{field, reason, allowed}`), `FILTER_TOO_COMPLEX` (400), `SEARCH_TIMEOUT` (408), `VIEW_NOT_FOUND` (404).

---

## 16. Permission codes added in V7

| Code | Description | Default roles |
|---|---|---|
| `platform.activity.read` | Read the domain-event activity log (doc 10 builds the screen) | Administrator, Management |
| `platform.documents.read` | Baseline for the document registry (type-level checks still apply) | Administrator, Management, Accounts Personnel, Cashier |
| `settings.custom-fields.manage` | Define custom fields | Administrator |
| `settings.module-settings.manage` | Change module settings | Administrator |
| `settings.views.share` | Share saved list views with colleagues (§5.8) | Administrator, Management |

`RoleDefaults` is extended accordingly (Administrator already has `*`).

---

## 17. V7 migration contents (checklist)

1. `create extension if not exists pg_trgm;` `create extension if not exists unaccent;` and the `f_unaccent` function (§4.1).
2. `domain_events` + `event_consumer_checkpoints` (§7), `issued_documents` + `document_reprints` (§8), `user_notifications` (§9.1), `module_settings` (§10), `custom_field_definitions` (§12.2), `stock_refill_drafts` (§12.3) — each with the standard RLS policy.
3. `otp_challenges`: add `context jsonb`, widen `status`/`purpose` checks (§9.4).
4. `locations`: `parent_location_id`, `area_kind`, widened type check, area-parent check (§11.2).
5. Setup-register columns and unique indexes (§12.1); `stock_items` variant/custom-attribute columns (§12.2).
6. Search and filter indexes for shipped tables (§5.12), all trigram indexes in the `f_unaccent(lower(col))` form (§4.5): `stock_items(name)`, `stock_items(brand)`, `suppliers(company_name)`, `suppliers(contact_person)`, `manufacturers(name)`, `categories(name)`, `units_of_measure(name)`, `recipes(name)`, `app_users(display_name)`, `drivers(name)`; full-text generated column on `recipes(description)` and `quantity_adjustments(note)`; btree filter indexes listed in §5.12. Plus `saved_views` (§5.8) and the `SearchProvider`/`FilterSpec` registrations for shipped lists.
7. Permission codes (§16).

## 18. Verification

1. `./gradlew test` — `ModularityTests` passes with the new `package-info.java` declarations (create them for `customers`, `settings`, `account`, `invoicing` with empty `spi` packages now, so later docs only fill them in).
2. Testcontainers test for `SearchSpec`: seed 200 stock items, assert `q=malt` ranks "Malt Classic 33cl" first, `q=mlt clasic` still matches via word similarity, `q=50%` doesn't wildcard, and a second tenant's items never appear.
3. Testcontainers test: `DomainEvents.record` inside a transaction that then throws → no event row exists.
4. Re-order notification: drive stock below reorder level → a `user_notifications` row exists for a user holding `inventory.stock.manage` at that location, none for a user assigned elsewhere.
5. `LocationAccessService`: a user assigned to showroom S can post to S's Warehouse area, is denied on another showroom's area.
6. Newman: existing 83 requests still pass unchanged (no shipped response shape changed); new folder "00 - Platform II" covers notifications inbox, document registry, OTP console, module settings, setup-register edit/bulk, refill drafts.
