# 15 — Workflow & Automation Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.workflow`
Depends on: `platform`, `common` only. It reacts to `domain_events` (doc 03 §7) and makes no `spi` calls (doc 03 §2.3).
Depended on by: nobody (leaf).
Migration: `V19__workflow_module.sql`
Replaces: nothing (new). The old `process` mock's content was re-homed (doc 03 §3.1). This is the sketch's "Workflow & Automation" engine, which had no backend.

---

## 1. Scope

### 1.1 Features (sketch: Workflow & Automation; Settings › Workflow Defaults)

| Feature | Sketch node |
|---|---|
| **Rule register**: rules = trigger event + conditions + ordered actions; activate/pause; versioned | Workflow Rules › Rule Register, New Rule |
| **Execution log**: every rule evaluation and action outcome | Workflow Rules › Execution Log |
| **Actions**: outbound webhook, notification (in-app / e-mail), internal task | Workflow Actions (Webhooks, Notifications, Internal Tasks) |
| **Outbound webhook endpoints**: register, signing secret, test, delivery log, retries | Workflow Actions › Webhooks |
| **Inbound webhooks**: endpoint register, verification, delivery log; inbound calls become events that rules can react to | Inbound Webhooks (Endpoint Register, New Endpoint, Delivery Log) |
| **Event catalog**: every event type with its payload fields | Event Catalog |
| **Internal tasks**: assigned work items with due dates ("My tasks") | Workflow Actions › Internal Tasks |
| **Workflow defaults**: retry policy, timeouts, secret vault | Settings › Workflow Defaults (Retry Policy, Timeouts, Vault) |

### 1.2 Decisions

1. **Automations inform and route work; they never change business records.** The available actions are notify, call a webhook, and create a task. There is no "approve", "post", "adjust" or "pay" action. A rule can't push money or stock past the controls the other modules enforce (four-eyes approval, OTP, Accounts confirmation). This is the same principle as PRD §6 rule 13 ("never automatically execute a financial or stock-affecting action"). Inbound webhooks likewise only create events and tasks; for example, a bank's "transfer received" call becomes a task for Accounts to confirm the matching receipt.
2. **Triggered by the event log.** Rules react to `domain_events` through the shared consumer mechanism (doc 03 §7.6, consumer `workflow.dispatcher`). Every module already emits events for every state change, so there is no extra coupling, and a rule can never slow down or roll back the business transaction that caused it. Latency is seconds.
3. **Safe expression handling.** Conditions are a fixed JSON operator tree evaluated by our own small evaluator, never SpEL, scripting or a general expression language. Message templates use **JMustache** (`com.samskivert:jmustache`), a logic-less template engine, so tenant-authored templates cannot execute code (no server-side template injection). Thymeleaf is deliberately *not* used for tenant-authored text.
4. **Outbound calls are SSRF-guarded.** Webhook URLs must be `https`. The host is resolved at **every** delivery, and the call is refused if any resolved address is loopback, private (RFC 1918), link-local (including `169.254.169.254`, the EC2 instance-metadata address), multicast, or carrier-grade NAT (`100.64.0.0/10`). Redirects are not followed. In the `dev` profile, `http://localhost` is allowed for testing.
5. **Payloads never contain cost data or secrets**, because domain-event payloads are already restricted (doc 03 §7.3). Confidential event families (`history.violation.*`, `security.*`) are excluded from the catalog and cannot be used as triggers.
6. **Secrets are encrypted at rest.** Webhook signing secrets and inbound verification secrets are encrypted with AES-256-GCM (JDK `javax.crypto`) under a key from the environment (`WORKFLOW_SECRETS_KEY`, 32 bytes base64; required at startup when any secret exists). They are shown once at creation and can only be rotated, never read back. This is the sketch's "Vault".

---

## 2. Schema — migration `V19__workflow_module.sql`

All tables: `tenant_id` + standard RLS (omitted) unless noted.

### 2.1 Rules

