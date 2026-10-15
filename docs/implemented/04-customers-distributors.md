# 04 — Customers & Distributors Module

Status: **design finalized, not yet implemented**
Owner package: `com.zifiq.customers` (substrate module — exposes `com.zifiq.customers.spi`, see doc 03 §2)
Depends on: `platform`, `common`, `inventory` (OPEN) only. Nothing else. Everything that moves money *into* or *out of* a wallet lives in another module and calls this module's `spi`.
Depended on by: `showroom` (returns credit wallets), `account` (wallet funding/withdrawal, deposits), `invoicing` (bill-to party), `cashier` (customer at POS, wallet/deposit payments, credit limit), `communication` (correspondents, dealers), `history`, `reports`, `ai`.
Migration: `V8__customers_module.sql`
Replaces: the mock `CustomerService`/`CustomerController`/`CustomerDto.kt` and `menu`'s mock `registerDealer`.

---

## 1. Scope

### 1.1 In scope (PRD reference → frontend screen)

| Feature | PRD | Screen |
|---|---|---|
| Customer register (all customer types) | 5.7.1 Customer Contacts | `others/CustomerContacts.jsx` |
| Dealer register (Authorised Dealers, with category, credit limit, correspondent) | 5.8.1 Dealer Register | `menu/DealerRegister.jsx` |
| Dealer categories (Platinum/Gold/Silver/Bronze — discount % and suggested credit limit) | 5.8.1 | `menu/DealerRegister.jsx` (category select) |
| Promotion / demotion between customer types | 5.7.14 Dealers Update | `others/DealersUpdate.jsx` |
| Customer history (wallet/deposit ledger with running balance) | 5.7.x Customer History | `others/CustomerHistory.jsx` |
| Wallet & deposit ledger — the **only** place a balance changes | PRD §6 rules 5, 10 | used by Showroom, Account, Cashier, Communication |
| Temporary walk-in customer records (created on return, removed at zero balance) | PRD §6 rule 10, 5.2.8 | `showroom/Returns.jsx` (via spi) |
| Correspondent (sales representative) register, assign / reassign / de-assign, history | 5.5.8 Correspondent | `process/Correspondent.jsx` |
| Admin wallet adjustment (correction credit/debit) | implied by `CustomerHistory` "Wallet adjustment" method | `others/CustomerHistory.jsx` |
| Dealer self-service view of own account | PRD §7 role "Dealer/Distributor/Customer — ✓ own account" | (no screen yet) |
| Correspondent "own customers" view | PRD §7 role "Correspondent — ✓ own customers" | (no screen yet) |
| Custom fields on customers | doc 03 §12.2 (`entity_type = 'CUSTOMER'`) | — |

### 1.2 Explicitly not in this module (and where it lives)

| Thing | Why not here | Where |
|---|---|---|
| Wallet funding (dealer tops up), deposit funding (special customer) | Moves real money into a bank account/cash — Account's concern | doc 07 (calls `WalletLedger.credit`) |
| Wallet withdrawal (OTP-gated) | Pays money out — Account's concern; also the screen is Account's (`PettyCashPayment.jsx`) | doc 07 (calls `WalletLedger.debit`) |
| Customer returns | A showroom stock operation that *also* credits a wallet | doc 05 |
| Outstanding receivables, Debtors List, credit ageing | Derived from invoices | doc 08 (reads `credit_limit` from here) |
| Customers Comprehensive (distributor/no-credit/special sales activity) | Derived from sales | doc 09 |
| Price list, dealer price | Product master data | doc 05 (inventory price list) |
| Instant discount | Applies to an open POS sale | doc 09 |
| Commission rates, allocation, payment | Communication | doc 12 (credits commission via `WalletLedger.credit`) |

### 1.3 Decisions made here (with the evidence that forced them)

1. **One `customers` table for every type**, not separate dealer/customer tables. `DealersUpdate.jsx` moves the *same* record between "No Credit", "Ordinary", "Special" and "Authorised Dealer" while the wallet "carries over" — they are one entity with a type.
2. **A customer's `code` is assigned once and never changes.** Dealers registered as dealers get `DLR-n`, everyone else `CU-n` (doc 03 §6). A customer later promoted to dealer keeps its `CU-` code — codes are printed on invoices and receipts and must stay stable. (The frontend shows `DL-0042`; `DL` is already taken by stock receipts, hence `DLR`.)
3. **Who can hold which balance** (resolves a conflict between `POS.jsx`, which blocks non-dealers from paying by wallet, and `Returns.jsx`/`CustomerContacts.jsx`, which create spendable wallets for non-dealers):
   | Type | Wallet | Deposit | Direct wallet funding | Credit limit |
   |---|---|---|---|---|
   | `AUTHORISED_DEALER` | yes | no | yes | any (default from category) |
   | `SPECIAL_CUSTOMER` | yes (return credits only) | yes | no (deposits instead) | any |
   | `ORDINARY_CUSTOMER` | yes (return credits only) | no | no | any |
   | `NO_CREDIT_WALKIN` | yes (return credits only) | no | no | always 0 |
   | temporary (`is_temporary = true`, type `NO_CREDIT_WALKIN`) | yes (return credits only) | no | no | always 0 |

   Any wallet balance a customer holds is spendable at POS (doc 09); the POS prototype's "non-dealers can't use wallet" check is superseded, otherwise temporary wallets could never reach zero and be removed (PRD rule 10).
4. **Balances are a cache of the ledger**, the same invariant as `stock_balances`: `customers.wallet_balance`/`deposit_balance` change only in the same transaction as a `customer_ledger_entries` insert, after locking the customer row `FOR UPDATE`. A database `CHECK (wallet_balance >= 0 and deposit_balance >= 0)` backs PRD rule 5 even if application code were wrong.
5. **Correspondents live here, not in Communication.** Every customer row references its correspondent, so the correspondent table must exist before customers; Communication (doc 12) only computes commissions over them.

---

## 2. Schema — migration `V8__customers_module.sql`

All tables carry `tenant_id uuid not null references tenants(id)` and the standard RLS policy (doc 02 §2 preamble), omitted below. All money columns `numeric(14,2)`.

### 2.1 `dealer_categories`

```sql
create table dealer_categories (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    name varchar(60) not null,                         -- 'Platinum', 'Gold', 'Silver', 'Bronze'
    discount_pct numeric(5,2) not null default 0 check (discount_pct >= 0 and discount_pct <= 100),
    suggested_credit_limit numeric(14,2) not null default 0 check (suggested_credit_limit >= 0),
    sort_order int not null default 0,                 -- higher = more senior; drives list order
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create unique index dealer_categories_name_uq on dealer_categories (tenant_id, lower(name));
```

Seeded per tenant on creation (code change in `AuthService.registerTenant` / `TenantService.createChild`, same place the eight roles are seeded): Platinum 5% / ₦3,000,000 (sort 4), Gold 3.5% / ₦1,500,000 (3), Silver 2% / ₦500,000 (2), Bronze 0% / ₦200,000 (1) — the values in `sampleMenu.dealerCategories`. Tenants edit them afterwards.

