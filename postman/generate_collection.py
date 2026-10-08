# -*- coding: utf-8 -*-
"""Generates the ZifIQ Postman collection + Local environment from a declarative endpoint list.

Run `python postman/generate_collection.py`: it rewrites ZifIQ-API.postman_collection.json and
ZifIQ-Local.postman_environment.json next to this script (the Dev environment is hand-maintained).
CI runs the collection with Newman against the Local environment, so every request must stay green.
"""
import json
import os
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))

def uid():
    return str(uuid.uuid4())

ENDPOINTS = []

def ep(folder, name, method, path, auth=None, body=None, resp=None, status=200, status_text="OK", tests=None, query=None, desc="", expect_error=None, form=None, pdf=False):
    # expect_error: {"status": int, "code": "ERROR_CODE"} for requests that are DELIBERATELY run
    # against a state that makes them fail (demonstrating a real error path), so the generic
    # "response has data envelope" assertion every other request gets would always fail here --
    # these get a status+error-code assertion instead, so a full collection run is genuinely
    # all-green rather than relying on two hardcoded "expected failures" the CI step has to know
    # to ignore.
    ENDPOINTS.append(dict(
        folder=folder, name=name, method=method, path=path, auth=auth, body=body,
        resp=resp, status=status, status_text=status_text, tests=tests or [], query=query or [], desc=desc,
        expect_error=expect_error, form=form, pdf=pdf,
    ))

NOW = "2026-10-01T10:15:30Z"

# ============================== AUTH ==============================
ep("01 - Auth", "Register Tenant", "POST", "/api/v1/auth/register-tenant", auth="noauth",
   body={"companyName": "Apex Industries", "adminEmail": "admin-{{$guid}}@apex.com", "adminDisplayName": "Alice Admin", "password": "password123"},
   resp={"data": {"tenantId": "{{tenantId}}", "userId": "{{userId}}", "companyName": "Apex Industries", "adminEmail": "admin-3f9c2e10@apex.com",
                  "tokenResponse": {"accessToken": "{{accessToken}}", "refreshToken": "{{refreshToken}}", "tokenType": "Bearer",
                                     "expiresInSeconds": 3600, "activeTenantId": "{{tenantId}}", "userId": "{{userId}}"}}},
   tests=[
       "pm.test('status is 200', () => pm.response.to.have.status(200));",
       "const json = pm.response.json();",
       "pm.collectionVariables.set('tenantId', json.data.tenantId);",
       "pm.collectionVariables.set('userId', json.data.userId);",
       "pm.collectionVariables.set('registeredEmail', json.data.adminEmail);",
       "pm.collectionVariables.set('registeredPassword', 'password123');",
       "pm.collectionVariables.set('accessToken', json.data.tokenResponse.accessToken);",
       "pm.collectionVariables.set('refreshToken', json.data.tokenResponse.refreshToken);",
   ],
   desc="Self-service root-tenant bootstrap. Run this first -- every other request depends on the token/tenant/credentials it creates. "
        "The email uses Postman's {{$guid}} dynamic variable so re-running this request never collides with a previous run's account -- "
        "no need to reset the database between test passes. Every later request that needs these credentials (Login, Forgot Password) "
        "reads them back from the registeredEmail/registeredPassword variables this sets, instead of a hardcoded literal, so they never drift out of sync.")

ep("01 - Auth", "Login", "POST", "/api/v1/auth/login", auth="noauth",
   body={"email": "{{registeredEmail}}", "password": "{{registeredPassword}}"},
   resp={"data": {"accessToken": "{{accessToken}}", "refreshToken": "{{refreshToken}}", "tokenType": "Bearer",
                  "expiresInSeconds": 3600, "activeTenantId": "{{tenantId}}", "userId": "{{userId}}"}},
   tests=[
       "if (pm.response.code === 200) {",
       "    const json = pm.response.json();",
       "    pm.collectionVariables.set('accessToken', json.data.accessToken);",
       "    pm.collectionVariables.set('refreshToken', json.data.refreshToken);",
       "}",
   ],
   desc="Uses the credentials Register Tenant just captured -- run that first in the same session, or this will 401.")

ep("01 - Auth", "Refresh Token", "POST", "/api/v1/auth/refresh", auth="noauth",
   body={"refreshToken": "{{refreshToken}}"},
   resp={"data": {"accessToken": "{{accessToken}}", "refreshToken": "{{refreshToken}}", "tokenType": "Bearer",
                  "expiresInSeconds": 3600, "activeTenantId": "{{tenantId}}", "userId": "{{userId}}"}},
   tests=[
       "if (pm.response.code === 200) {",
       "    const json = pm.response.json();",
       "    pm.collectionVariables.set('accessToken', json.data.accessToken);",
       "    pm.collectionVariables.set('refreshToken', json.data.refreshToken);",
       "}",
   ],
   desc="Refresh tokens are single-use -- the old one is revoked as soon as this succeeds.")

ep("01 - Auth", "Get Current Actor (/me)", "GET", "/api/v1/me",
   resp={"data": {"userId": "{{userId}}", "tenantId": "{{tenantId}}", "email": "admin-3f9c2e10@apex.com", "displayName": "Alice Admin"}})

ep("01 - Auth", "Switch Tenant", "POST", "/api/v1/auth/switch-tenant",
   body={"targetTenantId": "{{tenantId}}"},
   resp={"data": {"accessToken": "{{accessToken}}", "refreshToken": "{{refreshToken}}", "tokenType": "Bearer",
                  "expiresInSeconds": 3600, "activeTenantId": "{{tenantId}}", "userId": "{{userId}}"}})

ep("01 - Auth", "Change Password", "POST", "/api/v1/auth/change-password",
   body={"currentPassword": "{{registeredPassword}}", "newPassword": "newPassword456"},
   resp={"data": None},
   tests=[
       "if (pm.response.code === 200) {",
       "    pm.collectionVariables.set('registeredPassword', 'newPassword456');",
       "}",
   ],
   desc="Returns an empty data payload on success. Updates registeredPassword so a later Login in the same run still works.")

ep("01 - Auth", "Forgot Password", "POST", "/api/v1/auth/forgot-password", auth="noauth",
   body={"email": "{{registeredEmail}}"},
   resp={"data": {"message": "If an account exists with that email address, password reset instructions have been sent.", "status": "PENDING_VERIFICATION"}})

ep("01 - Auth", "Reset Password", "POST", "/api/v1/auth/reset-password", auth="noauth",
   body={"resetToken": "RST-482913", "newPassword": "newPassword456"},
   resp={"data": {"message": "Password has been successfully reset. You can now log in with your new password.", "status": "SUCCESS"}},
   expect_error={"status": 400, "code": "INVALID_RESET_TOKEN"},
   desc="resetToken comes from the EMAIL notification queued by Forgot Password (check notification_outbox while testing locally) -- "
        "this collection uses a placeholder token, so a full run is expected to get 400 INVALID_RESET_TOKEN here, asserted below rather "
        "than just tolerated.")

# ============================== TENANTS & MEMBERS ==============================
ep("02 - Tenants & Members", "List Child Tenants", "GET", "/api/v1/tenants",
   resp={"data": []})