```sql
create table workflow_rules (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- WR-n
    name varchar(120) not null,
    description varchar(500),
    trigger_event_type varchar(80) not null,                 -- from the catalog (§3.1); 'workflow.inbound.<key>' for inbound hooks
    conditions jsonb not null default '{"all":[]}'::jsonb,   -- §4.1 grammar; empty = always
    location_filter uuid[],                                  -- only events at these locations (null = any)
    throttle_seconds int not null default 0 check (throttle_seconds between 0 and 86400),   -- min gap between firings per throttle key
    throttle_key varchar(60),                                -- payload path for per-key throttling (e.g. 'aggregateRef'); null = per rule
    active_from time, active_to time,                        -- optional daily window (tenant tz); outside it the rule is skipped
    status varchar(10) not null check (status in ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
    version int not null default 1,
    created_by_user_id uuid not null references app_users(id),
    updated_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create index workflow_rules_trigger_idx on workflow_rules (tenant_id, trigger_event_type) where status = 'ACTIVE';
create index workflow_rules_name_trgm on workflow_rules using gin (lower(name) gin_trgm_ops);

create table workflow_rule_actions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    rule_id uuid not null references workflow_rules(id),
    position int not null,
    action_type varchar(12) not null check (action_type in ('WEBHOOK','NOTIFY','TASK')),
    config jsonb not null,                                   -- §4.2 per type
    continue_on_failure boolean not null default true,
    unique (rule_id, position)
);

create table workflow_rule_versions (                         -- immutable snapshot on every save of an ACTIVE/PAUSED rule
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    rule_id uuid not null references workflow_rules(id),
    version int not null,
    snapshot jsonb not null,                                 -- rule + actions as they were
    saved_by_user_id uuid not null references app_users(id),
    saved_at timestamptz not null default now(),
    unique (rule_id, version)
);
```

### 2.2 Executions

```sql
create table workflow_executions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    rule_id uuid not null references workflow_rules(id),
    rule_version int not null,
    event_id uuid not null,                                  -- domain_events.id
    event_type varchar(80) not null,
    aggregate_ref varchar(60),
    outcome varchar(14) not null check (outcome in ('MATCHED','NOT_MATCHED','THROTTLED','OUT_OF_WINDOW','ERROR')),
    status varchar(12) not null check (status in ('PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED','SKIPPED')),
    error_message varchar(500),
    evaluated_at timestamptz not null default now(),
    finished_at timestamptz,
    unique (rule_id, event_id)                               -- idempotent: an event fires a rule once
);
create index workflow_executions_rule_idx on workflow_executions (tenant_id, rule_id, evaluated_at desc);

create table workflow_action_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    execution_id uuid not null references workflow_executions(id),
    action_id uuid not null references workflow_rule_actions(id),
    action_type varchar(12) not null,
    status varchar(12) not null check (status in ('PENDING','SUCCEEDED','FAILED','RETRYING','SKIPPED')),
    attempts int not null default 0,
    next_attempt_at timestamptz,
    result jsonb,                                            -- task id / notification ids / webhook delivery id
    error_message varchar(500),
    updated_at timestamptz not null default now()
);
create index war_retry_idx on workflow_action_runs (next_attempt_at) where status = 'RETRYING';
```
`NOT_MATCHED` executions are recorded only when the rule has `log_non_matches` turned on for debugging (a column on `workflow_rules`, default false, auto-off after 24 h). Otherwise only matches are stored, which keeps the log useful rather than flooded.

### 2.3 Outbound webhook endpoints and deliveries

```sql
create table webhook_endpoints (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- WO-n
    name varchar(120) not null,
    url varchar(2000) not null,
    secret_ciphertext bytea not null,                        -- AES-GCM (§1.2-6)
    secret_last4 varchar(4) not null,
    custom_headers jsonb not null default '{}'::jsonb,       -- non-secret headers; header names validated, no Host/Authorization override
    auth_header_ciphertext bytea,                            -- optional 'Authorization' value, encrypted
    timeout_seconds int,                                     -- null = workflow.webhook.timeoutSeconds
    max_attempts int,                                        -- null = workflow.webhook.maxAttempts
    status varchar(10) not null check (status in ('ACTIVE','DISABLED')),
    disabled_reason varchar(200),                            -- 'Auto-disabled after 50 consecutive failures'
    consecutive_failures int not null default 0,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);

create table webhook_deliveries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    endpoint_id uuid not null references webhook_endpoints(id),
    action_run_id uuid references workflow_action_runs(id),  -- null for test sends
    event_id uuid,
    attempt int not null,
    request_body text not null,                              -- ≤ 64 KB (payload limit)
    response_status int,
    response_body_excerpt varchar(4000),
    duration_ms int,
    outcome varchar(14) not null check (outcome in ('SUCCESS','HTTP_ERROR','TIMEOUT','NETWORK_ERROR','BLOCKED_ADDRESS')),
    sent_at timestamptz not null default now()
);
create index webhook_deliveries_endpoint_idx on webhook_deliveries (tenant_id, endpoint_id, sent_at desc);
```

### 2.4 Inbound webhook endpoints and deliveries

