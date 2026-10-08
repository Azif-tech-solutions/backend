-- Search & filter foundation — docs/03-cross-module-conventions.md §4–§5.
-- PostgreSQL-native search: pg_trgm (substring / typo-tolerant), unaccent (accent-insensitive,
-- for the French rollout) and built-in full-text search. Both extensions are contrib modules that
-- ship with postgres:16-alpine and are marked trusted since PG 13.

create extension if not exists pg_trgm;
create extension if not exists unaccent;

-- unaccent() is only STABLE (its dictionary could change), so it can't be used in an index
-- expression directly. Pinning the dictionary makes the wrapper safely IMMUTABLE — the standard
-- recipe. Every trigram index and query uses f_unaccent(lower(col)) so they match.
create or replace function f_unaccent(text) returns text
    language sql immutable parallel safe strict
    as $$ select public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- Trigram indexes for already-shipped lists (doc 03 §5.12), in the accent-insensitive form.
create index stock_items_name_trgm on stock_items using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index stock_items_brand_trgm on stock_items using gin (f_unaccent(lower(brand)) gin_trgm_ops);
create index suppliers_company_name_trgm on suppliers using gin (f_unaccent(lower(company_name)) gin_trgm_ops);
create index manufacturers_name_trgm on manufacturers using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index categories_name_trgm on categories using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index units_of_measure_name_trgm on units_of_measure using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index recipes_name_trgm on recipes using gin (f_unaccent(lower(name)) gin_trgm_ops);
create index app_users_display_name_trgm on app_users using gin (f_unaccent(lower(display_name)) gin_trgm_ops);

alter table recipes add column search_vector tsvector
    generated always as (to_tsvector('simple', f_unaccent(coalesce(name, '') || ' ' || coalesce(description, '')))) stored;
create index recipes_search_vector_idx on recipes using gin (search_vector);

-- Saved list views (doc 03 §5.8): a user's named filter/sort/column set for a list screen.
create table saved_views (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    list_key varchar(80) not null,
    name varchar(80) not null,
    owner_user_id uuid not null references app_users(id),
    visibility varchar(10) not null check (visibility in ('PRIVATE', 'SHARED')),
    definition jsonb not null,
    is_default boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, owner_user_id, list_key, name)
);
create index saved_views_list_idx on saved_views (tenant_id, list_key, visibility);
create unique index saved_views_one_default_uq on saved_views (tenant_id, owner_user_id, list_key) where is_default;
alter table saved_views enable row level security;
alter table saved_views force row level security;
create policy saved_views_scope on saved_views
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

insert into permission_catalog (code, description) values
    ('settings.views.share', 'Share saved list views with other members of the tenant');

-- New permission codes only reach roles seeded *after* they exist (RoleDefaults runs at tenant
-- creation). This helper grants a code to the named default roles of every existing tenant, so
-- each module migration can call it for its new codes. tenants is readable with no tenant context
-- (V3 policy); tenant_roles/role_permissions are scoped, so app.tenant_id is set per tenant.
-- Runs as the invoker (zifiq_app in deployments), so RLS stays in force throughout.
create or replace function grant_permission_to_default_roles(permission_code text, role_names text[])
returns void language plpgsql as $$
declare
    t record;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into role_permissions (role_id, permission_id)
        select r.id, p.id
        from tenant_roles r
        cross join permission_catalog p
        where r.tenant_id = t.id
          and r.name = any(role_names)
          and p.code = permission_code
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end;
$$;

select grant_permission_to_default_roles('settings.views.share', array['Administrator', 'Management']);