ep("02 - Tenants & Members", "Create Child Tenant", "POST", "/api/v1/tenants",
   body={"name": "Apex Industries - Lagos Branch"},
   resp={"data": {"id": "{{childTenantId}}", "parentTenantId": "{{tenantId}}", "name": "Apex Industries - Lagos Branch"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('childTenantId', json.data.id);"])

ep("02 - Tenants & Members", "List Tenant Roles", "GET", "/api/v1/tenants/roles",
   resp={"data": [
       {"id": "{{adminRoleId}}", "name": "Administrator", "description": "System-seeded Administrator role", "permissions": ["accounting.manage", "accounting.read", "cashier.sales.manage", "cashier.sales.read", "factory.costing.approve", "factory.costing.read", "factory.production.manage", "factory.recipes.manage", "factory.setup.manage", "factory.trade.manage", "iam.roles.manage", "iam.users.assign", "iam.users.manage", "inventory.adjustments.manage", "inventory.stock.manage", "inventory.stock.read", "inventory.transfers.manage", "otp.issue", "settings.manage", "showroom.stock.manage", "showroom.stock.read", "tenants.create_child"]},
       {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper", "description": "System-seeded Stock Keeper role", "permissions": ["inventory.adjustments.manage", "inventory.stock.manage", "inventory.stock.read", "inventory.transfers.manage", "showroom.stock.manage", "showroom.stock.read"]},
   ]},
   tests=[
       "const json = pm.response.json();",
       "const admin = json.data.find(r => r.name === 'Administrator');",
       "const stockKeeper = json.data.find(r => r.name === 'Stock Keeper');",
       "if (admin) pm.collectionVariables.set('adminRoleId', admin.id);",
       "if (stockKeeper) pm.collectionVariables.set('stockKeeperRoleId', stockKeeper.id);",
   ],
   desc="Run after Register Tenant to capture the seeded role IDs used by Invite Member below.")

ep("02 - Tenants & Members", "Create Custom Role", "POST", "/api/v1/tenants/roles",
   body={"name": "Showroom Supervisor", "description": "Custom role for showroom floor supervisors", "permissions": ["showroom.stock.read", "showroom.stock.manage"]},
   resp={"data": {"id": "{{customRoleId}}", "name": "Showroom Supervisor", "description": "Custom role for showroom floor supervisors", "permissions": ["showroom.stock.manage", "showroom.stock.read"]}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('customRoleId', json.data.id);"])

ep("02 - Tenants & Members", "Invite Member", "POST", "/api/v1/tenants/members",
   body={"email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": "+2348000000000", "roleId": "{{stockKeeperRoleId}}", "locationId": "{{factoryId}}"},
   resp={"data": {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": "+2348000000000",
                  "status": "ACTIVE", "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "locationId": "{{factoryId}}",
                  "accessScope": "SINGLE_LOCATION", "invitedAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('memberUserId', json.data.userId);"],
   desc="Run Factory Setup > Create Factory first so {{factoryId}} is populated, or leave locationId null for ALL_LOCATIONS.")

ep("02 - Tenants & Members", "List Members", "GET", "/api/v1/tenants/members",
   resp={"data": [
       {"userId": "{{userId}}", "email": "admin@apex.com", "displayName": "Alice Admin", "phone": None, "status": "ACTIVE",
        "role": {"id": "{{adminRoleId}}", "name": "Administrator"}, "locationId": None, "accessScope": "ALL_LOCATIONS", "invitedAt": NOW},
       {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": "+2348000000000", "status": "ACTIVE",
        "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "locationId": "{{factoryId}}", "accessScope": "SINGLE_LOCATION", "invitedAt": NOW},
   ]})

ep("02 - Tenants & Members", "Update Member Status", "PUT", "/api/v1/tenants/members/{{memberUserId}}/status",
   body={"status": "SUSPENDED"},
   resp={"data": {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": None, "status": "SUSPENDED",
                  "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "locationId": "{{factoryId}}", "accessScope": "SINGLE_LOCATION", "invitedAt": NOW}})

ep("02 - Tenants & Members", "Update Member Location", "PUT", "/api/v1/tenants/members/{{memberUserId}}/location",
   body={"locationId": None},
   resp={"data": {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": None, "status": "ACTIVE",
                  "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "locationId": None, "accessScope": "ALL_LOCATIONS", "invitedAt": NOW}},
   desc="null locationId grants ALL_LOCATIONS access scope.")

# ============================== OTP ==============================
ep("03 - OTP", "Request OTP Challenge", "POST", "/api/v1/otp/challenges",
   body={"purpose": "QUANTITY_ADJUSTMENT", "targetReference": "cocoa-writeoff"},
   resp={"data": {"challengeId": "{{otpChallengeId}}", "status": "AWAITING", "expiresAt": None}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('otpChallengeId', json.data.challengeId);"],
   desc="purpose is QUANTITY_ADJUSTMENT or WALLET_WITHDRAWAL. targetReference is any string you choose to describe the action.")

ep("03 - OTP", "Issue OTP Code (Admin)", "POST", "/api/v1/otp/challenges/{{otpChallengeId}}/issue",
   resp={"data": {"challengeId": "{{otpChallengeId}}", "code": "{{otpCode}}", "expiresAt": "2026-10-01T10:30:30Z"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('otpCode', json.data.code);"],
   desc="Only a user holding otp.issue (Administrator by default) can call this. The plaintext code is returned exactly once.")

ep("03 - OTP", "Verify OTP Code", "POST", "/api/v1/otp/challenges/{{otpChallengeId}}/verify",
   body={"code": "{{otpCode}}"},
   resp={"data": {"verified": True}},
   desc="Must be called by the same user who requested the challenge.")

# ============================== VAT CONTROL ==============================
ep("04 - VAT Control", "List VAT Categories", "GET", "/api/v1/account/vat-control/vat-categories",
   resp={"data": [
       {"id": "{{standardRateVatCategoryId}}", "code": "STANDARD_RATE", "name": "Standard Rate", "rate": 7.50, "inputVatRecoverable": True,
        "description": "Standard-rated supplies under the Nigeria Tax Act 2025: manufactured goods, professional/consulting services, digital services, retail sales, and most B2B transactions. VAT charged at 7.5%, fully recoverable as input VAT.",
        "nrsTaxCategoryCode": "STANDARD_VAT"},
       {"id": "{{zeroRatedVatCategoryId}}", "code": "ZERO_RATED", "name": "Zero-Rated", "rate": 0.00, "inputVatRecoverable": True,
        "description": "Zero-rated supplies under the Nigeria Tax Act 2025: no VAT charged on the final sale (0%), but input VAT on related purchases remains fully recoverable. Includes exports, basic staples, textbooks and school supplies, and tuition fees.",
        "nrsTaxCategoryCode": "ZERO_VAT"},
       {"id": "{{exemptVatCategoryId}}", "code": "EXEMPT", "name": "Exempt", "rate": 0.00, "inputVatRecoverable": False,
        "description": "Exempt supplies under the Nigeria Tax Act 2025: no VAT charged, and input VAT on related costs is NOT recoverable. Includes most financial services, residential rent, certain land/building sales, and specified government services.",
        "nrsTaxCategoryCode": "EXEMPTED"},
   ]},
   tests=[
       "const json = pm.response.json();",
       "const standard = json.data.find(c => c.code === 'STANDARD_RATE');",
       "const zero = json.data.find(c => c.code === 'ZERO_RATED');",
       "const exempt = json.data.find(c => c.code === 'EXEMPT');",
       "if (standard) pm.collectionVariables.set('standardRateVatCategoryId', standard.id);",
       "if (zero) pm.collectionVariables.set('zeroRatedVatCategoryId', zero.id);",
       "if (exempt) pm.collectionVariables.set('exemptVatCategoryId', exempt.id);",
   ],
   desc="System-seeded, global, never tenant-created -- see V5__tax_classification.sql. Always exactly these 3 rows (NRS / Nigeria Tax Act 2025, effective 1 Jan 2026).")

ep("04 - VAT Control", "List HSN Sections", "GET", "/api/v1/account/vat-control/hsn-sections",
   resp={"data": [
       {"id": "{{hsnSectionId}}", "code": "VI", "chapterRange": "28-38", "name": "Products of the chemical or allied industries"},
       {"id": uid(), "code": "IV", "chapterRange": "16-24", "name": "Prepared foodstuffs; beverages, spirits and vinegar; tobacco and manufactured tobacco substitutes"},
   ]},
   tests=[
       "const json = pm.response.json();",
       "const chemicals = json.data.find(s => s.code === 'VI');",
       "if (chemicals) pm.collectionVariables.set('hsnSectionId', chemicals.id);",
   ],
   desc="WCO Harmonized System, 21 sections (chapter-level detail is out of scope until an authoritative Nigeria Customs tariff dataset is supplied -- see the migration's scope note). Captures Section VI (chemicals) to match this collection's Cocoa Powder/chemicals sample data.")

ep("04 - VAT Control", "List ISIC Sections", "GET", "/api/v1/account/vat-control/isic-sections",
   resp={"data": [
       {"id": "{{isicSectionId}}", "code": "C", "name": "Manufacturing"},
       {"id": uid(), "code": "A", "name": "Agriculture, forestry and fishing"},
   ]},
   tests=[
       "const json = pm.response.json();",
       "const manufacturing = json.data.find(s => s.code === 'C');",
       "if (manufacturing) pm.collectionVariables.set('isicSectionId', manufacturing.id);",
   ],
   desc="UN Statistics Division, ISIC Rev.4, 21 sections. Captures Section C (Manufacturing) to match this factory-floor sample data.")

ep("04 - VAT Control", "Get Tenant VAT Categories", "GET", "/api/v1/account/vat-control/tenant-vat-categories",
   resp={"data": [
       {"vatCategoryId": "{{standardRateVatCategoryId}}", "code": "STANDARD_RATE", "name": "Standard Rate", "rate": 7.50, "isActive": False, "activatedAt": "1970-01-01T00:00:00Z"},
       {"vatCategoryId": "{{zeroRatedVatCategoryId}}", "code": "ZERO_RATED", "name": "Zero-Rated", "rate": 0.00, "isActive": False, "activatedAt": "1970-01-01T00:00:00Z"},
       {"vatCategoryId": "{{exemptVatCategoryId}}", "code": "EXEMPT", "name": "Exempt", "rate": 0.00, "isActive": False, "activatedAt": "1970-01-01T00:00:00Z"},
   ]},
   desc="Every tenant starts with none of the 3 categories activated -- run Select Tenant VAT Categories below before registering any stock item, which requires an active vatCategoryId.")

ep("04 - VAT Control", "Select Tenant VAT Categories", "PUT", "/api/v1/account/vat-control/tenant-vat-categories",
   body={"vatCategoryCodes": ["STANDARD_RATE", "ZERO_RATED", "EXEMPT"]},
   resp={"data": [
       {"vatCategoryId": "{{standardRateVatCategoryId}}", "code": "STANDARD_RATE", "name": "Standard Rate", "rate": 7.50, "isActive": True, "activatedAt": NOW},
       {"vatCategoryId": "{{zeroRatedVatCategoryId}}", "code": "ZERO_RATED", "name": "Zero-Rated", "rate": 0.00, "isActive": True, "activatedAt": NOW},
       {"vatCategoryId": "{{exemptVatCategoryId}}", "code": "EXEMPT", "name": "Exempt", "rate": 0.00, "isActive": True, "activatedAt": NOW},
   ]},
   desc="Replaces the tenant's active VAT category set with exactly the codes given -- this call activates all 3. A stock item's vatCategoryId must be one the tenant has activated here, or registration fails with VAT_CATEGORY_NOT_ACTIVE.")

# ============================== SETTINGS ==============================
ep("05 - Settings", "Get Company Settings", "GET", "/api/v1/settings/company",
   resp={"data": {"tenantId": "{{tenantId}}", "companyName": "ZifIQ Operations Ltd", "primaryEmail": "info@zifiq.com",
                  "primaryPhone": "+1-800-555-ZIF", "taxRegistrationNumber": "TAX-9948120",
                  "headOfficeAddress": "Suite 400, Innovation Tower, Central District", "updatedAt": NOW}})

ep("05 - Settings", "Update Company Settings", "PUT", "/api/v1/settings/company",
   body={"companyName": "Apex Industries Ltd", "primaryEmail": "info@apex.com", "primaryPhone": "+1-800-555-0100",
         "taxRegistrationNumber": "TAX-1122334", "headOfficeAddress": "12 Industrial Way, Lagos"},
   resp={"data": {"tenantId": "{{tenantId}}", "companyName": "Apex Industries Ltd", "primaryEmail": "info@apex.com",
                  "primaryPhone": "+1-800-555-0100", "taxRegistrationNumber": "TAX-1122334",
                  "headOfficeAddress": "12 Industrial Way, Lagos", "updatedAt": NOW}})

# ============================== FACTORY SETUP ==============================
ep("06 - Factory Setup", "Create Factory", "POST", "/api/v1/factory/setup/factories",
   body={"name": "Apex Plant A", "address": "12 Industrial Way, Lagos", "state": "Lagos", "phone": "+234-800-100-0001",
         "vatRate": 7.5, "vatEnabled": True, "vatRegistrationNumber": "VAT-APEX-001"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A", "address": "12 Industrial Way, Lagos", "state": "Lagos",
                  "phone": "+234-800-100-0001", "managerUserId": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('factoryId', json.data.id);"],
   desc="code auto-generates (FAC-1, FAC-2, ...) when omitted.")

ep("06 - Factory Setup", "Create Second Factory (for Transfers/Trade)", "POST", "/api/v1/factory/setup/factories",
   body={"name": "Apex Plant B", "address": "4 Coastal Road, Port Harcourt", "state": "Rivers"},
   resp={"data": {"id": "{{factoryId2}}", "code": "FAC-2", "name": "Apex Plant B", "address": "4 Coastal Road, Port Harcourt",
                  "state": "Rivers", "phone": None, "managerUserId": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": None, "status": "ACTIVE"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('factoryId2', json.data.id);"])

ep("06 - Factory Setup", "List Factories", "GET", "/api/v1/factory/setup/factories",
   resp={"data": [{"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A", "address": "12 Industrial Way, Lagos", "state": "Lagos",
                   "phone": "+234-800-100-0001", "managerUserId": None, "vatRate": 7.5, "vatEnabled": True,
                   "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}]})

ep("06 - Factory Setup", "Update Factory", "PUT", "/api/v1/factory/setup/factories/{{factoryId}}",
   body={"name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos", "state": "Lagos", "phone": "+234-800-100-0099",
         "vatRate": 7.5, "vatEnabled": True, "vatRegistrationNumber": "VAT-APEX-001"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos",
                  "state": "Lagos", "phone": "+234-800-100-0099", "managerUserId": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}})

ep("06 - Factory Setup", "Update Factory Status", "PUT", "/api/v1/factory/setup/factories/{{factoryId}}/status",
   body={"status": "SUSPENDED"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos",
                  "state": "Lagos", "phone": "+234-800-100-0099", "managerUserId": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "SUSPENDED"}})

ep("06 - Factory Setup", "Reactivate Factory", "PUT", "/api/v1/factory/setup/factories/{{factoryId}}/status",
   body={"status": "ACTIVE"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos",
                  "state": "Lagos", "phone": "+234-800-100-0099", "managerUserId": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}},
   desc="Back to ACTIVE: a SUSPENDED location can't move stock (transfers, adjustments), and the following folders use this factory.")

ep("06 - Factory Setup", "Register Unit of Measure", "POST", "/api/v1/factory/setup/units",
   body={"unitCode": "kg", "unitName": "Kilogram", "symbol": "kg"},
   resp={"data": {"id": "{{unitId}}", "unitCode": "kg", "unitName": "Kilogram", "symbol": "kg"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('unitId', json.data.id);"])

ep("06 - Factory Setup", "List Units of Measure", "GET", "/api/v1/factory/setup/units",
   resp={"data": [{"id": "{{unitId}}", "unitCode": "kg", "unitName": "Kilogram", "symbol": "kg"}]})

ep("06 - Factory Setup", "Register Category", "POST", "/api/v1/factory/setup/categories",
   body={"name": "Chemicals", "type": "RAW_MATERIAL", "description": "Raw chemical inputs"},
   resp={"data": {"id": "{{categoryId}}", "name": "Chemicals", "type": "RAW_MATERIAL", "description": "Raw chemical inputs"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('categoryId', json.data.id);"],
   desc="type is one of FINISHED_PRODUCT, RAW_MATERIAL, PACKAGING.")

ep("06 - Factory Setup", "List Categories", "GET", "/api/v1/factory/setup/categories",
   resp={"data": [{"id": "{{categoryId}}", "name": "Chemicals", "type": "RAW_MATERIAL", "description": "Raw chemical inputs"}]})

ep("06 - Factory Setup", "Register Supplier", "POST", "/api/v1/factory/setup/suppliers",
   body={"supplierCode": None, "companyName": "Beta Chemical Supplies", "contactEmail": "sales@betachem.com", "phone": "+234-800-200-0002"},
   resp={"data": {"id": "{{supplierId}}", "supplierCode": "SUP-1", "companyName": "Beta Chemical Supplies",
                  "contactEmail": "sales@betachem.com", "phone": "+234-800-200-0002"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('supplierId', json.data.id);"])

ep("06 - Factory Setup", "List Suppliers", "GET", "/api/v1/factory/setup/suppliers",
   resp={"data": [{"id": "{{supplierId}}", "supplierCode": "SUP-1", "companyName": "Beta Chemical Supplies",
                   "contactEmail": "sales@betachem.com", "phone": "+234-800-200-0002"}]})

ep("06 - Factory Setup", "Register Manufacturer", "POST", "/api/v1/factory/setup/manufacturers",
   body={"manufacturerCode": None, "name": "Gamma Manufacturing Co", "country": "Nigeria", "contactEmail": "info@gammamfg.com",
         "phone": "+234-800-300-0003", "produces": "Emulsifiers, preservatives"},
   resp={"data": {"id": "{{manufacturerId}}", "manufacturerCode": "MFR-1", "name": "Gamma Manufacturing Co", "country": "Nigeria",
                  "contactEmail": "info@gammamfg.com", "phone": "+234-800-300-0003", "produces": "Emulsifiers, preservatives", "status": "ACTIVE"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('manufacturerId', json.data.id);"])

ep("06 - Factory Setup", "List Manufacturers", "GET", "/api/v1/factory/setup/manufacturers",
   resp={"data": [{"id": "{{manufacturerId}}", "manufacturerCode": "MFR-1", "name": "Gamma Manufacturing Co", "country": "Nigeria",
                   "contactEmail": "info@gammamfg.com", "phone": "+234-800-300-0003", "produces": "Emulsifiers, preservatives", "status": "ACTIVE"}]})

# ============================== INVENTORY STOCK ==============================
ep("07 - Inventory Stock", "Register Raw Material", "POST", "/api/v1/inventory/stock-items",
   body={"locationId": "{{factoryId}}", "itemType": "RAW_MATERIAL", "code": None, "name": "Cocoa Powder",
         "categoryId": "{{categoryId}}", "brand": None, "unitOfMeasureId": "{{unitId}}", "reorderLevel": 10,
         "barcode": None, "landingCost": 5.00, "markupCost": None,
         "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}"},
   resp={"data": {"id": "{{rawMaterialId}}", "locationId": "{{factoryId}}", "itemType": "RAW_MATERIAL", "code": "ITM-1",
                  "name": "Cocoa Powder", "categoryId": "{{categoryId}}", "brand": None, "unitOfMeasureId": "{{unitId}}",
                  "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                  "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}",
                  "status": "ACTIVE", "createdAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('rawMaterialId', json.data.id);"],
   desc="itemType is one of FINISHED_PRODUCT, RAW_MATERIAL, IN_HOUSE_RECIPE, OUTSOURCED. vatCategoryId/hsnSectionId/isicSectionId are required on every product -- run 04 - VAT Control first (List VAT Categories, List HSN Sections, List ISIC Sections, Select Tenant VAT Categories) to populate these variables. vatCategoryId must be one the active tenant has activated, or this returns 400 VAT_CATEGORY_NOT_ACTIVE.")

ep("07 - Inventory Stock", "Register Finished Product", "POST", "/api/v1/inventory/stock-items",
   body={"locationId": "{{factoryId}}", "itemType": "FINISHED_PRODUCT", "code": None, "name": "Premium Blend",
         "categoryId": None, "brand": None, "unitOfMeasureId": "{{unitId}}", "reorderLevel": 5, "barcode": None,
         "landingCost": None, "markupCost": None,
         "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}"},
   resp={"data": {"id": "{{finishedProductId}}", "locationId": "{{factoryId}}", "itemType": "FINISHED_PRODUCT", "code": "ITM-2",
                  "name": "Premium Blend", "categoryId": None, "brand": None, "unitOfMeasureId": "{{unitId}}",
                  "reorderLevel": 5.000, "barcode": None, "landingCost": None, "markupCost": None,
                  "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}",
                  "status": "ACTIVE", "createdAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('finishedProductId', json.data.id);"])

ep("07 - Inventory Stock", "List Stock Items", "GET", "/api/v1/inventory/stock-items",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"id": "{{rawMaterialId}}", "locationId": "{{factoryId}}", "itemType": "RAW_MATERIAL", "code": "ITM-1",
                   "name": "Cocoa Powder", "categoryId": "{{categoryId}}", "brand": None, "unitOfMeasureId": "{{unitId}}",
                   "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                   "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}",
                   "status": "ACTIVE", "createdAt": NOW}]})

ep("07 - Inventory Stock", "Update Stock Item Status", "PUT", "/api/v1/inventory/stock-items/{{rawMaterialId}}/status",
   body={"status": "SUSPENDED"},
   resp={"data": {"id": "{{rawMaterialId}}", "locationId": "{{factoryId}}", "itemType": "RAW_MATERIAL", "code": "ITM-1",
                  "name": "Cocoa Powder", "categoryId": "{{categoryId}}", "brand": None, "unitOfMeasureId": "{{unitId}}",
                  "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                  "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}",
                  "status": "SUSPENDED", "createdAt": NOW}})

ep("07 - Inventory Stock", "Reactivate Stock Item", "PUT", "/api/v1/inventory/stock-items/{{rawMaterialId}}/status",
   body={"status": "ACTIVE"},
   resp={"data": {"id": "{{rawMaterialId}}", "locationId": "{{factoryId}}", "itemType": "RAW_MATERIAL", "code": "ITM-1",
                  "name": "Cocoa Powder", "categoryId": "{{categoryId}}", "brand": None, "unitOfMeasureId": "{{unitId}}",
                  "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                  "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}",
                  "status": "ACTIVE", "createdAt": NOW}},
   desc="Reverses the previous request -- everything from here on (Refill, Transfers, Production, Trade) needs this item ACTIVE.")

ep("07 - Inventory Stock", "Refill Finished Product", "POST", "/api/v1/inventory/stock/refill/finished-product",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{finishedProductId}}", "quantity": 50, "note": "Initial stocking"},
   resp={"data": {"ref": "RF-1", "stockItemId": "{{finishedProductId}}", "quantity": 50, "performedBy": "{{userId}}", "at": NOW}})

ep("07 - Inventory Stock", "Refill Raw Material (Receive)", "POST", "/api/v1/inventory/stock/refill/raw-material",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "supplierId": "{{supplierId}}",
         "manufacturerId": "{{manufacturerId}}", "quantity": 100, "unitCost": 5.00,
         "manufacturedDate": "2026-09-01", "expiryDate": "2027-09-01"},
   resp={"data": {"id": "{{stockReceiptId}}", "ref": "DL-1", "stockItemId": "{{rawMaterialId}}", "supplierId": "{{supplierId}}",
                  "manufacturerId": "{{manufacturerId}}", "quantity": 100.000, "unitCost": 5.00, "manufacturedDate": "2026-09-01",
                  "expiryDate": "2027-09-01", "verifiedByUserId": None, "offloadedAt": None, "condition": "PENDING_VERIFICATION"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('stockReceiptId', json.data.id);"],
   desc="This is also the Material/Recipe Receive Report's data source (same table, see List Stock Receipts).")

ep("07 - Inventory Stock", "List Stock Receipts (Material Receive Report)", "GET", "/api/v1/inventory/stock/receipts",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"id": "{{stockReceiptId}}", "ref": "DL-1", "stockItemId": "{{rawMaterialId}}", "supplierId": "{{supplierId}}",
                   "manufacturerId": "{{manufacturerId}}", "quantity": 100.000, "unitCost": 5.00, "manufacturedDate": "2026-09-01",
                   "expiryDate": "2027-09-01", "verifiedByUserId": None, "offloadedAt": None, "condition": "PENDING_VERIFICATION"}]})

ep("07 - Inventory Stock", "Verify Stock Receipt", "POST", "/api/v1/inventory/stock/receipts/{{stockReceiptId}}/verify",
   body={"condition": "ACCEPTED"},
   resp={"data": {"id": "{{stockReceiptId}}", "ref": "DL-1", "stockItemId": "{{rawMaterialId}}", "supplierId": "{{supplierId}}",
                  "manufacturerId": "{{manufacturerId}}", "quantity": 100.000, "unitCost": 5.00, "manufacturedDate": "2026-09-01",
                  "expiryDate": "2027-09-01", "verifiedByUserId": "{{userId}}", "offloadedAt": NOW, "condition": "ACCEPTED"}})

ep("07 - Inventory Stock", "Stock Card (per-transaction)", "GET", "/api/v1/inventory/stock/card/{{rawMaterialId}}",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"sn": 1, "date": "2026-10-01", "remark": "DL-1", "qtyIn": 100.000, "qtyOut": 0.000, "runningBalance": 100.000}]})

ep("07 - Inventory Stock", "Bin Card (daily totals)", "GET", "/api/v1/inventory/stock/bin-card/{{rawMaterialId}}",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"sn": 1, "date": "2026-10-01", "qtyIn": 100.000, "qtyOut": 0.000, "balance": 100.000}]})

ep("07 - Inventory Stock", "Comprehensive Stock Report", "GET", "/api/v1/inventory/stock/comprehensive-report",
   query=[{"key": "locationId", "value": "{{factoryId}}"}, {"key": "view", "value": "CATEGORY"}],
   resp={"data": [{"lineNumber": 1, "groupingLabel": "Chemicals", "stockDetail": "Cocoa Powder", "unitCost": 5.00,
                   "quantityLeft": 100.000, "totalCost": 500.00}]},
   desc="unitCost/totalCost show as 0 for callers without factory.costing.read.")

ep("07 - Inventory Stock", "Stock Movement Report", "GET", "/api/v1/inventory/stock/movement-report",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [
       {"sn": 1, "date": "2026-10-01", "locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "itemCode": "ITM-1",
        "itemName": "Cocoa Powder", "sourceType": "RECEIVE", "sourceReference": "DL-1", "qtyIn": 100.000, "qtyOut": 0.000},
       {"sn": 2, "date": "2026-10-01", "locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "itemCode": "ITM-1",
        "itemName": "Cocoa Powder", "sourceType": "TRANSFER_OUT", "sourceReference": "WB-1", "qtyIn": 0.000, "qtyOut": 10.000},
   ]},
   desc="Cross-item, cross-movement-type ledger view (unlike Stock Card, which is scoped to a single item) -- every stock-affecting action across every module writes to the same ledger this reads from. All filters (locationId, stockItemId, sourceType, from, to) are optional. sourceType is one of REFILL, RECEIVE, PRODUCTION_RUN, TRANSFER_OUT, TRANSFER_IN, QUANTITY_ADJUSTMENT, TRADE_OUT, TRADE_IN.")

ep("07 - Inventory Stock", "Re-Order Report", "GET", "/api/v1/inventory/stock/re-order-report",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": []},
   desc="Returns items at or below their reorderLevel. Empty here since the sample data stays above threshold.")

# ============================== INVENTORY OPERATIONS ==============================
ep("08 - Inventory Operations", "Dispatch Stock Transfer", "POST", "/api/v1/inventory/stock/transfers",
   body={"sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}", "driverName": "John Driver",
         "driverVehicle": "LAG-123-XY", "lines": [{"stockItemId": "{{rawMaterialId}}", "quantity": 10, "unitPrice": None}]},
   resp={"data": {"id": "{{transferId}}", "waybillRef": "WB-1", "sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}",
                  "status": "DISPATCHED", "dispatchedAt": NOW, "receivedAt": None,
                  "lines": [{"id": "{{transferLineId}}", "stockItemId": "{{rawMaterialId}}", "qtySent": 10.000, "qtyReceived": None, "qtyRejected": None}]}},
   tests=[
       "const json = pm.response.json();",
       "pm.collectionVariables.set('transferId', json.data.id);",
       "pm.collectionVariables.set('transferLineId', json.data.lines[0].id);",
       "pm.collectionVariables.set('waybillRef', json.data.waybillRef);",
   ],
   desc="Run Create Second Factory first so {{factoryId2}} is populated.")

ep("08 - Inventory Operations", "Receive Stock Transfer", "POST", "/api/v1/inventory/stock/transfers/{{transferId}}/receive",
   body={"lines": [{"stockTransferLineId": "{{transferLineId}}", "qtyReceived": 10, "qtyRejected": None}]},
   resp={"data": {"id": "{{transferId}}", "waybillRef": "WB-1", "sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}",
                  "status": "RECEIVED", "dispatchedAt": NOW, "receivedAt": NOW,
                  "lines": [{"id": "{{transferLineId}}", "stockItemId": "{{rawMaterialId}}", "qtySent": 10.000, "qtyReceived": 10.000, "qtyRejected": None}]}})

ep("08 - Inventory Operations", "List Stock Transfers", "GET", "/api/v1/inventory/stock/transfers",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"id": "{{transferId}}", "waybillRef": "WB-1", "sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}",
                   "status": "RECEIVED", "dispatchedAt": NOW, "receivedAt": NOW,
                   "lines": [{"id": "{{transferLineId}}", "stockItemId": "{{rawMaterialId}}", "qtySent": 10.000, "qtyReceived": 10.000, "qtyRejected": None}]}]})

ep("08 - Inventory Operations", "Post Quantity Adjustment (below OTP threshold)", "POST", "/api/v1/inventory/quantity-adjustments",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 3,
         "reason": "DAMAGED_BROKEN", "note": "Bag torn in storage", "otpChallengeId": None},
   resp={"data": {"ref": "QA-1", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 3, "reason": "DAMAGED_BROKEN",
                  "note": "Bag torn in storage", "performedBy": "{{userId}}", "at": NOW}},
   desc="Adjustments at or below the 50-unit threshold (FactoryOperationsService.ADJUSTMENT_OTP_THRESHOLD) need no OTP.")

ep("08 - Inventory Operations", "Post Quantity Adjustment (above threshold, OTP-gated)", "POST", "/api/v1/inventory/quantity-adjustments",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 60,
         "reason": "STOCKTAKE_VARIANCE", "note": "Annual stocktake correction", "otpChallengeId": "{{otpChallengeId}}"},
   resp={"data": {"ref": "QA-2", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 60, "reason": "STOCKTAKE_VARIANCE",
                  "note": "Annual stocktake correction", "performedBy": "{{userId}}", "at": NOW}},
   desc="Run the OTP folder's three requests first (request -> issue -> verify) so otpChallengeId is USED, or this returns 403 OTP_REQUIRED.")

ep("08 - Inventory Operations", "List Quantity Adjustments", "GET", "/api/v1/inventory/quantity-adjustments",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"ref": "QA-1", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 3, "reason": "DAMAGED_BROKEN",
                   "note": "Bag torn in storage", "performedBy": "{{userId}}", "at": NOW}]})

# ============================== FACTORY PRODUCTION ==============================
ep("09 - Factory Production", "Register Recipe", "POST", "/api/v1/factory/recipes",
   body={"locationId": "{{factoryId}}", "code": None, "name": "Premium Blend Base", "description": "Base recipe for Premium Blend",
         "sourcing": "IN_HOUSE_PRODUCTION", "yieldQty": 100, "yieldUnitId": "{{unitId}}", "shelfLifeDays": 365,
         "reorderLevel": 20, "ingredients": [{"rawMaterialStockItemId": "{{rawMaterialId}}", "quantity": 10, "unitId": "{{unitId}}"}]},
   resp={"data": {"id": "{{recipeId}}", "code": "RCP-1", "name": "Premium Blend Base", "sourcing": "IN_HOUSE_PRODUCTION",
                  "yieldQty": 100, "status": "ACTIVE",
                  "ingredients": [{"rawMaterialStockItemId": "{{rawMaterialId}}", "quantity": 10, "unitId": "{{unitId}}"}]}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('recipeId', json.data.id);"],
   desc="sourcing is IN_HOUSE_PRODUCTION or DIRECT_IMPORTATION.")

ep("09 - Factory Production", "List Recipes", "GET", "/api/v1/factory/recipes",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"id": "{{recipeId}}", "code": "RCP-1", "name": "Premium Blend Base", "sourcing": "IN_HOUSE_PRODUCTION",
                   "yieldQty": 100, "status": "ACTIVE",
                   "ingredients": [{"rawMaterialStockItemId": "{{rawMaterialId}}", "quantity": 10, "unitId": "{{unitId}}"}]}]})

ep("09 - Factory Production", "Run Cost Calculator (QC - blind)", "POST", "/api/v1/factory/production/cost-calculator",
   body={"locationId": "{{factoryId}}", "targetStockItemId": "{{finishedProductId}}", "recipeId": None, "batchSizeQty": 50,
         "materials": [{"stockItemId": "{{rawMaterialId}}", "quantity": 20}], "overheads": [{"label": "Labour", "amount": 30}]},
   resp={"data": {"id": "{{costingId}}", "targetStockItemId": "{{finishedProductId}}", "batchSizeQty": 50, "status": "PENDING",
                  "computedByUserId": "{{userId}}", "createdAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('costingId', json.data.id);"],
   desc="Deliberately omits computedUnitCost/computedTotalCost -- QC (the typical caller) completes costing blind per the PRD. Pass either materials[] or recipeId.")

ep("09 - Factory Production", "Get Costing Detail (Management/Admin only)", "GET", "/api/v1/factory/production/cost-calculator/{{costingId}}",
   resp={"data": {"id": "{{costingId}}", "targetStockItemId": "{{finishedProductId}}", "recipeId": None, "batchSizeQty": 50.000,
                  "materialsSnapshot": [{"stockItemId": "{{rawMaterialId}}", "name": "Cocoa Powder", "quantity": 20, "unitCost": 5.0}],
                  "overheadSnapshot": [{"label": "Labour", "amount": 30}], "computedUnitCost": 2.60, "computedTotalCost": 130.00,
                  "status": "PENDING", "computedByUserId": "{{userId}}", "approvedByUserId": None, "approvedAt": None, "rejectionReason": None}},
   desc="Requires factory.costing.read -- this is the one endpoint that reveals the figure QC's own calculator just produced.")

ep("09 - Factory Production", "Approve Costing", "POST", "/api/v1/factory/production/cost-calculator/{{costingId}}/approve",
   resp={"data": {"id": "{{costingId}}", "targetStockItemId": "{{finishedProductId}}", "recipeId": None, "batchSizeQty": 50.000,
                  "materialsSnapshot": [{"stockItemId": "{{rawMaterialId}}", "name": "Cocoa Powder", "quantity": 20, "unitCost": 5.0}],
                  "overheadSnapshot": [{"label": "Labour", "amount": 30}], "computedUnitCost": 2.60, "computedTotalCost": 130.00,
                  "status": "APPROVED", "computedByUserId": "{{userId}}", "approvedByUserId": "{{userId}}", "approvedAt": NOW, "rejectionReason": None}})

ep("09 - Factory Production", "Reject Costing (alternative to Approve)", "POST", "/api/v1/factory/production/cost-calculator/{{costingId}}/reject",
   body={"rejectionReason": "Material substitution needed -- cost too high"},
   resp={"data": {"id": "{{costingId}}", "targetStockItemId": "{{finishedProductId}}", "recipeId": None, "batchSizeQty": 50.000,
                  "materialsSnapshot": [{"stockItemId": "{{rawMaterialId}}", "name": "Cocoa Powder", "quantity": 20, "unitCost": 5.0}],
                  "overheadSnapshot": [{"label": "Labour", "amount": 30}], "computedUnitCost": 2.60, "computedTotalCost": 130.00,
                  "status": "REJECTED", "computedByUserId": "{{userId}}", "approvedByUserId": None, "approvedAt": None,
                  "rejectionReason": "Material substitution needed -- cost too high"}},
   expect_error={"status": 409, "code": "ALREADY_DECIDED"},
   desc="Mutually exclusive with Approve -- in a full sequential run, Approve Costing (just above) already decided this same costing, "
        "so this is expected to get 409 ALREADY_DECIDED, asserted below. Run this in isolation against a fresh costing instead if you "
        "want to see the actual rejection path succeed.")

ep("09 - Factory Production", "Commit Production Run", "POST", "/api/v1/factory/production/runs",
   body={"productionCostingId": "{{costingId}}"},
   resp={"data": {"batchRef": "PB-1", "targetStockItemId": "{{finishedProductId}}", "quantityProduced": 50.000,
                  "unitLandingCost": 2.60, "totalLandingCost": 130.00, "runAt": NOW}},
   desc="productionCostingId must reference an APPROVED costing. Consumes the raw materials and credits the finished product in the same transaction.")

ep("09 - Factory Production", "List Production Runs", "GET", "/api/v1/factory/production/runs",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"batchRef": "PB-1", "targetStockItemId": "{{finishedProductId}}", "quantityProduced": 50.000,
                   "unitLandingCost": 2.60, "totalLandingCost": 130.00, "runAt": NOW}]})

ep("09 - Factory Production", "Total Cost for Selected Recipes", "GET", "/api/v1/factory/production/total-cost",
   query=[{"key": "recipeIds", "value": "{{recipeId}}"}],
   resp={"data": [{"recipeId": "{{recipeId}}", "recipeName": "Premium Blend Base", "qtyUsed": 50.000, "totalCost": 130.00}]})

ep("09 - Factory Production", "Register Special-Production Client", "POST", "/api/v1/factory/special-production/clients",
   body={"name": "Acme Contractors Ltd", "terms": "Net 30"},
   resp={"data": {"id": "{{specialClientId}}", "name": "Acme Contractors Ltd", "terms": "Net 30"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('specialClientId', json.data.id);"])

ep("09 - Factory Production", "Create Special Production Run", "POST", "/api/v1/factory/special-production/runs",
   body={"locationId": "{{factoryId}}", "clientId": "{{specialClientId}}", "targetStockItemId": None,
         "productDescription": "Custom emulsion batch for Acme", "quantity": 25, "unitId": "{{unitId}}",
         "unitCost": 4.00, "price": 7.50, "materials": [{"stockItemId": "{{rawMaterialId}}", "quantity": 5}]},
   resp={"data": {"id": "{{specialRunId}}", "orderRef": "SO-1", "batchRef": "SB-1", "clientId": "{{specialClientId}}",
                  "quantity": 25.000, "price": 7.50, "status": "IN_PRODUCTION"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('specialRunId', json.data.id);"])

ep("09 - Factory Production", "Update Special Production Status", "PUT", "/api/v1/factory/special-production/runs/{{specialRunId}}/status",
   body={"status": "AWAITING_DISPATCH"},
   resp={"data": {"id": "{{specialRunId}}", "orderRef": "SO-1", "batchRef": "SB-1", "clientId": "{{specialClientId}}",
                  "quantity": 25.000, "price": 7.50, "status": "AWAITING_DISPATCH"}},
   desc="status is one of IN_PRODUCTION, AWAITING_DISPATCH, DELIVERED.")

ep("09 - Factory Production", "List Special Production Runs", "GET", "/api/v1/factory/special-production/runs",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"id": "{{specialRunId}}", "orderRef": "SO-1", "batchRef": "SB-1", "clientId": "{{specialClientId}}",
                   "quantity": 25.000, "price": 7.50, "status": "AWAITING_DISPATCH"}]})

# ============================== FACTORY TRADING ==============================
ep("10 - Factory Trading", "Offer Trade", "POST", "/api/v1/factory/trades/offer",
   body={"fromLocationId": "{{factoryId2}}", "toLocationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}",
         "quantity": 5, "reason": "SURPLUS", "expiresAt": "2026-12-31T00:00:00Z"},
   resp={"data": {"id": "{{tradeOfferId}}", "ref": "FT-1", "fromLocationId": "{{factoryId2}}", "toLocationId": "{{factoryId}}",
                  "stockItemId": "{{rawMaterialId}}", "quantity": 5.000, "reason": "SURPLUS", "status": "PENDING",
                  "offeredAt": NOW, "expiresAt": "2026-12-31T00:00:00Z"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('tradeOfferId', json.data.id);"],
   desc="reason is one of SURPLUS, EXPIRY, CHANGEOVER, SCHEDULE, DISCONTINUED. Requires the receiving factory (fromLocationId) to actually hold the stock.")

ep("10 - Factory Trading", "Respond to Trade (Accept)", "POST", "/api/v1/factory/trades/{{tradeOfferId}}/respond",
   body={"decision": "ACCEPTED"},
   resp={"data": {"id": "{{tradeOfferId}}", "ref": "FT-1", "fromLocationId": "{{factoryId2}}", "toLocationId": "{{factoryId}}",
                  "stockItemId": "{{rawMaterialId}}", "quantity": 5.000, "reason": "SURPLUS", "status": "ACCEPTED",
                  "offeredAt": NOW, "expiresAt": "2026-12-31T00:00:00Z"}})

ep("10 - Factory Trading", "List Trade Offers", "GET", "/api/v1/factory/trades",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"id": "{{tradeOfferId}}", "ref": "FT-1", "fromLocationId": "{{factoryId2}}", "toLocationId": "{{factoryId}}",
                   "stockItemId": "{{rawMaterialId}}", "quantity": 5.000, "reason": "SURPLUS", "status": "ACCEPTED",
                   "offeredAt": NOW, "expiresAt": "2026-12-31T00:00:00Z"}]})

# ============================== NRS REFERENCE DATA ==============================
ep("11 - NRS Reference Data", "List NRS Tax Categories", "GET", "/api/v1/invoicing/reference/tax-categories",
   resp={"data": [
       {"code": "STANDARD_VAT", "name": "Standard Value-Added Tax"},
       {"code": "ZERO_VAT", "name": "Zero Value-Added Tax"},
       {"code": "EXEMPTED", "name": "Tax Exemption"},
       {"code": "IMPORT_DUTY", "name": "Import Duty"},
       {"code": "WITHHOLDING_TAX", "name": "Withholding Tax"},
   ]},
   desc="System-seeded, global -- see V6__nrs_reference_data.sql. The full 25-code enum NRS's real invoice schema accepts for tax_total[].tax_subtotal[].tax_category.id. ZifIQ's own vat_categories (see VAT Control) each map to one of these via nrsTaxCategoryCode.")

ep("11 - NRS Reference Data", "List NRS Invoice Type Codes", "GET", "/api/v1/invoicing/reference/invoice-type-codes",
   resp={"data": [
       {"code": "380", "name": "Credit Note", "description": "Credit Note"},
       {"code": "381", "name": "Commercial Invoice", "description": "Commercial Invoice"},
       {"code": "384", "name": "Debit Note", "description": "Debit Note"},
       {"code": "385", "name": "Self Billed Invoice", "description": "Self Billed Invoice"},
       {"code": "388", "name": "Factored Invoice", "description": "Factored Invoice"},
       {"code": "389", "name": "Statement of Account", "description": "Statement of Account"},
   ]},
   desc="NRS invoice_type_code (field 1.6 of the real Invoice Schema).")

ep("11 - NRS Reference Data", "List NRS Payment Means Codes", "GET", "/api/v1/invoicing/reference/payment-means-codes",
   resp={"data": [
       {"code": "10", "name": "Cash", "description": "Cash"},
       {"code": "20", "name": "Cheque", "description": "Cheque"},
       {"code": "30", "name": "Credit Transfer", "description": "Credit Transfer"},
       {"code": "31", "name": "Debit Transfer", "description": "Debit Transfer"},
       {"code": "42", "name": "ACH Credit", "description": "ACH Credit"},
       {"code": "43", "name": "ACH Debit", "description": "ACH Debit"},
   ]},
   desc="NRS payment_means_code (field 1.30 of the real Invoice Schema).")

ep("11 - NRS Reference Data", "List NRS Unit-of-Measure Codes", "GET", "/api/v1/invoicing/reference/unit-of-measure-codes",
   resp={"data": [
       {"code": "10", "name": "group", "description": "A unit of count defining the number of groups (group: set of items classified together)."},
       {"code": "20", "name": "twenty foot container", "description": "A unit of count defining the number of shipping containers that measure 20 foot in length."},
       {"code": "21", "name": "forty foot container", "description": "A unit of count defining the number of shipping containers that measure 40 foot in length."},
   ]},
   desc="UN/CEFACT Rec 20 style codes for invoice_line.price.price_unit. Distinct from a tenant's own Units of Measure (Factory Setup) -- this is NRS's fixed compliance code list. Sample only; see migration scope note.")

ep("11 - NRS Reference Data", "List HSN Codes (subheading level)", "GET", "/api/v1/invoicing/reference/hsn-codes",
   query=[{"key": "sectionCode", "value": "I"}],
   resp={"data": [
       {"code": "0101.21", "sectionCode": "I", "description": "Horses; live, pure-bred breeding animals"},
       {"code": "0102.90", "sectionCode": "I", "description": "Bovine animals; live, other than cattle and buffalo"},
       {"code": "1006.30", "sectionCode": "I", "description": "Cereals; rice, semi-milled or wholly milled, whether or not polished or glazed"},
   ]},
   desc="True dotted HS subheading codes (invoice_line.hsn_code for goods line items), optionally filtered by hsnSections code (see VAT Control). Sample only (WCO Chapter 01); see migration scope note.")

ep("11 - NRS Reference Data", "List ISIC Classes (4-digit level)", "GET", "/api/v1/invoicing/reference/isic-classes",
   query=[{"key": "sectionCode", "value": "A"}],
   resp={"data": [
       {"code": "0111", "sectionCode": "A", "description": "Growing of cereals (except rice), leguminous crops and oil seeds"},
       {"code": "0112", "sectionCode": "A", "description": "Growing of rice"},
       {"code": "0124", "sectionCode": "A", "description": "Growing of pome fruits and stone fruits"},
   ]},
   desc="True 4-digit ISIC Rev.4 class codes (invoice_line.isic_code for service line items), optionally filtered by isicSections code (see VAT Control). Sample only (Section A); see migration scope note.")

ep("11 - NRS Reference Data", "List Nigeria States", "GET", "/api/v1/invoicing/reference/states",
   resp={"data": [
       {"code": "NG-AB", "name": "Abia"},
       {"code": "NG-FC", "name": "Federal Capital Territory"},
       {"code": "NG-LA", "name": "Lagos"},
   ]},
   desc="postal_address.state. Seeded in full -- all 36 states + FCT (ISO 3166-2:NG).")

ep("11 - NRS Reference Data", "List Nigeria LGAs", "GET", "/api/v1/invoicing/reference/lgas",
   query=[{"key": "stateCode", "value": "NG-AB"}],
   resp={"data": [
       {"code": "NG-AB-ANO", "stateCode": "NG-AB", "name": "Aba North"},
       {"code": "NG-AB-UWE", "stateCode": "NG-AB", "name": "Ukwa West"},
   ]},
   desc="postal_address.lga, filtered by Nigeria state code. Only Abia state seeded so far (sample as supplied from the real NRS docs) -- Nigeria has 774 LGAs total; extend state-by-state as confirmed.")


# ============================== CUSTOMERS (docs/04) ==============================
# Ordered so each request sets up the state the next one needs; every id/ref is captured into a
# collection variable. Requests named "(... error ...)" deliberately demonstrate a business rule.
C = "12 - Customers"
CUST = {"id": "{{customerId}}", "code": "CU-1", "type": "ORDINARY_CUSTOMER", "isTemporary": False, "name": "Chidi Nwosu",
        "contactPerson": None, "phone": "0809 111 2222", "alternatePhone": None, "email": "chidi@example.com", "tin": None,
        "businessDescription": None, "streetAddress": None, "city": "Lagos", "stateCode": "NG-LA", "stateName": "Lagos",
        "lgaCode": None, "lgaName": None, "countryCode": "NG",
        "location": {"id": "{{customerLocationId}}", "code": "FAC-3", "name": "Customer Home Plant"},
        "correspondent": None, "dealerCategory": None, "creditLimit": 0.00, "walletBalance": 0.00, "depositBalance": 0.00,
        "lifetimeValue": 0.00, "lastPurchaseAt": None, "customerSince": "2026-10-08", "status": "ACTIVE", "statusReason": None,
        "userId": None, "customAttributes": {}, "createdAt": NOW}
DEALER = dict(CUST, id="{{dealerId}}", code="DLR-1", type="AUTHORISED_DEALER", name="Okafor & Sons Distribution 482",
              contactPerson="Tunde Okafor", phone="0803 412 7781", email="orders@okaforsons.ng", city=None, streetAddress="14 Ikorodu Rd",
              correspondent={"id": "{{correspondentId}}", "code": "CR-1", "name": "Bisi Adeyemi", "status": "ASSIGNED"},
              dealerCategory={"id": "{{goldCategoryId}}", "name": "Gold", "discountPct": 3.50}, creditLimit=1500000.00)
SPECIAL = dict(CUST, id="{{specialId}}", code="CU-3", type="SPECIAL_CUSTOMER", name="Adaeze Foods Ltd", contactPerson="Adaeze Obi",
               phone="0701 555 0101", email=None, creditLimit=250000.00)
SUMMARY = {k: DEALER[k] for k in ("id", "code", "type", "isTemporary", "name", "contactPerson", "phone", "stateCode", "stateName",
                                   "creditLimit", "walletBalance", "depositBalance", "lifetimeValue", "lastPurchaseAt", "customerSince", "status")}
SUMMARY.update({"locationId": "{{customerLocationId}}", "locationName": "Customer Home Plant", "correspondentId": "{{correspondentId}}",
                "correspondentName": "Bisi Adeyemi", "dealerCategoryName": "Gold"})


def PAGE(items, **extra):
    return {"data": dict({"items": items, "page": 0, "size": 50, "totalItems": len(items), "totalPages": 1,
                          "totalIsEstimate": False, "facets": None, "summary": None,
                          "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}, **extra)}


def ERR(code, message, details=None):
    return {"error": {"code": code, "message": message, "details": details or {}}}


CORR = {"id": "{{correspondentId}}", "code": "CR-1", "name": "Bisi Adeyemi", "phone": "0802 123 4567", "email": None,
        "region": "Lagos Mainland", "locationId": "{{customerLocationId}}", "locationName": "Customer Home Plant", "status": "ASSIGNED",
        "assignedAt": "2026-10-08", "customerCount": 1, "dealerCount": 1, "responsibilities": "Lagos dealers", "userLinked": False}
CORR2 = dict(CORR, id="{{correspondent2Id}}", code="CR-2", name="Emeka Eze", phone="0805 999 0000", region="South West",
             responsibilities="Ibadan and Abeokuta walk-in accounts", customerCount=0, dealerCount=0)
HISTORY_ROW = {"action": "ASSIGNED", "region": "Lagos Mainland", "locationId": "{{customerLocationId}}", "locationName": "Customer Home Plant",
               "effectiveFrom": "2026-10-08", "note": None, "performedByUserId": "{{userId}}", "createdAt": NOW}
CATEGORY = {"id": "{{diamondCategoryId}}", "name": "Diamond", "discountPct": 7.50, "suggestedCreditLimit": 5000000.00, "sortOrder": 5,
            "status": "ACTIVE", "dealerCount": 0}
ENTRY = {"id": "{{adjustmentEntryId}}", "customerId": "{{dealerId}}", "balanceType": "WALLET", "entryType": "ADJUSTMENT_CREDIT",
         "amount": 2500.00, "balanceAfter": 2500.00, "reference": "WLT-1"}
RECLASS = {"id": "{{reclassificationId}}", "customerId": "{{customerId}}", "customerCode": "CU-1", "customerName": "Chidi Nwosu",
           "fromType": "ORDINARY_CUSTOMER", "toType": "SPECIAL_CUSTOMER", "direction": "PROMOTION", "reason": "MET_PURCHASE_VOLUME",
           "note": "Crossed N5m in 90 days", "effectiveFrom": "2026-10-08", "status": "APPLIED", "fromCreditLimit": 0.00,
           "toCreditLimit": 0.00, "toDealerCategoryName": None, "walletBalanceCarriedOver": 0.00, "authorisedByUserId": "{{userId}}",
           "appliedAt": NOW, "createdAt": NOW}

# ---- A. Setup ----
ep(C, "A1 Create Customer Home Location", "POST", "/api/v1/factory/setup/factories",
   body={"code": None, "name": "Customer Home Plant", "address": "3 Allen Ave, Ikeja", "state": "Lagos", "phone": "+2348000000003",
         "vatRate": 7.5, "vatEnabled": True, "vatRegistrationNumber": None},
   resp={"data": {"id": "{{customerLocationId}}", "code": "FAC-3", "name": "Customer Home Plant", "status": "ACTIVE"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('customerLocationId', json.data.id);",
          "pm.collectionVariables.set('today', new Date(Date.now() + 3600 * 1000).toISOString().slice(0, 10));"],
   desc="Creates an ACTIVE location to home this folder's customers and correspondents at, so the folder can run on its own. "
        "Customers can be homed at an ACTIVE showroom or factory. Also stores today's date in the tenant's default timezone (Africa/Lagos) as {{today}}, "
        "used by A-today reclassification below.")

# ---- B. Dealer categories ----
ep(C, "B1 List Dealer Categories", "GET", "/api/v1/customers/dealer-categories",
   resp={"data": [{"id": "{{goldCategoryId}}", "name": "Gold", "discountPct": 3.50, "suggestedCreditLimit": 1500000.00, "sortOrder": 3, "status": "ACTIVE", "dealerCount": 0}]},
   tests=["const json = pm.response.json();",
          "pm.test('new tenant has the 4 default categories', () => pm.expect(json.data.map(c => c.name).sort()).to.eql(['Bronze', 'Gold', 'Platinum', 'Silver']));",
          "pm.collectionVariables.set('goldCategoryId', json.data.find(c => c.name === 'Gold').id);"],
   desc="Every new company starts with Platinum (5%, N3m), Gold (3.5%, N1.5m), Silver (2%, N500k) and Bronze (0%, N200k). "
        "Optional query: status=ACTIVE|INACTIVE, q=<name>. dealerCount = live dealers in the category. Perm customers.read.")

ep(C, "B2 Create Dealer Category", "POST", "/api/v1/customers/dealer-categories",
   body={"name": "Diamond", "discountPct": 7.5, "suggestedCreditLimit": 5000000, "sortOrder": 5},
   resp={"data": CATEGORY},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('diamondCategoryId', json.data.id);"],
   desc="Perm customers.categories.manage (Administrator). Names are unique per company (409 DUPLICATE_NAME).")

ep(C, "B3 Update Dealer Category", "PUT", "/api/v1/customers/dealer-categories/{{diamondCategoryId}}",
   body={"name": "Diamond", "discountPct": 8, "suggestedCreditLimit": 6000000, "sortOrder": 5},
   resp={"data": dict(CATEGORY, discountPct=8.00, suggestedCreditLimit=6000000.00)},
   desc="A new suggested credit limit applies to dealers registered or promoted from now on; existing dealers' limits don't change.")

ep(C, "B4 Deactivate Dealer Category", "PUT", "/api/v1/customers/dealer-categories/{{diamondCategoryId}}/status",
   body={"status": "INACTIVE"},
   resp={"data": dict(CATEGORY, discountPct=8.00, suggestedCreditLimit=6000000.00, status="INACTIVE")},
   desc="status=ACTIVE|INACTIVE. Inactive categories can't be given to new dealers. Deactivating a category dealers still use fails (see I1).")

# ---- C. Correspondents ----
ep(C, "C1 Register Correspondent (and assign)", "POST", "/api/v1/customers/correspondents",
   body={"name": "Bisi Adeyemi", "phone": "0802 123 4567", "email": None, "userId": None, "region": "Lagos Mainland",
         "locationId": "{{customerLocationId}}", "responsibilities": "Lagos dealers"},
   resp={"data": {"correspondent": dict(CORR, customerCount=0, dealerCount=0), "history": [HISTORY_ROW], "affectedCustomers": None}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('correspondentId', json.data.correspondent.id);",
          "pm.test('assigned in one step', () => pm.expect(json.data.correspondent.status).to.eql('ASSIGNED'));"],
   desc="Perm customers.correspondents.manage. Code CR-n. Giving region + locationId registers and assigns in one step; leave both out to register "
        "UNASSIGNED (C2). Regions come from the module setting customers.correspondent.regions (Settings > module settings). "
        "Optional userId links a staff login so the correspondent sees their own customers (customers.read.own).")

ep(C, "C2 Register Correspondent (unassigned)", "POST", "/api/v1/customers/correspondents",
   body={"name": "Emeka Eze", "phone": "0805 999 0000", "email": "emeka@example.com", "responsibilities": "Walk-in accounts"},
   resp={"data": {"correspondent": dict(CORR2, status="UNASSIGNED", region=None, locationId=None, locationName=None, assignedAt=None,
                                        responsibilities="Walk-in accounts", email="emeka@example.com"), "history": [], "affectedCustomers": None}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('correspondent2Id', json.data.correspondent.id);",
          "pm.test('starts unassigned', () => pm.expect(json.data.correspondent.status).to.eql('UNASSIGNED'));"])

ep(C, "C3 Register Correspondent (error: unknown region)", "POST", "/api/v1/customers/correspondents",
   body={"name": "Nobody", "phone": "08020000000", "region": "Atlantis", "locationId": "{{customerLocationId}}"},
   status=400, status_text="Bad Request",
   resp=ERR("INVALID_REGION", "Unknown region", {"allowed": "Lagos Mainland,Lagos Island,North Central,North West,South East,South South,South West"}),
   expect_error={"status": 400, "code": "INVALID_REGION"},
   desc="details.allowed lists the configured regions.")

ep(C, "C4 Assign Correspondent", "POST", "/api/v1/customers/correspondents/{{correspondent2Id}}/assign",
   body={"region": "Lagos Island", "locationId": "{{customerLocationId}}", "effectiveFrom": None, "note": "Covering Lagos Island"},
   resp={"data": {"correspondent": dict(CORR2, region="Lagos Island"), "history": [dict(HISTORY_ROW, region="Lagos Island", note="Covering Lagos Island")], "affectedCustomers": None}},
   desc="UNASSIGNED or DE_ASSIGNED -> ASSIGNED. effectiveFrom defaults to today. Wrong starting state -> 409 INVALID_STATUS_TRANSITION.")

ep(C, "C5 Reassign Correspondent", "POST", "/api/v1/customers/correspondents/{{correspondent2Id}}/reassign",
   body={"region": "South West", "locationId": "{{customerLocationId}}", "note": "Moved to Ibadan office"},
   resp={"data": {"correspondent": CORR2, "history": [dict(HISTORY_ROW, action="REASSIGNED", region="South West", note="Moved to Ibadan office"),
                                                      dict(HISTORY_ROW, region="Lagos Island", note="Covering Lagos Island")], "affectedCustomers": None}},
   desc="ASSIGNED only. Moves region/location; linked customers stay linked and the history keeps both rows.")

ep(C, "C6 Update Correspondent", "PUT", "/api/v1/customers/correspondents/{{correspondent2Id}}",
   body={"name": "Emeka Eze", "phone": "0805 999 0000", "email": "emeka@example.com", "userId": None,
         "responsibilities": "Ibadan and Abeokuta walk-in accounts"},
   resp={"data": {"correspondent": CORR2, "history": [], "affectedCustomers": None}},
   desc="Edits name/phone/e-mail/linked user/responsibilities only; region, location and status change through assign/reassign/de-assign.")

ep(C, "C7 Get Correspondent", "GET", "/api/v1/customers/correspondents/{{correspondent2Id}}",
   resp={"data": {"correspondent": CORR2, "history": [dict(HISTORY_ROW, action="REASSIGNED", region="South West"), HISTORY_ROW], "affectedCustomers": None}},
   tests=["const json = pm.response.json();", "pm.test('history is kept', () => pm.expect(json.data.history.length).to.eql(2));"],
   desc="Detail with the append-only assignment history, newest first.")

ep(C, "C8 List Correspondents", "GET", "/api/v1/customers/correspondents",
   query=[{"key": "status", "value": "ASSIGNED"}],
   resp={"data": [CORR, CORR2]},
   desc="Unpaged. Query: q (code, name, phone, e-mail, responsibilities), status (repeatable), region (repeatable), locationId, userLinked, hasCustomers, "
        "sort=name|customerCount|dealerCount|assignedAt.")

ep(C, "C9 Correspondent Lookup (picker)", "GET", "/api/v1/customers/correspondents/lookup",
   query=[{"key": "q", "value": "bisi"}, {"key": "assignedOnly", "value": "true"}],
   resp={"data": [CORR]},
   desc="For the correspondent picker on the customer form. assignedOnly defaults to true (only assigned correspondents can be linked).")

# ---- D. Register and edit customers ----
ep(C, "D1 Register Dealer", "POST", "/api/v1/customers",
   body={"type": "AUTHORISED_DEALER", "name": "Okafor & Sons Distribution {{$randomInt}}", "contactPerson": "Tunde Okafor",
         "phone": "0803 412 7781", "email": "orders@okaforsons.ng", "stateCode": "NG-LA", "streetAddress": "14 Ikorodu Rd",
         "locationId": "{{customerLocationId}}", "dealerCategoryId": "{{goldCategoryId}}", "correspondentId": "{{correspondentId}}"},
   resp={"data": DEALER},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('dealerId', json.data.id);",
          "pm.collectionVariables.set('dealerName', json.data.name);", "pm.collectionVariables.set('dealerCode', json.data.code);",
          "pm.test('credit limit defaults to the category suggestion', () => pm.expect(json.data.creditLimit).to.eql(1500000));"],
   desc="Perm customers.manage + customers.dealers.manage. Dealers need contactPerson, streetAddress, stateCode and an ACTIVE dealerCategoryId; "
        "creditLimit defaults to the category's suggestion. Code DLR-n. The phone is kept as typed and matched in any format (0803..., +234803..., with spaces). "
        "Funding a dealer's wallet is a separate Account call (not built yet). Other optional fields: alternatePhone, tin, businessDescription, city, "
        "lgaCode (must belong to stateCode), customerSince (<= today), userId (self-service login), customAttributes.")

ep(C, "D2 Register Dealer (error: duplicate name)", "POST", "/api/v1/customers",
   body={"type": "AUTHORISED_DEALER", "name": "{{dealerName}}", "contactPerson": "Someone", "phone": "08030000000",
         "stateCode": "NG-LA", "streetAddress": "1 Elsewhere", "dealerCategoryId": "{{goldCategoryId}}"},
   status=409, status_text="Conflict", resp=ERR("DUPLICATE_NAME", "'Okafor & Sons Distribution 482' is already in use", {"name": "Okafor & Sons Distribution 482"}),
   expect_error={"status": 409, "code": "DUPLICATE_NAME"},
   desc="Dealer names are unique (case-insensitive).")

ep(C, "D3 Register Dealer (error: no category)", "POST", "/api/v1/customers",
   body={"type": "AUTHORISED_DEALER", "name": "Category-less Traders", "contactPerson": "Someone", "phone": "08030000002",
         "stateCode": "NG-LA", "streetAddress": "2 Elsewhere"},
   status=400, status_text="Bad Request",
   resp=ERR("CATEGORY_REQUIRED_FOR_DEALER", "Authorised dealers need a dealer category (and only dealers may have one)"),
   expect_error={"status": 400, "code": "CATEGORY_REQUIRED_FOR_DEALER"})

ep(C, "D4 Register Ordinary Customer", "POST", "/api/v1/customers",
   body={"type": "ORDINARY_CUSTOMER", "name": "Chidi Nwosu", "phone": "0809 111 2222", "email": "chidi@example.com", "city": "Lagos",
         "stateCode": "NG-LA", "locationId": "{{customerLocationId}}"},
   resp={"data": CUST},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('customerId', json.data.id);"],
   desc="Code CU-n. Phone is required for every type except NO_CREDIT_WALKIN. Granting a creditLimit > 0 needs customers.dealers.manage.")

ep(C, "D5 Register Ordinary Customer (error: no phone)", "POST", "/api/v1/customers",
   body={"type": "ORDINARY_CUSTOMER", "name": "No Phone Person"},
   status=400, status_text="Bad Request", resp=ERR("PHONE_REQUIRED", "A phone number is required"),
   expect_error={"status": 400, "code": "PHONE_REQUIRED"})

ep(C, "D6 Register Special Customer", "POST", "/api/v1/customers",
   body={"type": "SPECIAL_CUSTOMER", "name": "Adaeze Foods Ltd", "contactPerson": "Adaeze Obi", "phone": "0701 555 0101",
         "stateCode": "NG-LA", "locationId": "{{customerLocationId}}", "creditLimit": 250000},
   resp={"data": SPECIAL},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('specialId', json.data.id);"],
   desc="Special Customers are the only type that can hold a deposit balance (see F4).")

ep(C, "D7 Register Walk-in Customer", "POST", "/api/v1/customers",
   body={"type": "NO_CREDIT_WALKIN", "name": "Walk-in Buyer"},
   resp={"data": dict(CUST, id="{{walkinId}}", code="CU-4", type="NO_CREDIT_WALKIN", name="Walk-in Buyer", phone=None, email=None,
                      city=None, stateCode=None, stateName=None, location=None)},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('walkinId', json.data.id);"],
   desc="No-credit walk-ins may be registered without a phone and never have a credit limit. (Temporary walk-ins are created automatically "
        "by Showroom returns, not through this endpoint.)")

ep(C, "D8 Register Walk-in Customer (error: credit limit)", "POST", "/api/v1/customers",
   body={"type": "NO_CREDIT_WALKIN", "name": "Credit Seeker", "creditLimit": 1000},
   status=400, status_text="Bad Request", resp=ERR("CREDIT_LIMIT_NOT_ALLOWED", "No-credit customers cannot have a credit limit"),
   expect_error={"status": 400, "code": "CREDIT_LIMIT_NOT_ALLOWED"})

ep(C, "D9 Get Customer", "GET", "/api/v1/customers/{{dealerId}}", resp={"data": DEALER},
   desc="Out-of-scope customers (another location for single-location staff, or not your customer for a correspondent) return 404 CUSTOMER_NOT_FOUND.")

ep(C, "D10 Update Customer", "PUT", "/api/v1/customers/{{customerId}}",
   body={"name": "Chidi Nwosu", "phone": "0809 111 2222", "alternatePhone": "0812 000 0000", "email": "chidi@example.com",
         "city": "Ikeja", "stateCode": "NG-LA", "locationId": "{{customerLocationId}}"},
   resp={"data": dict(CUST, alternatePhone="0812 000 0000", city="Ikeja")},
   desc="Send the whole form (fields left out are cleared). Type and dealer category change only through reclassification (G); customerSince "
        "never changes. Changing creditLimit needs customers.dealers.manage and notifies the other dealer managers.")

ep(C, "D11 Link Customer to Correspondent", "PUT", "/api/v1/customers/{{customerId}}/correspondent",
   body={"correspondentId": "{{correspondent2Id}}"},
   resp={"data": dict(CUST, alternatePhone="0812 000 0000", city="Ikeja",
                      correspondent={"id": "{{correspondent2Id}}", "code": "CR-2", "name": "Emeka Eze", "status": "ASSIGNED"})},
   desc="Perm customers.correspondents.manage. {\"correspondentId\": null} unlinks. The correspondent must be ASSIGNED (409 CORRESPONDENT_NOT_ASSIGNED).")

ep(C, "D12 Convert Temporary Customer (error: already registered)", "POST", "/api/v1/customers/{{customerId}}/register",
   body={"type": "ORDINARY_CUSTOMER", "name": "Chidi Nwosu", "phone": "0809 111 2222"},
   status=409, status_text="Conflict",
   resp=ERR("INVALID_STATUS_TRANSITION", "Only an active temporary walk-in customer can be registered this way; this one is already registered",
            {"from": "already registered", "to": "REGISTERED"}),
   expect_error={"status": 409, "code": "INVALID_STATUS_TRANSITION"},
   desc="Turns a temporary walk-in (auto-created by a Showroom return) into a registered customer of any type, keeping its CU code and wallet. "
        "Body = the register body. On a customer that isn't temporary it's refused, as here.")

# ---- E. Search, filters and pickers ----
ep(C, "E1 Search Customers (q, facets, highlight)", "GET", "/api/v1/customers",
   query=[{"key": "q", "value": "okafor"}, {"key": "facets", "value": "type,status"}, {"key": "highlight", "value": "true"}],
   resp=PAGE([SUMMARY], facets={"type": [{"value": "AUTHORISED_DEALER", "label": "Authorised Dealer", "count": 1}],
                                "status": [{"value": "ACTIVE", "label": "ACTIVE", "count": 1}]},
             summary={"count": 1, "walletBalanceTotal": 0.00, "depositBalanceTotal": 0.00, "lifetimeValueTotal": 0.00}),
   tests=["const json = pm.response.json();",
          "pm.test('dealer ranks first', () => pm.expect(json.data.items[0].id).to.eql(pm.collectionVariables.get('dealerId')));",
          "pm.test('matched words are highlighted', () => pm.expect(JSON.stringify(json.data.meta.matches)).to.include('<mark>'));"],
   desc="q searches code (DLR-42 or bare 42), phone in any format, e-mail, TIN, name and contact person (accent-insensitive, tolerates typos) and "
        "address text. Supports \"exact phrase\", -exclude, OR, and qualifiers like type:dealer status:watch state:NG-LA. "
        "facets=<keys> returns counts per value; highlight=true returns <mark>ed snippets in meta.matches; summary (on by default) totals the balances. "
        "No results? meta.suggestions offers close names. REMOVED and temporary customers are hidden unless you filter for them.")

ep(C, "E2 Search Customers by phone", "GET", "/api/v1/customers",
   query=[{"key": "q", "value": "08034127781"}],
   resp=PAGE([SUMMARY]),
   tests=["const json = pm.response.json();",
          "pm.test('phone matches regardless of format', () => pm.expect(json.data.items.map(c => c.id)).to.eql([pm.collectionVariables.get('dealerId')]));"],
   desc="The dealer was registered as \"0803 412 7781\"; 08034127781, +2348034127781 and 0803 412 7781 all find it.")

ep(C, "E3 Filter Customers (simple filters)", "GET", "/api/v1/customers",
   query=[{"key": "type", "value": "AUTHORISED_DEALER"}, {"key": "type", "value": "SPECIAL_CUSTOMER"},
          {"key": "creditLimit.gte", "value": "200000"}, {"key": "sort", "value": "creditLimit,desc"}],
   resp=PAGE([SUMMARY]),
   tests=["const json = pm.response.json();",
          "pm.test('dealer and special customer only', () => pm.expect(json.data.items.map(c => c.type).sort()).to.eql(['AUTHORISED_DEALER', 'SPECIAL_CUSTOMER']));"],
   desc="Repeat a parameter for OR (type=A&type=B). Suffixes: .gte/.lte/.gt/.lt, Min/Max, .from/.to, .isNull, key!=value. "
        "Other filters: status, isTemporary, locationId, correspondentId, dealerCategoryId, stateCode, lgaCode, walletBalance, depositBalance, "
        "lifetimeValue, hasWalletBalance, hasEmail, hasPhone, hasTin, hasCorrespondent, customerSince, lastPurchaseAt, createdAt, createdBy, "
        "from/to/period (on customerSince) and attr.<customField>. Sort: name (default), code, creditLimit, walletBalance, depositBalance, "
        "lifetimeValue, customerSince, lastPurchaseAt, createdAt.")

ep(C, "E4 Search Customers (error: unknown filter)", "GET", "/api/v1/customers",
   query=[{"key": "colour", "value": "blue"}],
   status=400, status_text="Bad Request",
   resp=ERR("INVALID_FILTER", "Invalid filter on 'colour': Unknown filter", {"field": "colour", "reason": "Unknown filter", "allowed": "type,status,..."}),
   expect_error={"status": 400, "code": "INVALID_FILTER"},
   desc="A misspelt filter is an error listing the allowed names - never a silently unfiltered list.")

ep(C, "E5 Customer Filter Metadata", "GET", "/api/v1/customers/filters",
   resp={"data": {"fields": [{"key": "type", "label": "Customer type", "type": "ENUM", "operators": ["eq", "ne", "in", "not_in", "is_null", "is_not_null"],
                              "options": [{"value": "AUTHORISED_DEALER", "label": "Authorised Dealer"}], "lookup": None, "facetable": True,
                              "sortable": False, "qualifierAliases": []}],
                  "collections": [{"key": "ledger", "label": "Wallet/deposit history"}], "primaryDate": "customerSince",
                  "defaultSort": [{"field": "name", "dir": "asc"}], "periodPresets": ["TODAY", "THIS_MONTH"]}},
   desc="Every filter with its type, operators, options and lookup endpoint - build the filter UI from this.")

ep(C, "E6 Customer Stats", "GET", "/api/v1/customers/stats",
   resp={"data": {"total": 4, "active": 4, "watch": 0, "suspended": 0, "dealers": 1, "special": 1, "ordinary": 1, "noCredit": 1,
                  "walletsHeld": 0, "walletBalanceTotal": 0.00, "depositBalanceTotal": 0.00}},
   desc="Register header tiles. Optional query: type, locationId.")

ep(C, "E7 Customer Lookup (picker)", "GET", "/api/v1/customers/lookup",
   query=[{"key": "q", "value": "chidi"}, {"key": "forSale", "value": "true"}],
   resp={"data": [{"id": "{{customerId}}", "code": "CU-1", "label": "Chidi Nwosu", "sublabel": "CU-1 · Ordinary Customer · …2222",
                   "type": "ORDINARY_CUSTOMER", "status": "ACTIVE", "disabled": False, "disabledReason": None,
                   "walletBalance": 0.00, "depositBalance": 0.00, "creditLimit": 0.00}]},
   desc="Customer picker for POS, pro-forma and returns. Not location-restricted (a customer can buy anywhere). Query: q, types (repeatable), "
        "forSale=true (suspended customers show disabled with the reason), limit (default 20, max 50).")

ep(C, "E8 Global Search", "GET", "/api/v1/search",
   query=[{"key": "q", "value": "{{dealerCode}}"}],
   resp={"data": {"groups": [{"type": "customer", "label": "Customers", "hits": [{"type": "customer", "id": "{{dealerId}}",
                  "title": "Okafor & Sons Distribution 482", "subtitle": "DLR-1 · Gold · …7781", "ref": "DLR-1", "route": "/customers/{{dealerId}}"}],
                  "more": False, "moreRoute": "/customers?q=DLR-1"}], "timedOut": []}},
   tests=["const json = pm.response.json();",
          "pm.test('exact reference is the single hit', () => pm.expect(json.data.groups[0].hits[0].id).to.eql(pm.collectionVariables.get('dealerId')));"],
   desc="The header search box across every module you can access (customers and correspondents so far). An exact reference returns just that "
        "record. Try q=okafor for grouped results. Query: q (min 2 chars), types (e.g. customer), limit per group (default 5).")

# ---- F. Wallet, deposit and ledger ----
ep(C, "F1 Wallet Adjustment (credit)", "POST", "/api/v1/customers/{{dealerId}}/wallet-adjustments",
   body={"balanceType": "WALLET", "direction": "CREDIT", "amount": 2500, "reason": "Opening balance migration"},
   resp={"data": ENTRY},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('adjustmentEntryId', json.data.id);",
          "pm.test('balance after is 2500', () => pm.expect(json.data.balanceAfter).to.eql(2500));"],
   desc="Perm customers.wallet.adjust (Administrator). An Administrator's correction, posted through the same ledger as every other movement; "
        "reference WLT-n; the reason is required and shown in the customer's history. direction=CREDIT|DEBIT.")

ep(C, "F2 Wallet Adjustment (error: overdraw)", "POST", "/api/v1/customers/{{dealerId}}/wallet-adjustments",
   body={"balanceType": "WALLET", "direction": "DEBIT", "amount": 999999, "reason": "More than the balance"},
   status=400, status_text="Bad Request",
   resp=ERR("INSUFFICIENT_WALLET_BALANCE", "Insufficient wallet balance", {"available": "2500.00", "requested": "999999.00"}),
   expect_error={"status": 400, "code": "INSUFFICIENT_WALLET_BALANCE"},
   desc="A balance can never go negative.")

ep(C, "F3 Wallet Adjustment (error: deposit on a dealer)", "POST", "/api/v1/customers/{{dealerId}}/wallet-adjustments",
   body={"balanceType": "DEPOSIT", "direction": "CREDIT", "amount": 100, "reason": "Dealers don't hold deposits"},
   status=400, status_text="Bad Request", resp=ERR("BALANCE_TYPE_NOT_ALLOWED", "Only Special Customers hold a deposit"),
   expect_error={"status": 400, "code": "BALANCE_TYPE_NOT_ALLOWED"})

ep(C, "F4 Deposit Adjustment (special customer)", "POST", "/api/v1/customers/{{specialId}}/wallet-adjustments",
   body={"balanceType": "DEPOSIT", "direction": "CREDIT", "amount": 10000, "reason": "Deposit carried over from the old system"},
   resp={"data": dict(ENTRY, id="7c1e0a52-2222-4c4c-9a9a-000000000002", customerId="{{specialId}}", balanceType="DEPOSIT",
                      amount=10000.00, balanceAfter=10000.00, reference="WLT-2")},
   desc="Special Customers hold a deposit balance alongside the wallet. Used again in G3.")

ep(C, "F5 Customer Ledger (wallet)", "GET", "/api/v1/customers/{{dealerId}}/ledger",
   query=[{"key": "balanceType", "value": "WALLET"}],
   resp={"data": {"customer": SUMMARY, "balanceType": "WALLET", "openingBalance": 0.00, "closingBalance": 2500.00, "totalCredits": 2500.00,
                  "totalDebits": 0.00, "items": [{"id": "{{adjustmentEntryId}}", "entryDate": "2026-10-08", "reference": "WLT-1",
                  "entryType": "ADJUSTMENT_CREDIT", "displayType": "Adjustment", "direction": "CREDIT",
                  "description": "Adjustment: Opening balance migration", "paymentMethod": "Wallet adjustment", "amount": 2500.00,
                  "balanceAfter": 2500.00, "locationId": "{{customerLocationId}}", "sourceModule": "customers", "performedByUserId": "{{userId}}",
                  "reversible": True, "reversedByEntryId": None, "createdAt": NOW}],
                  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1}},
   tests=["const json = pm.response.json();", "pm.test('closing balance 2500', () => pm.expect(json.data.closingBalance).to.eql(2500));"],
   desc="Perm customers.ledger.read. Customer History, oldest first so balances read top-down; amount is signed (credit +, debit -). "
        "Query: balanceType=WALLET|DEPOSIT, entryType (repeatable), direction, sourceModule, locationId, performedBy, amount range, "
        "from/to/period (bound opening/closing balances), q (reference, description, amount), page, size.")

ep(C, "F6 Customer Ledger (deposit)", "GET", "/api/v1/customers/{{specialId}}/ledger",
   query=[{"key": "balanceType", "value": "DEPOSIT"}],
   resp={"data": {"customer": dict(SUMMARY, id="{{specialId}}"), "balanceType": "DEPOSIT", "openingBalance": 0.00, "closingBalance": 10000.00,
                  "totalCredits": 10000.00, "totalDebits": 0.00, "items": [], "page": 0, "size": 50, "totalItems": 1, "totalPages": 1}})

ep(C, "F7 Ledger Filter Metadata", "GET", "/api/v1/customers/ledger/filters",
   resp={"data": {"fields": [{"key": "entryType", "label": "Entry type", "type": "ENUM", "operators": ["eq", "in"], "options": [],
                              "lookup": None, "facetable": True, "sortable": False, "qualifierAliases": []}],
                  "collections": [], "primaryDate": "entryDate", "defaultSort": [{"field": "createdAt", "dir": "asc"}], "periodPresets": ["TODAY"]}})

ep(C, "F8 Search Customers by ledger history (advanced)", "POST", "/api/v1/customers/search",
   body={"filter": {"ledger": {"all": [{"field": "balanceType", "op": "eq", "value": "DEPOSIT"},
                                       {"field": "amount", "op": "gte", "value": 5000}]}},
         "sort": [{"field": "name", "dir": "asc"}], "page": 0, "size": 20},
   resp=PAGE([dict(SUMMARY, id="{{specialId}}", code="CU-3", type="SPECIAL_CUSTOMER", name="Adaeze Foods Ltd")]),
   tests=["const json = pm.response.json();",
          "pm.test('finds the customer with a deposit of 5000+', () => pm.expect(json.data.items.map(c => c.id)).to.eql([pm.collectionVariables.get('specialId')]));"],
   desc="POST /customers/search takes { q, filter, params, sort, page, size, facets, highlight }. The filter tree supports all / any / not and the "
        "ledger collection: conditions grouped under \"ledger\" must hold for the same ledger entry (here: a DEPOSIT entry of at least 5000).")

ep(C, "F9 Reverse Ledger Entry", "POST", "/api/v1/customers/ledger-entries/{{adjustmentEntryId}}/reverse",
   body={"reason": "Entered twice"},
   resp={"data": dict(ENTRY, id="6b0f0e1e-1111-4c4c-9a9a-000000000001", entryType="REVERSAL_DEBIT", balanceAfter=0.00)},
   tests=["const json = pm.response.json();", "pm.test('balance back to 0', () => pm.expect(json.data.balanceAfter).to.eql(0));"],
   desc="Perm customers.wallet.adjust. Posts the opposite entry; the original stays in the history marked reversed. Only this module's own "
        "adjustments can be reversed here - sales and withdrawals are reversed by voiding their source document (409 REVERSE_IN_SOURCE_MODULE).")

ep(C, "F10 Reverse Ledger Entry (error: twice)", "POST", "/api/v1/customers/ledger-entries/{{adjustmentEntryId}}/reverse",
   body={"reason": "Twice"}, status=409, status_text="Conflict",
   resp=ERR("ALREADY_REVERSED", "This entry has already been reversed"),
   expect_error={"status": 409, "code": "ALREADY_REVERSED"})

# ---- G. Reclassification (promotion / demotion) ----
ep(C, "G1 Reclassify Customer (today)", "POST", "/api/v1/customers/{{customerId}}/reclassifications",
   body={"toType": "SPECIAL_CUSTOMER", "reason": "MET_PURCHASE_VOLUME", "note": "Crossed N5m in 90 days", "effectiveFrom": "{{today}}"},
   resp={"data": RECLASS},
   tests=["const json = pm.response.json();", "pm.test('applied immediately', () => pm.expect(json.data.status).to.eql('APPLIED'));"],
   desc="Perm customers.reclassify. effectiveFrom today applies at once (APPLIED); a future date is SCHEDULED and applied automatically once due. "
        "The wallet always carries over. Promoting to AUTHORISED_DEALER needs toDealerCategoryId; toCreditLimit is optional "
        "(defaults: dealer = category suggestion, walk-in = 0, otherwise unchanged). reason: SUSTAINED_SALES_PERFORMANCE | MET_PURCHASE_VOLUME | "
        "COMPLIANCE_CONCERN | PAYMENT_ARREARS | PROLONGED_INACTIVITY | MANAGEMENT_DECISION.")

ep(C, "G2 Reclassify Customer (error: same type)", "POST", "/api/v1/customers/{{customerId}}/reclassifications",
   body={"toType": "SPECIAL_CUSTOMER", "reason": "MANAGEMENT_DECISION", "effectiveFrom": "{{today}}"},
   status=400, status_text="Bad Request", resp=ERR("RECLASSIFICATION_SAME_TYPE", "The customer already has this type"),
   expect_error={"status": 400, "code": "RECLASSIFICATION_SAME_TYPE"})

ep(C, "G3 Reclassify Customer (error: deposit not settled)", "POST", "/api/v1/customers/{{specialId}}/reclassifications",
   body={"toType": "ORDINARY_CUSTOMER", "reason": "PAYMENT_ARREARS", "effectiveFrom": "{{today}}"},
   status=409, status_text="Conflict",
   resp=ERR("DEPOSIT_MUST_BE_SETTLED", "Draw or refund the deposit before moving this customer away from Special Customer"),
   expect_error={"status": 409, "code": "DEPOSIT_MUST_BE_SETTLED"},
   desc="The special customer holds a 10,000 deposit (F4), so they can't leave Special Customer until it's drawn or refunded.")

ep(C, "G4 Schedule Reclassification", "POST", "/api/v1/customers/{{customerId}}/reclassifications",
   body={"toType": "ORDINARY_CUSTOMER", "reason": "PROLONGED_INACTIVITY", "effectiveFrom": "2099-01-01"},
   resp={"data": dict(RECLASS, status="SCHEDULED", fromType="SPECIAL_CUSTOMER", toType="ORDINARY_CUSTOMER", direction="DEMOTION",
                      reason="PROLONGED_INACTIVITY", note=None, effectiveFrom="2099-01-01", appliedAt=None)},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('reclassificationId', json.data.id);",
          "pm.test('scheduled', () => pm.expect(json.data.status).to.eql('SCHEDULED'));"])

ep(C, "G5 Schedule Reclassification (error: one already pending)", "POST", "/api/v1/customers/{{customerId}}/reclassifications",
   body={"toType": "NO_CREDIT_WALKIN", "reason": "PAYMENT_ARREARS", "effectiveFrom": "2099-02-01"},
   status=409, status_text="Conflict", resp=ERR("RECLASSIFICATION_PENDING", "A reclassification is already scheduled for this customer"),
   expect_error={"status": 409, "code": "RECLASSIFICATION_PENDING"},
   desc="Only one scheduled reclassification per customer; cancel it first (G6).")

ep(C, "G6 Cancel Scheduled Reclassification", "DELETE", "/api/v1/customers/{{customerId}}/reclassifications/{{reclassificationId}}",
   resp={"data": dict(RECLASS, status="CANCELLED", fromType="SPECIAL_CUSTOMER", toType="ORDINARY_CUSTOMER", direction="DEMOTION",
                      effectiveFrom="2099-01-01", appliedAt=None)},
   tests=["const json = pm.response.json();", "pm.test('cancelled', () => pm.expect(json.data.status).to.eql('CANCELLED'));"],
   desc="Only SCHEDULED ones can be cancelled; the row is kept in the history.")

ep(C, "G7 List Reclassifications", "GET", "/api/v1/customers/reclassifications",
   query=[{"key": "customerId", "value": "{{customerId}}"}],
   resp=PAGE([dict(RECLASS, status="CANCELLED"), RECLASS]),
   tests=["const json = pm.response.json();", "pm.test('both kept in history', () => pm.expect(json.data.totalItems).to.eql(2));"],
   desc="History, newest effective date first. Filters: customerId, direction, fromType, toType, reason, status, effectiveFrom (range/period), "
        "authorisedBy, createdAt; q = customer code or name, note.")

ep(C, "G8 Search Reclassifications (advanced)", "POST", "/api/v1/customers/reclassifications/search",
   body={"filter": {"all": [{"field": "direction", "op": "eq", "value": "PROMOTION"}, {"field": "status", "op": "eq", "value": "APPLIED"}]}},
   resp=PAGE([RECLASS]))

ep(C, "G9 Reclassification Filter Metadata", "GET", "/api/v1/customers/reclassifications/filters",
   resp={"data": {"fields": [], "collections": [], "primaryDate": "effectiveFrom", "defaultSort": [{"field": "effectiveFrom", "dir": "desc"}], "periodPresets": []}})

# ---- H. Status ----
ep(C, "H1 Set Customer Status (WATCH)", "PUT", "/api/v1/customers/{{customerId}}/status",
   body={"status": "WATCH", "reason": "Two late payments"},
   resp={"data": dict(CUST, type="SPECIAL_CUSTOMER", status="WATCH", statusReason="Two late payments")},
   desc="status=ACTIVE|WATCH|SUSPENDED, reason required. WATCH <-> ACTIVE needs customers.manage; anything involving SUSPENDED needs "
        "customers.dealers.manage. WATCH is a warning badge only; SUSPENDED blocks sales and wallet movements.")

ep(C, "H2 Set Customer Status (error: REMOVED)", "PUT", "/api/v1/customers/{{customerId}}/status",
   body={"status": "REMOVED", "reason": "Not allowed"}, status=409, status_text="Conflict",
   resp=ERR("INVALID_STATUS_TRANSITION", "Cannot change status from WATCH to REMOVED", {"from": "WATCH", "to": "REMOVED"}),
   expect_error={"status": 409, "code": "INVALID_STATUS_TRANSITION"},
   desc="REMOVED is set only automatically (a temporary walk-in whose wallet is fully spent).")

ep(C, "H3 Suspend Dealer", "PUT", "/api/v1/customers/{{dealerId}}/status",
   body={"status": "SUSPENDED", "reason": "Cheque bounced"},
   resp={"data": dict(DEALER, status="SUSPENDED", statusReason="Cheque bounced")})

ep(C, "H4 Wallet Adjustment (error: suspended customer)", "POST", "/api/v1/customers/{{dealerId}}/wallet-adjustments",
   body={"balanceType": "WALLET", "direction": "CREDIT", "amount": 100, "reason": "Should be blocked"},
   status=409, status_text="Conflict",
   resp=ERR("CUSTOMER_NOT_ACTIVE", "This customer is SUSPENDED and cannot be transacted with", {"status": "SUSPENDED"}),
   expect_error={"status": 409, "code": "CUSTOMER_NOT_ACTIVE"})

ep(C, "H5 Reactivate Dealer", "PUT", "/api/v1/customers/{{dealerId}}/status",
   body={"status": "ACTIVE", "reason": "Cheque cleared"},
   resp={"data": dict(DEALER, statusReason="Cheque cleared")})

# ---- I. Rules that span records ----
ep(C, "I1 Deactivate Dealer Category (error: in use)", "PUT", "/api/v1/customers/dealer-categories/{{goldCategoryId}}/status",
   body={"status": "INACTIVE"}, status=409, status_text="Conflict",
   resp=ERR("CATEGORY_IN_USE", "This category is used by 1 dealer(s)", {"dealers": "1"}),
   expect_error={"status": 409, "code": "CATEGORY_IN_USE"},
   desc="Gold is used by the dealer from D1.")

ep(C, "I2 Correspondent's Customers", "GET", "/api/v1/customers/correspondents/{{correspondentId}}/customers",
   resp=PAGE([SUMMARY]),
   tests=["const json = pm.response.json();",
          "pm.test('the dealer belongs to this correspondent', () => pm.expect(json.data.items.map(c => c.id)).to.include(pm.collectionVariables.get('dealerId')));"],
   desc="Same filters, search and paging as the customer list, fixed to one correspondent.")

ep(C, "I3 De-assign Correspondent", "POST", "/api/v1/customers/correspondents/{{correspondentId}}/de-assign",
   body={"note": "Left the company"},
   resp={"data": {"correspondent": dict(CORR, status="DE_ASSIGNED", region=None, locationId=None, locationName=None, assignedAt=None),
                  "history": [dict(HISTORY_ROW, action="DE_ASSIGNED", note="Left the company"), HISTORY_ROW], "affectedCustomers": 1}},
   tests=["const json = pm.response.json();", "pm.test('reports linked customers', () => pm.expect(json.data.affectedCustomers).to.eql(1));"],
   desc="ASSIGNED -> DE_ASSIGNED. Linked customers keep the link so past sales stay attributed, but the correspondent earns no credit for new sales; "
        "affectedCustomers tells the UI how many customers to offer re-linking for. Assign again with C4's endpoint.")

ep(C, "I4 De-assign Correspondent (error: already de-assigned)", "POST", "/api/v1/customers/correspondents/{{correspondentId}}/de-assign",
   body={}, status=409, status_text="Conflict",
   resp=ERR("INVALID_STATUS_TRANSITION", "Cannot change status from DE_ASSIGNED to DE_ASSIGNED", {"from": "DE_ASSIGNED", "to": "DE_ASSIGNED"}),
   expect_error={"status": 409, "code": "INVALID_STATUS_TRANSITION"})

# ---- J. Self-service ----
ep(C, "J1 My Customer Record (self-service)", "GET", "/api/v1/customers/me",
   status=404, status_text="Not Found", resp=ERR("CUSTOMER_NOT_FOUND", "Customer not found"),
   expect_error={"status": 404, "code": "CUSTOMER_NOT_FOUND"},
   desc="For dealer/customer logins (perm customers.self.read): returns the customer record whose userId is the caller. The collection's admin "
        "isn't linked to a customer, so this returns 404 here. To try it for real: invite a user (folder 02), set that user's id as userId on a "
        "customer (D10), log in as them and call this.")

ep(C, "J2 My Ledger (self-service)", "GET", "/api/v1/customers/me/ledger",
   query=[{"key": "balanceType", "value": "WALLET"}],
   status=404, status_text="Not Found", resp=ERR("CUSTOMER_NOT_FOUND", "Customer not found"),
   expect_error={"status": 404, "code": "CUSTOMER_NOT_FOUND"},
   desc="The caller's own wallet/deposit history; same shape and query as F5.")

# ============================== DOCUMENTS (docs/03 §8) ==============================
D = "13 - Documents"
DOC = {"id": "8d2c1f0e-3333-4c4c-9a9a-000000000003", "ref": "WB-1", "docType": "WAYBILL", "sourceModule": "inventory",
       "sourceId": "{{transferId}}", "locationId": "{{factoryId}}", "locationName": "Apex Plant A (Updated)",
       "partyName": "Apex Plant B", "partyCustomerId": None, "valueAmount": None, "issuedByUserId": "{{userId}}",
       "issuedByName": "Alice Admin", "issuedAt": NOW, "status": "ISSUED", "reprintCount": 0, "pdfUrl": None}

ep(D, "List Issued Documents", "GET", "/api/v1/documents",
   query=[{"key": "type", "value": "WAYBILL"}],
   resp=PAGE([DOC]),
   tests=["const json = pm.response.json();",
          "pm.test('the dispatched waybill is registered', () => pm.expect(json.data.items.map(d => d.ref)).to.include(pm.collectionVariables.get('waybillRef')));"],
   desc="Transaction Receipts: every issued invoice, waybill, receipt and pro-forma, newest first. Perm platform.documents.read, and each "
        "document type also needs its own read permission (waybills: inventory.stock.read). Single-location users see only documents issued "
        "at their location. Query: q (reference, party name, value), type, status (ISSUED|VOIDED), locationId, sourceModule, partyCustomerId, "
        "valueAmount range, reprinted, issuedBy, issuedAt range / from / to / period, sort, page, size.")

ep(D, "Get Document", "GET", "/api/v1/documents/{{waybillRef}}", resp={"data": DOC},
   desc="404 DOCUMENT_NOT_FOUND also when the caller may not see the document.")

ep(D, "Reprint Document", "POST", "/api/v1/documents/{{waybillRef}}/reprints",
   body={"reason": "Driver lost the original"},
   resp={"data": {"ref": "WB-1", "reprintCount": 1, "pdfUrl": None}},
   tests=["const json = pm.response.json();", "pm.test('reprint counted', () => pm.expect(json.data.reprintCount).to.eql(1));"],
   desc="Records a reprint; the next PDF of this document is marked DUPLICATE COPY. pdfUrl points at the issuing module's PDF.")

ep(D, "Search Documents (advanced)", "POST", "/api/v1/documents/search",
   body={"filter": {"all": [{"field": "type", "op": "eq", "value": "WAYBILL"}, {"field": "reprinted", "op": "eq", "value": True}]}},
   resp=PAGE([dict(DOC, reprintCount=1)]))

ep(D, "Document Filter Metadata", "GET", "/api/v1/documents/filters",
   resp={"data": {"fields": [], "collections": [], "primaryDate": "issuedAt", "defaultSort": [{"field": "issuedAt", "dir": "desc"}], "periodPresets": []}})

# ============================== FILES (docs/03 §8.4) ==============================
F = "14 - Files"
FILE = {"id": "{{fileId}}", "purpose": "general.attachment", "originalName": "sample-attachment.pdf", "contentType": "application/pdf",
        "sizeBytes": 160, "sha256": "9f2b5c0e7a1d4b3c8e6f2a9d0c1b7e4f3a2d5c8b9e0f1a2b3c4d5e6f7a8b9c0d1", "status": "ACTIVE",
        "linkedType": None, "linkedId": None, "uploadedByUserId": "{{userId}}", "uploadedAt": NOW}

ep(F, "List Upload Purposes", "GET", "/api/v1/files/purposes",
   resp={"data": [{"code": "general.attachment", "label": "Attachment (PDF, image or CSV, up to 10 MB)",
                   "contentTypes": ["application/pdf", "image/jpeg", "image/png", "image/webp", "text/csv", "text/plain"], "maxBytes": 10485760}]},
   desc="What can be uploaded where: each module registers its purposes (accepted types, size limit, who may upload and read).")

ep(F, "Upload File", "POST", "/api/v1/files",
   form=[{"key": "file", "src": "postman/fixtures/sample-attachment.pdf"}, {"key": "purpose", "value": "general.attachment"}],
   resp={"data": FILE},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('fileId', json.data.id);",
          "pm.test('type detected from the content', () => pm.expect(json.data.contentType).to.eql('application/pdf'));"],
   desc="multipart/form-data with `file` and `purpose`. The type is detected from the file's content (not its name or the browser's claim) and must be one "
        "the purpose accepts; files go to S3 under a tenant-scoped key with server-side encryption. In the Postman app, pick any PDF/image/CSV for `file` "
        "(Newman uses postman/fixtures/sample-attachment.pdf). 503 STORAGE_NOT_CONFIGURED if the server has no bucket.")

ep(F, "Upload File (error: disguised type)", "POST", "/api/v1/files",
   form=[{"key": "file", "src": "postman/fixtures/disguised-archive.pdf"}, {"key": "purpose", "value": "general.attachment"}],
   status=400, status_text="Bad Request",
   resp={"error": {"code": "FILE_TYPE_NOT_ALLOWED", "message": "This kind of file (unrecognised) can't be uploaded here",
                   "details": {"detected": "unrecognised", "allowed": "application/pdf,image/jpeg,image/png,image/webp,text/csv,text/plain"}}},
   expect_error={"status": 400, "code": "FILE_TYPE_NOT_ALLOWED"},
   desc="A ZIP archive renamed to .pdf is refused: the name and declared type are ignored.")

ep(F, "Get File", "GET", "/api/v1/files/{{fileId}}", resp={"data": FILE},
   desc="404 FILE_NOT_FOUND also when the caller may not read it (another company's file, or a purpose with a read permission they lack).")

ep(F, "Get Download Link", "GET", "/api/v1/files/{{fileId}}/download",
   resp={"data": {"file": FILE, "url": "https://zifiq-files.s3.eu-north-1.amazonaws.com/tenants/.../general.attachment/2026/10/...?X-Amz-Signature=...",
                  "expiresAt": NOW}},
   tests=["const json = pm.response.json();", "pm.test('a signed link is returned', () => pm.expect(json.data.url).to.include('X-Amz-Signature'));"],
   desc="A pre-signed link valid for a few minutes (expiresAt); open it in the browser to download - the file comes straight from storage with its "
        "original name. Locally/CI the link points at S3Mock's internal host name, so it only opens from inside Docker.")

ep(F, "Delete File", "DELETE", "/api/v1/files/{{fileId}}",
   resp={"data": dict(FILE, status="DELETED")},
   desc="Marks the file deleted (records are never hard-deleted). Allowed for the uploader, or a holder of the purpose's upload permission.")

ep(F, "Get Download Link (error: deleted)", "GET", "/api/v1/files/{{fileId}}/download",
   status=404, status_text="Not Found", resp={"error": {"code": "FILE_NOT_FOUND", "message": "File not found", "details": {}}},
   expect_error={"status": 404, "code": "FILE_NOT_FOUND"})

# ============================== INVENTORY EXTENSIONS (docs/05 §4.2–§4.13) ==============================
I = "15 - Inventory Extensions"
DRIVER = {"id": "{{driverId}}", "name": "Musa Bello", "phone": "0803 000 1111", "vehicleNumber": "KN-482-ABC", "licenceNumber": "KAN-77821",
          "status": "ACTIVE", "createdAt": NOW}
TRANSFER2 = {"id": "{{transfer2Id}}", "waybillRef": "WB-2", "sourceLocationId": "{{factoryId}}", "sourceLocationName": "Apex Plant A (Updated)",
             "sourceLocationType": "FACTORY", "destinationLocationId": "{{factoryId2}}", "destinationLocationName": "Apex Plant B",
             "destinationLocationType": "FACTORY", "status": "DISPATCHED", "driverId": "{{driverId}}", "driverName": "Musa Bello",
             "driverVehicle": "KN-482-ABC", "transferDate": "2026-10-08", "dispatchedAt": NOW, "receivedDate": None, "receivedAt": None,
             "receiptNote": None, "totalSent": 5.000, "totalRejected": 0, "waybillUrl": "/api/v1/inventory/stock/transfers/{{transfer2Id}}/waybill.pdf",
             "lines": [{"id": "{{transfer2LineId}}", "stockItemId": "{{finishedProductId}}", "stockItemCode": "ITM-2", "stockItemName": "Premium Blend",
                        "unit": "Kilogram", "qtySent": 5.000, "qtyReceived": None, "qtyRejected": None, "outstanding": 5.000, "fromLocationId": None,
                        "fromLocationName": None, "receivedIntoLocationId": None, "receivedIntoLocationName": None, "batchNo": "B-2026-10-01",
                        "rejectionNote": None}]}

ep(I, "Register Driver", "POST", "/api/v1/inventory/drivers",
   body={"name": "Musa Bello", "phone": "0803 000 1111", "vehicleNumber": "kn-482-abc", "licenceNumber": "KAN-77821"},
   resp={"data": DRIVER},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('driverId', json.data.id);",
          "pm.collectionVariables.set('today', new Date(Date.now() + 3600 * 1000).toISOString().slice(0, 10));",
          "pm.test('vehicle number normalised', () => pm.expect(json.data.vehicleNumber).to.eql('KN-482-ABC'));"],
   desc="Perm inventory.drivers.manage. The same name + vehicle is the same driver (409 DUPLICATE_NAME).")

ep(I, "Register Driver (error: duplicate)", "POST", "/api/v1/inventory/drivers",
   body={"name": "musa bello", "vehicleNumber": "KN-482-ABC"},
   status=409, status_text="Conflict", resp={"error": {"code": "DUPLICATE_NAME", "message": "'musa bello / KN-482-ABC' is already in use"}},
   expect_error={"status": 409, "code": "DUPLICATE_NAME"})

ep(I, "Update Driver", "PUT", "/api/v1/inventory/drivers/{{driverId}}",
   body={"name": "Musa Bello", "phone": "0803 000 1111", "vehicleNumber": "KN-482-ABC", "licenceNumber": "KAN-77821-B"},
   resp={"data": dict(DRIVER, licenceNumber="KAN-77821-B")})

ep(I, "List Drivers", "GET", "/api/v1/inventory/drivers", query=[{"key": "q", "value": "musa"}], resp={"data": [DRIVER]},
   desc="Perm inventory.drivers.read. q: name, vehicle number, phone, licence. status=ACTIVE|INACTIVE. GET /drivers/lookup is the picker (active only).")

ep(I, "Dispatch Transfer (with driver and batch)", "POST", "/api/v1/inventory/stock/transfers",
   body={"sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}", "driverId": "{{driverId}}", "driverName": None,
         "driverVehicle": None, "transferDate": None,
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 5, "unitPrice": None, "fromLocationId": None, "batchNo": "B-2026-10-01"}]},
   resp={"data": TRANSFER2},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('transfer2Id', json.data.id);",
          "pm.collectionVariables.set('transfer2LineId', json.data.lines[0].id);",
          "pm.test('driver taken from the register', () => pm.expect(json.data.driverName).to.eql('Musa Bello'));"],
   desc="With driverId the driver's name and vehicle are filled in. Per line, fromLocationId picks the area the goods leave from (default the "
        "source) and availability is checked there. Rules: source must be ACTIVE (409 LOCATION_NOT_TRADING when pending opening), destination "
        "not SUSPENDED (409 LOCATION_SUSPENDED), and not the same location (400 SAME_LOCATION — use an area move). The waybill is registered "
        "in Transaction Receipts.")

ep(I, "List Incoming Consignments", "GET", "/api/v1/inventory/stock/transfers",
   query=[{"key": "locationId", "value": "{{factoryId2}}"}, {"key": "direction", "value": "INTO"}, {"key": "status", "value": "DISPATCHED,PARTIALLY_RECEIVED"}],
   resp={"data": [TRANSFER2]},
   tests=["const json = pm.response.json();",
          "pm.test('the new transfer is awaiting receipt', () => pm.expect(json.data.map(t => t.id)).to.include(pm.collectionVariables.get('transfer2Id')));"],
   desc="Query: locationId, direction (INTO | OUT_OF), status (repeatable or comma-separated), q (waybill), from/to (transfer date).")

ep(I, "Receive Transfer (error: not reconciled)", "POST", "/api/v1/inventory/stock/transfers/{{transfer2Id}}/receive",
   body={"lines": [{"stockTransferLineId": "{{transfer2LineId}}", "qtyReceived": 3, "qtyRejected": 1, "rejectionNote": "Torn bag"}]},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "RECEIPT_NOT_RECONCILED", "message": "Line 1: 1 of the 5 dispatched is unaccounted for",
                   "details": {"line": "1", "outstanding": "5.000", "attempted": "4"}}},
   expect_error={"status": 400, "code": "RECEIPT_NOT_RECONCILED"},
   desc="Accepted + rejected must equal what is outstanding (module setting inventory.receive.requireFullReconciliation, on by default).")

