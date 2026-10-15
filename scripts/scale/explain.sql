-- Times the hot list and report queries against the scale tenant (scripts/scale/seed.sql) with
-- EXPLAIN (ANALYZE, BUFFERS): each should read an index range, not scan a table. Run as the
-- application user so row-level security applies exactly as in production:
--   psql -U <app user> -d <db> -v tenant=<scale tenant id> -f scripts/scale/explain.sql
-- The SQL mirrors what the services generate; keep it in step when a query changes.
\set ON_ERROR_STOP on
begin;
select set_config('app.tenant_id', :'tenant', true);

\echo '== Debtors List, all showrooms, largest balance first (ReceivablesService.debtors)'
explain (analyze, buffers, costs off)
select b.customer_id, c.code, c.name, b.open_invoices, b.outstanding, b.oldest_open_issue_date, b.last_payment_at, b.outstanding > c.credit_limit
from debtor_balances b join customers c on c.id = b.customer_id and c.tenant_id = b.tenant_id
where b.tenant_id = :'tenant' and b.location_id = '00000000-0000-0000-0000-000000000000' and b.open_invoices > 0
order by b.outstanding desc, b.customer_id limit 50;

\echo '== Debtors List header (count, total, over limit)'
explain (analyze, buffers, costs off)
select count(*), sum(b.outstanding), count(*) filter (where b.outstanding > c.credit_limit)
from debtor_balances b join customers c on c.id = b.customer_id and c.tenant_id = b.tenant_id
where b.tenant_id = :'tenant' and b.location_id = '00000000-0000-0000-0000-000000000000' and b.open_invoices > 0;

\echo '== Receivables.outstandingFor (one customer)'
explain (analyze, buffers, costs off)
select outstanding from debtor_balances
where tenant_id = :'tenant' and customer_id = (select id from customers where tenant_id = :'tenant' order by id limit 1)
  and location_id = '00000000-0000-0000-0000-000000000000';

\echo '== Invoice history, newest first (InvoiceListing default sort)'
explain (analyze, buffers, costs off)
select id, number, issue_date, tax_inclusive_amount from invoices where tenant_id = :'tenant' order by issued_at desc, id desc limit 50;

\echo '== Bank statement, this month, running balance as a window (BankAccountService.statement)'
explain (analyze, buffers, costs off)
with account as (select id from bank_accounts where tenant_id = :'tenant' order by code limit 1)
select t.id, t.entry_date, t.amount, rb.running
from bank_transactions t
join (select id, sum(case when entry_type = 'CREDIT' then amount else -amount end) over (order by entry_date, created_at, id) as running
      from bank_transactions where tenant_id = :'tenant' and bank_account_id = (select id from account)
        and entry_date between date_trunc('month', current_date)::date and current_date) rb on rb.id = t.id
order by t.entry_date, t.created_at, t.id limit 50;

\echo '== Tenderable payable: one row per cashier per day, last 30 days (TenderableService.payable)'
explain (analyze, buffers, costs off)
select (timezone('Africa/Lagos', r.received_at))::date::text as day, r.cashier_user_id,
       coalesce(sum(r.amount) filter (where r.kind = 'RECEIPT' and r.channel = 'CASH' and r.confirmation_status in ('NOT_REQUIRED', 'APPROVED')), 0)
from payment_receipts r
where r.tenant_id = :'tenant' and r.status = 'ISSUED' and r.received_at >= now() - interval '30 days'
group by 1, 2 order by 1 desc, 2 limit 50;

\echo '== Stock movement report, newest posting order (stock_ledger_date_idx)'
explain (analyze, buffers, costs off)
select id, entry_date, qty_in, qty_out from stock_ledger_entries where tenant_id = :'tenant' order by entry_date, created_at, id limit 50;

\echo '== Customer search by name (trigram)'
explain (analyze, buffers, costs off)
select id, code, name from customers where tenant_id = :'tenant' and f_unaccent(lower(name)) like '%' || f_unaccent(lower('okafor stores 1234')) || '%' limit 20;

rollback;
