-- Platform foundation, slice 1b — docs/03-cross-module-conventions.md §7 (domain events),
-- §9 (in-app notifications), §10 (module settings), §12.2 (custom field definitions).

-- ---------------------------------------------------------------------------------------------
-- Domain events: an append-only record of every business state change, in the same transaction
-- as the change (an event exists iff the change committed). Feeds the activity log, workflow
-- rules, AI detectors and the dashboard through event consumers.
-- ---------------------------------------------------------------------------------------------
create table domain_events (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    event_type varchar(80) not null,
    aggregate_type varchar(60) not null,
    aggregate_id uuid not null,
    aggregate_ref varchar(60),
    location_id uuid references locations(id),
    actor_user_id uuid references app_users(id),
    actor_label varchar(120),
    occurred_at timestamptz not null default now(),
    payload jsonb not null default '{}'::jsonb,
    summary varchar(300) not null,
    -- The writing transaction's id. Consumers only read events whose transaction is older than
    -- every transaction still in progress (pg_snapshot_xmin), ordered by (txid, seq): a commit can
    -- never later reveal an event "behind" a consumer's checkpoint, which ordering by seq alone
    -- can't guarantee (transactions commit out of seq order).
    txid bigint not null default (pg_current_xact_id()::text::bigint),
    seq bigint generated always as identity,
    search_vector tsvector generated always as (
        to_tsvector('simple', f_unaccent(coalesce(aggregate_ref, '') || ' ' || summary))
    ) stored
);
create index domain_events_tenant_time_idx on domain_events (tenant_id, occurred_at desc);
create index domain_events_aggregate_idx on domain_events (tenant_id, aggregate_type, aggregate_id);
create index domain_events_type_idx on domain_events (tenant_id, event_type, occurred_at desc);
create index domain_events_ref_idx on domain_events (tenant_id, aggregate_ref);
create index domain_events_actor_idx on domain_events (tenant_id, actor_user_id, occurred_at desc);
create index domain_events_consume_idx on domain_events (tenant_id, txid, seq);
create index domain_events_search_idx on domain_events using gin (search_vector);
alter table domain_events enable row level security;
alter table domain_events force row level security;
create policy domain_events_scope on domain_events
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Where each consumer has got to, per tenant (consumers run tenant by tenant under RLS).
create table event_consumer_checkpoints (
    consumer varchar(60) not null,
    tenant_id uuid not null references tenants(id),
    last_txid bigint not null default 0,
    last_seq bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (consumer, tenant_id)
);
alter table event_consumer_checkpoints enable row level security;
alter table event_consumer_checkpoints force row level security;
create policy event_consumer_checkpoints_scope on event_consumer_checkpoints
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- In-app notification inbox (PRD §8 "In-app alert").
-- ---------------------------------------------------------------------------------------------
create table user_notifications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    user_id uuid not null references app_users(id),
    category varchar(30) not null check (category in ('APPROVAL_REQUIRED', 'ALERT', 'INFO', 'CONFIRMATION')),
    title varchar(160) not null,
    body varchar(600) not null,
    link_type varchar(40),
    link_ref varchar(60),
    source_event_id uuid references domain_events(id),
    created_at timestamptz not null default now(),
    read_at timestamptz,
    dismissed_at timestamptz
);
create index user_notifications_user_idx on user_notifications (tenant_id, user_id, created_at desc) where dismissed_at is null;
create index user_notifications_unread_idx on user_notifications (tenant_id, user_id) where read_at is null and dismissed_at is null;
alter table user_notifications enable row level security;
alter table user_notifications force row level security;
create policy user_notifications_scope on user_notifications
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Module settings: tenant-configurable thresholds and lists, registered in code with type,
-- default and bounds (an unregistered key can't be written).
-- ---------------------------------------------------------------------------------------------
create table module_settings (
    tenant_id uuid not null references tenants(id),
    setting_key varchar(80) not null,
    value jsonb not null,
    updated_by_user_id uuid not null references app_users(id),
    updated_at timestamptz not null default now(),
    primary key (tenant_id, setting_key)
);
alter table module_settings enable row level security;
alter table module_settings force row level security;
create policy module_settings_scope on module_settings
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------------------------
-- Custom field definitions for stock items, customers and suppliers. Values live in each
-- entity's custom_attributes jsonb and are validated against the active definitions.
-- ---------------------------------------------------------------------------------------------
create table custom_field_definitions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    entity_type varchar(30) not null check (entity_type in ('STOCK_ITEM', 'CUSTOMER', 'SUPPLIER')),
    field_key varchar(60) not null,
    label varchar(80) not null,
    data_type varchar(20) not null check (data_type in ('TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'SELECT')),
    options jsonb,
    required boolean not null default false,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    sort_order int not null default 0,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, entity_type, field_key)
);
alter table custom_field_definitions enable row level security;
alter table custom_field_definitions force row level security;
create policy custom_field_definitions_scope on custom_field_definitions
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

insert into permission_catalog (code, description) values
    ('platform.activity.read', 'Read the activity log of business events'),
    ('settings.module-settings.manage', 'Change module settings (thresholds, lists)'),
    ('settings.custom-fields.manage', 'Define custom fields for stock items, customers and suppliers');

select grant_permission_to_default_roles('platform.activity.read', array['Administrator', 'Management']);
select grant_permission_to_default_roles('settings.module-settings.manage', array['Administrator']);
select grant_permission_to_default_roles('settings.custom-fields.manage', array['Administrator']);
