-- Module 11 (Barcode & QR), slice 3: label print runs (docs/11 §2.3). A run snapshots what it printed
-- (item name, unit, price, and which code) so a reprinted history matches the original even after the
-- product changes. Tenant-scoped composite FKs keep every reference inside the tenant.

create table label_print_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    label_size varchar(10) not null check (label_size in ('40x25','58x40','100x50','A4_24')),
    output_format varchar(4) not null check (output_format in ('PDF','ZPL')),
    show_price boolean not null default false,
    price_basis varchar(10) check (price_basis in ('RETAIL','DEALER')),
    location_id uuid,
    total_labels int not null,
    codes_generated int not null default 0,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
alter table label_print_runs enable row level security;
alter table label_print_runs force row level security;
create policy label_print_runs_scope on label_print_runs
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- Lets children carry a tenant-scoped composite FK back to a run.
create unique index label_print_runs_tenant_id_key on label_print_runs (tenant_id, id);
create index label_print_runs_recent_idx on label_print_runs (tenant_id, created_at desc);

-- A code can carry a tenant-scoped composite FK (slice 1 created only the PK).
create unique index stock_item_codes_tenant_id_key on stock_item_codes (tenant_id, id);

create table label_print_run_items (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null,
    stock_item_id uuid not null,
    code_id uuid not null,
    copies int not null check (copies between 1 and 1000),
    item_name varchar(200) not null,
    unit_name varchar(60) not null,
    price numeric(14,2),
    constraint label_print_run_items_run_fkey foreign key (tenant_id, run_id) references label_print_runs (tenant_id, id),
    constraint label_print_run_items_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id),
    constraint label_print_run_items_code_fkey foreign key (tenant_id, code_id) references stock_item_codes (tenant_id, id)
);
alter table label_print_run_items enable row level security;
alter table label_print_run_items force row level security;
create policy label_print_run_items_scope on label_print_run_items
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create index label_print_run_items_run_idx on label_print_run_items (tenant_id, run_id);
create index label_print_run_items_item_idx on label_print_run_items (tenant_id, stock_item_id);
