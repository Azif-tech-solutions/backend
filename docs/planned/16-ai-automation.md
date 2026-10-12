# 16 — AI & Automation Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.ai`
Depends on: `platform`, `common`, `inventory` (OPEN), every `spi` (`customers`, `settings`, `account`, `invoicing`, `cashier`, `showroom`, `communication`, `factory` — new, read-only, §5) and `reports::spi`
Depended on by: nobody (leaf). Audit & Compliance reacts to its events (§4.5).
Migration: `V20__ai_module.sql`
Replaces: the mock `AiOperationsService` (7 hard-coded methods; Assistant and Briefing ungated, fraud gated by `iam.roles.manage`, OCR gated by `cashier.sales.manage`).

---

## 1. Scope

### 1.1 Features (PRD §5.9 — "each is additive … no AI output may automatically execute a financial or stock-affecting action without human confirmation")

| Feature | PRD | Screen | Engine |
|---|---|---|---|
| Smart Re-Order Forecasting | 5.9 | `ai/ReorderForecast.jsx` | statistical (§3.1) |
| Production Cost / Recipe Optimizer | 5.9 | `ai/RecipeOptimizer.jsx` | linear programming (§3.2) |
| Cash Flow Forecasting | 5.9 | `ai/CashFlowForecast.jsx` | statistical (§3.3) |
| Sales & Demand Forecasting | 5.9 | `ai/DemandForecast.jsx` | statistical (§3.4) |
| Fraud / Anomaly Detection | 5.9 | `ai/AnomalyDetection.jsx` | statistical (§3.5) |
| Document OCR Capture | 5.9 | `ai/DocumentOCR.jsx` | Claude vision + structured output (§3.6) |
| ZifIQ Assistant (EN/FR chat, calculations, cited) | 5.9 | `ai/Assistant.jsx` | Claude tool use over read-only tools (§3.7) |
| Executive Insights & Automated Briefing | 5.9 | `ai/ExecutiveBriefing.jsx` | deterministic facts + Claude wording (§3.8) |

### 1.2 Decisions

