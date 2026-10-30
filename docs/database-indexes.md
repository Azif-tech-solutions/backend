# Database indexes

Generated from the schema by `scripts/db/index-report.sql` (regenerate after every migration). Every tenant table leads its working indexes with `tenant_id`, the column row-level security filters on; `gin` indexes back trigram (`gin_trgm_ops`) and full-text (`search_vector`) search; partial indexes (a value under "Only rows where") cover only the rows a hot query reads.

### `app_users`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `app_users_pkey` | primary key | id |  |
| `app_users_display_name_trgm` | gin | f_unaccent(lower((display_name)::text)) gin_trgm_ops |  |
| `app_users_email_key` | unique | email |  |

### `audit_alerts`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `audit_alerts_pkey` | primary key | id |  |
| `audit_alerts_dedupe_uq` | unique | tenant_id, dedupe_key |  |
| `audit_alerts_evidence_idx` | gin | evidence |  |
| `audit_alerts_open_idx` | btree | tenant_id, status, created_at DESC |  |
| `audit_alerts_rule_idx` | btree | tenant_id, rule_code, created_at DESC |  |

### `audit_reviews`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `audit_reviews_pkey` | primary key | id |  |
| `audit_reviews_list_idx` | btree | tenant_id, record_type, status, updated_at DESC |  |
| `audit_reviews_record_uq` | unique | tenant_id, record_type, record_id |  |

### `bank_accounts`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `bank_accounts_pkey` | primary key | id |  |
| `bank_accounts_name_trgm` | gin | f_unaccent(lower((((((bank_name)::text \|\| ' '::text) \|\| (account_name)::text) \|\| ' '::text) \|\| (COALESCE(branch, ''::character varying))::text))) gin_trgm_ops |  |
| `bank_accounts_number_uq` | unique | tenant_id, account_number, lower((bank_name)::text) |  |
| `bank_accounts_tenant_id_code_key` | unique | tenant_id, code |  |
| `bank_accounts_tenant_id_key` | unique | tenant_id, id |  |

### `bank_transactions`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `bank_transactions_pkey` | primary key | id |  |
| `bank_transactions_tenant_id_key` | unique | tenant_id, id |  |
| `bank_tx_account_idx` | btree | tenant_id, bank_account_id, entry_date, created_at, id |  |
| `bank_tx_category_idx` | btree | tenant_id, expense_category_id, entry_date | (expense_category_id IS NOT NULL) |
| `bank_tx_reference_idx` | btree | tenant_id, lower((reference)::text) |  |
| `bank_tx_search_idx` | gin | search_vector |  |
| `bank_tx_source_idx` | btree | tenant_id, source_type, source_id |  |

### `cash_accounts`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `cash_accounts_pkey` | primary key | id |  |
| `cash_accounts_location_idx` | btree | tenant_id, location_id, kind |  |
| `cash_accounts_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `cash_accounts_tenant_id_code_key` | unique | tenant_id, code |  |
| `cash_accounts_tenant_id_key` | unique | tenant_id, id |  |

### `cash_lodgements`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `cash_lodgements_pkey` | primary key | id |  |
| `cash_lodgements_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `lodgements_location_idx` | btree | tenant_id, location_id, status, deposit_date DESC |  |

### `cash_transactions`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `cash_transactions_pkey` | primary key | id |  |
| `cash_transactions_tenant_id_key` | unique | tenant_id, id |  |
| `cash_tx_account_idx` | btree | tenant_id, cash_account_id, entry_date, created_at, id |  |
| `cash_tx_category_idx` | btree | tenant_id, expense_category_id, entry_date | (expense_category_id IS NOT NULL) |
| `cash_tx_reference_idx` | btree | tenant_id, lower((reference)::text) |  |
| `cash_tx_search_idx` | gin | search_vector |  |
| `cash_tx_source_idx` | btree | tenant_id, source_type, source_id |  |

### `categories`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `categories_pkey` | primary key | id |  |
| `categories_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `categories_tenant_id_key` | unique | tenant_id, id |  |
| `categories_tenant_id_name_key` | unique | tenant_id, name |  |

### `company_profiles`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `company_profiles_pkey` | primary key | tenant_id |  |

### `correspondent_assignment_history`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `correspondent_assignment_history_pkey` | primary key | id |  |
| `cah_correspondent_idx` | btree | tenant_id, correspondent_id, effective_from DESC |  |

### `correspondents`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `correspondents_pkey` | primary key | id |  |
| `correspondents_name_idx` | btree | tenant_id, name, id |  |
| `correspondents_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `correspondents_search_idx` | gin | search_vector |  |
| `correspondents_status_region_idx` | btree | tenant_id, status, region |  |
| `correspondents_tenant_id_code_key` | unique | tenant_id, code |  |
| `correspondents_tenant_id_key` | unique | tenant_id, id |  |
| `correspondents_user_uq` | unique | tenant_id, user_id | (user_id IS NOT NULL) |

