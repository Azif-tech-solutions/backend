# 07 — Finance & Accounting Module

Status: **slice 1 of 3 implemented** (2026-10-12: the money ledgers — §2.1–2.4, §2.11, §4.1–4.5, §4.16, §5.3, §5.5; see §12). Slice 2 (petty cash, running costs, funds awaiting, lodgements) and slice 3 (receipts, tenderable, wallet money, other income, VAT per location) follow. VAT/HSN/ISIC classification in this package is already shipped — V5 — and is extended, not redesigned.
Owner package: `com.zifiq.account` (substrate module — exposes `com.zifiq.account.spi`)
Depends on: `platform`, `common`, `inventory` (OPEN), `customers :: spi`
Depended on by: `invoicing` (invoice payments post here), `cashier` (every POS payment posts here), `communication` (commission payouts), `history`, `reports`, `dashboard`, `ai`.
Migration: `V11__finance_accounting_ledgers.sql` (slice 1); slices 2 and 3 add `V11_1`, `V11_2`
Replaces: the mock `AccountService`/`AccountController`/`AccountDto.kt` (`getCashFlow`, `getBankStatement`, `getPettyCashStatement`, `recordOperatingCost`, `registerBankAccount`, `topUpPettyCash`) and `process`'s mock exchange rates.

---

## 1. Scope

### 1.1 In scope (PRD → screen)

| Feature | PRD | Screen |
|---|---|---|
| Cash Inflow/Outflow Statement (per showroom, per factory, company-wide) | 5.3.1, 5.1.18 | `account/AccountCashFlow.jsx`, factory nav "Cash Inflow/Outflow" |
| Bank Statement | 5.3.2 | `account/BankStatement.jsx` |
| Petty Cash Payment History | 5.3.3 (flagged "not yet functional" in the PRD — made real here) | `account/PettyCashPaymentHistory.jsx` |
| Petty Cash Statement | 5.3.4 | `account/PettyCashStatement.jsx` |
| Payment / Running Cost (approval workflow, drafts) | 5.3.5 | `account/RunningCost.jsx` |
| Tenderable (cashier reconciliation, receipt track, wallet funds, general report) | 5.3.6 | `account/Tenderable.jsx` |
| Expenses / Payment List Guide | 5.3.7 | `account/ExpenseGuide.jsx` |
| Bank Register | 5.3.8 | `account/BankRegisterScreen.jsx` |
| Credit Petty Cash = Fund Petty Cash (one feature, two menu entries) | 5.3.9, 5.3.12 | `account/FundPettyCash.jsx` |
| POS / Fund Transfer Confirmation | 5.3.10 | `account/POSConfirmation.jsx` |
| Funds Awaiting (HQ → branch/factory disbursement confirmation) | 5.3.11, 5.1.19 | `account/FundsAwaitingBranch.jsx`, factory nav "Funds Awaiting Reception" |
| Petty Cash Payment / Wallet Withdrawal | 5.3.13 | `account/PettyCashPayment.jsx` |
| Accept or Reject Pending Petty Cash | 5.3.14 | `account/PendingPettyCash.jsx` |
| Cash Transfer History (takings lodged to bank) | 5.7.x | `others/CashTransferHistory.jsx` |
| Exchange Rate (Financial Controls) | 5.5.x | `process/ExchangeRate.jsx` |
| VAT Control per location (rate, enablement, registration no., effective date) | 5.5.x | `process/VATControl.jsx` |
| Wallet funding (dealer) and deposit funding/refund (special customer) | 5.3.6 Wallet Funds tab, `DealerRegister.jsx` "Fund wallet on registration" | — |
| Cash accounts register (tills, petty-cash boxes, vaults) | implied by POS "Till POS-IKJ-01" and per-location petty cash | — |
| Payment receipts — the single record of money received, issued/voided, printable | 5.3.6 Receipt Track, `POS.jsx` "Print receipt", `TransactionReceipts.jsx` | — |

### 1.2 Not here

SCI (Statement of Comprehensive Income) → doc 13 (needs sales revenue). Factory/Showroom VAT reports → doc 10. Transaction Receipts screen → platform registry (doc 03 §8). Adjustments Report → Inventory (doc 05 §4.6). Invoice "record payment" → doc 08 (posts here through the spi). Accounts payable to suppliers → not in the PRD (Running Cost covers paying a supplier).

### 1.3 Decisions