ep(I, "Receive Transfer (with a rejection)", "POST", "/api/v1/inventory/stock/transfers/{{transfer2Id}}/receive",
   body={"receivedDate": None, "note": "One bag torn",
         "lines": [{"stockTransferLineId": "{{transfer2LineId}}", "qtyReceived": 4, "qtyRejected": 1, "rejectionNote": "Torn bag", "receivedIntoLocationId": None}]},
   resp={"data": dict(TRANSFER2, status="RECEIVED", receivedDate="2026-10-08", receivedAt=NOW, receiptNote="One bag torn", totalRejected=1.000)},
   tests=["const json = pm.response.json();", "pm.test('received with one rejected', () => { pm.expect(json.data.status).to.eql('RECEIVED'); pm.expect(json.data.totalRejected).to.eql(1); });"],
   desc="Accepted goods enter the destination (or receivedIntoLocationId: one of its areas). Rejected goods go straight back to where they were "
        "sent from (ledger entry TRANSFER_REJECTED_IN), and the source's stock staff get an in-app alert. rejectionNote is required for rejections.")

ep(I, "Get Transfer", "GET", "/api/v1/inventory/stock/transfers/{{transfer2Id}}",
   resp={"data": dict(TRANSFER2, status="RECEIVED", totalRejected=1.000)})