### 2.2 `correspondents` and `correspondent_assignment_history`

```sql
create table correspondents (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,                          -- CR-n
    name varchar(160) not null,
    phone varchar(40) not null,
    phone_e164 varchar(20) not null,                    -- normalised, see §4.8
    email varchar(320),
    user_id uuid references app_users(id),              -- optional login (role Correspondent) for "own customers" access
    region varchar(60),                                 -- must be in module setting customers.correspondent.regions
    location_id uuid references locations(id),          -- reporting showroom (null while unassigned)
    responsibilities varchar(300),
    status varchar(20) not null default 'UNASSIGNED' check (status in ('ASSIGNED','UNASSIGNED','DE_ASSIGNED')),
    assigned_at date,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index correspondents_user_uq on correspondents (tenant_id, user_id) where user_id is not null;
create index correspondents_name_trgm on correspondents using gin (lower(name) gin_trgm_ops);

create table correspondent_assignment_history (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    correspondent_id uuid not null references correspondents(id),
    action varchar(20) not null check (action in ('ASSIGNED','REASSIGNED','DE_ASSIGNED')),
    region varchar(60),
    location_id uuid references locations(id),
    effective_from date not null,
    note varchar(300),
    performed_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now()
);
create index cah_correspondent_idx on correspondent_assignment_history (tenant_id, correspondent_id, effective_from desc);
```

`correspondent_assignment_history` is append-only (never updated) — PRD/screen rule "de-assigning retains their sales history … reassigning moves them without losing that history".

### 2.3 `customers`

```sql
create table customers (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,                          -- CU-n or DLR-n, immutable
    type varchar(30) not null check (type in ('AUTHORISED_DEALER','SPECIAL_CUSTOMER','ORDINARY_CUSTOMER','NO_CREDIT_WALKIN')),
    is_temporary boolean not null default false,        -- auto-created walk-in from a return (PRD rule 10)
    name varchar(200) not null,                          -- person or business name
    contact_person varchar(160),                         -- dealers/businesses
    phone varchar(40),                                   -- required except for NO_CREDIT_WALKIN registered without one
    phone_e164 varchar(20),
    alternate_phone varchar(40),
    email varchar(320),                                  -- invoice e-mail delivery target (doc 08)
    tin varchar(40),                                     -- tax identification no. (NRS buyer party, doc 08)
    business_description varchar(200),                   -- NRS party business_description
    street_address varchar(300),
    city varchar(120),
    state_code varchar(5) references nigeria_states(code),     -- 'NG-LA' (global table from V6)
    lga_code varchar(10) references nigeria_lgas(code),        -- optional; V6 seeds only part of Nigeria's LGAs
    country_code varchar(2) not null default 'NG',
    location_id uuid references locations(id),           -- home showroom
    correspondent_id uuid references correspondents(id),
    dealer_category_id uuid references dealer_categories(id),   -- required when type = AUTHORISED_DEALER, else null
    credit_limit numeric(14,2) not null default 0 check (credit_limit >= 0),
    wallet_balance numeric(14,2) not null default 0,
    deposit_balance numeric(14,2) not null default 0,
    lifetime_value numeric(14,2) not null default 0,     -- Σ completed sales, maintained via spi (§5.5)
    last_purchase_at timestamptz,
    customer_since date not null default current_date,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','WATCH','SUSPENDED','REMOVED')),
    status_reason varchar(300),
    removed_at timestamptz,
    user_id uuid references app_users(id),               -- optional dealer/customer self-service login
    custom_attributes jsonb not null default '{}'::jsonb,
    search_vector tsvector generated always as (
        to_tsvector('simple', coalesce(name,'') || ' ' || coalesce(contact_person,'') || ' ' || coalesce(business_description,'') || ' ' || coalesce(city,''))
    ) stored,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, code),
    constraint customers_balances_non_negative check (wallet_balance >= 0 and deposit_balance >= 0),
    constraint customers_dealer_category check ((type = 'AUTHORISED_DEALER') = (dealer_category_id is not null)),
    constraint customers_no_credit check (type <> 'NO_CREDIT_WALKIN' or credit_limit = 0),
    constraint customers_deposit_only_special check (type = 'SPECIAL_CUSTOMER' or deposit_balance = 0),
    constraint customers_temporary_type check (not is_temporary or type = 'NO_CREDIT_WALKIN')
);
-- dealer names are unique (DealerRegister: "a name that duplicates an existing account")
create unique index customers_dealer_name_uq on customers (tenant_id, lower(name))
    where type = 'AUTHORISED_DEALER' and status <> 'REMOVED';
-- one live temporary record per phone number (matching key for repeat returns)
create unique index customers_temp_phone_uq on customers (tenant_id, phone_e164)
    where is_temporary and status = 'ACTIVE';
create index customers_type_status_idx on customers (tenant_id, type, status);
create index customers_location_idx on customers (tenant_id, location_id);
create index customers_correspondent_idx on customers (tenant_id, correspondent_id);
create index customers_phone_idx on customers (tenant_id, phone_e164);
create index customers_name_trgm on customers using gin (lower(name) gin_trgm_ops);
create index customers_contact_trgm on customers using gin (lower(contact_person) gin_trgm_ops);
create index customers_search_idx on customers using gin (search_vector);
create index customers_attrs_idx on customers using gin (custom_attributes);
```

### 2.4 `customer_ledger_entries`

The single generic ledger for every wallet and deposit movement — the customer equivalent of `stock_ledger_entries`.

