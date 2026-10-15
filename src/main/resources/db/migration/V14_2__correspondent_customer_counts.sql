-- =============================================================================================
-- Correspondent register (docs/04 §4): each rep's customer and dealer counts. Counting them per
-- list request grouped every live customer of the tenant; the counts now live on the
-- correspondent row, kept by a trigger on customers in the same transaction as the change.
-- =============================================================================================

alter table correspondents
    add column customer_count integer not null default 0,
    add column dealer_count integer not null default 0;

-- The register's default order.
create index correspondents_name_idx on correspondents (tenant_id, name, id);

-- Sets both counts from the customers table, for a whole tenant (backfill, bulk loads).
create function correspondents_recount(p_tenant uuid) returns void language sql as $$
    update correspondents c
    set customer_count = coalesce(x.customers, 0), dealer_count = coalesce(x.dealers, 0)
    from correspondents c2
    left join (
        select correspondent_id, count(*) as customers, count(*) filter (where type = 'AUTHORISED_DEALER') as dealers
        from customers
        where tenant_id = p_tenant and correspondent_id is not null and status <> 'REMOVED'
        group by correspondent_id
    ) x on x.correspondent_id = c2.id
    where c.id = c2.id and c2.tenant_id = p_tenant
      and (c.customer_count, c.dealer_count) is distinct from (coalesce(x.customers, 0), coalesce(x.dealers, 0))
$$;

-- A live customer counts towards their correspondent; a dealer also towards dealer_count. The
-- correspondents' rows are updated in id order, so two transactions moving customers between the
-- same pair of reps in opposite directions never deadlock.
create function correspondents_count_customers() returns trigger language plpgsql as $$
declare
    d record;
begin
    if tg_op = 'UPDATE' and (old.correspondent_id, old.type, old.status = 'REMOVED') is not distinct from (new.correspondent_id, new.type, new.status = 'REMOVED') then
        return null;
    end if;
    for d in
        select v.id, sum(v.customers) as customers, sum(v.dealers) as dealers
        from (values
            (case when tg_op <> 'INSERT' and old.status <> 'REMOVED' then old.correspondent_id end, -1,
             case when tg_op <> 'INSERT' and old.type = 'AUTHORISED_DEALER' then -1 else 0 end),
            (case when tg_op <> 'DELETE' and new.status <> 'REMOVED' then new.correspondent_id end, 1,
             case when tg_op <> 'DELETE' and new.type = 'AUTHORISED_DEALER' then 1 else 0 end)
        ) v (id, customers, dealers)
        where v.id is not null
        group by v.id
        order by v.id
    loop
        update correspondents set customer_count = customer_count + d.customers, dealer_count = dealer_count + d.dealers
        where id = d.id and (d.customers <> 0 or d.dealers <> 0);
    end loop;
    return null;
end $$;
create trigger customers_correspondent_counts
    after insert or delete or update of correspondent_id, type, status on customers
    for each row execute function correspondents_count_customers();

-- Backfill every tenant.
do $$
declare
    t record;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        perform correspondents_recount(t.id);
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;
