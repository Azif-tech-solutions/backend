-- =============================================================================================
-- Finance & Accounting, slice 1 (docs/07-finance-accounting.md §2.1–2.4, §2.11): the two money
-- ledgers (bank and cash), the accounts they belong to, expense categories and exchange rates.
-- Every later money document (petty cash, running costs, receipts, lodgements, …) posts to these
-- ledgers; statements only read them.
-- =============================================================================================

-- ---- Expense categories (Expenses / Payment List Guide, §2.4) ----
create table expense_categories (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,
    name varchar(160) not null,
    description varchar(300),
    account_head varchar(20) not null check (account_head in ('UTILITIES', 'LOGISTICS', 'MAINTENANCE', 'ADMINISTRATIVE', 'FINANCE')),
    monthly_cap numeric(14,2) check (monthly_cap is null or monthly_cap > 0),
    petty_cash_allowed boolean not null default true,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by_user_id uuid references app_users(id),   -- null for the categories seeded at tenant creation
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index expense_categories_name_uq on expense_categories (tenant_id, lower(name));
create index expense_categories_name_trgm on expense_categories using gin (f_unaccent(lower(name)) gin_trgm_ops);

-- The PRD's list, for every existing tenant (new tenants get it from the Account TenantProvisioner).
-- Done before RLS is enabled so it reaches every tenant whatever role runs the migration.
insert into expense_categories (tenant_id, code, name, account_head, petty_cash_allowed)
select t.id, 'EX-' || c.n, c.name, c.head, c.petty
from tenants t
cross join (values
    (1, 'Electrical Bills', 'UTILITIES', true),
    (2, 'Transportation', 'LOGISTICS', true),
    (3, 'Loading Charges', 'LOGISTICS', true),
    (4, 'Offloading Charges', 'LOGISTICS', true),
    (5, 'Maintenance Expenses', 'MAINTENANCE', true),
    (6, 'Fuel Expenses', 'LOGISTICS', true),
    (7, 'Communication Expenses', 'ADMINISTRATIVE', true),
    (8, 'Office Supplies', 'ADMINISTRATIVE', true),
    (9, 'Other Operational Costs', 'ADMINISTRATIVE', true),
    (10, 'Bank Charges', 'FINANCE', false)
) as c(n, name, head, petty);

-- Keep the EX- sequence past the seeded codes (document_sequences has forced RLS, so scope each tenant).
do $$
declare
    t record;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into document_sequences (tenant_id, prefix, next_value) values (t.id, 'EX', 10)
        on conflict (tenant_id, prefix) do update set next_value = greatest(document_sequences.next_value, 10);
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;

-- ---- Bank accounts and the bank ledger (§2.1–2.2) ----
create table bank_accounts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,
    bank_name varchar(160) not null,
    branch varchar(120),
    account_name varchar(160) not null,
    account_number varchar(30) not null,
    account_type varchar(20) not null check (account_type in ('CURRENT', 'COLLECTIONS', 'PAYROLL', 'SAVINGS', 'DOMICILIARY')),
    currency_code varchar(3) not null default 'NGN',
    sort_code_or_bic varchar(40),
    authorised_signatory varchar(160),
    relationship_manager varchar(160),
    date_opened date,
    opening_balance numeric(14,2) not null default 0,
    opening_balance_date date not null default current_date,
    -- Cache of opening + credits − debits; written only together with a bank_transactions insert.
    -- No >= 0 check: an overdraft is a real bank state.
    current_balance numeric(14,2) not null default 0,
    location_id uuid references locations(id),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'DORMANT')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index bank_accounts_number_uq on bank_accounts (tenant_id, account_number, lower(bank_name));
create index bank_accounts_name_trgm on bank_accounts using gin (f_unaccent(lower(bank_name || ' ' || account_name || ' ' || coalesce(branch, ''))) gin_trgm_ops);

create table bank_transactions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    bank_account_id uuid not null references bank_accounts(id),
    entry_type varchar(6) not null check (entry_type in ('CREDIT', 'DEBIT')),
    amount numeric(14,2) not null check (amount > 0),
    -- The account balance right after this entry was posted (posting order). Statements show a
    -- running balance in date order, which differs only for back-dated entries.
    balance_after numeric(14,2) not null,
    reference varchar(40) not null,
    description varchar(300) not null,
    source_type varchar(30) not null check (source_type in (
        'OPENING', 'CASH_LODGEMENT', 'PETTY_CASH_FUNDING', 'RUNNING_COST', 'RECEIPT_CONFIRMED', 'RECEIPT_REVERSED',
        'FUND_DISBURSEMENT_OUT', 'FUND_DISBURSEMENT_IN', 'FUND_DISBURSEMENT_RETURN', 'PETTY_CASH_ALLOCATION_OUT',
        'PETTY_CASH_ALLOCATION_RETURN', 'WALLET_WITHDRAWAL', 'DEPOSIT_REFUND', 'COMMISSION_PAYOUT', 'MANUAL')),
    source_id uuid,
    expense_category_id uuid references expense_categories(id),
    entry_date date not null,
    created_by_user_id uuid references app_users(id),
    created_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(description, '')))) stored
);
create index bank_tx_account_idx on bank_transactions (tenant_id, bank_account_id, entry_date, created_at, id);
create index bank_tx_source_idx on bank_transactions (tenant_id, source_type, source_id);
create index bank_tx_reference_idx on bank_transactions (tenant_id, lower(reference));
create index bank_tx_category_idx on bank_transactions (tenant_id, expense_category_id, entry_date) where expense_category_id is not null;
create index bank_tx_search_idx on bank_transactions using gin (search_vector);