ep(I, "Transfer Waybill PDF", "GET", "/api/v1/inventory/stock/transfers/{{transfer2Id}}/waybill.pdf", pdf=True,
   resp=None, desc="The printable waybill (Send and Download in Postman to save it). After a reprint is recorded (folder 13), copies are marked DUPLICATE COPY.")

ep(I, "Counted Adjustment", "POST", "/api/v1/inventory/quantity-adjustments",
   body={"locationId": "{{factoryId2}}", "stockItemId": "{{finishedProductId}}", "mode": "COUNTED", "countedQuantity": 2,
         "reason": "STOCKTAKE_VARIANCE", "note": "Monthly count", "otpChallengeId": None},
   resp={"data": {"id": "5c1d2e3f-4444-4c4c-9a9a-000000000004", "ref": "QA-3", "locationId": "{{factoryId2}}", "locationName": "Apex Plant B",
                  "rootLocationName": "Apex Plant B", "stockItemId": "{{finishedProductId}}", "productCode": "ITM-2", "productName": "Premium Blend",
                  "unit": "Kilogram", "mode": "COUNTED", "direction": "NEGATIVE", "quantity": 2.000, "recordedQuantity": 4.000, "newQuantity": 2.000,
                  "reason": "STOCKTAKE_VARIANCE", "note": "Monthly count", "otpVerified": False, "performedBy": "{{userId}}",
                  "performedByName": "Alice Admin", "at": NOW}},
   tests=["const json = pm.response.json();", "pm.test('difference worked out from what was on hand', () => pm.expect(json.data.newQuantity).to.eql(2));"],
   desc="mode COUNTED: send what was counted; the server locks the balance and posts the difference (here 4 on hand → 2 counted = −2). "
        "mode DELTA (default) takes direction + quantity. Above the OTP threshold an Admin-issued OTP is needed either way.")

