# 08 — Electronic Invoicing Module

Status: **design finalized, not yet implemented** (NRS reference data in this package — V6 — is already shipped and is reused, not redesigned)
Owner package: `com.zifiq.invoicing` (substrate module — exposes `com.zifiq.invoicing.spi`)
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi`, `settings :: spi`, `account :: spi`
Depended on by: `cashier` (issues invoices at POS / from pro-formas, records sale payments, credit check), `history`, `reports`, `dashboard`, `ai`.
Migration: `V12__electronic_invoicing_module.sql`
Replaces: the mock `ElectronicInvoicingService` (`generateInvoice` with hard-coded ₦1,250.00, `deliverInvoice`, `getInvoiceHistory`), and `menu`'s mock Debtors List.

---

## 1. Scope

### 1.1 PRD §5.10 phase scope (verbatim intent)

"Scoped as internal digital invoicing for this phase … does NOT integrate with Nigeria's NRS/FIRS Merchant-Buyer Solution for regulatory e-invoice transmission (no Invoice Reference Number, Cryptographic Stamp, QR code, or Access Point Provider submission). The data model reserves fields for these identifiers so the module can be extended to full regulatory compliance in a future phase without a redesign." This doc does exactly that: every NRS invoice-schema field the business data can already supply is stored now, in NRS's own vocabulary (V6 codes), and the four regulatory identifiers are reserved, null.

### 1.2 In scope

| Feature | PRD | Screen |
|---|---|---|
| Invoice generation — automatic at POS checkout for a registered customer; on request for a walk-in sale; from a confirmed pro-forma | 5.10 Invoice Generation | `invoicing/InvoiceGeneration.jsx`, `menu/POS.jsx` |
| Sequential, unique, non-reusable numbers; issued financial fields immutable | PRD §6 rule 14 | — |
| VAT per issuing showroom's VAT Control setting | PRD §6 rule 9 | VAT badge on `InvoiceGeneration.jsx` |
| Credit notes, void, void-and-reissue | 5.10, rule 14 | `InvoiceStatus.jsx` (Void), — |
| PDF rendering, download, e-mail delivery, delivery status, resend/redirect, bulk ZIP export | 5.10 Invoice PDF & Email Delivery | `invoicing/InvoiceDelivery.jsx` |
| Status & payment tracking (Draft/Issued/Partially Paid/Paid/Overdue/Void), record payment, outstanding receivables | 5.10 Status & Payment Tracking | `invoicing/InvoiceStatus.jsx` |
| History & search with linked pro-forma, payments, waybills | 5.10 History & Search | `invoicing/InvoiceHistory.jsx` |
| Debtors List (per-customer receivables, ageing, over-limit) | 5.8.3 | `menu/DebtorsList.jsx` |
| NRS-shaped data (tax categories, invoice type code, payment means, HS/ISIC codes, party addresses) | NRS Invoice Schema; PRD §9 risk mitigation | Regulatory identifiers panel |

### 1.3 Decisions

1. **Invoicing owns the invoice; Sales & POS decides *when* one is issued.** Invoicing is built before Sales & POS and may not read its sales or pro-formas (doc 03 §2.3). So the HTTP actions "generate from POS sale" and "convert pro-forma" live in Sales & POS (doc 09), which assembles the lines and calls `InvoiceIssuer.issue(...)` here. `InvoiceGeneration.jsx`'s two source tabs therefore call doc 09 endpoints; every other invoicing screen calls this module.
2. **Numbers are assigned at issue, inside the issuing transaction** (`next("INV")`, doc 03 §6) — drafts carry no number, so a discarded draft never burns one and the sequence stays gap-free.
3. **Status is derived, not typed.** `status` stores only the lifecycle state a person sets (`DRAFT`, `ISSUED`, `VOID`); `paymentStatus` (`UNPAID`/`PARTIALLY_PAID`/`PAID`) and overdue-ness are computed from linked payments, credit notes and the due date — "Status is derived from the payments linked to each invoice and its due date — it is never set by hand" (`InvoiceStatus.jsx`). A cached `amount_paid` makes it filterable in SQL; it is kept correct when Account declines/voids a receipt via an event consumer (doc 03 §7.6).
4. **Credit notes are financial only — they never move stock.** Physical returns go through Showroom Returns (doc 05), which credit the customer's wallet; the wallet can then settle the invoice. This prevents a customer being compensated twice (wallet credit *and* debt reduction) for the same goods.
5. **Line VAT** = the issuing location's VAT rate when the item's VAT category is Standard Rate and the location's collection is enabled; 0 for Zero-Rated and Exempt items or when collection is disabled. VAT is computed and rounded per line (half-up, 2 dp) and totals are sums of lines, so "the invoice can never state a total its own lines disagree with".
6. **The issuer and buyer are snapshotted onto the invoice** (company profile, issuing location, buyer details) so later edits to Settings or Customers never change an issued invoice (rule 14).
7. **E-mail goes through the notification outbox** (retryable without renumbering). The shipped e-mail adapter only logs; real delivery needs an e-mail provider — §11 open item. Until then "Sent" means accepted by the outbox dispatcher.

---

## 2. Schema — migration `V12__electronic_invoicing_module.sql`

All tables: `tenant_id` + standard RLS (omitted). Money `numeric(14,2)`.

### 2.1 Granular classification on stock items (inventory side-effect)

NRS invoice lines carry a dotted HS subheading (`invoice_line.hsn_code`, e.g. `1006.30`) for goods or a 4-digit ISIC class (`isic_code`) for services; shipped `stock_items` only has the 21 broad sections (V5).

```sql
alter table stock_items
    add column hsn_code varchar(10) references hsn_codes(code),      -- V6 global table (sample-seeded; extended over time)
    add column isic_code varchar(4) references isic_classes(code),
    add column nrs_unit_code varchar(10) references nrs_unit_of_measure_codes(code),
    add column is_service boolean not null default false;             -- services use isic_code, goods hsn_code
