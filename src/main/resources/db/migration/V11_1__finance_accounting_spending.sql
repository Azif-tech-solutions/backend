-- =============================================================================================
-- Finance & Accounting, slice 2 (docs/07 §2.5–2.6, §2.8–2.9): petty cash (fundings, payments,
-- allocations to branches), running costs with approval, funds sent to branches, and cash
-- lodgements. Every one is a document that posts through the slice-1 ledgers.
-- =============================================================================================

create table petty_cash_fundings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    petty_cash_account_id uuid not null references cash_accounts(id),
    source_kind varchar(10) not null check (source_kind in ('BANK', 'CASH')),
    source_bank_account_id uuid references bank_accounts(id),
    source_cash_account_id uuid references cash_accounts(id),
    amount numeric(14,2) not null check (amount > 0),
    funding_date date not null,
    transfer_or_cheque_reference varchar(80),
    authorised_by varchar(160),
    description varchar(300),
    status varchar(20) not null default 'POSTED' check (status in ('POSTED', 'REVERSED')),
    reversal_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    reversed_at timestamptz,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint pcf_source check ((source_kind = 'BANK') = (source_bank_account_id is not null) and (source_kind = 'CASH') = (source_cash_account_id is not null)),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(description, '')))) stored
);
create index pcf_account_idx on petty_cash_fundings (tenant_id, petty_cash_account_id, funding_date desc);
create index pcf_search_idx on petty_cash_fundings using gin (search_vector);

create table petty_cash_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    petty_cash_account_id uuid not null references cash_accounts(id),
    expense_category_id uuid not null references expense_categories(id),
    amount numeric(14,2) not null check (amount > 0),
    paid_to varchar(160) not null,
    payment_date date not null,
    approved_by varchar(160),            -- as captured on the screen (a named person's verbal approval)
    description varchar(300),
    status varchar(20) not null default 'POSTED' check (status in ('POSTED', 'REVERSED')),
    reversal_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    reversed_at timestamptz,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create index pcp_account_idx on petty_cash_payments (tenant_id, petty_cash_account_id, payment_date desc);
create index pcp_paid_to_trgm on petty_cash_payments using gin (f_unaccent(lower(paid_to)) gin_trgm_ops);

create table petty_cash_allocations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    source_kind varchar(10) not null check (source_kind in ('BANK', 'CASH')),
    source_bank_account_id uuid references bank_accounts(id),
    source_cash_account_id uuid references cash_accounts(id),
    destination_location_id uuid not null references locations(id),
    destination_petty_cash_account_id uuid not null references cash_accounts(id),
    amount numeric(14,2) not null check (amount > 0),
    purpose varchar(300) not null,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING', 'ACCEPTED', 'REJECTED')),
    responded_by_user_id uuid references app_users(id),
    responded_at timestamptz,
    note varchar(300),
    sent_by_user_id uuid not null references app_users(id),
    sent_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint ppc_source check ((source_kind = 'BANK') = (source_bank_account_id is not null) and (source_kind = 'CASH') = (source_cash_account_id is not null)),
    constraint ppc_rejection_note check (status <> 'REJECTED' or note is not null)
);
create index ppc_destination_idx on petty_cash_allocations (tenant_id, destination_location_id, status, sent_at desc);

create table operating_costs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20),                     -- RC-n, assigned at submission; drafts have none
    location_id uuid references locations(id),
    expense_category_id uuid not null references expense_categories(id),
    payee varchar(160) not null,
    amount numeric(14,2) not null check (amount > 0),
    payment_method varchar(20) not null check (payment_method in ('PETTY_CASH', 'CASH', 'BANK_TRANSFER', 'CHEQUE')),
    paying_cash_account_id uuid references cash_accounts(id),
    paying_bank_account_id uuid references bank_accounts(id),
    cheque_number varchar(30),
    invoice_or_receipt_no varchar(80),
    input_vat_amount numeric(14,2) not null default 0 check (input_vat_amount >= 0 and input_vat_amount < amount),
    supplier_tin varchar(40),
    payment_date date not null,
    description varchar(300),
    exceeds_monthly_cap boolean not null default false,
    status varchar(20) not null default 'DRAFT' check (status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'REVERSED')),
    rejection_reason varchar(300),
    submitted_by_user_id uuid references app_users(id),
    submitted_at timestamptz,
    approved_by_user_id uuid references app_users(id),
    approved_at timestamptz,
    reversal_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    reversed_at timestamptz,
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(description, '')))) stored,
    constraint oc_paying_account check (
        (payment_method in ('PETTY_CASH', 'CASH') and paying_cash_account_id is not null and paying_bank_account_id is null) or
        (payment_method in ('BANK_TRANSFER', 'CHEQUE') and paying_bank_account_id is not null and paying_cash_account_id is null)),
    constraint oc_ref_after_draft check (status = 'DRAFT' or ref is not null),
    constraint oc_vat_needs_tin check (input_vat_amount = 0 or supplier_tin is not null)
);
create unique index operating_costs_ref_uq on operating_costs (tenant_id, ref) where ref is not null;
create index operating_costs_status_idx on operating_costs (tenant_id, status, payment_date desc);
create index operating_costs_category_idx on operating_costs (tenant_id, expense_category_id, payment_date);
create index operating_costs_payee_trgm on operating_costs using gin (f_unaccent(lower(payee)) gin_trgm_ops);
create index operating_costs_search_idx on operating_costs using gin (search_vector);

