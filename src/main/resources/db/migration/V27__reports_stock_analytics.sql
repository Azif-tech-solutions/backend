-- Module 13 (Reports & Analytics), slice 3c: the Stock analytics permission (docs/13 §4.5, §6).
-- The views read the inventory ledger and item master at request time, so this migration adds only the
-- permission; stock value is masked without factory.costing.read.

insert into permission_catalog (code, description) values
    ('reports.stock.read', 'View Stock analytics (value, slow-moving/dead, days of cover)');

select grant_permission_to_default_roles('reports.stock.read', array['Administrator', 'Management', 'Stock Keeper']);
