-- =============================================================================================
-- Performance indexes for production-size data (millions of rows). Each one serves a specific
-- query, named in its comment, so that query reads an index range instead of scanning the table.
-- Every working index leads with tenant_id: row-level security filters on it, so the planner can
-- use the index for the RLS predicate and the query's own filters together.
--
-- CONCURRENTLY: building an index this way never blocks writes on a large live table. Postgres
-- can't do that inside a transaction, so this migration holds only concurrent index statements
-- and Flyway runs it outside one. `if not exists` makes a retried deploy safe.
-- =============================================================================================

-- ---- Joins from a parent to its child rows (a foreign key with no index makes every join scan) ----
-- Transfer lines of a page of transfers (InventoryOperationsService.transferSummaries).
create index concurrently if not exists stock_transfer_lines_transfer_idx on stock_transfer_lines (stock_transfer_id);
-- Ingredients of a page of recipes (ProductionService.listRecipes).
create index concurrently if not exists recipe_ingredients_recipe_idx on recipe_ingredients (recipe_id);
-- Rates of a publication (ExchangeRateService history).
create index concurrently if not exists exchange_rates_publication_idx on exchange_rates (publication_id);

-- ---- Inventory ----
-- One item's stock across locations: quantity on hand in the stock register, comprehensive report join.
create index concurrently if not exists stock_balances_item_idx on stock_balances (tenant_id, stock_item_id, location_id);
-- The stock register in name order, whole tenant or one location (InventoryStockService.listStockItems).
create index concurrently if not exists stock_items_name_idx on stock_items (tenant_id, name, id);
create index concurrently if not exists stock_items_location_name_idx on stock_items (tenant_id, location_id, name, id);
-- Members of a category (GroupCardService).
create index concurrently if not exists stock_items_category_idx on stock_items (tenant_id, category_id) where category_id is not null;
-- Stock Movement Report in posting order: tenant-wide and per location.
create index concurrently if not exists stock_ledger_date_idx on stock_ledger_entries (tenant_id, entry_date, created_at, id);
create index concurrently if not exists stock_ledger_location_date_idx on stock_ledger_entries (tenant_id, location_id, entry_date, created_at, id);
-- Material Receive Report, newest first, tenant-wide and per location.
create index concurrently if not exists stock_receipts_created_idx on stock_receipts (tenant_id, created_at desc, id desc);
create index concurrently if not exists stock_receipts_location_idx on stock_receipts (tenant_id, location_id, created_at desc, id desc);
-- An item's adjustment history.
create index concurrently if not exists quantity_adjustments_item_idx on quantity_adjustments (tenant_id, stock_item_id, created_at desc);

-- ---- Factory ----
-- Production runs, newest first, tenant-wide and per factory (ProductionService.listProductionRuns).
create index concurrently if not exists production_runs_run_at_idx on production_runs (tenant_id, run_at desc, id desc);
create index concurrently if not exists production_runs_location_idx on production_runs (tenant_id, location_id, run_at desc, id desc);
-- Special production runs, newest first, and per client.
create index concurrently if not exists special_production_runs_created_idx on special_production_runs (tenant_id, created_at desc, id desc);
create index concurrently if not exists special_production_runs_client_idx on special_production_runs (tenant_id, client_id, created_at desc);
-- Factory-to-factory trade offers, newest first, and by status.
create index concurrently if not exists factory_trade_offers_offered_idx on factory_trade_offers (tenant_id, offered_at desc, id desc);
create index concurrently if not exists factory_trade_offers_status_idx on factory_trade_offers (tenant_id, status, offered_at desc);
-- Costings awaiting approval at a factory.
create index concurrently if not exists production_costings_location_status_idx on production_costings (tenant_id, location_id, status);

-- ---- Default sort orders of tenant-wide lists ----
-- Receipts list, newest first, for users who see every location (ReceiptService.list).
create index concurrently if not exists pr_received_idx on payment_receipts (tenant_id, received_at desc, id desc);
-- Invoice history, newest first (InvoiceListing default sort issuedAt desc).
create index concurrently if not exists invoices_issued_at_idx on invoices (tenant_id, issued_at desc, id desc);

-- ---- Superseded ----
-- (tenant_id, location_id, stock_item_id, entry_date) is covered by stock_ledger_item_location_date_idx
-- (item queries) and stock_ledger_location_date_idx (location queries): one less index to update on
-- every stock movement.
drop index concurrently if exists stock_ledger_entries_item_idx;
