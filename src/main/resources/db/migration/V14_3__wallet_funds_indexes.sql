-- Tenderable Wallet Funds (docs/07 §4.11) reads its two sources newest first, a page at a time,
-- and merges them. Concurrent, like V13, so neither build blocks writes on a large table.

-- Dealers' wallet fundings, newest first, whole tenant (wf_customer_idx covers one customer).
create index concurrently if not exists wf_created_idx on wallet_fundings (tenant_id, created_at desc, id desc);

-- Customer ledger entries of one type (return credits), newest first by entry date
-- (CustomerQueries.ledgerEntries with newestFirst, and its ledgerTotal).
create index concurrently if not exists cle_type_date_idx on customer_ledger_entries (tenant_id, entry_type, entry_date desc, created_at desc, id desc);