1. **Two ledgers, many documents.** Every money movement is a *document* (a funding, a payment, a lodgement, a receipt, …) that posts one or more *ledger entries*: `bank_transactions` (per bank account) and `cash_transactions` (per cash account). Wallet/deposit movements post to Customers' ledger through its spi. Statements are reads over ledgers; nothing is entered twice. This is the PRD's master consistency rule — "All financial entries must be reflected consistently across related statements and reports (e.g. Cash Inflow/Outflow, Petty Cash)" — enforced by construction.
2. **Cash accounts, not "showroom cash" + "petty cash fund" as separate concepts.** A *cash account* is a physical cash container at a location: a `TILL` (a cashier's drawer, optionally with a card terminal), a `PETTY_CASH` box, or a `VAULT` (branch safe, HQ cash). Location-level Cash Inflow/Outflow = all cash accounts at that location; the Petty Cash Statement = the `PETTY_CASH` accounts. `location_id` null = head-office cash.
3. **One `payment_receipts` table for all money received** — POS sales (Cashier), invoice payments (Invoicing), wallet and deposit fundings (here). POS/Fund-Transfer/Cheque receipts need Accounts confirmation (PRD §6 rule 7) before the bank is credited; the POS/Fund Transfer Confirmation screen is a filtered view of this table, and Tenderable is an aggregation of it. This replaces the research proposal of separate `pos_transfer_confirmations` + `cashier_receipts` tables, which would have had to be kept in sync.
4. **Declined POS/transfer payments are not "un-sold" here.** The sale stays; the receipt becomes `DECLINED`; Invoicing derives invoice status from non-declined receipts (doc 08), so the invoice becomes Partially Paid / Overdue and shows up in receivables. Account never calls Invoicing (it can't — doc 03 §2.3).
5. **Four-eyes on running costs.** "Payments are registered and verified by accounting personnel before processing" (5.3.5): the approver must not be the submitter (Administrator excepted, for single-person tenants).
6. **Exchange rates are append-only**, and every foreign-currency value stores the rate it used ("historic records keep the rate that applied at the time").
7. **The legacy `accounting.manage` permission is removed.** It only gated the mock controller this doc replaces; new endpoints use the granular codes in §3. V11 deletes its `role_permissions` rows and catalog row (no shim, per project rule).

---

## 2. Schema — migration `V11__finance_accounting_module.sql`

All tables: `tenant_id` + standard RLS (omitted). Money `numeric(14,2)`; rates `numeric(14,6)`.

### 2.1 `bank_accounts` (Bank Register)

```sql
create table bank_accounts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,                           -- BA-n
    bank_name varchar(160) not null,
    branch varchar(120),
    account_name varchar(160) not null,
    account_number varchar(30) not null,
    account_type varchar(20) not null check (account_type in ('CURRENT','COLLECTIONS','PAYROLL','SAVINGS','DOMICILIARY')),
    currency_code varchar(3) not null default 'NGN',     -- DOMICILIARY accounts carry a foreign currency
    sort_code_or_bic varchar(40),
    authorised_signatory varchar(160),
    relationship_manager varchar(160),
    date_opened date,
    opening_balance numeric(14,2) not null default 0,
    opening_balance_date date not null default current_date,
    current_balance numeric(14,2) not null default 0,    -- cache: = opening + Σ credits − Σ debits; written only with a bank_transactions insert
    location_id uuid references locations(id),          -- branch account (null = head office)
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','DORMANT')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index bank_accounts_number_uq on bank_accounts (tenant_id, account_number, lower(bank_name));
create index bank_accounts_name_trgm on bank_accounts using gin (lower(bank_name || ' ' || account_name) gin_trgm_ops);
```
Bank balances may go negative (overdraft is a real bank state); there is no `>= 0` check here, unlike cash.

### 2.2 `bank_transactions` (bank ledger)

```sql
create table bank_transactions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    bank_account_id uuid not null references bank_accounts(id),
    entry_type varchar(6) not null check (entry_type in ('CREDIT','DEBIT')),
    amount numeric(14,2) not null check (amount > 0),
    balance_after numeric(14,2) not null,
    reference varchar(40) not null,                      -- the source document ref: LDG-n, PCF-n, RC-n, RCT-n, BF-n, WW-n, MAN-…
    description varchar(300) not null,
    source_type varchar(30) not null check (source_type in (
        'OPENING','CASH_LODGEMENT','PETTY_CASH_FUNDING','RUNNING_COST','RECEIPT_CONFIRMED','RECEIPT_REVERSED',
        'FUND_DISBURSEMENT_OUT','FUND_DISBURSEMENT_IN','FUND_DISBURSEMENT_RETURN','PETTY_CASH_ALLOCATION_OUT',
        'PETTY_CASH_ALLOCATION_RETURN','WALLET_WITHDRAWAL','DEPOSIT_REFUND','COMMISSION_PAYOUT','MANUAL')),
    source_id uuid,
    expense_category_id uuid references expense_categories(id),   -- MANUAL bank charges
    entry_date date not null,
    created_by_user_id uuid references app_users(id),
    created_at timestamptz not null default now()
);
create index bank_tx_account_idx on bank_transactions (tenant_id, bank_account_id, entry_date, created_at);
create index bank_tx_source_idx on bank_transactions (tenant_id, source_type, source_id);
```
(`expense_categories` is created before this table in the migration; listed after for readability.)

### 2.3 `cash_accounts` and `cash_transactions` (cash ledger)

```sql
create table cash_accounts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(30) not null,                           -- e.g. 'TILL-IKJ-01', 'PC-IKJ', 'VAULT-HQ' (entered; unique)
    name varchar(120) not null,
    kind varchar(20) not null check (kind in ('TILL','PETTY_CASH','VAULT')),
    location_id uuid references locations(id),          -- null = head office
    currency_code varchar(3) not null default 'NGN',
    opening_balance numeric(14,2) not null default 0,
    current_balance numeric(14,2) not null default 0,
    float_limit numeric(14,2),                           -- PETTY_CASH imprest ceiling (optional)
    pos_terminal_ref varchar(40),                        -- TILL: card terminal id
    pos_settlement_bank_account_id uuid references bank_accounts(id),   -- TILL: where card settlements land
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code),
    constraint cash_accounts_non_negative check (current_balance >= 0)    -- you cannot have less than no cash
);

create table cash_transactions (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    cash_account_id uuid not null references cash_accounts(id),
    direction varchar(3) not null check (direction in ('IN','OUT')),
    amount numeric(14,2) not null check (amount > 0),
    balance_after numeric(14,2) not null check (balance_after >= 0),
    reference varchar(40) not null,
    remark varchar(300) not null,                         -- shown as "Remark" on the Cash Inflow/Outflow Statement
    source_type varchar(30) not null check (source_type in (
        'OPENING','RECEIPT','REFUND','CASH_LODGEMENT','PETTY_CASH_FUNDING','PETTY_CASH_PAYMENT','RUNNING_COST',
        'FUND_DISBURSEMENT_OUT','FUND_DISBURSEMENT_IN','FUND_DISBURSEMENT_RETURN',
        'PETTY_CASH_ALLOCATION_OUT','PETTY_CASH_ALLOCATION_IN','PETTY_CASH_ALLOCATION_RETURN',
        'WALLET_WITHDRAWAL','DEPOSIT_REFUND','COMMISSION_PAYOUT','CASH_COUNT_ADJUSTMENT')),
    source_id uuid,
    expense_category_id uuid references expense_categories(id),   -- payments: for "utilisation by category"
    entry_date date not null,
    created_by_user_id uuid references app_users(id),
    created_at timestamptz not null default now()
);
create index cash_tx_account_idx on cash_transactions (tenant_id, cash_account_id, entry_date, created_at);
create index cash_tx_source_idx on cash_transactions (tenant_id, source_type, source_id);
```

**Ledger write primitive** (`account.repository.MoneyLedgerRepository`, the Account analogue of `StockLedgerRepository`): `postBank(accountId, CREDIT|DEBIT, amount, …)` and `postCash(accountId, IN|OUT, amount, …)` lock the account row `FOR UPDATE`, compute `balance_after`, insert the entry, update `current_balance`. A cash `OUT` exceeding the balance throws `400 INSUFFICIENT_CASH` (details `{ account, available, requested }`) — the DB checks back it up. Dormant bank / inactive cash accounts reject new entries → `409 ACCOUNT_NOT_ACTIVE` ("Dormant accounts stay in historic statements but cannot receive new transactions"). Creating an account with a non-zero opening balance posts one `OPENING` entry.

### 2.4 `expense_categories` (Expenses / Payment List Guide)

```sql
create table expense_categories (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    code varchar(20) not null,                           -- EX-n unless supplied
    name varchar(160) not null,
    description varchar(300),
    account_head varchar(20) not null check (account_head in ('UTILITIES','LOGISTICS','MAINTENANCE','ADMINISTRATIVE','FINANCE')),
    monthly_cap numeric(14,2) check (monthly_cap is null or monthly_cap > 0),
    petty_cash_allowed boolean not null default true,
    status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','INACTIVE')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);
create unique index expense_categories_name_uq on expense_categories (tenant_id, lower(name));
```
Seeded per tenant at creation (the PRD's list): Electrical Bills (UTILITIES), Transportation (LOGISTICS), Loading Charges (LOGISTICS), Offloading Charges (LOGISTICS), Maintenance Expenses (MAINTENANCE), Fuel Expenses (LOGISTICS), Communication Expenses (ADMINISTRATIVE), Office Supplies (ADMINISTRATIVE), Other Operational Costs (ADMINISTRATIVE), Bank Charges (FINANCE, `petty_cash_allowed = false`).

### 2.5 Petty cash documents

```sql
create table petty_cash_fundings (                       -- Credit/Fund Petty Cash (5.3.9 = 5.3.12)
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- PCF-n
    petty_cash_account_id uuid not null references cash_accounts(id),   -- kind PETTY_CASH
    source_kind varchar(10) not null check (source_kind in ('BANK','CASH')),
    source_bank_account_id uuid references bank_accounts(id),
    source_cash_account_id uuid references cash_accounts(id),
    amount numeric(14,2) not null check (amount > 0),
    funding_date date not null,
    transfer_or_cheque_reference varchar(80),
    authorised_by varchar(160),
    description varchar(300),
    status varchar(20) not null default 'POSTED' check (status in ('POSTED','REVERSED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint pcf_source check ((source_kind = 'BANK') = (source_bank_account_id is not null) and (source_kind = 'CASH') = (source_cash_account_id is not null))
);

create table petty_cash_payments (                       -- Petty Cash Payment (5.3.13 "petty" mode)
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- PCP-n
    petty_cash_account_id uuid not null references cash_accounts(id),
    expense_category_id uuid not null references expense_categories(id),
    amount numeric(14,2) not null check (amount > 0),
    paid_to varchar(160) not null,
    payment_date date not null,
    approved_by varchar(160),                            -- as captured on the screen (verbal approval by a named person)
    description varchar(300),
    status varchar(20) not null default 'POSTED' check (status in ('POSTED','REVERSED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);

create table petty_cash_allocations (                    -- HQ → branch petty cash, accepted or rejected by the branch (5.3.14)
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- PPC-n
    source_kind varchar(10) not null check (source_kind in ('BANK','CASH')),
    source_bank_account_id uuid references bank_accounts(id),
    source_cash_account_id uuid references cash_accounts(id),
    destination_location_id uuid not null references locations(id),
    destination_petty_cash_account_id uuid not null references cash_accounts(id),
    amount numeric(14,2) not null check (amount > 0),
    purpose varchar(300) not null,
    status varchar(20) not null default 'PENDING' check (status in ('PENDING','ACCEPTED','REJECTED')),
    responded_by_user_id uuid references app_users(id),
    responded_at timestamptz,
    note varchar(300),                                   -- required on REJECTED
    sent_by_user_id uuid not null references app_users(id),
    sent_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
```

### 2.6 `operating_costs` (Payment / Running Cost)

```sql
create table operating_costs (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- RC-n (assigned at submission; drafts have none)
    location_id uuid references locations(id),
    expense_category_id uuid not null references expense_categories(id),
    payee varchar(160) not null,
    amount numeric(14,2) not null check (amount > 0),
    payment_method varchar(20) not null check (payment_method in ('PETTY_CASH','CASH','BANK_TRANSFER','CHEQUE')),
    paying_cash_account_id uuid references cash_accounts(id),      -- PETTY_CASH → a PETTY_CASH account; CASH → a TILL/VAULT
    paying_bank_account_id uuid references bank_accounts(id),      -- BANK_TRANSFER / CHEQUE
    cheque_number varchar(30),
    invoice_or_receipt_no varchar(80),
    input_vat_amount numeric(14,2) not null default 0 check (input_vat_amount >= 0 and input_vat_amount < amount),  -- VAT included in `amount`, per the supplier's VAT invoice (0 = none / not reclaimable)
    supplier_tin varchar(40),                            -- required when input_vat_amount > 0 (VAT claimed needs the supplier's TIN)
    payment_date date not null,
    description varchar(300),
    exceeds_monthly_cap boolean not null default false,  -- computed at submission, shown to the approver
    status varchar(20) not null default 'DRAFT' check (status in ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','REVERSED')),
    rejection_reason varchar(300),
    submitted_by_user_id uuid references app_users(id),
    submitted_at timestamptz,
    approved_by_user_id uuid references app_users(id),
    approved_at timestamptz,
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    constraint oc_paying_account check (
        (payment_method in ('PETTY_CASH','CASH') and paying_cash_account_id is not null and paying_bank_account_id is null) or
        (payment_method in ('BANK_TRANSFER','CHEQUE') and paying_bank_account_id is not null and paying_cash_account_id is null))
);
create unique index operating_costs_ref_uq on operating_costs (tenant_id, ref) where ref is not null;
create index operating_costs_status_idx on operating_costs (tenant_id, status, payment_date desc);
create index operating_costs_payee_trgm on operating_costs using gin (lower(payee) gin_trgm_ops);
```
`ref` is nullable only for `DRAFT` rows (a draft isn't a document yet — matches "Save draft").

### 2.7 `payment_receipts` (all money received)

```sql
create table payment_receipts (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- RCT-n
    kind varchar(10) not null default 'RECEIPT' check (kind in ('RECEIPT','REFUND')),
    location_id uuid references locations(id),
    cash_account_id uuid references cash_accounts(id),   -- the till (CASH channel, and the till a card was taken at)
    cashier_user_id uuid not null references app_users(id),
    customer_id uuid,                                    -- customers.id (cross-module plain uuid)
    customer_name varchar(200),                          -- snapshot ("Walk-in" when none)
    channel varchar(20) not null check (channel in ('CASH','POS','FUND_TRANSFER','CHEQUE','WALLET','DEPOSIT')),
    amount numeric(14,2) not null check (amount > 0),
    source_module varchar(30) not null,                  -- 'cashier','invoicing','account'
    source_type varchar(30) not null check (source_type in ('POS_SALE','INVOICE_PAYMENT','WALLET_FUNDING','DEPOSIT_FUNDING','SALE_REFUND','DEPOSIT_REFUND')),
    source_id uuid not null,
    source_ref varchar(40) not null,                     -- TXN-n / INV-n / WF-n
    receiving_bank_account_id uuid references bank_accounts(id),   -- FUND_TRANSFER/CHEQUE (and POS when the till has no settlement account)
    terminal_ref varchar(40),                            -- POS
    payer_reference varchar(80),                         -- transfer reference / cheque number / card auth code
    customer_ledger_entry_id uuid,                       -- WALLET/DEPOSIT: the customers ledger debit
    confirmation_status varchar(20) not null check (confirmation_status in ('NOT_REQUIRED','PENDING','APPROVED','DECLINED')),
    reviewed_by_user_id uuid references app_users(id),
    reviewed_at timestamptz,
    decline_note varchar(300),
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    status varchar(20) not null default 'ISSUED' check (status in ('ISSUED','VOIDED')),
    void_reason varchar(300),
    voided_by_user_id uuid references app_users(id),
    voided_at timestamptz,
    received_at timestamptz not null default now(),
    search_vector tsvector generated always as (to_tsvector('simple', ref || ' ' || source_ref || ' ' || coalesce(customer_name,'') || ' ' || coalesce(payer_reference,''))) stored,
    unique (tenant_id, ref)
);
create index pr_location_time_idx on payment_receipts (tenant_id, location_id, received_at desc);
create index pr_pending_idx on payment_receipts (tenant_id, location_id, received_at) where confirmation_status = 'PENDING';
create index pr_cashier_idx on payment_receipts (tenant_id, cashier_user_id, received_at);
create index pr_source_idx on payment_receipts (tenant_id, source_module, source_id);
create index pr_search_idx on payment_receipts using gin (search_vector);
```

**Channel rules** (applied by `MoneyLedger.recordReceipt`, §5.1):

| Channel | Confirmation | Ledger posting |
|---|---|---|
| `CASH` | `NOT_REQUIRED` | immediately: `cash_transactions IN` on `cash_account_id` (must be an active `TILL` or `VAULT` at the receipt's location) |
| `POS` (card) | `PENDING` | on approval: `bank CREDIT` on the till's `pos_settlement_bank_account_id` (or `receiving_bank_account_id`); nothing on decline |
| `FUND_TRANSFER` | `PENDING` | on approval: `bank CREDIT` on `receiving_bank_account_id` (required at receipt time) |
| `CHEQUE` | `PENDING` | on approval (cheque cleared): `bank CREDIT` on `receiving_bank_account_id` |
| `WALLET` / `DEPOSIT` | `NOT_REQUIRED` | none here — the caller has already debited the customer ledger and passes `customer_ledger_entry_id` |

`REFUND` receipts: `CASH` → `cash OUT` immediately; `FUND_TRANSFER` → `bank DEBIT` immediately (a refund is the company paying, so no inbound confirmation applies); `WALLET` → caller credits the wallet; others → `400 REFUND_CHANNEL_NOT_SUPPORTED`.

### 2.8 Funds Awaiting — `fund_disbursements`

```sql
create table fund_disbursements (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- BF-n
    channel varchar(20) not null check (channel in ('BANK_TRANSFER','CASH')),
    source_bank_account_id uuid references bank_accounts(id),
    source_cash_account_id uuid references cash_accounts(id),
    destination_location_id uuid not null references locations(id),     -- showroom or factory
    destination_bank_account_id uuid references bank_accounts(id),      -- BANK_TRANSFER
    destination_cash_account_id uuid references cash_accounts(id),      -- CASH (a VAULT or TILL at the destination)
    purpose varchar(300) not null,
    sent_amount numeric(14,2) not null check (sent_amount > 0),
    sent_by_user_id uuid not null references app_users(id),
    sent_at timestamptz not null default now(),
    received_amount numeric(14,2),
    shortfall_amount numeric(14,2),                      -- = sent − received (server-computed)
    shortfall_reason varchar(300),
    bank_credit_reference varchar(80),
    date_received date,
    confirmed_by_user_id uuid references app_users(id),
    confirmed_at timestamptz,
    status varchar(25) not null default 'AWAITING' check (status in ('AWAITING','CONFIRMED','PARTIALLY_CONFIRMED','REJECTED')),
    unique (tenant_id, ref),
    constraint fd_channel_accounts check (
        (channel = 'BANK_TRANSFER' and source_bank_account_id is not null and destination_bank_account_id is not null and source_cash_account_id is null and destination_cash_account_id is null) or
        (channel = 'CASH' and source_cash_account_id is not null and destination_cash_account_id is not null and source_bank_account_id is null and destination_bank_account_id is null)),
    constraint fd_received_le_sent check (received_amount is null or (received_amount >= 0 and received_amount <= sent_amount))
);
```

### 2.9 Cash lodgements (Cash Transfer History)

```sql
create table cash_lodgements (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- LDG-n
    location_id uuid not null references locations(id),
    cash_account_id uuid not null references cash_accounts(id),   -- till/vault the cash leaves
    deposit_date date not null,
    takings numeric(14,2) not null check (takings >= 0),          -- cash counted for the period
    banked numeric(14,2) not null check (banked > 0),
    retained numeric(14,2) not null check (retained >= 0),        -- float kept back
    bank_account_id uuid not null references bank_accounts(id),
    teller_reference varchar(60),
    deposited_by_user_id uuid not null references app_users(id),
    status varchar(25) not null default 'AWAITING_CONFIRMATION' check (status in ('AWAITING_CONFIRMATION','CONFIRMED','REJECTED')),
    confirmed_by_user_id uuid references app_users(id),
    confirmed_at timestamptz,
    rejection_reason varchar(300),
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref),
    constraint lodgement_balances check (takings = banked + retained)
);
```

### 2.10 Wallet & deposit money documents

```sql
create table wallet_fundings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- WF-n
    customer_id uuid not null,                           -- customers.id
    customer_name varchar(200) not null,
    balance_type varchar(10) not null check (balance_type in ('WALLET','DEPOSIT')),   -- DEPOSIT = special-customer deposit
    channel varchar(20) not null check (channel in ('CASH','POS','FUND_TRANSFER','CHEQUE')),
    amount numeric(14,2) not null check (amount > 0),
    location_id uuid references locations(id),
    payment_receipt_id uuid not null references payment_receipts(id),
    customer_ledger_entry_id uuid,                       -- set when the wallet is actually credited
    status varchar(25) not null check (status in ('AWAITING_CONFIRMATION','CONFIRMED','DECLINED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);

create table wallet_withdrawals (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- WW-n
    customer_id uuid not null,
    customer_name varchar(200) not null,
    balance_type varchar(10) not null check (balance_type in ('WALLET','DEPOSIT')),   -- DEPOSIT = deposit refund
    reason varchar(40) not null check (reason in ('DEALER_REQUEST','EXCESS_COMMISSION_RECOVERY','OVER_CREDIT_CORRECTION','DEPOSIT_REFUND')),
    initiated_by varchar(10) not null check (initiated_by in ('DEALER','COMPANY')),   -- History's Wallet Withdrawal report
    amount numeric(14,2) not null check (amount > 0),
    balance_before numeric(14,2) not null,
    balance_after numeric(14,2) not null check (balance_after >= 0),
    payout_kind varchar(10) not null check (payout_kind in ('CASH','BANK','NONE')),   -- NONE: recoveries/corrections pay nobody
    payout_cash_account_id uuid references cash_accounts(id),
    payout_bank_account_id uuid references bank_accounts(id),
    paid_via varchar(120),                               -- free text as on the screen (e.g. "Transfer to GTB 0123…")
    otp_challenge_id uuid references otp_challenges(id), -- required for DEALER_REQUEST when the OTP setting is on
    authorised_by_user_id uuid references app_users(id), -- required for COMPANY-initiated reasons
    note varchar(300),
    withdrawal_date date not null,
    location_id uuid references locations(id),
    customer_ledger_entry_id uuid not null,
    cash_transaction_id uuid references cash_transactions(id),
    bank_transaction_id uuid references bank_transactions(id),
    audit_status varchar(20) not null default 'COMPLETED' check (audit_status in ('COMPLETED','UNDER_REVIEW','REVERSED')),
    created_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
```

### 2.11 Exchange rates

```sql
create table exchange_rate_publications (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    ref varchar(20) not null,                            -- FX-n
    published_by_user_id uuid not null references app_users(id),
    published_at timestamptz not null default now(),
    unique (tenant_id, ref)
);
create table exchange_rates (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    publication_id uuid not null references exchange_rate_publications(id),
    currency_code varchar(3) not null,                   -- ISO 4217
    buy_rate numeric(14,6) not null check (buy_rate > 0),    -- NGN per 1 unit of currency
    sell_rate numeric(14,6) not null check (sell_rate > 0),
    published_at timestamptz not null,
    constraint fx_sell_ge_buy check (sell_rate >= buy_rate)
);
create index exchange_rates_lookup_idx on exchange_rates (tenant_id, currency_code, published_at desc);
```
Tracked currencies (code, name, symbol) are the module setting `accounting.fx.currencies` (default USD US Dollar $, GBP British Pound £, EUR Euro €, CNY Chinese Yuan ¥, ZAR South African Rand R, GHS Ghanaian Cedi ₵) — the "Currencies tracked" list; adding one is a settings change, not a schema change.

### 2.12 VAT Control per location (effective-dated)

```sql
create table location_vat_settings (
    id uuid primary key default gen_random_uuid(),
    tenant_id uuid not null references tenants(id),
    location_id uuid not null references locations(id),   -- SHOWROOM or FACTORY (not areas)
    vat_enabled boolean not null,
    vat_rate numeric(5,2) not null check (vat_rate >= 0 and vat_rate <= 100),
    vat_registration_number varchar(60),
    effective_from date not null,
    note varchar(300),
    updated_by_user_id uuid not null references app_users(id),
    created_at timestamptz not null default now(),
    unique (tenant_id, location_id, effective_from)
);
-- backfill from the current locations.vat_* values, effective from each location's created_at date
insert into location_vat_settings (tenant_id, location_id, vat_enabled, vat_rate, vat_registration_number, effective_from, updated_by_user_id)
select tenant_id, id, vat_enabled, vat_rate, vat_registration_number, created_at::date, created_by_user_id
from locations where type in ('SHOWROOM','FACTORY');
```
`locations.vat_rate`/`vat_enabled`/`vat_registration_number` remain as the **current** values (cache), refreshed when a setting becomes effective (immediately if `effective_from ≤ today`, else by a daily job at 00:05 tenant time).

### 2.13 Module settings registered

| Key | Type | Default |
|---|---|---|
| `accounting.fx.currencies` | object[] | the six above |
| `accounting.runningCost.allowSelfApprovalForAdmin` | boolean | `true` |
| `accounting.receipts.notifyPerReceiptChannels` | string[] | `["FUND_TRANSFER","CHEQUE"]` (POS card receipts are too frequent to notify one by one — see §8) |
| `customers.wallet.withdrawalOtpRequired` | boolean | `true` (registered by doc 03) |

---

## 3. Permission codes

`accounting.read` (V1) remains the read permission for statements and reports. `accounting.vat.manage` (V5) now also covers per-location VAT Control. `accounting.manage` is deleted (§1.3-7). New in V11:

| Code | Allows | Default roles |
|---|---|---|
| `accounting.bank.manage` | Register/edit bank accounts, change status, post manual bank entries | Accounts Personnel |
| `accounting.cash-accounts.manage` | Register tills, petty-cash boxes, vaults; cash-count adjustments | Accounts Personnel |
| `accounting.expense-categories.manage` | Expense/Payment List Guide | Accounts Personnel |
| `accounting.petty-cash.fund` | Credit/Fund Petty Cash; send petty cash to a branch | Accounts Personnel |
| `accounting.petty-cash.pay` | Petty cash payments | Accounts Personnel |
| `accounting.petty-cash.respond` | Accept/reject petty cash sent to the caller's location | Accounts Personnel |
| `accounting.running-costs.submit` | Create/submit running costs | Accounts Personnel |
| `accounting.running-costs.approve` | Approve/reject running costs | Management |
| `accounting.receipts.read` | Receipts, Receipt Track, POS/Transfer confirmation queue | Management, Accounts Personnel, Cashier (own) |
| `accounting.receipts.confirm` | Approve/decline POS, fund-transfer and cheque receipts | Accounts Personnel |
| `accounting.receipts.void` | Void a receipt | Accounts Personnel |
| `accounting.receipts.record` | Record other income (§4.18) | Accounts Personnel |
| `accounting.funds.send` | Disburse funds to a branch/factory | Accounts Personnel |
| `accounting.funds.confirm` | Confirm/reject funds received at the caller's location | Accounts Personnel |
| `accounting.lodgements.manage` | Record cash lodgements | Accounts Personnel, Cashier |
| `accounting.lodgements.confirm` | Confirm lodgements against the bank | Accounts Personnel |
| `accounting.wallet.fund` | Fund dealer wallets / special-customer deposits | Accounts Personnel, Cashier |
| `accounting.wallet.withdraw` | Dealer-requested withdrawals (OTP-gated) and deposit refunds | Accounts Personnel |
| `accounting.wallet.recover` | Company-initiated wallet recoveries/corrections | Management |
| `accounting.tenderable.read` | Tenderable | Management, Accounts Personnel |
| `accounting.fx.manage` | Publish exchange rates | Accounts Personnel |

"Cashier (own)": a Cashier holding `accounting.receipts.read` only sees receipts where `cashier_user_id` = themselves (PRD §7 "Cashier — ✓ own transactions"); Accounts and Management see all in their location scope.

Every location-bound endpoint goes through `LocationAccessService`. Head-office records (`location_id` null) are visible only to ALL_LOCATIONS users.

---

## 4. Endpoints (all under `/api/v1/account`)

### 4.1 Bank Register — `/bank-accounts`

- **`POST`** — perm `accounting.bank.manage`. Body `{ bankName, branch?, accountName, accountNumber (6–30 digits), accountType, currencyCode? (DOMICILIARY only, must be in tracked currencies), sortCodeOrBic?, authorisedSignatory?, relationshipManager?, dateOpened?, openingBalance? (default 0), openingBalanceDate?, locationId?, active? (default true) }`. Duplicate number at the same bank → `409 DUPLICATE_ACCOUNT_NUMBER`. Code `next("BA")`. Non-zero opening balance posts an `OPENING` entry. Emits `account.bank-account.registered`.
- **`GET`** — perm `accounting.read`. Unpaged. Query `accountType`, `status`, `locationId`, `q`. Row `{ id, code, bankName, accountName, accountNumberMasked ("•••• 4471" unless caller holds accounting.bank.manage), accountType, currencyCode, branch, signatory, dateOpened, currentBalance, status }`. Account numbers are shown in full only to `accounting.bank.manage` holders.
- **`PUT /{id}`** (all but `openingBalance*`, which is immutable once there are entries), **`PUT /{id}/status`** `{ status: ACTIVE|DORMANT }`.
- **`POST /{id}/manual-entries`** — perm `accounting.bank.manage`. `{ entryType: CREDIT|DEBIT, amount, entryDate, description, expenseCategoryId? (required for DEBIT — e.g. Bank Charges) }`. Reference `next("MAN")`. Emits `account.bank.manual-entry-posted`. (Bank charges, interest and reconciling items appear on real statements and the PRD's Bank Statement must match them.)

### 4.2 Bank Statement — `GET /bank-accounts/{id}/statement` [paged]

Perm `accounting.read`. Query `from`, `to` (required), `entryType`, `q` (reference CODE, description FULLTEXT). Response: `{ account: { code, bankName, accountNumberMasked, accountType, branch, signatory }, period: { from, to }, openingBalance, closingBalance, totalCredits, totalDebits, items: [ { date, reference, description, type ('Credit'|'Debit'), credit, debit, balance, sourceType } ], page… }`. Running balance is `balance_after` from the ledger (no recomputation).

### 4.3 Cash accounts — `/cash-accounts`

- **`POST`** — perm `accounting.cash-accounts.manage`. `{ code (unique, ≤30), name, kind, locationId?, currencyCode?, openingBalance?, floatLimit? (PETTY_CASH), posTerminalRef? (TILL), posSettlementBankAccountId? (TILL) }`. `locationId` must be a FACTORY/SHOWROOM (not an area). Emits `account.cash-account.registered`.
- **`GET`** — perm `accounting.read` (Cashiers also see their location's tills via `accounting.receipts.read`). Query `kind`, `locationId`, `status`. Row `{ id, code, name, kind, locationName, currentBalance, floatLimit, posTerminalRef, status }`.
- **`PUT /{id}`**, **`PUT /{id}/status`** (deactivating with a non-zero balance → `409 ACCOUNT_HAS_BALANCE`).
- **`POST /{id}/cash-counts`** — perm `accounting.cash-accounts.manage`. `{ countedAmount, countDate, note (required if variance ≠ 0) }` → posts `CASH_COUNT_ADJUSTMENT` IN/OUT for the variance, emits `account.cash.count-variance` when ≠ 0 (feeds Audit/AI anomaly detection).

### 4.4 Cash Inflow/Outflow Statement — `GET /cash-flow` [paged]

Perm `accounting.read`. Query: `locationId` (showroom or factory; null = company-wide, ALL_LOCATIONS only), `cashAccountId`, `kind` (`TILL`|`PETTY_CASH`|`VAULT`, repeatable; default all), `direction` (`IN`|`OUT`), `from`, `to`.
Response: `{ scope: { locationId, locationName, accounts: [ { code, name, kind } ] }, openingCash, totalInflow, totalOutflow, closingCash, items: [ { sn, date, reference, remark, inflow, outflow, balance, accountCode, sourceType } ] }` — the five columns the PRD names (Date, Remark, In-Flow, Out-Flow, Balance) plus reference/account for drill-down. Balance is the running total across the accounts in scope (opening = Σ account balances at `from`).

### 4.5 Expenses / Payment List Guide — `/expense-categories`

`POST` (perm `accounting.expense-categories.manage`; `{ code?, name, description?, accountHead, monthlyCap?, pettyCashAllowed, active? }`; `409 DUPLICATE_NAME` / `DUPLICATE_CODE`), `GET` (perm `accounting.read`; unpaged; `accountHead`, `status`, `pettyCashAllowed`; row adds `spentThisMonth` = approved running costs + petty cash payments this calendar month), `PUT /{id}`, `PUT /{id}/status` ("Inactive categories stay in historic reports but cannot be selected on new entries").

### 4.6 Fund / Credit Petty Cash — `/petty-cash/fundings`

- **`POST`** — perm `accounting.petty-cash.fund`. `{ pettyCashAccountId, sourceKind: BANK|CASH, sourceBankAccountId? | sourceCashAccountId?, amount, fundingDate, transferOrChequeReference?, authorisedBy?, description? }`. Rules: target is an ACTIVE `PETTY_CASH` account; `floatLimit` set and balance + amount > limit → `409 FLOAT_LIMIT_EXCEEDED`; cash source must have the cash. Posting: source `bank DEBIT` (source_type `PETTY_CASH_FUNDING`) or `cash OUT`; target `cash IN` — "Funding appears as a debit on the source bank account". Ref `next("PCF")`. Emits `account.petty-cash.funded`.
- **`GET`** [paged] — perm `accounting.read`. `pettyCashAccountId`, `locationId`, `from`, `to`. Row `{ date, ref, description, sourceName, fundedBy, amount }`. Header `{ openingBalance, fundedToDate, utilisedToDate, currentBalance }` for the selected account (the screen's aside).
- **`POST /{id}/reverse`** — perm `accounting.petty-cash.fund`. `{ reason }`. Only if the petty cash still holds the amount (`400 INSUFFICIENT_CASH`). Posts opposite entries.

### 4.7 Petty cash payments — `/petty-cash/payments`

- **`POST`** — perm `accounting.petty-cash.pay`. `{ pettyCashAccountId, expenseCategoryId, amount, paidTo, paymentDate, approvedBy?, description? }`. Rules (from `PettyCashPayment.jsx`/`RunningCost.jsx`): category ACTIVE → else `400 EXPENSE_CATEGORY_INACTIVE`; `petty_cash_allowed` → else `400 CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH` ("settle it by bank transfer or cheque instead"); amount ≤ balance → `400 INSUFFICIENT_CASH` ("petty cash must not be overdrawn"); month-to-date for the category + amount > `monthly_cap` → `409 MONTHLY_CAP_EXCEEDED` (a petty payment has no approval step, so the cap is enforced; Running Cost only flags it). Posting: `cash OUT` with `expense_category_id`. Ref `next("PCP")`. Emits `account.petty-cash.paid`.
- **`POST /{id}/reverse`** `{ reason }` — posts `cash IN`; status `REVERSED`.

**Petty Cash Payment History** — `GET /petty-cash/payments/history` [paged] — perm `accounting.read`. Union of petty cash payments and running costs approved with `PETTY_CASH`. Query `pettyCashAccountId`, `locationId`, `expenseCategoryId`, `approvedBy`, `from`, `to`, `q`. Row `{ sn, date, ref, description, category, paidTo, approvedBy, amount, source ('Petty cash payment'|'Running cost') }`. Header `{ paymentsMade, totalPaidOut, categoriesUsed, largestPayment }`.

**Petty Cash Statement** — `GET /petty-cash/statement` — perm `accounting.read`. Query `pettyCashAccountId` (or `locationId` → all petty cash accounts there), `from`, `to`. Response:
```json
{ "data": {
  "account": { "code": "PC-IKJ", "name": "Ikeja petty cash" },
  "ledger": [ { "date": "2026-10-01", "ref": "PCF-12", "description": "Top-up from GTB collections", "category": null,
                "direction": "Funding", "in": 150000, "out": 0, "balance": 162500 } ],
  "statement": {
    "funding": { "openingBalance": 12500, "entries": [ { "ref": "PCF-12", "date": "…", "amount": 150000 } ], "totalAvailable": 162500 },
    "utilisationByCategory": [ { "category": "Transportation", "count": 4, "amount": 38000 } ],
    "totalUtilised": 61200,
    "closingBalance": 101300,
    "utilisedPct": 37.7 } } }
```

### 4.8 Petty cash sent to a branch (Accept or Reject Pending) — `/petty-cash/allocations`

- **`POST`** — perm `accounting.petty-cash.fund`. `{ sourceKind, sourceBankAccountId?|sourceCashAccountId?, destinationPettyCashAccountId, amount, purpose }`. Destination must be a `PETTY_CASH` account at a location other than the source's. At send: source debited (`PETTY_CASH_ALLOCATION_OUT`), status `PENDING`. Ref `next("PPC")`. Notifies holders of `accounting.petty-cash.respond` at the destination location — PRD §8 "Petty cash sent to branch → Branch Account Officer → in-app → prompt to accept or reject".
- **`POST /{id}/accept`** — perm `accounting.petty-cash.respond`, `assertAccess(destination)`. From `PENDING` only → `409 ALREADY_DECIDED`. Credits the destination (`PETTY_CASH_ALLOCATION_IN`). Binary — no partial amount.
- **`POST /{id}/reject`** — same perm. `{ note }` (required). Returns the money to the source (`PETTY_CASH_ALLOCATION_RETURN`).
- **`GET`** [paged] — perm `accounting.read`. `status` (Pending|Accepted|Rejected tabs), `locationId`, `from`, `to`. Row `{ id, ref, date, branch, purpose, sentBy, amount, status, respondedBy, respondedOn, note }`. Header `{ awaitingCount, valuePending, acceptedValueThisPeriod, rejectedCount }`.

### 4.9 Payment / Running Cost — `/running-costs`

- **`POST`** — perm `accounting.running-costs.submit`. Body `{ locationId?, expenseCategoryId, payee, amount, paymentMethod, payingCashAccountId? | payingBankAccountId?, chequeNumber? (CHEQUE), invoiceOrReceiptNo?, paymentDate, description?, submit: boolean }`. `submit=false` → `DRAFT` (no ref, no checks beyond field validation — "Save draft"); `submit=true` → validations then `PENDING_APPROVAL` with `ref = next("RC")`:
  - category ACTIVE; `PETTY_CASH` method needs `petty_cash_allowed` → `400 CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH`;
  - paying account active and of the right kind (`PETTY_CASH` → PETTY_CASH account; `CASH` → TILL/VAULT) → `400 INVALID_PAYING_ACCOUNT`;
  - cash methods: amount ≤ current balance at submission → `400 INSUFFICIENT_CASH` (re-checked at approval);
  - `exceeds_monthly_cap` computed (month-to-date approved + pending + this > cap) — not blocking; shown to the approver.
  Notifies holders of `accounting.running-costs.approve` (location-scoped): "RC-112 ₦45,000 — Generator servicing awaiting approval".
- **`PUT /{id}`** — edit a `DRAFT` or `REJECTED` row (resubmitting a rejected one keeps its `ref` and clears the rejection) → `409 NOT_EDITABLE` otherwise.
- **`POST /{id}/submit`** — `DRAFT → PENDING_APPROVAL` (same checks).
- **`POST /{id}/approve`** — perm `accounting.running-costs.approve`. Approver ≠ submitter → else `409 SELF_APPROVAL_NOT_ALLOWED` (Administrator exempt when `accounting.runningCost.allowSelfApprovalForAdmin`). From `PENDING_APPROVAL` → `409 ALREADY_DECIDED`. Posts atomically: `PETTY_CASH`/`CASH` → `cash OUT` (with category); `BANK_TRANSFER`/`CHEQUE` → `bank DEBIT`. "Approved transactions automatically update both Petty Cash records and Cash Inflow/Outflow Statement" — they're the same ledger reads. Emits `account.running-cost.approved`; notifies the submitter.
- **`POST /{id}/reject`** — same perm. `{ rejectionReason }` (required). No posting. Notifies the submitter.
- **`POST /{id}/reverse`** — perm `accounting.running-costs.approve`. `{ reason }`. `APPROVED → REVERSED`, posts opposite entries.
- **`GET`** [paged] — perm `accounting.read`. `status`, `expenseCategoryId`, `paymentMethod`, `locationId`, `from`, `to`, `q` (ref/invoice no CODE, payee TRIGRAM, description FULLTEXT). Row `{ id, date, ref, description, category, payee, method, payingAccount, amount, status, exceedsMonthlyCap, submittedBy, approvedBy, rejectionReason }`.
- **`DELETE /{id}`** — `DRAFT` only (a draft is not a record; doc 03 §13).

### 4.10 Payment receipts and POS / Fund Transfer Confirmation — `/receipts`

Receipts are created through the spi by Cashier, Invoicing and this module (§4.12); there is no generic public create endpoint.

- **`GET`** [paged] — perm `accounting.receipts.read` ("own" scoping for Cashiers). Query `locationId`, `cashAccountId`, `cashierUserId`, `channel`, `kind`, `confirmationStatus`, `status`, `sourceType`, `customerId`, `from`, `to`, `q` (ref/source ref/payer reference CODE, customer name TRIGRAM). Row `{ id, ref, kind, receivedAt, locationName, till, cashier, customerName, channel, amount, sourceType, sourceRef, receivingBank, terminalRef, payerReference, confirmationStatus, reviewedBy, reviewedAt, declineNote, status }`.
- **Confirmation queue** = `GET /receipts?confirmationStatus=PENDING&channel=POS,FUND_TRANSFER,CHEQUE` (the screen's Pending tab; Approved/Declined tabs filter by those statuses). Header from `GET /receipts/confirmation-stats?locationId=` → `{ pendingCount, pendingValue, approvedToday, declinedToday }`.
- **`POST /{id}/approve`** — perm `accounting.receipts.confirm`, location-scoped. From `PENDING` → `409 ALREADY_DECIDED`. Posts the bank credit per §2.7; for a `WALLET_FUNDING`/`DEPOSIT_FUNDING` receipt also confirms the linked funding (credits the wallet, §4.12). Emits `account.receipt.approved`.
- **`POST /{id}/decline`** — same perm. `{ note }` required ("No matching settlement received"). No posting. If the source is a wallet/deposit funding, the funding becomes `DECLINED` (wallet never credited). Emits `account.receipt.declined` (Invoicing recomputes invoice status from it — doc 08 §4.6), notifies the receipt's cashier and holders of `invoicing.receivables.read` at the location.
- **`POST /{id}/void`** — perm `accounting.receipts.void`. `{ reason }`. Only `kind RECEIPT` with `source_type WALLET_FUNDING|DEPOSIT_FUNDING` may be voided here (sales and invoice payments are voided in their own modules, which call `MoneyLedger.voidReceipt`). Reverses whatever was posted (cash OUT / bank DEBIT `RECEIPT_REVERSED`) and the wallet credit (fails if already spent → `400 INSUFFICIENT_WALLET_BALANCE`).
- **`GET /{id}/receipt.pdf`** — perm `accounting.receipts.read`. Printable receipt (company header from `settings::spi`, receipt no., date, till, cashier, customer, channel, amount, source ref, "PAID"/"PENDING CONFIRMATION"/"VOID" stamp; "DUPLICATE COPY" after the first print). Registered as `doc_type RECEIPT` in the document registry when issued.

### 4.11 Tenderable — `/tenderable/*`

All perm `accounting.tenderable.read`, location-scoped, query `locationId`, `from`, `to` (+ filters noted). Built entirely from `payment_receipts` (+ Customers' ledger for wallet funds).

- **`GET /tenderable/payable`** — `cashierUserId`. One row per cashier per day: `{ date, cashier, ftf, cash, pos, wallet, deposit, refund, total (= ftf + cash + pos + wallet + deposit − refund), pendingConfirmation }`. Declined receipts are excluded from `total` and listed in `pendingConfirmation`/a `declined` column so a cashier can't be credited with money that never arrived.
- **`GET /tenderable/receipts`** [paged] — "Receipt Track": `cashierUserId`, `channel`. Row `{ receipt, date, cashier, customer, channel, amount, status ('Issued'|'Voided'), confirmationStatus }`.
- **`GET /tenderable/wallet-funds`** [paged] — `customerId`. Rows from `wallet_fundings` **plus** Customers' `RETURN_CREDIT` entries for dealers (`CustomerQueries.ledgerEntries`, doc 04 §5.6): `{ ref, date, dealer, method ('Fund transfer'|'Cash deposit'|'POS'|'Cheque'|'Return credit'), bank, amount, confirmedBy, status ('Confirmed'|'Awaiting confirmation'|'Declined') }`.
- **`GET /tenderable/general-report`** — per cashier for the period: `{ cashier, daysActive, ftf, cash, pos, wallet, deposit, refund, total }` + grand totals.

### 4.12 Wallet / deposit funding — `/wallet-fundings`

- **`POST`** — perm `accounting.wallet.fund`. `{ customerId, balanceType: WALLET|DEPOSIT, channel: CASH|POS|FUND_TRANSFER|CHEQUE, amount, locationId, cashAccountId? (CASH, POS), receivingBankAccountId? (FUND_TRANSFER, CHEQUE), terminalRef?, payerReference? }`. Rules via `CustomerDirectory`: `WALLET` funding only for `AUTHORISED_DEALER`, `DEPOSIT` only for `SPECIAL_CUSTOMER` → `400 BALANCE_TYPE_NOT_ALLOWED` (doc 04 §1.3). Creates the `payment_receipts` row (`source_type WALLET_FUNDING|DEPOSIT_FUNDING`) and the funding (`ref next("WF")`). `CASH` → confirmed now: cash IN + `WalletLedger.credit(FUNDING, ref WF-n)`, status `CONFIRMED`. Other channels → `AWAITING_CONFIRMATION`; the wallet is credited only when Accounts approves the receipt (§4.10) — the dealer can't spend money that hasn't been verified (PRD rule 7). Emits `account.wallet-funding.created` / `.confirmed` / `.declined`.
- **`GET`** [paged] — perm `accounting.read`. `customerId`, `status`, `channel`, `from`, `to`.

### 4.13 Wallet withdrawal and deposit refund — `/wallet-withdrawals`

- **`POST`** — perm `accounting.wallet.withdraw` (dealer request, deposit refund) or `accounting.wallet.recover` (company-initiated reasons).
```json
{ "customerId": "…", "balanceType": "WALLET", "reason": "DEALER_REQUEST",
  "amount": 120000, "payoutKind": "BANK", "payoutBankAccountId": "…", "payoutCashAccountId": null,
  "paidVia": "Transfer to Access 0123456789", "otpChallengeId": "…", "authorisedByUserId": null,
  "withdrawalDate": "2026-10-08", "locationId": "…", "note": "Dealer request by phone" }
```
Rules:
  - `reason` vs initiator: `DEALER_REQUEST` and `DEPOSIT_REFUND` → `initiated_by DEALER`, need `accounting.wallet.withdraw`, a payout (`CASH`|`BANK`, money leaves); `EXCESS_COMMISSION_RECOVERY`, `OVER_CREDIT_CORRECTION` → `initiated_by COMPANY`, need `accounting.wallet.recover` and `authorisedByUserId` (a member holding `accounting.wallet.recover`, may be the caller), `payoutKind NONE` (nobody is paid; the balance is clawed back).
  - The screen's fourth reason, "settlement against an outstanding invoice", is **not** a withdrawal: it is an invoice payment by wallet and goes through `POST /api/v1/invoicing/invoices/{id}/payments` with `method WALLET` (doc 08 §4.6). This endpoint rejects it → `400 USE_INVOICE_PAYMENT`, so the frontend routes that option there.
  - **OTP** (PRD §6 rule 8; `customers.wallet.withdrawalOtpRequired`): `DEALER_REQUEST` requires `otpChallengeId` such that `OtpChallengeService.isVerified(id, "WALLET_WITHDRAWAL")` (requested by the caller, status `USED`) **and** the challenge's `target_reference` equals the `customerId` and its `context.amount` equals `amount` (a code issued for ₦50,000 can't authorise ₦500,000) → else `403 OTP_REQUIRED` / `403 OTP_MISMATCH`. The caller requests the challenge beforehand via `POST /api/v1/otp/challenges { purpose: WALLET_WITHDRAWAL, targetReference: <customerId>, context: { amount, targetLabel: "Okafor & Sons (DLR-42)" } }` (platform, shipped + doc 03 §9.4); the Admin reviews it in the OTP console and issues the code.
  - `WalletLedger.debit(WITHDRAWAL_DEBIT, …)` — balance can't go negative (`400 INSUFFICIENT_WALLET_BALANCE`, PRD rule 5); `DEPOSIT_REFUND` debits `DEPOSIT`.
  - Payout: `CASH` → `cash OUT` on `payoutCashAccountId` (`400 INSUFFICIENT_CASH`); `BANK` → `bank DEBIT` on `payoutBankAccountId`.
  - Snapshots `balance_before`/`balance_after`; ref `next("WW")`; emits `account.wallet.withdrawn` (summary includes `initiated_by` and whether OTP-verified).
- **`GET`** [paged] — perm `accounting.read`. `customerId`, `reason`, `initiatedBy`, `auditStatus`, `from`, `to`. Row `{ ref, date, customer, initiatedBy, reason, balanceBefore, amount, balanceAfter, payoutKind, paidVia, otp ('Verified'|'Not required'), processedBy, auditStatus }` — the same data Audit's Wallet Withdrawal History reads (doc 10).
- **`PUT /{id}/audit-status`** — perm `accounting.wallet.recover`. `{ auditStatus: UNDER_REVIEW|COMPLETED }` (flag for investigation). `REVERSED` is set only by **`POST /{id}/reverse`** `{ reason }` (same perm): credits the wallet back (`REVERSAL_CREDIT`) and reverses the payout posting.

### 4.14 Funds Awaiting — `/fund-disbursements`

- **`POST`** — perm `accounting.funds.send`. `{ channel, sourceBankAccountId?|sourceCashAccountId?, destinationLocationId (SHOWROOM|FACTORY), destinationBankAccountId?|destinationCashAccountId?, purpose, sentAmount }`. Accounts must match the channel (`fd_channel_accounts`); the destination account must belong to the destination location (`400 INVALID_DESTINATION_ACCOUNT`). Source debited now (`FUND_DISBURSEMENT_OUT`). Ref `next("BF")`. Notifies holders of `accounting.funds.confirm` at the destination — PRD §8 "Funds disbursed to showroom → Receiving Account Officer → in-app → confirm and acknowledge receipt".
- **`POST /{id}/confirm`** — perm `accounting.funds.confirm`, `assertAccess(destination)`. `{ receivedAmount, dateReceived, bankCreditReference?, shortfallReason? }`. `receivedAmount > sent` → `400 RECEIVED_EXCEEDS_SENT` ("Cannot exceed the amount sent"); `receivedAmount < sent` requires `shortfallReason` → `400 SHORTFALL_REASON_REQUIRED`. Credits the destination with `receivedAmount` (`FUND_DISBURSEMENT_IN`); `shortfall_amount = sent − received` computed server-side; status `CONFIRMED` (no shortfall) or `PARTIALLY_CONFIRMED`. A shortfall is money that left the source but didn't arrive — it is **not** silently re-credited anywhere; it emits `account.funds.shortfall` (summary with amount and reason), notifies holders of `accounting.funds.send` and Management, and Audit & Compliance can raise a violation from it (doc 10).
- **`POST /{id}/reject`** — same perm. `{ reason }`. Full rejection (nothing arrived / sent in error): returns the money to the source (`FUND_DISBURSEMENT_RETURN`), status `REJECTED`.
- **`GET`** [paged] — perm `accounting.read`. `locationId` (destination), `status`, `channel`, `from`, `to`. Row `{ id, ref, date, branch, purpose, channel, sentBy, sent, received, shortfall, status, confirmedBy, dateReceived }`. "Awaiting" cards = `status=AWAITING` for the caller's location.

### 4.15 Cash lodgements (Cash Transfer History) — `/cash-lodgements`

- **`POST`** — perm `accounting.lodgements.manage`. `{ locationId, cashAccountId, depositDate, takings, banked, bankAccountId, tellerReference? }`; `retained = takings − banked` computed (≥ 0 → else `400 BANKED_EXCEEDS_TAKINGS`). `banked` ≤ cash account balance → `400 INSUFFICIENT_CASH`. Status `AWAITING_CONFIRMATION`; the cash leaves the till now (`cash OUT CASH_LODGEMENT`, remark "Cash lodgement to <bank> — LDG-n") because it has physically gone to the bank. Ref `next("LDG")`.
- **`POST /{id}/confirm`** — perm `accounting.lodgements.confirm`. Posts `bank CREDIT CASH_LODGEMENT` for `banked`. "Every confirmed deposit appears as a credit on the corresponding bank statement and as an out-flow on the showroom's Cash Inflow/Outflow Statement."
- **`POST /{id}/reject`** — `{ reason }` (the bank never received it) → status `REJECTED`, the cash is **not** put back automatically (it physically left); emits `account.lodgement.rejected`, notifies Management — this is a cash-loss investigation, not a reversal.
- **`GET`** [paged] — perm `accounting.read`. `locationId`, `bankAccountId`, `status`, `from`, `to`. Row `{ date, ref, showroom, takings, banked, retained, bank, account (masked), depositedBy, teller, status, confirmedBy }`.

### 4.16 Exchange rates — `/exchange-rates`

- **`GET`** — perm `accounting.read` (also readable by holders of `invoicing.invoice.read`, `cashier.sales.read`). One row per tracked currency: `{ code, currency, symbol, buy, sell, spread (sell − buy), previousBuy, move (buy − previousBuy), movePct, updatedAt, updatedBy, freshness ('Current' if published today in tenant time, else 'Stale') }`. Currencies never published show nulls.
- **`POST /publish`** — perm `accounting.fx.manage`. `{ rates: [ { currencyCode, buyRate, sellRate } ] }` (any subset of tracked currencies; untracked → `400 CURRENCY_NOT_TRACKED`; `sell < buy` → `400 INVALID_RATE`). One `FX-n` publication, one row per currency, all stamped with the same `published_at`. Emits `account.fx.published`.
- **`GET /history?currencyCode=&from=&to=`** [paged].

### 4.17 VAT Control per location — extends shipped `/vat-control`

- **`GET /vat-control/locations`** — perm `accounting.read`. Row per showroom/factory: `{ locationId, code, name, type, registration, rate, enabled, effectiveFrom, updatedBy, scheduled: { rate, enabled, effectiveFrom } | null }`. Header: count of locations with collection disabled (the screen's warning callout).
- **`POST /vat-control/locations/{locationId}`** — perm `accounting.vat.manage`. `{ vatEnabled, vatRate, vatRegistrationNumber?, effectiveFrom (≥ today), note? }`. Duplicate date → `409 VAT_SETTING_EXISTS_FOR_DATE`. Applies to `locations.*` cache immediately when effective today. Emits `account.vat.location-updated`. "VAT is calculated and applied at the rate configured here for the relevant showroom. A showroom with collection disabled charges no output VAT and reports zero on its VAT report."
- **`GET /vat-control/locations/{locationId}/history`**.

### 4.18 Other income — `/other-income`

Money received that is not a sale, an invoice payment or a wallet funding — by-product and scrap sales, rent, interest received, sundry income. It is needed by the SCI's "By-product sales" / "Other income" lines (doc 13 §4.2).
- `payment_receipts.source_type` gains `'OTHER_INCOME'`, and the table gains `income_category varchar(20) check (income_category is null or income_category in ('BY_PRODUCT_SALES','SCRAP_SALES','RENTAL','INTEREST','OTHER'))`, required when `source_type = 'OTHER_INCOME'`.
- **`POST`** — perm `accounting.receipts.record`. `{ locationId, incomeCategory, channel (CASH|FUND_TRANSFER|CHEQUE|POS), amount, cashAccountId?, receivingBankAccountId?, payerName, payerReference?, description (required), receivedDate }`. It runs `MoneyLedger.recordReceipt` with `source_module account`, `source_type OTHER_INCOME`, `source_id` = the receipt's own id, and `source_ref` = its RCT ref. Channel confirmation rules apply as for any receipt (§2.7). Emits `account.other-income.recorded`.
- **`GET`** [paged] — perm `accounting.read`. Query `locationId`, `incomeCategory`, `from`, `to`. Voiding uses `POST /receipts/{id}/void` (now also allowed for `OTHER_INCOME`).
- `AccountQueries.otherIncome(filter)` returns `{ date, ref, locationId, category, amount, confirmationStatus }` (doc 13 counts APPROVED / NOT_REQUIRED only).

---

## 5. Exposed to other modules — `com.zifiq.account.spi`

All run in the caller's transaction.

### 5.1 `MoneyLedger`
```kotlin
fun recordReceipt(req: ReceiptRequest): ReceiptRef      // creates payment_receipts + applies §2.7 channel rules; registers doc RECEIPT
fun recordRefund(req: RefundRequest): ReceiptRef
fun voidReceipt(receiptId: UUID, reason: String, sourceModule: String)   // only by the module that created it
data class ReceiptRequest(
    val locationId: UUID, val cashAccountId: UUID?, val customerId: UUID?, val customerName: String?,
    val channel: Channel, val amount: BigDecimal, val sourceModule: String, val sourceType: String,
    val sourceId: UUID, val sourceRef: String, val receivingBankAccountId: UUID?, val terminalRef: String?,
    val payerReference: String?, val customerLedgerEntryId: UUID?)
data class ReceiptRef(val id: UUID, val ref: String, val channel: Channel, val amount: BigDecimal, val confirmationStatus: ConfirmationStatus)
```
Validation inside: `CASH` needs an ACTIVE TILL/VAULT at `locationId` (`400 INVALID_PAYING_ACCOUNT`); `FUND_TRANSFER`/`CHEQUE` need `receivingBankAccountId`; `POS` needs a till with a settlement account or `receivingBankAccountId`; `WALLET`/`DEPOSIT` need `customerLedgerEntryId`.

### 5.2 `Receipts` (read)
```kotlin
fun bySource(sourceModule: String, sourceIds: Collection<UUID>): List<ReceiptView>   // Invoicing: paid = Σ non-DECLINED, non-VOIDED
fun get(id: UUID): ReceiptView
```

### 5.3 `CashAccounts`, `BankAccounts` (read)
`tillsAt(locationId)`, `get(id)`, `activeBankAccounts()`, `netCashBetween(cashAccountId, from: Instant, to: Instant): CashMovementSummary` — `{ cashIn, cashOut, net }` from `cash_transactions` on that account in the window (Sales & POS uses it to compute a till session's expected cash, doc 09 §4.1).

### 5.4 `VatRates`
```kotlin
fun forLocation(locationId: UUID, onDate: LocalDate): VatSetting   // resolves an area to its root; enabled=false → rate treated as 0
data class VatSetting(val locationId: UUID, val enabled: Boolean, val rate: BigDecimal, val registrationNumber: String?, val effectiveFrom: LocalDate)
```

### 5.5 `ExchangeRates`
`current(currencyCode): RateRef?`, `asOf(currencyCode, instant): RateRef?` — `RateRef(id, buy, sell, publishedAt)`; callers store `id` + the rate values they used.

### 5.6 `CommissionPayouts` (for doc 12)
```kotlin
fun payOut(req: CommissionPayoutRequest): PayoutRef
data class CommissionPayoutRequest(
    val payeeType: PayeeType,            // DEALER (a customers.id) | CORRESPONDENT (a correspondents.id)
    val payeeId: UUID, val payeeName: String, val amount: BigDecimal,
    val method: PayoutMethod,            // WALLET (DEALER only) | CASH | BANK
    val cashAccountId: UUID?, val bankAccountId: UUID?, val paidVia: String?,
    val sourceRef: String,               // CP-n
    val locationId: UUID?)
```
WALLET → `WalletLedger.credit(COMMISSION_CREDIT)` (a correspondent payee → `400 WALLET_NOT_AVAILABLE_FOR_PAYEE`); CASH → cash OUT on `cashAccountId` / BANK → bank DEBIT on `bankAccountId`, both with source `COMMISSION_PAYOUT` (insufficient cash → `409 INSUFFICIENT_CASH`). Returns `{ cashTransactionId?, bankTransactionId?, customerLedgerEntryId? }`.

### 5.7 `AccountQueries` (read-only for doc 10/13/16)
`expenses(filter)` (approved running costs + petty cash payments, with category/payee/method/approver/status), `inputVat(filter)` (approved, non-reversed running costs with `input_vat_amount > 0`: `{ date, ref, locationId, payee, supplierTin, description, net = amount − input_vat_amount, vat }` — Audit & Compliance VAT reports, doc 10 §4.6), `cashLedger(filter)`, `bankLedger(filter)`, `withdrawals(filter)`, `receipts(filter)`, `fundDisbursements(filter)`.

---

## 6. Business rules

| # | Rule | Source | Where |
|---|---|---|---|
| A1 | Every financial entry is reflected consistently across statements | PRD §5.3 (all features) | §1.3-1 — statements read the same ledgers |
| A2 | POS, fund-transfer, cheque and petty-cash transactions are confirmed by Accounts before they are final | PRD §6 rule 7 | §4.10, §4.8, §4.12 |
| A3 | Running costs are registered and verified before processing; only approved ones move balances | PRD 5.3.5 | §4.9 |
| A4 | Approver ≠ submitter | derived from A3 (verification by *another* person) | §4.9 |
| A5 | Petty cash is never overdrawn; cash never goes negative | PRD 5.3.13 | `MoneyLedgerRepository` + DB checks |
| A6 | Wallets never go negative | PRD §6 rule 5 | `WalletLedger` (doc 04) |
| A7 | Dealer-requested withdrawals need an Admin-issued OTP matching the customer and amount | PRD §6 rule 8; `PettyCashPayment.jsx` note | §4.13 |
| A8 | Company-initiated recoveries are Management-authorised instead of OTP | `PettyCashPayment.jsx` | §4.13 |
| A9 | Categories not payable from petty cash can't be paid from petty cash; inactive categories can't be used | `ExpenseGuide.jsx`, `RunningCost.jsx` | §4.7, §4.9 |
| A10 | Dormant bank accounts stay in history but take no new transactions | `BankRegisterScreen.jsx` | `MoneyLedgerRepository` |
| A11 | Received funds can't exceed what was sent; a shortfall needs a reason | `FundsAwaitingBranch.jsx` | §4.14 |
| A12 | Takings = banked + retained; confirmed lodgements credit the bank and are an outflow of the till | `CashTransferHistory.jsx` | §4.15 |
| A13 | Exchange rates are stamped onto transactions at the time of use; history keeps the old rate | `ExchangeRate.jsx` footnote | §2.11, §5.5 |
| A14 | VAT per location rate/enablement; disabled = zero output VAT | PRD §6 rule 9; `VATControl.jsx` | §4.17, `VatRates` |
| A15 | Records are never deleted; mistakes are reversed | doc 03 §13 | reverse endpoints |
| A16 | Cashiers see only their own transactions | PRD §7 | §3 "own" scoping |

## 7. Errors added

`DUPLICATE_ACCOUNT_NUMBER` 409 · `ACCOUNT_NOT_ACTIVE` 409 · `ACCOUNT_HAS_BALANCE` 409 · `INSUFFICIENT_CASH` 400 · `FLOAT_LIMIT_EXCEEDED` 409 · `EXPENSE_CATEGORY_INACTIVE` 400 · `CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH` 400 · `MONTHLY_CAP_EXCEEDED` 409 · `INVALID_PAYING_ACCOUNT` 400 · `NOT_EDITABLE` 409 · `SELF_APPROVAL_NOT_ALLOWED` 409 · `REFUND_CHANNEL_NOT_SUPPORTED` 400 · `USE_INVOICE_PAYMENT` 400 · `OTP_MISMATCH` 403 · `RECEIVED_EXCEEDS_SENT` 400 · `SHORTFALL_REASON_REQUIRED` 400 · `INVALID_DESTINATION_ACCOUNT` 400 · `BANKED_EXCEEDS_TAKINGS` 400 · `CURRENCY_NOT_TRACKED` 400 · `INVALID_RATE` 400 · `VAT_SETTING_EXISTS_FOR_DATE` 409 · `BANK_ACCOUNT_NOT_FOUND` / `CASH_ACCOUNT_NOT_FOUND` / `RECEIPT_NOT_FOUND` / `RUNNING_COST_NOT_FOUND` / `DISBURSEMENT_NOT_FOUND` / `LODGEMENT_NOT_FOUND` / `ALLOCATION_NOT_FOUND` / `WITHDRAWAL_NOT_FOUND` 404. Reused: `ALREADY_DECIDED`, `OTP_REQUIRED`, `INSUFFICIENT_WALLET_BALANCE`, `BALANCE_TYPE_NOT_ALLOWED`, `DUPLICATE_NAME`.

## 8. Event catalog & notifications (PRD §8 mapping)

| Event | Notifies |
|---|---|
| `account.bank-account.registered` / `.status-changed`, `account.bank.manual-entry-posted` | — |
| `account.cash-account.registered`, `account.cash.count-variance` | count variance → in-app Management |
| `account.petty-cash.funded` / `.paid` / `.reversed` | — |
| `account.petty-cash.allocation-sent` | **PRD §8 "Petty cash sent to branch"** → in-app holders of `accounting.petty-cash.respond` at destination |
| `account.petty-cash.allocation-accepted` / `-rejected` | in-app the sender |
| `account.running-cost.submitted` | in-app holders of `accounting.running-costs.approve` (location scope) |
| `account.running-cost.approved` / `.rejected` / `.reversed` | in-app the submitter |
| `account.receipt.recorded` | channels in `accounting.receipts.notifyPerReceiptChannels` → **PRD §8 "POS / Fund Transfer transaction submitted → Accounts Department"** in-app holders of `accounting.receipts.confirm` at the location; POS card receipts are surfaced as a count on the Dashboard's pending actions (doc 14) instead of one notification per sale |
| `account.receipt.approved` | — |
| `account.receipt.declined` | in-app the receipt's cashier + holders of `invoicing.receivables.read` at the location |
| `account.receipt.voided` | — |
| `account.funds.disbursed` | **PRD §8 "Funds disbursed to showroom"** → in-app holders of `accounting.funds.confirm` at destination |
| `account.funds.confirmed` / `.rejected` | in-app the sender |
| `account.funds.shortfall` | in-app sender + Management |
| `account.lodgement.recorded` / `.confirmed` | — |
| `account.lodgement.rejected` | in-app Management |
| `account.wallet-funding.created` / `.confirmed` / `.declined` | declined → in-app the creator |
| `account.wallet.withdrawn` / `.withdrawal-reversed` / `.withdrawal-flagged` | flagged → in-app Management |
| `account.fx.published` | — |
| `account.vat.location-updated` | in-app holders of `accounting.read` at that location |

## 9. Search & filters

Implements doc 03 §4–§5: advanced `POST …/search` bodies, `/filters` metadata, facets, `highlight`, saved views, and identical-filter exports on every paged list. Ledgers (bank statement, cash flow, receipts) use **keyset paging** (`after=`) so long statements stay fast. Account numbers are searchable only by holders of `accounting.bank.manage` (others see them masked, so matching on them would leak digits). Searching on a masked value matches its last 4 digits only.

| Endpoint · list_key | `q` fields | Filters / qualifiers | Facets | Sort (default first) | Summary |
|---|---|---|---|---|---|
| `GET /bank-accounts` (unpaged) · `account.banks` | `code` REFERENCE, `bank_name`/`account_name`/`branch` TRIGRAM, account number last-4 CODE | `accountType` multi, `status`, `locationId`, `currencyCode`, `currentBalance` range | `accountType`, `bank` | `bankName`; `currentBalance`, `dateOpened` | `{ accounts, totalBalance by currency }` |
| `GET /bank-accounts/{id}/statement` [paged, keyset] · `account.bank-statement` | `reference` REFERENCE, `description` FULLTEXT, `amount` AMOUNT, counterparty TRIGRAM | `from`/`to`/`period` (required), `entryType`, `sourceType` multi, `amount` range, `expenseCategoryId` | `sourceType`, `entryType` | fixed chronological | `{ openingBalance, closingBalance, totalCredits, totalDebits }` |
| `GET /cash-accounts` (unpaged) · `account.cash-accounts` | `code` CODE, `name` TRIGRAM, `pos_terminal_ref` CODE | `kind` multi, `locationId`, `status`, `belowFloat` | `kind` | `code` | `{ total by kind }` |
| `GET /cash-flow` [paged, keyset] · `account.cash-flow` | `reference` REFERENCE, `remark` FULLTEXT, `amount` AMOUNT | `locationId`, `cashAccountId` multi, `kind` multi, `direction`, `sourceType` multi, `expenseCategoryId`, `amount` range, `from`/`to`/`period` | `sourceType`, `account` | chronological | `{ openingCash, totalInflow, totalOutflow, closingCash }` |
| `GET /expense-categories` (unpaged) · `account.expense-categories` | `code` CODE, `name` TRIGRAM, `description` FULLTEXT | `accountHead`, `status`, `pettyCashAllowed`, `overCap` (spent this month > cap) | `accountHead` | `name`; `spentThisMonth` | — |
| `GET /petty-cash/fundings` [paged] · `account.petty-fundings` | `ref` REFERENCE, `transferOrChequeReference` CODE, `description` FULLTEXT, `amount` AMOUNT | `pettyCashAccountId`, `locationId`, `sourceKind`, `fundedBy`, `amount` range, `from`/`to` | `sourceKind` | `date desc` | aside header |
| `GET /petty-cash/payments/history` [paged] · `account.petty-payments` | `ref` REFERENCE, `paidTo` TRIGRAM, `description` FULLTEXT, `amount` AMOUNT | `pettyCashAccountId`, `locationId`, `expenseCategoryId` multi, `approvedBy`, `source`, `status`, `amount` range, `from`/`to` | `category`, `source` | `date desc`; `amount` | `{ paymentsMade, totalPaidOut, categoriesUsed, largestPayment }` |
| `GET /petty-cash/allocations` [paged] · `account.petty-allocations` | `ref` REFERENCE, `purpose` FULLTEXT, branch name TRIGRAM | `status` (tabs), `locationId` (source or destination: `direction=SENT|RECEIVED`), `sentBy`, `respondedBy`, `amount` range, `from`/`to` | `status`, `branch` | `date desc` | `{ awaitingCount, valuePending, acceptedValueThisPeriod, rejectedCount }` |
| `GET /running-costs` [paged] · `account.running-costs` | `ref` REFERENCE, `invoice_or_receipt_no` CODE, `cheque_number` CODE, `payee` TRIGRAM, `description` FULLTEXT, `amount` AMOUNT, `supplier_tin` CODE | `status` multi, `expenseCategoryId` multi, `paymentMethod` multi, `locationId`, `payingAccountId`, `submittedBy`, `approvedBy`, `exceedsMonthlyCap`, `hasInputVat`, `amount` range, `paymentDate` range, `submittedAt` range, `awaitingMyApproval` (pending and not submitted by me) | `status`, `category`, `method` | `paymentDate desc`; `amount`, `submittedAt` | `{ count, total, pendingCount, pendingValue }` |
| `GET /receipts` [paged, keyset] · `account.receipts` | `ref` REFERENCE, `source_ref` REFERENCE (`INV-…`, `TXN-…`), `payer_reference` CODE, `terminal_ref` CODE, `customer_name` TRIGRAM, `amount` AMOUNT | `locationId`, `cashAccountId`, `cashierUserId` ("own" forced for Cashiers), `channel` multi, `kind`, `confirmationStatus` multi, `status`, `sourceType` multi, `customerId`, `receivingBankAccountId`, `incomeCategory`, `amount` range, `receivedAt` range/`period`, `pendingOlderThanHours` | `channel`, `confirmationStatus`, `sourceType`, `cashier` | `receivedAt desc`; `amount` | `{ count, total by channel, pendingCount, pendingValue }` |
| `GET /tenderable/receipts` [paged] · `account.receipt-track` | as `/receipts` | `cashierUserId`, `channel`, `status`, `confirmationStatus`, `from`/`to` | `channel` | `date desc` | per-channel totals |
| `GET /tenderable/wallet-funds` [paged] · `account.wallet-funds` | `ref` REFERENCE, dealer code REFERENCE, dealer name TRIGRAM, `amount` AMOUNT | `customerId`, `method` multi, `status`, `bankAccountId`, `from`/`to` | `method`, `status` | `date desc` | `{ total, confirmed, awaiting }` |
| `GET /wallet-fundings` [paged] · `account.wallet-fundings` | `ref` REFERENCE, customer code/name, `payer_reference` CODE, `amount` AMOUNT | `customerId`, `balanceType`, `status`, `channel`, `from`/`to`, `amount` range | `status`, `channel` | `date desc` | `{ total, pending }` |
| `GET /wallet-withdrawals` [paged] · `account.wallet-withdrawals` | `ref` REFERENCE, customer code/name, `paid_via` TRIGRAM, `amount` AMOUNT | `customerId`, `reason` multi, `initiatedBy`, `payoutKind`, `auditStatus`, `processedBy`, `otpVerified`, `amount` range, `from`/`to` | `initiatedBy`, `reason` | `date desc`; `amount` | `{ totalWithdrawn, dealerInitiated, companyInitiated }` |
| `GET /fund-disbursements` [paged] · `account.funds-awaiting` | `ref` REFERENCE, `purpose` FULLTEXT, branch TRIGRAM | `locationId`, `status` multi, `channel`, `hasShortfall`, `sentBy`, `from`/`to` | `status`, `branch` | `date desc` | `{ awaiting, valueAwaiting, shortfallTotal }` |
| `GET /cash-lodgements` [paged] · `account.lodgements` | `ref` REFERENCE, `teller_reference` CODE, `banked` AMOUNT | `locationId`, `bankAccountId`, `status`, `depositedBy`, `from`/`to` | `status`, `bank` | `date desc` | `{ takings, banked, retained }` |
| `GET /exchange-rates/history` [paged] · `account.fx-history` | publication `ref` REFERENCE | `currencyCode` multi, `from`/`to`, `publishedBy` | `currency` | `publishedAt desc` | — |
| `GET /other-income` [paged] · `account.other-income` | `ref` REFERENCE, `payer_name` TRIGRAM, `description` FULLTEXT, `amount` AMOUNT | `locationId`, `incomeCategory` multi, `channel`, `confirmationStatus`, `from`/`to` | `incomeCategory` | `date desc` | `{ total by category }` |

**Lookups**: `GET /bank-accounts/lookup?q=&active=true`, `GET /cash-accounts/lookup?q=&kind=&locationId=`, `GET /expense-categories/lookup?q=&pettyCashAllowed=&active=true` (shows month-to-date spend vs cap as the sublabel).

**Global search providers**: `receipt` (title `RCT-n`, subtitle `channel · amount · customer · status`), `running-cost` (`RC-n`, payee · amount · status), `bank-transaction` (reference, perm `accounting.read`), `lodgement` (`LDG-n`). An exact `INV-`/`TXN-` source ref also returns its receipts.

**Indexes (V11)**: GIN `search_vector` on `payment_receipts` (exists), `operating_costs` (ref, invoice no, payee, description), `bank_transactions` (reference, description), `cash_transactions` (reference, remark), `other_income` via receipts; trigram `f_unaccent(lower(payee))`, `f_unaccent(lower(customer_name))`; btree `(tenant_id, status, payment_date desc)` and `(tenant_id, expense_category_id, payment_date)` on running costs; `(tenant_id, cashier_user_id, received_at)`, `(tenant_id, confirmation_status, received_at)` (exists), `(tenant_id, source_ref)` on receipts; `(tenant_id, bank_account_id, entry_date, id)` and `(tenant_id, cash_account_id, entry_date, id)` keyset indexes on the two ledgers; `(tenant_id, customer_id, created_at)` on fundings and withdrawals.

## 10. Worked example

1. Accounts registers **GTB Collections** (`BA-1`, opening ₦2,000,000) and **Ikeja Till 1** (`TILL-IKJ-01`, TILL, Ikeja, card terminal `T-55`, settlement → `BA-1`), **Ikeja Petty Cash** (`PC-IKJ`, PETTY_CASH, float limit ₦300,000).
2. Fund petty cash ₦150,000 from `BA-1` → `PCF-12`: `BA-1` DEBIT 150,000 (balance 1,850,000), `PC-IKJ` IN 150,000.
3. Petty cash payment ₦8,000 "Transportation" to "Danfo driver" → `PCP-40`: `PC-IKJ` OUT 8,000 with category.
4. Running cost ₦45,000 "Maintenance Expenses" paid by bank transfer from `BA-1`, submitted by Accounts officer A → `RC-112` `PENDING_APPROVAL`; Management user M approves → `BA-1` DEBIT 45,000; M can't approve if M had submitted it.
5. A POS sale (doc 09) of ₦52,137.50: ₦30,000 cash + ₦22,137.50 card → Cashier calls `MoneyLedger.recordReceipt` twice: `RCT-551` CASH (NOT_REQUIRED, `TILL-IKJ-01` IN 30,000) and `RCT-552` POS (PENDING). Accounts approves `RCT-552` next morning → `BA-1` CREDIT 22,137.50.
6. A dealer requests a ₦120,000 withdrawal: Accounts requests an OTP (`context.amount 120000`, target the dealer), Admin issues it, Accounts verifies the code, then `POST /wallet-withdrawals { reason: DEALER_REQUEST, payoutKind: BANK, otpChallengeId, amount: 120000 }` → wallet 250,000 → 130,000, `BA-1` DEBIT 120,000, `WW-31`.
7. End of day the cashier lodges ₦30,000 of ₦32,500 takings (₦2,500 float retained) → `LDG-77`: till OUT 30,000 now; Accounts confirms → `BA-1` CREDIT 30,000.
8. `GET /cash-flow?locationId=<Ikeja>&from=today&to=today` → rows: RCT-551 in 30,000; PCF-12 in 150,000; PCP-40 out 8,000; LDG-77 out 30,000 — running balance across `TILL-IKJ-01` and `PC-IKJ`.

## 11. Verification

- Ledger integrity test (Testcontainers): after a randomised sequence of fundings, payments, receipts, approvals, lodgements and reversals, every account's `current_balance` = opening + Σ ledger, and every `balance_after` chain is consistent.
- Concurrency: two petty cash payments that together exceed the balance → one `400 INSUFFICIENT_CASH`.
- Self-approval blocked for Accounts, allowed for Administrator (setting on).
- Decline a POS receipt → no bank entry; Invoicing (once built) shows the invoice Partially Paid.
- OTP issued for ₦50,000 used for ₦120,000 → `403 OTP_MISMATCH`.
- Funds Awaiting: received > sent → 400; partial without reason → 400; partial with reason → destination credited received only, shortfall event emitted.
- Cashier lists receipts → only their own; Accounts → all at their location.
- Newman folder "14 - Finance & Accounting" covering every endpoint above.

## 12. Implementation notes — slice 1 (V11)

What shipped: bank accounts and the bank ledger, cash accounts and the cash ledger (`MoneyLedgerRepository.postBank/postCash`), manual bank entries, cash counts, the Bank Statement, the Cash Inflow/Outflow Statement, expense categories (seeded per tenant), exchange rates, the `CashAccounts` / `BankAccounts` / `ExchangeRates` spi, and the `bank-transaction` global-search provider. Where it differs from the design above:

- **Running balances are computed in date order.** Statement rows are ordered `(entry_date, created_at, id)` and each row's balance is the sum of the account's (or scope's) entries up to it, so a back-dated entry (bank interest found at month end) still reads correctly. `balance_after` keeps the balance at posting time and backs the cash `>= 0` check. Manual entries and counts may be back-dated, but not into the future and not before the account's opening balance date.
- **Offset paging, not keyset.** The shared paging has no keyset mode yet; statements use the standard `page`/`size`.
- **Default period.** With no `from`/`to`/`period`, the Bank Statement and Cash Inflow/Outflow cover the current month to date.
- **Response shape.** Both statements are a normal paged list; the header values are in `summary`: Bank Statement `{ account, period, openingBalance, closingBalance, totalCredits, totalDebits }`; cash flow `{ scope: { locationId, locationName, accounts }, period, openingCash, totalInflow, totalOutflow, closingCash }`. Cash-flow scope parameters (`locationId`, `cashAccountId`, `kind`) are applied before the row filters (`direction`, `sourceType`, `expenseCategoryId`, `amount`). Filter metadata: `GET /bank-accounts/statement/filters`, `GET /cash-flow/filters`; advanced bodies: `POST /bank-accounts/{id}/statement/search`, `POST /cash-flow/search`.
- **`spentThisMonth`** is read from the two ledgers (bank debits and cash out tagged with the category, less credits/ins from reversals) rather than from running costs and petty cash payments, so slice 2's documents count automatically once they post.
- **`accounting.fx.currencies`** is a list of ISO codes (`STRING_LIST`), chosen from a built-in table of 20 currencies that supplies names and symbols; the default is the six in §2.11.
- **Codes:** bank accounts `BA-n`, manual entries `MAN-n`, cash counts `CC-n`, rate publications `FX-n`, expense categories `EX-n` (the ten seeded categories are `EX-1`…`EX-10`, and the sequence continues at `EX-11`). Cash account codes are entered and stored upper-case.
- **`expense_categories.created_by_user_id` is nullable**: the seeded categories have no creator.
- **Currencies:** bank accounts are NGN unless `DOMICILIARY` (then a tracked currency is required); cash accounts are NGN or a tracked currency.
- **`belowFloat`** = a petty-cash balance under half its float limit.
- **Cash-count variance alerts** go to holders of `platform.activity.read` at the location (Administrator and Management by default), excluding the person who counted.
- **Location scope:** users tied to one location see only that location's bank and cash accounts; head-office accounts (`location_id` null) only to everyone else. A restricted user's cash flow is always their own location.
- **`accounting.manage` is kept until slice 2**, which replaces the last mock endpoints it guards (`/petty-cash/statement`, `/operating-costs`, `/petty-cash/top-up`). Slice 1 removed the mock `/cashflow`, `/bank-statement/{accountNumber}` and mock `POST /bank-accounts`.
- **New permissions** (V11): `accounting.bank.manage`, `accounting.cash-accounts.manage`, `accounting.expense-categories.manage`, `accounting.fx.manage` — Administrator and Accounts Personnel.
- Tests: `AccountLedgersIntegrationTest` (7, including the concurrent-payout race and ledger integrity). Newman folder **"17 - Finance & Accounting"** (29 requests).

