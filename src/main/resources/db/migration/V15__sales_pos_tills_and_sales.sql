-- =============================================================================================
-- Sales & POS, slice 1 (docs/09 §2.2–2.3): till sessions and POS sales — a server-side cart that
-- completes into stock out, money in (Account receipts, wallet/deposit debits) and an invoice.
-- Goods are collected at the till in this slice; deliver-later (stock reservations and customer
-- waybills) arrives with slice 2, instant discounts and pro-formas with slice 3.
--
-- Every reference between tenant records is tenant-scoped (V14_9): (tenant_id, x_id) → parent
-- (tenant_id, id). Users (app_users) are platform-wide and keep a plain key.
-- =============================================================================================

-- Stock leaving with a sale, and coming back when it is voided; customer waybills (slice 2) too.
alter table stock_ledger_entries drop constraint stock_ledger_entries_source_type_check;
alter table stock_ledger_entries add constraint stock_ledger_entries_source_type_check check (source_type in (
    'REFILL', 'RECEIVE', 'PRODUCTION_RUN', 'TRANSFER_OUT', 'TRANSFER_IN', 'QUANTITY_ADJUSTMENT', 'TRADE_OUT', 'TRADE_IN',
    'TRANSFER_REJECTED_IN', 'DIRECT_ENTRY', 'OUTSOURCE_RECEIPT', 'CUSTOMER_RETURN', 'AREA_MOVE_OUT', 'AREA_MOVE_IN',
    'POS_SALE', 'SALE_VOID', 'CUSTOMER_WAYBILL', 'CUSTOMER_WAYBILL_CANCEL'
));

-- The parents referenced below; V14_9 created these where a reference already existed.
create unique index if not exists locations_tenant_id_key on locations (tenant_id, id);
create unique index if not exists stock_items_tenant_id_key on stock_items (tenant_id, id);
create unique index if not exists customers_tenant_id_key on customers (tenant_id, id);
create unique index if not exists correspondents_tenant_id_key on correspondents (tenant_id, id);
create unique index if not exists cash_accounts_tenant_id_key on cash_accounts (tenant_id, id);
create unique index if not exists bank_accounts_tenant_id_key on bank_accounts (tenant_id, id);
create unique index if not exists payment_receipts_tenant_id_key on payment_receipts (tenant_id, id);
create unique index if not exists customer_ledger_entries_tenant_id_key on customer_ledger_entries (tenant_id, id);
create unique index if not exists invoices_tenant_id_key on invoices (tenant_id, id);

-- ---- Till sessions (§2.2): one open session per till and per cashier ----
create table till_sessions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    cash_account_id uuid not null,
    till_code varchar(30) not null,
    location_id uuid not null,
    cashier_user_id uuid not null references app_users(id),
    status varchar(10) not null check (status in ('OPEN', 'CLOSED')),
    opened_at timestamptz not null default now(),
    opening_float numeric(14,2) not null check (opening_float >= 0),
    closed_at timestamptz,
    closed_by_user_id uuid references app_users(id),
    counted_cash numeric(14,2) check (counted_cash >= 0),
    expected_cash numeric(14,2),
    variance numeric(14,2),
    close_note varchar(300),
    force_closed boolean not null default false,
    constraint till_sessions_cash_account_fkey foreign key (tenant_id, cash_account_id) references cash_accounts (tenant_id, id),
    constraint till_sessions_location_fkey foreign key (tenant_id, location_id) references locations (tenant_id, id),
    constraint till_sessions_closed check ((status = 'CLOSED') = (closed_at is not null))
);
create unique index till_sessions_tenant_id_key on till_sessions (tenant_id, id);
create unique index till_sessions_open_till_uq on till_sessions (tenant_id, cash_account_id) where status = 'OPEN';
create unique index till_sessions_open_cashier_uq on till_sessions (tenant_id, cashier_user_id) where status = 'OPEN';
create index till_sessions_cashier_idx on till_sessions (tenant_id, cashier_user_id, opened_at desc);
create index till_sessions_location_idx on till_sessions (tenant_id, location_id, opened_at desc);

