-- =============================================================================================
-- Complete tenant isolation for references between records.
--
-- A foreign-key check ignores row-level security: `location_id references locations(id)` only asks
-- whether the location exists, in any tenant. So a row of tenant A could point at a record of
-- tenant B whenever a service forgot to check an id. From here on the database itself refuses it:
-- every reference between two tenant tables is a composite key, `(tenant_id, x_id) references
-- parent (tenant_id, id)`, so it can only ever point inside its own tenant.
--
-- 1. The two line tables that had no tenant_id of their own (recipe ingredients, stock transfer
--    lines) get one, backfilled from their parent, with direct tenant policies.
-- 2. Every single-column foreign key from a tenant table to a tenant table is replaced by the
--    composite one (each parent gains a unique (tenant_id, id) index for it to reference).
-- The existing data was audited first: no reference crossed tenants.
-- =============================================================================================

-- ---- 1. tenant_id on the line tables ----
alter table recipe_ingredients add column tenant_id uuid references tenants(id);
alter table stock_transfer_lines add column tenant_id uuid references tenants(id);

do $$
declare
    t uuid;
begin
    -- Collect the tenants first: once app.tenant_id is set, row-level security hides the others.
    foreach t in array (select coalesce(array_agg(id), '{}') from tenants) loop
        perform set_config('app.tenant_id', t::text, true);
        update recipe_ingredients i set tenant_id = r.tenant_id from recipes r where r.id = i.recipe_id and i.tenant_id is null;
        update stock_transfer_lines l set tenant_id = s.tenant_id from stock_transfers s where s.id = l.stock_transfer_id and l.tenant_id is null;
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;

alter table recipe_ingredients alter column tenant_id set not null;
alter table stock_transfer_lines alter column tenant_id set not null;

drop policy recipe_ingredients_scope on recipe_ingredients;
create policy recipe_ingredients_scope on recipe_ingredients
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
drop policy stock_transfer_lines_scope on stock_transfer_lines;
create policy stock_transfer_lines_scope on stock_transfer_lines
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ---- 2. Composite tenant foreign keys ----
do $$
declare
    fk record;
    parent regclass;
    action text;
begin
    -- Each referenced tenant table gets the unique (tenant_id, id) a composite key needs.
    for parent in
        select distinct c.confrelid::regclass
        from pg_constraint c
        join pg_attribute pc on pc.attrelid = c.conrelid and pc.attname = 'tenant_id' and not pc.attisdropped
        join pg_attribute pp on pp.attrelid = c.confrelid and pp.attname = 'tenant_id' and not pp.attisdropped
        where c.contype = 'f' and array_length(c.conkey, 1) = 1 and c.confrelid <> 'tenants'::regclass
    loop
        -- Reuse an existing unique (tenant_id, id) (e.g. tenant_roles has one) rather than duplicating it.
        if not exists (
            select 1 from pg_index i
            where i.indrelid = parent and i.indisunique and i.indpred is null
              and i.indkey::int2[] = array[
                  (select attnum from pg_attribute where attrelid = parent and attname = 'tenant_id'),
                  (select attnum from pg_attribute where attrelid = parent and attname = 'id')]
        ) then
            execute format('create unique index %I on %s (tenant_id, id)', replace(parent::text, '.', '_') || '_tenant_id_key', parent);
        end if;
    end loop;

    for fk in
        select c.conname, c.conrelid::regclass as child, a.attname as col, c.confrelid::regclass as parent,
               c.confdeltype, c.confupdtype, a.attnotnull
        from pg_constraint c
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
        join pg_attribute pc on pc.attrelid = c.conrelid and pc.attname = 'tenant_id' and not pc.attisdropped
        join pg_attribute pp on pp.attrelid = c.confrelid and pp.attname = 'tenant_id' and not pp.attisdropped
        where c.contype = 'f' and array_length(c.conkey, 1) = 1 and c.confrelid <> 'tenants'::regclass
    loop
        action := case fk.confdeltype when 'r' then 'restrict' when 'c' then 'cascade' else 'no action' end;
        if fk.confdeltype in ('n', 'd') then
            raise exception 'Foreign key % sets null or default on delete; give it a column list before converting', fk.conname;
        end if;
        execute format('alter table %s drop constraint %I', fk.child, fk.conname);
        execute format('alter table %s add constraint %I foreign key (tenant_id, %I) references %s (tenant_id, id) on delete %s not valid',
                       fk.child, fk.conname, fk.col, fk.parent, action);
        execute format('alter table %s validate constraint %I', fk.child, fk.conname);
    end loop;
end $$;
