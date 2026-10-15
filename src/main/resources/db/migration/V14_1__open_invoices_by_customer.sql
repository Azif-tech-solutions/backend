-- A customer's issued invoices in issue order: their open invoices for receipt allocation and
-- statements (InvoiceService.openInvoicesFor), and the debtor_balances refresh. Concurrent, like
-- V13, so building it never blocks writes on a large invoices table.
create index concurrently if not exists invoices_customer_issued_idx on invoices (tenant_id, customer_id, issue_date, id) where status = 'ISSUED';
