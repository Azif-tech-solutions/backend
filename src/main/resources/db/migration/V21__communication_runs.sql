-- Module 12 (Communication), slice 2: monthly commission allocation runs (docs/12 §2.2). A run turns
-- qualifying, not-yet-allocated sale lines into per-beneficiary commission, snapshotting the rate in
-- force on each sale date. DRAFT -> APPROVED (locked, approver differs) -> payable. Payments are slice 3.

create table commission_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    beneficiary_type varchar(14) not null check (beneficiary_type in ('DEALER','CORRESPONDENT')),
    period_month date not null check (extract(day from period_month) = 1),
    location_id uuid,
    status varchar(12) not null check (status in ('DRAFT','APPROVED','CANCELLED')),
    total_commission numeric(14,2) not null default 0,
    beneficiary_count int not null default 0,
    eligible_lines int not null default 0,
    ineligible_lines int not null default 0,
    pending_confirmation_lines int not null default 0,
    calculated_at timestamptz not null,
    run_by_user_id uuid not null references app_users(id),
    approved_by_user_id uuid references app_users(id),
    approved_at timestamptz,
    cancelled_reason varchar(300),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
alter table commission_runs enable row level security;
alter table commission_runs force row level security;
create policy commission_runs_scope on commission_runs
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- One live (non-cancelled) run per beneficiary type + period + location.
create unique index commission_runs_live_uq on commission_runs
    (tenant_id, beneficiary_type, period_month, coalesce(location_id, '00000000-0000-0000-0000-000000000000'::uuid))
    where status <> 'CANCELLED';
create index commission_runs_period_idx on commission_runs (tenant_id, beneficiary_type, period_month desc);
-- Lets children carry a tenant-scoped composite FK back to a run.
create unique index commission_runs_tenant_id_key on commission_runs (tenant_id, id);

create table commission_run_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null,
    beneficiary_id uuid not null,                             -- customers.id (DEALER) or correspondents.id (CORRESPONDENT)
    beneficiary_code varchar(20) not null,
    beneficiary_name varchar(200) not null,
    region varchar(60),
    stock_item_id uuid not null,
    item_code varchar(40) not null,
    item_name varchar(200) not null,
    qty_sold numeric(14,3) not null,
    qty_returned numeric(14,3) not null default 0,
    net_qty numeric(14,3) not null,
    sales_value numeric(14,2) not null,
    rate_id uuid,
    rate_type varchar(10),
    rate numeric(14,4),
    unit_commission numeric(14,4) not null default 0,
    commission numeric(14,2) not null,
    eligible boolean not null,
    ineligible_reason varchar(30) check (ineligible_reason in ('NO_RATE','RATE_INACTIVE','CORRESPONDENT_NOT_ASSIGNED')),
    late_from_period date,
    constraint crl_run_fkey foreign key (tenant_id, run_id) references commission_runs (tenant_id, id)
);
alter table commission_run_lines enable row level security;
alter table commission_run_lines force row level security;
create policy commission_run_lines_scope on commission_run_lines
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create index crl_run_idx on commission_run_lines (tenant_id, run_id, beneficiary_id);

create table commission_run_beneficiaries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null,
    beneficiary_id uuid not null,
    beneficiary_code varchar(20) not null,
    beneficiary_name varchar(200) not null,
    region varchar(60),
    gross_commission numeric(14,2) not null,
    carried_in numeric(14,2) not null default 0,
    earned numeric(14,2) not null check (earned >= 0),
    carried_out numeric(14,2) not null default 0,
    constraint crb_run_fkey foreign key (tenant_id, run_id) references commission_runs (tenant_id, id),
    unique (run_id, beneficiary_id)
);
alter table commission_run_beneficiaries enable row level security;
alter table commission_run_beneficiaries force row level security;
create policy commission_run_beneficiaries_scope on commission_run_beneficiaries
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table commission_allocated_lines (
    tenant_id uuid not null references tenants(id),
    beneficiary_type varchar(14) not null,
    source varchar(10) not null check (source in ('SALE','RETURN')),
    sale_line_id uuid not null,
    run_id uuid not null,
    primary key (tenant_id, beneficiary_type, source, sale_line_id),
    constraint cal_run_fkey foreign key (tenant_id, run_id) references commission_runs (tenant_id, id)
);
alter table commission_allocated_lines enable row level security;
alter table commission_allocated_lines force row level security;
create policy commission_allocated_lines_scope on commission_allocated_lines
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create index cal_run_idx on commission_allocated_lines (tenant_id, run_id);

insert into permission_catalog (code, description) values
    ('communication.allocation.read', 'View commission allocation runs and lines'),
    ('communication.allocation.run', 'Calculate, recalculate and cancel a draft allocation run'),
    ('communication.allocation.approve', 'Approve an allocation run (must differ from the runner)');

select grant_permission_to_default_roles('communication.allocation.read', array['Administrator', 'Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('communication.allocation.run', array['Administrator']);
select grant_permission_to_default_roles('communication.allocation.approve', array['Administrator', 'Management']);