-- ---- Sales (§2.3): the cart while OPEN; the sale once COMPLETED ----
create table sales (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20),                                       -- TXN-n, assigned at completion
    channel varchar(20) not null check (channel in ('POS', 'PRO_FORMA_ORDER')),
    status varchar(12) not null check (status in ('OPEN', 'COMPLETED', 'ABANDONED', 'VOIDED')),
    version int not null default 0,                        -- optimistic lock, bumped by every change
    location_id uuid not null,                             -- the selling showroom or factory
    till_session_id uuid,
    cashier_user_id uuid not null references app_users(id),
    pro_forma_id uuid,                                     -- slice 3
    customer_id uuid,                                      -- null = walk-in
    customer_code varchar(20),
    customer_name varchar(200) not null default 'Walk-in customer',
    customer_type varchar(30),
    price_basis varchar(10) not null default 'RETAIL' check (price_basis in ('RETAIL', 'DEALER')),
    correspondent_id uuid,                                 -- captured at completion (§1.2-6)
    gross_amount numeric(14,2) not null default 0,
    line_discount_total numeric(14,2) not null default 0,
    instant_discount_total numeric(14,2) not null default 0,
    net_amount numeric(14,2) not null default 0,
    vat_enabled boolean not null default true,
    vat_rate numeric(5,2) not null default 0,
    vat_amount numeric(14,2) not null default 0,
    total numeric(14,2) not null default 0,
    paid_now numeric(14,2) not null default 0,
    on_account numeric(14,2) not null default 0,
    cash_tendered numeric(14,2),
    change_given numeric(14,2),
    credit_override_reason varchar(300),
    credit_override_by_user_id uuid references app_users(id),
    invoice_id uuid,
    invoice_number varchar(20),
    walk_in_phone varchar(40),
    supply_status varchar(20) not null default 'NOT_APPLICABLE'
        check (supply_status in ('NOT_APPLICABLE', 'FULLY_SUPPLIED', 'PARTIALLY_SUPPLIED', 'NOT_SUPPLIED')),
    sale_date date,
    started_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    abandoned_at timestamptz,
    voided_at timestamptz,
    voided_by_user_id uuid references app_users(id),
    void_reason varchar(300),
    search_vector tsvector generated always as (to_tsvector('simple',
        coalesce(ref, '') || ' ' || coalesce(invoice_number, '') || ' ' || customer_name || ' ' || coalesce(customer_code, ''))) stored,
    constraint sales_location_fkey foreign key (tenant_id, location_id) references locations (tenant_id, id),
    constraint sales_till_session_fkey foreign key (tenant_id, till_session_id) references till_sessions (tenant_id, id),
    constraint sales_customer_fkey foreign key (tenant_id, customer_id) references customers (tenant_id, id),
    constraint sales_correspondent_fkey foreign key (tenant_id, correspondent_id) references correspondents (tenant_id, id),
    constraint sales_invoice_fkey foreign key (tenant_id, invoice_id) references invoices (tenant_id, id),
    constraint sales_ref_when_done check ((status in ('OPEN', 'ABANDONED')) = (ref is null)),
    constraint sales_settled check (status <> 'COMPLETED' or abs(paid_now + on_account - total) < 0.005),
    constraint sales_pos_has_till check (channel <> 'POS' or till_session_id is not null)
);
create unique index sales_tenant_id_key on sales (tenant_id, id);
create unique index sales_ref_uq on sales (tenant_id, ref) where ref is not null;
create index sales_open_idx on sales (tenant_id, location_id, started_at) where status = 'OPEN';
create index sales_till_session_idx on sales (tenant_id, till_session_id) where till_session_id is not null;
create index sales_date_idx on sales (tenant_id, location_id, sale_date) where status = 'COMPLETED';
create index sales_completed_idx on sales (tenant_id, completed_at desc, id) where ref is not null;
create index sales_customer_idx on sales (tenant_id, customer_id, sale_date);
create index sales_cashier_idx on sales (tenant_id, cashier_user_id, sale_date);
create index sales_correspondent_idx on sales (tenant_id, correspondent_id, sale_date);
create index sales_invoice_idx on sales (tenant_id, invoice_id) where invoice_id is not null;
create index sales_invoice_number_idx on sales (tenant_id, invoice_number) where invoice_number is not null;
create index sales_name_trgm on sales using gin (f_unaccent(lower(customer_name)) gin_trgm_ops);
create index sales_search_idx on sales using gin (search_vector);

