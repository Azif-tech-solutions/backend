-- =============================================================================================
-- Audit & Compliance, slice 3 (docs/10 §4.6, §4.8, §4.9, §4.10): VAT compliance (read-only, no
-- schema of its own beyond the input-VAT columns here), the Error Handler's data-quality flags,
-- the Recycle Bin's product lifecycle, and the integrity-check runs.
--
-- References between tenant records are tenant-scoped composite keys (V14_9); references to
-- app_users (no tenant_id) stay single-column.
-- =============================================================================================

create extension if not exists fuzzystrmatch;      -- levenshtein(); ships with the postgres:16 image

-- ---- §2.1 Product lifecycle for the Recycle Bin (inventory-owned tables, OPEN module) ----
alter table stock_items drop constraint stock_items_status_check;
alter table stock_items add constraint stock_items_status_check check (status in ('ACTIVE', 'SUSPENDED', 'DELETED', 'PURGED'));
alter table stock_items
    add column status_reason varchar(300),
    add column status_changed_at timestamptz,
    add column status_changed_by_user_id uuid references app_users(id),
    add column suspension_review_on date,            -- SUSPENDED: date to revisit (Recycle Bin "Review on")
    add column deleted_retention_until date;         -- DELETED: purge-eligible after this date
create index stock_items_lifecycle_idx on stock_items (tenant_id, status, suspension_review_on);

-- input VAT on factory raw-material deliveries (shipped stock_receipts, V4)
alter table stock_receipts
    add column supplier_invoice_ref varchar(60),
    add column input_vat_rate numeric(5,2) not null default 0 check (input_vat_rate between 0 and 100),
    add column input_vat_amount numeric(14,2) not null default 0 check (input_vat_amount >= 0);

alter table stock_ledger_entries drop constraint stock_ledger_entries_source_type_check;
alter table stock_ledger_entries add constraint stock_ledger_entries_source_type_check check (source_type in (
    'REFILL', 'RECEIVE', 'PRODUCTION_RUN', 'TRANSFER_OUT', 'TRANSFER_IN', 'QUANTITY_ADJUSTMENT', 'TRADE_OUT', 'TRADE_IN',
    'TRANSFER_REJECTED_IN', 'DIRECT_ENTRY', 'OUTSOURCE_RECEIPT', 'CUSTOMER_RETURN', 'AREA_MOVE_OUT', 'AREA_MOVE_IN',
    'POS_SALE', 'SALE_VOID', 'CUSTOMER_WAYBILL', 'CUSTOMER_WAYBILL_CANCEL',
    'PURGE_WRITE_OFF'           -- stock still held when a deleted product is purged (§4.9)
));

-- ---- §2.5 Error Handler: data-quality flags ----
create table data_quality_flags (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- ER-n
    entity_type varchar(20) not null check (entity_type in ('STOCK_ITEM', 'UNIT', 'CATEGORY')),
    entity_id uuid not null,                               -- polymorphic; no FK (STOCK_ITEM/UNIT/CATEGORY)
    entity_code varchar(40),
    field varchar(20) not null check (field in ('NAME', 'BRAND', 'UNIT_NAME', 'CATEGORY_NAME')),
    current_value varchar(200) not null,
    suggested_value varchar(200),
    kind varchar(10) not null check (kind in ('SPELLING', 'NAMING')),
    source varchar(10) not null check (source in ('SYSTEM', 'USER')),
    flagged_by_user_id uuid references app_users(id),      -- null for SYSTEM
    status varchar(12) not null default 'OPEN' check (status in ('OPEN', 'CORRECTED', 'DISMISSED')),
    applied_value varchar(200),
    resolved_by_user_id uuid references app_users(id),
    resolved_at timestamptz,
    dismiss_reason varchar(300),
    created_at timestamptz not null default now(),
    constraint data_quality_flags_ref_uq unique (tenant_id, ref)
);
create unique index dqf_open_uq on data_quality_flags (tenant_id, entity_type, entity_id, field) where status = 'OPEN';
create index dqf_list_idx on data_quality_flags (tenant_id, status, kind, created_at desc);
create index dqf_value_trgm on data_quality_flags using gin (f_unaccent(lower(current_value)) gin_trgm_ops);

