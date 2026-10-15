-- =============================================================================================
-- Scale seed: loads one tenant with production-sized data so list, report and search queries can
-- be timed and EXPLAINed against millions of rows (see scripts/scale/README.md).
--
-- LOCAL / TEST DATABASES ONLY. Run as the database superuser: it bypasses RLS, and sets
-- session_replication_role = replica for its own session, which skips foreign-key and trigger
-- checks so millions of rows load in minutes (the ids are built consistently, so references hold):
--   psql -U <superuser> -d <db> -v tenant=<tenant uuid> -v admin=<admin user uuid> -f scripts/scale/seed.sql
-- The tenant and admin come from a normal POST /api/v1/auth/register-tenant, so roles and
-- provisioned defaults exist. Ids are derived as md5(tenant || kind || n)::uuid, so rows can
-- reference each other without lookups. Each step commits on its own, so a failure late in the run
-- keeps the tables already loaded; rerun from the failed step. Takes a while (millions of rows).
-- =============================================================================================
\set ON_ERROR_STOP on
\timing on

\set showrooms 10
\set items 20000
\set customers 200000
\set invoices 1000000
\set receipts 1000000
\set ledger 2000000
\set events 1000000

set session_replication_role = replica;

-- ---- Locations: 1 factory (n = 0) and :showrooms showrooms ----
insert into locations (id, tenant_id, type, code, name, created_by_user_id, created_at)
select md5(:'tenant' || 'loc' || n)::uuid, :'tenant', case when n = 0 then 'FACTORY' else 'SHOWROOM' end,
       'SCL-' || n, case when n = 0 then 'Scale Factory' else 'Scale Showroom ' || n end, :'admin', now() - interval '3 years'
from generate_series(0, :showrooms) n;

insert into location_vat_settings (tenant_id, location_id, vat_enabled, vat_rate, effective_from, updated_by_user_id)
select :'tenant', md5(:'tenant' || 'loc' || n)::uuid, true, 7.50, current_date - 1200, :'admin' from generate_series(0, :showrooms) n;

-- ---- Catalogue: :items stock items with a balance at every location ----
insert into units_of_measure (id, tenant_id, code, name) values (md5(:'tenant' || 'uom')::uuid, :'tenant', 'SCTN', 'Cartons');

insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, landing_cost, markup_cost,
                         vat_category_id, hsn_section_id, isic_section_id, status, created_by_user_id, created_at)
select md5(:'tenant' || 'item' || i)::uuid, :'tenant', md5(:'tenant' || 'loc0')::uuid, 'FINISHED_PRODUCT', 'SI-' || i,
       'Scale product ' || i || ' ' || (array['Citrus','Cola','Malt','Water','Juice','Rice','Beans','Oil'])[1 + i % 8],
       md5(:'tenant' || 'uom')::uuid, 50, 1000 + i % 5000, 200, (select id from vat_categories where code = case when i % 10 = 0 then 'ZERO_RATED' else 'STANDARD_RATE' end),
       (select id from hsn_sections where code = 'IV'), (select id from isic_sections where code = 'C'), case when i % 50 = 0 then 'SUSPENDED' else 'ACTIVE' end,
       :'admin', now() - interval '2 years'
from generate_series(1, :items) i;

insert into stock_balances (tenant_id, location_id, stock_item_id, quantity_on_hand, updated_at)
select :'tenant', md5(:'tenant' || 'loc' || n)::uuid, md5(:'tenant' || 'item' || i)::uuid, (i * 7 + n * 13) % 900, now()
from generate_series(1, :items) i cross join generate_series(0, :showrooms) n;

-- ---- Stock ledger: :ledger movements over two years ----
insert into stock_ledger_entries (tenant_id, location_id, stock_item_id, source_type, source_reference, qty_in, qty_out, unit_cost, remark, entry_date, created_by_user_id, created_at)
select :'tenant', md5(:'tenant' || 'loc' || (g % (:showrooms + 1)))::uuid, md5(:'tenant' || 'item' || (1 + g % :items))::uuid,
       (array['REFILL','TRANSFER_OUT','TRANSFER_IN','QUANTITY_ADJUSTMENT'])[1 + g % 4], 'SCL-' || g,
       case when g % 4 in (0, 2) then 1 + g % 40 else 0 end, case when g % 4 in (1, 3) then 1 + g % 30 else 0 end, 1000, null,
       current_date - (g % 730), :'admin', now() - ((g % 730) || ' days')::interval
