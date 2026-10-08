-- File storage metadata (docs/03 §8.4). The bytes live in S3 under storage_key; this row is the
-- tenant-scoped record of what was uploaded, by whom and for what. Rows are never deleted: a removed
-- file is marked DELETED (the object is kept for audit until a lifecycle rule expires it).
create table stored_files (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    purpose varchar(60) not null,                 -- registered by the owning module, e.g. 'general.attachment'
    storage_key varchar(400) not null,            -- '<prefix>tenants/<tenant>/<purpose>/<yyyy>/<mm>/<id>' — never derived from the file name
    original_name varchar(255) not null,          -- as uploaded, for the download's Content-Disposition only
    content_type varchar(100) not null,           -- detected from the bytes, not trusted from the client
    size_bytes bigint not null check (size_bytes > 0),
    sha256 char(64) not null,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE', 'DELETED')),
    linked_type varchar(60),                      -- optional owner record, e.g. 'customer'
    linked_id uuid,
    uploaded_by_user_id uuid not null references app_users(id),
    uploaded_at timestamptz not null default now(),
    deleted_by_user_id uuid references app_users(id),
    deleted_at timestamptz,
    unique (storage_key)
);
create index stored_files_purpose_idx on stored_files (tenant_id, purpose, uploaded_at desc);
create index stored_files_linked_idx on stored_files (tenant_id, linked_type, linked_id) where linked_id is not null;
alter table stored_files enable row level security;
alter table stored_files force row level security;
create policy stored_files_scope on stored_files
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