-- Section consistency (hsn_codes.section_code = the item's hsn_section) spans two tables, so it is checked in the service, not by a constraint.
```
Endpoint (inventory): `PATCH /api/v1/inventory/stock-items/{id}/classification` — perm `accounting.vat.manage` or `inventory.stock.manage`. Body `{ hsnCode?, isicCode?, nrsUnitCode?, isService? }`; the HS code's section must equal the item's `hsn_section_id` section → `400 HSN_SECTION_MISMATCH` (likewise ISIC). All optional: V6 only sample-seeds these tables, so invoices must never fail for a missing granular code (§4.12 falls back to the section).

### 2.2 `invoices`

```sql
create table invoices (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    number varchar(20),                                  -- INV-n, set at issue; null while DRAFT
    status varchar(10) not null check (status in ('DRAFT','ISSUED','VOID')),
    invoice_type_code varchar(3) not null default '381' references nrs_invoice_type_codes(code),  -- 381 Commercial Invoice (per the NRS list in V6)
    invoice_kind varchar(3) not null check (invoice_kind in ('B2B','B2C','B2G')),   -- NRS invoice_kind; B2B when the buyer has a TIN
    source_type varchar(20) not null check (source_type in ('POS_SALE','PRO_FORMA')),
    source_id uuid not null,                             -- cashier sale or pro-forma id (plain uuid, cross-module)
    source_ref varchar(30) not null,                     -- TXN-n / PF-n
    pro_forma_ref varchar(30),                           -- when raised from (or for a sale that came from) a pro-forma
    location_id uuid not null references locations(id),  -- issuing showroom
    issue_date date,
    issue_time time,
    due_date date,
    payment_terms_days int not null default 0 check (payment_terms_days >= 0),
    payment_terms_note varchar(300),
    document_currency_code varchar(3) not null default 'NGN',
    tax_currency_code varchar(3) not null default 'NGN',
    exchange_rate_id uuid,                               -- when document currency ≠ NGN (account exchange_rates.id snapshot)
    exchange_rate numeric(14,6),
    -- buyer (snapshot)
    customer_id uuid,                                    -- customers.id; null = walk-in with details typed at issue
    buyer_name varchar(200) not null,
    buyer_type varchar(30),                              -- customer type at issue
    buyer_email varchar(320),
    buyer_phone varchar(40),
    buyer_tin varchar(40),
    buyer_business_description varchar(200),
    buyer_street_address varchar(300),
    buyer_city varchar(120),
    buyer_postal_code varchar(20),
    buyer_state_code varchar(5),
    buyer_lga_code varchar(10),
    buyer_country_code varchar(2) not null default 'NG',
    correspondent_id uuid,                               -- snapshot for reports
    -- issuer (snapshot of settings::spi.CompanyProfile + the location)
    issuer jsonb not null default '{}'::jsonb,           -- { tradingName, legalName, tin, vatRegistrationNumber, nrsBusinessId, email, phone, address{…}, locationName, locationAddress, logoPresent }
    -- money
    line_extension_amount numeric(14,2) not null default 0,  -- Σ line net (after line discounts), before VAT  (NRS legal_monetary_total.line_extension_amount)
    discount_total numeric(14,2) not null default 0,         -- Σ line discounts (allowances)
    tax_exclusive_amount numeric(14,2) not null default 0,   -- = line_extension_amount (no document-level charges this phase)
    vat_total numeric(14,2) not null default 0,
    tax_inclusive_amount numeric(14,2) not null default 0,   -- total
    vat_enabled boolean not null,
    location_vat_rate numeric(5,2) not null,                 -- the location's rate at issue
    amount_paid numeric(14,2) not null default 0,            -- cache: Σ non-declined, non-voided payments (§1.3-3)
    amount_credited numeric(14,2) not null default 0,        -- Σ issued credit notes
    last_payment_at timestamptz,
    -- lifecycle
    issued_by_user_id uuid references app_users(id),
    issued_at timestamptz,
    void_reason varchar(300),
    voided_by_user_id uuid references app_users(id),
    voided_at timestamptz,
    reissue_of_invoice_id uuid references invoices(id),      -- this invoice replaces a voided one
    replaced_by_invoice_id uuid references invoices(id),     -- the voided invoice points at its replacement
    overdue_notified_at timestamptz,
    -- NRS regulatory identifiers — reserved, always null this phase (PRD §5.10)
    irn varchar(80),
    cryptographic_stamp_id varchar(200),
    qr_code text,
    ap_submitted_at timestamptz,
    nrs_submission_status varchar(20) not null default 'NOT_SUBMITTED'
        check (nrs_submission_status in ('NOT_SUBMITTED','VALIDATED','SIGNED','TRANSMITTED','REJECTED')),
    search_vector tsvector generated always as (to_tsvector('simple',
        coalesce(number,'') || ' ' || source_ref || ' ' || coalesce(pro_forma_ref,'') || ' ' || buyer_name)) stored,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint invoices_issued_has_number check ((status = 'DRAFT') = (number is null)),
    constraint invoices_paid_le_total check (amount_paid + amount_credited <= tax_inclusive_amount + 0.01)
);
create unique index invoices_number_uq on invoices (tenant_id, number) where number is not null;
create unique index invoices_source_uq on invoices (tenant_id, source_type, source_id) where status <> 'VOID';   -- one live invoice per sale/pro-forma
create index invoices_customer_idx on invoices (tenant_id, customer_id, status);
create index invoices_location_issue_idx on invoices (tenant_id, location_id, issue_date desc);
create index invoices_open_idx on invoices (tenant_id, due_date) where status = 'ISSUED';
create index invoices_buyer_trgm on invoices using gin (lower(buyer_name) gin_trgm_ops);
create index invoices_search_idx on invoices using gin (search_vector);
```

**Immutability (rule 14) at the database:** a trigger `invoices_issued_immutable` rejects any `UPDATE` of an `ISSUED`/`VOID` row that changes `number`, `issue_date`, buyer/issuer columns, money columns other than `amount_paid`, `amount_credited`, `last_payment_at`, or that changes `status` other than `ISSUED → VOID`; and a trigger on `invoice_lines` rejects any `INSERT/UPDATE/DELETE` when the parent invoice is not `DRAFT`. The application never attempts these; the triggers make the rule hold even against a bug or a manual SQL session.

### 2.3 `invoice_lines`

```sql
create table invoice_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    line_no int not null,
    stock_item_id uuid not null references stock_items(id),
    item_code varchar(40) not null,                      -- snapshots below
    item_name varchar(200) not null,                     -- NRS item.name
    item_description varchar(300),                       -- NRS item.description
    sellers_item_identification varchar(80),             -- NRS item.sellers_item_identification (= item code)
    quantity numeric(14,3) not null check (quantity > 0),   -- NRS invoiced_quantity
    unit_name varchar(60) not null,
    nrs_unit_code varchar(10),                           -- NRS price.price_unit (nullable: V6 sample list)
    base_quantity numeric(14,3) not null default 1,      -- NRS price.base_quantity
    unit_price numeric(14,2) not null check (unit_price >= 0),   -- NRS price.price_amount
    discount_amount numeric(14,2) not null default 0 check (discount_amount >= 0),
    discount_rate numeric(7,4),                          -- NRS discount_rate (%) when the discount was a percentage
    line_net numeric(14,2) not null,                     -- quantity × unit_price − discount  (NRS line_extension_amount)
    vat_category_id uuid not null,                       -- vat_categories.id at issue
    vat_category_code varchar(30) not null,              -- STANDARD_RATE / ZERO_RATED / EXEMPT
    nrs_tax_category_code varchar(40) not null references nrs_tax_categories(code),   -- STANDARD_VAT / ZERO_VAT / EXEMPTED
    vat_rate numeric(5,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,                   -- line_net + vat_amount
    is_service boolean not null default false,
    hsn_code varchar(10),                                -- goods: granular code, else null
    hsn_section_code varchar(6),                         -- fallback classification always present for goods
    product_category varchar(500),                       -- NRS product_category (HS description or section name)
    isic_code varchar(4),                                -- services
    service_category varchar(300),                       -- NRS service_category
    unique (invoice_id, line_no),
    constraint invoice_lines_discount_le_value check (discount_amount <= quantity * unit_price)
);
```

### 2.4 `invoice_payments`

```sql
create table invoice_payments (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    method varchar(20) not null check (method in ('CASH','POS','FUND_TRANSFER','CHEQUE','WALLET','DEPOSIT')),
    amount numeric(14,2) not null check (amount > 0),
    payment_receipt_id uuid not null,                    -- account payment_receipts.id (plain uuid, cross-module)
    receipt_ref varchar(20) not null,
    receipt_status varchar(20) not null default 'ACTIVE' check (receipt_status in ('ACTIVE','DECLINED','VOIDED')),   -- mirrored from Account (§1.3-3)
    nrs_payment_means_code varchar(3) references nrs_payment_means_codes(code),   -- §4.12 mapping; null for WALLET/DEPOSIT/POS
    paid_at timestamptz not null,
    recorded_by_user_id uuid not null references app_users(id),
    source varchar(20) not null check (source in ('POS_CHECKOUT','RECORDED','REISSUE_TRANSFER')),
    created_at timestamptz not null default now()
);
create index invoice_payments_invoice_idx on invoice_payments (tenant_id, invoice_id);
create unique index invoice_payments_receipt_uq on invoice_payments (payment_receipt_id) where source <> 'REISSUE_TRANSFER';
```
`amount_paid` = Σ `amount` where `receipt_status = 'ACTIVE'`.

### 2.5 `credit_notes` / `credit_note_lines`

```sql
create table credit_notes (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    number varchar(20) not null,                         -- CN-n
    invoice_type_code varchar(3) not null default '380' references nrs_invoice_type_codes(code),   -- 380 Credit Note (V6 list)
    invoice_id uuid not null references invoices(id),
    reason varchar(40) not null check (reason in ('PRICING_ERROR','OVERCHARGE','DISCOUNT_GRANTED_AFTER_ISSUE','DUPLICATE_BILLING','GOODWILL','OTHER')),
    reason_note varchar(300) not null,
    line_extension_amount numeric(14,2) not null,
    vat_total numeric(14,2) not null,
    total numeric(14,2) not null check (total > 0),
    applied_to_invoice numeric(14,2) not null,            -- part that reduced the invoice balance
    excess_refunded numeric(14,2) not null default 0,     -- part beyond the balance, credited to the wallet (§4.5)
    excess_customer_ledger_entry_id uuid,
    issued_by_user_id uuid not null references app_users(id),
    issued_at timestamptz not null default now(),
    unique (tenant_id, number)
);
create table credit_note_lines (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    credit_note_id uuid not null references credit_notes(id),
    invoice_line_id uuid not null references invoice_lines(id),
    quantity numeric(14,3),                              -- for quantity-based credits (price stays the line's)
    amount numeric(14,2),                                -- for value-based credits (e.g. price reduction per line)
    line_net numeric(14,2) not null,
    vat_amount numeric(14,2) not null,
    line_total numeric(14,2) not null,
    constraint cnl_one_basis check ((quantity is null) <> (amount is null))
);
```

### 2.6 `invoice_deliveries`

```sql
create table invoice_deliveries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    recipient_email varchar(320) not null,
    template varchar(20) not null check (template in ('STANDARD','REMINDER','STATEMENT')),
    message varchar(1000),
    notification_outbox_id uuid not null,                -- platform notification_outbox.id
    trigger varchar(20) not null check (trigger in ('ON_ISSUE','MANUAL','REDIRECT','OVERDUE_REMINDER','RETRY')),
    requested_by_user_id uuid references app_users(id),  -- null for automatic
    requested_at timestamptz not null default now()
);
create index invoice_deliveries_invoice_idx on invoice_deliveries (tenant_id, invoice_id, requested_at desc);
```
Delivery status of an invoice = status of its latest delivery's outbox row: `PENDING` → "Not sent"(queued), `SENT` → "Sent", `FAILED` (after the dispatcher's retries) → "Failed"; no delivery and no buyer e-mail → "No email on file"; no delivery but an e-mail exists → "Not sent". The PDF is attached at send time: the outbox row's `metadata` carries `{ "attachment": "INVOICE_PDF", "invoiceId": "…" }` and the e-mail adapter resolves it through `platform.documents.AttachmentResolver`, which Invoicing implements — so a retry renders the identical invoice, never a regenerated one.

### 2.7 `invoice_waybill_links`

```sql
create table invoice_waybill_links (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    invoice_id uuid not null references invoices(id),
    waybill_id uuid not null,                            -- cashier customer_waybills.id (plain uuid, cross-module)
    waybill_ref varchar(20) not null,                    -- WB-n
    waybill_date date not null,
    created_at timestamptz not null default now(),
    unique (invoice_id, waybill_id)
);
```
Written only through `InvoiceWaybillLinks.link` (§5.4) by Sales & POS; read by History & Search (§4.2) and the detail view (§4.3).

### 2.8 Module settings registered

| Key | Type | Default |
|---|---|---|
| `invoicing.defaultPaymentTermsDays` | int | 30 (doc 03) |
| `invoicing.paidTolerance` | decimal | 1.00 (doc 03) — absorbs VAT rounding, as `invoiceModel.js` does |
| `invoicing.autoEmailOnIssue` | boolean | `true` |
| `invoicing.overdueReminderDays` | int[] | `[1, 7, 30]` (days past due to send the REMINDER template; empty = never) |
| `invoicing.creditNoteExcessTo` | enum `WALLET`|`BLOCK` | `WALLET` |
| `invoicing.bulkExportMaxInvoices` | int | 200 |

---

## 3. Permission codes

The mock controller reused `cashier.sales.*` for everything, including the Accounts-only Void. New codes (V12):

| Code | Allows | Default roles |
|---|---|---|
| `invoicing.invoice.read` | Invoice history, detail, PDF | Management, Accounts Personnel, Cashier (own location) |
| `invoicing.invoice.issue` | Issue drafts; required (with `cashier.*`) for POS/pro-forma invoice generation in doc 09 | Accounts Personnel, Cashier |
| `invoicing.invoice.void` | Void and void-and-reissue | Accounts Personnel |
| `invoicing.credit-note.issue` | Issue credit notes | Accounts Personnel |
| `invoicing.payment.record` | Record a payment against an invoice | Accounts Personnel, Cashier |
| `invoicing.delivery.manage` | Send/resend/redirect e-mails; bulk export | Accounts Personnel, Cashier |
| `invoicing.receivables.read` | Outstanding receivables, Debtors List | Management, Accounts Personnel |

The shipped `NrsReferenceDataController` (V6) currently gates its eight read endpoints with `cashier.sales.read`; V12 switches them to `invoicing.invoice.read` **or** `accounting.vat.manage` (the VAT Control screen also reads the code lists).

Location scope: every list filters by the invoice's issuing `location_id` through `LocationAccessService` — "search never returns invoices outside the user's permitted scope" (5.10 acceptance criterion). Correspondent-role users with `customers.read.own` see invoices of their own customers via `GET /invoices?mine=true` (requires `invoicing.invoice.read`; grant it to Correspondent only if the business wants reps to see invoices — default no).

---

## 4. Endpoints (all under `/api/v1/invoicing`)

### 4.1 Issue a draft — `POST /invoices/{id}/issue`

Drafts are created through `InvoiceIssuer.createDraft` by Sales & POS (doc 09 "convert pro-forma, save as draft"). Perm `invoicing.invoice.issue`. Body `{ issueDate? (default today, not in the future, not before the source document date), paymentTermsDays?, dueDate? (≥ issueDate; default issueDate + terms), paymentTermsNote?, buyerEmail? }`. Re-validates exactly as §5.1 (customer still active, VAT recomputed at the *issue date's* location rate, prices kept as drafted), assigns `number = next("INV")`, snapshots issuer/buyer, registers `doc_type INVOICE` in the document registry, queues the e-mail when `invoicing.autoEmailOnIssue` and an e-mail exists. Emits `invoicing.invoice.issued`. Not `DRAFT` → `409 INVOICE_NOT_DRAFT`.
`PUT /invoices/{id}` — edit a `DRAFT`'s header fields above (lines come from the source and can't be edited here — change the pro-forma instead). `DELETE /invoices/{id}` — discard a `DRAFT` (the only invoice delete; it has no number). Emits `invoicing.invoice.discarded { sourceType, sourceId }`; Sales & POS consumes it to cancel the pro-forma order and release its stock reservation (doc 09 §4.4).

### 4.2 History & search — `GET /invoices` [paged]

Perm `invoicing.invoice.read`. Query: `number` (exact or prefix), `customerId`, `locationId`, `status` (`DRAFT`|`ISSUED`|`PARTIALLY_PAID`|`PAID`|`OVERDUE`|`VOID`, repeatable — derived statuses resolve to SQL on `status`, `amount_paid`, `amount_credited`, `due_date`), `sourceType`, `from`, `to` (issue date), `q` (number/source/pro-forma CODE, buyer name TRIGRAM, FULLTEXT), `sort` (`issuedAt`, `number`, `total`, `dueDate`, `balance`).
Row: `{ id, number, issued (date|null for draft), due, customer, customerType, showroom, source ('POS sale'|'Pro-forma'), sourceRef, proForma, waybills: [refs], total, paid, credited, balance, status, daysOverdue, delivery }`.
Header: `{ invoicesOnFile, combinedValue, fromProForma, fromPos, withWaybills }`.
`waybills` comes from `invoice_waybill_links` (§2.7), written by doc 09 when it raises a customer waybill against an invoice — Invoicing stores only the link, not the waybill.

### 4.3 Detail — `GET /invoices/{id}`

Perm `invoicing.invoice.read`. Response:
```json
{ "data": {
  "id": "…", "number": "INV-4031", "status": "ISSUED", "displayStatus": "Partially Paid",
  "invoiceTypeCode": "381", "invoiceKind": "B2B",
  "issueDate": "2026-10-08", "issueTime": "11:42:05", "dueDate": "2026-11-07", "paymentTermsDays": 30,
  "issuedBy": "F. Adeniyi", "showroom": { "id": "…", "name": "Showroom Ikeja" },
  "issuer": { "tradingName": "Zif Manufacturing Ltd", "tin": "33467982-0001", "vatRegistrationNumber": "NG-VAT-4471029", "address": "32 Owonikoko Street, Ikeja, Lagos" },
  "buyer": { "customerId": "…", "code": "DLR-42", "name": "Okafor & Sons Distribution", "type": "AUTHORISED_DEALER",
             "email": "orders@okaforsons.ng", "tin": "21993344-0001", "address": "14 Ikorodu Rd, Lagos", "stateCode": "NG-LA" },
  "lines": [ { "lineNo": 1, "code": "ZF-101", "name": "Citrus Drink 50cl", "quantity": 12, "unit": "Cartons",
               "unitPrice": 4100.00, "discountAmount": 0, "lineNet": 49200.00, "vatCategory": "STANDARD_RATE",
               "vatRate": 7.50, "vatAmount": 3690.00, "lineTotal": 52890.00, "hsnCode": null, "hsnSectionCode": "IV" } ],
  "totals": { "lineExtension": 49200.00, "discount": 0, "vat": 3690.00, "total": 52890.00, "paid": 30000.00,
              "credited": 0, "balance": 22890.00, "pendingConfirmation": 22890.00 },
  "vat": { "enabled": true, "rate": 7.50 },
  "linked": { "sourceType": "POS_SALE", "sourceRef": "TXN-4481", "proForma": null,
              "waybills": [ { "ref": "WB-9031", "date": "2026-10-09" } ],
              "payments": [ { "date": "…", "method": "Cash", "amount": 30000.00, "receipt": "RCT-551", "status": "Confirmed" },
                            { "date": "…", "method": "POS", "amount": 22890.00, "receipt": "RCT-552", "status": "Pending confirmation" } ],
              "creditNotes": [], "reissueOf": null, "replacedBy": null },
  "delivery": { "status": "Sent", "lastAttemptAt": "…", "recipient": "orders@okaforsons.ng" },
  "regulatory": { "irn": null, "cryptographicStampId": null, "qrCode": null, "accessPointSubmission": "Not in this phase" },
  "voidReason": null
} }
```
`pendingConfirmation` = payments whose receipt is still `PENDING` in Account (counted as paid, flagged so the UI can show it). "Also retrievable from Transaction Receipts" — the same document in the platform registry.

### 4.4 PDF — `GET /invoices/{id}/invoice.pdf`

Perm `invoicing.invoice.read`. Renders "Tax Invoice" (issuer block with logo, buyer block, meta `[Issue date, Payment due, From pro-forma]`, lines `[product, qty, unit, price, discount, VAT, amount]`, totals, payments received, balance due, payment terms note, company document footer) and the fixed footer: "Issued under ZifIQ internal digital invoicing. This document is not transmitted to the NRS/FIRS Merchant-Buyer Solution and carries no Invoice Reference Number or cryptographic stamp in this phase." Stamps: `VOID` (diagonal, with reason), `PAID`, `DUPLICATE COPY` once reprinted. "The PDF must match the displayed data exactly" — both are rendered from the same `InvoiceDetail` object. `GET /credit-notes/{id}/credit-note.pdf` likewise.

### 4.5 Credit notes — `POST /invoices/{id}/credit-notes`

Perm `invoicing.credit-note.issue`. Invoice must be `ISSUED` → `409 INVOICE_NOT_ISSUED`.
```json
{ "reason": "PRICING_ERROR", "reasonNote": "Dealer price should have applied",
  "lines": [ { "invoiceLineId": "…", "amount": 4800.00 } ] }      // or { "invoiceLineId", "quantity": 2 }
```
Rules:
- Per line, cumulative credited quantity ≤ invoiced quantity and cumulative credited value ≤ the line's net → `400 CREDIT_EXCEEDS_LINE`.
- VAT on each credit line uses the **original line's** VAT rate (a credit note reverses the original tax treatment).
- Number `next("CN")`.
- Application: `applied_to_invoice = min(total, invoice balance)`.
- Any excess (the invoice was already partly or fully paid): if `invoicing.creditNoteExcessTo = WALLET` and the buyer is a registered customer → `WalletLedger.credit(entryType CREDIT_NOTE, reference = CN number, sourceModule invoicing)`. `CREDIT_NOTE` is a dedicated customer-ledger entry type (direction CREDIT) in doc 04's `customer_ledger_entries.entry_type` — the other types don't fit: it is not an admin `ADJUSTMENT_CREDIT`, not a `RETURN_CREDIT` (no goods came back) and not `FUNDING` (no money came in).
- Walk-in buyer, or setting `BLOCK` → `409 CREDIT_EXCEEDS_BALANCE` (refund cash through Account instead). Registers `doc_type CREDIT_NOTE`. Emits `invoicing.credit-note.issued`. Credit notes never move stock (§1.3-4).
`GET /credit-notes` [paged] (`invoiceId`, `customerId`, `from`, `to`, `q`), `GET /credit-notes/{id}`.

### 4.6 Record a payment — `POST /invoices/{id}/payments`

Perm `invoicing.payment.record`. Invoice `ISSUED` with balance > tolerance → else `409 INVOICE_ALREADY_PAID` / `409 INVOICE_NOT_ISSUED`.
```json
{ "method": "FUND_TRANSFER", "amount": 22890.00, "paidAt": "2026-10-10T09:00:00Z",
  "cashAccountId": null, "receivingBankAccountId": "…", "terminalRef": null, "payerReference": "GTB/77310" }
```
Rules: `amount ≤ balance` → `400 OVERPAYMENT` (details `{ balance }`) — an invoice is never overpaid (`invoices_paid_le_total`); `WALLET`/`DEPOSIT`: `WalletLedger.debit(PURCHASE_DEBIT, reference = invoice number, sourceModule invoicing)` first (fails on insufficient balance, PRD rule 5); then `MoneyLedger.recordReceipt(source_type INVOICE_PAYMENT, source_ref = invoice number, …)` (doc 07 §5.1 applies channel rules: CASH posts now, POS/transfer/cheque await Accounts confirmation). Insert `invoice_payments`, update `amount_paid`, `last_payment_at`. Emits `invoicing.payment.recorded`; when fully settled also `invoicing.invoice.paid`. Response: the updated `InvoiceDetail.totals` + `{ receiptRef, confirmationStatus }`.

**Receipt reconciliation (event consumer, doc 03 §7.6):** consumer `invoicing.receipt-reconciler` handles `account.receipt.declined` and `account.receipt.voided`: finds `invoice_payments` by `payment_receipt_id`, sets `receipt_status`, recomputes `amount_paid`, emits `invoicing.payment.reversed` (summary "RCT-552 declined — INV-4031 balance ₦22,890 again") and notifies holders of `invoicing.receivables.read` at the location. Idempotent (setting the same status twice is a no-op).

### 4.7 Void and void-and-reissue — `POST /invoices/{id}/void`

Perm `invoicing.invoice.void` (Accounts — "Accounts-authorized user can Void with documented reason").
```json
{ "reason": "Wrong customer selected at the till",
  "reissue": { "customerId": "…", "lines": null, "buyerEmail": null } }
```
- `reason` required (≥10 chars) → `400 VALIDATION_FAILED`.
- **Void only** (`reissue` absent): allowed only when `amount_paid = 0` and no credit notes → else `409 INVOICE_HAS_PAYMENTS` ("refund or credit first, or void-and-reissue"). Sets `VOID`, marks the registry document `VOIDED`. Voiding an invoice does **not** reverse the sale or stock — that is a sale void in doc 09, which voids its invoice through `InvoiceIssuer.voidForSource`.
- **Void-and-reissue**: creates a new invoice (new number, `reissue_of_invoice_id`) from the same source with the corrections allowed by the reissue block (buyer change, or corrected lines supplied by doc 09 via spi when quantities/prices change); moves the old invoice's active payments onto the new one as `source REISSUE_TRANSFER` rows referencing the same receipts (the receipts don't change); sets `replaced_by_invoice_id`. New invoice total < transferred payments → `409 REISSUE_BELOW_PAID` (issue a credit note instead).
- "Void requires authorization and a reason, never deletes" — the row stays, visible with status Void and its reason. Emits `invoicing.invoice.voided` (+ `invoicing.invoice.issued` for the replacement).

### 4.8 Delivery — `/invoices/{id}/deliveries`, `/deliveries`

- `POST /invoices/{id}/deliveries` — perm `invoicing.delivery.manage`. `{ recipientEmail? (default the invoice's buyer e-mail; different → trigger REDIRECT), template: STANDARD|REMINDER|STATEMENT, message? }`. No e-mail at all → `400 NO_EMAIL_ON_FILE`. Writes the outbox row (channel EMAIL, attachment metadata) + `invoice_deliveries`. Draft/void invoices → `409 INVOICE_NOT_ISSUED` (a void invoice can still be downloaded, not e-mailed). "Retrying a failed delivery reuses the same invoice — the number and its data never change."
- `GET /deliveries` [paged] — perm `invoicing.invoice.read`. Query `delivery` (`SENT`|`FAILED`|`NO_EMAIL`|`NOT_SENT`), `locationId`, `from`, `to`, `q`. Row `{ invoiceId, number, issued, customer, email | null, showroom, total, delivery, lastAttemptAt, attempts }`. Header `{ issued, delivered, deliveredPct, failed, noEmailCount, noEmailValue }`.
- `STATEMENT` template: the attachment is the customer's statement (all their open invoices + ageing), not the single invoice.
- **Bulk export** — `GET /invoices/export.zip?from=&to=&locationId=&status=` — perm `invoicing.delivery.manage`. Streams a ZIP of the invoice PDFs in scope (same location/permission filtering as the list — "bulk export respects the same access permissions as opening an invoice individually"). More than `invoicing.bulkExportMaxInvoices` → `400 EXPORT_TOO_LARGE` (narrow the range; large exports become async jobs in doc 13).

### 4.9 Outstanding receivables — `GET /receivables` [paged]

Perm `invoicing.receivables.read`. Invoices `ISSUED` with balance > tolerance. Query `customerId`, `locationId`, `overdueOnly`, `from`, `to`. Row `{ number, issued, due, customer, showroom, total, paid, balance, status ('Issued'|'Partially Paid'|'Overdue'), daysOverdue }`. Header `{ outstandingTotal, outstandingCount, overdueTotal, overdueCount, partiallyPaidTotal, partiallyPaidCount, voidedCount }` + `outstandingByCustomer: [ { customer, invoiceCount, balance } ]` sorted by balance desc. "The receivables view reconciles exactly with the sum of unpaid/partial balances" — header totals are computed from the same query as the rows.
`GET /invoices/voided` [paged] — `{ number, voidReason, customer, issued, total, voidedBy, voidedAt, replacedBy }`.

### 4.10 Debtors List — `GET /debtors` [paged]

Perm `invoicing.receivables.read`. One row per customer with an open balance. Query `locationId` (customer's home showroom), `customerType`, `ageing` (`WITHIN_30`|`DAYS_31_60`|`OVER_60`), `overLimitOnly`, `q`. Row: `{ customerId, code, name, type, showroom, rep, openInvoices, outstanding, creditLimit, overLimit, oldestInvoiceDays, ageing ('Within 30 days'|'31–60 days'|'Over 60 days'), lastPaymentAt }` (ageing on the oldest open invoice's issue date, as the screen does). Customer fields come from `CustomerDirectory`.
`GET /debtors/{customerId}` — `{ customer: {…, phone}, account: { openInvoices, lastPayment, totalInvoiced, totalPaid, outstanding, creditLimit, overLimit }, transactions: [ { date, invoice, description, value, paid, balance, dueDate } ] }`. When `overLimit`: the response carries `warning: "Further credit sales should be withheld until the balance is brought within limit."` — and doc 09 enforces it at the till.

### 4.11 Overdue sweep (scheduled)

`OverdueSweep` runs daily at 06:00 tenant time: for each issued invoice whose `due_date` passed with balance > tolerance and `overdue_notified_at` is null → emit `invoicing.invoice.overdue` (once), set `overdue_notified_at`, notify holders of `invoicing.receivables.read` at the location; for each day in `invoicing.overdueReminderDays` matching days-past-due and a buyer e-mail → queue a `REMINDER` delivery (trigger `OVERDUE_REMINDER`). "Auto-flag Overdue once past due date without full payment" — the flag itself is derived (no manual review needed); the sweep only notifies.

### 4.12 NRS mapping (for the future regulatory phase)

How each NRS Invoice Schema field (POST `/api/v1/invoice/validate`) is sourced from this module's data, so the future Access-Point integration is a serializer, not a redesign:

| NRS field | Source |
|---|---|
| `business_id` | `issuer.nrsBusinessId` (Settings) |
| `irn` | `invoices.irn` (reserved) |
| `issue_date`, `issue_time`, `due_date` | invoice |
| `invoice_type_code` | `invoices.invoice_type_code` (381) / `credit_notes.invoice_type_code` (380) |
| `invoice_kind` | `invoices.invoice_kind` |
| `payment_status` | derived: PAID / PENDING |
| `note`, `payment_terms_note` | `payment_terms_note` |
| `document_currency_code`, `tax_currency_code` | invoice |
| `billing_reference` | for credit notes / reissues: the original invoice's number + issue date |
| `accounting_supplier_party` | `issuer` snapshot (party_name, tin, email, telephone, business_description, postal_address{street_name, city_name, postal_zone, lga, state, country}) |
| `accounting_customer_party` | buyer snapshot (same shape) |
| `payment_means[]` | `invoice_payments.nrs_payment_means_code` + paid date: CASH → `10`, CHEQUE → `20`, FUND_TRANSFER → `30`; **POS (card) has no code in the V6 list** and WALLET/DEPOSIT are internal settlement rails, not payment means → omitted (§11) |
| `allowance_charge[]` | one allowance (`charge_indicator false`) per discounted line |
| `tax_total[].tax_subtotal[]` | grouped by `nrs_tax_category_code` + `vat_rate`: taxable amount, tax amount, `tax_category { id, percent }` |
| `legal_monetary_total` | `line_extension_amount`, `tax_exclusive_amount`, `tax_inclusive_amount`, payable = `tax_inclusive_amount − amount_credited` |
| `invoice_line[]` | `hsn_code` (or the section as fallback) + `product_category` for goods; `isic_code` + `service_category` for services; `discount_rate`, `discount_amount`, `invoiced_quantity`, `line_extension_amount`, `item{name, description, sellers_item_identification}`, `price{price_amount, base_quantity, price_unit}` |

`GET /invoices/{id}/nrs-preview` — perm `invoicing.invoice.read` — returns this JSON for an issued invoice plus `{ ready: bool, missing: [ "issuer.nrsBusinessId", "lines[2].hsn_code (fallback used)", … ] }`. Nothing is transmitted.

---

## 5. Exposed to other modules — `com.zifiq.invoicing.spi`

### 5.1 `InvoiceIssuer`
```kotlin
fun issue(req: InvoiceRequest): InvoiceRef          // issues immediately (number assigned) — POS checkout, walk-in on request
fun createDraft(req: InvoiceRequest): InvoiceRef    // pro-forma conversion saved as draft
fun attachPayment(invoiceId: UUID, payment: PaymentLink)   // POS checkout payments (already receipted by Account)
fun voidForSource(sourceType: String, sourceId: UUID, reason: String)   // sale void in doc 09: the caller has already voided every
                                                                        // receipt in the same transaction, so this marks the invoice's
                                                                        // payments VOIDED, zeroes amount_paid and voids the invoice —
                                                                        // the INVOICE_HAS_PAYMENTS guard of §4.7 does not apply here
fun discardDraftForSource(sourceType: String, sourceId: UUID)          // pro-forma conversion abandoned before issue (doc 09 §4.4)
fun reissueForSource(sourceType: String, sourceId: UUID, corrected: InvoiceRequest, reason: String): InvoiceRef

data class InvoiceRequest(
    val sourceType: SourceType, val sourceId: UUID, val sourceRef: String, val proFormaRef: String?,
    val locationId: UUID, val customerId: UUID?, val walkInBuyer: WalkInBuyer?,      // exactly one of the two
    val issueDate: LocalDate, val paymentTermsDays: Int?, val paymentTermsNote: String?,
    val lines: List<InvoiceLineRequest>, val correspondentId: UUID?)
data class InvoiceLineRequest(val stockItemId: UUID, val quantity: BigDecimal, val unitPrice: BigDecimal,
                              val discountAmount: BigDecimal = ZERO, val discountRate: BigDecimal? = null)
data class WalkInBuyer(val name: String, val email: String?, val phone: String?, val tin: String?, val address: String?, val stateCode: String?)
```
Validation inside `issue`/`createDraft`: one live invoice per source (`409 INVOICE_EXISTS_FOR_SOURCE`); customer `ACTIVE`/`WATCH`; each item `ACTIVE` and not a variant parent; discount ≤ line value → `400 DISCOUNT_EXCEEDS_LINE`; location `ACTIVE` showroom → `409 LOCATION_NOT_TRADING`; VAT per §1.3-5 via `VatRates.forLocation(location, issueDate)`; issuer from `CompanyProfileProvider.get()`; buyer from `CustomerDirectory.get()` (or walk-in block); `invoice_kind` = B2B if buyer TIN present else B2C; HS/ISIC/unit codes from the item with section fallback.

### 5.2 `Receivables`
```kotlin
fun outstandingFor(customerId: UUID): BigDecimal          // doc 09 credit-limit check
fun openInvoicesFor(customerId: UUID): List<OpenInvoiceRef>
```

### 5.3 `InvoiceQueries` (read-only)
`byNumber(number)`, `bySource(sourceType, sourceId)`, `linesOf(invoiceId)` (doc 09's waybill release reads purchased quantities from here), `search(filter)` (doc 10/13), `creditNoteLines(filter: locationId?, from, to)` → `{ date, creditNoteNumber, invoiceNumber, locationId, buyerName, buyerTin, lineNet, vatRate, vatAmount }` (doc 10 deducts these from output VAT).

### 5.4 `InvoiceWaybillLinks`
`link(invoiceId, waybillId, waybillRef, waybillDate)` — called by doc 09 when it raises a customer waybill; stored in `invoice_waybill_links` (§2.7).

---

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| I1 | Invoice numbers unique, sequential, never reused | PRD §6 rule 14 | `next("INV")` at issue inside the issuing transaction; unique index |
| I2 | Issued items, amounts, VAT immutable; corrections only by credit note or void-and-reissue | rule 14 | §2.2 triggers; §4.5, §4.7 |
| I3 | VAT per issuing showroom's rate/enablement | rule 9 | §1.3-5 |
| I4 | Every invoice traceable to its POS sale or pro-forma | 5.10 acceptance | `source_type`/`source_id` NOT NULL; one live invoice per source |
| I5 | Status reflects linked payments; Paid only when fully settled; Overdue automatic | 5.10 acceptance | §1.3-3, §4.6, §4.11 |
| I6 | Void requires authorisation + reason; never deletes | 5.10 acceptance | §4.7 |
| I7 | Failed delivery retryable without regenerating/renumbering | 5.10 acceptance | §2.6, §4.8 |
| I8 | PDF matches displayed data exactly | 5.10 acceptance | §4.4 |
| I9 | Bulk export respects individual access permissions | 5.10 acceptance | §4.8 |
| I10 | Search never returns invoices outside the user's scope | 5.10 acceptance | §3 location scope |
| I11 | An invoice is never overpaid | derived | §4.6, DB check |
| I12 | Credit notes don't move stock; physical returns go through Showroom | derived (prevents double compensation) | §1.3-4 |
| I13 | Over-limit customers: further credit sales withheld | `DebtorsList.jsx` | §4.10 warning; enforced in doc 09 |
| I14 | Regulatory identifiers reserved, not transmitted this phase | 5.10 scope | §2.2, §4.12 |

## 7. Errors added

`INVOICE_NOT_FOUND` 404 · `INVOICE_NOT_DRAFT` 409 · `INVOICE_NOT_ISSUED` 409 · `INVOICE_ALREADY_PAID` 409 · `INVOICE_EXISTS_FOR_SOURCE` 409 · `INVOICE_HAS_PAYMENTS` 409 · `REISSUE_BELOW_PAID` 409 · `OVERPAYMENT` 400 · `CREDIT_EXCEEDS_LINE` 400 · `CREDIT_EXCEEDS_BALANCE` 409 · `DISCOUNT_EXCEEDS_LINE` 400 · `NO_EMAIL_ON_FILE` 400 · `EXPORT_TOO_LARGE` 400 · `HSN_SECTION_MISMATCH` 400 · `CREDIT_NOTE_NOT_FOUND` 404.

## 8. Event catalog

| Event | Notifies |
|---|---|
| `invoicing.invoice.drafted` / `.issued` | — |
| `invoicing.invoice.discarded` | — (consumed by Sales & POS) |
| `invoicing.invoice.paid` | — |
| `invoicing.payment.recorded` | — |
| `invoicing.payment.reversed` | in-app holders of `invoicing.receivables.read` at the location |
| `invoicing.invoice.overdue` | in-app holders of `invoicing.receivables.read` at the location; the customer's correspondent user |
| `invoicing.invoice.voided` | in-app Management |
| `invoicing.credit-note.issued` | — |
| `invoicing.delivery.queued` / `.failed` | failed → in-app the requester (or holders of `invoicing.delivery.manage` at the location for automatic sends) |

`invoicing.delivery.failed` is emitted by the outbox dispatcher's completion hook when an `INVOICE_PDF` row ends `FAILED` (platform calls a registered `DeliveryOutcomeListener`, implemented here).

## 9. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search`, `/filters` metadata, facets, `highlight`, saved views, and exports (incl. bulk PDF ZIP §4.8) with identical filters. Derived statuses (`PARTIALLY_PAID`, `PAID`, `OVERDUE`) are filterable because the filter compiler translates them into SQL over `status`, `amount_paid`, `amount_credited`, `tax_inclusive_amount` and `due_date`; nothing is computed in memory.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /invoices` [paged] · `invoicing.invoices` | `number` REFERENCE (bare `4031` matches `INV-4031`), `source_ref`/`pro_forma_ref` REFERENCE, `buyer_name` TRIGRAM, buyer phone PHONE, `buyer_tin` CODE, `buyer_email` CODE, `tax_inclusive_amount` AMOUNT, `search_vector` FULLTEXT; line item names via `lines` collection (TRIGRAM) | `status` multi incl. derived (`status:overdue`), `customerId` (`customer:`), `customerType` multi, `locationId`, `sourceType`, `invoiceKind` (B2B/B2C), `issueDate`/`dueDate` ranges + `period`, `total`/`balance`/`paid` MONEY ranges, `daysOverdue` range, `delivery` (SENT/FAILED/NO_EMAIL/NOT_SENT), `hasCreditNotes`, `hasWaybills`, `supplyStatus`, `issuedBy`, `correspondentId`, `vatEnabled`, `nrsReady`, `lines.stockItemId` + `lines.quantity` (advanced, same-line semantics) | `status`, `showroom`, `customerType`, `delivery`, `source` | `issuedAt desc`; `number`, `total`, `balance`, `dueDate`, `daysOverdue`, `buyerName` | `{ invoicesOnFile, combinedValue, outstanding, overdue, fromProForma, fromPos, withWaybills }` |
| `GET /credit-notes` [paged] · `invoicing.credit-notes` | `number` REFERENCE, invoice number REFERENCE, buyer name TRIGRAM, `reason_note` FULLTEXT, `total` AMOUNT | `invoiceId`, `customerId`, `reason` multi, `locationId`, `issuedBy`, `total` range, `from`/`to`, `hasExcessRefunded` | `reason` | `issuedAt desc` | `{ count, total, excessToWallets }` |
| `GET /deliveries` [paged] · `invoicing.deliveries` | invoice number REFERENCE, customer TRIGRAM, recipient e-mail CODE | `delivery` multi, `locationId`, `trigger`, `template`, `lastAttemptAt` range, `attempts.gte` | `delivery`, `trigger` | `issued desc` | `{ issued, delivered, deliveredPct, failed, noEmailCount, noEmailValue }` |
| `GET /receivables` [paged] · `invoicing.receivables` | invoice number REFERENCE, customer code/name, `balance` AMOUNT | `customerId`, `customerType`, `locationId`, `overdueOnly`, `ageingBucket` (`WITHIN_30`/`DAYS_31_60`/`OVER_60`/`NOT_DUE`), `balance` range, `dueDate` range, `status` | `ageingBucket`, `showroom`, `status` | `daysOverdue desc`; `balance`, `dueDate` | `{ outstandingTotal/Count, overdueTotal/Count, partiallyPaidTotal/Count, byCustomer }` |
| `GET /invoices/voided` [paged] · `invoicing.voided` | number REFERENCE, `void_reason` FULLTEXT, customer TRIGRAM | `voidedBy`, `voidedAt` range, `hasReplacement`, `locationId` | — | `voidedAt desc` | `{ count, value }` |
| `GET /debtors` [paged] · `invoicing.debtors` | customer code REFERENCE, name TRIGRAM, phone PHONE | `locationId`, `customerType` multi, `ageing` multi, `overLimitOnly`, `correspondentId`, `outstanding` range, `oldestInvoiceDays` range, `lastPaymentAt.lt` ("no payment since") | `ageing`, `customerType`, `showroom` | `outstanding desc`; `oldestInvoiceDays`, `name`, `lastPaymentAt` | `{ debtors, outstandingTotal, overLimitCount }` |
| `GET /debtors/{customerId}` (transactions) | invoice number REFERENCE, description FULLTEXT | `from`/`to`, `openOnly` | — | `date desc` | account block |

**Lookups**: `GET /invoices/lookup?q=&openOnly=true&customerId=` (for "record payment", waybill lookup and returns: shows `number · customer · balance`); `GET /invoices/lookup-source?ref=` (resolves TXN/PF to its invoice).

**Global search provider**: `invoice` (title `INV-n`, subtitle `customer · ₦total · status/daysOverdue`, route `/invoicing/invoices/{id}`) and `credit-note`. An exact invoice number short-circuits.

**Indexes (V12)**: in §2.2 (`invoices_number_uq`, `invoices_customer_idx`, `invoices_location_issue_idx`, `invoices_open_idx`, buyer trigram, `search_vector`), plus btree `(tenant_id, due_date) where status='ISSUED'` (exists), expression index `(tenant_id, (tax_inclusive_amount - amount_paid - amount_credited)) where status='ISSUED'` for balance filters/sorts, `(tenant_id, correspondent_id)`, `invoice_lines (tenant_id, stock_item_id)`, `credit_notes (tenant_id, invoice_id)`, trigram on `credit_notes` via invoice buyer name join, `invoice_deliveries (tenant_id, invoice_id, requested_at desc)` (exists).

## 10. Worked example

1. POS checkout at Ikeja for dealer DLR-42 (doc 09): 12 × Citrus 50cl at dealer price ₦4,100; Ikeja VAT 7.5 % enabled; Standard-Rate item → line net 49,200, VAT 3,690, total 52,890. Cashier calls `InvoiceIssuer.issue(source POS_SALE TXN-4481)` → `INV-4031`; then `attachPayment` for `RCT-551` cash 30,000 (confirmed) and `RCT-552` POS 22,890 (pending). Invoice: paid 52,890, balance 0, `displayStatus` "Paid" with `pendingConfirmation` 22,890. E-mail queued to orders@okaforsons.ng.
2. Next morning Accounts **declines** `RCT-552` (no settlement). Within ~15 s the reconciler sets that payment `DECLINED`; `INV-4031` balance 22,890, "Partially Paid"; receivables holders at Ikeja get an in-app alert.
3. The dealer pays by transfer: `POST /invoices/{INV-4031}/payments { method FUND_TRANSFER, amount 22890, receivingBankAccountId <BA-1>, payerReference "GTB/77310" }` → `RCT-560` pending; Accounts approves → Paid.
4. Pricing error found: dealer should have had ₦4,000. `POST /invoices/{id}/credit-notes { reason PRICING_ERROR, lines: [ { invoiceLineId, amount: 1200 } ] }` → `CN-12` line net 1,200, VAT 90, total 1,290; invoice already paid → excess 1,290 credited to DLR-42's wallet (`CREDIT_NOTE` entry).
5. On 7 Nov an unrelated invoice INV-4044 passes due with ₦15,000 open → overdue sweep notifies receivables holders once and e-mails a reminder on day 1.

## 11. Open items

1. **E-mail provider.** The shipped e-mail adapter only logs. Real delivery needs a provider decision — recommended **Amazon SES** (the system is already on AWS), sending from a verified tenant domain or a shared ZifIQ domain. Until configured, delivery status reflects the outbox, not actual inbox delivery.
2. **POS/card payment means code.** The NRS payment-means list supplied (V6) has no card/POS code; confirm the code NRS expects for card payments before the regulatory phase (likely a UN/CEFACT 4461 code such as `48` "bank card", not in the seeded list).
3. **Granular HS/ISIC coverage.** V6 seeds samples only; until the full lists are loaded, invoices use the section fallback (allowed in this phase, flagged in `nrs-preview`).
4. **Foreign-currency invoices.** Fields exist (`document_currency_code`, `exchange_rate_*`); the PRD shows only NGN invoicing, so issuing in another currency is not enabled in the API this phase (`document_currency_code` is always NGN).

## 12. Verification

- Two parallel issues → numbers N and N+1, never equal; a rolled-back issue doesn't consume a number.
- `UPDATE invoice_lines …` on an issued invoice via psql → rejected by trigger.
- VAT: same items at a VAT-disabled showroom → VAT 0; Zero-Rated item at an enabled showroom → VAT 0.
- Decline a receipt → invoice status changes within one consumer cycle; re-running the consumer is a no-op.
- Void with payments → 409; void-and-reissue moves payments, both invoices link to each other.
- Overpayment → 400; wallet payment beyond balance → 400 from Customers.
- Bulk export as a single-location user → only that location's PDFs in the ZIP.
- Newman folder "15 - Invoicing" (issue via doc 09 stubs in the test, payments, credit note, void/reissue, deliveries, receivables, debtors, NRS preview).