from generate_series(1, :ledger) g;

-- ---- Customers ----
insert into customers (id, tenant_id, code, type, name, phone, phone_e164, email, tin, location_id, credit_limit, status, created_by_user_id, created_at)
select md5(:'tenant' || 'cust' || c)::uuid, :'tenant', 'SCU-' || c, case when c % 3 = 0 then 'SPECIAL_CUSTOMER' else 'ORDINARY_CUSTOMER' end,
       (array['Okafor','Adeyemi','Bello','Eze','Ibrahim','Musa','Okoro','Balogun'])[1 + c % 8] || ' Stores ' || c,
       '080' || lpad((c % 100000000)::text, 8, '0'), '+23480' || lpad((c % 100000000)::text, 8, '0'),
       case when c % 2 = 0 then 'buyer' || c || '@example.com' end, case when c % 5 = 0 then 'TIN-' || c end,
       md5(:'tenant' || 'loc' || (1 + c % :showrooms))::uuid, (c % 10) * 50000, case when c % 40 = 0 then 'WATCH' else 'ACTIVE' end,
       :'admin', now() - interval '2 years'
from generate_series(1, :customers) c;

-- ---- Money accounts: a till per showroom and three banks ----
insert into bank_accounts (id, tenant_id, code, bank_name, account_name, account_number, account_type, opening_balance, opening_balance_date, current_balance, status, created_by_user_id)
select md5(:'tenant' || 'bank' || b)::uuid, :'tenant', 'SBA-' || b, 'Scale Bank ' || b, 'Scale Co', '90' || lpad(b::text, 8, '0'), 'COLLECTIONS', 0, current_date - 800, 0, 'ACTIVE', :'admin'
from generate_series(1, 3) b;
insert into cash_accounts (id, tenant_id, code, name, kind, location_id, opening_balance, current_balance, status, created_by_user_id)
select md5(:'tenant' || 'till' || n)::uuid, :'tenant', 'STILL-' || n, 'Scale Till ' || n, 'TILL', md5(:'tenant' || 'loc' || n)::uuid, 0, 0, 'ACTIVE', :'admin'
from generate_series(1, :showrooms) n;

-- ---- Invoices: :invoices issued over two years (2% void), two lines each ----
insert into invoices (id, tenant_id, number, status, invoice_type_code, invoice_kind, source_type, source_id, source_ref, source_date, location_id,
                      issue_date, issue_time, due_date, payment_terms_days, customer_id, buyer_name, buyer_type, buyer_email, buyer_phone, buyer_country_code,
                      issuer, line_extension_amount, discount_total, tax_exclusive_amount, vat_total, tax_inclusive_amount, vat_enabled, location_vat_rate,
                      amount_paid, amount_credited, last_payment_at, issued_by_user_id, issued_at, void_reason, voided_by_user_id, voided_at, created_by_user_id, created_at)
select md5(:'tenant' || 'inv' || g)::uuid, :'tenant', 'INV-S' || g, case when g % 50 = 0 then 'VOID' else 'ISSUED' end, '381',
       case when g % 5 = 0 then 'B2B' else 'B2C' end, case when g % 4 = 0 then 'PRO_FORMA' else 'POS_SALE' end,
       md5(:'tenant' || 'src' || g)::uuid, 'TXN-S' || g, d, md5(:'tenant' || 'loc' || (1 + g % :showrooms))::uuid,
       d, time '10:00', d + 30, 30, md5(:'tenant' || 'cust' || (1 + g % :customers))::uuid,
       (array['Okafor','Adeyemi','Bello','Eze','Ibrahim','Musa','Okoro','Balogun'])[1 + (1 + g % :customers) % 8] || ' Stores ' || (1 + g % :customers),
       'ORDINARY_CUSTOMER', case when g % 2 = 0 then 'buyer' || g || '@example.com' end, '0803' || lpad((g % 10000000)::text, 7, '0'), 'NG',
       '{}'::jsonb, net, 0, net, vat, net + vat, true, 7.50,
       paid, 0, case when paid > 0 then d::timestamptz + interval '5 days' end, :'admin', d::timestamptz + interval '10 hours',
       case when g % 50 = 0 then 'Seeded void' end, case when g % 50 = 0 then :'admin'::uuid end, case when g % 50 = 0 then d::timestamptz + interval '1 day' end,
       :'admin', d::timestamptz