1. **Use an LLM only where language is the job.** Forecasts, the optimizer and anomaly detection are numeric problems. They use transparent statistical methods from **Apache Commons Math 3** (`org.apache.commons:commons-math3`: exponential smoothing built on its statistics, `SimpleRegression`, `DescriptiveStatistics`, and `SimplexSolver` for the optimizer). Results are reproducible, explainable ("consumption rose 18 % over 14 days"), cheap, and keep working with the LLM switched off. Claude is used for reading documents (OCR), understanding questions (Assistant) and writing prose (Briefing).
2. **Nothing an AI feature produces changes a live record by itself.** Every output is a *suggestion* row with a status. Acting on it goes through the owning module's normal endpoint, as the user, with that endpoint's permission checks and controls (OTP, four-eyes, Accounts confirmation). An accepted re-order suggestion becomes a refill **draft** (doc 03 §12.3), not a posted refill. An approved recipe mix is used only when QC runs the Production Cost Calculator with it. OCR pre-fills a form the user submits. A confirmed anomaly becomes an audit alert for a person to investigate.
3. **Scope and cost visibility are inherited, never re-implemented.** Every data read runs as the requesting user (or, for scheduled jobs, the recipient) through the same `spi`/report services the screens use, so `LocationAccessService` and `CostVisibility` apply unchanged. One deliberate difference, required by PRD 5.9 Assistant test case 2: when the Assistant's or Briefing's data tools hit a cost field the user cannot see, they **omit** it and return `costHidden: true`, and the model must decline, "not display a zeroed or approximate figure that implies unauthorized visibility".
4. **Claude integration.** Official **Anthropic Java SDK** (`com.anthropic:anthropic-java`, used from Kotlin), client `AnthropicOkHttpClient` built once as a Spring bean from `ANTHROPIC_API_KEY`. Model **`claude-opus-5-5`** for all three LLM features, configured as `ai.llm.model` so a tenant can be moved without a code change. Calls go through one `ClaudeGateway` service that applies:
   - **Effort** via `OutputConfig.effort`: OCR `LOW`, Briefing wording `MEDIUM`, Assistant `MEDIUM` (Claude Opus 5.5 defaults to `medium`; it is set explicitly so behaviour doesn't drift with defaults). Thinking is left at the model default (adaptive; on Opus 5.5 it cannot be disabled).
   - **Structured outputs** (`MessageCreateParams.builder().outputConfig(<Kotlin data class>)`) for OCR results, briefing items and the Assistant's final answer, so responses are schema-valid JSON, never parsed from prose.
   - **No forced `tool_choice`** (not supported on Claude Opus 5.5): the Assistant uses `auto` with tools marked `strict: true`.
   - **Prompt caching**: the system prompt and the tool definitions are static per feature (no timestamps or user data in them) and carry a cache breakpoint. Per-user context goes in the messages.
   - **Refusal handling**: always check `stopReason` before reading content. A `refusal` becomes a polite "I can't help with that" response (Assistant) or a `FAILED` job (OCR/briefing). The server-side refusal **fallback** (beta `fallbacks: "default"` with header `server-side-fallback-2026-07-01`) is enabled on these calls. The exact Java builder call for it must be confirmed against the SDK at implementation time, because the skill documentation does not show it.
   - **Errors**: the SDK's typed exceptions. Rate limit / 5xx / connection errors are retried by the SDK (default 2) and then surfaced as `503 AI_UNAVAILABLE`. Invalid-request errors are logged as bugs.
   - **Metering**: every call writes `ai_llm_calls` (tokens in/out/cache, latency, feature, user). A per-tenant monthly token budget (`ai.llm.monthlyTokenBudget`) is enforced before each call → `429 AI_BUDGET_EXCEEDED`.
5. **LLM features are off until a tenant switches them on** (`ai.llm.enabled`, default `false`). Tenant data (product names, customer names and balances, document images) is sent to Anthropic's API for processing, which the tenant must accept under its data-protection obligations (Nigeria Data Protection Act 2023; §11). The statistical features need no external service and are on by default.
6. **Grounded or silent.** The Assistant and Briefing may state only numbers that came from a tool or fact. A server-side check extracts every number in the model's text and verifies it against the tool or fact values (allowing formatting and rounding). Arithmetic is done by a deterministic `calculate` tool, so the "calculation" line the screen shows was computed by code, not the model. A failed check replaces the answer with an explicit "I couldn't verify that figure — open <report>" (PRD: "say so explicitly rather than guessing"). Sources are derived from the tools actually called, never from model text.

---

## 2. Schema — migration `V20__ai_module.sql`

All tables: `tenant_id` + standard RLS (omitted). Confidence is always an integer 0–100. The bands match the frontend (`aiConfidence.js`): ≥ 80 high, ≥ 55 medium, < 55 low. Below `ai.forecast.insufficientBelow` (55) a forecast is shown as "insufficient data" with no prediction (PRD test cases 2).

### 2.1 Shared changes

```sql
alter table stock_items add column lead_time_days int check (lead_time_days is null or lead_time_days between 0 and 365);   -- null → ai.reorder.defaultLeadTimeDays
alter table production_costings add column ai_suggestion_ref varchar(20);   -- OPT-n used for this costing (factory, OPEN-by-spi write is the factory endpoint itself)
alter table production_runs     add column ai_suggestion_ref varchar(20);   -- copied from the costing; "log which production runs used an AI-suggested mix"

create table recipe_ingredient_tolerances (                  -- factory-owned spec (edited on the Recipe Register; doc 02 extension)
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    recipe_id uuid not null references recipes(id),
    stock_item_id uuid not null references stock_items(id),  -- the registered ingredient
    min_qty numeric(14,3) not null check (min_qty >= 0),     -- per recipe yield
    max_qty numeric(14,3) not null,
    substitute_group varchar(40),                            -- ingredients sharing a group may replace each other
    substitute_ratio numeric(10,4) not null default 1,       -- units of this item equivalent to 1 unit of the group's base
    constraint rit_range check (max_qty >= min_qty),
    unique (recipe_id, stock_item_id)
);
create table recipe_group_requirements (                     -- e.g. 'SWEETENER' must total 42–45 kg-equivalent per yield
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    recipe_id uuid not null references recipes(id),
    substitute_group varchar(40) not null,
    min_equivalent numeric(14,3) not null,
    max_equivalent numeric(14,3) not null,
    unique (recipe_id, substitute_group)
);
```
Endpoints for the tolerances belong to Factory (`PUT /api/v1/factory/recipes/{id}/tolerances`, perm `factory.recipes.manage`, `{ ingredients: [ { stockItemId, minQty, maxQty, substituteGroup?, substituteRatio? } ], groups: [ { substituteGroup, minEquivalent, maxEquivalent } ] }`). The shipped Production Cost Calculator request gains optional `aiSuggestionRef`. Factory may not call AI (AI depends on Factory), so the ref is stored as given and AI reconciles it from the production-run event (§3.2).

### 2.2 Re-order forecasts

```sql
create table ai_reorder_forecasts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_date date not null,
    location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    on_hand numeric(14,3) not null,
    available numeric(14,3) not null,
    reorder_level numeric(14,3) not null,
    history_days int not null,
    daily_use numeric(14,4),                                 -- null when insufficient
    trend varchar(8) check (trend in ('RISING','FALLING','STEADY')),
    trend_pct numeric(7,2),                                  -- recent vs prior rate
    forecast_date date,                                      -- projected crossing of reorder_level
    days_to_threshold int,
    lead_time_days int not null,
    safety_stock numeric(14,3),
    suggested_qty numeric(14,3) check (suggested_qty is null or suggested_qty > 0),   -- never zero/negative (PRD); null = no order needed or cannot forecast
    no_order_needed boolean not null default false,
    spike boolean not null default false,
    spike_reason varchar(200),
    confidence int not null check (confidence between 0 and 100),
    insufficient_data boolean not null,
    method_note varchar(300) not null,                       -- e.g. 'EWMA α=0.3 over 90d, trend +18% (14d vs prior 14d)'
    status varchar(10) not null default 'OPEN' check (status in ('OPEN','ACCEPTED','ADJUSTED','DISMISSED','SUPERSEDED')),
    decided_qty numeric(14,3),
    decided_by_user_id uuid references app_users(id),
    decided_at timestamptz,
    dismiss_reason varchar(300),
    refill_draft_id uuid,                                    -- stock_refill_drafts.id created on accept/adjust
    unique (location_id, stock_item_id, run_date)
);
create index arf_open_idx on ai_reorder_forecasts (tenant_id, location_id, status, days_to_threshold);
```

### 2.3 Recipe optimizations

```sql
create table ai_recipe_suggestions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- OPT-n
    recipe_id uuid not null references recipes(id),
    location_id uuid not null references locations(id),      -- the factory (prices are location costs)
    batch_qty numeric(14,3) not null,
    current_cost numeric(14,2) not null,                     -- [cost]
    proposed_cost numeric(14,2),                             -- [cost]; null when no viable optimization
    saving numeric(14,2),                                    -- [cost]
    spec_check varchar(16) not null check (spec_check in ('WITHIN_SPEC','NEAR_TOLERANCE','NO_VIABLE_MIX')),   -- OUTSIDE_SPEC never produced
    changes jsonb not null default '[]'::jsonb,              -- [{ stockItemId, material, fromQty, toQty, unit, deltaCost, note }]
    confidence int not null,
    inputs_snapshot jsonb not null,                          -- prices used, tolerance set version, costing ids analysed
    status varchar(20) not null check (status in ('PROPOSED','PENDING_APPROVAL','APPROVED','REJECTED','APPLIED','EXPIRED')),
    requested_by_user_id uuid not null references app_users(id),
    decided_by_user_id uuid references app_users(id),
    decided_at timestamptz,
    rejection_reason varchar(300),
    applied_run_refs jsonb not null default '[]'::jsonb,     -- production batch refs that used it
    expires_at timestamptz not null,                         -- prices move: approvals lapse after ai.optimizer.validityDays
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
```

### 2.4 Cash-flow and demand forecasts

```sql
create table ai_cash_forecasts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_date date not null,
    location_id uuid references locations(id),               -- null = company-wide
    opening_position numeric(14,2) not null,
    opening_as_of timestamptz not null,
    history_days int not null,
    insufficient_data boolean not null,
    horizons jsonb not null,                                 -- [{ days:7|30|90, inflow, outflow, net, low, high, confidence }]
    weeks jsonb not null,                                    -- [{ period, start, end, inflow, outflow, net, low, high, confidence, outflowExceedsInflow }]
    recurring jsonb not null,                                -- [{ label, amount, due, source }]
    receivables_expected jsonb not null,                     -- [{ invoice, customer, due, amount, collectionProbability }]
    method_note varchar(300) not null,
    unique (tenant_id, run_date, coalesce(location_id, '00000000-0000-0000-0000-000000000000'::uuid))
);

create table ai_demand_forecasts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_date date not null,
    location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    history_days int not null,
    sold_last_period numeric(14,3) not null,                 -- last 4 weeks
    forecast_weeks jsonb,                                    -- [{ weekStart, qty, low, high }] for the next 12 weeks; null when no history
    forecast_period_qty numeric(14,3),                       -- next 4 weeks; null = no history
    stock_available numeric(14,3) not null,
    trend varchar(8) check (trend in ('RISING','FALLING','STEADY')),
    seasonality varchar(80),                                 -- 'Dry-season uplift' / 'Promotion active' / null
    promotion_active boolean not null default false,
    stockout_risk boolean not null default false,
    days_of_cover numeric(8,1),
    confidence int not null,
    insufficient_data boolean not null,
    method_note varchar(300) not null,
    unique (location_id, stock_item_id, run_date)
);
```

### 2.5 Anomalies

```sql
create table ai_anomalies (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- AN-n
    detector varchar(40) not null,                           -- §3.5 codes
    category varchar(80) not null,                           -- display: 'Repeated round-number discounts'
    subject_type varchar(10) not null check (subject_type in ('USER','LOCATION')),
    subject_user_id uuid references app_users(id),
    location_id uuid references locations(id),
    severity varchar(6) not null check (severity in ('HIGH','MEDIUM','LOW')),
    signal_strength int not null check (signal_strength between 0 and 100),
    detail varchar(2000) not null,                           -- deterministic narrative from the detector
    metrics jsonb not null,                                  -- { observed, baselineMedian, mad, robustZ, window, … }
    evidence jsonb not null,                                 -- [{ type, ref, id }] — the transactions behind it
    evidence_hash varchar(64) not null,                      -- dedupe identical evidence sets only (§3.5)
    status varchar(24) not null check (status in ('AWAITING_REVIEW','UNDER_INVESTIGATION','CONFIRMED','DISMISSED')),
    reviewed_by_user_id uuid references app_users(id),
    reviewed_at timestamptz,
    resolution_note varchar(1000),                           -- required for DISMISSED and CONFIRMED
    detected_at timestamptz not null default now(),
    unique (tenant_id, ref),
    unique (tenant_id, detector, evidence_hash)
);
create index ai_anomalies_status_idx on ai_anomalies (tenant_id, status, detected_at desc);
```

### 2.6 OCR

```sql
create table ai_documents (                                  -- the retained original (PRD: "retained and linked … for audit")
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    file_name varchar(200) not null,
    content_type varchar(60) not null check (content_type in ('image/jpeg','image/png','image/webp','application/pdf')),
    size_bytes int not null check (size_bytes <= 10485760),
    sha256 varchar(64) not null,
    content bytea not null,
    pages int not null default 1,
    uploaded_by_user_id uuid not null references app_users(id),
    uploaded_at timestamptz not null default now()
);
create table ai_ocr_jobs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- OCR-n
    document_id uuid not null references ai_documents(id),
    doc_type varchar(20) not null check (doc_type in ('DELIVERY_NOTE','SUPPLIER_INVOICE')),
    location_id uuid references locations(id),
    status varchar(16) not null check (status in ('PROCESSING','AWAITING_REVIEW','CONFIRMED','DISCARDED','FAILED')),
    fields jsonb,                                            -- [{ key, label, value, confidence, target, flagged, note }]
    line_items jsonb,                                        -- supplier invoice lines [{ description, quantity, unitPrice, amount, confidence }]
    matched_supplier_id uuid,                                -- trigram match against the register
    error_message varchar(300),
    linked_record_type varchar(30) check (linked_record_type in ('STOCK_RECEIPT','RUNNING_COST','PETTY_CASH_PAYMENT','DIRECT_ENTRY','OUTSOURCE_RECEIPT')),
    linked_record_ref varchar(40),
    linked_record_id uuid,
    confirmed_by_user_id uuid references app_users(id),
    confirmed_at timestamptz,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
```

### 2.7 Assistant

```sql
create table ai_conversations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    user_id uuid not null references app_users(id),
    language varchar(2) not null check (language in ('en','fr')),
    title varchar(120),                                      -- first question, truncated
    started_at timestamptz not null default now(),
    last_message_at timestamptz not null default now(),
    status varchar(10) not null default 'OPEN' check (status in ('OPEN','CLOSED'))
);
create table ai_messages (                                   -- append-only audit log (PRD: "All chatbot conversations must be retained")
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    conversation_id uuid not null references ai_conversations(id),
    seq int not null,
    role varchar(10) not null check (role in ('user','assistant')),
    text varchar(8000) not null,
    sources jsonb,                                           -- ["Comprehensive Stock Report"]
    calculation varchar(500),
    deep_link jsonb,                                         -- { label, route }
    confidence int,
    outcome varchar(16) check (outcome in ('ANSWERED','CANNOT_ANSWER','DECLINED_ACTION','DECLINED_PERMISSION','UNVERIFIED')),
    tool_calls jsonb,                                        -- [{ tool, input, resultSummary, denied }] — what data was read
    api_messages jsonb,                                      -- exact content blocks sent/received (needed to continue the thread append-only, §3.7)
    llm_call_id uuid,
    created_at timestamptz not null default now(),
    unique (conversation_id, seq)
);
```

### 2.8 Briefings

```sql
create table ai_briefing_preferences (
    tenant_id uuid not null references tenants(id),
    user_id uuid not null references app_users(id),
    cadence varchar(8) not null default 'WEEKLY' check (cadence in ('DAILY','WEEKLY','MONTHLY','OFF')),
    send_at time not null default '07:00',
    email_enabled boolean not null default false,            -- in-app always on
    language varchar(2) not null default 'en',
    metrics jsonb not null default '["SALES","STOCK","CASH","ANOMALIES","RECEIVABLES"]'::jsonb,
    thresholds jsonb not null default '{}'::jsonb,           -- overrides of §3.8 defaults, e.g. {"salesChangePct": 15}
    location_ids uuid[],                                     -- narrower than the user's scope (null = their full scope)
    primary key (tenant_id, user_id)
);
create table ai_briefings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- BR-n
    recipient_user_id uuid not null references app_users(id),
    cadence varchar(8) not null,
    period_start date not null,
    period_end date not null,
    generated_at timestamptz not null default now(),
    summary varchar(1000) not null,
    items jsonb not null,                                    -- [{ kind, tone, headline, detail, sources, factIds, route }]
    facts jsonb not null,                                    -- the computed facts the text was checked against (audit)
    flag_count int not null default 0,
    wording varchar(10) not null check (wording in ('LLM','TEMPLATE')),   -- TEMPLATE when the LLM is off/failed
    status varchar(10) not null default 'CURRENT' check (status in ('CURRENT','ARCHIVED')),
    unique (tenant_id, ref)
);
```

### 2.9 LLM metering

```sql
create table ai_llm_calls (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    feature varchar(12) not null check (feature in ('ASSISTANT','OCR','BRIEFING')),
    user_id uuid references app_users(id),
    model varchar(60) not null,
    request_id varchar(80),                                  -- Anthropic request id, for support
    input_tokens int, output_tokens int, cache_read_tokens int, cache_write_tokens int,
    stop_reason varchar(20),
    latency_ms int,
    outcome varchar(12) not null check (outcome in ('OK','REFUSAL','ERROR','BUDGET_BLOCKED')),
    error_type varchar(60),
    created_at timestamptz not null default now()
);
create index ai_llm_calls_month_idx on ai_llm_calls (tenant_id, created_at);
```

### 2.10 Module settings registered

| Key | Type | Default |
|---|---|---|
| `ai.llm.enabled` | boolean | false |
| `ai.llm.model` | string | `claude-opus-5-5` |
| `ai.llm.monthlyTokenBudget` | int | 20,000,000 |
| `ai.assistant.dailyMessagesPerUser` | int | 200 |
| `ai.assistant.maxToolRounds` | int | 8 |
| `ai.forecast.insufficientBelow` | int | 55 |
| `ai.forecast.minHistoryDays` | int | 30 |
| `ai.reorder.defaultLeadTimeDays` | int | 7 |
| `ai.reorder.serviceLevel` | decimal | 0.95 |
| `ai.reorder.coverDays` | int | 14 (target cover after the order arrives) |
| `ai.optimizer.validityDays` | int | 14 |
| `ai.anomaly.zThreshold` | decimal | 3.5 |
| `ai.ocr.confidentAt` | int | 80 |

---

## 3. Engines

All statistical jobs run nightly per tenant (02:30, after the day closes) in a `TenantScopedTransaction` with a **system actor holding full scope**. The rows they store are company-wide facts. Every **read endpoint** filters and masks them for the requesting user (location scope, cost fields), so storing full-scope results never leaks data.

### 3.1 Re-order forecasting

Per (location root, stock item) with stock or movement in the last 180 days:
1. **Consumption series**: daily outbound quantity from the inventory ledger (`POS_SALE`, `CUSTOMER_WAYBILL`, `TRANSFER_OUT`, `TRADE_OUT`, and raw-material consumption by `PRODUCTION_RUN`), last 90 days, zero-filled. Inbound entries and adjustments are excluded: they are not consumption.
2. `history_days` = days since the first ledger entry (cap 90). Fewer than `ai.forecast.minHistoryDays` → `insufficient_data = true`, `confidence` ≤ 40, no forecast. The screen falls back to the static threshold (PRD test case 2).
3. `daily_use` = EWMA (α = 0.3) of the daily series. **Trend** = mean of the last 14 days vs the previous 14: > +10 % RISING, < −10 % FALLING, else STEADY. Projected use = `daily_use × (1 + trend slope)` from a `SimpleRegression` over the last 30 days, floored at 0.
4. `days_to_threshold = ceil((available − reorder_level) / projected_daily_use)`, or 0 if already at/below the level. `forecast_date = run_date + days_to_threshold`.
5. Safety stock = z(`serviceLevel`) × σ(daily use, 30 d) × √`lead_time`. `suggested = ceil(projected_use × (lead_time + coverDays) + safety − (available − reorder_level))`. A result ≤ 0 → `suggested_qty null`, `no_order_needed true` ("never suggest a negative or zero-value order").
6. **Spike**: last-7-day use > median + 3.5 × MAD of the 90-day weekly sums (robust z), or < 30 % of median (a drop) → `spike = true` with the reason.
7. **Confidence** = 100 × (1 − clamp(MAPE of a one-step-ahead backtest over the last 28 days, 0, 1)) × min(1, history_days / 60), rounded.
8. The previous day's `OPEN` row for the pair → `SUPERSEDED` ("recompute automatically").

### 3.2 Recipe optimizer (on request, not nightly)

Input: recipe, factory, batch size. Data through `factory::spi.RecipeQueries` (ingredients, tolerances, group requirements, approved costings) and inventory (current landing cost per ingredient and substitute at that factory; available stock).
- **Linear program** (Commons Math `SimplexSolver`): variables = quantity of each allowed ingredient (registered + same-group substitutes) per batch. Minimise Σ qty × unit cost. Constraints: each ingredient within its `min_qty..max_qty` (scaled to batch); each group's Σ qty × `substitute_ratio` within the group's equivalent range; total yield balance (Σ quantities × the recipe's yield factor = batch size); and qty ≤ available stock when `respectStock=true`.
- **Feasible and cheaper by ≥ 0.5 %** → `spec_check WITHIN_SPEC`, or `NEAR_TOLERANCE` when any variable sits within 2 % of a bound (the screen's "Near tolerance limit"). `changes` lists every ingredient whose quantity changed, with `deltaCost`. **Infeasible or no saving** → `NO_VIABLE_MIX` ("indicates no viable optimization was found rather than forcing a suggestion").
- **No tolerances registered** for the recipe → `409 RECIPE_HAS_NO_TOLERANCES`. The optimizer never guesses a spec. This is the only way to guarantee "never fall outside the registered quality/spec tolerance".
- **Confidence**: price freshness (days since each ingredient's last receipt cost) and the dispersion of past runs' actual vs computed cost for this recipe.
- **Flow**: `PROPOSED` → Management/Admin approves → `APPROVED` (valid `validityDays`). QC then runs the shipped Production Cost Calculator with the suggested quantities and `aiSuggestionRef: OPT-n`. Factory stores the ref on the costing and the run. AI's consumer on `factory.production-run.committed` (payload includes `aiSuggestionRef`) marks the suggestion `APPLIED` and appends the batch ref (the "audit record of every AI-suggested recipe that was applied"). Costing approval itself remains Factory's normal four-eyes step.

### 3.3 Cash-flow forecasting

Per location (showroom/factory root) and company-wide:
- **Opening position**: Σ cash accounts + bank accounts in scope (`AccountQueries`) as of the run.
- **History**: daily inflows/outflows from `AccountQueries.cashLedger/bankLedger` (excluding internal moves between the company's own accounts: lodgements, fund disbursements), last 180 days.
- **Base projection**: weekly sums → Holt's linear exponential smoothing (α 0.4, β 0.1) separately for inflow and outflow, plus day-of-week shares to split weeks into days for the 7-day horizon.
- **Known items replace the base where they apply**:
  - **recurring commitments**: approved running costs with the same category + payee in ≥ 3 of the last 4 months → projected on their usual day, with source "Expenses/Payment List Guide / Running costs";
  - **receivables**: open invoices (`InvoiceQueries`/`Receivables`) at their due dates × a collection probability from the customer's on-time payment history (default 0.7);
  - **pending confirmations**: receipts awaiting Accounts at 0.9.
- **Range**: 80 % interval = ± 1.28 × residual σ × √(weeks ahead); `low`/`high` per week and horizon (the 90-day low can be negative, as the screen shows).
- **Confidence** from history length and the backtest error. Fewer than 60 days of history → `insufficient_data` (declines to forecast; PRD test case 2).
- **Flags**: `outflowExceedsInflow` per week.

### 3.4 Sales & demand forecasting

Per (showroom, sellable item):
- **Series**: weekly units from `SalesQueries.linesFor` (completed, non-voided), last 104 weeks.
- **No sales ever** → `forecast_* null`, `insufficient_data true`, confidence 0 ("must never present a demand forecast for a product with no historical sales data as though it were data-backed").
- **< 8 weeks**: insufficient. **8–51 weeks**: Holt's linear on weekly units. **≥ 52 weeks**: Holt-Winters additive with 52-week seasonality (own small implementation over Commons Math statistics). The seasonal label is set when the seasonal index for the forecast window is ≥ +15 % ("Dry-season uplift" uses the tenant's month names; otherwise "Seasonal uplift"/"Seasonal dip").
- **Promotions**: weeks with an active price reduction (price-list drop ≥ 5 %) or instant-discount share ≥ 5 % of net are marked. The model gets a promotion uplift factor estimated from past promoted vs unpromoted weeks. A promotion that is currently active sets `seasonality = 'Promotion active'`.
- `stockout_risk` = available < forecast demand over the next 4 weeks (lead-time aware: over `max(4 weeks, lead time)`). `days_of_cover` = available / (forecast per day). At-risk rows link to Stock Transfer and the Re-Order forecast (deep links).
- **Company view**: Σ sold/forecast/stock across showrooms; confidence = demand-weighted mean.

### 3.5 Anomaly detection

Detectors compare each subject to **its own baseline** (trailing 90 days, minimum 20 observations, otherwise the detector is skipped for that subject). Robust z = (x − median) / (1.4826 × MAD); flag at ≥ `ai.anomaly.zThreshold` (3.5). Severity: z ≥ 6 HIGH, ≥ 4.5 MEDIUM, else LOW. `signal_strength = min(100, round(z × 12))`.

| `detector` | Category (display) | Metric per subject-day / event | Source |
|---|---|---|---|
| `DISCOUNT_FREQUENCY` | Unusual discount frequency | instant discounts applied to a cashier's sales per day | cashier `SalesQueries` + discount history |
| `ROUND_DISCOUNTS` | Repeated round-number discounts | share of discounts that are exact multiples of ₦1,000 or whole percentages ≥ 5 %, vs baseline share (binomial test p < 0.001) | cashier |
| `RECEIPT_VOIDS` | Receipt voiding pattern | sale voids + receipt voids per cashier per week | cashier, account |
| `DECLINED_RECEIPTS` | Declined payment pattern | declined card/transfer receipts per cashier per week | account |
| `WITHDRAWAL_TIMING` | Wallet withdrawal timing | withdrawals outside the location's 5th–95th percentile operating hours, or ≥ 3 withdrawals for one dealer within 24 h | account `withdrawals` |
| `WITHDRAWAL_NEAR_THRESHOLD` | Withdrawals just under OTP threshold | count of company-initiated amounts within 5 % below any configured approval threshold | account |
| `ADJUSTMENT_FREQUENCY` | Adjustment frequency outlier | negative adjustments per stock keeper per week | inventory ledger |
| `ADJUSTMENT_VALUE` | Stock adjustment value outlier | value of a single negative adjustment vs the location's adjustments | inventory (value is internal; the anomaly shows quantities, and value only to cost-authorised viewers) |
| `TILL_VARIANCE` | Till variance pattern | \|variance\| per session per cashier | cashier till sessions |

- Withdrawals are also evaluated **as they happen** (consumer on `account.wallet.withdrawn`). Everything else runs nightly.
- Each anomaly records the exact transactions in `evidence` and a deterministic `detail` sentence ("Cashier F. Adeniyi applied 14 discounts on 6 Oct; her 90-day median is 2 (robust z 7.1). 11 of the 14 were exact ₦1,000 multiples.").
- **Dedupe** is only on the identical evidence set (`evidence_hash`). A dismissed false positive does **not** suppress future flags: new transactions create a new evidence set and a new anomaly ("must not silently suppress future flags of genuinely repeated behavior"), and the dismissal stays on record.
- Never any automated action: no suspension, reversal or blocking (PRD).

### 3.6 Document OCR (Claude vision)

1. Upload is stored in `ai_documents`. PDF: ≤ 5 pages (more → `400 DOCUMENT_TOO_LONG`). Image: re-encoded to JPEG ≤ 2,000 px on the long edge (reduces tokens, and EXIF/GPS metadata is stripped).
2. One Messages call: the document block (image or PDF base64) + an instruction naming the document type and, for context, the tenant's supplier/manufacturer names and the items expected at the location (both lists ≤ 200 names). Structured output schema per type:
   - **Delivery note**: `{ supplierName, manufacturerName, deliveryNoteNo, deliveryDate, items: [ { description, quantity, unit, batchNo, manufacturedDate, expiryDate } ] }`.
   - **Supplier invoice**: `{ supplierName, supplierTin, invoiceNo, invoiceDate, lines: [ { description, quantity, unitPrice, amount } ], subtotal, vatAmount, total }`.
   Every field is `{ value: string|null, legibility: "CLEAR"|"UNCERTAIN"|"NOT_PRESENT", sourceText: string|null }`. The instructions say: copy what is printed, never infer or complete a value, use null when not legible.
3. **Server-side confidence**, which does not trust the model's self-assessment alone: CLEAR = 90, UNCERTAIN = 60, NOT_PRESENT = 0. Then adjust:
   - dates that don't parse, or expiry ≤ manufactured → 40;
   - quantity × unitPrice ≠ amount by > 1 % → both 50;
   - Σ lines ≠ subtotal → total 50;
   - supplier name matched to the register with trigram similarity ≥ 0.6 → +10 and `matched_supplier_id` set;
   - TIN fails the format check → 40.
   Fields below `ai.ocr.confidentAt` (80) are `flagged`; NOT_PRESENT fields are "Not read" ("flag fields it could not confidently extract, rather than silently leaving them blank or guessing").
4. `fields[].target` maps each value to the destination form field ("Delivery note no." → "Waybill / invoice no.").
5. **Confirmation never writes the record here.** The screen opens the target form (Material Receive / Running Cost / Direct Entry / Outsource receipt) pre-filled. The user corrects and submits it through that module's own endpoint, under its permission, so OCR "must not bypass existing role-based permissions". The screen then calls `POST /ocr/jobs/{id}/link { recordType, recordRef }`. AI verifies the record exists through the owning module's query (inventory directly; `AccountQueries.get`; `ShowroomQueries`) and that it was created by the caller after the job → status `CONFIRMED` and the document link stored. "Discard" → `DISCARDED`. The document is kept either way.

### 3.7 ZifIQ Assistant

**Request loop** (per user message):
1. Check `ai.llm.enabled`, the daily per-user quota, and the tenant token budget.
2. Build the request:
   - **System prompt** (static, cached; no data in it). Rules: answer only from tool results; use `calculate` for any arithmetic; never perform or offer to perform an action, only deep-link; decline when a tool reports `denied` or `costHidden`, without hinting at the value; say when you cannot answer and name the report to open; reply in the requested language (`en`/`fr`).
   - **Tools**: the read-only set below, `strict: true`, cached after the last tool.
   - **Messages**: the conversation's prior **content blocks exactly as stored** (`ai_messages.api_messages`), appended, never edited. This keeps the cached prefix and Claude Opus 5.5's preserved-thinking blocks valid.
   - The current turn includes a short context block: user's display name, role names, assigned locations, today's date in the tenant timezone, and language.
3. Run the tool loop (`tool_choice auto`) for at most `ai.assistant.maxToolRounds`. Each tool executes **as the user** (§1.2-3), and every tool call is logged.
4. The final turn uses structured output: `{ answer, usedFigures: [ { value, fromTool, field } ], calculationId?, deepLink?: { module, route }, outcome: "ANSWERED"|"CANNOT_ANSWER"|"DECLINED_ACTION"|"DECLINED_PERMISSION", confidence }`.
5. **Grounding check** (§1.2-6): every number in `answer` must appear in a tool result or the cited calculation (normalising ₦, commas and rounding to the displayed precision). Otherwise the outcome is `UNVERIFIED` and the text is replaced: "I couldn't verify that figure. Please check the {report} report." The `confidence` shown = the model's value capped at 60 if any used tool returned partial data.
6. `sources` = display names of the tools' reports that contributed figures. `calculation` = the server-rendered formula string from the `calculate` call ("Gross profit ÷ revenue = 7,825,000 ÷ 22,865,000 = 34.2 %").
7. Persist both messages. Response to the screen: `{ conversationId, message: { text, sources, confidence, calculation, deepLink, refused (outcome ∈ DECLINED_*), cannotAnswer } }`.

**Tools** (each returns `{ source: "<report name>", data, denied?: { reason }, costHidden?: true, partial?: true }`):

| Tool | Input | Backed by | Permission |
|---|---|---|---|
| `find_items` | `query` | inventory item search (TRIGRAM) | `inventory.stock.read` |
| `find_customers` | `query` | `CustomerDirectory.search` | `customers.read` / `.read.own` |
| `find_locations` | `query` | locations in the user's scope | — |
| `stock_position` | `itemIds?`, `locationId?`, `asOf?` | Comprehensive Stock Report service | `inventory.stock.read` (value: `factory.costing.read`) |
| `sales_summary` | `from`, `to`, `groupBy`, `locationIds?`, `itemIds?` | `reports::spi.SalesAnalytics` | `reports.sales.read` or `cashier.sales.read-all` |
| `sci` | `scope`, `locationId?`, `from`, `to` | `reports::spi.FinancialSummary` | `reports.sci.read` |
| `customer_balance` | `customerId` | `CustomerDirectory` + `Receivables.outstandingFor` | `customers.read` / `invoicing.receivables.read` |
| `receivables` | `locationId?`, `overdueOnly?` | Invoicing receivables | `invoicing.receivables.read` |
| `cash_position` | `locationId?` | `AccountQueries` | `accounting.read` |
| `reorder_forecast` / `demand_forecast` | `locationId?`, `itemIds?` | §3.1 / §3.4 rows | as their screens (§4) |
| `production_summary` | `factoryId`, `from`, `to` | `factory::spi.ProductionQueries` | `factory.production.manage` (costs: `factory.costing.read`) |
| `calculate` | `expression` over named operands `{ name: value }` from earlier tool results | deterministic evaluator (`+ − × ÷ %`, `sum`, `avg`, `pctChange`, `margin`); exp4j-style own parser, no scripting | — |
| `open_report_link` | `reportKey`, `params` | doc 13 catalogue (returns route if permitted) | per report |

No tool writes anything. Date phrases ("last month", "le mois dernier") are resolved by the model into explicit `from`/`to` inputs, which the tool validates and echoes into `source` ("Sales Report, 1–30 Sep 2026").

**Audit**: conversations and messages are append-only and never deleted. `GET /assistant/audit` lets Administrators read any user's conversations (it emits `ai.assistant.audit-viewed`).

### 3.8 Executive briefing

For each recipient whose cadence is due (the `cadence` job runs every 15 minutes):
1. **Facts**, computed as the recipient, limited to their scope and chosen metrics. Each fact has `factId`, `kind`, values, comparison and `source`:
   - **SALES**: net sales by location vs the prior period, from `SalesAnalytics`. A fact is notable at |change| ≥ `salesChangePct` (default 15 %).
   - **STOCK**: items with a re-order forecast ≤ 7 days, demand stockout risks, slow-moving value [cost].
   - **CASH**: opening vs closing position, and any forecast week with outflow > inflow (§3.3).
   - **ANOMALIES**: new anomalies by severity. Only for recipients with `ai.anomalies.read`.
   - **RECEIVABLES**: overdue total change and new over-limit customers.
2. **Select** notable facts (threshold-based, max 8, ordered by severity × magnitude). If there are none → a single "stable" item ("must deliver a brief, accurate summary indicating stable performance rather than fabricating a notable change").
3. **Wording**: if the LLM is enabled, one call with the facts as JSON and structured output `{ summary, items: [ { factIds, kind, tone, headline, detail } ] }`. Instructions: use only the given numbers; one or two sentences per item; recipient's language. The grounding check runs per item, and any failing item falls back to its template text. If the LLM is disabled or the call fails, deterministic templates are used for everything (`wording TEMPLATE`).
4. Store `BR-n` with facts and items, mark the recipient's previous briefing `ARCHIVED`, and deliver in-app (always) plus by e-mail if enabled (notification outbox; e-mail provider caveat doc 08 §11). Emits `ai.briefing.delivered`.

---

## 4. Endpoints (all under `/api/v1/ai`)

Every response carrying a forecast includes `"label": "Forecast"` and `"generatedAt"`. Confidence is always present ("a forecast must never be presented without an associated confidence indicator").

### 4.1 Re-order forecast
- `GET /reorder` — perm `inventory.stock.read`. Query `locationId`, `itemType`, `status` (default OPEN), `dueWithinDays`, `spikeOnly`, `q`. Row: `{ id, item ("RM-06 · Cocoa Powder"), unit, onHand, available, reorderLevel, dailyUse, trend, trendPct, forecastDate, daysToThreshold, suggested, noOrderNeeded, leadTime, confidence, history, insufficientData, spike, spikeReason, methodNote, status, staticReorder: { belowLevel, level } }`. The static re-order view is included in every row (PRD: "must remain visible alongside").
- `POST /reorder/{id}/accept` — perm `inventory.stock.manage`. Creates a refill **draft** at the location (doc 03 §12.3) for `suggested` → `ACCEPTED`, returns `{ refillDraftId }`. `POST /reorder/{id}/adjust { quantity (> 0) }` → same with the user's quantity → `ADJUSTED`. `POST /reorder/{id}/dismiss { reason }` → `DISMISSED`. None of these touch stock, and manual re-ordering is never blocked.

### 4.2 Recipe optimizer
- `POST /recipe-optimizations` — perm `factory.recipes.manage` **and** `factory.costing.read` (the optimizer is a cost instrument; PRD: "landing-cost visibility restrictions … apply equally"). `{ recipeId, factoryId, batchQty, respectStock? }` → the suggestion (`ref OPT-n`). `NO_VIABLE_MIX` returns `proposedCost null`, `changes []`, `message`.
- `POST /recipe-optimizations/{id}/submit` → `PENDING_APPROVAL` (notifies `factory.costing.approve` holders). `/approve` and `/reject { reason }` — perm `factory.costing.approve`; approver ≠ requester.
- `GET /recipe-optimizations` [paged] — `recipeId`, `status`. Row: `{ ref, recipe, batch, currentCost, proposedCost, saving, savingPct, confidence, spec ('Within spec'|'Near tolerance limit'|'No viable mix'), status, changes, appliedRuns }`.

### 4.3 Cash-flow forecast
- `GET /cash-forecast?locationId=&horizon=7|30|90` — perm `accounting.read` (Accounts/Management only, PRD). Returns `{ openingPosition, openingAsOf, horizon: { days, inflow, outflow, net, low, high, confidence }, horizons, weeks, recurring, receivablesExpected, insufficientData, methodNote }`. `locationId` null → company-wide (ALL_LOCATIONS users only).

### 4.4 Demand forecast
- `GET /demand?scope=SHOWROOM|COMPANY&locationId=&categoryId=&atRiskOnly=` — perm `reports.sales.read` or `cashier.sales.read-all` (the Sales Report's own access, PRD), location-scoped. Row: `{ product, showroom, sold, forecast (null = "No history"), forecastWeeks, stock, cover (days), trend, seasonality, confidence, history, atRisk, links: { transfer: route, reorder: route } }`.

### 4.5 Anomalies
- `GET /anomalies` [paged] — perm `ai.anomalies.read` (Administrator, Management). Query `status` (tabs: `AWAITING_REVIEW`, `UNDER_INVESTIGATION`, resolved = `CONFIRMED,DISMISSED`), `severity`, `detector`, `locationId`, `subjectUserId`, `from`, `to`. Row: `{ ref, detected, subject, role, location, category, severity, signalStrength, detail, evidence: [ { type, ref, route } ], status, reviewedBy, resolutionNote }`. **The subject of an anomaly never sees it** (same rule as doc 10).
- `POST /anomalies/{id}/investigate` → `UNDER_INVESTIGATION`.
- `POST /anomalies/{id}/confirm { note }` → `CONFIRMED` and emits `ai.anomaly.confirmed`. Audit & Compliance turns it into an audit alert (doc 10 §4.4 `AI_ANOMALY`), "referred to audit".
- `POST /anomalies/{id}/dismiss { reason }` → `DISMISSED`, logged as a false positive.
- All three require perm `ai.anomalies.review`. No action anywhere suspends a user or reverses a transaction.

### 4.6 OCR
- `POST /ocr/jobs` — multipart `file`, `docType`, `locationId?`. Perm: the target record's create permission (`inventory.stock.manage` for delivery notes; `accounting.running-costs.submit` or `accounting.petty-cash.pay` for supplier invoices) **and** `ai.llm.enabled`. Returns `202 { ref, status: PROCESSING }`. Extraction runs on a worker; typically ~10–30 s.
- `GET /ocr/jobs` [paged] (own jobs; `all=true` for `ai.ocr.manage`) — row `{ ref, uploaded, file, docType, pages, status, by, linkedRecord }`.
- `GET /ocr/jobs/{id}` — `{ ref, file, docType, status, fields: [ { key, label, value, confidence, target, flagged, notRead } ], lineItems, matchedSupplier, prefill: { targetForm, route, values } }`.
- `GET /ocr/jobs/{id}/document` — the original file (requester, `ai.ocr.manage`, or anyone who can read the linked record).
- `POST /ocr/jobs/{id}/link { recordType, recordRef }` → `CONFIRMED` (§3.6). `POST /ocr/jobs/{id}/discard { reason? }`.

### 4.7 Assistant
- `POST /assistant/conversations` `{ language }` → `{ conversationId }`. `GET /assistant/conversations` (own) · `GET /assistant/conversations/{id}` (messages).
- `POST /assistant/conversations/{id}/messages` `{ text (1–2,000 chars) }` → the assistant message (§3.7). Perm `ai.assistant.use` (all operational roles by default). Answers are scoped per tool regardless.
- `GET /assistant/suggestions` → role-aware starter questions (static per permission set, localised).
- `GET /assistant/audit` [paged] — perm `ai.assistant.audit` (Administrator): conversations of any user, filterable by user and date.
- `POST /assistant/conversations/{id}/close`. Conversations are never deleted.

### 4.8 Briefings
- `GET /briefings` (own; `ref`, `period`, `generated`, `cadence`, `items`, `flags`, `status`) · `GET /briefings/{id}` (items with `sources`, `route`).
- `GET /briefings/preferences` · `PUT /briefings/preferences` `{ cadence, sendAt, emailEnabled, language, metrics, thresholds, locationIds }`. `locationIds` must be within the user's scope. Perm `ai.briefings.receive` (Administrator, Management, plus anyone granted).
- `POST /briefings/generate-now` — generates the current period for the caller immediately (rate-limited to 3 per day).

### 4.9 Usage
- `GET /usage?month=` — perm `ai.settings.manage`: tokens by feature and by user, budget remaining, refusal and error counts.

---

## 5. New read-only `factory::spi`

The optimizer and Assistant need recipe, tolerance, costing and production data that the `factory` module owns. Factory gains `com.zifiq.factory.spi` (read-only, like `cashier::spi`):
```kotlin
interface RecipeQueries {
    fun recipe(id: UUID): RecipeView                          // ingredients, yield
    fun tolerances(recipeId: UUID): ToleranceSet?
    fun approvedCostings(recipeId: UUID, limit: Int): List<CostingView>   // [cost] masked per caller
}
interface ProductionQueries {
    fun runs(filter: ProductionFilter): List<ProductionRunView>         // incl. aiSuggestionRef
}
```
Doc 03 §2.3 graph: `factory` exposes `factory::spi` (read-only); `ai ──► … factory::spi`. `factory.production-run.committed` event payload gains `aiSuggestionRef`.

## 6. Permission codes

| Code | Allows | Default roles |
|---|---|---|
| `ai.assistant.use` | Chat | Administrator, Management, Accounts Personnel, Stock Keeper, Quality Control, Cashier |
| `ai.assistant.audit` | Read all conversations | Administrator |
| `ai.anomalies.read` | Anomaly queue | Administrator, Management |
| `ai.anomalies.review` | Investigate/confirm/dismiss | Administrator, Management |
| `ai.ocr.manage` | See all OCR jobs/documents | Administrator, Accounts Personnel |
| `ai.briefings.receive` | Receive and configure briefings | Administrator, Management |
| `ai.settings.manage` | AI module settings, usage | Administrator |

Forecast screens reuse their underlying reports' permissions (§4.1–4.4). The mock-era gates are removed.

## 7. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| AI1 | No AI output executes a financial/stock action without human confirmation | PRD §6 rule 13; 5.9 | §1.2-2; every endpoint |
| AI2 | Forecasts labelled as forecasts with a confidence indicator | 5.9 | §4 |
| AI3 | Insufficient history → say so, fall back to static/manual | 5.9 test cases | §3.1–3.4 |
| AI4 | Suggested order quantity never ≤ 0 | 5.9 | §3.1 step 5, DB check |
| AI5 | Optimizer never outside registered spec; no viable mix → say so | 5.9 | §3.2 |
| AI6 | AI mixes need approval; applied mixes are audited | 5.9 | §3.2 flow |
| AI7 | Anomalies route to human review; never punitive; dismissals logged, don't suppress repeats | 5.9 | §3.5, §4.5 |
| AI8 | OCR never saves without confirmation, flags unread fields, keeps the original, respects permissions | 5.9 | §3.6 |
| AI9 | Assistant: scoped to user permissions, declines (no zero/approximation), cites sources, says when unsure, EN/FR, no actions, logged | 5.9 | §3.7 |
| AI10 | Briefings: scoped per recipient, traceable, retained, stable when nothing notable | 5.9 | §3.8 |
| AI11 | Cost data visible only to cost-authorised users, including inside AI outputs | rule 3 | §1.2-3 |

## 8. Errors added

`AI_DISABLED` 409 (LLM features off) · `AI_UNAVAILABLE` 503 · `AI_BUDGET_EXCEEDED` 429 · `AI_QUOTA_EXCEEDED` 429 (per-user daily) · `RECIPE_HAS_NO_TOLERANCES` 409 · `SUGGESTION_EXPIRED` 409 · `SUGGESTION_NOT_PENDING` 409 · `DOCUMENT_TOO_LARGE` 400 · `DOCUMENT_TOO_LONG` 400 · `UNSUPPORTED_DOCUMENT_TYPE` 400 · `OCR_JOB_NOT_READY` 409 · `LINKED_RECORD_INVALID` 400 · `ANOMALY_ALREADY_RESOLVED` 409 · `CONVERSATION_CLOSED` 409. (`APPROVER_MUST_DIFFER`, `LOCATION_ACCESS_DENIED` reused.)

## 9. Event catalog

| Event | Notifies |
|---|---|
| `ai.reorder.accepted` / `.adjusted` / `.dismissed` | — |
| `ai.recipe-suggestion.submitted` | in-app `factory.costing.approve` holders |
| `ai.recipe-suggestion.approved` / `.rejected` / `.applied` | in-app the requester |
| `ai.anomaly.detected` | in-app `ai.anomalies.read` holders (HIGH immediately; MEDIUM/LOW batched daily) — never the subject |
| `ai.anomaly.confirmed` | consumed by Audit & Compliance → audit alert |
| `ai.anomaly.dismissed` | — |
| `ai.ocr.completed` / `.failed` | in-app the uploader |
| `ai.briefing.delivered` | the recipient (in-app/e-mail) |
| `ai.assistant.audit-viewed` | — |

Consumes: `factory.production-run.committed` (apply suggestion), `account.wallet.withdrawn` (live withdrawal detectors).

## 10. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search`, `/filters` metadata, facets, saved views, and identical-filter exports. Forecast lists filter the stored rows of the latest run. They are location-scoped and cost-masked per caller (§3 preamble), and confidence/insufficient-data are first-class filters, so "show me only forecasts I can trust" is one click.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /reorder` · `ai.reorder` | item code CODE, name TRIGRAM | `locationId` multi, `itemType`, `categoryId`, `status` multi, `dueWithinDays`, `spikeOnly`, `trend` multi, `confidence` range / `confidenceBand` (HIGH/MEDIUM/LOW), `insufficientData`, `noOrderNeeded`, `suggested` range | `trend`, `confidenceBand`, `status` | `daysToThreshold asc nulls last`; `suggested`, `confidence` | `{ dueThisWeek, spikes, insufficient, suggestedTotalUnits }` |
| `GET /recipe-optimizations` [paged] · `ai.recipe-optimizations` | `ref` REFERENCE, recipe code/name | `recipeId`, `factoryId`, `status` multi, `specCheck` multi, `savingPct` range [cost], `requestedBy`, `createdAt` range, `applied` | `status`, `specCheck` | `createdAt desc`; `saving`[cost] | `{ proposed, approved, applied, totalSaving[cost] }` |
| `GET /demand` · `ai.demand` | product code/name | `scope`, `locationId` multi, `categoryId` multi, `atRiskOnly`, `trend`, `seasonality` (has label), `promotionActive`, `confidenceBand`, `insufficientData`, `daysOfCover` range | `trend`, `atRisk`, `confidenceBand` | `daysOfCover asc`; `forecast desc` | `{ products, atRisk, insufficient }` |
| `GET /anomalies` [paged] · `ai.anomalies` | `ref` REFERENCE, `detail` FULLTEXT, evidence refs REFERENCE (`TXN-4402` finds anomalies citing it), subject name TRIGRAM | `status` (tabs) multi, `severity` multi, `detector` multi, `locationId`, `subjectUserId`, `subjectType`, `signalStrength` range, `detectedAt` range/`period`, `reviewedBy` | `status`, `severity`, `detector` | `detectedAt desc`; `severity`, `signalStrength` | `{ awaiting, investigating, confirmed, dismissed }` |
| `GET /ocr/jobs` [paged] · `ai.ocr-jobs` | `ref` REFERENCE, file name TRIGRAM, extracted supplier/invoice no (from `fields`) TRIGRAM/CODE, linked record ref REFERENCE | `docType`, `status` multi, `createdBy` (own unless `ai.ocr.manage`), `locationId`, `hasFlaggedFields`, `linkedRecordType`, `createdAt` range | `status`, `docType` | `createdAt desc` | `{ awaitingReview, confirmed, discarded, failed }` |
| `GET /assistant/conversations` (own) / `/assistant/audit` [paged] · `ai.conversations` | message text FULLTEXT (accent-insensitive, EN/FR), title TRIGRAM, cited sources CODE | `language`, `userId` (audit only), `outcome` multi (has a message with that outcome, e.g. `UNVERIFIED`/`DECLINED_PERMISSION`), `toolUsed`, `from`/`to` | `outcome`, `language` | `lastMessageAt desc` | `{ conversations, messages, declined, unverified }` |
| `GET /briefings` · `ai.briefings` | `ref` REFERENCE, headlines/details FULLTEXT | `cadence`, `status`, `period` range, `kind` (has an item of kind), `tone` (has a negative item), `hasFlags` | `cadence`, `kind` | `generatedAt desc` | — |
| `GET /usage` | — | `month`, `feature`, `userId`, `outcome` | `feature`, `outcome` | — | token totals |

**Global search providers**: `anomaly` (perm `ai.anomalies.read`; never returns anomalies about the caller), `ocr-job`, `briefing`, `recipe-suggestion` (`OPT-n`, cost-gated). Assistant conversations are not in global search (private chat), except to `ai.assistant.audit` holders.

**Indexes (V20)**: `arf_open_idx` (exists) + `(tenant_id, location_id, run_date, confidence)`; `ai_demand_forecasts (tenant_id, location_id, run_date, stockout_risk)`; `ai_anomalies_status_idx` (exists) + GIN `evidence jsonb_path_ops`, full-text generated column on `detail`, `(tenant_id, subject_user_id)`; `ai_ocr_jobs (tenant_id, created_by_user_id, created_at desc)`, `(tenant_id, status)`, GIN on `fields`; `ai_messages` full-text generated column on `text` (`to_tsvector('simple', f_unaccent(text))`), `(tenant_id, conversation_id, seq)`; `ai_briefings (tenant_id, recipient_user_id, generated_at desc)` + full-text over item text.

## 11. Worked example

1. Nightly: Cocoa Powder at Factory B: 90 days of history, EWMA 6.2 kg/day, rising 18 %, available 172 kg, level 60 kg → crosses in 16 days. Lead time 10 d, cover 14 d, safety 22 kg → suggested 145 kg, confidence 81. The Stock Keeper adjusts to 150 → refill draft created, then posted later through the normal refill.
2. QC asks the optimizer for Citrus Base Syrup 480 L. Tolerances allow refined sugar ↔ liquid glucose within SWEETENER 42–45 kg-eq → mix 2.4 % cheaper, `NEAR_TOLERANCE` (sugar at its minimum + 1 %). Management approves `OPT-12`. QC runs the costing with `OPT-12`, batch `PB-221` committed → the suggestion is `APPLIED`.
3. A cashier gives 14 discounts in a day (median 2; z 7.1), 11 exact ₦1,000 → `AN-231` HIGH. Management investigates, then confirms → doc 10 audit alert → violation record opened by a person.
4. A Stock Keeper photographs a delivery note → `OCR-77`: supplier matched (0.82), quantity CLEAR 40 bags, expiry UNCERTAIN (60, flagged). They correct the expiry in the pre-filled Material Receive form and submit → `DL-310` → linked, `CONFIRMED`; the photo is kept.
5. A cashier asks (FR) « Quel est le coût de revient de notre meilleure vente ? » → `sales_summary` finds the top product; `stock_position` returns `costHidden: true` → DECLINED_PERMISSION: « Je ne peux pas afficher le coût de revient avec votre profil. » No figure is shown.
6. Monday 07:00 Management briefing `BR-188`: Ikeja sales −22 % w/w (source Sales Report), Kano stockout risk for Malt 33cl within 6 days (Demand forecast), forecast week 3 outflow > inflow (Cash forecast), 1 HIGH anomaly. Each item links to its report.

## 12. Open items

1. **Data protection**: enabling `ai.llm.enabled` sends tenant data (including customer names/balances in Assistant answers and document images for OCR) to Anthropic's API. The tenant's administrator should accept this explicitly (record who and when in `module_settings`), and the privacy notice should say so (NDPA 2023). Confirm the data-retention arrangement with Anthropic for the account used.
2. **Refusal-fallback Java binding**: the server-side `fallbacks` parameter is a beta request field. Its exact Java SDK builder method was not documented in the material reviewed and must be checked in the SDK repo when implementing `ClaudeGateway`.
3. **Recipe tolerances must be entered** before the optimizer can work. No recipe has them today (doc 02 didn't model specs).
4. **Lead times** default to 7 days until `stock_items.lead_time_days` is filled per item. Forecast quality depends on it.
5. **Purchase orders** don't exist in the system. An accepted re-order suggestion becomes a refill draft; a proper procurement module would be the right target later.

## 13. Verification

- Statistical engines: deterministic unit tests on fixed series (known EWMA/Holt outputs; an LP with a known optimum; robust z on a planted outlier). Insufficient-history cases return no forecast.
- No AI endpoint changes `stock_balances`, any ledger, wallet or cash/bank table (an integration test diffs those tables around every AI endpoint call).
- Assistant: a Cashier asking for landing cost gets DECLINED_PERMISSION with no number in the text; a stubbed model inventing a figure is caught by the grounding check (UNVERIFIED); every answered message stores sources; FR question → FR answer.
- OCR on a blurred fixture flags the unreadable fields (`notRead`) and never fills them; linking a record created by a different user → 400.
- The subject of an anomaly can't see it; dismissing one then repeating the behaviour creates a new anomaly.
- A briefing for a single-location manager contains only that location's figures and no cost values without `factory.costing.read`.
- With `ai.llm.enabled=false`, forecasts/optimizer/anomalies work; Assistant/OCR return `AI_DISABLED`; briefings use templates.
- Budget exhaustion blocks further LLM calls with 429 and logs `BUDGET_BLOCKED`.
- Newman folder "23 - AI" (LLM calls stubbed via a `ClaudeGateway` test double in the CI profile).