```sql
create table inbound_webhook_endpoints (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- WH-n
    key varchar(40) not null,                                -- slug used in the event type: workflow.inbound.<key>
    name varchar(120) not null,
    token_hash varchar(64) not null,                         -- SHA-256 of the URL token (token shown once)
    verification varchar(12) not null check (verification in ('TOKEN_ONLY','HMAC_SHA256')),
    secret_ciphertext bytea,                                 -- HMAC secret (required for HMAC_SHA256)
    signature_header varchar(60),                            -- e.g. 'X-Signature' (HMAC)
    ip_allowlist cidr[],                                     -- optional
    payload_mapping jsonb not null default '{}'::jsonb,      -- §4.4: which JSON paths become event fields
    status varchar(10) not null check (status in ('ACTIVE','DISABLED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    unique (tenant_id, key)
);
create unique index inbound_token_uq on inbound_webhook_endpoints (token_hash);   -- global: the token identifies the tenant

create table inbound_webhook_deliveries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    endpoint_id uuid not null references inbound_webhook_endpoints(id),
    received_at timestamptz not null default now(),
    source_ip inet,
    idempotency_key varchar(120),
    body_excerpt varchar(8000),
    headers jsonb not null,                                  -- with Authorization/secret headers redacted
    outcome varchar(16) not null check (outcome in ('ACCEPTED','DUPLICATE','BAD_SIGNATURE','IP_REJECTED','TOO_LARGE','DISABLED','BAD_PAYLOAD')),
    event_id uuid                                             -- the domain_events row created when ACCEPTED
);
create unique index inbound_idem_uq on inbound_webhook_deliveries (endpoint_id, idempotency_key) where idempotency_key is not null and outcome = 'ACCEPTED';
```

### 2.5 Tasks

```sql
create table workflow_tasks (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                                -- TSK-n
    title varchar(200) not null,
    description varchar(2000),
    assignee_user_id uuid references app_users(id),          -- direct assignee, or
    assignee_permission varchar(80),                          -- anyone holding this permission (at location_id, if set) may claim it
    location_id uuid references locations(id),
    priority varchar(8) not null default 'NORMAL' check (priority in ('LOW','NORMAL','HIGH','URGENT')),
    due_at timestamptz,
    status varchar(12) not null check (status in ('OPEN','IN_PROGRESS','DONE','CANCELLED')),
    claimed_by_user_id uuid references app_users(id),
    linked_aggregate_type varchar(60),
    linked_aggregate_ref varchar(60),
    linked_route varchar(300),                                -- frontend route to the record
    source varchar(10) not null check (source in ('RULE','MANUAL')),
    rule_execution_id uuid references workflow_executions(id),
    created_by_user_id uuid references app_users(id),        -- null for rule-created
    completed_by_user_id uuid references app_users(id),
    completed_at timestamptz,
    resolution_note varchar(1000),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint wt_assignee check (assignee_user_id is not null or assignee_permission is not null)
);
create index workflow_tasks_open_idx on workflow_tasks (tenant_id, status, due_at) where status in ('OPEN','IN_PROGRESS');
create index workflow_tasks_assignee_idx on workflow_tasks (tenant_id, assignee_user_id, status);

create table workflow_task_comments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    task_id uuid not null references workflow_tasks(id),
    body varchar(2000) not null,
    by_user_id uuid not null references app_users(id),
    at timestamptz not null default now()
);
```

### 2.6 Module settings ("Workflow Defaults")

| Key | Type | Default |
|---|---|---|
| `workflow.webhook.timeoutSeconds` | int (1–30) | 10 |
| `workflow.webhook.maxAttempts` | int (1–10) | 5 |
| `workflow.webhook.backoffSeconds` | int[] | [60, 300, 1800, 7200, 21600] |
| `workflow.webhook.autoDisableAfterFailures` | int | 50 |
| `workflow.rules.maxActive` | int | 100 |
| `workflow.actions.maxPerRule` | int | 10 |
| `workflow.inbound.maxBodyBytes` | int | 262144 (256 KB) |
| `workflow.log.retentionDays` | int | 90 (executions, action runs, deliveries; hard-deleted by a nightly job — operational logs, not business records) |

New numbering prefixes (doc 03 §6): `WO` (outbound webhook endpoint), `TSK` (task). `WR` and `WH` are already registered.

---

## 3. Event catalog

### 3.1 Registry