from (
    select g, d, net, round(net * 0.075, 2) as vat,
           case when g % 50 = 0 then 0 when g % 20 < 12 then net + round(net * 0.075, 2) when g % 20 < 15 then round(net / 2, 2) else 0 end as paid
    from (select g, current_date - (g % 730) as d, (1000 + (g % 997) * 50)::numeric(14,2) as net from generate_series(1, :invoices) g) x
) y;

insert into invoice_lines (tenant_id, invoice_id, line_no, stock_item_id, item_code, item_name, quantity, unit_name, unit_price, discount_amount, line_net,
                           vat_category_id, vat_category_code, nrs_tax_category_code, vat_rate, vat_amount, line_total, hsn_section_code, product_category)
select :'tenant', md5(:'tenant' || 'inv' || g)::uuid, l, md5(:'tenant' || 'item' || (1 + (g * 2 + l) % :items))::uuid, 'SI-' || (1 + (g * 2 + l) % :items),
       'Scale product', 1, 'Cartons', net / 2, 0, net / 2, (select id from vat_categories where code = 'STANDARD_RATE'), 'STANDARD_RATE', 'STANDARD_VAT', 7.50,
       round(net / 2 * 0.075, 2), net / 2 + round(net / 2 * 0.075, 2), 'IV', 'Prepared foodstuffs'
from (select g, (1000 + (g % 997) * 50)::numeric(14,2) as net from generate_series(1, :invoices) g) x cross join generate_series(1, 2) l;

-- ---- Receipts: one per paid invoice (cash or transfer), plus invoice_payments links ----
insert into payment_receipts (id, tenant_id, ref, kind, location_id, cash_account_id, cashier_user_id, customer_id, customer_name, channel, amount, source_module, source_type,
                              source_id, source_ref, receiving_bank_account_id, confirmation_status, status, received_at)
select md5(:'tenant' || 'rct' || g)::uuid, :'tenant', 'RCT-S' || g, 'RECEIPT', i.location_id,
       case when g % 3 = 0 then null else md5(:'tenant' || 'till' || (1 + g % :showrooms))::uuid end, :'admin', i.customer_id, i.buyer_name,
       case when g % 3 = 0 then 'FUND_TRANSFER' else 'CASH' end, i.amount_paid, 'invoicing', 'INVOICE_PAYMENT', i.id, i.number,
       case when g % 3 = 0 then md5(:'tenant' || 'bank' || (1 + g % 3))::uuid end,
       case when g % 3 = 0 then (case when g % 30 = 0 then 'PENDING' else 'APPROVED' end) else 'NOT_REQUIRED' end, 'ISSUED', i.last_payment_at
from generate_series(1, :invoices) g
join invoices i on i.id = md5(:'tenant' || 'inv' || g)::uuid
where i.amount_paid > 0 and g <= :receipts;

insert into invoice_payments (tenant_id, invoice_id, method, amount, payment_receipt_id, receipt_ref, receipt_status, nrs_payment_means_code, paid_at, recorded_by_user_id, source)
select r.tenant_id, r.source_id, r.channel, r.amount, r.id, r.ref, 'ACTIVE', case when r.channel = 'CASH' then '10' else '30' end, r.received_at, :'admin', 'RECORDED'
from payment_receipts r where r.tenant_id = :'tenant' and r.source_type = 'INVOICE_PAYMENT';