ep(I, "Counted Adjustment (error: no difference)", "POST", "/api/v1/inventory/quantity-adjustments",
   body={"locationId": "{{factoryId2}}", "stockItemId": "{{finishedProductId}}", "mode": "COUNTED", "countedQuantity": 2, "reason": "STOCKTAKE_VARIANCE"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "NO_ADJUSTMENT_NEEDED", "message": "The counted quantity equals the quantity on hand; nothing to adjust"}},
   expect_error={"status": 400, "code": "NO_ADJUSTMENT_NEEDED"})

ep(I, "Adjustments Report", "GET", "/api/v1/inventory/quantity-adjustments",
   query=[{"key": "reason", "value": "STOCKTAKE_VARIANCE"}],
   resp=PAGE([], summary={"count": 2, "qtyAdded": 0, "qtyRemoved": 62, "netEffect": -62}),
   tests=["const json = pm.response.json();", "pm.test('has totals', () => pm.expect(json.data.summary).to.have.property('netEffect'));"],
   desc="Paged. A showroom/factory locationId includes its storage areas; no locationId = every location you can see. Filters: locationId, "
        "direction, reason, mode, stockItemId, performedBy, otpVerified, quantity range, createdAt / from / to / period. q: reference, product, note. "
        "Summary: count, qtyAdded, qtyRemoved, netEffect. POST /quantity-adjustments/search and GET /quantity-adjustments/filters as usual.")

