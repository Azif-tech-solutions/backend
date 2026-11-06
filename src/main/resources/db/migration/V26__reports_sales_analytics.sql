-- Module 13 (Reports & Analytics), slice 3b: the Sales analytics permission (docs/13 §4.4, §6).
-- The analytics read the owning modules' records at request time, so this migration adds only the
-- permission; cost columns (COGS, margin) are masked without factory.costing.read.

insert into permission_catalog (code, description) values
    ('reports.sales.read', 'View Sales analytics (grouped sales with quantity, revenue, discounts, VAT and margin)');

select grant_permission_to_default_roles('reports.sales.read', array['Administrator', 'Management', 'Accounts Personnel']);
