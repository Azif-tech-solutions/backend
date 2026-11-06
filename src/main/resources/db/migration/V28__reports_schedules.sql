-- Module 13 (Reports & Analytics), slice 4d: scheduled reports (docs/13 §2.3, §4.7, §6).
-- A schedule produces an export on a cadence as its owner (run_as_user_id) and delivers it to tenant
-- members who could run the report themselves. Report data still lives in the owning modules.

create table report_schedules (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(120) not null,
    report_key varchar(80) not null,
    params jsonb not null,
    format varchar(4) not null check (format in ('CSV', 'XLSX', 'PDF')),
    frequency varchar(10) not null check (frequency in ('DAILY', 'WEEKLY', 'MONTHLY')),
    day_of_week int check (day_of_week between 1 and 7),
    day_of_month int check (day_of_month between 1 and 28),
    send_at time not null default '07:00',
    recipients jsonb not null,
    run_as_user_id uuid not null references app_users(id),
    status varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE', 'PAUSED')),
    last_run_at timestamptz,
    next_run_at timestamptz not null,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);

alter table report_schedules enable row level security;
alter table report_schedules force row level security;
create policy report_schedules_scope on report_schedules
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create index report_schedules_due_idx on report_schedules (next_run_at) where status = 'ACTIVE';
create index report_schedules_tenant_idx on report_schedules (tenant_id, created_at desc);

insert into permission_catalog (code, description) values
    ('reports.schedules.manage', 'Create and manage scheduled reports');

select grant_permission_to_default_roles('reports.schedules.manage', array['Administrator', 'Management']);
