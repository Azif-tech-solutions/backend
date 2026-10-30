-- =============================================================================================
-- Audit & Compliance, slice 1 (docs/10): the read layer — Waybill History, Expenses/Payment
-- Reports, Stock Movement History, Wallet Withdrawal History, the Activity log (§4.7, which reads
-- the existing domain_events table — no schema here) — and the one owned table these need: the
-- audit-review state a reviewer can set on any record (§2.4, §4.11).
--
-- The module display name is "Audit & Compliance"; the owner package is com.zifiq.history.
-- The design doc numbered this migration V14; V14/V14_x and V15_x were taken by debtor balances,
-- the performance indexes and Sales & POS, so it lands at V16 (an unapplied migration is free to
-- renumber; docs/README.md records the convention).
-- =============================================================================================

-- ---- audit_reviews (§2.4): one current review state per record, keyed by (type, record id) ----
-- record_id points at another module's record, so it is a plain uuid (no cross-module FK). The
-- trail of changes lives in the activity log as history.review.updated events (§4.11).
create table audit_reviews (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    record_type varchar(30) not null check (record_type in (
        'WALLET_WITHDRAWAL', 'RUNNING_COST', 'PETTY_CASH_PAYMENT', 'PAYMENT_RECEIPT', 'QUANTITY_ADJUSTMENT',
        'STOCK_TRANSFER', 'CUSTOMER_RETURN', 'INSTANT_DISCOUNT', 'SALE', 'INVOICE', 'CREDIT_NOTE')),
    record_id uuid not null,
    record_ref varchar(40) not null,
    status varchar(12) not null check (status in ('UNDER_REVIEW', 'REVIEWED', 'FLAGGED')),
    note varchar(1000),
    reviewer_user_id uuid not null references app_users(id),
    updated_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    constraint audit_reviews_record_uq unique (tenant_id, record_type, record_id)
);
create index audit_reviews_list_idx on audit_reviews (tenant_id, record_type, status, updated_at desc);

alter table audit_reviews enable row level security;
alter table audit_reviews force row level security;
create policy audit_reviews_scope on audit_reviews
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---- Permissions (§3) — slice 1 only; violations, VAT, data-quality, recycle-bin and integrity
-- codes arrive with their slices. platform.activity.read (the Activity log) already exists (V7_1). ----
insert into permission_catalog (code, description) values
    ('history.waybills.read', 'Waybill History: the supply history of a sales invoice'),
    ('history.expenses.read', 'Expenses / Payment Reports'),
    ('history.stock-movements.read', 'Stock Movement History (long-range, all locations)'),
    ('history.wallet-withdrawals.read', 'Wallet Withdrawal History, with the audit-review badge'),
    ('history.reviews.manage', 'Set the audit-review status (under review / reviewed / flagged) on any record');

select grant_permission_to_default_roles('history.waybills.read', array['Management', 'Accounts Personnel', 'Stock Keeper']);
select grant_permission_to_default_roles('history.expenses.read', array['Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('history.stock-movements.read', array['Management', 'Stock Keeper', 'Accounts Personnel']);
select grant_permission_to_default_roles('history.wallet-withdrawals.read', array['Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('history.reviews.manage', array['Administrator', 'Management']);
