-- Module 12 (Communication), slice 3: commission payments (docs/12 §2.3, §4.3). Running a commission
-- is not paying it — payments are separate, partial-allowed rows that move money through Account
-- (cash/bank) or the customer wallet. Outstanding = earned − Σ PAID. Reports arrive alongside.

create table commission_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,
    run_id uuid not null,
    beneficiary_type varchar(14) not null check (beneficiary_type in ('DEALER','CORRESPONDENT')),
    beneficiary_id uuid not null,
    beneficiary_name varchar(200) not null,
    amount numeric(14,2) not null check (amount > 0),
    method varchar(10) not null check (method in ('WALLET','CASH','BANK')),
    cash_account_id uuid,
    bank_account_id uuid,
    paid_via varchar(120),
    account_cash_transaction_id uuid,
    account_bank_transaction_id uuid,
    customer_ledger_entry_id uuid,
    paid_at timestamptz not null default now(),
    paid_by_user_id uuid not null references app_users(id),
    note varchar(300),
    status varchar(10) not null default 'PAID' check (status in ('PAID','REVERSED')),
    reversed_reason varchar(300),
    reversed_by_user_id uuid references app_users(id),
    constraint cp_run_fkey foreign key (tenant_id, run_id) references commission_runs (tenant_id, id),
    unique (tenant_id, ref)
);
alter table commission_payments enable row level security;
alter table commission_payments force row level security;
create policy commission_payments_scope on commission_payments
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create index cp_beneficiary_idx on commission_payments (tenant_id, beneficiary_type, beneficiary_id, run_id);
create index cp_run_idx on commission_payments (tenant_id, run_id);

insert into permission_catalog (code, description) values
    ('communication.payments.manage', 'Pay and reverse commission'),
    ('communication.reports.read', 'Read the Commission and Correspondents reports');

select grant_permission_to_default_roles('communication.payments.manage', array['Accounts Personnel']);
select grant_permission_to_default_roles('communication.reports.read', array['Administrator', 'Management', 'Accounts Personnel']);
