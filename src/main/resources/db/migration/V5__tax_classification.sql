-- Tax/trade classification reference data (owned by com.zifiq.account -- see sketch/index.html's
-- "Finance & Accounting > Tendering & Financial Controls (VAT Control, Exchange Rates)"). These
-- three catalogs are GLOBAL and system-seeded exactly once below, the same pattern as
-- permission_catalog in V1: no tenant_id, no RLS, never tenant-created. A tenant activates the
-- subset of VAT categories it uses via tenant_vat_categories; HSN/ISIC are plain shared lookups
-- every tenant's products classify against directly.
--
-- Scope note: HSN and ISIC are seeded at SECTION level only (21 rows each), not the full
-- Chapter/Division/Class granularity (97 HS chapters, ~419 ISIC classes). The section-level
-- structure is stable, internationally standardized, and verifiable; the deeper levels need an
-- authoritative source document (the actual Nigeria Customs tariff book / ISIC Rev.4 class list)
-- that wasn't available when this was seeded -- extend these tables once one is supplied, rather
-- than risk transcribing thousands of codes from memory.

-- VAT categories under the Nigeria Tax Act 2025 (NRS -- Nigeria Revenue Service, formerly FIRS),
-- effective 1 January 2026.
create table vat_categories (
    id uuid primary key default gen_random_uuid(),
    code varchar(30) not null unique check (code in ('STANDARD_RATE', 'ZERO_RATED', 'EXEMPT')),
    name varchar(100) not null,
    rate numeric(5,2) not null,
    input_vat_recoverable boolean not null,
    description varchar(600) not null
);

insert into vat_categories (code, name, rate, input_vat_recoverable, description) values
    ('STANDARD_RATE', 'Standard Rate', 7.50, true,
     'Standard-rated supplies under the Nigeria Tax Act 2025: manufactured goods, professional/consulting services, digital services, retail sales, and most B2B transactions. VAT charged at 7.5%, fully recoverable as input VAT.'),
    ('ZERO_RATED', 'Zero-Rated', 0.00, true,
     'Zero-rated supplies under the Nigeria Tax Act 2025: no VAT charged on the final sale (0%), but input VAT on related purchases remains fully recoverable. Includes exports, basic staples (rice, beans, yam, cassava, other locally produced agricultural goods), textbooks and school supplies, and tuition fees.'),
    ('EXEMPT', 'Exempt', 0.00, false,
     'Exempt supplies under the Nigeria Tax Act 2025: no VAT charged, and input VAT on related costs is NOT recoverable. Includes most financial services, residential rent, certain land/building sales, and specified government services.');

-- HSN / HS (Harmonized System) sections -- WCO-standard goods classification, 21 sections.
create table hsn_sections (
    id uuid primary key default gen_random_uuid(),
    code varchar(6) not null unique, -- up to "XVIII" (5 chars)
    chapter_range varchar(20) not null,
    name varchar(200) not null
);

insert into hsn_sections (code, chapter_range, name) values
    ('I', '01-05', 'Live animals; animal products'),
    ('II', '06-14', 'Vegetable products'),
    ('III', '15', 'Animal or vegetable fats and oils and their cleavage products; prepared edible fats; animal or vegetable waxes'),
    ('IV', '16-24', 'Prepared foodstuffs; beverages, spirits and vinegar; tobacco and manufactured tobacco substitutes'),
    ('V', '25-27', 'Mineral products'),
    ('VI', '28-38', 'Products of the chemical or allied industries'),
    ('VII', '39-40', 'Plastics and articles thereof; rubber and articles thereof'),
    ('VIII', '41-43', 'Raw hides and skins, leather, furskins and articles thereof; saddlery and harness; travel goods, handbags'),
    ('IX', '44-46', 'Wood and articles of wood; wood charcoal; cork and articles of cork; manufactures of straw, basketware'),
    ('X', '47-49', 'Pulp of wood; paper and paperboard and articles thereof; printed books, newspapers and other printed matter'),
    ('XI', '50-63', 'Textiles and textile articles'),
    ('XII', '64-67', 'Footwear, headgear, umbrellas, walking sticks; prepared feathers and articles made therewith; artificial flowers'),
    ('XIII', '68-70', 'Articles of stone, plaster, cement, asbestos, mica; ceramic products; glass and glassware'),
    ('XIV', '71', 'Natural or cultured pearls, precious or semi-precious stones, precious metals; imitation jewellery; coin'),
    ('XV', '72-83', 'Base metals and articles of base metal'),
    ('XVI', '84-85', 'Machinery and mechanical appliances; electrical equipment; sound/television recorders and reproducers'),
    ('XVII', '86-89', 'Vehicles, aircraft, vessels and associated transport equipment'),
    ('XVIII', '90-92', 'Optical, photographic, measuring, precision, medical instruments; clocks and watches; musical instruments'),
    ('XIX', '93', 'Arms and ammunition; parts and accessories thereof'),
    ('XX', '94-96', 'Miscellaneous manufactured articles'),
    ('XXI', '97', 'Works of art, collectors'' pieces and antiques');

-- ISIC Rev.4 sections -- UN Statistics Division, 21 sections.
create table isic_sections (
    id uuid primary key default gen_random_uuid(),
    code varchar(1) not null unique,
    name varchar(200) not null
);

insert into isic_sections (code, name) values
    ('A', 'Agriculture, forestry and fishing'),
    ('B', 'Mining and quarrying'),
    ('C', 'Manufacturing'),
    ('D', 'Electricity, gas, steam and air conditioning supply'),
    ('E', 'Water supply; sewerage, waste management and remediation activities'),
    ('F', 'Construction'),
    ('G', 'Wholesale and retail trade; repair of motor vehicles and motorcycles'),
    ('H', 'Transportation and storage'),
    ('I', 'Accommodation and food service activities'),
    ('J', 'Information and communication'),
    ('K', 'Financial and insurance activities'),
    ('L', 'Real estate activities'),
    ('M', 'Professional, scientific and technical activities'),
    ('N', 'Administrative and support service activities'),
    ('O', 'Public administration and defence; compulsory social security'),
    ('P', 'Education'),
    ('Q', 'Human health and social work activities'),
    ('R', 'Arts, entertainment and recreation'),
    ('S', 'Other service activities'),
    ('T', 'Activities of households as employers; undifferentiated goods- and services-producing activities of households for own use'),
    ('U', 'Activities of extraterritorial organizations and bodies');

-- Per-tenant VAT category activation -- a tenant picks which of the three global categories it
-- actually uses; stock_items.vat_category_id is validated against this at the service layer.
create table tenant_vat_categories (
    tenant_id uuid not null references tenants(id),
    vat_category_id uuid not null references vat_categories(id),
    is_active boolean not null default true,
    activated_by_user_id uuid not null references app_users(id),
    activated_at timestamptz not null default now(),
    primary key (tenant_id, vat_category_id)
);
alter table tenant_vat_categories enable row level security;
alter table tenant_vat_categories force row level security;
create policy tenant_vat_categories_scope on tenant_vat_categories
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Every product carries a VAT category, an HSN section, and an ISIC section.
alter table stock_items
    add column vat_category_id uuid not null references vat_categories(id),
    add column hsn_section_id uuid not null references hsn_sections(id),
    add column isic_section_id uuid not null references isic_sections(id);

insert into permission_catalog (code, description) values
    ('accounting.vat.manage', 'Activate VAT categories for the tenant (VAT Control)');