ep(I, "Publish Prices (error: dealer above retail)", "POST", "/api/v1/inventory/price-list/publish",
   body={"effectiveFrom": "{{today}}", "entries": [{"stockItemId": "{{finishedProductId}}", "retailPrice": 5000, "dealerPrice": 5200}]},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "DEALER_PRICE_ABOVE_RETAIL", "message": "ITM-2 Premium Blend: the dealer price can't be above the retail price"}},
   expect_error={"status": 400, "code": "DEALER_PRICE_ABOVE_RETAIL"})

ep(I, "Publish Prices", "POST", "/api/v1/inventory/price-list/publish",
   body={"effectiveFrom": "{{today}}", "note": "October list",
         "entries": [{"stockItemId": "{{finishedProductId}}", "retailPrice": 5000, "dealerPrice": 4500}]},
   resp={"data": {"batchRef": "PL-1", "effectiveFrom": "2026-10-08", "count": 1, "belowCostCount": 0, "costVisible": True}},
   desc="Perm inventory.pricing.manage. effectiveFrom today or later; one PL-n reference per publication; up to 1000 items. A price in force "
        "is never edited — publish a new one. Price-list readers get an in-app notice.")

ep(I, "Publish Prices (error: same date again)", "POST", "/api/v1/inventory/price-list/publish",
   body={"effectiveFrom": "{{today}}", "entries": [{"stockItemId": "{{finishedProductId}}", "retailPrice": 5100, "dealerPrice": 4600}]},
   status=409, status_text="Conflict",
   resp={"error": {"code": "PRICE_ALREADY_SET_FOR_DATE", "message": "ITM-2 Premium Blend already has a price effective 2026-10-08"}},
   expect_error={"status": 409, "code": "PRICE_ALREADY_SET_FOR_DATE"})

