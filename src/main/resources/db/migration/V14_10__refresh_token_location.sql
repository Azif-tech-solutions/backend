-- The factory or showroom a session switched to (POST /auth/switch-location) survives a token
-- refresh. Tenant-scoped like every reference between tenant records (V14_9).
alter table refresh_tokens add column location_id uuid;
alter table refresh_tokens add constraint refresh_tokens_location_id_fkey
    foreign key (tenant_id, location_id) references locations (tenant_id, id);
