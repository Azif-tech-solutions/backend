alter table tenant_memberships
    add column status varchar(20) not null default 'ACTIVE'
    check (status in ('ACTIVE', 'SUSPENDED'));

create table locations (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    type varchar(20) not null check (type in ('FACTORY', 'SHOWROOM')),
    code varchar(40) not null,
    name varchar(160) not null,
    address varchar(300),
    state varchar(80),
    phone varchar(40),
    manager_user_id uuid references app_users(id),
    vat_rate numeric(5,2) not null default 7.50,
    vat_enabled boolean not null default true,
    vat_registration_number varchar(60),
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'PENDING', 'SUSPENDED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
alter table locations enable row level security;
alter table locations force row level security;
create policy locations_scope on locations
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table document_sequences (
    tenant_id uuid not null references tenants(id),
    prefix varchar(10) not null,
    next_value bigint not null default 1,
    primary key (tenant_id, prefix)
);
alter table document_sequences enable row level security;
alter table document_sequences force row level security;
create policy document_sequences_scope on document_sequences
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table user_location_assignments (
    tenant_id uuid not null,
    user_id uuid not null,
    location_id uuid references locations(id),
    access_scope varchar(20) not null check (access_scope in ('SINGLE_LOCATION', 'ALL_LOCATIONS')),
    assigned_by_user_id uuid not null references app_users(id),
    assigned_at timestamptz not null default now(),
    primary key (tenant_id, user_id),
    foreign key (tenant_id, user_id) references tenant_memberships(tenant_id, user_id) on delete cascade,
    constraint single_location_requires_id check (
        (access_scope = 'SINGLE_LOCATION' and location_id is not null) or
        (access_scope = 'ALL_LOCATIONS' and location_id is null)
    )
);
alter table user_location_assignments enable row level security;
alter table user_location_assignments force row level security;
create policy user_location_assignments_scope on user_location_assignments
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table otp_challenges (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    requested_by_user_id uuid not null references app_users(id),
    purpose varchar(30) not null check (purpose in ('WALLET_WITHDRAWAL', 'QUANTITY_ADJUSTMENT')),
    target_reference varchar(60) not null,
    code_hash varchar(255),
    status varchar(20) not null default 'AWAITING' check (status in ('AWAITING', 'SENT', 'USED', 'EXPIRED')),
    issued_by_user_id uuid references app_users(id),
    expires_at timestamptz,
    created_at timestamptz not null default now()
);
alter table otp_challenges enable row level security;
alter table otp_challenges force row level security;
create policy otp_challenges_scope on otp_challenges
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Fix: V1's tenant_memberships_scope required a tenant context to already be set, but
-- AuthService.login and AuthService.forgotPassword both need to look up which tenant(s) a user
-- belongs to BEFORE a tenant context exists (that's the whole point of the lookup) -- login was
-- silently returning zero memberships for every user. Mirror the tenants_scope carve-out: allow
-- reads with no tenant context set (tenant_id/user_id/status only -- no sensitive columns here)
-- in addition to the existing scoped case; writes stay strictly scoped via WITH CHECK.
drop policy tenant_memberships_scope on tenant_memberships;
create policy tenant_memberships_scope on tenant_memberships
    using (nullif(current_setting('app.tenant_id', true), '') is null
        or tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

insert into permission_catalog (code, description) values
    ('iam.users.manage', 'Create and manage tenant member accounts (beyond role assignment)'),
    ('settings.manage', 'Manage company profile settings'),
    ('otp.issue', 'Review and issue OTP challenges for gated actions');

-- Fix: V1's tenants_scope WITH CHECK required parent_tenant_id to match the current session's
-- app.tenant_id, which made root-tenant self-registration (AuthService.registerTenant) impossible
-- to satisfy under RLS -- there is no tenant context yet, and a root tenant's parent_tenant_id is
-- null, so "null = null" never evaluates true. Allow a root-tenant insert (parent_tenant_id is
-- null) specifically when no tenant context is set, in addition to the existing child-tenant case.
-- Also: no app-level connection can ever enumerate tenants (e.g. NotificationOutboxDispatcher's
-- per-tenant sweep, docs/00 §5.2) because zifiq_app is nosuperuser/nobypassrls and the prior USING
-- clause required a tenant context to already be set, which a background job doesn't have. Allow
-- reads with NO tenant context at all (id/name/created_at only -- no sensitive columns on this
-- table) specifically for that narrow, legitimate case, in addition to the existing scoped cases.
drop policy tenants_scope on tenants;
create policy tenants_scope on tenants
    using (nullif(current_setting('app.tenant_id', true), '') is null
        or id = nullif(current_setting('app.tenant_id', true), '')::uuid
        or parent_tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (
        parent_tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        or (parent_tenant_id is null and nullif(current_setting('app.tenant_id', true), '') is null)
    );
