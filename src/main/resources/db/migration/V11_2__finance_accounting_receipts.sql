-- =============================================================================================
-- Finance & Accounting, slice 3 (docs/07 §2.7, §2.10, §2.12, §4.10–4.13, §4.17–4.18): payment
-- receipts (all money received, with POS / transfer / cheque confirmation), wallet and deposit
-- funding and withdrawals, other income, and VAT control per location (effective-dated).
-- =============================================================================================

create table payment_receipts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    kind varchar(10) not null default 'RECEIPT' check (kind in ('RECEIPT', 'REFUND')),
    location_id uuid references locations(id),
    cash_account_id uuid references cash_accounts(id),
    cashier_user_id uuid not null references app_users(id),
    customer_id uuid,                                   -- customers.id (cross-module plain uuid)
    customer_name varchar(200),
    channel varchar(20) not null check (channel in ('CASH', 'POS', 'FUND_TRANSFER', 'CHEQUE', 'WALLET', 'DEPOSIT')),
    amount numeric(14,2) not null check (amount > 0),
    source_module varchar(30) not null,
    source_type varchar(30) not null check (source_type in ('POS_SALE', 'INVOICE_PAYMENT', 'WALLET_FUNDING', 'DEPOSIT_FUNDING', 'SALE_REFUND', 'DEPOSIT_REFUND', 'OTHER_INCOME')),
    source_id uuid not null,
    source_ref varchar(40) not null,
    income_category varchar(20) check (income_category is null or income_category in ('BY_PRODUCT_SALES', 'SCRAP_SALES', 'RENTAL', 'INTEREST', 'OTHER')),
    description varchar(300),
    receiving_bank_account_id uuid references bank_accounts(id),
    terminal_ref varchar(40),
    payer_reference varchar(80),
    customer_ledger_entry_id uuid,
    confirmation_status varchar(20) not null check (confirmation_status in ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'DECLINED')),
    reviewed_by_user_id uuid references app_users(id),
    reviewed_at timestamptz,
    decline_note varchar(300),
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    status varchar(20) not null default 'ISSUED' check (status in ('ISSUED', 'VOIDED')),
    void_reason varchar(300),
    voided_by_user_id uuid references app_users(id),
    voided_at timestamptz,
    received_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(ref || ' ' || source_ref || ' ' || coalesce(customer_name, '') || ' ' || coalesce(payer_reference, '') || ' ' || coalesce(description, '')))) stored,
    unique (tenant_id, ref),
    constraint pr_other_income_category check ((source_type = 'OTHER_INCOME') = (income_category is not null))
);
create index pr_location_time_idx on payment_receipts (tenant_id, location_id, received_at desc);
create index pr_pending_idx on payment_receipts (tenant_id, location_id, received_at) where confirmation_status = 'PENDING';
create index pr_cashier_idx on payment_receipts (tenant_id, cashier_user_id, received_at);
create index pr_source_idx on payment_receipts (tenant_id, source_module, source_id);
create index pr_source_ref_idx on payment_receipts (tenant_id, lower(source_ref));
create index pr_customer_name_trgm on payment_receipts using gin (f_unaccent(lower(customer_name)) gin_trgm_ops);
create index pr_search_idx on payment_receipts using gin (search_vector);

