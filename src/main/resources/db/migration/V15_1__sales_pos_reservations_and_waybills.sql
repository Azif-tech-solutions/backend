-- =============================================================================================
-- Sales & POS, slice 2 (docs/09 §2.1, §2.6): deliver-later.
--
-- Goods sold but not collected at the till are RESERVED against the selling location (held, not
-- moved) and released later by a CUSTOMER WAYBILL, which posts the stock out. "Available" to sell,
-- transfer or trade = on hand across a location's areas minus its open reservations, so sold-but-
-- uncollected goods can't be promised twice while a stocktake still counts them (they are still
-- physically there).
--
-- stock_reservations is owned by inventory (the shared stock substrate); the waybill tables are
-- Sales & POS's. Every reference between tenant records is tenant-scoped (V14_9).
-- =============================================================================================

-- Composite-FK parents that slice 2 references and V14_9 didn't already index.
create unique index if not exists drivers_tenant_id_key on drivers (tenant_id, id);

-- ---- Reservations (inventory) ----
create table stock_reservations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null,                             -- the selling SHOWROOM/FACTORY (root, never an area)
    stock_item_id uuid not null,
    source_module varchar(30) not null,                    -- 'cashier'
    source_type varchar(30) not null check (source_type in ('SALE_LINE')),
    source_id uuid not null,                               -- sale_lines.id
    source_ref varchar(30) not null,                       -- TXN-n
    quantity_reserved numeric(14,3) not null check (quantity_reserved > 0),
    quantity_released numeric(14,3) not null default 0 check (quantity_released >= 0),
    status varchar(10) not null default 'OPEN' check (status in ('OPEN', 'CLOSED', 'CANCELLED')),
    created_at timestamptz not null default now(),
    closed_at timestamptz,
    constraint stock_reservations_location_fkey foreign key (tenant_id, location_id) references locations (tenant_id, id),
    constraint stock_reservations_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id),
    constraint stock_reservations_released_le check (quantity_released <= quantity_reserved),
    constraint stock_reservations_source_uq unique (source_type, source_id)
);
create unique index stock_reservations_tenant_id_key on stock_reservations (tenant_id, id);
create index stock_reservations_open_idx on stock_reservations (tenant_id, location_id, stock_item_id) where status = 'OPEN';

-- ---- Customer waybills (Sales & POS) ----
create table customer_waybills (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- WB-n (shared tenant-wide waybill space with stock transfers, docs/03 §6)
    sale_id uuid not null,
    invoice_number varchar(20),
    location_id uuid not null,
    release_method varchar(12) not null check (release_method in ('DELIVERY', 'COLLECTION')),
    release_date date not null,
    driver_id uuid,                                        -- required for DELIVERY
    driver_name varchar(160),
    vehicle_number varchar(30),
    collected_by varchar(160),                             -- required for COLLECTION
    delivery_address varchar(300),
    status varchar(12) not null check (status in ('ISSUED', 'DELIVERED', 'CANCELLED')),
    received_by varchar(160),                              -- signature name on acknowledgement
    received_at timestamptz,
    acknowledgement_note varchar(300),
    cancelled_reason varchar(300),
    cancelled_by_user_id uuid references app_users(id),
    issued_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint customer_waybills_sale_fkey foreign key (tenant_id, sale_id) references sales (tenant_id, id),
    constraint customer_waybills_driver_fkey foreign key (tenant_id, driver_id) references drivers (tenant_id, id),
    constraint customer_waybills_location_fkey foreign key (tenant_id, location_id) references locations (tenant_id, id),
    constraint customer_waybills_ref_uq unique (tenant_id, ref)
);
create unique index customer_waybills_tenant_id_key on customer_waybills (tenant_id, id);
create index customer_waybills_sale_idx on customer_waybills (tenant_id, sale_id);
create index customer_waybills_date_idx on customer_waybills (tenant_id, location_id, release_date desc);
create index customer_waybills_status_idx on customer_waybills (tenant_id, status, release_date desc);

create table customer_waybill_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    waybill_id uuid not null,
    sale_line_id uuid not null,
    from_location_id uuid not null,                        -- the area the goods left from
    quantity numeric(14,3) not null check (quantity > 0),
    outstanding_after numeric(14,3) not null,              -- printed "Still outstanding"
    constraint cwl_waybill_fkey foreign key (tenant_id, waybill_id) references customer_waybills (tenant_id, id) on delete cascade,
    constraint cwl_sale_line_fkey foreign key (tenant_id, sale_line_id) references sale_lines (tenant_id, id),
    constraint cwl_from_location_fkey foreign key (tenant_id, from_location_id) references locations (tenant_id, id)
);
create index customer_waybill_lines_waybill_idx on customer_waybill_lines (tenant_id, waybill_id);
create index customer_waybill_lines_sale_line_idx on customer_waybill_lines (tenant_id, sale_line_id);

do $$
declare
    t text;
begin
    foreach t in array array['stock_reservations', 'customer_waybills', 'customer_waybill_lines'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('cashier.waybill.manage', 'Generate, acknowledge and cancel customer waybills'),
    ('cashier.supply.read', 'Pending Supply reports and supply history');

select grant_permission_to_default_roles('cashier.waybill.manage', array['Administrator', 'Cashier', 'Stock Keeper']);
select grant_permission_to_default_roles('cashier.supply.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier', 'Stock Keeper']);