create table data_quality_ignores (                        -- "the spelling is intentional": never flag this word/value again
    tenant_id uuid not null references tenants(id),
    token varchar(120) not null,                           -- lower-case word or whole value
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    primary key (tenant_id, token)
);

-- ---- §2.6 Recycle Bin log ----
create table recycle_bin_log (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    stock_item_id uuid not null,
    action varchar(12) not null check (action in ('SUSPENDED', 'RESUMED', 'DELETED', 'RESTORED', 'PURGED', 'REPRICED')),
    reason varchar(300),
    quantity_held numeric(14,3),                           -- Σ on hand across locations at the time
    value_held numeric(14,2),                              -- [cost] quantity × landing cost at the time
    review_on date,
    retention_until date,
    write_off_quantity numeric(14,3),                      -- PURGED with stock
    by_user_id uuid not null references app_users(id),
    at timestamptz not null default now(),
    constraint recycle_bin_log_item_fkey foreign key (tenant_id, stock_item_id) references stock_items (tenant_id, id)
);
create index rbl_item_idx on recycle_bin_log (tenant_id, stock_item_id, at desc);

-- ---- §2.7 Integrity checks ----
create table integrity_check_runs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    trigger varchar(10) not null check (trigger in ('SCHEDULED', 'MANUAL')),
    triggered_by_user_id uuid references app_users(id),
    checks_run int not null default 0,
    findings_count int not null default 0,
    status varchar(10) not null check (status in ('RUNNING', 'PASSED', 'FINDINGS', 'FAILED'))
);
create unique index integrity_check_runs_tenant_id_key on integrity_check_runs (tenant_id, id);
create index integrity_runs_idx on integrity_check_runs (tenant_id, started_at desc);
-- at most one run at a time per tenant (§4.10 RUN_IN_PROGRESS)
create unique index integrity_runs_one_running on integrity_check_runs (tenant_id) where status = 'RUNNING';

create table integrity_check_findings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    run_id uuid not null,
    check_code varchar(40) not null,
    severity varchar(10) not null check (severity in ('WARNING', 'CRITICAL')),
    record_type varchar(40) not null,
    record_ref varchar(60) not null,
    expected varchar(120),
    actual varchar(120),
    message varchar(300) not null,
    constraint integrity_findings_run_fkey foreign key (tenant_id, run_id) references integrity_check_runs (tenant_id, id) on delete cascade
);
create index integrity_findings_run_idx on integrity_check_findings (tenant_id, run_id, severity);

do $$
declare
    t text;
begin
    foreach t in array array['data_quality_flags', 'data_quality_ignores', 'recycle_bin_log', 'integrity_check_runs', 'integrity_check_findings'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('compliance.vat.read', 'Showroom/Factory VAT Reports and the VAT return summary'),
    ('history.data-quality.manage', 'Error Handler: flag, correct and dismiss product data-quality issues'),
    ('history.recycle-bin.manage', 'Suspend/resume and delete/restore products; reprice-and-resume'),
    ('history.recycle-bin.purge', 'Purge a deleted product (irreversible)'),
    ('history.integrity.read', 'Integrity check runs and findings; trigger a manual run');

select grant_permission_to_default_roles('compliance.vat.read', array['Management', 'Accounts Personnel']);
select grant_permission_to_default_roles('history.data-quality.manage', array['Administrator']);
select grant_permission_to_default_roles('history.recycle-bin.manage', array['Administrator']);
select grant_permission_to_default_roles('history.recycle-bin.purge', array['Administrator']);
select grant_permission_to_default_roles('history.integrity.read', array['Administrator', 'Management']);
