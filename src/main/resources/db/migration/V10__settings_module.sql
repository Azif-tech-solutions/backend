-- =============================================================================================
-- Settings module (docs/06-settings.md §2): the company profile every document, report and
-- invoice prints as its issuer. Module settings and custom-field definitions already exist
-- (V7_1); this adds only the profile.
-- =============================================================================================

create table company_profiles (
    tenant_id uuid primary key references tenants(id),
    trading_name varchar(200) not null,
    legal_name varchar(200),
    registration_number varchar(40),
    tin varchar(30),
    vat_registration_number varchar(60),
    nrs_business_id uuid,
    business_description varchar(200),
    email varchar(320),
    phone varchar(40) not null,
    phone_e164 varchar(20),
    website varchar(200),
    street_address varchar(300) not null,
    city varchar(120),
    postal_code varchar(20),
    state_code varchar(5) references nigeria_states(code),
    lga_code varchar(10) references nigeria_lgas(code),
    country_code varchar(2) not null default 'NG',
    -- One small image per tenant, kept in Postgres so it stays under RLS and in the database backup,
    -- and so PDFs render without a network call (docs/06 §2).
    logo bytea,
    logo_content_type varchar(20) check (logo_content_type is null or logo_content_type in ('image/png', 'image/jpeg')),
    logo_updated_at timestamptz,
    document_footer varchar(500),
    financial_year_start_month int not null default 1 check (financial_year_start_month between 1 and 12),
    onboarding_completed_at timestamptz,
    onboarding_skipped_steps jsonb not null default '[]'::jsonb,
    updated_by_user_id uuid references app_users(id),
    updated_at timestamptz not null default now(),
    check ((logo is null) = (logo_content_type is null)),
    check (logo is null or octet_length(logo) <= 524288)
);

-- Every existing tenant gets a profile (rule ST1). Done before RLS is enabled so it reaches every
-- tenant whatever role runs the migration. New tenants get theirs from the Settings TenantProvisioner.
insert into company_profiles (tenant_id, trading_name, phone, street_address)
select id, name, '', '' from tenants
on conflict do nothing;

alter table company_profiles enable row level security;
alter table company_profiles force row level security;
create policy company_profiles_scope on company_profiles
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
