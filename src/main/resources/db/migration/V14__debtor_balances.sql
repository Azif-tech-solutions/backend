-- =============================================================================================
-- Debtors List read model (docs/08 §4.10). The Debtors List ranks customers by what they owe; read
-- straight from invoices that is a GROUP BY over every open invoice of the tenant on every page
-- view. debtor_balances holds the per-customer totals instead, so a page is an index range read.
--
-- One row per (customer, showroom of their invoices) — what a location-restricted user sees — and
-- one per customer for all showrooms (location_id = the nil UUID) — what everyone else sees.
-- A trigger on invoices recomputes the affected customer's rows in the same transaction as the
-- invoice change, so no write path can forget it and the list can never drift from the invoices.
--
-- "Open" is the same rule as InvoiceExpressions.open: status ISSUED and balance above the tenant's
-- paid tolerance (setting invoicing.paidTolerance). Changing the tolerance rebuilds the tenant.
-- =============================================================================================

create table debtor_balances (
    tenant_id uuid not null references tenants(id),
    customer_id uuid not null,
    location_id uuid not null,
    open_invoices integer not null,
    outstanding numeric(16,2) not null,
    oldest_open_issue_date date,
    last_payment_at timestamptz,
    updated_at timestamptz not null default now(),
    primary key (tenant_id, customer_id, location_id)
);
alter table debtor_balances enable row level security;
alter table debtor_balances force row level security;
create policy debtor_balances_scope on debtor_balances
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The list's sort orders, per scope key (all showrooms, or one showroom): largest balance first,
-- oldest debt first, most recent payment. Only debtors (open_invoices > 0) are indexed.
create index debtor_balances_outstanding_idx on debtor_balances (tenant_id, location_id, outstanding desc, customer_id) where open_invoices > 0;
create index debtor_balances_oldest_idx on debtor_balances (tenant_id, location_id, oldest_open_issue_date, customer_id) where open_invoices > 0;
create index debtor_balances_payment_idx on debtor_balances (tenant_id, location_id, last_payment_at, customer_id) where open_invoices > 0;

-- The tenant's paid tolerance. The fallback must equal InvoicingSettings' default (1.00);
-- InvoicingIntegrationTest checks that it does.
create function invoicing_paid_tolerance(p_tenant uuid) returns numeric language sql stable as $$
    select coalesce((select (value #>> '{}')::numeric from module_settings where tenant_id = p_tenant and setting_key = 'invoicing.paidTolerance'), 1.00)
$$;

-- Recomputes one customer's rows: their showroom row for [p_location] and their all-showrooms row.
-- Two transactions changing the same customer's invoices take turns on the customer lock, and the
-- totals are computed after it is granted (a fresh snapshot per statement, READ COMMITTED), so the
-- later one always sees the earlier one's committed invoices: concurrent payments never leave a
-- stale total. Rebuilds hold the tenant lock exclusively; refreshes share it.
create function debtor_balances_refresh(p_tenant uuid, p_customer uuid, p_location uuid) returns void language plpgsql as $$
declare
    tolerance numeric := invoicing_paid_tolerance(p_tenant);
    nil constant uuid := '00000000-0000-0000-0000-000000000000';
begin
    perform pg_advisory_xact_lock_shared(hashtextextended('debtor_balances:' || p_tenant, 0));
    perform pg_advisory_xact_lock(hashtextextended('debtor_balances:' || p_tenant || ':' || p_customer, 0));
    insert into debtor_balances as b (tenant_id, customer_id, location_id, open_invoices, outstanding, oldest_open_issue_date, last_payment_at, updated_at)
    select p_tenant, p_customer, scope.location_id,
           count(*) filter (where i.open), coalesce(sum(i.balance) filter (where i.open), 0), min(i.issue_date) filter (where i.open),
           max(i.last_payment_at), now()
    from (values (p_location), (nil)) scope (location_id)
    left join lateral (
        select v.issue_date, v.last_payment_at, v.balance, v.status = 'ISSUED' and v.balance > tolerance as open
        from (select status, issue_date, last_payment_at, tax_inclusive_amount - amount_paid - amount_credited as balance
              from invoices
              where tenant_id = p_tenant and customer_id = p_customer and status in ('ISSUED', 'VOID')
                and (scope.location_id = nil or location_id = scope.location_id)) v
    ) i on true
    group by scope.location_id
    on conflict (tenant_id, customer_id, location_id) do update
        set open_invoices = excluded.open_invoices, outstanding = excluded.outstanding,
            oldest_open_issue_date = excluded.oldest_open_issue_date, last_payment_at = excluded.last_payment_at, updated_at = now()
        where (b.open_invoices, b.outstanding, b.oldest_open_issue_date, b.last_payment_at)
              is distinct from (excluded.open_invoices, excluded.outstanding, excluded.oldest_open_issue_date, excluded.last_payment_at);
end $$;

-- Rebuilds a whole tenant in one set-based pass (backfill, tolerance change, bulk loads).
create function debtor_balances_rebuild(p_tenant uuid) returns void language plpgsql as $$
declare
    tolerance numeric := invoicing_paid_tolerance(p_tenant);
begin
    perform pg_advisory_xact_lock(hashtextextended('debtor_balances:' || p_tenant, 0));
    delete from debtor_balances where tenant_id = p_tenant;
    insert into debtor_balances (tenant_id, customer_id, location_id, open_invoices, outstanding, oldest_open_issue_date, last_payment_at)
    select p_tenant, customer_id, coalesce(location_id, '00000000-0000-0000-0000-000000000000'),
           count(*) filter (where open), coalesce(sum(balance) filter (where open), 0), min(issue_date) filter (where open), max(last_payment_at)
    from (select customer_id, location_id, issue_date, last_payment_at, tax_inclusive_amount - amount_paid - amount_credited as balance,
                 status = 'ISSUED' and tax_inclusive_amount - amount_paid - amount_credited > tolerance as open
          from invoices
          where tenant_id = p_tenant and customer_id is not null and status in ('ISSUED', 'VOID')) v
    group by grouping sets ((customer_id, location_id), (customer_id));
end $$;

create function debtor_balances_on_invoice() returns trigger language plpgsql as $$
begin
    if tg_op <> 'INSERT' and old.customer_id is not null then
        perform debtor_balances_refresh(old.tenant_id, old.customer_id, old.location_id);
    end if;
    if tg_op <> 'DELETE' and new.customer_id is not null
       and (tg_op = 'INSERT' or (old.customer_id, old.location_id) is distinct from (new.customer_id, new.location_id)) then
        perform debtor_balances_refresh(new.tenant_id, new.customer_id, new.location_id);
    end if;
    return null;
end $$;
create trigger invoices_debtor_balances
    after insert or delete or update of status, customer_id, location_id, issue_date, tax_inclusive_amount, amount_paid, amount_credited, last_payment_at
    on invoices for each row execute function debtor_balances_on_invoice();

create function debtor_balances_on_tolerance() returns trigger language plpgsql as $$
begin
    if coalesce(new.setting_key, old.setting_key) = 'invoicing.paidTolerance' then
        perform debtor_balances_rebuild(coalesce(new.tenant_id, old.tenant_id));
    end if;
    return null;
end $$;
create trigger module_settings_paid_tolerance
    after insert or update or delete on module_settings for each row execute function debtor_balances_on_tolerance();

-- Backfill every tenant.
do $$
declare
    t record;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        perform debtor_balances_rebuild(t.id);
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;
