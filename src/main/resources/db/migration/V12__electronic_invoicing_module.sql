-- Electronic Invoicing, slice 1 (docs/08 §2.1–2.3, §2.7, §3): granular NRS classification on stock
-- items, invoices (draft → issued → void), invoice lines, waybill links, immutability triggers and
-- the invoicing permissions. Payments, credit notes and deliveries arrive in V12_1 (slice 2).
-- =============================================================================================

alter table stock_items
    add column hsn_code varchar(10) references hsn_codes(code),
    add column isic_code varchar(4) references isic_classes(code),
    add column nrs_unit_code varchar(10) references nrs_unit_of_measure_codes(code),
    add column is_service boolean not null default false;

create table invoices (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    number varchar(20),
    status varchar(10) not null check (status in ('DRAFT', 'ISSUED', 'VOID')),
    invoice_type_code varchar(3) not null default '381' references nrs_invoice_type_codes(code),
    invoice_kind varchar(3) not null check (invoice_kind in ('B2B', 'B2C', 'B2G')),
    source_type varchar(20) not null check (source_type in ('POS_SALE', 'PRO_FORMA')),
    source_id uuid not null,
    source_ref varchar(30) not null,
    source_date date not null,                           -- the sale / pro-forma date: an invoice is never dated before it
    pro_forma_ref varchar(30),
    location_id uuid not null references locations(id),
    issue_date date,
    issue_time time,
    due_date date,
    payment_terms_days int not null default 0 check (payment_terms_days >= 0),
    payment_terms_note varchar(300),
    document_currency_code varchar(3) not null default 'NGN',
    tax_currency_code varchar(3) not null default 'NGN',
    exchange_rate_id uuid,
    exchange_rate numeric(14,6),
    customer_id uuid,
    buyer_name varchar(200) not null,
    buyer_type varchar(30),
    buyer_email varchar(320),
    buyer_phone varchar(40),
    buyer_tin varchar(40),
    buyer_business_description varchar(200),
    buyer_street_address varchar(300),
    buyer_city varchar(120),
    buyer_postal_code varchar(20),
    buyer_state_code varchar(5),
    buyer_lga_code varchar(10),
    buyer_country_code varchar(2) not null default 'NG',
    correspondent_id uuid,
    issuer jsonb not null default '{}'::jsonb,
    line_extension_amount numeric(14,2) not null default 0,
    discount_total numeric(14,2) not null default 0,
    tax_exclusive_amount numeric(14,2) not null default 0,
    vat_total numeric(14,2) not null default 0,
    tax_inclusive_amount numeric(14,2) not null default 0,
    vat_enabled boolean not null,
    location_vat_rate numeric(5,2) not null,
    amount_paid numeric(14,2) not null default 0,
    amount_credited numeric(14,2) not null default 0,
    last_payment_at timestamptz,
    issued_by_user_id uuid references app_users(id),
    issued_at timestamptz,
    void_reason varchar(300),
    voided_by_user_id uuid references app_users(id),
    voided_at timestamptz,
    reissue_of_invoice_id uuid references invoices(id),
    replaced_by_invoice_id uuid references invoices(id),
    overdue_notified_at timestamptz,
    irn varchar(80),
    cryptographic_stamp_id varchar(200),
    qr_code text,
    ap_submitted_at timestamptz,
    nrs_submission_status varchar(20) not null default 'NOT_SUBMITTED'
        check (nrs_submission_status in ('NOT_SUBMITTED', 'VALIDATED', 'SIGNED', 'TRANSMITTED', 'REJECTED')),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(
        coalesce(number, '') || ' ' || source_ref || ' ' || coalesce(pro_forma_ref, '') || ' ' || buyer_name))) stored,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint invoices_issued_has_number check ((status = 'DRAFT') = (number is null)),
    constraint invoices_paid_le_total check (amount_paid + amount_credited <= tax_inclusive_amount + 0.01)
);
create unique index invoices_number_uq on invoices (tenant_id, number) where number is not null;
create unique index invoices_source_uq on invoices (tenant_id, source_type, source_id) where status <> 'VOID';
create index invoices_customer_idx on invoices (tenant_id, customer_id, status);
create index invoices_location_issue_idx on invoices (tenant_id, location_id, issue_date desc);
create index invoices_open_idx on invoices (tenant_id, due_date) where status = 'ISSUED';
create index invoices_balance_idx on invoices (tenant_id, (tax_inclusive_amount - amount_paid - amount_credited)) where status = 'ISSUED';
create index invoices_correspondent_idx on invoices (tenant_id, correspondent_id);
create index invoices_buyer_trgm on invoices using gin (f_unaccent(lower(buyer_name)) gin_trgm_ops);
create index invoices_search_idx on invoices using gin (search_vector);