ep(I, "Schedule a Price Change", "POST", "/api/v1/inventory/price-list/publish",
   body={"effectiveFrom": "2099-01-01", "note": "Long-term test price",
         "entries": [{"stockItemId": "{{finishedProductId}}", "retailPrice": 5500, "dealerPrice": 5000}]},
   resp={"data": {"batchRef": "PL-2", "effectiveFrom": "2099-01-01", "count": 1, "belowCostCount": 0, "costVisible": True}})

ep(I, "Price List", "GET", "/api/v1/inventory/price-list", query=[{"key": "q", "value": "premium"}],
   resp=PAGE([{"stockItemId": "{{finishedProductId}}", "code": "ITM-2", "product": "Premium Blend", "itemType": "FINISHED_PRODUCT", "categoryId": None,
               "group": "Ungrouped", "unit": "Kilogram", "status": "ACTIVE", "priceId": "9e0f1a2b-5555-4c4c-9a9a-000000000005", "retailPrice": 5000.00,
               "dealerPrice": 4500.00, "effectiveFrom": "2026-10-08", "batchRef": "PL-1",
               "nextChange": {"priceId": "{{scheduledPriceId}}", "effectiveFrom": "2099-01-01", "retailPrice": 5500.00, "dealerPrice": 5000.00},
               "landingCost": 0.00, "margin": 5000.00, "marginPct": 100.00, "dealerMargin": 4500.00, "belowCost": False}],
             summary={"items": 1, "priced": 1, "unpriced": 0, "belowCost": 0, "costVisible": True, "asAt": "2026-10-08"}),
   tests=["const json = pm.response.json();", "const row = json.data.items[0];",
          "pm.test('current price and next change', () => { pm.expect(row.retailPrice).to.eql(5000); pm.expect(row.nextChange.retailPrice).to.eql(5500); });"],
   desc="Perm inventory.pricing.read. Prices in force on asAt (default today) plus the next scheduled change. landingCost/margin/marginPct/"
        "dealerMargin/belowCost are cost fields: 0/false without factory.costing.read. Filters: itemType, categoryId, status, retailPrice, "
        "dealerPrice, effectiveFrom, hasPrice, hasNextChange, batchRef, belowCost and marginPct (cost), locationId (direct-entry items).")