```sql
create table customer_ledger_entries (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    customer_id uuid not null references customers(id),
    balance_type varchar(10) not null check (balance_type in ('WALLET','DEPOSIT')),
    entry_type varchar(30) not null check (entry_type in (
        'FUNDING',              -- dealer wallet top-up / special-customer deposit (Account)
        'RETURN_CREDIT',        -- goods returned (Showroom)
        'COMMISSION_CREDIT',    -- commission paid into wallet (Communication)
        'CREDIT_NOTE',          -- credit-note value beyond an invoice's open balance (Invoicing, doc 08 §4.5)
        'PURCHASE_DEBIT',       -- POS / invoice settled from the balance (Cashier, Invoicing)
        'WITHDRAWAL_DEBIT',     -- money paid out to the customer (Account, OTP-gated when dealer-requested)
        'ADJUSTMENT_CREDIT',    -- admin correction (this module)
        'ADJUSTMENT_DEBIT',     -- admin correction (this module)
        'REVERSAL_CREDIT',      -- reverses an earlier debit
        'REVERSAL_DEBIT'        -- reverses an earlier credit
    )),
    direction varchar(6) not null check (direction in ('CREDIT','DEBIT')),
    amount numeric(14,2) not null check (amount > 0),     -- always positive; direction carries the sign
    balance_after numeric(14,2) not null,                 -- running balance of THIS balance_type after the entry
    reference varchar(40) not null,                       -- business document ref: 'RT-457', 'WW-31', 'TXN-4481', 'WLT-9'
    description varchar(300) not null,
    payment_method varchar(30),                           -- display: 'Wallet', 'Wallet credit', 'Cash deposit', 'Bank transfer', 'Fund transfer', 'Wallet adjustment'
    source_module varchar(30) not null,                   -- 'showroom','account','cashier','invoicing','communication','customers'
    source_type varchar(40) not null,                     -- e.g. 'CUSTOMER_RETURN','WALLET_FUNDING','POS_SALE','WALLET_WITHDRAWAL'
    source_id uuid,                                       -- id of the source row in the owning module
    location_id uuid references locations(id),           -- where it happened
    reverses_entry_id uuid references customer_ledger_entries(id),
    reason varchar(300),                                  -- required for ADJUSTMENT_* and REVERSAL_*
    performed_by_user_id uuid references app_users(id),   -- null only for scheduled/system postings
    entry_date date not null default current_date,
    created_at timestamptz not null default now(),
    constraint cle_direction_matches_type check (
        (entry_type in ('FUNDING','RETURN_CREDIT','COMMISSION_CREDIT','CREDIT_NOTE','ADJUSTMENT_CREDIT','REVERSAL_CREDIT') and direction = 'CREDIT') or
        (entry_type in ('PURCHASE_DEBIT','WITHDRAWAL_DEBIT','ADJUSTMENT_DEBIT','REVERSAL_DEBIT') and direction = 'DEBIT')
    ),
    constraint cle_balance_after_non_negative check (balance_after >= 0)
);
create index cle_customer_idx on customer_ledger_entries (tenant_id, customer_id, balance_type, created_at);
create index cle_source_idx on customer_ledger_entries (tenant_id, source_module, source_id);
create unique index cle_single_reversal on customer_ledger_entries (reverses_entry_id) where reverses_entry_id is not null;
```

Rows are never updated or deleted. A mistake is corrected with a `REVERSAL_*` entry pointing at the original (`reverses_entry_id`, unique → an entry can be reversed once).

### 2.5 `customer_reclassifications`

```sql
create table customer_reclassifications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    customer_id uuid not null references customers(id),
    from_type varchar(30) not null,
    to_type varchar(30) not null,
    direction varchar(10) not null check (direction in ('PROMOTION','DEMOTION')),
    reason varchar(40) not null check (reason in (
        'SUSTAINED_SALES_PERFORMANCE','MET_PURCHASE_VOLUME','COMPLIANCE_CONCERN',
        'PAYMENT_ARREARS','PROLONGED_INACTIVITY','MANAGEMENT_DECISION')),
    note varchar(300),
    from_dealer_category_id uuid references dealer_categories(id),
    to_dealer_category_id uuid references dealer_categories(id),
    from_credit_limit numeric(14,2) not null,
    to_credit_limit numeric(14,2) not null,
    effective_from date not null,
    status varchar(20) not null check (status in ('SCHEDULED','APPLIED','CANCELLED')),
    authorised_by_user_id uuid not null references app_users(id),
    applied_at timestamptz,
    created_at timestamptz not null default now(),
    constraint reclass_types_differ check (from_type <> to_type)
);
create index reclass_customer_idx on customer_reclassifications (tenant_id, customer_id, created_at desc);
create unique index reclass_one_scheduled on customer_reclassifications (customer_id) where status = 'SCHEDULED';
```

### 2.6 Module settings registered (doc 03 §10)

| Key | Type | Default |
|---|---|---|
| `customers.correspondent.regions` | string[] | `["Lagos Mainland","Lagos Island","North Central","North West","South East","South South","South West"]` |
| `customers.temporary.matchByPhone` | boolean | `true` |

### 2.7 Custom fields

`custom_field_definitions` rows with `entity_type = 'CUSTOMER'` (doc 03 §12.2) apply to `customers.custom_attributes`; validated on create/update exactly as for stock items.

---

## 3. Permission codes (added in V8)

| Code | Allows | Default roles |
|---|---|---|
| `customers.read` | List/view all customers, dealers, correspondents in scope | Management, Accounts Personnel, Cashier |
| `customers.read.own` | List/view only customers whose correspondent is linked to the caller's user | Correspondent |
| `customers.manage` | Register/edit non-dealer customers, change status to WATCH/ACTIVE | Accounts Personnel, Cashier |
| `customers.dealers.manage` | Register/edit dealers; set any customer's credit limit; SUSPEND/re-activate any customer | Accounts Personnel |
| `customers.reclassify` | Promote/demote between types | Management |
| `customers.categories.manage` | Edit dealer categories | (Administrator only) |
| `customers.correspondents.manage` | Register correspondents; assign/reassign/de-assign; link customers | Management |
| `customers.ledger.read` | View wallet/deposit ledgers (Customer History) | Management, Accounts Personnel |
| `customers.wallet.adjust` | Post an adjustment or reversal entry | (Administrator only) |
| `customers.self.read` | A dealer/customer user reads their own record and ledger | Dealer |

Administrator holds all (`*`). `RoleDefaults` is extended with the defaults above.

**Location scope.** All list endpoints and single-record reads pass through `LocationAccessService.resolveFilter` against the customer's **home** location (an out-of-scope id reads as `404 CUSTOMER_NOT_FOUND`, so ids can't be probed); a single-location user only lists customers homed at their location. Lookups made by other modules through the `spi` (e.g. POS at showroom B serving a customer homed at A) are deliberately *not* location-restricted — a customer can buy anywhere.

---

## 4. Endpoints

