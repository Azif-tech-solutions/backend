-- =============================================================================================
-- Audit & Compliance, slice 2 (docs/10 §4.4): Violations / Fraud History. Confidential audit
-- records raised against a member of staff, an append-only investigation trail, and the system
-- alerts that detectors raise from other modules' events (a person decides whether an alert becomes
-- a record — PRD rule 13).
-- =============================================================================================

create table violation_records (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                              -- VN-n
    subject_user_id uuid not null references app_users(id),
    location_id uuid references locations(id),
    category varchar(30) not null check (category in (
        'DEALER_FUNDS_TAMPERING', 'UNAUTHORISED_WITHDRAWAL', 'CASH_SHORTAGE', 'STOCK_DISCREPANCY',
        'UNAUTHORISED_DISCOUNT', 'RECEIPT_MANIPULATION', 'DATA_MANIPULATION', 'POLICY_BREACH', 'OTHER')),
    description varchar(2000) not null,
    amount_involved numeric(14,2) check (amount_involved is null or amount_involved >= 0),
    severity varchar(10) not null check (severity in ('LOW', 'MEDIUM', 'HIGH')),
    status varchar(20) not null default 'UNDER_INVESTIGATION'
        check (status in ('UNDER_INVESTIGATION', 'SUBSTANTIATED', 'RESOLVED', 'DISMISSED')),
    occurred_on date,
    raised_by_user_id uuid not null references app_users(id),
    raised_at timestamptz not null default now(),
    source varchar(10) not null default 'MANUAL' check (source in ('MANUAL', 'ALERT')),
    audit_alert_id uuid,
    linked_refs jsonb not null default '[]'::jsonb,
    conclusion varchar(2000),
    concluded_by_user_id uuid references app_users(id),
    concluded_at timestamptz,
    search_vector tsvector generated always as (to_tsvector('simple', ref || ' ' || description)) stored,
    constraint violation_records_ref_uq unique (tenant_id, ref),
    constraint vr_concluded check ((status = 'UNDER_INVESTIGATION') = (conclusion is null))
);
create index vr_subject_idx on violation_records (tenant_id, subject_user_id, raised_at desc);
create index vr_location_idx on violation_records (tenant_id, location_id, status);
create index vr_search_idx on violation_records using gin (search_vector);
create index vr_linked_refs_idx on violation_records using gin (linked_refs jsonb_path_ops);

create table violation_record_events (                    -- append-only investigation trail
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    violation_record_id uuid not null references violation_records(id),
    kind varchar(20) not null check (kind in ('RAISED', 'NOTE', 'SEVERITY_CHANGED', 'AMOUNT_CHANGED', 'CONCLUDED', 'REOPENED')),
    from_value varchar(60),
    to_value varchar(60),
    note varchar(2000),
    by_user_id uuid not null references app_users(id),
    at timestamptz not null default now()
);
create index vre_record_idx on violation_record_events (tenant_id, violation_record_id, at);

create table audit_alerts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    rule_code varchar(40) not null,
    subject_user_id uuid references app_users(id),
    location_id uuid references locations(id),
    summary varchar(300) not null,
    amount numeric(14,2),
    evidence jsonb not null default '{}'::jsonb,
    status varchar(20) not null default 'OPEN' check (status in ('OPEN', 'OPENED_AS_RECORD', 'DISMISSED')),
    decided_by_user_id uuid references app_users(id),
    decided_at timestamptz,
    dismiss_reason varchar(300),
    opened_record_id uuid references violation_records(id),
    dedupe_key varchar(120) not null,                      -- rule_code + source ref; the same trigger never alerts twice
    created_at timestamptz not null default now(),
    constraint audit_alerts_dedupe_uq unique (tenant_id, dedupe_key)
);
create index audit_alerts_open_idx on audit_alerts (tenant_id, status, created_at desc);
create index audit_alerts_rule_idx on audit_alerts (tenant_id, rule_code, created_at desc);
create index audit_alerts_evidence_idx on audit_alerts using gin (evidence);

do $$
declare
    t text;
begin
    foreach t in array array['violation_records', 'violation_record_events', 'audit_alerts'] loop
        execute format('alter table %I enable row level security', t);
        execute format('alter table %I force row level security', t);
        execute format('create policy %I on %I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
                       'with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', t || '_scope', t);
    end loop;
end $$;

-- ---- Permissions (§3) ----
insert into permission_catalog (code, description) values
    ('history.violations.read', 'Read violation records and audit alerts (never records where you are the subject)'),
    ('history.violations.manage', 'Raise, annotate and conclude violation records; open or dismiss audit alerts');

select grant_permission_to_default_roles('history.violations.read', array['Administrator', 'Management']);
select grant_permission_to_default_roles('history.violations.manage', array['Administrator', 'Management']);
