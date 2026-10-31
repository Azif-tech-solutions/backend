-- Module 11 (Barcode & QR), slice 1: the one code table every product code lives in, owned by the
-- OPEN inventory module so registration can assign a code without depending on the leaf barcode
-- module (docs/11 §2.1, §4.1). Scan events and label-print tables arrive with slices 2 and 3.

create table stock_item_codes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null,
    code varchar(64) not null,                             -- exactly as encoded (digits for EAN/UPC; text for QR/Code 128)
    code_normalized varchar(64) generated always as (upper(regexp_replace(code, '\s', '', 'g'))) stored,
    symbology varchar(12) not null check (symbology in ('EAN_13','EAN_8','UPC_A','CODE_128','QR')),
    source varchar(14) not null check (source in ('GENERATED','MANUFACTURER','MANUAL')),
    is_primary boolean not null default false,             -- the label code; mirrored to stock_items.barcode
    pack_quantity numeric(14,3) not null default 1 check (pack_quantity > 0),   -- a carton code that means 24 units -> 24
    pack_label varchar(40),                                -- 'Carton of 24'
    status varchar(10) not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
    retired_reason varchar(200),
    assigned_by_user_id uuid not null references app_users(id),
    assigned_at timestamptz not null default now(),
    retired_at timestamptz,
    -- Tenant-scoped composite FK (docs/03): a code can never point at another tenant's item.
    constraint stock_item_codes_stock_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id)
);
alter table stock_item_codes enable row level security;
alter table stock_item_codes force row level security;
create policy stock_item_codes_scope on stock_item_codes
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Retired codes stay reserved (never reused): an old printed label must never resolve to a new product.
create unique index stock_item_codes_code_uq on stock_item_codes (tenant_id, code_normalized);
create unique index stock_item_codes_primary_uq on stock_item_codes (stock_item_id) where is_primary and status = 'ACTIVE';
create index stock_item_codes_item_idx on stock_item_codes (tenant_id, stock_item_id);

-- Mirror of the primary code's symbology for the shipped Inventory API.
alter table stock_items add column barcode_symbology varchar(12);

-- Backfill: one primary code per existing stock_items.barcode; symbology inferred from the shape.
insert into stock_item_codes (tenant_id, stock_item_id, code, symbology, source, is_primary, assigned_by_user_id, assigned_at)
select tenant_id, id, barcode,
       case when barcode ~ '^[0-9]{13}$' then 'EAN_13'
            when barcode ~ '^[0-9]{8}$'  then 'EAN_8'
            when barcode ~ '^[0-9]{12}$' then 'UPC_A'
            else 'CODE_128' end,
       'MANUAL', true, created_by_user_id, now()
from stock_items
where barcode is not null
on conflict (tenant_id, code_normalized) do nothing;

update stock_items set barcode_symbology =
       case when barcode ~ '^[0-9]{13}$' then 'EAN_13'
            when barcode ~ '^[0-9]{8}$'  then 'EAN_8'
            when barcode ~ '^[0-9]{12}$' then 'UPC_A'
            else 'CODE_128' end
where barcode is not null;

-- Permissions (docs/11 §3.1).
insert into permission_catalog (code, description) values
    ('barcode.lookup', 'Resolve barcodes/QR codes on the Scan & Label screen'),
    ('barcode.codes.manage', 'Generate, assign, replace and retire product codes'),
    ('barcode.labels.print', 'Create label print runs and download label PDFs/ZPL'),
    ('barcode.scan-log.read', 'Read the barcode scan log');

select grant_permission_to_default_roles('barcode.lookup', array['Administrator', 'Management', 'Stock Keeper', 'Quality Control', 'Cashier', 'Accounts Personnel']);
select grant_permission_to_default_roles('barcode.codes.manage', array['Administrator', 'Stock Keeper']);
select grant_permission_to_default_roles('barcode.labels.print', array['Administrator', 'Stock Keeper']);
select grant_permission_to_default_roles('barcode.scan-log.read', array['Administrator', 'Management']);
