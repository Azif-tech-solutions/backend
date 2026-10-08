-- Inventory changes Showroom needs (docs/05 §2.2–§2.6): direct-entry items, wider ledger source
-- types, drivers, transfer receipt detail, the price list, and counted adjustments.

-- ---------------------------------------------------------------------------------------------
-- Direct-entry items: third-party goods held at one showroom, not in the product register.
-- ---------------------------------------------------------------------------------------------
alter table stock_items drop constraint stock_items_item_type_check;
alter table stock_items add constraint stock_items_item_type_check
    check (item_type in ('FINISHED_PRODUCT', 'RAW_MATERIAL', 'IN_HOUSE_RECIPE', 'OUTSOURCED', 'DIRECT_ENTRY'));
alter table stock_items alter column hsn_section_id drop not null;
alter table stock_items alter column isic_section_id drop not null;
alter table stock_items add constraint stock_items_classification_required
    check (item_type = 'DIRECT_ENTRY' or (hsn_section_id is not null and isic_section_id is not null));

-- ---------------------------------------------------------------------------------------------
-- Ledger source types (Sales & POS adds POS_SALE / CUSTOMER_WAYBILL later).
-- ---------------------------------------------------------------------------------------------
alter table stock_ledger_entries drop constraint stock_ledger_entries_source_type_check;
alter table stock_ledger_entries add constraint stock_ledger_entries_source_type_check check (source_type in (
    'REFILL', 'RECEIVE', 'PRODUCTION_RUN', 'TRANSFER_OUT', 'TRANSFER_IN', 'QUANTITY_ADJUSTMENT', 'TRADE_OUT', 'TRADE_IN',
    'TRANSFER_REJECTED_IN', 'DIRECT_ENTRY', 'OUTSOURCE_RECEIPT', 'CUSTOMER_RETURN', 'AREA_MOVE_OUT', 'AREA_MOVE_IN'
));
create index stock_ledger_item_location_date_idx on stock_ledger_entries (tenant_id, stock_item_id, location_id, entry_date, created_at);

-- ---------------------------------------------------------------------------------------------
-- Drivers (driver + vehicle used on waybills).
-- ---------------------------------------------------------------------------------------------
create table drivers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(160) not null,
    phone varchar(40),
    phone_e164 varchar(20),
    vehicle_number varchar(30),
    licence_number varchar(40),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create unique index drivers_name_vehicle_uq on drivers (tenant_id, lower(name), coalesce(lower(vehicle_number), ''));
create index drivers_name_trgm on drivers using gin (f_unaccent(lower(name)) gin_trgm_ops);
alter table drivers enable row level security;
alter table drivers force row level security;
create policy drivers_scope on drivers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Transfer receipt detail.
-- ---------------------------------------------------------------------------------------------
alter table stock_transfers
    add column driver_id uuid references drivers(id),
    add column transfer_date date not null default current_date,
    add column received_date date,
    add column receipt_note varchar(300);
update stock_transfers set transfer_date = (dispatched_at at time zone 'Africa/Lagos')::date;
alter table stock_transfer_lines
    add column from_location_id uuid references locations(id),
    add column received_into_location_id uuid references locations(id),
    add column batch_no varchar(40),
    add column rejection_note varchar(300);
create index stock_transfers_to_idx on stock_transfers (tenant_id, destination_location_id, status, transfer_date desc);
create index stock_transfers_from_idx on stock_transfers (tenant_id, source_location_id, transfer_date desc);
create index stock_transfers_waybill_trgm on stock_transfers using gin (lower(waybill_ref) gin_trgm_ops);

-- ---------------------------------------------------------------------------------------------
-- Price list: effective-dated retail and dealer prices. Rows are never updated once effective.
-- ---------------------------------------------------------------------------------------------
create table stock_item_prices (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null references stock_items(id),
    retail_price numeric(14,2) not null check (retail_price >= 0),
    dealer_price numeric(14,2) not null check (dealer_price >= 0),
    effective_from date not null,
    batch_ref varchar(20) not null,
    note varchar(300),
    set_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint stock_item_prices_dealer_le_retail check (dealer_price <= retail_price),
    unique (tenant_id, stock_item_id, effective_from)
);
create index stock_item_prices_lookup_idx on stock_item_prices (tenant_id, stock_item_id, effective_from desc);
create index stock_item_prices_batch_idx on stock_item_prices (tenant_id, batch_ref);
alter table stock_item_prices enable row level security;
alter table stock_item_prices force row level security;
create policy stock_item_prices_scope on stock_item_prices
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Counted adjustments record what was on hand and what was counted; note is searchable.
-- ---------------------------------------------------------------------------------------------
alter table quantity_adjustments
    add column mode varchar(10) not null default 'DELTA' check (mode in ('DELTA', 'COUNTED')),
    add column recorded_quantity numeric(14,3),
    add column new_quantity numeric(14,3),
    add column search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(note, '')))) stored;
create index quantity_adjustments_location_idx on quantity_adjustments (tenant_id, location_id, created_at desc);
create index quantity_adjustments_reason_idx on quantity_adjustments (tenant_id, reason);
create index quantity_adjustments_search_idx on quantity_adjustments using gin (search_vector);

-- ---------------------------------------------------------------------------------------------
-- Permissions (docs/05 §3).
-- ---------------------------------------------------------------------------------------------
insert into permission_catalog (code, description) values
    ('inventory.pricing.read', 'View the price list (cost and margin columns still need factory.costing.read)'),
    ('inventory.pricing.manage', 'Publish and change prices'),
    ('inventory.drivers.read', 'List drivers'),
    ('inventory.drivers.manage', 'Register and edit drivers');
select grant_permission_to_default_roles('inventory.pricing.read', array['Administrator', 'Management', 'Accounts Personnel', 'Stock Keeper', 'Cashier']);
select grant_permission_to_default_roles('inventory.pricing.manage', array['Administrator']);
select grant_permission_to_default_roles('inventory.drivers.read', array['Administrator', 'Management', 'Stock Keeper', 'Cashier']);
select grant_permission_to_default_roles('inventory.drivers.manage', array['Administrator', 'Stock Keeper']);