create table invoice_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    item_code varchar(40) not null,
    item_name varchar(200) not null,
    item_description varchar(300),
    sellers_item_identification varchar(80),
    quantity numeric(14,3) not null check (quantity > 0),
    unit_name varchar(60) not null,
    nrs_unit_code varchar(10),
    base_quantity numeric(14,3) not null default 1,
    unit_price numeric(14,2) not null check (unit_price >= 0),
    discount_amount numeric(14,2) not null default 0 check (discount_amount >= 0),
    discount_rate numeric(7,4),
    line_net numeric(14,2) not null,
    vat_category_id uuid not null,
    vat_category_code varchar(30) not null,
    nrs_tax_category_code varchar(40) not null references nrs_tax_categories(code),
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    is_service boolean not null default false,
    hsn_code varchar(10),
    hsn_section_code varchar(6),
    product_category varchar(500),
    isic_code varchar(4),
    service_category varchar(300),
    unique (invoice_id, line_no),
    constraint invoice_lines_discount_le_value check (discount_amount <= quantity * unit_price)
);
create index invoice_lines_item_idx on invoice_lines (tenant_id, stock_item_id);

create table invoice_waybill_links (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    waybill_id uuid not null,
    waybill_ref varchar(20) not null,
    waybill_date date not null,
    created_at timestamptz not null default now(),
    unique (invoice_id, waybill_id)
);

-- ---- Rule 14 at the database: an issued invoice's content never changes ----
create function invoices_issued_immutable() returns trigger language plpgsql as $$
declare
    -- search_vector is generated from immutable columns, and a BEFORE trigger sees it as null on NEW
    -- (generated columns are computed after it), so it is left out of the comparison.
    mutable text[] := array['amount_paid', 'amount_credited', 'last_payment_at', 'overdue_notified_at', 'status', 'void_reason',
                            'voided_by_user_id', 'voided_at', 'replaced_by_invoice_id', 'irn', 'cryptographic_stamp_id', 'qr_code',
                            'ap_submitted_at', 'nrs_submission_status', 'search_vector'];
begin
    if tg_op = 'DELETE' then
        if old.status <> 'DRAFT' then raise exception 'Invoice % is issued and can''t be deleted', old.number using errcode = 'check_violation'; end if;
        return old;
    end if;
    if old.status = 'DRAFT' then return new; end if;
    if new.status <> old.status and not (old.status = 'ISSUED' and new.status = 'VOID') then
        raise exception 'Invoice % can''t go from % to %', old.number, old.status, new.status using errcode = 'check_violation';
    end if;
    if (to_jsonb(old) - mutable) <> (to_jsonb(new) - mutable) then
        raise exception 'Invoice % is issued: its content is immutable', old.number using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger invoices_issued_immutable before update or delete on invoices for each row execute function invoices_issued_immutable();

create function invoice_lines_draft_only() returns trigger language plpgsql as $$
declare
    parent_status text;
begin
    select status into parent_status from invoices where id = coalesce(new.invoice_id, old.invoice_id);
    if parent_status is distinct from 'DRAFT' then
        raise exception 'Lines of an issued invoice can''t change' using errcode = 'check_violation';
    end if;
    return coalesce(new, old);
end $$;
create trigger invoice_lines_draft_only before insert or update or delete on invoice_lines for each row execute function invoice_lines_draft_only();

do $$
declare
    t text;
begin
    foreach t in array array['invoices', 'invoice_lines', 'invoice_waybill_links'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('invoicing.invoice.read', 'Invoice history, detail and PDF'),
    ('invoicing.invoice.issue', 'Issue draft invoices; needed (with cashier permissions) to invoice POS sales and pro-formas'),
    ('invoicing.invoice.void', 'Void an invoice, or void and reissue it'),
    ('invoicing.credit-note.issue', 'Issue credit notes'),
    ('invoicing.payment.record', 'Record a payment against an invoice'),
    ('invoicing.delivery.manage', 'E-mail, resend and redirect invoices; bulk export'),
    ('invoicing.receivables.read', 'Outstanding receivables and the Debtors List');

select grant_permission_to_default_roles('invoicing.invoice.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('invoicing.invoice.issue', array['Administrator', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('invoicing.invoice.void', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('invoicing.credit-note.issue', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('invoicing.payment.record', array['Administrator', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('invoicing.delivery.manage', array['Administrator', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('invoicing.receivables.read', array['Administrator', 'Management', 'Accounts Personnel']);