### `credit_note_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `credit_note_lines_pkey` | primary key | id |  |
| `credit_note_lines_by_line_idx` | btree | invoice_line_id |  |

### `credit_notes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `credit_notes_pkey` | primary key | id |  |
| `credit_notes_by_invoice_idx` | btree | invoice_id |  |
| `credit_notes_issued_idx` | btree | tenant_id, issued_at DESC |  |
| `credit_notes_search_idx` | gin | search_vector |  |
| `credit_notes_tenant_id_key` | unique | tenant_id, id |  |
| `credit_notes_tenant_id_number_key` | unique | tenant_id, number |  |

### `custom_field_definitions`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `custom_field_definitions_pkey` | primary key | id |  |
| `custom_field_definitions_tenant_id_entity_type_field_key_key` | unique | tenant_id, entity_type, field_key |  |

### `customer_ledger_entries`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customer_ledger_entries_pkey` | primary key | id |  |
| `cle_customer_idx` | btree | tenant_id, customer_id, balance_type, created_at, id |  |
| `cle_reference_idx` | btree | tenant_id, reference |  |
| `cle_search_idx` | gin | search_vector |  |
| `cle_single_reversal` | unique | reverses_entry_id | (reverses_entry_id IS NOT NULL) |
| `cle_source_idx` | btree | tenant_id, source_module, source_id |  |
| `cle_type_date_idx` | btree | tenant_id, entry_type, entry_date DESC, created_at DESC, id DESC |  |
| `customer_ledger_entries_tenant_id_key` | unique | tenant_id, id |  |

### `customer_reclassifications`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customer_reclassifications_pkey` | primary key | id |  |
| `reclass_customer_idx` | btree | tenant_id, customer_id, created_at DESC |  |
| `reclass_due_idx` | btree | tenant_id, effective_from | ((status)::text = 'SCHEDULED'::text) |
| `reclass_one_scheduled` | unique | customer_id | ((status)::text = 'SCHEDULED'::text) |

### `customer_return_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customer_return_lines_pkey` | primary key | id |  |
| `crl_reason_idx` | btree | tenant_id, reason |  |
| `crl_return_idx` | btree | tenant_id, return_id |  |
| `customer_return_lines_return_id_line_no_key` | unique | return_id, line_no |  |

### `customer_returns`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customer_returns_pkey` | primary key | id |  |
| `cr_customer_idx` | btree | tenant_id, customer_id |  |
| `cr_customer_name_trgm` | gin | f_unaccent(lower((customer_name)::text)) gin_trgm_ops |  |
| `cr_location_date_idx` | btree | tenant_id, location_id, return_date DESC |  |
| `cr_original_doc_idx` | btree | tenant_id, original_document_ref |  |
| `cr_search_idx` | gin | search_vector |  |
| `customer_returns_tenant_id_key` | unique | tenant_id, id |  |
| `customer_returns_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `customer_waybill_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customer_waybill_lines_pkey` | primary key | id |  |
| `customer_waybill_lines_sale_line_idx` | btree | tenant_id, sale_line_id |  |
| `customer_waybill_lines_waybill_idx` | btree | tenant_id, waybill_id |  |

### `customer_waybills`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customer_waybills_pkey` | primary key | id |  |
| `customer_waybills_date_idx` | btree | tenant_id, location_id, release_date DESC |  |
| `customer_waybills_ref_uq` | unique | tenant_id, ref |  |
| `customer_waybills_sale_idx` | btree | tenant_id, sale_id |  |
| `customer_waybills_status_idx` | btree | tenant_id, status, release_date DESC |  |
| `customer_waybills_tenant_id_key` | unique | tenant_id, id |  |

