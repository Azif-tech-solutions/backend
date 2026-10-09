-- Showroom module (docs/05 §2.1, §2.7–§2.10): showroom details on locations, direct-entry and
-- outsource receipts, customer returns, storage-area moves, and permissions.

-- ---------------------------------------------------------------------------------------------
-- Showroom (and factory) details. "Pending opening" uses locations.status = 'PENDING'.
-- ---------------------------------------------------------------------------------------------
alter table locations
    add column street_address varchar(300),
    add column city varchar(120),
    add column email varchar(320),
    add column manager_name varchar(160),
    add column date_opened date,
    add column storage_capacity varchar(80),
    add column operating_hours varchar(120),
    add column default_receiving_location_id uuid references locations(id),
    add column default_selling_location_id uuid references locations(id);
create index locations_name_trgm on locations using gin (f_unaccent(lower(name)) gin_trgm_ops);
-- Showroom names are unique per company (factories keep their existing rule).
create unique index locations_showroom_name_uq on locations (tenant_id, lower(name)) where type = 'SHOWROOM';

-- ---------------------------------------------------------------------------------------------
-- Direct entries (third-party goods) and outsource receipts (registered outsourced products).
-- ---------------------------------------------------------------------------------------------
create table showroom_stock_entries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    kind varchar(20) not null check (kind in ('DIRECT_ENTRY', 'OUTSOURCE_RECEIPT')),
    location_id uuid not null references locations(id),
    into_location_id uuid not null references locations(id),
    supplier_id uuid references suppliers(id),
    supplier_name varchar(160),
    manufacturer_id uuid references manufacturers(id),
    partner_reference varchar(60),
    payment_terms varchar(120),
    checked_by varchar(120),
    received_date date not null,
    total_cost numeric(14,2) not null,
    total_input_vat numeric(14,2) not null default 0,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint sse_kind_party check (
        (kind = 'DIRECT_ENTRY' and manufacturer_id is null and (supplier_id is not null or supplier_name is not null)) or
        (kind = 'OUTSOURCE_RECEIPT' and manufacturer_id is not null and supplier_id is null and supplier_name is null))
);
create index sse_location_date_idx on showroom_stock_entries (tenant_id, location_id, received_date desc);
create index sse_kind_idx on showroom_stock_entries (tenant_id, kind, received_date desc);
create index sse_supplier_name_trgm on showroom_stock_entries using gin (f_unaccent(lower(supplier_name)) gin_trgm_ops);
alter table showroom_stock_entries enable row level security;
alter table showroom_stock_entries force row level security;
create policy showroom_stock_entries_scope on showroom_stock_entries
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table showroom_stock_entry_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    entry_id uuid not null references showroom_stock_entries(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    description varchar(200) not null,
    quantity numeric(14,3) not null check (quantity > 0),
    unit_of_measure_id uuid not null references units_of_measure(id),
    unit_cost numeric(14,2) not null check (unit_cost >= 0),
    line_cost numeric(14,2) not null,
    input_vat_rate numeric(5,2) not null default 0 check (input_vat_rate >= 0 and input_vat_rate <= 100),
    input_vat_amount numeric(14,2) not null default 0,
    batch_no varchar(40),
    expiry_date date,
    selling_price numeric(14,2),
    unique (entry_id, line_no)
);
create index ssel_entry_idx on showroom_stock_entry_lines (tenant_id, entry_id);
create index ssel_item_idx on showroom_stock_entry_lines (tenant_id, stock_item_id);
create index ssel_description_trgm on showroom_stock_entry_lines using gin (f_unaccent(lower(description)) gin_trgm_ops);
alter table showroom_stock_entry_lines enable row level security;
alter table showroom_stock_entry_lines force row level security;
create policy showroom_stock_entry_lines_scope on showroom_stock_entry_lines
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Customer returns: the value is credited to the customer's wallet (docs/04 WalletLedger).
-- ---------------------------------------------------------------------------------------------
create table customer_returns (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    return_type varchar(20) not null check (return_type in ('DEALER', 'NON_DEALER')),
    location_id uuid not null references locations(id),
    customer_id uuid not null,                       -- customers.id; cross-module, so no FK (docs/00)
    customer_code varchar(20) not null,
    customer_name varchar(200) not null,
    customer_type varchar(30) not null,
    customer_phone varchar(40),
    temporary_customer boolean not null,
    original_document_ref varchar(40),
    return_date date not null,
    total_value numeric(14,2) not null,
    write_off_value numeric(14,2) not null,
    wallet_entry_id uuid not null,
    wallet_balance_before numeric(14,2) not null,
    wallet_balance_after numeric(14,2) not null,
    note varchar(300),
    status varchar(20) not null default 'POSTED' check (status in ('POSTED', 'REVERSED')),
    reversed_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    reversed_at timestamptz,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(note, '')))) stored,
    unique (tenant_id, ref)
);
create index cr_location_date_idx on customer_returns (tenant_id, location_id, return_date desc);
create index cr_customer_idx on customer_returns (tenant_id, customer_id);
create index cr_original_doc_idx on customer_returns (tenant_id, original_document_ref);
create index cr_customer_name_trgm on customer_returns using gin (f_unaccent(lower(customer_name)) gin_trgm_ops);
create index cr_search_idx on customer_returns using gin (search_vector);
alter table customer_returns enable row level security;
alter table customer_returns force row level security;
create policy customer_returns_scope on customer_returns
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table customer_return_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    return_id uuid not null references customer_returns(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null check (quantity > 0),
    unit_price numeric(14,2) not null,
    line_value numeric(14,2) not null,
    reason varchar(40) not null check (reason in (
        'DAMAGED_IN_TRANSIT', 'EXPIRED_OR_NEAR_EXPIRY', 'WRONG_ITEM_SUPPLIED',
        'PRODUCT_DEFECT', 'UNSOLD_AGREED_RETURN', 'CUSTOMER_DISSATISFACTION')),
    condition varchar(20) not null check (condition in ('RESALEABLE', 'DAMAGED_WRITE_OFF', 'QUARANTINE')),
    into_location_id uuid references locations(id),
    note varchar(300),
    unique (return_id, line_no),
    constraint crl_location_by_condition check ((condition = 'DAMAGED_WRITE_OFF') = (into_location_id is null))
);
create index crl_return_idx on customer_return_lines (tenant_id, return_id);
create index crl_reason_idx on customer_return_lines (tenant_id, reason);
alter table customer_return_lines enable row level security;
alter table customer_return_lines force row level security;
create policy customer_return_lines_scope on customer_return_lines
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Storage-area moves (Shop floor ⇄ Warehouse); goods don't leave the premises, so no waybill.
-- ---------------------------------------------------------------------------------------------
create table storage_area_moves (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    root_location_id uuid not null references locations(id),
    from_location_id uuid not null references locations(id),
    to_location_id uuid not null references locations(id),
    moved_date date not null,
    note varchar(300),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint sam_distinct check (from_location_id <> to_location_id)
);
create index sam_root_date_idx on storage_area_moves (tenant_id, root_location_id, moved_date desc);
alter table storage_area_moves enable row level security;
alter table storage_area_moves force row level security;
create policy storage_area_moves_scope on storage_area_moves
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table storage_area_move_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    move_id uuid not null references storage_area_moves(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    quantity numeric(14,3) not null check (quantity > 0),
    unique (move_id, line_no)
);
alter table storage_area_move_lines enable row level security;
alter table storage_area_move_lines force row level security;
create policy storage_area_move_lines_scope on storage_area_move_lines
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Permissions (docs/05 §3).
-- ---------------------------------------------------------------------------------------------
insert into permission_catalog (code, description) values
    ('showroom.register.manage', 'Register and edit showrooms, change their status, manage storage areas and defaults'),
    ('showroom.intake.manage', 'Post direct stock entries and outsource receipts'),
    ('showroom.returns.manage', 'Post and reverse customer returns'),
    ('showroom.returns.read', 'View Return Reports');
select grant_permission_to_default_roles('showroom.register.manage', array['Administrator']);
select grant_permission_to_default_roles('showroom.intake.manage', array['Administrator', 'Stock Keeper']);
select grant_permission_to_default_roles('showroom.returns.manage', array['Administrator', 'Stock Keeper', 'Cashier']);
select grant_permission_to_default_roles('showroom.returns.read', array['Administrator', 'Management', 'Accounts Personnel', 'Stock Keeper', 'Cashier']);
-- Showroom screens read stock through showroom.stock.read; Cashiers and Accounts need it too.
select grant_permission_to_default_roles('showroom.stock.read', array['Accounts Personnel', 'Cashier']);