`platform.events.EventCatalog` collects `EventTypeDescriptor(type, module, aggregateType, description, payloadFields: List<Field(name, type: STRING|NUMBER|BOOLEAN|DATE|UUID|ENUM, enumValues?, description)>, routeTemplate?)` beans that each module declares next to its event-writing code. The event catalog tables at the end of every module doc (§8 in docs 04–14) are the source list. A unit test fails when code emits an event type that has no descriptor, which keeps the catalog complete.

Common fields every event exposes to conditions and templates: `eventType`, `occurredAt`, `aggregateType`, `aggregateId`, `aggregateRef`, `locationId`, `locationName`, `actorName`, `summary`, plus `data.*` = the payload fields.

Excluded from the catalog (§1.2-5): `history.violation.*`, `security.*`.

### 3.2 Endpoint

`GET /api/v1/workflow/events` — perm `workflow.rules.read`. Query `module`, `q`. Returns descriptors grouped by module. `GET /api/v1/workflow/events/{type}/sample` returns the most recent real event of that type in the tenant (payload only, for building conditions), or a synthetic example if none exists.

---

## 4. Rule grammar and actions

### 4.1 Conditions

```json
{ "all": [
    { "field": "data.amount", "op": "gte", "value": 500000 },
    { "any": [ { "field": "locationName", "op": "eq", "value": "Showroom Ikeja" },
               { "field": "data.channel", "op": "in", "value": ["FUND_TRANSFER","CHEQUE"] } ] } ] }
```
- Groups: `all` (AND), `any` (OR), `not` (single child). Max depth 4, max 30 leaves → `400 CONDITION_TOO_COMPLEX`.
- Operators: `eq`, `ne`, `gt`, `gte`, `lt`, `lte` (numbers and ISO dates), `in`, `not_in`, `contains` (string, case-insensitive), `starts_with`, `exists`, `not_exists`, `changed_to` (for status-change events: `data.toStatus`).
- Field paths must exist in the trigger's descriptor (or be common fields) and operators must suit the field type → `400 INVALID_CONDITION` (details: path, reason). They are validated at save, not at run.
- Evaluation is pure, needs no I/O, and has a 50 ms time budget per rule.

### 4.2 Actions (`config` per type)

**`NOTIFY`**
```json
{ "channels": ["IN_APP","EMAIL"],
  "recipients": { "users": ["…"], "permission": "accounting.receipts.confirm", "atEventLocation": true, "eventActor": false },
  "title": "Large transfer {{aggregateRef}}",
  "body": "{{data.customerName}} paid ₦{{data.amount}} by {{data.channel}} at {{locationName}}." }
```
Recipients are resolved at run time through `Recipients.withPermission` (doc 03 §9.2) and limited to active members. Templates are rendered with JMustache, HTML-escaped for e-mail, ≤ 200/2000 chars. In-app messages go to `user_notifications`; e-mail goes to `notification_outbox` (e-mail provider caveat: doc 08 §11). A notification only ever goes to users who hold the permission or are named, and the rule author cannot add arbitrary e-mail addresses (no data exfiltration by e-mail).

**`WEBHOOK`**
```json
{ "endpointId": "…", "includeFields": ["aggregateRef","data.amount","data.channel"] }
```
`includeFields` null sends all catalog fields. The body:
```json
{ "id": "<delivery uuid>", "type": "account.receipt.recorded", "occurredAt": "…", "tenant": "<tenant slug>",
  "rule": { "ref": "WR-4", "name": "Notify ERP of transfers" },
  "aggregate": { "type": "payment_receipt", "id": "…", "ref": "RCT-602" },
  "location": { "id": "…", "name": "Showroom Ikeja" },
  "data": { … } }
```
Headers: `Content-Type: application/json`, `User-Agent: ZifIQ-Webhooks/1`, `X-ZifIQ-Event`, `X-ZifIQ-Delivery` (stable across retries, so the receiver can deduplicate), `X-ZifIQ-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`, plus custom headers. Any 2xx is success. 4xx except 408 and 429 is a permanent failure (no retry). 408, 429, 5xx, timeouts and network errors retry on the backoff schedule up to `maxAttempts`. After `autoDisableAfterFailures` consecutive failures the endpoint is disabled and Administrators are notified. The HTTP client is Spring's `RestClient` over the JDK `HttpClient` with connect/read timeouts and no redirect following.

**`TASK`**
```json
{ "title": "Confirm transfer {{aggregateRef}} — ₦{{data.amount}}",
  "description": "{{summary}}", "assignee": { "permission": "accounting.receipts.confirm", "atEventLocation": true },
  "priority": "HIGH", "dueInHours": 4, "linkToEvent": true }
```
`linkToEvent` sets `linked_aggregate_*` and `linked_route` from the descriptor's `routeTemplate`.

