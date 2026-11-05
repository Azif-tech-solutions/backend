-- Module 12 (Communication), slice 1: the Comms. Guide — commission rates per finished product and
-- beneficiary type (dealer or correspondent), effective-dated (docs/12 §2.1). The allocation runs,
-- payments and reports arrive in later slices. The planned V16 number was long taken; this is V20.

create table commission_rates (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null,
    beneficiary_type varchar(14) not null check (beneficiary_type in ('DEALER','CORRESPONDENT')),
    rate_type varchar(10) not null check (rate_type in ('PER_UNIT','PERCENTAGE')),
    rate numeric(14,4) not null check (rate >= 0),           -- naira per unit, or % of net unit price
    reference_price numeric(14,2),                            -- price used only for the per-unit preview
    status varchar(10) not null check (status in ('ACTIVE','INACTIVE')),
    effective_from date not null,
    effective_to date,                                        -- set automatically when a later row supersedes this one
    note varchar(300),
    set_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint cr_pct_range check (rate_type <> 'PERCENTAGE' or rate <= 100),
    constraint cr_dates check (effective_to is null or effective_to >= effective_from),
    constraint commission_rates_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id),
    unique (tenant_id, stock_item_id, beneficiary_type, effective_from)
);
alter table commission_rates enable row level security;
alter table commission_rates force row level security;
create policy commission_rates_scope on commission_rates
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The rate in force for (item, type) on date d is the row with the greatest effective_from <= d.
create index cr_lookup_idx on commission_rates (tenant_id, stock_item_id, beneficiary_type, effective_from desc);
create index cr_type_status_idx on commission_rates (tenant_id, beneficiary_type, status, effective_from);

-- Permissions (docs/12 §3). The Comms. Guide is Administrator-only to manage.
insert into permission_catalog (code, description) values
    ('communication.guide.read', 'View commission rates (Comms. Guide)'),
    ('communication.guide.manage', 'Set and withdraw commission rates');

select grant_permission_to_default_roles('communication.guide.read', array['Administrator', 'Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('communication.guide.manage', array['Administrator']);
