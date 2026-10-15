-- The Debtors List header (count, total, how many over their credit limit) joins every debtor to
-- their credit limit. Covering it lets that join read this narrow index instead of the wide
-- customers rows: measured on the scale tenant (80k debtors, 200k customers), 929 ms → 210 ms.
-- Concurrent, like V13, so the build never blocks writes on customers.
create index concurrently if not exists customers_credit_limit_idx on customers (tenant_id, id) include (credit_limit);
