-- Customers & Distributors — docs/04-customers-distributors.md §2.
-- Every customer type lives in one table; the wallet/deposit ledger is the only place a balance
-- changes (balances on customers are a cache, updated in the same transaction as a ledger row).

-- ---------------------------------------------------------------------------------------------
-- Dealer categories (Platinum / Gold / Silver / Bronze by default)
-- ---------------------------------------------------------------------------------------------
create table dealer_categories (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(60) not null,
    discount_pct numeric(5,2) not null default 0 check (discount_pct >= 0 and discount_pct <= 100),
    suggested_credit_limit numeric(14,2) not null default 0 check (suggested_credit_limit >= 0),
    sort_order int not null default 0,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create unique index dealer_categories_name_uq on dealer_categories (tenant_id, lower(name));
alter table dealer_categories enable row level security;
alter table dealer_categories force row level security;
create policy dealer_categories_scope on dealer_categories
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Correspondents (sales representatives) and their append-only assignment history
-- ---------------------------------------------------------------------------------------------
create table correspondents (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,
    name varchar(160) not null,
    phone varchar(40) not null,
    phone_e164 varchar(20) not null,
    email varchar(320),
    user_id uuid references app_users(id),
    region varchar(60),
    location_id uuid references locations(id),
    responsibilities varchar(300),
    status varchar(20) not null default 'UNASSIGNED' check (status in ('ASSIGNED', 'UNASSIGNED', 'DE_ASSIGNED')),
    assigned_at date,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    search_vector tsvector generated always as (
        to_tsvector('simple', f_unaccent(coalesce(name, '') || ' ' || coalesce(responsibilities, '')))
    ) stored,
    unique (tenant_id, code)
);
create unique index correspondents_user_uq on correspondents (tenant_id, user_id) where user_id is not null;
create index correspondents_name_trgm on correspondents using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index correspondents_status_region_idx on correspondents (tenant_id, status, region);
create index correspondents_search_idx on correspondents using gin (search_vector);
alter table correspondents enable row level security;
alter table correspondents force row level security;
create policy correspondents_scope on correspondents
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table correspondent_assignment_history (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    correspondent_id uuid not null references correspondents(id),
    action varchar(20) not null check (action in ('ASSIGNED', 'REASSIGNED', 'DE_ASSIGNED')),
    region varchar(60),
    location_id uuid references locations(id),
    effective_from date not null,
    note varchar(300),
    performed_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create index cah_correspondent_idx on correspondent_assignment_history (tenant_id, correspondent_id, effective_from desc);
alter table correspondent_assignment_history enable row level security;
alter table correspondent_assignment_history force row level security;
create policy correspondent_assignment_history_scope on correspondent_assignment_history
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Customers (every type)
-- ---------------------------------------------------------------------------------------------
create table customers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,
    type varchar(30) not null check (type in ('AUTHORISED_DEALER', 'SPECIAL_CUSTOMER', 'ORDINARY_CUSTOMER', 'NO_CREDIT_WALKIN')),
    is_temporary boolean not null default false,
    name varchar(200) not null,
    contact_person varchar(160),
    phone varchar(40),
    phone_e164 varchar(20),
    alternate_phone varchar(40),
    email varchar(320),
    tin varchar(40),
    business_description varchar(200),
    street_address varchar(300),
    city varchar(120),
    state_code varchar(5) references nigeria_states(code),
    lga_code varchar(10) references nigeria_lgas(code),
    country_code varchar(2) not null default 'NG',
    location_id uuid references locations(id),
    correspondent_id uuid references correspondents(id),
    dealer_category_id uuid references dealer_categories(id),
    credit_limit numeric(14,2) not null default 0 check (credit_limit >= 0),
    wallet_balance numeric(14,2) not null default 0,
    deposit_balance numeric(14,2) not null default 0,
    lifetime_value numeric(14,2) not null default 0,
    last_purchase_at timestamptz,
    customer_since date not null default current_date,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'WATCH', 'SUSPENDED', 'REMOVED')),
    status_reason varchar(300),
    removed_at timestamptz,
    user_id uuid references app_users(id),
    custom_attributes jsonb not null default '{}'::jsonb,
    search_vector tsvector generated always as (
        to_tsvector('simple', f_unaccent(
            coalesce(name, '') || ' ' || coalesce(contact_person, '') || ' ' || coalesce(business_description, '') || ' ' ||
            coalesce(street_address, '') || ' ' || coalesce(city, '')))
    ) stored,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, code),
    constraint customers_balances_non_negative check (wallet_balance >= 0 and deposit_balance >= 0),
    constraint customers_dealer_category check ((type = 'AUTHORISED_DEALER') = (dealer_category_id is not null)),
    constraint customers_no_credit check (type <> 'NO_CREDIT_WALKIN' or credit_limit = 0),
    constraint customers_deposit_only_special check (type = 'SPECIAL_CUSTOMER' or deposit_balance = 0),
    constraint customers_temporary_type check (not is_temporary or type = 'NO_CREDIT_WALKIN')
);
create unique index customers_dealer_name_uq on customers (tenant_id, lower(name))
    where type = 'AUTHORISED_DEALER' and status <> 'REMOVED';