-- ---- Cash accounts and the cash ledger (§2.3) ----
create table cash_accounts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(30) not null,
    name varchar(120) not null,
    kind varchar(20) not null check (kind in ('TILL', 'PETTY_CASH', 'VAULT')),
    location_id uuid references locations(id),
    currency_code varchar(3) not null default 'NGN',
    opening_balance numeric(14,2) not null default 0,
    current_balance numeric(14,2) not null default 0,
    float_limit numeric(14,2) check (float_limit is null or float_limit > 0),
    pos_terminal_ref varchar(40),
    pos_settlement_bank_account_id uuid references bank_accounts(id),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code),
    constraint cash_accounts_non_negative check (current_balance >= 0)   -- you cannot have less than no cash
);
create index cash_accounts_name_trgm on cash_accounts using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index cash_accounts_location_idx on cash_accounts (tenant_id, location_id, kind);

create table cash_transactions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    cash_account_id uuid not null references cash_accounts(id),
    direction varchar(3) not null check (direction in ('IN', 'OUT')),
    amount numeric(14,2) not null check (amount > 0),
    balance_after numeric(14,2) not null check (balance_after >= 0),
    reference varchar(40) not null,
    remark varchar(300) not null,
    source_type varchar(30) not null check (source_type in (
        'OPENING', 'RECEIPT', 'REFUND', 'CASH_LODGEMENT', 'PETTY_CASH_FUNDING', 'PETTY_CASH_PAYMENT', 'RUNNING_COST',
        'FUND_DISBURSEMENT_OUT', 'FUND_DISBURSEMENT_IN', 'FUND_DISBURSEMENT_RETURN',
        'PETTY_CASH_ALLOCATION_OUT', 'PETTY_CASH_ALLOCATION_IN', 'PETTY_CASH_ALLOCATION_RETURN',
        'WALLET_WITHDRAWAL', 'DEPOSIT_REFUND', 'COMMISSION_PAYOUT', 'CASH_COUNT_ADJUSTMENT')),
    source_id uuid,
    expense_category_id uuid references expense_categories(id),
    entry_date date not null,
    created_by_user_id uuid references app_users(id),
    created_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', f_unaccent(coalesce(remark, '')))) stored
);
create index cash_tx_account_idx on cash_transactions (tenant_id, cash_account_id, entry_date, created_at, id);
create index cash_tx_source_idx on cash_transactions (tenant_id, source_type, source_id);
create index cash_tx_reference_idx on cash_transactions (tenant_id, lower(reference));
create index cash_tx_category_idx on cash_transactions (tenant_id, expense_category_id, entry_date) where expense_category_id is not null;
create index cash_tx_search_idx on cash_transactions using gin (search_vector);

-- ---- Exchange rates (§2.11): append-only; each transaction stores the rate it used ----
create table exchange_rate_publications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    published_by_user_id uuid not null references app_users(id),
    published_at timestamptz not null default now(),
    unique (tenant_id, ref)
);

create table exchange_rates (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    publication_id uuid not null references exchange_rate_publications(id),
    currency_code varchar(3) not null,
    buy_rate numeric(14,6) not null check (buy_rate > 0),    -- NGN per 1 unit of the currency
    sell_rate numeric(14,6) not null check (sell_rate > 0),
    published_at timestamptz not null,
    constraint fx_sell_ge_buy check (sell_rate >= buy_rate)
);
create index exchange_rates_lookup_idx on exchange_rates (tenant_id, currency_code, published_at desc);

-- ---- Row-level security: every table is scoped to the current tenant ----
do $$
declare
    t text;
begin
    foreach t in array array['expense_categories', 'bank_accounts', 'bank_transactions', 'cash_accounts', 'cash_transactions',
                             'exchange_rate_publications', 'exchange_rates'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3). accounting.manage stays until the remaining mock endpoints go (slice 2). ----
insert into permission_catalog (code, description) values
    ('accounting.bank.manage', 'Register and edit bank accounts, change their status, post manual bank entries'),
    ('accounting.cash-accounts.manage', 'Register tills, petty-cash boxes and vaults; record cash counts'),
    ('accounting.expense-categories.manage', 'Manage the expense / payment list guide'),
    ('accounting.fx.manage', 'Publish exchange rates');

select grant_permission_to_default_roles('accounting.bank.manage', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.cash-accounts.manage', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.expense-categories.manage', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('accounting.fx.manage', array['Administrator', 'Accounts Personnel']);
