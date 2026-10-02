-- Foundation reference data for the electronic-invoicing module (com.zifiq.invoicing), sourced
-- verbatim from NRS's own real "Invoice Schema" API documentation (einvoice.nrs.gov.ng/docs --
-- POST base_url/api/v1/invoice/validate), supplied directly by the user after navigating the
-- (JS-rendered, non-fetchable) docs site themselves. These are the exact code/name/description
-- values NRS's UBL-based schema expects on an invoice payload -- every table here is global,
-- system-seeded, never tenant-created, the same pattern as vat_categories in V5.
--
-- Scope note: NRS's full lookup lists (HS subheadings, ISIC classes, UN/CEFACT unit-of-measure
-- codes, and Nigeria's 774 LGAs) run into the thousands of rows. Only the rows the user actually
-- supplied from the real NRS docs are seeded below -- fabricating the remainder from memory would
-- reintroduce the exact problem this migration exists to fix (V5's HSN/ISIC seed was a
-- research-backed approximation, not verified NRS data). Each table is structured so the full list
-- can be appended later without any schema change. Nigeria's 36 states + FCT are the one exception:
-- that list is short, stable, and matches the official ISO 3166-2:NG standard exactly (verified
-- against the 8-state sample NRS itself returned), so it is seeded in full.

-- 1. NRS tax_category codes -- the full enum accepted by invoice_line.tax_total[].tax_subtotal[].
--    tax_category.id in the real schema. Broader than Nigeria VAT alone (it also covers excise,
--    customs duty, sales tax, withholding, stamp duty, etc.), because the schema is built to
--    accommodate multiple tax regimes generically.
create table nrs_tax_categories (
    code varchar(40) primary key,
    name varchar(120) not null
);

insert into nrs_tax_categories (code, name) values
    ('STANDARD_GST', 'Standard Goods and Services Tax'),
    ('REDUCED_GST', 'Reduced Goods and Services Tax'),
    ('ZERO_GST', 'Zero Goods and Services Tax'),
    ('STANDARD_VAT', 'Standard Value-Added Tax'),
    ('REDUCED_VAT', 'Reduced Value-Added Tax'),
    ('ZERO_VAT', 'Zero Value-Added Tax'),
    ('STATE_SALES_TAX', 'State Sales Tax'),
    ('LOCAL_SALES_TAX', 'Local Sales Tax'),
    ('ALCOHOL_EXCISE_TAX', 'Alcohol Excise Tax'),
    ('TOBACCO_EXCISE_TAX', 'Tobacco Excise Tax'),
    ('FUEL_EXCISE_TAX', 'Fuel Excise Tax'),
    ('SOCIAL_SECURITY_TAX', 'Social Security Tax'),
    ('MEDICARE_TAX', 'Medicare Tax'),
    ('REAL_ESTATE_TAX', 'Real Estate Tax'),
    ('PERSONAL_PROPERTY_TAX', 'Personal Property Tax'),
    ('CARBON_TAX', 'Carbon Tax'),
    ('PLASTIC_TAX', 'Plastic Tax'),
    ('IMPORT_DUTY', 'Import Duty'),
    ('EXPORT_DUTY', 'Export Duty'),
    ('LUXURY_TAX', 'Luxury Tax'),
    ('SERVICE_TAX', 'Service Tax'),
    ('TOURISM_TAX', 'Tourism Tax'),
    ('WITHHOLDING_TAX', 'Withholding Tax'),
    ('STAMP_DUTY', 'Stamp Duty'),
    ('EXEMPTED', 'Tax Exemption');

-- Map ZifIQ's own tenant-facing VAT categories (V5, Nigeria Tax Act 2025) onto the matching NRS
-- tax_category.id, so any future invoice submission can pull the correct code straight off the
-- stock item's vat_category_id instead of re-deriving it.
alter table vat_categories add column nrs_tax_category_code varchar(40) references nrs_tax_categories(code);

update vat_categories set nrs_tax_category_code = 'STANDARD_VAT' where code = 'STANDARD_RATE';
update vat_categories set nrs_tax_category_code = 'ZERO_VAT' where code = 'ZERO_RATED';
update vat_categories set nrs_tax_category_code = 'EXEMPTED' where code = 'EXEMPT';

alter table vat_categories alter column nrs_tax_category_code set not null;

