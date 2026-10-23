-- =============================================================================================
-- Sales & POS, slice 3 (docs/09 §2.4, §2.5): Instant Discount on a live sale, and pro-forma
-- invoices (quotations that convert to a sale/invoice).
--
-- Every reference between tenant records is tenant-scoped (V14_9).
-- =============================================================================================

-- ---- Instant Discount (§2.4): an Admin's discount on an open sale, approved above a ceiling ----
create table instant_discounts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- IDS-n
    sale_id uuid not null,
    mode varchar(10) not null check (mode in ('PERCENT', 'AMOUNT')),
    value numeric(14,4) not null check (value > 0),        -- % or ₦ as entered
    discount_amount numeric(14,2) not null check (discount_amount > 0),
    base_amount numeric(14,2) not null,                    -- sale gross − line discounts at the time (VAT-exclusive)
    pct_of_base numeric(7,4) not null,
    reason varchar(20) not null check (reason in ('VOLUME', 'BULK', 'LOYALTY', 'GOODWILL', 'CLEARANCE')),
    note varchar(300),
    status varchar(20) not null check (status in ('PENDING_APPROVAL', 'APPLIED', 'REJECTED', 'WITHDRAWN', 'LAPSED')),
    applied_by_user_id uuid not null references app_users(id),
    approved_by_user_id uuid references app_users(id),
    decided_at timestamptz,
    rejection_reason varchar(300),
    created_at timestamptz not null default now(),
    constraint instant_discounts_sale_fkey foreign key (tenant_id, sale_id) references sales (tenant_id, id),
    constraint instant_discounts_ref_uq unique (tenant_id, ref)
);
create unique index instant_discounts_tenant_id_key on instant_discounts (tenant_id, id);
-- At most one live discount per sale.
create unique index instant_discounts_one_live_uq on instant_discounts (sale_id) where status in ('PENDING_APPROVAL', 'APPLIED');
create index instant_discounts_list_idx on instant_discounts (tenant_id, status, created_at desc);

-- ---- Pro-forma invoices (§2.5): a quotation that can convert to an order ----
create table pro_formas (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- PF-n
    status varchar(20) not null check (status in ('DRAFT', 'PENDING_APPROVAL', 'SENT', 'CONVERTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    location_id uuid not null,
    customer_id uuid,                                      -- registered customer, or null for a prospect
    prospect_name varchar(200),
    prospect_address varchar(300),
    prospect_contact varchar(200),
    prospect_email varchar(320),
    customer_name varchar(200) not null,                   -- snapshot for lists
    customer_type varchar(30),
    price_basis varchar(10) not null check (price_basis in ('RETAIL', 'DEALER')),
    issue_date date not null,
    valid_until date not null,
    payment_terms varchar(300),
    delivery_terms varchar(300),
    note varchar(500),
    gross_amount numeric(14,2) not null default 0,
    discount_total numeric(14,2) not null default 0,
    net_amount numeric(14,2) not null default 0,
    vat_enabled boolean not null,
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null default 0,
    total numeric(14,2) not null default 0,
    prepared_by_user_id uuid not null references app_users(id),
    discount_approved_by_user_id uuid references app_users(id),
    sent_at timestamptz,
    decided_at timestamptz,
    decline_reason varchar(300),
    converted_sale_id uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', ref || ' ' || customer_name)) stored,
    constraint pro_formas_location_fkey foreign key (tenant_id, location_id) references locations (tenant_id, id),
    constraint pro_formas_customer_fkey foreign key (tenant_id, customer_id) references customers (tenant_id, id),
    constraint pro_formas_converted_sale_fkey foreign key (tenant_id, converted_sale_id) references sales (tenant_id, id),
    constraint pro_formas_ref_uq unique (tenant_id, ref),
    constraint pro_formas_party check (customer_id is not null or prospect_name is not null),
    constraint pro_formas_validity check (valid_until >= issue_date)
);
create unique index pro_formas_tenant_id_key on pro_formas (tenant_id, id);
create index pro_formas_status_idx on pro_formas (tenant_id, location_id, status, issue_date desc);
create index pro_formas_customer_idx on pro_formas (tenant_id, customer_id);
create index pro_formas_name_trgm on pro_formas using gin (f_unaccent(lower(customer_name)) gin_trgm_ops);
create index pro_formas_search_idx on pro_formas using gin (search_vector);

create table pro_forma_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    pro_forma_id uuid not null,
    line_no int not null,
    stock_item_id uuid not null,
    item_name varchar(200) not null,
    unit_name varchar(60) not null,
    quantity numeric(14,3) not null check (quantity > 0),
    unit_price numeric(14,2) not null,
    line_discount numeric(14,2) not null default 0 check (line_discount >= 0),
    line_net numeric(14,2) not null,
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    constraint pfl_pro_forma_fkey foreign key (tenant_id, pro_forma_id) references pro_formas (tenant_id, id) on delete cascade,
    constraint pfl_stock_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id),
    constraint pfl_line_uq unique (pro_forma_id, line_no),
    constraint pfl_discount_le check (line_discount <= quantity * unit_price)
);
create index pro_forma_lines_pf_idx on pro_forma_lines (tenant_id, pro_forma_id);

do $$
declare
    t text;
begin
    foreach t in array array['instant_discounts', 'pro_formas', 'pro_forma_lines'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('cashier.discount.apply', 'Apply an Instant Discount to a live sale'),
    ('cashier.discount.approve', 'Approve discounts above the ceiling (live sales and pro-formas)'),
    ('cashier.pro-forma.manage', 'Create, edit, send, decline, cancel and convert pro-forma invoices');

select grant_permission_to_default_roles('cashier.discount.apply', array['Administrator']);
select grant_permission_to_default_roles('cashier.discount.approve', array['Administrator', 'Management']);
select grant_permission_to_default_roles('cashier.pro-forma.manage', array['Administrator', 'Cashier', 'Accounts Personnel']);