### 4.3 Dispatch

1. The consumer `workflow.dispatcher` (doc 03 §7.6) receives events whose type has at least one `ACTIVE` rule in the tenant (an in-memory index of active trigger types per tenant, refreshed on rule change).
2. For each matching rule: apply the location filter, the daily window and the throttle, then evaluate conditions. Insert a `workflow_executions` row; the unique `(rule_id, event_id)` key makes redelivery a no-op.
3. On a match: create the `workflow_action_runs` rows and run them in `position` order. `NOTIFY` and `TASK` run inline in the consumer's transaction. `WEBHOOK` is marked `PENDING` and sent by the delivery worker (`FOR UPDATE SKIP LOCKED`, after commit), so a slow receiver never blocks event processing. An action failure with `continue_on_failure = false` skips the remaining actions → execution `PARTIAL`.
4. Rule loops are impossible: workflow's own events (`workflow.*`) can be triggers only for `workflow.inbound.*` and `workflow.task.overdue`, and actions don't emit events that create further executions of the same rule.

### 4.4 Inbound webhook processing — `POST /api/v1/hooks/in/{token}`

Unauthenticated (no JWT), permitted in the security config. Rate-limited: 60 requests per minute per endpoint (Bucket4j `com.bucket4j:bucket4j-core`, in-memory), → `429`.
1. Look up the endpoint by `sha256(token)`. Unknown → `404`, with nothing logged against any tenant. `DISABLED` → `410`.
2. Body > `workflow.inbound.maxBodyBytes` → `413` (`TOO_LARGE`). Source IP not in the allowlist → `403` (`IP_REJECTED`).
3. `HMAC_SHA256`: verify `signature_header` = hex/base64 HMAC of the raw body with a constant-time comparison → `401` (`BAD_SIGNATURE`).
4. The body must be JSON → else `400` (`BAD_PAYLOAD`). `Idempotency-Key` header (or the mapped `idempotencyPath`) already accepted → `200 { duplicate: true }` (`DUPLICATE`).
5. Map the payload: `payload_mapping` = `{ "fields": { "amount": "$.data.amount", "reference": "$.data.ref", "payer": "$.data.sender.name" }, "aggregateRef": "$.data.ref", "idempotencyPath": "$.id" }`, using JsonPath (`com.jayway.jsonpath:json-path`, stable). Only mapped fields enter the event (≤ 30 fields, strings ≤ 500 chars), so arbitrary third-party data doesn't flow into the event log.
6. In the endpoint's tenant transaction, `DomainEvents.record(type = "workflow.inbound.<key>", aggregateType = "inbound_webhook", aggregateRef = mapped ref, summary = "<name>: <ref>", payload = mapped fields)`, then log the delivery `ACCEPTED`. Response `202 { received: true }`.

Rules can then trigger on `workflow.inbound.<key>`. Its descriptor is generated from the endpoint's mapping (all fields typed `STRING` unless a type is given in the mapping: `{ "path": "$.data.amount", "type": "NUMBER" }`).

---

## 5. Endpoints (under `/api/v1/workflow`)

### 5.1 Rules
- `GET /rules` [paged] — perm `workflow.rules.read`. Query `status`, `triggerEventType`, `q` (ref CODE, name TRIGRAM). Row `{ id, ref, name, trigger, actions: ["Webhook → WO-2","Notify"], status, lastFiredAt, firings7d, failures7d, updatedBy, updatedAt }`.
- `GET /rules/{id}` — rule + actions + `versions` list.
- `POST /rules` — perm `workflow.rules.manage`. `{ name, description?, triggerEventType, conditions, locationFilter?, throttleSeconds?, throttleKey?, activeFrom?, activeTo?, actions: [ { actionType, config, continueOnFailure? } ] }` → `DRAFT`, `ref = next("WR")`. Validation: trigger exists in the catalog (`400 UNKNOWN_EVENT_TYPE`); conditions (§4.1); action configs (endpoint ACTIVE and in tenant; permission codes exist; templates parse, `400 INVALID_TEMPLATE` with line/col); ≤ `workflow.actions.maxPerRule`.
- `PUT /rules/{id}` — full replace. Saving an `ACTIVE`/`PAUSED` rule bumps `version` and writes `workflow_rule_versions`; executions reference the version that ran.
- `POST /rules/{id}/activate` (≤ `workflow.rules.maxActive` active → `409 RULE_LIMIT_REACHED`) · `/pause` · `/archive` (no hard delete).
- `POST /rules/{id}/test` — `{ eventId? | samplePayload? }`. Evaluates the conditions and **renders** every action (notification text, webhook body, task title) **without executing** them. Response `{ matched, conditionTrace: [ { path, op, expected, actual, result } ], renderedActions }`.
- `POST /rules/{id}/debug-log` `{ enabled }` — toggles `log_non_matches` for 24 h.