-- 2. invoice_type_code -- field 1.6 of the real schema ("a numeric code representing different
--    invoice types").
create table nrs_invoice_type_codes (
    code varchar(3) primary key,
    name varchar(80) not null,
    description varchar(200) not null
);

insert into nrs_invoice_type_codes (code, name, description) values
    ('380', 'Credit Note', 'Credit Note'),
    ('381', 'Commercial Invoice', 'Commercial Invoice'),
    ('384', 'Debit Note', 'Debit Note'),
    ('385', 'Self Billed Invoice', 'Self Billed Invoice'),
    ('388', 'Factored Invoice', 'Factored Invoice'),
    ('389', 'Statement of Account', 'Statement of Account');

-- 3. payment_means_code -- field 1.30 of the real schema (payment_means[].payment_means_code).
create table nrs_payment_means_codes (
    code varchar(3) primary key,
    name varchar(80) not null,
    description varchar(200) not null
);

insert into nrs_payment_means_codes (code, name, description) values
    ('10', 'Cash', 'Cash'),
    ('20', 'Cheque', 'Cheque'),
    ('30', 'Credit Transfer', 'Credit Transfer'),
    ('31', 'Debit Transfer', 'Debit Transfer'),
    ('42', 'ACH Credit', 'ACH Credit'),
    ('43', 'ACH Debit', 'ACH Debit');

-- 4. Unit-of-measure codes -- UN/CEFACT Recommendation 20 style, used in invoice_line.price.
--    price_unit. Distinct from units_of_measure (V4): that table is each tenant's own operational
--    unit list (e.g. "KG", "Carton"); this is NRS's fixed compliance code list for what goes on the
--    wire. Sample as supplied from the real docs -- UN/CEFACT Rec 20 runs to several hundred codes;
--    extend as more are confirmed.
create table nrs_unit_of_measure_codes (
    code varchar(10) primary key,
    name varchar(120) not null,
    description varchar(300) not null
);

insert into nrs_unit_of_measure_codes (code, name, description) values
    ('10', 'group', 'A unit of count defining the number of groups (group: set of items classified together).'),
    ('11', 'outfit', 'A unit of count defining the number of outfits (outfit: a complete set of equipment / materials / objects used for a specific purpose).'),
    ('13', 'ration', 'A unit of count defining the number of rations (ration: a single portion of provisions).'),
    ('14', 'shot', 'A unit of liquid measure, especially related to spirits.'),
    ('15', 'stick, military', 'A unit of count defining the number of military sticks (military stick: bombs or paratroops released in rapid succession from an aircraft).'),
    ('20', 'twenty foot container', 'A unit of count defining the number of shipping containers that measure 20 foot in length.'),
    ('21', 'forty foot container', 'A unit of count defining the number of shipping containers that measure 40 foot in length.'),
    ('22', 'decilitre per gram', 'decilitre per gram'),
    ('23', 'gram per cubic centimetre', 'gram per cubic centimetre');

-- 5. HSN (Harmonized System) codes at true subheading granularity (e.g. "1006.30"), as used by
--    invoice_line.hsn_code for goods line items. hsn_sections (V5) stays as the broad 21-section
--    grouping; this table hangs the real dotted subheading codes NRS actually expects off each
--    section. Sample as supplied (WCO Chapter 01, Section I) -- the full HS tariff runs to several
--    thousand subheadings; extend as more are confirmed.
create table hsn_codes (
    code varchar(10) primary key,
    section_code varchar(6) not null references hsn_sections(code),
    description varchar(500) not null
);
create index hsn_codes_section_idx on hsn_codes (section_code);

insert into hsn_codes (code, section_code, description) values
    ('0101.21', 'I', 'Horses; live, pure-bred breeding animals'),
    ('0101.29', 'I', 'Horses; live, other than pure-bred breeding animals'),
    ('0101.30', 'I', 'Asses; live'),
    ('0101.90', 'I', 'Mules and hinnies; live'),
    ('0102.21', 'I', 'Cattle; live, pure-bred breeding animals'),
    ('0102.29', 'I', 'Cattle; live, other than pure-bred breeding animals'),
    ('0102.31', 'I', 'Buffalo; live, pure-bred breeding animals'),
    ('0102.39', 'I', 'Buffalo; live, other than pure-bred breeding animals'),
    ('0102.90', 'I', 'Bovine animals; live, other than cattle and buffalo'),
    ('1006.30', 'I', 'Cereals; rice, semi-milled or wholly milled, whether or not polished or glazed');

-- 6. ISIC Rev.4 codes at true 4-digit class granularity, as used by invoice_line.isic_code for
--    service line items. isic_sections (V5) stays as the broad 21-section grouping; this table
--    hangs the real 4-digit class codes off each section. Sample as supplied (Section A,
--    Agriculture) -- ISIC Rev.4 runs to ~419 classes; extend as more are confirmed.
create table isic_classes (
    code varchar(4) primary key,
    section_code varchar(1) not null references isic_sections(code),
    description varchar(300) not null
);
create index isic_classes_section_idx on isic_classes (section_code);

insert into isic_classes (code, section_code, description) values
    ('0111', 'A', 'Growing of cereals (except rice), leguminous crops and oil seeds'),
    ('0112', 'A', 'Growing of rice'),
    ('0113', 'A', 'Growing of vegetables and melons, roots and tubers'),
    ('0114', 'A', 'Growing of sugar cane'),
    ('0115', 'A', 'Growing of tobacco'),
    ('0116', 'A', 'Growing of fibre crops'),
    ('0119', 'A', 'Growing of other non-perennial crops'),
    ('0121', 'A', 'Growing of grapes'),
    ('0122', 'A', 'Growing of tropical and subtropical fruits'),
    ('0123', 'A', 'Growing of citrus fruits'),
    ('0124', 'A', 'Growing of pome fruits and stone fruits');

-- 7. Nigeria states -- used in postal_address.state (e.g. accounting_supplier_party /
--    accounting_customer_party). Seeded in full: ISO 3166-2:NG is a stable, officially documented
--    standard and matches the NRS-supplied sample exactly, so there is no truncation risk here.
create table nigeria_states (
    code varchar(5) primary key,
    name varchar(40) not null
);

insert into nigeria_states (code, name) values
    ('NG-AB', 'Abia'),
    ('NG-AD', 'Adamawa'),
    ('NG-AK', 'Akwa Ibom'),
    ('NG-AN', 'Anambra'),
    ('NG-BA', 'Bauchi'),
    ('NG-BY', 'Bayelsa'),
    ('NG-BE', 'Benue'),
    ('NG-BO', 'Borno'),
    ('NG-CR', 'Cross River'),
    ('NG-DE', 'Delta'),
    ('NG-EB', 'Ebonyi'),
    ('NG-ED', 'Edo'),
    ('NG-EK', 'Ekiti'),
    ('NG-EN', 'Enugu'),
    ('NG-FC', 'Federal Capital Territory'),
    ('NG-GO', 'Gombe'),
    ('NG-IM', 'Imo'),
    ('NG-JI', 'Jigawa'),
    ('NG-KD', 'Kaduna'),
    ('NG-KN', 'Kano'),
    ('NG-KT', 'Katsina'),
    ('NG-KE', 'Kebbi'),
    ('NG-KO', 'Kogi'),
    ('NG-KW', 'Kwara'),
    ('NG-LA', 'Lagos'),
    ('NG-NA', 'Nasarawa'),
    ('NG-NI', 'Niger'),
    ('NG-OG', 'Ogun'),
    ('NG-ON', 'Ondo'),
    ('NG-OS', 'Osun'),
    ('NG-OY', 'Oyo'),
    ('NG-PL', 'Plateau'),
    ('NG-RI', 'Rivers'),
    ('NG-SO', 'Sokoto'),
    ('NG-TA', 'Taraba'),
    ('NG-YO', 'Yobe'),
    ('NG-ZA', 'Zamfara');

-- 8. Nigeria Local Government Areas -- used in postal_address.lga. Nigeria has 774 LGAs in total;
--    only Abia state's LGAs (as supplied from the real NRS docs) are seeded below. NRS's own
--    3-letter LGA code suffixes aren't a standardized external scheme, so the remaining ~760 are
--    deliberately left unseeded rather than guessed -- extend state-by-state as confirmed.
create table nigeria_lgas (
    code varchar(10) primary key,
    state_code varchar(5) not null references nigeria_states(code),
    name varchar(80) not null
);
create index nigeria_lgas_state_idx on nigeria_lgas (state_code);

insert into nigeria_lgas (code, state_code, name) values
    ('NG-AB-ANO', 'NG-AB', 'Aba North'),
    ('NG-AB-ASO', 'NG-AB', 'Aba South'),
    ('NG-AB-ARO', 'NG-AB', 'Arochukwu'),
    ('NG-AB-BEN', 'NG-AB', 'Bende'),
    ('NG-AB-IKW', 'NG-AB', 'Ikwuano'),
    ('NG-AB-INN', 'NG-AB', 'Isiala-Ngwa North'),
    ('NG-AB-INS', 'NG-AB', 'Isiala-Ngwa South'),
    ('NG-AB-ISU', 'NG-AB', 'Isuikwato'),
    ('NG-AB-ONW', 'NG-AB', 'Obi Nwa'),
    ('NG-AB-OHA', 'NG-AB', 'Ohafia'),
    ('NG-AB-ONG', 'NG-AB', 'Osisioma Ngwa'),
    ('NG-AB-UGW', 'NG-AB', 'Ugwunagbo'),
    ('NG-AB-UEA', 'NG-AB', 'Ukwa East'),
    ('NG-AB-UWE', 'NG-AB', 'Ukwa West');