### `customers`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `customers_pkey` | primary key | id |  |
| `customers_attrs_idx` | gin | custom_attributes jsonb_path_ops |  |
| `customers_category_idx` | btree | tenant_id, dealer_category_id |  |
| `customers_contact_trgm` | gin | f_unaccent(lower((contact_person)::text)) gin_trgm_ops |  |
| `customers_correspondent_idx` | btree | tenant_id, correspondent_id |  |
| `customers_credit_limit_idx` | btree | tenant_id, id) INCLUDE (credit_limit |  |
| `customers_dealer_name_uq` | unique | tenant_id, lower((name)::text) | (((type)::text = 'AUTHORISED_DEALER'::text) AND ((status)::text <> 'REMOVED'::text)) |
| `customers_email_idx` | btree | tenant_id, lower((email)::text) |  |
| `customers_last_purchase_idx` | btree | tenant_id, last_purchase_at |  |
| `customers_location_idx` | btree | tenant_id, location_id, status |  |
| `customers_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `customers_phone_idx` | btree | tenant_id, phone_e164 |  |
| `customers_search_idx` | gin | search_vector |  |
| `customers_temp_phone_uq` | unique | tenant_id, phone_e164 | (is_temporary AND ((status)::text = 'ACTIVE'::text)) |
| `customers_tenant_id_code_key` | unique | tenant_id, code |  |
| `customers_tenant_id_key` | unique | tenant_id, id |  |
| `customers_type_status_idx` | btree | tenant_id, type, status, name |  |
| `customers_user_uq` | unique | tenant_id, user_id | (user_id IS NOT NULL) |

### `dealer_categories`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `dealer_categories_pkey` | primary key | id |  |
| `dealer_categories_name_uq` | unique | tenant_id, lower((name)::text) |  |
| `dealer_categories_tenant_id_key` | unique | tenant_id, id |  |

### `debtor_balances`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `debtor_balances_pkey` | primary key | tenant_id, customer_id, location_id |  |
| `debtor_balances_oldest_idx` | btree | tenant_id, location_id, oldest_open_issue_date, customer_id | (open_invoices > 0) |
| `debtor_balances_outstanding_idx` | btree | tenant_id, location_id, outstanding DESC, customer_id | (open_invoices > 0) |
| `debtor_balances_payment_idx` | btree | tenant_id, location_id, last_payment_at, customer_id | (open_invoices > 0) |

### `document_reprints`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `document_reprints_pkey` | primary key | id |  |
| `document_reprints_doc_idx` | btree | tenant_id, issued_document_id, reprinted_at DESC |  |

### `document_sequences`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `document_sequences_pkey` | primary key | tenant_id, prefix |  |

### `domain_events`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `domain_events_pkey` | primary key | id |  |
| `domain_events_actor_idx` | btree | tenant_id, actor_user_id, occurred_at DESC |  |
| `domain_events_aggregate_idx` | btree | tenant_id, aggregate_type, aggregate_id |  |
| `domain_events_consume_idx` | btree | tenant_id, txid, seq |  |
| `domain_events_ref_idx` | btree | tenant_id, aggregate_ref |  |
| `domain_events_search_idx` | gin | search_vector |  |
| `domain_events_tenant_id_key` | unique | tenant_id, id |  |
| `domain_events_tenant_time_idx` | btree | tenant_id, occurred_at DESC |  |
| `domain_events_type_idx` | btree | tenant_id, event_type, occurred_at DESC |  |

### `drivers`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `drivers_pkey` | primary key | id |  |
| `drivers_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `drivers_name_vehicle_uq` | unique | tenant_id, lower((name)::text), COALESCE(lower((vehicle_number)::text), ''::text) |  |
| `drivers_tenant_id_key` | unique | tenant_id, id |  |

### `event_consumer_checkpoints`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `event_consumer_checkpoints_pkey` | primary key | consumer, tenant_id |  |

### `exchange_rate_publications`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `exchange_rate_publications_pkey` | primary key | id |  |
| `exchange_rate_publications_tenant_id_key` | unique | tenant_id, id |  |
| `exchange_rate_publications_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `exchange_rates`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `exchange_rates_pkey` | primary key | id |  |
| `exchange_rates_lookup_idx` | btree | tenant_id, currency_code, published_at DESC |  |
| `exchange_rates_publication_idx` | btree | publication_id |  |

### `expense_categories`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `expense_categories_pkey` | primary key | id |  |
| `expense_categories_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `expense_categories_name_uq` | unique | tenant_id, lower((name)::text) |  |
| `expense_categories_tenant_id_code_key` | unique | tenant_id, code |  |
| `expense_categories_tenant_id_key` | unique | tenant_id, id |  |

### `factory_trade_offers`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `factory_trade_offers_pkey` | primary key | id |  |
| `factory_trade_offers_offered_idx` | btree | tenant_id, offered_at DESC, id DESC |  |
| `factory_trade_offers_status_idx` | btree | tenant_id, status, offered_at DESC |  |
| `factory_trade_offers_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `fund_disbursements`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `fund_disbursements_pkey` | primary key | id |  |
| `fd_destination_idx` | btree | tenant_id, destination_location_id, status, sent_at DESC |  |
| `fund_disbursements_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `hsn_codes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `hsn_codes_pkey` | primary key | code |  |
| `hsn_codes_section_idx` | btree | section_code |  |

### `hsn_sections`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `hsn_sections_pkey` | primary key | id |  |
| `hsn_sections_code_key` | unique | code |  |

### `instant_discounts`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `instant_discounts_pkey` | primary key | id |  |
| `instant_discounts_list_idx` | btree | tenant_id, status, created_at DESC |  |
| `instant_discounts_one_live_uq` | unique | sale_id | ((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPLIED'::character varying])::text[])) |
| `instant_discounts_ref_uq` | unique | tenant_id, ref |  |
| `instant_discounts_tenant_id_key` | unique | tenant_id, id |  |

### `invoice_deliveries`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `invoice_deliveries_pkey` | primary key | id |  |
| `invoice_deliveries_by_invoice_idx` | btree | invoice_id, requested_at DESC |  |
| `invoice_deliveries_outbox_idx` | btree | notification_outbox_id |  |

### `invoice_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `invoice_lines_pkey` | primary key | id |  |
| `invoice_lines_invoice_id_line_no_key` | unique | invoice_id, line_no |  |
| `invoice_lines_item_idx` | btree | tenant_id, stock_item_id |  |
| `invoice_lines_tenant_id_key` | unique | tenant_id, id |  |

### `invoice_payments`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `invoice_payments_pkey` | primary key | id |  |
| `invoice_payments_by_invoice_idx` | btree | invoice_id |  |
| `invoice_payments_receipt_idx` | btree | tenant_id, payment_receipt_id |  |
| `invoice_payments_receipt_uq` | unique | payment_receipt_id | ((source)::text <> 'REISSUE_TRANSFER'::text) |

### `invoice_waybill_links`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `invoice_waybill_links_pkey` | primary key | id |  |
| `invoice_waybill_links_invoice_id_waybill_id_key` | unique | invoice_id, waybill_id |  |

### `invoices`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `invoices_pkey` | primary key | id |  |
| `invoices_balance_idx` | btree | tenant_id, (((tax_inclusive_amount - amount_paid) - amount_credited)) | ((status)::text = 'ISSUED'::text) |
| `invoices_buyer_trgm` | gin | f_unaccent(lower((buyer_name)::text)) gin_trgm_ops |  |
| `invoices_correspondent_idx` | btree | tenant_id, correspondent_id |  |
| `invoices_customer_idx` | btree | tenant_id, customer_id, status |  |
| `invoices_customer_issued_idx` | btree | tenant_id, customer_id, issue_date, id | ((status)::text = 'ISSUED'::text) |
| `invoices_issued_at_idx` | btree | tenant_id, issued_at DESC, id DESC |  |
| `invoices_location_issue_idx` | btree | tenant_id, location_id, issue_date DESC |  |
| `invoices_number_uq` | unique | tenant_id, number | (number IS NOT NULL) |
| `invoices_open_idx` | btree | tenant_id, due_date | ((status)::text = 'ISSUED'::text) |
| `invoices_reissue_of_idx` | btree | reissue_of_invoice_id | (reissue_of_invoice_id IS NOT NULL) |
| `invoices_replaced_by_idx` | btree | replaced_by_invoice_id | (replaced_by_invoice_id IS NOT NULL) |
| `invoices_search_idx` | gin | search_vector |  |
| `invoices_source_uq` | unique | tenant_id, source_type, source_id | ((status)::text <> 'VOID'::text) |
| `invoices_tenant_id_key` | unique | tenant_id, id |  |

### `isic_classes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `isic_classes_pkey` | primary key | code |  |
| `isic_classes_section_idx` | btree | section_code |  |

### `isic_sections`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `isic_sections_pkey` | primary key | id |  |
| `isic_sections_code_key` | unique | code |  |

### `issued_documents`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `issued_documents_pkey` | primary key | id |  |
| `issued_documents_location_idx` | btree | tenant_id, location_id, issued_at DESC |  |
| `issued_documents_party_trgm` | gin | f_unaccent(lower((party_name)::text)) gin_trgm_ops |  |
| `issued_documents_search_idx` | gin | search_vector |  |
| `issued_documents_source_idx` | btree | tenant_id, source_module, source_id |  |
| `issued_documents_tenant_id_key` | unique | tenant_id, id |  |
| `issued_documents_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `issued_documents_type_time_idx` | btree | tenant_id, doc_type, issued_at DESC |  |

### `location_vat_settings`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `location_vat_settings_pkey` | primary key | id |  |
| `location_vat_settings_tenant_id_location_id_effective_from_key` | unique | tenant_id, location_id, effective_from |  |
| `lvs_location_idx` | btree | tenant_id, location_id, effective_from DESC |  |

### `locations`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `locations_pkey` | primary key | id |  |
| `locations_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `locations_parent_idx` | btree | tenant_id, parent_location_id | (parent_location_id IS NOT NULL) |
| `locations_showroom_name_uq` | unique | tenant_id, lower((name)::text) | ((type)::text = 'SHOWROOM'::text) |
| `locations_tenant_id_code_key` | unique | tenant_id, code |  |
| `locations_tenant_id_key` | unique | tenant_id, id |  |

### `manufacturers`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `manufacturers_pkey` | primary key | id |  |
| `manufacturers_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `manufacturers_tenant_id_code_key` | unique | tenant_id, code |  |
| `manufacturers_tenant_id_key` | unique | tenant_id, id |  |

### `module_settings`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `module_settings_pkey` | primary key | tenant_id, setting_key |  |

### `nigeria_lgas`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `nigeria_lgas_pkey` | primary key | code |  |
| `nigeria_lgas_state_idx` | btree | state_code |  |

### `nigeria_states`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `nigeria_states_pkey` | primary key | code |  |

### `notification_outbox`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `notification_outbox_pkey` | primary key | id |  |
| `notification_outbox_pending_idx` | btree | tenant_id, created_at | ((status)::text = 'PENDING'::text) |
| `notification_outbox_tenant_id_key` | unique | tenant_id, id |  |

### `nrs_invoice_type_codes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `nrs_invoice_type_codes_pkey` | primary key | code |  |

### `nrs_payment_means_codes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `nrs_payment_means_codes_pkey` | primary key | code |  |

### `nrs_tax_categories`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `nrs_tax_categories_pkey` | primary key | code |  |

### `nrs_unit_of_measure_codes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `nrs_unit_of_measure_codes_pkey` | primary key | code |  |

### `operating_costs`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `operating_costs_pkey` | primary key | id |  |
| `operating_costs_category_idx` | btree | tenant_id, expense_category_id, payment_date |  |
| `operating_costs_payee_trgm` | gin | f_unaccent(lower((payee)::text)) gin_trgm_ops |  |
| `operating_costs_ref_uq` | unique | tenant_id, ref | (ref IS NOT NULL) |
| `operating_costs_search_idx` | gin | search_vector |  |
| `operating_costs_status_idx` | btree | tenant_id, status, payment_date DESC |  |

### `otp_challenges`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `otp_challenges_pkey` | primary key | id |  |
| `otp_challenges_tenant_id_key` | unique | tenant_id, id |  |

### `password_reset_tokens`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `password_reset_tokens_pkey` | primary key | id |  |
| `password_reset_tokens_token_hash_key` | unique | token_hash |  |
| `password_reset_tokens_user_idx` | btree | user_id |  |

### `payment_receipts`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `payment_receipts_pkey` | primary key | id |  |
| `payment_receipts_tenant_id_key` | unique | tenant_id, id |  |
| `payment_receipts_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `pr_cashier_idx` | btree | tenant_id, cashier_user_id, received_at |  |
| `pr_customer_name_trgm` | gin | f_unaccent(lower((customer_name)::text)) gin_trgm_ops |  |
| `pr_location_time_idx` | btree | tenant_id, location_id, received_at DESC |  |
| `pr_pending_idx` | btree | tenant_id, location_id, received_at | ((confirmation_status)::text = 'PENDING'::text) |
| `pr_received_idx` | btree | tenant_id, received_at DESC, id DESC |  |
| `pr_search_idx` | gin | search_vector |  |
| `pr_source_idx` | btree | tenant_id, source_module, source_id |  |
| `pr_source_ref_idx` | btree | tenant_id, lower((source_ref)::text) |  |

### `permission_catalog`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `permission_catalog_pkey` | primary key | id |  |
| `permission_catalog_code_key` | unique | code |  |

### `petty_cash_allocations`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `petty_cash_allocations_pkey` | primary key | id |  |
| `petty_cash_allocations_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `ppc_destination_idx` | btree | tenant_id, destination_location_id, status, sent_at DESC |  |

### `petty_cash_fundings`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `petty_cash_fundings_pkey` | primary key | id |  |
| `pcf_account_idx` | btree | tenant_id, petty_cash_account_id, funding_date DESC |  |
| `pcf_search_idx` | gin | search_vector |  |
| `petty_cash_fundings_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `petty_cash_payments`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `petty_cash_payments_pkey` | primary key | id |  |
| `pcp_account_idx` | btree | tenant_id, petty_cash_account_id, payment_date DESC |  |
| `pcp_paid_to_trgm` | gin | f_unaccent(lower((paid_to)::text)) gin_trgm_ops |  |
| `petty_cash_payments_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `pro_forma_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `pro_forma_lines_pkey` | primary key | id |  |
| `pfl_line_uq` | unique | pro_forma_id, line_no |  |
| `pro_forma_lines_pf_idx` | btree | tenant_id, pro_forma_id |  |

### `pro_formas`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `pro_formas_pkey` | primary key | id |  |
| `pro_formas_customer_idx` | btree | tenant_id, customer_id |  |
| `pro_formas_name_trgm` | gin | f_unaccent(lower((customer_name)::text)) gin_trgm_ops |  |
| `pro_formas_ref_uq` | unique | tenant_id, ref |  |
| `pro_formas_search_idx` | gin | search_vector |  |
| `pro_formas_status_idx` | btree | tenant_id, location_id, status, issue_date DESC |  |
| `pro_formas_tenant_id_key` | unique | tenant_id, id |  |

### `production_costings`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `production_costings_pkey` | primary key | id |  |
| `production_costings_location_status_idx` | btree | tenant_id, location_id, status |  |
| `production_costings_recipe_idx` | btree | tenant_id, recipe_id | (recipe_id IS NOT NULL) |
| `production_costings_tenant_id_key` | unique | tenant_id, id |  |

### `production_runs`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `production_runs_pkey` | primary key | id |  |
| `production_runs_costing_idx` | btree | production_costing_id |  |
| `production_runs_location_idx` | btree | tenant_id, location_id, run_at DESC, id DESC |  |
| `production_runs_run_at_idx` | btree | tenant_id, run_at DESC, id DESC |  |
| `production_runs_tenant_id_batch_ref_key` | unique | tenant_id, batch_ref |  |

### `quantity_adjustments`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `quantity_adjustments_pkey` | primary key | id |  |
| `quantity_adjustments_item_idx` | btree | tenant_id, stock_item_id, created_at DESC |  |
| `quantity_adjustments_location_idx` | btree | tenant_id, location_id, created_at DESC |  |
| `quantity_adjustments_reason_idx` | btree | tenant_id, reason |  |
| `quantity_adjustments_search_idx` | gin | search_vector |  |
| `quantity_adjustments_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `recipe_ingredients`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `recipe_ingredients_pkey` | primary key | id |  |
| `recipe_ingredients_recipe_idx` | btree | recipe_id |  |

### `recipes`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `recipes_pkey` | primary key | id |  |
| `recipes_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `recipes_search_vector_idx` | gin | search_vector |  |
| `recipes_tenant_id_code_key` | unique | tenant_id, code |  |
| `recipes_tenant_id_key` | unique | tenant_id, id |  |

### `refresh_tokens`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `refresh_tokens_pkey` | primary key | id |  |
| `refresh_tokens_token_hash_key` | unique | token_hash |  |
| `refresh_tokens_user_idx` | btree | user_id, tenant_id |  |

### `role_permissions`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `role_permissions_pkey` | primary key | role_id, permission_id |  |

### `sale_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `sale_lines_pkey` | primary key | id |  |
| `sale_lines_item_idx` | btree | tenant_id, stock_item_id |  |
| `sale_lines_line_uq` | unique | sale_id, line_no |  |
| `sale_lines_tenant_id_key` | unique | tenant_id, id |  |

### `sale_payments`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `sale_payments_pkey` | primary key | id |  |
| `sale_payments_method_idx` | btree | tenant_id, method, sale_id |  |
| `sale_payments_receipt_idx` | btree | tenant_id, payment_receipt_id |  |
| `sale_payments_sale_idx` | btree | tenant_id, sale_id |  |

### `sales`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `sales_pkey` | primary key | id |  |
| `sales_cashier_idx` | btree | tenant_id, cashier_user_id, sale_date |  |
| `sales_completed_idx` | btree | tenant_id, completed_at DESC, id | (ref IS NOT NULL) |
| `sales_correspondent_idx` | btree | tenant_id, correspondent_id, sale_date |  |
| `sales_customer_idx` | btree | tenant_id, customer_id, sale_date |  |
| `sales_date_idx` | btree | tenant_id, location_id, sale_date | ((status)::text = 'COMPLETED'::text) |
| `sales_invoice_idx` | btree | tenant_id, invoice_id | (invoice_id IS NOT NULL) |
| `sales_invoice_number_idx` | btree | tenant_id, invoice_number | (invoice_number IS NOT NULL) |
| `sales_name_trgm` | gin | f_unaccent(lower((customer_name)::text)) gin_trgm_ops |  |
| `sales_open_idx` | btree | tenant_id, location_id, started_at | ((status)::text = 'OPEN'::text) |
| `sales_ref_uq` | unique | tenant_id, ref | (ref IS NOT NULL) |
| `sales_search_idx` | gin | search_vector |  |
| `sales_tenant_id_key` | unique | tenant_id, id |  |
| `sales_till_session_idx` | btree | tenant_id, till_session_id | (till_session_id IS NOT NULL) |

### `saved_views`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `saved_views_pkey` | primary key | id |  |
| `saved_views_list_idx` | btree | tenant_id, list_key, visibility |  |
| `saved_views_one_default_uq` | unique | tenant_id, owner_user_id, list_key | is_default |
| `saved_views_tenant_id_owner_user_id_list_key_name_key` | unique | tenant_id, owner_user_id, list_key, name |  |

### `showroom_stock_entries`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `showroom_stock_entries_pkey` | primary key | id |  |
| `showroom_stock_entries_tenant_id_key` | unique | tenant_id, id |  |
| `showroom_stock_entries_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `sse_kind_idx` | btree | tenant_id, kind, received_date DESC |  |
| `sse_location_date_idx` | btree | tenant_id, location_id, received_date DESC |  |
| `sse_supplier_name_trgm` | gin | f_unaccent(lower((supplier_name)::text)) gin_trgm_ops |  |

### `showroom_stock_entry_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `showroom_stock_entry_lines_pkey` | primary key | id |  |
| `showroom_stock_entry_lines_entry_id_line_no_key` | unique | entry_id, line_no |  |
| `ssel_description_trgm` | gin | f_unaccent(lower((description)::text)) gin_trgm_ops |  |
| `ssel_entry_idx` | btree | tenant_id, entry_id |  |
| `ssel_item_idx` | btree | tenant_id, stock_item_id |  |

### `special_production_clients`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `special_production_clients_pkey` | primary key | id |  |
| `special_production_clients_tenant_id_key` | unique | tenant_id, id |  |

### `special_production_runs`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `special_production_runs_pkey` | primary key | id |  |
| `special_production_runs_client_idx` | btree | tenant_id, client_id, created_at DESC |  |
| `special_production_runs_created_idx` | btree | tenant_id, created_at DESC, id DESC |  |

### `stock_balances`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_balances_pkey` | primary key | location_id, stock_item_id |  |
| `stock_balances_item_idx` | btree | tenant_id, stock_item_id, location_id |  |

### `stock_item_prices`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_item_prices_pkey` | primary key | id |  |
| `stock_item_prices_batch_idx` | btree | tenant_id, batch_ref |  |
| `stock_item_prices_lookup_idx` | btree | tenant_id, stock_item_id, effective_from DESC |  |
| `stock_item_prices_tenant_id_stock_item_id_effective_from_key` | unique | tenant_id, stock_item_id, effective_from |  |

### `stock_items`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_items_pkey` | primary key | id |  |
| `stock_items_barcode_idx` | unique | tenant_id, barcode | (barcode IS NOT NULL) |
| `stock_items_brand_trgm` | gin | f_unaccent(lower((brand)::text)) gin_trgm_ops |  |
| `stock_items_category_idx` | btree | tenant_id, category_id | (category_id IS NOT NULL) |
| `stock_items_location_name_idx` | btree | tenant_id, location_id, name, id |  |
| `stock_items_name_idx` | btree | tenant_id, name, id |  |
| `stock_items_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `stock_items_tenant_id_code_key` | unique | tenant_id, code |  |
| `stock_items_tenant_id_key` | unique | tenant_id, id |  |

### `stock_ledger_entries`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_ledger_entries_pkey` | primary key | id |  |
| `stock_ledger_date_idx` | btree | tenant_id, entry_date, created_at, id |  |
| `stock_ledger_item_location_date_idx` | btree | tenant_id, stock_item_id, location_id, entry_date, created_at |  |
| `stock_ledger_location_date_idx` | btree | tenant_id, location_id, entry_date, created_at, id |  |

### `stock_receipts`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_receipts_pkey` | primary key | id |  |
| `stock_receipts_created_idx` | btree | tenant_id, created_at DESC, id DESC |  |
| `stock_receipts_location_idx` | btree | tenant_id, location_id, created_at DESC, id DESC |  |
| `stock_receipts_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `stock_reservations`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_reservations_pkey` | primary key | id |  |
| `stock_reservations_open_idx` | btree | tenant_id, location_id, stock_item_id | ((status)::text = 'OPEN'::text) |
| `stock_reservations_source_uq` | unique | source_type, source_id |  |
| `stock_reservations_tenant_id_key` | unique | tenant_id, id |  |

### `stock_transfer_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_transfer_lines_pkey` | primary key | id |  |
| `stock_transfer_lines_transfer_idx` | btree | stock_transfer_id |  |

### `stock_transfers`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stock_transfers_pkey` | primary key | id |  |
| `stock_transfers_from_idx` | btree | tenant_id, source_location_id, transfer_date DESC |  |
| `stock_transfers_tenant_id_key` | unique | tenant_id, id |  |
| `stock_transfers_tenant_id_waybill_ref_key` | unique | tenant_id, waybill_ref |  |
| `stock_transfers_to_idx` | btree | tenant_id, destination_location_id, status, transfer_date DESC |  |
| `stock_transfers_waybill_trgm` | gin | lower((waybill_ref)::text) gin_trgm_ops |  |

### `storage_area_move_lines`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `storage_area_move_lines_pkey` | primary key | id |  |
| `storage_area_move_lines_move_id_line_no_key` | unique | move_id, line_no |  |

### `storage_area_moves`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `storage_area_moves_pkey` | primary key | id |  |
| `sam_root_date_idx` | btree | tenant_id, root_location_id, moved_date DESC |  |
| `storage_area_moves_tenant_id_key` | unique | tenant_id, id |  |
| `storage_area_moves_tenant_id_ref_key` | unique | tenant_id, ref |  |

### `stored_files`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `stored_files_pkey` | primary key | id |  |
| `stored_files_linked_idx` | btree | tenant_id, linked_type, linked_id | (linked_id IS NOT NULL) |
| `stored_files_purpose_idx` | btree | tenant_id, purpose, uploaded_at DESC |  |
| `stored_files_storage_key_key` | unique | storage_key |  |
| `stored_files_tenant_id_key` | unique | tenant_id, id |  |

### `suppliers`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `suppliers_pkey` | primary key | id |  |
| `suppliers_company_name_trgm` | gin | f_unaccent(lower((company_name)::text)) gin_trgm_ops |  |
| `suppliers_tenant_id_code_key` | unique | tenant_id, code |  |
| `suppliers_tenant_id_key` | unique | tenant_id, id |  |

### `tenant_memberships`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `tenant_memberships_pkey` | primary key | tenant_id, user_id |  |
| `tenant_memberships_user_idx` | btree | user_id, tenant_id |  |

### `tenant_roles`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `tenant_roles_pkey` | primary key | id |  |
| `tenant_roles_tenant_id_id_key` | unique | tenant_id, id |  |
| `tenant_roles_tenant_id_name_key` | unique | tenant_id, name |  |

### `tenant_vat_categories`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `tenant_vat_categories_pkey` | primary key | tenant_id, vat_category_id |  |

### `tenants`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `tenants_pkey` | primary key | id |  |

### `till_sessions`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `till_sessions_pkey` | primary key | id |  |
| `till_sessions_cashier_idx` | btree | tenant_id, cashier_user_id, opened_at DESC |  |
| `till_sessions_location_idx` | btree | tenant_id, location_id, opened_at DESC |  |
| `till_sessions_open_cashier_uq` | unique | tenant_id, cashier_user_id | ((status)::text = 'OPEN'::text) |
| `till_sessions_open_till_uq` | unique | tenant_id, cash_account_id | ((status)::text = 'OPEN'::text) |
| `till_sessions_tenant_id_key` | unique | tenant_id, id |  |

### `units_of_measure`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `units_of_measure_pkey` | primary key | id |  |
| `units_of_measure_name_trgm` | gin | f_unaccent(lower((name)::text)) gin_trgm_ops |  |
| `units_of_measure_tenant_id_code_key` | unique | tenant_id, code |  |
| `units_of_measure_tenant_id_key` | unique | tenant_id, id |  |

### `user_location_assignments`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `user_location_assignments_pkey` | primary key | tenant_id, user_id |  |

### `user_notifications`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `user_notifications_pkey` | primary key | id |  |
| `user_notifications_unread_idx` | btree | tenant_id, user_id | ((read_at IS NULL) AND (dismissed_at IS NULL)) |
| `user_notifications_user_idx` | btree | tenant_id, user_id, created_at DESC | (dismissed_at IS NULL) |

### `user_role_assignments`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `user_role_assignments_pkey` | primary key | tenant_id, user_id, role_id |  |
| `user_role_assignments_user_idx` | btree | tenant_id, user_id |  |

### `vat_categories`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `vat_categories_pkey` | primary key | id |  |
| `vat_categories_code_key` | unique | code |  |

### `violation_record_events`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `violation_record_events_pkey` | primary key | id |  |
| `vre_record_idx` | btree | tenant_id, violation_record_id, at |  |

### `violation_records`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `violation_records_pkey` | primary key | id |  |
| `violation_records_ref_uq` | unique | tenant_id, ref |  |
| `vr_linked_refs_idx` | gin | linked_refs jsonb_path_ops |  |
| `vr_location_idx` | btree | tenant_id, location_id, status |  |
| `vr_search_idx` | gin | search_vector |  |
| `vr_subject_idx` | btree | tenant_id, subject_user_id, raised_at DESC |  |

### `wallet_fundings`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `wallet_fundings_pkey` | primary key | id |  |
| `wallet_fundings_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `wf_created_idx` | btree | tenant_id, created_at DESC, id DESC |  |
| `wf_customer_idx` | btree | tenant_id, customer_id, created_at DESC |  |

### `wallet_withdrawals`

| Index | Kind | Columns / expression | Only rows where |
|---|---|---|---|
| `wallet_withdrawals_pkey` | primary key | id |  |
| `wallet_withdrawals_tenant_id_ref_key` | unique | tenant_id, ref |  |
| `ww_customer_idx` | btree | tenant_id, customer_id, created_at DESC |  |
| `ww_otp_once` | unique | otp_challenge_id | (otp_challenge_id IS NOT NULL) |