create table sale_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    sale_id uuid not null,
    line_no int not null,
    stock_item_id uuid not null,
    item_code varchar(40) not null,                        -- snapshots: the receipt shows what was sold
    item_name varchar(200) not null,
    unit_name varchar(60) not null,
    category_name varchar(120),
    quantity numeric(14,3) not null check (quantity > 0),
    unit_price numeric(14,2) not null check (unit_price >= 0),
    price_basis varchar(10) not null check (price_basis in ('RETAIL', 'DEALER', 'PRO_FORMA')),
    price_list_ref varchar(30),                            -- the price-list batch the price came from
    line_discount numeric(14,2) not null default 0 check (line_discount >= 0),
    instant_discount_share numeric(14,2) not null default 0 check (instant_discount_share >= 0),
    line_net numeric(14,2) not null,
    vat_category_code varchar(30) not null,
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    supply_now_qty numeric(14,3) not null default 0 check (supply_now_qty >= 0),
    supplied_qty numeric(14,3) not null default 0 check (supplied_qty >= 0),
    from_location_id uuid,                                 -- the area at-till goods left from
    constraint sale_lines_sale_fkey foreign key (tenant_id, sale_id) references sales (tenant_id, id) on delete cascade,
    constraint sale_lines_stock_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id),
    constraint sale_lines_from_location_fkey foreign key (tenant_id, from_location_id) references locations (tenant_id, id),
    constraint sale_lines_line_uq unique (sale_id, line_no),
    constraint sale_lines_supplied_le check (supplied_qty <= quantity),
    constraint sale_lines_discount_le check (line_discount + instant_discount_share <= quantity * unit_price)
);
create unique index sale_lines_tenant_id_key on sale_lines (tenant_id, id);
create index sale_lines_item_idx on sale_lines (tenant_id, stock_item_id);

create table sale_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    sale_id uuid not null,
    method varchar(20) not null check (method in ('CASH', 'POS', 'FUND_TRANSFER', 'WALLET', 'DEPOSIT')),
    amount numeric(14,2) not null check (amount > 0),
    payment_receipt_id uuid not null,
    receipt_ref varchar(20) not null,
    customer_ledger_entry_id uuid,
    terminal_ref varchar(40),
    payer_reference varchar(80),
    receiving_bank_account_id uuid,
    created_at timestamptz not null default now(),
    constraint sale_payments_sale_fkey foreign key (tenant_id, sale_id) references sales (tenant_id, id),
    constraint sale_payments_receipt_fkey foreign key (tenant_id, payment_receipt_id) references payment_receipts (tenant_id, id),
    constraint sale_payments_ledger_entry_fkey foreign key (tenant_id, customer_ledger_entry_id) references customer_ledger_entries (tenant_id, id),
    constraint sale_payments_bank_account_fkey foreign key (tenant_id, receiving_bank_account_id) references bank_accounts (tenant_id, id)
);
create index sale_payments_sale_idx on sale_payments (tenant_id, sale_id);
create index sale_payments_method_idx on sale_payments (tenant_id, method, sale_id);
create index sale_payments_receipt_idx on sale_payments (tenant_id, payment_receipt_id);

do $$
declare
    t text;
begin
    foreach t in array array['till_sessions', 'sales', 'sale_lines', 'sale_payments'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) — cashier.sales.read / .manage exist since V1 ----
update permission_catalog set description = 'Own sales and till sessions, sale detail and receipt' where code = 'cashier.sales.read';
update permission_catalog set description = 'Open and close own till; build and complete sales' where code = 'cashier.sales.manage';
insert into permission_catalog (code, description) values
    ('cashier.sales.read-all', 'Every sale and till session in location scope, and the sales reports'),
    ('cashier.sales.void', 'Void a completed sale within the void window'),
    ('cashier.credit.override', 'Complete an on-account sale beyond the customer''s credit limit (with a reason)'),
    ('cashier.till.supervise', 'Force-close another cashier''s till session');

select grant_permission_to_default_roles('cashier.sales.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('cashier.sales.manage', array['Administrator', 'Cashier']);
select grant_permission_to_default_roles('cashier.sales.read-all', array['Administrator', 'Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('cashier.sales.void', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('cashier.credit.override', array['Administrator', 'Management']);
select grant_permission_to_default_roles('cashier.till.supervise', array['Administrator', 'Management', 'Accounts Personnel']);
