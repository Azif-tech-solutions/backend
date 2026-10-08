-- Showroom prerequisites from docs/03 that the V7 pass did not ship: storage areas (§11.2) and the
-- document registry (§8.1). The Showroom module itself follows in V9_2.

-- ---------------------------------------------------------------------------------------------
-- Storage areas: child locations of a factory or showroom (Shop Floor, Warehouse, ...), so the
-- stock ledger and balances work per area without any change.
-- ---------------------------------------------------------------------------------------------
alter table locations add column parent_location_id uuid references locations(id);
alter table locations add column area_kind varchar(20)
    check (area_kind is null or area_kind in ('SHOP_FLOOR', 'WAREHOUSE', 'RECEIVING', 'QUARANTINE', 'OTHER'));
alter table locations drop constraint locations_type_check;
alter table locations add constraint locations_type_check check (type in ('FACTORY', 'SHOWROOM', 'STORAGE_AREA'));
alter table locations add constraint locations_area_parent_check
    check ((type = 'STORAGE_AREA') = (parent_location_id is not null));
alter table locations add constraint locations_area_requires_kind
    check ((type = 'STORAGE_AREA') = (area_kind is not null));
create index locations_parent_idx on locations (tenant_id, parent_location_id) where parent_location_id is not null;

-- Areas never nest and only factories/showrooms have them.
create function locations_area_parent_is_root() returns trigger language plpgsql as $$
begin
    if new.parent_location_id is not null and exists (
        select 1 from locations p where p.id = new.parent_location_id and p.type = 'STORAGE_AREA'
    ) then
        raise exception 'A storage area must belong to a factory or showroom, not another area';
    end if;
    return new;
end $$;
create trigger locations_area_parent_is_root before insert or update of parent_location_id on locations
    for each row execute function locations_area_parent_is_root();

-- A user is assigned to a factory or showroom (and thereby its areas), never to a single area.
create function user_location_is_root() returns trigger language plpgsql as $$
begin
    if new.location_id is not null and exists (
        select 1 from locations l where l.id = new.location_id and l.type = 'STORAGE_AREA'
    ) then
        raise exception 'Users are assigned to a factory or showroom, not to a storage area';
    end if;
    return new;
end $$;
create trigger user_location_is_root before insert or update of location_id on user_location_assignments
    for each row execute function user_location_is_root();

-- ---------------------------------------------------------------------------------------------
-- Document registry (Transaction Receipts): one row per issued invoice, waybill, receipt or
-- pro-forma, written by the issuing module in the same transaction (docs/03 §8).
-- ---------------------------------------------------------------------------------------------
create table issued_documents (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(40) not null,
    doc_type varchar(20) not null check (doc_type in ('INVOICE', 'CREDIT_NOTE', 'WAYBILL', 'RECEIPT', 'SALE_RECEIPT', 'PRO_FORMA')),
    source_module varchar(30) not null,
    source_id uuid not null,
    location_id uuid references locations(id),
    party_name varchar(200),
    party_customer_id uuid,
    value_amount numeric(14,2),
    issued_by_user_id uuid not null references app_users(id),
    issued_at timestamptz not null default now(),
    status varchar(20) not null default 'ISSUED' check (status in ('ISSUED', 'VOIDED')),
    voided_at timestamptz,
    reprint_count int not null default 0,
    search_vector tsvector generated always as (
        to_tsvector('simple', f_unaccent(ref || ' ' || coalesce(party_name, '')))
    ) stored,
    unique (tenant_id, ref)
);
create index issued_documents_type_time_idx on issued_documents (tenant_id, doc_type, issued_at desc);
create index issued_documents_location_idx on issued_documents (tenant_id, location_id, issued_at desc);
create index issued_documents_source_idx on issued_documents (tenant_id, source_module, source_id);
create index issued_documents_party_trgm on issued_documents using gin (f_unaccent(lower(party_name)) gin_trgm_ops);
create index issued_documents_search_idx on issued_documents using gin (search_vector);
alter table issued_documents enable row level security;
alter table issued_documents force row level security;
create policy issued_documents_scope on issued_documents
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create table document_reprints (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    issued_document_id uuid not null references issued_documents(id),
    reprinted_by_user_id uuid not null references app_users(id),
    reprinted_at timestamptz not null default now(),
    reason varchar(200)
);
create index document_reprints_doc_idx on document_reprints (tenant_id, issued_document_id, reprinted_at desc);
alter table document_reprints enable row level security;
alter table document_reprints force row level security;
create policy document_reprints_scope on document_reprints
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

insert into permission_catalog (code, description) values
    ('platform.documents.read', 'List issued documents (Transaction Receipts); each document type also needs its own read permission');
select grant_permission_to_default_roles('platform.documents.read', array['Administrator', 'Management', 'Accounts Personnel', 'Cashier', 'Stock Keeper']);

-- Waybills issued before the registry existed.
do $$
declare
    t record;
begin
    for t in select id from tenants loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into issued_documents (tenant_id, ref, doc_type, source_module, source_id, location_id, party_name, issued_by_user_id, issued_at)
        select st.tenant_id, st.waybill_ref, 'WAYBILL', 'inventory', st.id, st.source_location_id, dest.name, st.dispatched_by_user_id, st.dispatched_at
        from stock_transfers st
        join locations dest on dest.id = st.destination_location_id
        where st.tenant_id = t.id
        on conflict (tenant_id, ref) do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end $$;