ep(I, "Product Price History", "GET", "/api/v1/inventory/stock-items/{{finishedProductId}}/prices",
   resp={"data": [{"priceId": "{{scheduledPriceId}}", "retailPrice": 5500.00, "dealerPrice": 5000.00, "effectiveFrom": "2099-01-01", "batchRef": "PL-2",
                   "note": "Long-term test price", "setByUserId": "{{userId}}", "createdAt": NOW, "state": "SCHEDULED"}]},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('scheduledPriceId', json.data[0].id || json.data[0].priceId);",
          "pm.test('newest first: scheduled, then current', () => pm.expect(json.data.map(p => p.state)).to.eql(['SCHEDULED', 'CURRENT']));"])

ep(I, "Change Scheduled Price", "PUT", "/api/v1/inventory/price-list/entries/{{scheduledPriceId}}",
   body={"retailPrice": 5600, "dealerPrice": 5100, "note": "Revised"},
   resp={"data": {"priceId": "{{scheduledPriceId}}", "retailPrice": 5600.00, "dealerPrice": 5100.00, "effectiveFrom": "2099-01-01", "batchRef": "PL-2",
                  "note": "Revised", "setByUserId": "{{userId}}", "createdAt": NOW, "state": "SCHEDULED"}},
   desc="Only a price not yet in force can change (409 PRICE_ALREADY_EFFECTIVE otherwise).")

ep(I, "Group Stock Card", "GET", "/api/v1/inventory/stock/group-card",
   query=[{"key": "groupBy", "value": "CATEGORY"}, {"key": "groupId", "value": "{{categoryId}}"}, {"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": {"header": {"groupType": "CATEGORY", "groupId": "{{categoryId}}", "groupName": "Beverages", "members": [], "openingBalance": 0,
                             "totalReceived": 0, "totalIssued": 0, "closingBalance": 0}, "rows": []}},
   desc="Every movement of every product in the category, one running total. A showroom/factory includes its storage areas; from/to optional. "
        "Products with different units can't be added up (400 GROUP_MIXED_UNITS). groupBy=PARENT_PRODUCT needs product variants (not built yet).")

ep(I, "Group Bin Card", "GET", "/api/v1/inventory/stock/group-bin-card",
   query=[{"key": "groupBy", "value": "CATEGORY"}, {"key": "groupId", "value": "{{categoryId}}"}, {"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": {"header": {"groupType": "CATEGORY", "groupId": "{{categoryId}}", "groupName": "Beverages", "members": [], "openingBalance": 0,
                             "totalReceived": 0, "totalIssued": 0, "closingBalance": 0}, "rows": []}},
   desc="Daily totals for the group.")

print(f"TOTAL ENDPOINTS: {len(ENDPOINTS)}")

# =====================================================================================
# ---------------------------- Collection / Environment builders ---------------------
# =====================================================================================

def pm_url(path, query):
    full = "{{baseUrl}}" + path
    parts = [p for p in path.split("/") if p]
    url = {"raw": full + ("?" + "&".join(f"{q['key']}={q['value']}" for q in query) if query else ""),
           "host": ["{{baseUrl}}"], "path": parts}
    if query:
        url["query"] = [{"key": q["key"], "value": q["value"]} for q in query]
    return url

def pm_body(body):
    if body is None:
        return None
    return {"mode": "raw", "raw": json.dumps(body, indent=2), "options": {"raw": {"language": "json"}}}

BEARER_AUTH = {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]}

def pm_request_item(e, with_examples=True):
    item = {
        "name": e["name"],
        "request": {
            "method": e["method"],
            "header": ([{"key": "Content-Type", "value": "application/json"}] if e["body"] is not None else []),
            "url": pm_url(e["path"], e["query"]),
        },
    }
    if e["desc"]:
        item["request"]["description"] = e["desc"]
    if e["body"] is not None:
        item["request"]["body"] = pm_body(e["body"])
    if e.get("form"):
        # Postman sets the multipart Content-Type (with its boundary) itself.
        item["request"]["body"] = {"mode": "formdata", "formdata": [
            {"key": f["key"], "type": "file", "src": f["src"]} if "src" in f else {"key": f["key"], "value": f["value"], "type": "text"}
            for f in e["form"]
        ]}
    # Set explicitly on every request rather than inherited from the collection, so each request's
    # Authorization tab shows Bearer {{accessToken}} and it still works when run, copied or exported on its own.
    if e["auth"] == "noauth":
        item["request"]["auth"] = {"type": "noauth"}
    else:
        item["request"]["auth"] = BEARER_AUTH
    custom_tests = e["tests"]
    if custom_tests:
        # Auto-guard every capture script against non-2xx responses so a single failed/conflicting
        # request (e.g. a 409 from re-running Register Tenant against existing data) never throws an
        # uncaught TypeError reading `.data` off an error envelope -- which otherwise aborts the whole
        # Collection Runner instead of just failing that one request's tests cleanly.
        already_guarded = custom_tests[0].strip().startswith("if (pm.response.code")
        if already_guarded:
            custom_tests = custom_tests
        else:
            custom_tests = ["if (pm.response.code >= 200 && pm.response.code < 300) {"] + \
                ["    " + line for line in custom_tests] + ["}"]
    if e.get("expect_error"):
        err = e["expect_error"]
        full_tests = custom_tests + [
            f"pm.test('fails as expected with {err['status']} {err['code']}', () => {{",
            f"    pm.response.to.have.status({err['status']});",
            "    const j = pm.response.json();",
            "    pm.expect(j).to.have.property('error');",
            f"    pm.expect(j.error.code).to.eql('{err['code']}');",
            "});",
        ]
    elif e.get("pdf"):
        full_tests = custom_tests + [
            "pm.test('status is 200', () => pm.response.to.have.status(200));",
            "pm.test('response is a PDF', () => pm.expect(pm.response.headers.get('Content-Type')).to.include('application/pdf'));",
        ]
    else:
        full_tests = custom_tests + [
            "pm.test('response has data envelope', () => { const j = pm.response.json(); pm.expect(j).to.have.property('data'); });",
        ]
    item["event"] = [{"listen": "test", "script": {"type": "text/javascript", "exec": full_tests}}]

    if with_examples:
        req_copy = json.loads(json.dumps(item["request"]))
        example = {
            "name": f"{e['name']} - {e['status']} {e['status_text']}",
            "originalRequest": req_copy,
            "status": e["status_text"],
            "code": e["status"],
            "_postman_previewlanguage": "json",
            "header": [{"key": "Content-Type", "value": "application/json"}],
            "cookie": [],
            "body": ("%PDF-1.7 ... (binary PDF)" if e.get("pdf") else json.dumps(e["resp"], indent=2) if e["resp"] is not None else ""),
        }
        item["response"] = [example]
    return item

FOLDER_DESCRIPTIONS = {
    "12 - Customers": (
        "Customers & Distributors (backend docs/04). Needs 01 - Auth > Register Tenant first (for the token); everything else this folder "
        "needs it creates itself, so it can be run on its own right after Register Tenant.\n\n"
        "Run top to bottom - requests are lettered by area and each one sets up the next:\n"
        "A setup (a home location) - B dealer categories - C correspondents - D registering customers of every type - "
        "E search, filters and pickers - F wallet/deposit ledger, adjustments, reversals - G promotion/demotion - H status - "
        "I rules that span records - J dealer self-service.\n\n"
        "Requests marked \"(error: ...)\" deliberately break a business rule and assert the exact error code, so a full run is all green. "
        "Captured variables: customerLocationId, today, goldCategoryId, diamondCategoryId, correspondentId, correspondent2Id, dealerId, "
        "dealerName, dealerCode, customerId, specialId, walkinId, adjustmentEntryId, reclassificationId."
    ),
}

def build_collection():
    folders = {}
    order = []
    for e in ENDPOINTS:
        if e["folder"] not in folders:
            folders[e["folder"]] = []
            order.append(e["folder"])
        folders[e["folder"]].append(pm_request_item(e))

    items = [dict({"name": f, "item": folders[f]}, **({"description": FOLDER_DESCRIPTIONS[f]} if f in FOLDER_DESCRIPTIONS else {}))
             for f in order]

    collection_vars = [
        "tenantId", "userId", "accessToken", "refreshToken", "childTenantId",
        "adminRoleId", "stockKeeperRoleId", "customRoleId", "memberUserId",
        "otpChallengeId", "otpCode",
        "factoryId", "factoryId2", "unitId", "categoryId", "supplierId", "manufacturerId",
        "rawMaterialId", "finishedProductId", "stockReceiptId",
        "transferId", "transferLineId",
        "recipeId", "costingId", "specialClientId", "specialRunId",
        "tradeOfferId",
        "customerLocationId", "today", "goldCategoryId", "diamondCategoryId", "correspondentId",
        "dealerId", "dealerName", "dealerCode", "customerId", "adjustmentEntryId", "reclassificationId",
        "correspondent2Id", "specialId", "walkinId", "waybillRef", "fileId",
        "driverId", "transfer2Id", "transfer2LineId", "scheduledPriceId",
    ]

    collection = {
        "info": {
            "name": "ZifIQ Backend API",
            "description": "Local test collection for the ZifIQ Kotlin/Spring Boot backend -- covers only the modules with real persistence and business logic (Auth, Tenants & Members, OTP, VAT Control, Settings, Factory Setup, Factory Production, Factory Trading, Inventory Stock, Inventory Operations, NRS Reference Data, Customers). The remaining scaffolded modules (Account, AI, Barcode, Cashier, History, Invoicing, Menu, Process Guide, Showroom) still return hardcoded mock data server-side and are intentionally excluded until they're built out for real. Run folders top-to-bottom in order (01 through 12) -- each request's Tests script captures IDs/tokens into collection variables that later requests reuse, so the whole collection can be run end-to-end with Postman's Collection Runner with no manual edits. See docs/00-02 in the backend repo for the full design spec these endpoints implement.",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "auth": BEARER_AUTH,
        "event": [],
        "variable": [{"key": v, "value": "", "type": "string"} for v in collection_vars],
        "item": items,
    }
    return collection

def existing_environment_id():
    """Keeps the environment's id stable across regenerations, so the file only changes when its values do."""
    path = os.path.join(HERE, "ZifIQ-Local.postman_environment.json")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f).get("id")

def build_environment():
    values = [{"key": "baseUrl", "value": "http://localhost:8080", "type": "default", "enabled": True}]
    return {
        "id": existing_environment_id() or str(uuid.uuid4()),
        "name": "ZifIQ Local",
        "values": values,
        "_postman_variable_scope": "environment",
    }

collection = build_collection()
environment = build_environment()

with open(os.path.join(HERE, "ZifIQ-API.postman_collection.json"), "w", encoding="utf-8") as f:
    json.dump(collection, f, indent=2, ensure_ascii=False)

with open(os.path.join(HERE, "ZifIQ-Local.postman_environment.json"), "w", encoding="utf-8") as f:
    json.dump(environment, f, indent=2, ensure_ascii=False)

print("Wrote ZifIQ-API.postman_collection.json and ZifIQ-Local.postman_environment.json")
print(f"Folders: {len(set(e['folder'] for e in ENDPOINTS))}, Requests: {len(ENDPOINTS)}")
