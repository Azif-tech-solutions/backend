alter table app_users add column if not exists password_hash varchar(255);

create table if not exists refresh_tokens (
    id uuid primary key default gen_random_uuid(),
    token_hash varchar(255) not null unique,
    user_id uuid not null references app_users(id) on delete cascade,
    tenant_id uuid not null references tenants(id) on delete cascade,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    revoked boolean not null default false
);

create table if not exists password_reset_tokens (
    id uuid primary key default gen_random_uuid(),
    token_hash varchar(255) not null unique,
    user_id uuid not null references app_users(id) on delete cascade,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now()
);

create index if not exists refresh_tokens_user_idx on refresh_tokens(user_id, tenant_id);
create index if not exists password_reset_tokens_user_idx on password_reset_tokens(user_id);
