-- =============================================================================================
-- Electronic Invoicing, slice 2 (docs/08 §2.4–2.6, §4.5–4.11): payments against invoices (each one
-- an Account receipt), credit notes, and e-mail deliveries through the notification outbox.
-- Receivables and the Debtors List are read over these tables; they add none of their own.
-- =============================================================================================

create table invoice_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    method varchar(20) not null check (method in ('CASH', 'POS', 'FUND_TRANSFER', 'CHEQUE', 'WALLET', 'DEPOSIT')),
    amount numeric(14,2) not null check (amount > 0),
    payment_receipt_id uuid not null,                    -- account payment_receipts.id (cross-module plain uuid)
    receipt_ref varchar(20) not null,
    receipt_status varchar(20) not null default 'ACTIVE' check (receipt_status in ('ACTIVE', 'DECLINED', 'VOIDED')),
    customer_ledger_entry_id uuid,                       -- WALLET / DEPOSIT: the Customers debit that paid (reversed with the payment)
    nrs_payment_means_code varchar(3) references nrs_payment_means_codes(code),
    paid_at timestamptz not null,
    recorded_by_user_id uuid not null references app_users(id),
    source varchar(20) not null check (source in ('POS_CHECKOUT', 'RECORDED', 'REISSUE_TRANSFER')),
    reversal_reason varchar(300),
    created_at timestamptz not null default now()
);
create index invoice_payments_invoice_idx on invoice_payments (tenant_id, invoice_id);
create index invoice_payments_receipt_idx on invoice_payments (tenant_id, payment_receipt_id);
create unique index invoice_payments_receipt_uq on invoice_payments (payment_receipt_id) where source <> 'REISSUE_TRANSFER';

create table credit_notes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    number varchar(20) not null,
    invoice_type_code varchar(3) not null default '380' references nrs_invoice_type_codes(code),
    invoice_id uuid not null references invoices(id),
    reason varchar(40) not null check (reason in ('PRICING_ERROR', 'OVERCHARGE', 'DISCOUNT_GRANTED_AFTER_ISSUE', 'DUPLICATE_BILLING', 'GOODWILL', 'OTHER')),
    reason_note varchar(300) not null,
    line_extension_amount numeric(14,2) not null,
    vat_total numeric(14,2) not null,
    total numeric(14,2) not null check (total > 0),
    applied_to_invoice numeric(14,2) not null check (applied_to_invoice >= 0),
    excess_refunded numeric(14,2) not null default 0 check (excess_refunded >= 0),
    excess_customer_ledger_entry_id uuid,
    issued_by_user_id uuid not null references app_users(id),
    issued_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(number || ' ' || reason_note))) stored,
    unique (tenant_id, number),
    constraint credit_notes_split check (applied_to_invoice + excess_refunded = total)
);
create index credit_notes_invoice_idx on credit_notes (tenant_id, invoice_id);
create index credit_notes_issued_idx on credit_notes (tenant_id, issued_at desc);
create index credit_notes_search_idx on credit_notes using gin (search_vector);

create table credit_note_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    credit_note_id uuid not null references credit_notes(id),
    invoice_line_id uuid not null references invoice_lines(id),
    quantity numeric(14,3) check (quantity is null or quantity > 0),
    amount numeric(14,2) check (amount is null or amount > 0),
    line_net numeric(14,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    constraint cnl_one_basis check ((quantity is null) <> (amount is null))
);
create index credit_note_lines_line_idx on credit_note_lines (tenant_id, invoice_line_id);

-- A credit note is a tax document like an invoice: once written it never changes or disappears.
create function credit_notes_immutable() returns trigger language plpgsql as $$
begin
    raise exception '% is issued and can''t be changed or deleted', tg_table_name using errcode = 'check_violation';
end $$;
create trigger credit_notes_immutable before update or delete on credit_notes for each row execute function credit_notes_immutable();
create trigger credit_note_lines_immutable before update or delete on credit_note_lines for each row execute function credit_notes_immutable();

create table invoice_deliveries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    recipient_email varchar(320) not null,
    template varchar(20) not null check (template in ('STANDARD', 'REMINDER', 'STATEMENT')),
    message varchar(1000),
    notification_outbox_id uuid not null references notification_outbox(id),
    trigger varchar(20) not null check (trigger in ('ON_ISSUE', 'MANUAL', 'REDIRECT', 'OVERDUE_REMINDER', 'RETRY')),
    requested_by_user_id uuid references app_users(id),
    requested_at timestamptz not null default now()
);
create index invoice_deliveries_invoice_idx on invoice_deliveries (tenant_id, invoice_id, requested_at desc);
create index invoice_deliveries_outbox_idx on invoice_deliveries (notification_outbox_id);

do $$
declare
    t text;
begin
    foreach t in array array['invoice_payments', 'credit_notes', 'credit_note_lines', 'invoice_deliveries'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;
