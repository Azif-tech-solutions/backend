-- Module 13 (Reports & Analytics), slice 2: the SCI statement permission (docs/13 §6). The SCI reads
-- from the owning modules' records at request time (no new report data), so this migration adds only
-- the permission; cost sections are masked without factory.costing.read.

insert into permission_catalog (code, description) values
    ('reports.sci.read', 'View the SCI (Statement of Comprehensive Income), company or per-location');

select grant_permission_to_default_roles('reports.sci.read', array['Administrator', 'Management', 'Accounts Personnel']);