create table wallet_fundings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    customer_id uuid not null,
    customer_code varchar(20) not null,
    customer_name varchar(200) not null,
    balance_type varchar(10) not null check (balance_type in ('WALLET', 'DEPOSIT')),
    channel varchar(20) not null check (channel in ('CASH', 'POS', 'FUND_TRANSFER', 'CHEQUE')),
    amount numeric(14,2) not null check (amount > 0),
    location_id uuid references locations(id),
    payment_receipt_id uuid not null references payment_receipts(id),
    customer_ledger_entry_id uuid,
    status varchar(25) not null check (status in ('AWAITING_CONFIRMATION', 'CONFIRMED', 'DECLINED', 'VOIDED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create index wf_customer_idx on wallet_fundings (tenant_id, customer_id, created_at desc);

create table wallet_withdrawals (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    customer_id uuid not null,
    customer_code varchar(20) not null,
    customer_name varchar(200) not null,
    balance_type varchar(10) not null check (balance_type in ('WALLET', 'DEPOSIT')),
    reason varchar(40) not null check (reason in ('DEALER_REQUEST', 'EXCESS_COMMISSION_RECOVERY', 'OVER_CREDIT_CORRECTION', 'DEPOSIT_REFUND')),
    initiated_by varchar(10) not null check (initiated_by in ('DEALER', 'COMPANY')),
    amount numeric(14,2) not null check (amount > 0),
    balance_before numeric(14,2) not null,
    balance_after numeric(14,2) not null check (balance_after >= 0),
    payout_kind varchar(10) not null check (payout_kind in ('CASH', 'BANK', 'NONE')),
    payout_cash_account_id uuid references cash_accounts(id),
    payout_bank_account_id uuid references bank_accounts(id),
    paid_via varchar(120),
    otp_challenge_id uuid references otp_challenges(id),
    authorised_by_user_id uuid references app_users(id),
    note varchar(300),
    withdrawal_date date not null,
    location_id uuid references locations(id),
    customer_ledger_entry_id uuid not null,
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    audit_status varchar(20) not null default 'COMPLETED' check (audit_status in ('COMPLETED', 'UNDER_REVIEW', 'REVERSED')),
    reversal_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    reversed_at timestamptz,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint ww_payout_account check (
        (payout_kind = 'CASH' and payout_cash_account_id is not null and payout_bank_account_id is null) or
        (payout_kind = 'BANK' and payout_bank_account_id is not null and payout_cash_account_id is null) or
        (payout_kind = 'NONE' and payout_cash_account_id is null and payout_bank_account_id is null))
);
create index ww_customer_idx on wallet_withdrawals (tenant_id, customer_id, created_at desc);
-- An OTP authorises one withdrawal, once.
create unique index ww_otp_once on wallet_withdrawals (otp_challenge_id) where otp_challenge_id is not null;

-- ---- VAT Control per location (§2.12) ----
create table location_vat_settings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),
    vat_enabled boolean not null,
    vat_rate numeric(5,2) not null check (vat_rate >= 0 and vat_rate <= 100),
    vat_registration_number varchar(60),
    effective_from date not null,
    note varchar(300),
    updated_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, location_id, effective_from)
);
create index lvs_location_idx on location_vat_settings (tenant_id, location_id, effective_from desc);

-- Each showroom/factory's current values, effective from its creation date. Before RLS is enabled,
-- so it reaches every tenant whatever role runs the migration.
insert into location_vat_settings (tenant_id, location_id, vat_enabled, vat_rate, vat_registration_number, effective_from, updated_by_user_id)
select tenant_id, id, vat_enabled, vat_rate, vat_registration_number, created_at::date, created_by_user_id
from locations where type in ('SHOWROOM', 'FACTORY');

-- ---- OTP context (docs/03 §9.4): what a challenge authorises, e.g. { "amount": 120000, "targetLabel": "…" } ----
alter table otp_challenges add column context jsonb not null default '{}'::jsonb;

do $$
declare
    t text;
begin
    foreach t in array array['payment_receipts', 'wallet_fundings', 'wallet_withdrawals', 'location_vat_settings'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('accounting.receipts.read', 'See receipts, Receipt Track and the POS / transfer confirmation queue (cashiers: their own)'),
    ('accounting.receipts.confirm', 'Approve or decline POS, fund-transfer and cheque receipts'),
    ('accounting.receipts.void', 'Void a wallet funding or other-income receipt'),
    ('accounting.receipts.record', 'Record other income'),
    ('accounting.wallet.fund', 'Fund dealer wallets and special-customer deposits'),
    ('accounting.wallet.withdraw', 'Pay out dealer-requested wallet withdrawals (OTP-gated) and deposit refunds'),
    ('accounting.wallet.recover', 'Company-initiated wallet recoveries and corrections; review withdrawals'),
    ('accounting.tenderable.read', 'See the Tenderable reports');

select grant_permission_to_default_roles('accounting.receipts.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('accounting.receipts.confirm', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.receipts.void', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.receipts.record', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.wallet.fund', array['Administrator', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('accounting.wallet.withdraw', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.wallet.recover', array['Administrator', 'Management']);
select grant_permission_to_default_roles('accounting.tenderable.read', array['Administrator', 'Management', 'Accounts Personnel']);