### 5.2 Execution log
- `GET /executions` [paged] — perm `workflow.logs.read`. Query `ruleId`, `outcome`, `status`, `eventType`, `aggregateRef`, `from`, `to`. Row `{ id, rule (ref + name), event (type + ref), outcome, status, actions: [ { type, status, attempts, error } ], evaluatedAt, durationMs }`.
- `GET /executions/{id}` — details with action runs and webhook deliveries.
- `POST /executions/{id}/actions/{actionRunId}/retry` — perm `workflow.rules.manage`. Re-runs a `FAILED` webhook action now (same delivery id, so the receiver can deduplicate).

### 5.3 Outbound endpoints
- `GET /webhooks` · `GET /webhooks/{id}` (secret shown as `••••<last4>`) — perm `workflow.webhooks.manage`.
- `POST /webhooks` `{ name, url, customHeaders?, authorizationHeader?, timeoutSeconds?, maxAttempts? }`. URL validation: https, ≤ 2000 chars, host resolves to a public address → `400 WEBHOOK_URL_NOT_ALLOWED`. It generates a 32-byte secret and returns it **once**: `{ endpoint, signingSecret: "whsec_…" }`. `ref = next("WO")`.
- `PUT /webhooks/{id}` (no secret change) · `POST /webhooks/{id}/rotate-secret` (returns the new secret once; the old one stays valid for 24 h, and both signatures are sent as `v1=` entries during the overlap) · `POST /webhooks/{id}/disable` / `enable` (enable resets `consecutive_failures`).
- `POST /webhooks/{id}/test` — sends `{ "type": "workflow.test", … }` synchronously and returns the delivery (status, duration, response excerpt).
- `GET /webhooks/{id}/deliveries` [paged] — query `outcome`, `from`, `to`.

### 5.4 Inbound endpoints
- `GET /inbound` · `GET /inbound/{id}` — perm `workflow.webhooks.manage`.
- `POST /inbound` `{ name, key (slug, unique), verification, signatureHeader?, ipAllowlist?, payloadMapping }` → `{ endpoint, url: "https://<host>/api/v1/hooks/in/<token>", secret? }` — the token and secret are shown once. `ref = next("WH")`. The event type `workflow.inbound.<key>` is registered in the catalog.
- `PUT /inbound/{id}` (mapping/allowlist/name; `key` is immutable because rules reference it) · `POST /inbound/{id}/rotate-token` · `/rotate-secret` · `/disable` / `enable`.
- `GET /inbound/{id}/deliveries` [paged].

### 5.5 Tasks
- `GET /tasks` [paged] — authenticated. `scope` = `MINE` (default: assigned to me, claimed by me, or open to a permission I hold at my locations) | `ALL` (perm `workflow.tasks.manage`). Query `status` (default OPEN, IN_PROGRESS), `priority`, `overdue`, `q` (ref CODE, title TRIGRAM). Row `{ id, ref, title, priority, status, dueAt, overdue, assignee (user name or "Anyone with <permission label>"), claimedBy, linkedRef, linkedRoute, source, createdAt }`.
- `POST /tasks` — perm `workflow.tasks.manage`. Manual task with the same fields as the TASK action. `ref = next("TSK")`.
- `POST /tasks/{id}/claim` (permission-assigned tasks; must hold the permission at the location → `403`) · `POST /tasks/{id}/start` · `POST /tasks/{id}/complete { resolutionNote? }` · `POST /tasks/{id}/cancel { reason }` (creator or `workflow.tasks.manage`) · `POST /tasks/{id}/comments { body }` · `GET /tasks/{id}`.
- A sweep every 15 minutes emits `workflow.task.overdue` once per task past `due_at` (rules may trigger on it, e.g. to escalate) and notifies the assignee/claimer.

Every task creation and assignment also drops an in-app notification (doc 03 §9.1), so tasks are visible from the header bell. The Dashboard shows "My open tasks" from its counter projection (event `workflow.task.*` carries `{ openCountForAssignee, assigneeUserId }`; doc 14 §3.5 counter key `workflow.open-tasks:<userId>`).

---

