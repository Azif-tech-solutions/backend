create extension if not exists pgcrypto;

create table tenants (
    id uuid primary key default gen_random_uuid(),
    parent_tenant_id uuid references tenants(id),
    name varchar(160) not null,
    created_at timestamptz not null default now(),
    constraint tenants_no_self_parent check (parent_tenant_id is null or parent_tenant_id <> id)
);

create table app_users (
    id uuid primary key,
    email varchar(320) not null unique,
    display_name varchar(160) not null,
    created_at timestamptz not null default now()
);

create table tenant_memberships (
    tenant_id uuid not null references tenants(id),
    user_id uuid not null references app_users(id),
    created_by_user_id uuid references app_users(id),
    created_at timestamptz not null default now(),
    primary key (tenant_id, user_id)
);

create table permission_catalog (
    id uuid primary key default gen_random_uuid(),
    code varchar(160) not null unique,
    description varchar(300) not null
);

create table tenant_roles (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(100) not null,
    description varchar(300),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, name),
    unique (tenant_id, id)
);

create table role_permissions (
    role_id uuid not null references tenant_roles(id) on delete cascade,
    permission_id uuid not null references permission_catalog(id) on delete cascade,
    primary key (role_id, permission_id)
);

alter table role_permissions enable row level security;
alter table role_permissions force row level security;
create policy role_permissions_scope on role_permissions
        using (exists (
                select 1 from tenant_roles role
                where role.id = role_permissions.role_id
                    and role.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        ))
        with check (exists (
                select 1 from tenant_roles role
                where role.id = role_permissions.role_id
                    and role.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        ));

create table user_role_assignments (
    tenant_id uuid not null,
    user_id uuid not null,
    role_id uuid not null,
    granted_by_user_id uuid not null references app_users(id),
    granted_at timestamptz not null default now(),
    primary key (tenant_id, user_id, role_id),
    foreign key (tenant_id, user_id) references tenant_memberships(tenant_id, user_id) on delete cascade,
    foreign key (tenant_id, role_id) references tenant_roles(tenant_id, id) on delete cascade
);

create table notification_outbox (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    channel varchar(20) not null check (channel in ('PUSH', 'EMAIL', 'WHATSAPP', 'TELEGRAM')),
    recipient varchar(320) not null,
    subject varchar(300),
    body text not null,
    metadata jsonb not null default '{}'::jsonb,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING', 'SENT', 'FAILED')),
    attempts integer not null default 0,
    created_at timestamptz not null default now(),
    sent_at timestamptz
);

create index tenant_memberships_user_idx on tenant_memberships(user_id, tenant_id);
create index user_role_assignments_user_idx on user_role_assignments(tenant_id, user_id);
create index notification_outbox_pending_idx on notification_outbox(tenant_id, created_at) where status = 'PENDING';

insert into permission_catalog (code, description) values
    ('iam.users.assign', 'Assign roles to users in this tenant'),
    ('iam.roles.manage', 'Create tenant roles and configure their permissions'),
    ('tenants.create_child', 'Create child tenants'),
    ('inventory.stock.read', 'View stock across factory/showroom locations'),
    ('inventory.stock.manage', 'Manage stock across factory/showroom locations'),
    ('showroom.stock.read', 'View showroom stock'),
    ('showroom.stock.manage', 'Manage showroom stock'),
    ('accounting.read', 'View accounting information'),
    ('accounting.manage', 'Manage accounting information'),
    ('cashier.sales.read', 'View sales and POS reports'),
    ('cashier.sales.manage', 'Manage sales and POS transactions');

alter table tenants enable row level security;
alter table tenants force row level security;
create policy tenants_scope on tenants
    using (id = nullif(current_setting('app.tenant_id', true), '')::uuid
        or parent_tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (parent_tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table tenant_memberships enable row level security;
alter table tenant_memberships force row level security;
create policy tenant_memberships_scope on tenant_memberships
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table tenant_roles enable row level security;
alter table tenant_roles force row level security;
create policy tenant_roles_scope on tenant_roles
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table user_role_assignments enable row level security;
alter table user_role_assignments force row level security;
create policy user_role_assignments_scope on user_role_assignments
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table notification_outbox enable row level security;
alter table notification_outbox force row level security;
create policy notification_outbox_scope on notification_outbox
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
