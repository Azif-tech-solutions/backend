-- =============================================================================================
-- Repairs per-tenant backfills that stopped after the first few tenants.
--
-- Migrations run as the application role, which row-level security applies to. A PL/pgSQL loop
-- `for t in select id from tenants loop perform set_config('app.tenant_id', t.id, …)` reads tenants
-- through a cursor in batches; once the first iteration sets app.tenant_id, the tenants policy hides
-- every other tenant from the batches still to be fetched. So grant_permission_to_default_roles
-- (V7, used by every later migration to give existing tenants' default roles a new permission) and
-- the backfills in V8, V9, V11, V14 and V14_2 only reached the first batch of tenants.
--
-- The fix collects the tenant ids into an array before any tenant is set. Then, for every tenant:
--  * each permission any migration granted to default roles is granted again, but only where none
--    of those roles has it (a tenant the grant never reached) — a permission an administrator
--    removed after it was granted stays removed;
--  * the other backfills are rerun; all of them are idempotent.
-- =============================================================================================

create or replace function grant_permission_to_default_roles(permission_code text, role_names text[]) returns void language plpgsql as $$
declare
    t uuid;
begin
    foreach t in array (select coalesce(array_agg(id), '{}') from tenants) loop
        perform set_config('app.tenant_id', t::text, true);
        insert into role_permissions (role_id, permission_id)
        select r.id, p.id
        from tenant_roles r
        cross join permission_catalog p
        where r.tenant_id = t
          and r.name = any(role_names)
          and p.code = permission_code
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;

create function repair_default_role_grant(permission_code text, role_names text[]) returns void language plpgsql as $$
declare
    t uuid;
begin
    foreach t in array (select coalesce(array_agg(id), '{}') from tenants) loop
        perform set_config('app.tenant_id', t::text, true);
        if not exists (
            select 1 from role_permissions rp
            join tenant_roles r on r.id = rp.role_id
            join permission_catalog p on p.id = rp.permission_id
            where r.tenant_id = t and r.name = any(role_names) and p.code = permission_code
        ) then
            insert into role_permissions (role_id, permission_id)
            select r.id, p.id
            from tenant_roles r
            cross join permission_catalog p
            where r.tenant_id = t and r.name = any(role_names) and p.code = permission_code
            on conflict do nothing;
        end if;
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;

-- V7
select repair_default_role_grant('settings.views.share', array['Administrator', 'Management']);
-- V7_1
select repair_default_role_grant('platform.activity.read', array['Administrator', 'Management']);
select repair_default_role_grant('settings.module-settings.manage', array['Administrator']);
select repair_default_role_grant('settings.custom-fields.manage', array['Administrator']);
-- V8
select repair_default_role_grant('customers.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('customers.read.own', array['Administrator', 'Correspondent']);
select repair_default_role_grant('customers.manage', array['Administrator', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('customers.dealers.manage', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('customers.reclassify', array['Administrator', 'Management']);
select repair_default_role_grant('customers.categories.manage', array['Administrator']);
select repair_default_role_grant('customers.correspondents.manage', array['Administrator', 'Management']);
select repair_default_role_grant('customers.ledger.read', array['Administrator', 'Management', 'Accounts Personnel']);
select repair_default_role_grant('customers.wallet.adjust', array['Administrator']);
select repair_default_role_grant('customers.self.read', array['Administrator', 'Dealer']);
-- V9
select repair_default_role_grant('platform.documents.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier', 'Stock Keeper']);
-- V9_2
select repair_default_role_grant('inventory.pricing.read', array['Administrator', 'Management', 'Accounts Personnel', 'Stock Keeper', 'Cashier']);
select repair_default_role_grant('inventory.pricing.manage', array['Administrator']);
select repair_default_role_grant('inventory.drivers.read', array['Administrator', 'Management', 'Stock Keeper', 'Cashier']);
select repair_default_role_grant('inventory.drivers.manage', array['Administrator', 'Stock Keeper']);
-- V9_3
select repair_default_role_grant('showroom.register.manage', array['Administrator']);
select repair_default_role_grant('showroom.intake.manage', array['Administrator', 'Stock Keeper']);
select repair_default_role_grant('showroom.returns.manage', array['Administrator', 'Stock Keeper', 'Cashier']);
select repair_default_role_grant('showroom.returns.read', array['Administrator', 'Management', 'Accounts Personnel', 'Stock Keeper', 'Cashier']);
select repair_default_role_grant('showroom.stock.read', array['Accounts Personnel', 'Cashier']);
-- V11
select repair_default_role_grant('accounting.bank.manage', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.cash-accounts.manage', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.expense-categories.manage', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.fx.manage', array['Administrator', 'Accounts Personnel']);
-- V11_1
select repair_default_role_grant('accounting.petty-cash.fund', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.petty-cash.pay', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.petty-cash.respond', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.running-costs.submit', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.running-costs.approve', array['Administrator', 'Management']);
select repair_default_role_grant('accounting.funds.send', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.funds.confirm', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.lodgements.manage', array['Administrator', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('accounting.lodgements.confirm', array['Administrator', 'Accounts Personnel']);
-- V11_2
select repair_default_role_grant('accounting.receipts.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('accounting.receipts.confirm', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.receipts.void', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.receipts.record', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.wallet.fund', array['Administrator', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('accounting.wallet.withdraw', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('accounting.wallet.recover', array['Administrator', 'Management']);
select repair_default_role_grant('accounting.tenderable.read', array['Administrator', 'Management', 'Accounts Personnel']);
-- V12
select repair_default_role_grant('invoicing.invoice.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('invoicing.invoice.issue', array['Administrator', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('invoicing.invoice.void', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('invoicing.credit-note.issue', array['Administrator', 'Accounts Personnel']);
select repair_default_role_grant('invoicing.payment.record', array['Administrator', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('invoicing.delivery.manage', array['Administrator', 'Accounts Personnel', 'Cashier']);
select repair_default_role_grant('invoicing.receivables.read', array['Administrator', 'Management', 'Accounts Personnel']);

drop function repair_default_role_grant(text, text[]);

do $$
declare
    t uuid;
    creator uuid;
begin
    foreach t in array (select coalesce(array_agg(id), '{}') from tenants) loop
        perform set_config('app.tenant_id', t::text, true);

        -- V8: the default dealer categories, for a tenant that has none.
        if not exists (select 1 from dealer_categories where tenant_id = t) then
            select user_id into creator from tenant_memberships where tenant_id = t order by created_at limit 1;
            if creator is not null then
                insert into dealer_categories (tenant_id, name, discount_pct, suggested_credit_limit, sort_order, created_by_user_id) values
                    (t, 'Platinum', 5.00, 3000000.00, 4, creator),
                    (t, 'Gold', 3.50, 1500000.00, 3, creator),
                    (t, 'Silver', 2.00, 500000.00, 2, creator),
                    (t, 'Bronze', 0.00, 200000.00, 1, creator);
            end if;
        end if;

        -- V9: waybills in the document registry.
        insert into issued_documents (tenant_id, ref, doc_type, source_module, source_id, location_id, party_name, issued_by_user_id, issued_at)
        select st.tenant_id, st.waybill_ref, 'WAYBILL', 'inventory', st.id, st.source_location_id, dest.name, st.dispatched_by_user_id, st.dispatched_at
        from stock_transfers st
        join locations dest on dest.id = st.destination_location_id
        where st.tenant_id = t and st.waybill_ref is not null and st.dispatched_at is not null
        on conflict (tenant_id, ref) do nothing;

        -- V11: expense category codes start at EX-10.
        insert into document_sequences (tenant_id, prefix, next_value) values (t, 'EX', 10)
        on conflict (tenant_id, prefix) do update set next_value = greatest(document_sequences.next_value, 10);

        -- V14, V14_2: the read models.
        perform debtor_balances_rebuild(t);
        perform correspondents_recount(t);
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;