## 6. Permission codes

| Code | Allows | Default roles |
|---|---|---|
| `workflow.rules.read` | View rules, catalog | Administrator, Management |
| `workflow.rules.manage` | Create/edit/activate rules; retry actions | Administrator |
| `workflow.webhooks.manage` | Outbound and inbound endpoints, secrets, deliveries | Administrator |
| `workflow.logs.read` | Execution log | Administrator, Management |
| `workflow.tasks.manage` | Create manual tasks, see/cancel all tasks | Administrator, Management |

Webhooks send tenant data outside the system, so they are Administrator-only by default. Settings › Workflow Defaults use `settings.module-settings.manage` (doc 03 §16).

## 7. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| W1 | Automations never change financial or stock records | PRD §6 rule 13 (principle) | §1.2-1 action set |
| W2 | No cost data or secrets leave via payloads | rule 3; doc 03 §7.3 | §1.2-5 |
| W3 | Notifications only reach permitted users | rule 12 | §4.2 NOTIFY recipients |
| W4 | An event fires a rule at most once | derived | unique (rule, event) |
| W5 | Outbound calls cannot reach internal addresses | security | §1.2-4 |
| W6 | Tenant-authored text can't execute code | security | §1.2-3 |
| W7 | Inbound calls are verified, rate-limited, deduplicated and size-capped | security | §4.4 |

## 8. Errors added

`UNKNOWN_EVENT_TYPE` 400 · `INVALID_CONDITION` 400 · `CONDITION_TOO_COMPLEX` 400 · `INVALID_TEMPLATE` 400 · `INVALID_ACTION_CONFIG` 400 · `RULE_LIMIT_REACHED` 409 · `RULE_NOT_FOUND` 404 · `WEBHOOK_URL_NOT_ALLOWED` 400 · `WEBHOOK_ENDPOINT_NOT_FOUND` 404 · `WEBHOOK_ENDPOINT_DISABLED` 409 · `INBOUND_KEY_TAKEN` 409 · `TASK_NOT_FOUND` 404 · `TASK_NOT_CLAIMABLE` 409 · `TASK_ALREADY_CLOSED` 409 · `SECRETS_KEY_MISSING` (startup failure, not an API error).

## 9. Event catalog (emitted here)

| Event | Notes |
|---|---|
| `workflow.rule.created` / `.updated` / `.activated` / `.paused` / `.archived` | — |
| `workflow.webhook.disabled` | auto-disable → in-app Administrators |
| `workflow.inbound.<key>` | one per accepted inbound call (trigger-able) |
| `workflow.task.created` / `.claimed` / `.completed` / `.cancelled` | counter payload for the Dashboard |
| `workflow.task.overdue` | trigger-able (escalation) |

## 10. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search`, `/filters` metadata, facets, saved views, and identical-filter exports. Logs (executions, deliveries) use keyset paging.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /rules` [paged] · `workflow.rules` | `ref` REFERENCE, `name` TRIGRAM, `description` FULLTEXT, trigger event type CODE (prefix: `account.` finds all Account triggers) | `status` multi, `triggerEventType` multi, `triggerModule`, `actionType` multi (via actions), `webhookEndpointId`, `hasFailures7d`, `lastFiredAt` range, `updatedBy` | `status`, `triggerModule`, `actionType` | `updatedAt desc`; `name`, `firings7d`, `failures7d` | `{ rules, active, failing }` |
| `GET /executions` [paged, keyset] · `workflow.executions` | `aggregate_ref` REFERENCE, rule ref/name, error message FULLTEXT | `ruleId` multi, `outcome` multi, `status` multi, `eventType` multi, `actionType`, `from`/`to`/`period`, `durationMs.gte` | `outcome`, `status`, `rule` | `evaluatedAt desc` | `{ executions, succeeded, failed, partial }` |
| `GET /webhooks` · `workflow.webhooks` | `ref` REFERENCE, `name` TRIGRAM, URL host CODE | `status`, `consecutiveFailures.gte`, `usedByRule` | `status` | `name` | — |
| `GET /webhooks/{id}/deliveries` [paged, keyset] | delivery id CODE, event type CODE, response excerpt FULLTEXT | `outcome` multi, `responseStatus` range (`responseStatus:>=500`), `attempt.gte`, `from`/`to` | `outcome`, `responseStatus` (bucketed 2xx/4xx/5xx) | `sentAt desc` | `{ deliveries, successRate, p95DurationMs }` |
| `GET /inbound` and `/inbound/{id}/deliveries` [paged, keyset] | `ref`/`key` CODE, `name` TRIGRAM; deliveries: idempotency key CODE, body excerpt FULLTEXT, source IP CODE | `status`; deliveries: `outcome` multi, `from`/`to` | `outcome` | `receivedAt desc` | `{ received, accepted, rejected by reason }` |
| `GET /tasks` [paged] · `workflow.tasks` | `ref` REFERENCE, `title` TRIGRAM, `description` FULLTEXT, `linked_aggregate_ref` REFERENCE, comments FULLTEXT | `scope` (MINE/ALL), `status` multi, `priority` multi, `overdue`, `dueAt` range (`dueWithinHours`), `assigneeUserId`, `assigneePermission`, `claimedBy`, `locationId`, `source`, `ruleId`, `createdAt` range | `status`, `priority`, `source` | `dueAt asc nulls last`, then `priority desc`; `createdAt` | `{ open, overdue, dueToday }` |
| `GET /events` (catalogue) | type CODE (prefix), description TRIGRAM, payload field names | `module` multi, `usedByActiveRule` | `module` | `type` | — |