All under `/api/v1/customers`, all return `ApiEnvelope<T>`, controllers split as `CustomerApi`/`CustomerController`, `DealerCategoryApi`/…, `CorrespondentApi`/…, `CustomerLedgerApi`/… (the project's `*Api` interface + controller pattern). Validation errors → `400 VALIDATION_FAILED` with field details (shipped `GlobalExceptionHandler`), not repeated per endpoint below.

### 4.1 Register a customer (any type) — `POST /api/v1/customers`

Perm: `customers.manage`; if `type = AUTHORISED_DEALER` **or** `creditLimit > 0`, also `customers.dealers.manage` (checked in service; missing → `403 FORBIDDEN`).

Request `CustomerRequest`:
| Field | Type | Rules |
|---|---|---|
| `type` | enum | required: `AUTHORISED_DEALER`, `SPECIAL_CUSTOMER`, `ORDINARY_CUSTOMER`, `NO_CREDIT_WALKIN` |
| `name` | string | required, 2–200 |
| `contactPerson` | string? | ≤160; required when `type = AUTHORISED_DEALER` |
| `phone` | string? | required unless `NO_CREDIT_WALKIN`; 7–20 chars of `+0-9 ()-` |
| `alternatePhone` | string? | same format |
| `email` | string? | valid e-mail, ≤320 |
| `tin` | string? | ≤40 |
| `businessDescription` | string? | ≤200 |
| `streetAddress` | string? | ≤300; required for `AUTHORISED_DEALER` |
| `city` | string? | ≤120 |
| `stateCode` | string? | must exist in `nigeria_states` → else `400 INVALID_STATE_CODE`; required for `AUTHORISED_DEALER` |
| `lgaCode` | string? | must exist in `nigeria_lgas` **and** belong to `stateCode` → else `400 INVALID_LGA_CODE` |
| `locationId` | uuid? | home location; must be an ACTIVE `SHOWROOM` or `FACTORY` location of the tenant (factories sell to dealers directly, and the Showroom module isn't built yet) → else `400 INVALID_LOCATION`; `LocationAccessService.assertAccess` |
| `correspondentId` | uuid? | must exist and be `ASSIGNED` → else `409 CORRESPONDENT_NOT_ASSIGNED` |
| `dealerCategoryId` | uuid? | required iff `AUTHORISED_DEALER` → `400 CATEGORY_REQUIRED_FOR_DEALER`; must be ACTIVE |
| `creditLimit` | decimal? | ≥0; dealer default = category's `suggested_credit_limit`; must be 0 for `NO_CREDIT_WALKIN` → `400 CREDIT_LIMIT_NOT_ALLOWED` |
| `customerSince` | date? | ≤ today; default today (for migrating existing customers) |
| `customAttributes` | object? | validated against active `CUSTOMER` custom fields → `400 INVALID_CUSTOM_FIELD` |

Logic: normalise phone (§4.8); code = `next("DLR")` for dealers, `next("CU")` otherwise; insert; emit `customers.customer.registered`. Dealer name collision → `409 DUPLICATE_NAME`.

Opening wallet funding shown on `DealerRegister.jsx` ("Fund wallet on registration") is **not** part of this request: the frontend calls Account's `POST /api/v1/account/wallet-fundings` (doc 07 §4.12) with the new id immediately after, because funding moves money into a bank account and needs Account's checks. This keeps one code path for every funding.

Response `CustomerDetail`:
```json
{ "data": {
  "id": "…", "code": "DLR-42", "type": "AUTHORISED_DEALER", "isTemporary": false,
  "name": "Okafor & Sons Distribution", "contactPerson": "Tunde Okafor",
  "phone": "+234 803 412 7781", "alternatePhone": null, "email": "orders@okaforsons.ng",
  "tin": "21993344-0001", "businessDescription": "Beverage distribution",
  "streetAddress": "14 Ikorodu Rd", "city": "Lagos", "stateCode": "NG-LA", "stateName": "Lagos",
  "lgaCode": null, "lgaName": null, "countryCode": "NG",
  "location": { "id": "…", "code": "SHR-1", "name": "Showroom Ikeja" },
  "correspondent": { "id": "…", "code": "CR-11", "name": "Bisi Adeyemi", "status": "ASSIGNED" },
  "dealerCategory": { "id": "…", "name": "Gold", "discountPct": 3.50 },
  "creditLimit": 1500000.00, "walletBalance": 0.00, "depositBalance": 0.00,
  "lifetimeValue": 0.00, "lastPurchaseAt": null, "customerSince": "2026-10-08",
  "status": "ACTIVE", "statusReason": null,
  "customAttributes": {}, "createdAt": "2026-10-08T09:12:44Z"
} }
```
`outstandingBalance` is **not** on this DTO (it belongs to Invoicing); the frontend's Debtors List calls doc 08.

### 4.2 List / search — `GET /api/v1/customers` [paged]

Perm: `customers.read` or `customers.read.own` (the latter adds `correspondent.user_id = caller`).
Query: `type` (repeatable), `status` (repeatable; default excludes `REMOVED`), `isTemporary` (default `false`), `locationId`, `correspondentId`, `dealerCategoryId`, `hasWalletBalance` (bool), `q`, `page`, `size`, `sort` (`code`, `name`, `customerSince`, `walletBalance`, `lifetimeValue`, `createdAt`; default `name,asc`).
Search fields (doc 03 §4.3; full filter catalogue in §9 below): `code`, `phone_e164` (the normalised `q`), `email` → CODE; `name`, `contact_person` → TRIGRAM; `search_vector` → FULLTEXT.
Row `CustomerSummary`: `{ id, code, type, isTemporary, name, contactPerson, phone, stateCode, stateName, locationId, locationName, correspondentId, correspondentName, dealerCategoryName, creditLimit, walletBalance, depositBalance, lifetimeValue, customerSince, status }`.

`GET /api/v1/customers/stats?type=&locationId=` → `{ total, active, watch, suspended, dealers, special, ordinary, noCredit, walletsHeld, walletBalanceTotal, depositBalanceTotal }` (for register header tiles).

### 4.3 Get / update / status

- `GET /api/v1/customers/{id}` — perm `customers.read` (or `.own` scoped). `404 CUSTOMER_NOT_FOUND`.
- `PUT /api/v1/customers/{id}` — perm as §4.1. Body `CustomerUpdateRequest` = `CustomerRequest` **minus** `type`, `dealerCategoryId` (changed only by reclassification, §4.4) and `customerSince` (immutable). Changing `creditLimit` requires `customers.dealers.manage`. Emits `customers.customer.updated` (payload lists changed field names, not values) and, if credit limit changed, `customers.customer.credit-limit-changed` (payload `{from, to}`).
- `PUT /api/v1/customers/{id}/status` — body `{ status: ACTIVE|WATCH|SUSPENDED, reason }`. `WATCH` ⇄ `ACTIVE` needs `customers.manage`; anything involving `SUSPENDED` needs `customers.dealers.manage`. `REMOVED` cannot be set by hand (`409 INVALID_STATUS_TRANSITION`) — only the temporary-wallet rule sets it. Suspended customers cannot be debited, credited, or sold to (`409 CUSTOMER_NOT_ACTIVE`); `WATCH` is informational (shows a badge on POS/Debtors). Emits `customers.customer.status-changed`.

### 4.4 Promotion / demotion — `POST /api/v1/customers/{id}/reclassifications`

Perm: `customers.reclassify`.
Request: `{ toType, reason (enum §2.5), note?, effectiveFrom (date, ≥ today), toDealerCategoryId? (required iff toType = AUTHORISED_DEALER), toCreditLimit? }`.
Rules (from `DealersUpdate.jsx`):
- `toType == current type` → `400 RECLASSIFICATION_SAME_TYPE`. Temporary customers can't be reclassified (`409 CUSTOMER_IS_TEMPORARY`) — register them properly first (§4.7).
- Direction derived from rank `NO_CREDIT_WALKIN 0 < ORDINARY_CUSTOMER 1 < SPECIAL_CUSTOMER 2 < AUTHORISED_DEALER 3`: higher → `PROMOTION`, lower → `DEMOTION`.
- **Wallet balance always carries over.** Deposit balance: if moving *away* from `SPECIAL_CUSTOMER` with `deposit_balance > 0` → `409 DEPOSIT_MUST_BE_SETTLED` (the deposit must first be drawn or refunded through Account — the `deposit_balance = 0` check constraint would reject it otherwise).
- Credit limit: `toCreditLimit` if given; else promotion to dealer → category's suggested limit; demotion to `NO_CREDIT_WALKIN` → 0 (allowed even with debt outstanding — it only stops *new* credit); otherwise unchanged.
- Dealer category cleared when leaving `AUTHORISED_DEALER`.
- Commission eligibility follows type: only `AUTHORISED_DEALER` earns commission (doc 12).
- `effectiveFrom == today` → applied immediately (status `APPLIED`); future → `SCHEDULED`; a job (`ReclassificationService.applyDue`, hourly at :05 so every tenant timezone is covered, cron `zifiq.customers.reclassification-cron`) applies due ones, each in its own transaction; one that can't be applied yet (e.g. a deposit was taken after scheduling → `DEPOSIT_MUST_BE_SETTLED`) stays `SCHEDULED`, is logged and retried next run. Only one `SCHEDULED` per customer (`409 RECLASSIFICATION_PENDING`); `DELETE /api/v1/customers/{id}/reclassifications/{reclassId}` cancels a `SCHEDULED` one (status `CANCELLED`, row kept).
Response: `ReclassificationSummary { id, customerId, customerCode, fromType, toType, direction, reason, effectiveFrom, status, fromCreditLimit, toCreditLimit, toDealerCategoryName, walletBalanceCarriedOver, authorisedBy, createdAt }`.
Events: `customers.customer.reclassified` (on apply), `customers.reclassification.scheduled`, `customers.reclassification.cancelled`.
`GET /api/v1/customers/reclassifications?customerId=&direction=&from=&to=` [paged] — history.

### 4.5 Customer history (ledger) — `GET /api/v1/customers/{id}/ledger` [paged]

Perm: `customers.ledger.read` (or `customers.self.read` for the caller's own id).
Query: `balanceType` (`WALLET` default | `DEPOSIT`), `entryType` (repeatable), `from`, `to`, `q` (reference CODE, description FULLTEXT), `page`, `size` (sort fixed: `created_at asc`, so running balances read top-down like `CustomerHistory.jsx`).
Row: `{ id, entryDate, reference, entryType, displayType ('Purchase'|'Return'|'Wallet funding'|'Withdrawal'|'Commission'|'Adjustment'|'Reversal'), direction, description, paymentMethod, amount (signed: credit +, debit −), balanceAfter, locationName, performedBy, reversible (bool) }`.
Response wraps the page with a header: `{ customer: CustomerSummary, openingBalance (balance before the first row of the range), closingBalance, totalCredits, totalDebits, items: [...], page... }`.

### 4.6 Wallet adjustment & reversal (admin correction)

- `POST /api/v1/customers/{id}/wallet-adjustments` — perm `customers.wallet.adjust`. Body `{ balanceType, direction: CREDIT|DEBIT, amount (>0), reason (required, ≤300) }`. Posts `ADJUSTMENT_CREDIT`/`ADJUSTMENT_DEBIT` via the same `WalletLedger` code path (reference `next("WLT")`, source `customers`/`ADMIN_ADJUSTMENT`). Debit beyond balance → `400 INSUFFICIENT_WALLET_BALANCE` / `INSUFFICIENT_DEPOSIT_BALANCE`. Deposit adjustments only on `SPECIAL_CUSTOMER` → else `400 BALANCE_TYPE_NOT_ALLOWED`. Emits `customers.wallet.adjusted`.
- `POST /api/v1/customers/ledger-entries/{entryId}/reverse` — perm `customers.wallet.adjust`. Body `{ reason }`. Posts the opposite `REVERSAL_*` entry for the same amount; reversing a credit that's already been spent fails (`400 INSUFFICIENT_WALLET_BALANCE`). Already reversed → `409 ALREADY_REVERSED`. Entries whose `source_module ≠ customers` (a sale, a withdrawal) are **not** reversible here → `409 REVERSE_IN_SOURCE_MODULE` — they're reversed by voiding the source document in its own module, which posts the reversal through the spi. Emits `customers.wallet.entry-reversed`.

**Temporary-customer removal (PRD rule 10).** Inside `WalletLedger.debit`: after posting, if the customer `is_temporary` and its `wallet_balance` is now 0 → set `status = 'REMOVED'`, `removed_at = now()`, emit `customers.customer.removed` (summary "Temporary customer removed — wallet fully spent"). The row and its ledger remain for audit; removed customers never appear in pickers or default lists and their phone no longer matches (`customers_temp_phone_uq` covers only `ACTIVE`), so the same person returning goods later gets a fresh temporary record.

### 4.7 Convert a temporary customer to a registered one — `POST /api/v1/customers/{id}/register`

Perm `customers.manage`. Allowed only when `is_temporary` and `status = ACTIVE`. Body = `CustomerRequest` (type may be any; dealer needs `customers.dealers.manage`). Sets `is_temporary = false` and fills the fields; the code is kept (`CU-`); wallet carries over. Emits `customers.customer.registered`.

### 4.8 Phone normalisation (used everywhere a phone is stored or searched)

Implemented once in `common.phone.PhoneNumbers` (Settings, Showroom and Account reuse it). Strip everything but digits and a leading `+`. `0` + 10 digits → `+234` + the 10 digits. `234` + 10 digits → `+234…`. Already `+` → keep. Anything else → stored as given, `phone_e164` = digits only. Searching `q` that looks like a phone (≥7 digits after stripping) is normalised the same way and matched against `phone_e164` by prefix.

### 4.9 Dealer categories — `/api/v1/customers/dealer-categories`

- `GET` — perm `customers.read`. Unpaged. Query `status`. Row `{ id, name, discountPct, suggestedCreditLimit, sortOrder, status, dealerCount }`.
- `POST` — perm `customers.categories.manage`. Body `{ name, discountPct, suggestedCreditLimit, sortOrder }`. `409 DUPLICATE_NAME`.
- `PUT /{id}` — same body. Changing `suggestedCreditLimit` does **not** change existing dealers' limits (it's a suggestion for new/reclassified dealers). Changing `discountPct` takes effect on the next POS sale (doc 09 snapshots it per sale line).
- `PUT /{id}/status` — `{ status }`. Deactivating a category in use by an ACTIVE dealer → `409 CATEGORY_IN_USE`.

### 4.10 Correspondents — `/api/v1/customers/correspondents`

- `POST` — perm `customers.correspondents.manage`. Body `{ name, phone, email?, userId?, region?, locationId?, responsibilities? }`. Code `next("CR")`. If `region` + `locationId` given, it's registered **and** assigned in one step (status `ASSIGNED`, history row `ASSIGNED`); otherwise `UNASSIGNED`. `region` not in `customers.correspondent.regions` → `400 INVALID_REGION`. `userId` must be an active tenant member → `400 INVALID_USER`; already linked to another correspondent → `409 USER_ALREADY_LINKED`.
- `GET` — perm `customers.read`. Unpaged (small list). Query `status`, `region`, `locationId`, `q` (code CODE, name TRIGRAM, phone). Row `{ id, code, name, phone, email, region, locationId, locationName, status, assignedAt, customerCount, dealerCount, responsibilities, userLinked: bool }`.
- `GET /{id}` — detail + `history: [{ action, region, locationName, effectiveFrom, note, performedBy, createdAt }]`.
- `PUT /{id}` — edit name/phone/email/userId/responsibilities (not region/location/status — those go through the actions below).
- `POST /{id}/assign` — `{ region, locationId, effectiveFrom?, note? }` from `UNASSIGNED`/`DE_ASSIGNED` → `ASSIGNED` (history `ASSIGNED`).
- `POST /{id}/reassign` — same body, from `ASSIGNED` only → stays `ASSIGNED` with new region/location (history `REASSIGNED`).
- `POST /{id}/de-assign` — `{ effectiveFrom?, note? }` from `ASSIGNED` → `DE_ASSIGNED` (history `DE_ASSIGNED`). Customers linked to them **keep** the link (history must stay attributable) but the correspondent can't be credited with new sales (doc 09 snapshots only `ASSIGNED` correspondents) — response includes `affectedCustomers` count so the UI can prompt re-linking.
- Wrong-state transitions → `409 INVALID_STATUS_TRANSITION`.
- `GET /{id}/customers` [paged] — the correspondent's customers (`CustomerSummary`).
- `PUT /api/v1/customers/{customerId}/correspondent` — perm `customers.correspondents.manage`, body `{ correspondentId | null }` — link/unlink; target must be `ASSIGNED`.
- Events: `customers.correspondent.registered`, `.assigned`, `.reassigned`, `.de-assigned`, `customers.customer.correspondent-changed`.

### 4.11 Self-service

- `GET /api/v1/customers/me` — perm `customers.self.read`. Returns the `CustomerDetail` whose `user_id` = caller (`404 CUSTOMER_NOT_FOUND` if none).
- `GET /api/v1/customers/me/ledger` — same as §4.5 for that record.

---

## 5. Exposed to other modules — `com.zifiq.customers.spi`

All methods run in the caller's transaction (doc 03 §2.4). DTOs here are plain Kotlin data classes in the `spi` package.

### 5.1 `CustomerDirectory`
```kotlin
fun get(id: UUID): CustomerRef                       // throws CUSTOMER_NOT_FOUND
fun getForUpdate(id: UUID): CustomerRef              // same, locks the row FOR UPDATE
fun findActiveByPhone(phone: String): CustomerRef?   // normalised match, excludes REMOVED
fun search(q: String, limit: Int = 20, types: Set<CustomerType>? = null): List<CustomerRef>  // POS picker; not location-restricted
```
`CustomerRef(id, code, type, isTemporary, name, contactPerson, phone, email, tin, businessDescription, streetAddress, city, stateCode, lgaCode, countryCode, locationId, correspondentId, correspondentAssigned: Boolean, dealerCategoryId, dealerCategoryName, dealerDiscountPct, creditLimit, walletBalance, depositBalance, status)`.

### 5.2 `WalletLedger`
```kotlin
fun credit(posting: LedgerPosting): LedgerEntryRef
fun debit(posting: LedgerPosting): LedgerEntryRef   // INSUFFICIENT_WALLET_BALANCE / INSUFFICIENT_DEPOSIT_BALANCE
fun reverse(entryId: UUID, reason: String, sourceModule: String): LedgerEntryRef   // used when a source document is voided
data class LedgerPosting(
    val customerId: UUID, val balanceType: BalanceType, val entryType: LedgerEntryType,
    val amount: BigDecimal, val reference: String, val description: String,
    val paymentMethod: String?, val sourceModule: String, val sourceType: String, val sourceId: UUID?,
    val locationId: UUID?, val reason: String? = null)
```
Checks, in order: customer exists and is not `SUSPENDED`/`REMOVED` (`409 CUSTOMER_NOT_ACTIVE`); `entryType` matches direction; balance type allowed for the customer type (§1.3 table: `FUNDING` to `WALLET` only for dealers, `DEPOSIT` only for special customers → `400 BALANCE_TYPE_NOT_ALLOWED`); amount > 0; then lock, compute, insert, update cache, temp-removal rule (§4.6), emit `customers.wallet.credited` / `customers.wallet.debited` (summary like "Wallet debited ₦12,500 — TXN-4481"). `reverse` is the only path that may reverse a non-`customers` entry, and only when called with the entry's own `sourceModule`.

### 5.3 `TemporaryCustomers`
```kotlin
fun findOrCreate(name: String, phone: String, locationId: UUID): CustomerRef
```
Matches an `ACTIVE` temporary **or** a registered non-dealer customer by normalised phone (registered match wins — "Used to match an existing temporary record" plus an existing ordinary customer shouldn't be duplicated). None found → creates `type = NO_CREDIT_WALKIN`, `is_temporary = true`, code `next("CU")`, home location = `locationId`, emits `customers.customer.temporary-created`. Phone is mandatory for temporary records (`400 PHONE_REQUIRED`).

### 5.4 `Correspondents`
```kotlin
fun get(id: UUID): CorrespondentRef
fun creditableFor(customerId: UUID): CorrespondentRef?   // the customer's correspondent iff status = ASSIGNED, else null
fun listAssigned(locationId: UUID?): List<CorrespondentRef>
```

### 5.5 `CustomerActivity`
```kotlin
fun recordSale(customerId: UUID, amount: BigDecimal, at: Instant)       // lifetime_value += amount; last_purchase_at = max(...)
fun reverseSale(customerId: UUID, amount: BigDecimal)                   // called when a sale/invoice is voided
```

### 5.6 Read-only for reports
`CustomerQueries.byIds(ids)`, `CustomerQueries.ledgerEntries(filter)` (used by doc 10 Wallet Withdrawal History and doc 13), `CustomerQueries.correspondentHistory(id)`.

---

## 6. Business rules (traceable)

| # | Rule | Source | Enforced at |
|---|---|---|---|
| C1 | A wallet or deposit balance never goes negative | PRD §6 rule 5; `DealerRegister.jsx` callout | `WalletLedger.debit` + DB checks `customers_balances_non_negative`, `cle_balance_after_non_negative` |
| C2 | A temporary (non-dealer) customer is removed once its wallet reaches zero | PRD §6 rule 10; `CustomerContacts.jsx` footnote | `WalletLedger.debit` (§4.6) |
| C3 | A sale bigger than the dealer's wallet is part-settled another way | `DealerRegister.jsx` callout | doc 09 split-tender (wallet debit limited to balance) |
| C4 | Dealer names are unique; customers can't be created with missing required fields | `DealerRegister.jsx` footnote; PRD 5.8.1 | `customers_dealer_name_uq`; request validation |
| C5 | Reclassification never zeroes or moves the wallet | `DealersUpdate.jsx` side panel | §4.4 |
| C6 | Only Authorised Dealers earn commission | `DealersUpdate.jsx` ("Commission eligibility: eligible only if Authorised Dealer"); PRD 5.5.4 | doc 12 reads `type` |
| C7 | Unassigned/de-assigned correspondents can't be credited with sales or earn commission | `Correspondent.jsx` | `Correspondents.creditableFor` |
| C8 | De-assigning/reassigning keeps history | `Correspondent.jsx` | append-only `correspondent_assignment_history`; links kept |
| C9 | No-credit customers have no credit limit | PRD 5.7.3 "No Credit Customers" | `customers_no_credit` check |
| C10 | A credit sale must not push a customer beyond `credit_limit` | `DebtorsList.jsx` callout (not in PRD §6) | doc 09 at sale time using `CustomerRef.creditLimit` + doc 08 outstanding |
| C11 | Users only see customers of their assigned location (unless ALL_LOCATIONS) | PRD §6 rule 12 | list endpoints via `LocationAccessService` |
| C12 | Correspondents see only their own customers; dealers only their own account | PRD §7 | `customers.read.own`, `customers.self.read` |
| C13 | A ledger entry is never edited; corrections are reversal entries | doc 03 §13 | no update/delete paths; `cle_single_reversal` |

---

## 7. Errors added (ErrorCode / ApiErrors)

`CUSTOMER_NOT_FOUND` 404 · `CUSTOMER_NOT_ACTIVE` 409 · `CUSTOMER_IS_TEMPORARY` 409 · `INSUFFICIENT_WALLET_BALANCE` 400 (details `{available, requested}`) · `INSUFFICIENT_DEPOSIT_BALANCE` 400 (same) · `BALANCE_TYPE_NOT_ALLOWED` 400 · `CATEGORY_REQUIRED_FOR_DEALER` 400 · `CREDIT_LIMIT_NOT_ALLOWED` 400 · `DEALER_CATEGORY_NOT_FOUND` 404 · `CATEGORY_IN_USE` 409 · `RECLASSIFICATION_SAME_TYPE` 400 · `RECLASSIFICATION_PENDING` 409 · `DEPOSIT_MUST_BE_SETTLED` 409 · `CORRESPONDENT_NOT_FOUND` 404 · `CORRESPONDENT_NOT_ASSIGNED` 409 · `INVALID_REGION` 400 · `INVALID_USER` 400 · `USER_ALREADY_LINKED` 409 · `INVALID_STATE_CODE` 400 · `INVALID_LGA_CODE` 400 · `INVALID_LOCATION` 400 · `INVALID_STATUS_TRANSITION` 409 · `ALREADY_REVERSED` 409 · `REVERSE_IN_SOURCE_MODULE` 409 · `PHONE_REQUIRED` 400 · `LEDGER_ENTRY_NOT_FOUND` 404.

`INVALID_STATUS_TRANSITION` and `INVALID_LOCATION` are generic and reused by later modules.

---

## 8. Event catalog

| Event type | When | Notifies |
|---|---|---|
| `customers.customer.registered` | §4.1, §4.7 | — |
| `customers.customer.updated` | §4.3 | — |
| `customers.customer.credit-limit-changed` | §4.3 | in-app: holders of `customers.dealers.manage` (except the actor) — "Credit limit for DLR-42 changed ₦1.5m → ₦2m" |
| `customers.customer.status-changed` | §4.3 | — |
| `customers.customer.temporary-created` | §5.3 | — |
| `customers.customer.removed` | §4.6 | — |
| `customers.reclassification.scheduled` / `.cancelled` | §4.4 | — |
| `customers.customer.reclassified` | §4.4 apply | in-app: the customer's correspondent's user (if linked) |
| `customers.correspondent.registered` / `.assigned` / `.reassigned` / `.de-assigned` | §4.10 | in-app: the correspondent's user (if linked) |
| `customers.customer.correspondent-changed` | §4.10 | — |
| `customers.wallet.credited` / `.debited` | §5.2 | — |
| `customers.wallet.adjusted` / `.entry-reversed` | §4.6 | in-app: holders of `customers.ledger.read` |

---

## 9. Search & filters

Implements doc 03 §4–§5. Every paged list below also accepts `POST <list>/search` (advanced filter body), `GET <list>/filters` (metadata), `facets=`, `highlight=true`, saved views (`list_key`), and exports with the same filters.

### Customers — `GET /api/v1/customers` [paged] · list_key `customers.customers`

| Aspect | Definition |
|---|---|
| `q` fields | `code` REFERENCE (`DLR-42`, bare `42`); `phone_e164` PHONE; `email` CODE; `tin` CODE; `name`, `contact_person` TRIGRAM (accent-insensitive, typo-tolerant); `search_vector` FULLTEXT over name + contact person + street address + city + notes |
| Filters / qualifiers | `type` ENUM multi (`type:dealer`, `type:special`, `type:walkin` via unambiguous word match, doc 03 §4.3) · `status` ENUM multi (default excludes `REMOVED`) · `isTemporary` BOOLEAN · `locationId` LOCATION · `correspondentId` UUID (`rep:`) · `dealerCategoryId` UUID (pick from `GET /customers/dealer-categories`; a `category:gold` name qualifier is not implemented — filter by id) · `stateCode`, `lgaCode` ENUM · `walletBalance`, `depositBalance`, `creditLimit`, `lifetimeValue` MONEY ranges · `hasWalletBalance`, `hasEmail`, `hasPhone`, `hasTin`, `hasCorrespondent` BOOLEAN · `customerSince`, `lastPurchaseAt`, `createdAt` DATE ranges (`lastPurchaseAt.lt=<date>` = "dormant since") · `createdBy` UUID · **custom fields** `attr.<field_key>` typed from `custom_field_definitions` (entity `CUSTOMER`, doc 03 §12.2), e.g. `attr.trade_sector=HARDWARE` |
| Advanced-only fields | `ledger` collection (`EXISTS` on `customer_ledger_entries`: `ledger.entryType`, `ledger.amount`, `ledger.entryDate`), e.g. "dealers with a withdrawal over ₦100,000 this month" |
| Facets | `type`, `status`, `location`, `dealerCategory`, `correspondent`, `state` |
| Sort | `name,asc` (default); `code`, `customerSince`, `walletBalance`, `depositBalance`, `lifetimeValue`, `lastPurchaseAt`, `createdAt` |
| Summary | `{ count, walletBalanceTotal, depositBalanceTotal, lifetimeValueTotal }` over the filtered set (`/customers/stats` stays for the register tiles) |

### Customer ledger — `GET /api/v1/customers/{id}/ledger` [paged, keyset] · list_key `customers.ledger`
`q`: `reference` REFERENCE, `description` FULLTEXT, `amount` AMOUNT. Filters: `balanceType`, `entryType` multi, `direction`, `sourceModule`, `locationId`, `performedBy`, `amount` range, `from`/`to`/`period`, `reversible`. Facets: `entryType`, `direction`. Sort fixed `createdAt asc` (running balance must read top-down); `sort=createdAt,desc` is allowed only with `includeRunningBalance=false`. Summary: `{ openingBalance, closingBalance, totalCredits, totalDebits }`.

### Reclassifications — `GET /api/v1/customers/reclassifications` [paged] · list_key `customers.reclassifications`
`q`: customer code REFERENCE, customer name TRIGRAM, note FULLTEXT. Filters: `customerId`, `direction`, `fromType`, `toType`, `reason`, `status`, `effectiveFrom` range, `authorisedBy`. Facets: `direction`, `status`, `reason`. Sort: `effectiveFrom desc`; `createdAt`.

### Correspondents — `GET /api/v1/customers/correspondents` [paged] · list_key `customers.correspondents`
Each correspondent's `customerCount` / `dealerCount` (live customers, dealers among them) are columns on the correspondent row, kept by a trigger on `customers` (V14_2), so filtering by `hasCustomers` and sorting by the counts never count customers at request time.
`q`: `code` REFERENCE, `name` TRIGRAM, `phone` PHONE, `email` CODE, `responsibilities` FULLTEXT. Filters: `status` multi, `region` multi, `locationId`, `userLinked`, `hasCustomers`, `assignedAt` range. Facets: `status`, `region`. Sort: `name`; `customerCount`, `dealerCount`, `assignedAt`. `GET /{id}/customers` uses the Customers spec above with `correspondentId` fixed.

### Dealer categories (unpaged)
`q` name TRIGRAM; filters `status`.

### Lookups (pickers)
- `GET /api/v1/customers/lookup?q=&types=&forSale=true&locationId=` → `{ id, code, label (name), sublabel ("DLR-42 · Gold · 0803…"), status, disabled, disabledReason, walletBalance, creditAvailable }`. `forSale` hides `SUSPENDED`/`REMOVED` and shows them disabled with the reason when matched by exact code. Used by the POS customer picker, pro-forma and returns forms.
- `GET /api/v1/customers/correspondents/lookup?q=&assignedOnly=true`.

### Global search provider
Type `customer` (perm `customers.read` / `.read.own`): title = name, subtitle = `code · type · masked phone (…4567)`, route `/customers/{id}`. Type `correspondent`: title = name, subtitle = `code · region · status`.

### Indexes (V8)
`customers`: `f_unaccent(lower(name))` and `f_unaccent(lower(contact_person))` trigram; `phone_e164 text_pattern_ops` btree (prefix/suffix by reversed-digits expression index `reverse(phone_e164)` for suffix match); `lower(email)` btree; `(tenant_id, type, status, name)`; `(tenant_id, location_id, status)`; `(tenant_id, correspondent_id)`; `(tenant_id, dealer_category_id)`; `(tenant_id, last_purchase_at)`; `search_vector` GIN; `custom_attributes` GIN (`jsonb_path_ops`). This needs a new column: V8 adds `customers.custom_attributes jsonb not null default '{}'`, validated against the active `CUSTOMER` custom-field definitions exactly as stock items are (doc 03 §12.2; `400 INVALID_CUSTOM_FIELD`), and `CustomerRequest` gains `customAttributes`. `customer_ledger_entries`: `(tenant_id, customer_id, balance_type, created_at, id)`; `(tenant_id, reference)`; `search_vector` GIN on description. `correspondents`: name trigram, `(tenant_id, status, region)`.

## 10. Worked example

1. Accounts registers a dealer: `POST /api/v1/customers` `{ type: AUTHORISED_DEALER, name: "Okafor & Sons Distribution", contactPerson: "Tunde Okafor", phone: "0803 412 7781", stateCode: "NG-LA", streetAddress: "14 Ikorodu Rd", locationId: <Ikeja>, dealerCategoryId: <Gold>, correspondentId: <CR-11> }` → `DLR-42`, credit limit ₦1,500,000 (Gold's suggestion), `phone_e164 = +2348034127781`, wallet 0.
2. The frontend immediately calls Account `POST /api/v1/account/wallet-fundings` `{ customerId, amount: 250000, method: FUND_TRANSFER, bankAccountId: <GTB collections> }` (doc 07) → Account posts the bank side and calls `WalletLedger.credit(FUNDING, 250000, ref "WF-18")` → ledger row `balance_after 250,000`.
3. A walk-in returns 2 cartons at Ikeja (doc 05): Showroom calls `TemporaryCustomers.findOrCreate("Ada", "08091112222", Ikeja)` → new `CU-96` (temporary), then `WalletLedger.credit(RETURN_CREDIT, 9000, "RT-458")`.
4. Ada comes back and buys ₦9,000 of goods paying entirely by wallet (doc 09): `WalletLedger.debit(PURCHASE_DEBIT, 9000, "TXN-4502")` → balance 0 → `CU-96` becomes `REMOVED`, event `customers.customer.removed`.
5. Management promotes `CU-77` (Ordinary, wallet ₦4,000) to Special Customer effective next Monday: `POST …/reclassifications { toType: SPECIAL_CUSTOMER, reason: MET_PURCHASE_VOLUME, effectiveFrom: 2026-10-12 }` → `SCHEDULED`; on 12 Oct the job applies it; wallet ₦4,000 carried over; the customer can now hold a deposit.
6. `GET /api/v1/customers/{DLR-42}/ledger` → one `FUNDING` row, `displayType "Wallet funding"`, `paymentMethod "Fund transfer"`, `balanceAfter 250000.00`.

---

## 11. Verification

- Testcontainers: two concurrent `debit` calls of ₦150,000 against a ₦250,000 wallet → exactly one succeeds, one gets `INSUFFICIENT_WALLET_BALANCE`; final balance ₦100,000; ledger sum equals cache.
- Direct SQL `update customers set wallet_balance = -1` fails on the check constraint.
- Temp-removal: credit 9,000 then debit 9,000 → status `REMOVED`; `findOrCreate` with the same phone creates a new record.
- Reclassify away from Special with a deposit → `409 DEPOSIT_MUST_BE_SETTLED`.
- Correspondent-role user lists customers → only their own; Dealer user `GET /me` → own record only.
- Search: `q=okafor` ranks `DLR-42` first; `q=0803 412` matches by phone; another tenant's customers never appear.
- Newman folder "11 - Customers": register each type, dealer duplicate name 409, reclassify now/scheduled/cancel, correspondent lifecycle, ledger read, adjustment + reversal, self-service.
- `ModularityTests`: `customers` depends only on platform/common/inventory.
