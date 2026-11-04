-- Module 11 (Barcode & QR), slice 2: the scan log. A high-volume operational log (docs/11 §2.2), so
-- it is NOT a domain_events stream and is hard-deleted by a nightly retention job — the one log
-- exempt from the append-only rule, because scans are lookups, not business records. The record
-- references (stock_item_id / matched_code_id / location_id) are plain uuids, not foreign keys: the
-- log must survive the records it points at being suspended, deleted or purged, and it never writes
-- on their hot path. RLS still isolates every row by tenant.

create table scan_events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    scanned_at timestamptz not null default now(),
    code varchar(200) not null,
    input_method varchar(10) not null check (input_method in ('SCANNER','CAMERA','IMAGE','MANUAL')),
    context varchar(24) not null,
    outcome varchar(16) not null check (outcome in ('MATCHED','NOT_RECOGNISED','BLOCKED','MISREAD')),
    block_reason varchar(30),
    stock_item_id uuid,
    matched_code_id uuid,
    location_id uuid,
    user_id uuid not null references app_users(id),
    client_ref varchar(60)
);
alter table scan_events enable row level security;
alter table scan_events force row level security;
create policy scan_events_scope on scan_events
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create index scan_events_time_idx on scan_events (tenant_id, scanned_at desc);
create index scan_events_outcome_idx on scan_events (tenant_id, outcome, scanned_at desc);
create index scan_events_code_idx on scan_events (tenant_id, code);
create index scan_events_user_idx on scan_events (tenant_id, user_id, scanned_at desc);
create index scan_events_context_idx on scan_events (tenant_id, context, scanned_at desc);
