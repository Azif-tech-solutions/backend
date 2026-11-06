-- Module 13 (Reports & Analytics), slice 1: the export engine's record of generated files (docs/13
-- §2.2). Reports compute from the owning modules' rows at request time — there is no reporting
-- warehouse — so these tables only track exports, not report data. The planned V17 number was taken;
-- this is V23. Schedules (§2.3) and analytics arrive in later slices.

create table report_exports (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    report_key varchar(80) not null,
    params jsonb not null,
    format varchar(4) not null check (format in ('CSV','XLSX','PDF')),
    status varchar(10) not null check (status in ('QUEUED','RUNNING','DONE','FAILED','EXPIRED')),
    row_count int,
    file_name varchar(200),
    content_type varchar(100),
    size_bytes bigint,
    error_message varchar(500),
    requested_by_user_id uuid not null references app_users(id),
    schedule_id uuid,                                        -- set when produced by a schedule (table arrives in a later slice)
    requested_at timestamptz not null default now(),
    started_at timestamptz,
    finished_at timestamptz,
    expires_at timestamptz,
    unique (tenant_id, ref)
);
alter table report_exports enable row level security;
alter table report_exports force row level security;
create policy report_exports_scope on report_exports
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create index report_exports_user_idx on report_exports (tenant_id, requested_by_user_id, requested_at desc);
create index report_exports_queue_idx on report_exports (status, requested_at) where status = 'QUEUED';
-- Lets the file row carry a tenant-scoped composite FK back to the export.
create unique index report_exports_tenant_id_key on report_exports (tenant_id, id);

-- Separate so listing exports never loads file bytes.
create table report_export_files (
    export_id uuid primary key,
    tenant_id uuid not null references tenants(id),
    content bytea not null,
    constraint report_export_files_export_fkey foreign key (tenant_id, export_id) references report_exports (tenant_id, id)
);
alter table report_export_files enable row level security;
alter table report_export_files force row level security;
create policy report_export_files_scope on report_export_files
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