**Lookups**: `GET /webhooks/lookup?q=&status=ACTIVE`, `GET /events/lookup?q=` (trigger picker with payload-field preview), `GET /tasks/lookup?q=&openOnly=true`.

**Global search providers**: `workflow-rule` (title `WR-n name`, subtitle trigger · status) and `task` (title `TSK-n title`, subtitle priority · due · assignee; only tasks the caller can see).

**Indexes (V19)**: `workflow_rules_trigger_idx`, `workflow_rules_name_trgm` (exist) + full-text on description; `workflow_executions_rule_idx` (exists) + `(tenant_id, evaluated_at desc, id)` keyset and `(tenant_id, aggregate_ref)`; `webhook_deliveries_endpoint_idx` (exists) + `(tenant_id, endpoint_id, outcome, sent_at desc)`; `workflow_tasks_open_idx`, `workflow_tasks_assignee_idx` (exist) + trigram on `title`, full-text generated column over `title || description`, `(tenant_id, linked_aggregate_ref)`, `(tenant_id, assignee_permission, location_id, status)`.

## 11. Worked example

1. Admin registers ERP endpoint `WO-2` `https://erp.example.ng/hooks/zifiq` → signing secret shown once.
2. Rule `WR-4` "Large transfers to ERP + Accounts task": trigger `account.receipt.recorded`, conditions `data.channel in [FUND_TRANSFER]` and `data.amount gte 500000`. Actions: WEBHOOK `WO-2`; TASK "Confirm transfer {{aggregateRef}}" for `accounting.receipts.confirm` at the event location, due in 4 h, HIGH.
3. `POST /rules/{id}/test` with the latest real event → matched, rendered webhook body and task title shown. Activate.
4. The cashier records `RCT-602` transfer ₦600,000 → within ~15 s the execution is MATCHED, task `TSK-31` is open for Ikeja Accounts, and the webhook is delivered (ERP returns 503 → retry at +60 s → 200).
5. The bank integration posts to inbound `WH-1` (`key bank-alerts`, HMAC) → event `workflow.inbound.bank-alerts { amount, reference, payer }`. Rule `WR-5` creates a task "Match bank credit {{data.reference}}" for Accounts. Nothing is posted automatically.

## 12. Open items

1. **Scheduled (time-based) rules** ("every Monday if …") are not in this phase; use Reports schedules (doc 13 §4.7) for periodic reporting and `workflow.task.overdue` for escalation.
2. **Multi-instance rate limiting** of inbound hooks is in-memory per instance; with several instances, move Bucket4j to its Postgres/Redis backend.

## 13. Verification

- An event delivered twice to the dispatcher → one execution.
- Webhook to `http://169.254.169.254/…`, `https://10.0.0.5`, or a hostname that resolves to a private address at send time → refused (`BLOCKED_ADDRESS`), never sent.
- A template containing `{{#…}}` constructs or `${T(java.lang.Runtime)}` renders literally or fails validation; nothing executes.
- Signature verification: a receiver recomputing HMAC(secret, t.body) matches `v1`; a tampered inbound body → 401.
- A NOTIFY with a permission recipient never reaches users outside the event's location when `atEventLocation`.
- A rule with `continueOnFailure=false` on a failing webhook skips later actions → PARTIAL.
- Log retention job removes executions older than 90 days.
- Newman folder "22 - Workflow" (rule CRUD/test, endpoint test against a mock receiver, inbound accept/duplicate/bad-signature, tasks).