create unique index customers_temp_phone_uq on customers (tenant_id, phone_e164)
    where is_temporary and status = 'ACTIVE';
create unique index customers_user_uq on customers (tenant_id, user_id) where user_id is not null;
create index customers_type_status_idx on customers (tenant_id, type, status, name);
create index customers_location_idx on customers (tenant_id, location_id, status);
create index customers_correspondent_idx on customers (tenant_id, correspondent_id);
create index customers_category_idx on customers (tenant_id, dealer_category_id);
create index customers_last_purchase_idx on customers (tenant_id, last_purchase_at);
create index customers_phone_idx on customers (tenant_id, phone_e164);
create index customers_email_idx on customers (tenant_id, lower(email));
create index customers_name_trgm on customers using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index customers_contact_trgm on customers using gin (f_unaccent(lower(contact_person)) gin_trgm_ops);
create index customers_search_idx on customers using gin (search_vector);
create index customers_attrs_idx on customers using gin (custom_attributes jsonb_path_ops);
alter table customers enable row level security;
alter table customers force row level security;
create policy customers_scope on customers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Wallet / deposit ledger: append-only; corrections are reversal entries
-- ---------------------------------------------------------------------------------------------
create table customer_ledger_entries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    customer_id uuid not null references customers(id),
    balance_type varchar(10) not null check (balance_type in ('WALLET', 'DEPOSIT')),
    entry_type varchar(30) not null check (entry_type in (
        'FUNDING', 'RETURN_CREDIT', 'COMMISSION_CREDIT', 'CREDIT_NOTE', 'PURCHASE_DEBIT', 'WITHDRAWAL_DEBIT',
        'ADJUSTMENT_CREDIT', 'ADJUSTMENT_DEBIT', 'REVERSAL_CREDIT', 'REVERSAL_DEBIT')),
    direction varchar(6) not null check (direction in ('CREDIT', 'DEBIT')),
    amount numeric(14,2) not null check (amount > 0),
    balance_after numeric(14,2) not null,
    reference varchar(40) not null,
    description varchar(300) not null,
    payment_method varchar(30),
    source_module varchar(30) not null,
    source_type varchar(40) not null,
    source_id uuid,
    location_id uuid references locations(id),
    reverses_entry_id uuid references customer_ledger_entries(id),
    reason varchar(300),
    performed_by_user_id uuid references app_users(id),
    entry_date date not null default current_date,
    created_at timestamptz not null default now(),
    search_vector tsvector generated always as (
        to_tsvector('simple', f_unaccent(reference || ' ' || description))
    ) stored,
    constraint cle_direction_matches_type check (
        (entry_type in ('FUNDING', 'RETURN_CREDIT', 'COMMISSION_CREDIT', 'CREDIT_NOTE', 'ADJUSTMENT_CREDIT', 'REVERSAL_CREDIT') and direction = 'CREDIT') or
        (entry_type in ('PURCHASE_DEBIT', 'WITHDRAWAL_DEBIT', 'ADJUSTMENT_DEBIT', 'REVERSAL_DEBIT') and direction = 'DEBIT')
    ),
    constraint cle_balance_after_non_negative check (balance_after >= 0)
);
create index cle_customer_idx on customer_ledger_entries (tenant_id, customer_id, balance_type, created_at, id);
create index cle_source_idx on customer_ledger_entries (tenant_id, source_module, source_id);
create index cle_reference_idx on customer_ledger_entries (tenant_id, reference);
create index cle_search_idx on customer_ledger_entries using gin (search_vector);
create unique index cle_single_reversal on customer_ledger_entries (reverses_entry_id) where reverses_entry_id is not null;
alter table customer_ledger_entries enable row level security;
alter table customer_ledger_entries force row level security;
create policy customer_ledger_entries_scope on customer_ledger_entries
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Promotions / demotions between customer types
-- ---------------------------------------------------------------------------------------------
create table customer_reclassifications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    customer_id uuid not null references customers(id),
    from_type varchar(30) not null,
    to_type varchar(30) not null,
    direction varchar(10) not null check (direction in ('PROMOTION', 'DEMOTION')),
    reason varchar(40) not null check (reason in (
        'SUSTAINED_SALES_PERFORMANCE', 'MET_PURCHASE_VOLUME', 'COMPLIANCE_CONCERN',
        'PAYMENT_ARREARS', 'PROLONGED_INACTIVITY', 'MANAGEMENT_DECISION')),
    note varchar(300),
    from_dealer_category_id uuid references dealer_categories(id),
    to_dealer_category_id uuid references dealer_categories(id),
    from_credit_limit numeric(14,2) not null,
    to_credit_limit numeric(14,2) not null,
    effective_from date not null,
    status varchar(20) not null check (status in ('SCHEDULED', 'APPLIED', 'CANCELLED')),
    authorised_by_user_id uuid not null references app_users(id),
    applied_at timestamptz,
    created_at timestamptz not null default now(),
    constraint reclass_types_differ check (from_type <> to_type)
);
create index reclass_customer_idx on customer_reclassifications (tenant_id, customer_id, created_at desc);
create index reclass_due_idx on customer_reclassifications (tenant_id, effective_from) where status = 'SCHEDULED';
create unique index reclass_one_scheduled on customer_reclassifications (customer_id) where status = 'SCHEDULED';
alter table customer_reclassifications enable row level security;
alter table customer_reclassifications force row level security;
create policy customer_reclassifications_scope on customer_reclassifications
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Permissions (docs/04 §3) and their default roles, for new and existing tenants
-- ---------------------------------------------------------------------------------------------
insert into permission_catalog (code, description) values
    ('customers.read', 'View customers, dealers and correspondents in scope'),
    ('customers.read.own', 'View only customers whose correspondent is linked to the caller'),
    ('customers.manage', 'Register and edit non-dealer customers; set WATCH/ACTIVE'),
    ('customers.dealers.manage', 'Register and edit dealers; set credit limits; suspend or reactivate customers'),
    ('customers.reclassify', 'Promote or demote customers between types'),
    ('customers.categories.manage', 'Edit dealer categories'),
    ('customers.correspondents.manage', 'Register correspondents; assign, reassign, de-assign; link customers'),
    ('customers.ledger.read', 'View customer wallet and deposit ledgers'),
    ('customers.wallet.adjust', 'Post wallet adjustments and reversals'),
    ('customers.self.read', 'A dealer or customer user reads their own record and ledger');