create table fund_disbursements (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    channel varchar(20) not null check (channel in ('BANK_TRANSFER', 'CASH')),
    source_bank_account_id uuid references bank_accounts(id),
    source_cash_account_id uuid references cash_accounts(id),
    destination_location_id uuid not null references locations(id),
    destination_bank_account_id uuid references bank_accounts(id),
    destination_cash_account_id uuid references cash_accounts(id),
    purpose varchar(300) not null,
    sent_amount numeric(14,2) not null check (sent_amount > 0),
    sent_by_user_id uuid not null references app_users(id),
    sent_at timestamptz not null default now(),
    received_amount numeric(14,2),
    shortfall_amount numeric(14,2),
    shortfall_reason varchar(300),
    bank_credit_reference varchar(80),
    date_received date,
    rejection_reason varchar(300),
    confirmed_by_user_id uuid references app_users(id),
    confirmed_at timestamptz,
    status varchar(25) not null default 'AWAITING' check (status in ('AWAITING', 'CONFIRMED', 'PARTIALLY_CONFIRMED', 'REJECTED')),
    unique (tenant_id, ref),
    constraint fd_channel_accounts check (
        (channel = 'BANK_TRANSFER' and source_bank_account_id is not null and destination_bank_account_id is not null and source_cash_account_id is null and destination_cash_account_id is null) or
        (channel = 'CASH' and source_cash_account_id is not null and destination_cash_account_id is not null and source_bank_account_id is null and destination_bank_account_id is null)),
    constraint fd_received_le_sent check (received_amount is null or (received_amount >= 0 and received_amount <= sent_amount))
);
create index fd_destination_idx on fund_disbursements (tenant_id, destination_location_id, status, sent_at desc);

create table cash_lodgements (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    location_id uuid not null references locations(id),
    cash_account_id uuid not null references cash_accounts(id),
    deposit_date date not null,
    takings numeric(14,2) not null check (takings >= 0),
    banked numeric(14,2) not null check (banked > 0),
    retained numeric(14,2) not null check (retained >= 0),
    bank_account_id uuid not null references bank_accounts(id),
    teller_reference varchar(60),
    deposited_by_user_id uuid not null references app_users(id),
    status varchar(25) not null default 'AWAITING_CONFIRMATION' check (status in ('AWAITING_CONFIRMATION', 'CONFIRMED', 'REJECTED')),
    confirmed_by_user_id uuid references app_users(id),
    confirmed_at timestamptz,
    rejection_reason varchar(300),
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint lodgement_balances check (takings = banked + retained)
);
create index lodgements_location_idx on cash_lodgements (tenant_id, location_id, status, deposit_date desc);

do $$
declare
    t text;
begin
    foreach t in array array['petty_cash_fundings', 'petty_cash_payments', 'petty_cash_allocations', 'operating_costs',
                             'fund_disbursements', 'cash_lodgements'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('accounting.petty-cash.fund', 'Fund petty cash and send petty cash to a branch'),
    ('accounting.petty-cash.pay', 'Pay from petty cash'),
    ('accounting.petty-cash.respond', 'Accept or reject petty cash sent to your location'),
    ('accounting.running-costs.submit', 'Create and submit running costs'),
    ('accounting.running-costs.approve', 'Approve, reject and reverse running costs'),
    ('accounting.funds.send', 'Disburse funds to a branch or factory'),
    ('accounting.funds.confirm', 'Confirm or reject funds received at your location'),
    ('accounting.lodgements.manage', 'Record cash lodgements to the bank'),
    ('accounting.lodgements.confirm', 'Confirm lodgements against the bank');

select grant_permission_to_default_roles('accounting.petty-cash.fund', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.petty-cash.pay', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.petty-cash.respond', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.running-costs.submit', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.running-costs.approve', array['Administrator', 'Management']);
select grant_permission_to_default_roles('accounting.funds.send', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.funds.confirm', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.lodgements.manage', array['Administrator', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('accounting.lodgements.confirm', array['Administrator', 'Accounts Personnel']);

-- accounting.manage only guarded the mock endpoints this slice removes (§1.3-7): drop it outright.
do $$
declare
    t record;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        delete from role_permissions where permission_id in (select id from permission_catalog where code = 'accounting.manage');
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;
delete from permission_catalog where code = 'accounting.manage';
