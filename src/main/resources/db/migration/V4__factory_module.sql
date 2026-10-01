-- Settings reference tables (owned by com.zifiq.settings; created here because the Factory
-- module is their first real consumer -- see docs/02-factory.md §2.1).
create table categories (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(120) not null,
    type varchar(20) not null check (type in ('FINISHED_PRODUCT', 'RAW_MATERIAL', 'PACKAGING')),
    brand varchar(120),
    description varchar(300),
    created_at timestamptz not null default now(),
    unique (tenant_id, name)
);
alter table categories enable row level security;
alter table categories force row level security;
create policy categories_scope on categories
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table units_of_measure (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,
    name varchar(80) not null,
    symbol varchar(10),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
alter table units_of_measure enable row level security;
alter table units_of_measure force row level security;
create policy units_of_measure_scope on units_of_measure
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table suppliers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(40) not null,
    company_name varchar(160) not null,
    contact_email varchar(320),
    phone varchar(40),
    address varchar(300),
    supplies varchar(300),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
alter table suppliers enable row level security;
alter table suppliers force row level security;
create policy suppliers_scope on suppliers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table manufacturers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(40) not null,
    name varchar(160) not null,
    country varchar(80),
    contact_email varchar(320),
    phone varchar(40),
    produces varchar(300),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
alter table manufacturers enable row level security;
alter table manufacturers force row level security;
create policy manufacturers_scope on manufacturers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Product/material master. Every factory has its own product range (PRD 5.1.3).
create table stock_items (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    item_type varchar(20) not null check (item_type in ('FINISHED_PRODUCT', 'RAW_MATERIAL', 'IN_HOUSE_RECIPE', 'OUTSOURCED')),
    code varchar(40) not null,
    name varchar(160) not null,
    category_id uuid references categories(id),
    brand varchar(120),
    unit_of_measure_id uuid not null references units_of_measure(id),
    reorder_level numeric(14,3) not null default 0,
    barcode varchar(64),
    landing_cost numeric(14,2),
    markup_cost numeric(14,2),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index stock_items_barcode_idx on stock_items (tenant_id, barcode) where barcode is not null;
alter table stock_items enable row level security;
alter table stock_items force row level security;
create policy stock_items_scope on stock_items
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Materialized running total -- only ever written inside the same transaction as a
-- stock_ledger_entries insert (see docs/02 §2.3).
create table stock_balances (
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    quantity_on_hand numeric(14,3) not null default 0,
    updated_at timestamptz not null default now(),
    primary key (location_id, stock_item_id)
);
alter table stock_balances enable row level security;
alter table stock_balances force row level security;
create policy stock_balances_scope on stock_balances
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The one generic ledger every stock-affecting action writes to (docs/02 §2.4).
create table stock_ledger_entries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    source_type varchar(30) not null check (source_type in
        ('REFILL', 'RECEIVE', 'PRODUCTION_RUN', 'TRANSFER_OUT', 'TRANSFER_IN',
         'QUANTITY_ADJUSTMENT', 'TRADE_OUT', 'TRADE_IN')),
    source_reference varchar(40) not null,
    qty_in numeric(14,3) not null default 0,
    qty_out numeric(14,3) not null default 0,
    unit_cost numeric(14,2),
    remark varchar(300),
    entry_date date not null,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create index stock_ledger_entries_item_idx on stock_ledger_entries (tenant_id, location_id, stock_item_id, entry_date);
alter table stock_ledger_entries enable row level security;
alter table stock_ledger_entries force row level security;
create policy stock_ledger_entries_scope on stock_ledger_entries
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table recipes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    code varchar(40) not null,
    name varchar(160) not null,
    description varchar(300),
    sourcing varchar(20) not null check (sourcing in ('IN_HOUSE_PRODUCTION', 'DIRECT_IMPORTATION')),
    yield_qty numeric(14,3) not null,
    yield_unit_id uuid not null references units_of_measure(id),
    shelf_life_days integer,
    reorder_level numeric(14,3),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
alter table recipes enable row level security;
alter table recipes force row level security;
create policy recipes_scope on recipes
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table recipe_ingredients (
    id uuid primary key default gen_random_uuid(),
    recipe_id uuid not null references recipes(id) on delete cascade,
    raw_material_stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null,
    unit_id uuid not null references units_of_measure(id)
);
alter table recipe_ingredients enable row level security;
alter table recipe_ingredients force row level security;
create policy recipe_ingredients_scope on recipe_ingredients
    using (exists (select 1 from recipes r where r.id = recipe_ingredients.recipe_id
        and r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid))
    with check (exists (select 1 from recipes r where r.id = recipe_ingredients.recipe_id
        and r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Production Cost Calculator output. QC (computed_by_user_id) completes costing "blind" -- see
-- docs/02 §3.8 for how the API masks/omits computed_unit_cost/computed_total_cost for them.
create table production_costings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    target_stock_item_id uuid not null references stock_items(id),
    recipe_id uuid references recipes(id),
    batch_size_qty numeric(14,3) not null,
    materials_snapshot jsonb not null,
    overhead_snapshot jsonb not null default '[]'::jsonb,
    computed_unit_cost numeric(14,2) not null,
    computed_total_cost numeric(14,2) not null,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED')),
    computed_by_user_id uuid not null references app_users(id),
    approved_by_user_id uuid references app_users(id),
    approved_at timestamptz,
    rejection_reason varchar(300),
    created_at timestamptz not null default now()
);
alter table production_costings enable row level security;
alter table production_costings force row level security;
create policy production_costings_scope on production_costings
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table production_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    batch_ref varchar(40) not null,
    production_costing_id uuid not null references production_costings(id),
    quantity_produced numeric(14,3) not null,
    unit_landing_cost numeric(14,2) not null,
    total_landing_cost numeric(14,2) not null,
    materials_used jsonb not null,
    run_by_user_id uuid not null references app_users(id),
    run_at timestamptz not null default now(),
    unique (tenant_id, batch_ref)
);
alter table production_runs enable row level security;
alter table production_runs force row level security;
create policy production_runs_scope on production_runs
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table special_production_clients (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(160) not null,
    terms varchar(300),
    created_at timestamptz not null default now()
);
alter table special_production_clients enable row level security;
alter table special_production_clients force row level security;
create policy special_production_clients_scope on special_production_clients
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table special_production_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    order_ref varchar(40) not null,
    batch_ref varchar(40) not null,
    client_id uuid not null references special_production_clients(id),
    target_stock_item_id uuid references stock_items(id),
    product_description varchar(160),
    quantity numeric(14,3) not null,
    unit_id uuid not null references units_of_measure(id),
    unit_cost numeric(14,2) not null,
    price numeric(14,2) not null,
    materials_snapshot jsonb not null default '[]'::jsonb,
    status varchar(20) not null default 'IN_PRODUCTION' check (status in ('IN_PRODUCTION', 'AWAITING_DISPATCH', 'DELIVERED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
alter table special_production_runs enable row level security;
alter table special_production_runs force row level security;
create policy special_production_runs_scope on special_production_runs
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table stock_transfers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    waybill_ref varchar(40) not null,
    source_location_id uuid not null references locations(id),
    destination_location_id uuid not null references locations(id),
    driver_name varchar(120),
    driver_vehicle varchar(60),
    dispatched_by_user_id uuid not null references app_users(id),
    dispatched_at timestamptz not null default now(),
    received_by_user_id uuid references app_users(id),
    received_at timestamptz,
    status varchar(20) not null default 'DISPATCHED' check (status in ('DISPATCHED', 'PARTIALLY_RECEIVED', 'RECEIVED')),
    unique (tenant_id, waybill_ref)
);
alter table stock_transfers enable row level security;
alter table stock_transfers force row level security;
create policy stock_transfers_scope on stock_transfers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table stock_transfer_lines (
    id uuid primary key default gen_random_uuid(),
    stock_transfer_id uuid not null references stock_transfers(id) on delete cascade,
    stock_item_id uuid not null references stock_items(id),
    qty_sent numeric(14,3) not null,
    qty_received numeric(14,3),
    qty_rejected numeric(14,3),
    unit_price numeric(14,2)
);
alter table stock_transfer_lines enable row level security;
alter table stock_transfer_lines force row level security;
create policy stock_transfer_lines_scope on stock_transfer_lines
    using (exists (select 1 from stock_transfers t where t.id = stock_transfer_lines.stock_transfer_id
        and t.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid))
    with check (exists (select 1 from stock_transfers t where t.id = stock_transfer_lines.stock_transfer_id
        and t.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid));

create table quantity_adjustments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    ref varchar(40) not null,
    stock_item_id uuid not null references stock_items(id),
    direction varchar(10) not null check (direction in ('POSITIVE', 'NEGATIVE')),
    quantity numeric(14,3) not null,
    reason varchar(30) not null check (reason in
        ('STOCKTAKE_VARIANCE', 'DAMAGED_BROKEN', 'EXPIRED_WRITEOFF', 'SUSPECTED_THEFT',
         'MISCOUNT_CORRECTION', 'UNRECORDED_RETURN', 'OTHER')),
    note varchar(300),
    otp_challenge_id uuid references otp_challenges(id),
    performed_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
alter table quantity_adjustments enable row level security;
alter table quantity_adjustments force row level security;
create policy quantity_adjustments_scope on quantity_adjustments
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Material/Recipe Receive Report + Raw Material Refill (same feature, one table -- docs/02 §2.11).
create table stock_receipts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    ref varchar(40) not null,
    stock_item_id uuid not null references stock_items(id),
    supplier_id uuid references suppliers(id),
    manufacturer_id uuid references manufacturers(id),
    quantity numeric(14,3) not null,
    unit_cost numeric(14,2) not null,
    manufactured_date date,
    expiry_date date,
    verified_by_user_id uuid references app_users(id),
    offloaded_at timestamptz,
    condition varchar(30) not null default 'PENDING_VERIFICATION' check (condition in
        ('ACCEPTED', 'ACCEPTED_WITH_EXCEPTIONS', 'PENDING_VERIFICATION')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
alter table stock_receipts enable row level security;
alter table stock_receipts force row level security;
create policy stock_receipts_scope on stock_receipts
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table factory_trade_offers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(40) not null,
    from_location_id uuid not null references locations(id),
    to_location_id uuid not null references locations(id),
    stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null,
    reason varchar(20) not null check (reason in ('SURPLUS', 'EXPIRY', 'CHANGEOVER', 'SCHEDULE', 'DISCONTINUED')),
    offered_by_user_id uuid not null references app_users(id),
    offered_at timestamptz not null default now(),
    expires_at timestamptz not null,
    responded_at timestamptz,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING', 'OPEN', 'ACCEPTED', 'REJECTED', 'EXPIRED')),
    responded_by_user_id uuid references app_users(id),
    unique (tenant_id, ref)
);
alter table factory_trade_offers enable row level security;
alter table factory_trade_offers force row level security;
create policy factory_trade_offers_scope on factory_trade_offers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

insert into permission_catalog (code, description) values
    ('factory.setup.manage', 'Register and manage factories, suppliers, manufacturers, units of measure, and categories'),
    ('factory.recipes.manage', 'Register and edit in-house recipes'),
    ('factory.production.manage', 'Run the production cost calculator and commit production runs'),
    ('factory.costing.read', 'View computed landing/unit cost figures'),
    ('factory.costing.approve', 'Approve or reject a production costing'),
    ('inventory.transfers.manage', 'Dispatch and receive stock transfers'),
    ('inventory.adjustments.manage', 'Post quantity adjustments'),
    ('factory.trade.manage', 'Offer and respond to factory-to-factory trades');
