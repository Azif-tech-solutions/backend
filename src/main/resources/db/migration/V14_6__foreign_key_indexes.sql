-- =============================================================================================
-- Indexes for foreign keys whose parent rows get deleted.
--
-- Deleting a row makes PostgreSQL look for rows that still reference it, by the foreign-key column
-- alone (`where invoice_id = $1`, no tenant_id). An index that leads with tenant_id can't answer
-- that, so each check scanned the child table: deleting one draft invoice on the scale tenant took
-- 41 s, checking invoice_payments (730k rows) and invoices' own reissue links (1M rows).
--
-- Only drafts are ever deleted (invoices with their lines, running costs, settings, saved views),
-- so only the tables referencing invoices and invoice lines need this. An index that serves a
-- foreign key leads with that column: it answers the delete's check and the tenant-scoped "rows of
-- this invoice" reads equally well (row-level security adds the tenant filter on top), so each
-- replaces the tenant-leading index it supersedes rather than sitting beside it.
-- =============================================================================================

-- Payments of an invoice (and the delete check).
create index concurrently if not exists invoice_payments_by_invoice_idx on invoice_payments (invoice_id);
drop index concurrently if exists invoice_payments_invoice_idx;

-- Credit notes against an invoice.
create index concurrently if not exists credit_notes_by_invoice_idx on credit_notes (invoice_id);
drop index concurrently if exists credit_notes_invoice_idx;

-- An invoice's deliveries, latest first (InvoiceExpressions' delivery status reads the latest).
create index concurrently if not exists invoice_deliveries_by_invoice_idx on invoice_deliveries (invoice_id, requested_at desc);
drop index concurrently if exists invoice_deliveries_invoice_idx;

-- Credit-note lines against an invoice line (draft lines are deleted and replaced).
create index concurrently if not exists credit_note_lines_by_line_idx on credit_note_lines (invoice_line_id);
drop index concurrently if exists credit_note_lines_line_idx;

-- Reissue links between invoices: few rows have them, so partial indexes stay tiny.
create index concurrently if not exists invoices_reissue_of_idx on invoices (reissue_of_invoice_id) where reissue_of_invoice_id is not null;
create index concurrently if not exists invoices_replaced_by_idx on invoices (replaced_by_invoice_id) where replaced_by_invoice_id is not null;