select grant_permission_to_default_roles('customers.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('customers.read.own', array['Administrator', 'Correspondent']);
select grant_permission_to_default_roles('customers.manage', array['Administrator', 'Accounts Personnel', 'Cashier']);
select grant_permission_to_default_roles('customers.dealers.manage', array['Administrator', 'Accounts Personnel']);
select grant_permission_to_default_roles('customers.reclassify', array['Administrator', 'Management']);
select grant_permission_to_default_roles('customers.categories.manage', array['Administrator']);
select grant_permission_to_default_roles('customers.correspondents.manage', array['Administrator', 'Management']);
select grant_permission_to_default_roles('customers.ledger.read', array['Administrator', 'Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('customers.wallet.adjust', array['Administrator']);
select grant_permission_to_default_roles('customers.self.read', array['Administrator', 'Dealer']);

-- Default dealer categories for tenants that already exist (new tenants get them at registration).
-- created_by: the tenant's earliest member (registration creates the admin first).
do $$
declare
    t record;
    creator uuid;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        select user_id into creator from tenant_memberships where tenant_id = t.id order by created_at limit 1;
        if creator is not null then
            insert into dealer_categories (tenant_id, name, discount_pct, suggested_credit_limit, sort_order, created_by_user_id) values
                (t.id, 'Platinum', 5.00, 3000000.00, 4, creator),
                (t.id, 'Gold', 3.50, 1500000.00, 3, creator),
                (t.id, 'Silver', 2.00, 500000.00, 2, creator),
                (t.id, 'Bronze', 0.00, 200000.00, 1, creator);
        end if;
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;
