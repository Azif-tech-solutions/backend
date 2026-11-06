-- Module 13 (Reports & Analytics), slice 3a: the Out-Source Stock Report permission (docs/13 §4.3, §6).
-- Like the SCI, the report reads the owning modules' records at request time, so this migration adds
-- only the permission; outsourced consignments are showroom stock entries of OUTSOURCED products.

insert into permission_catalog (code, description) values
    ('reports.outsource.read', 'View the Out-Source Stock Report (outsourced consignment sell-through)');

select grant_permission_to_default_roles('reports.outsource.read', array['Administrator', 'Management', 'Stock Keeper']);