-- ---- Cash and bank ledgers behind those receipts (running balances per account) ----
insert into cash_transactions (tenant_id, cash_account_id, direction, amount, balance_after, reference, remark, source_type, source_id, entry_date, created_by_user_id, created_at)
select r.tenant_id, r.cash_account_id, 'IN', r.amount, sum(r.amount) over (partition by r.cash_account_id order by r.received_at, r.id), r.ref, 'Receipt ' || r.source_ref,
       'RECEIPT', r.id, r.received_at::date, :'admin', r.received_at
from payment_receipts r where r.tenant_id = :'tenant' and r.channel = 'CASH';

insert into bank_transactions (tenant_id, bank_account_id, entry_type, amount, balance_after, reference, description, source_type, source_id, entry_date, created_by_user_id, created_at)
select r.tenant_id, r.receiving_bank_account_id, 'CREDIT', r.amount, sum(r.amount) over (partition by r.receiving_bank_account_id order by r.received_at, r.id), r.ref,
       'Transfer ' || r.source_ref, 'RECEIPT_CONFIRMED', r.id, r.received_at::date, :'admin', r.received_at
from payment_receipts r where r.tenant_id = :'tenant' and r.channel = 'FUND_TRANSFER' and r.confirmation_status = 'APPROVED';

update cash_accounts a set current_balance = coalesce((select sum(amount) from cash_transactions t where t.cash_account_id = a.id), 0) where a.tenant_id = :'tenant';
update bank_accounts a set current_balance = coalesce((select sum(amount) from bank_transactions t where t.bank_account_id = a.id), 0) where a.tenant_id = :'tenant';

-- ---- Wallet ledger: return credits on every 4th customer ----
insert into customer_ledger_entries (tenant_id, customer_id, balance_type, entry_type, direction, amount, balance_after, reference, description, source_module, source_type, entry_date, created_at)
select :'tenant', md5(:'tenant' || 'cust' || c)::uuid, 'WALLET', 'RETURN_CREDIT', 'CREDIT', 1000, 1000 * k, 'RT-S' || c || '-' || k, 'Seeded return credit',
       'showroom', 'CUSTOMER_RETURN', current_date - k, now() - (k || ' days')::interval
from generate_series(4, :customers, 4) c cross join generate_series(1, 20) k;
update customers c set wallet_balance = 20000 where c.tenant_id = :'tenant' and c.code ~ '^SCU-' and (substring(c.code from 5)::int % 4) = 0;

-- ---- Document registry, events, notifications ----
insert into issued_documents (tenant_id, ref, doc_type, source_module, source_id, location_id, party_name, party_customer_id, value_amount, issued_by_user_id, issued_at, status)
select i.tenant_id, i.number, 'INVOICE', 'invoicing', i.id, i.location_id, i.buyer_name, i.customer_id, i.tax_inclusive_amount, :'admin', i.issued_at,
       case when i.status = 'VOID' then 'VOIDED' else 'ISSUED' end
from invoices i where i.tenant_id = :'tenant' and i.number like 'INV-S%';

insert into domain_events (tenant_id, event_type, aggregate_type, aggregate_id, aggregate_ref, location_id, actor_user_id, occurred_at, payload, summary)
select :'tenant', 'invoicing.invoice.issued', 'invoice', md5(:'tenant' || 'inv' || g)::uuid, 'INV-S' || g, md5(:'tenant' || 'loc' || (1 + g % :showrooms))::uuid, :'admin',
       now() - ((g % 730) || ' days')::interval, '{}'::jsonb, 'INV-S' || g || ' issued'
from generate_series(1, :events) g;

insert into user_notifications (tenant_id, user_id, category, title, body, link_type, link_ref, created_at, read_at)
select :'tenant', :'admin', 'INFO', 'Seeded notification ' || g, 'Body ' || g, 'invoice', md5(:'tenant' || 'inv' || g)::uuid::text,
       now() - ((g % 365) || ' days')::interval, case when g % 3 = 0 then null else now() end
from generate_series(1, 200000) g;

-- The Debtors List read model is kept by an invoices trigger, which replica mode skips: rebuild it
-- from the seeded invoices (only once V14 has created it).
select debtor_balances_rebuild(:'tenant') from pg_proc where proname = 'debtor_balances_rebuild';

analyze;
