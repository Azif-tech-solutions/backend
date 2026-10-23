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

def ep(folder, name, method, path, auth=None, body=None, resp=None, status=200, status_text="OK", tests=None, query=None, desc="", expect_error=None, form=None, pdf=False, binary=None):
    # expect_error: {"status": int, "code": "ERROR_CODE"} for requests that are DELIBERATELY run
    # against a state that makes them fail (demonstrating a real error path), so the generic
    # "response has data envelope" assertion every other request gets would always fail here --
    # these get a status+error-code assertion instead, so a full collection run is genuinely
    # all-green rather than relying on two hardcoded "expected failures" the CI step has to know
    # to ignore.
    ENDPOINTS.append(dict(
        folder=folder, name=name, method=method, path=path, auth=auth, body=body,
        resp=resp, status=status, status_text=status_text, tests=tests or [], query=query or [], desc=desc,
        expect_error=expect_error, form=form, pdf=pdf, binary=binary,
    ))

NOW = "2026-10-01T10:15:30Z"

# Every user a response mentions comes back like this (platform.directory.UserRef).
USER = {"id": "{{userId}}", "name": "Alice Admin", "email": "admin@apex.com"}

# A referenced record, as every response returns it (platform.directory.Ref): its id plus identifying details.
REF_EXAMPLES = {
    "location": {"code": "SHR-1", "name": "Showroom Ikeja", "type": "SHOWROOM"},
    "customer": {"code": "CU-1", "name": "Okafor Stores", "type": "ORDINARY_CUSTOMER"},
    "stockItem": {"code": "RM-0001", "name": "Malted Barley", "itemType": "RAW_MATERIAL"},
    "correspondent": {"code": "COR-1", "name": "Bisi Adeyemi"},
    "cashAccount": {"code": "TILL-1", "name": "Ikeja Till", "kind": "TILL"},
    "bankAccount": {"code": "BA-1", "bankName": "Guaranty Trust Bank", "accountName": "Apex Industries Ltd"},
    "expenseCategory": {"code": "EX-10", "name": "Fuel Expenses"},
    "recipe": {"code": "RCP-1", "name": "Premium Blend Base"},
    "specialProductionClient": {"name": "Acme Contractors Ltd"},
    "category": {"name": "Beverages", "type": "FINISHED_PRODUCT"},
    "unit": {"code": "KG", "name": "Kilogram", "symbol": "kg"},
    "vatCategory": {"code": "STANDARD", "name": "Standard rate", "rate": 7.50},
    "hsnSection": {"code": "VI", "name": "Products of the chemical or allied industries"},
    "isicSection": {"code": "C", "name": "Manufacturing"},
    "supplier": {"code": "SUP-1", "name": "Northern Grains Ltd"},
    "manufacturer": {"code": "MFR-1", "name": "Apex Maltings"},
    "driver": {"name": "Musa Bello", "phone": "0803 555 1212"},
    "invoice": {"number": "INV-1", "status": "ISSUED"},
    "receipt": {"ref": "RCT-1", "kind": "RECEIPT"},
    "stockTransfer": {"ref": "WB-1"},
    "role": {"name": "Stock Keeper"},
    "sale": {"ref": "TXN-2", "status": "COMPLETED", "customer": "Okafor & Sons Distribution 482"},
    "customerWaybill": {"ref": "WB-2", "status": "ISSUED"},
}


def REF(kind, id):
    return dict({"id": id}, **REF_EXAMPLES[kind])


def PAGED(items, summary=None, **extra):
    """A paged response example (common/paging PageResult), one page holding [items]."""
    return {"data": dict({"items": items, "page": 0, "size": 50, "totalItems": len(items), "totalPages": 1 if items else 0,
                          "totalIsEstimate": False, "facets": None, "summary": summary,
                          "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}, **extra)}

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
       {"id": "{{adminRoleId}}", "name": "Administrator", "description": "System-seeded Administrator role", "permissions": ["accounting.read", "cashier.sales.manage", "cashier.sales.read", "factory.costing.approve", "factory.costing.read", "factory.production.manage", "factory.recipes.manage", "factory.setup.manage", "factory.trade.manage", "iam.roles.manage", "iam.users.assign", "iam.users.manage", "inventory.adjustments.manage", "inventory.stock.manage", "inventory.stock.read", "inventory.transfers.manage", "otp.issue", "settings.manage", "showroom.stock.manage", "showroom.stock.read", "tenants.create_child"]},
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
                  "status": "ACTIVE", "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "location": REF("location", "{{factoryId}}"),
                  "accessScope": "SINGLE_LOCATION", "invitedAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('memberUserId', json.data.userId);"],
   desc="Run Factory Setup > Create Factory first so {{factoryId}} is populated, or leave locationId null for ALL_LOCATIONS.")

ep("02 - Tenants & Members", "List Members", "GET", "/api/v1/tenants/members",
   query=[{"key": "q", "value": "", "disabled": True}, {"key": "status", "value": "ACTIVE", "disabled": True}, {"key": "page", "value": "0"}, {"key": "size", "value": "50"}],
   desc="Paged, name order. q matches name (trigram) or e-mail (prefix); status narrows (ACTIVE, SUSPENDED, INVITED).",
   resp=PAGED([
       {"userId": "{{userId}}", "email": "admin@apex.com", "displayName": "Alice Admin", "phone": None, "status": "ACTIVE",
        "role": {"id": "{{adminRoleId}}", "name": "Administrator"}, "location": None, "accessScope": "ALL_LOCATIONS", "invitedAt": NOW},
       {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": "+2348000000000", "status": "ACTIVE",
        "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "location": REF("location", "{{factoryId}}"), "accessScope": "SINGLE_LOCATION", "invitedAt": NOW},
   ]))

ep("02 - Tenants & Members", "Update Member Status", "PUT", "/api/v1/tenants/members/{{memberUserId}}/status",
   body={"status": "SUSPENDED"},
   resp={"data": {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": None, "status": "SUSPENDED",
                  "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "location": REF("location", "{{factoryId}}"), "accessScope": "SINGLE_LOCATION", "invitedAt": NOW}})

ep("02 - Tenants & Members", "Update Member Location", "PUT", "/api/v1/tenants/members/{{memberUserId}}/location",
   body={"locationId": None},
   resp={"data": {"userId": "{{memberUserId}}", "email": "keeper@apex.com", "displayName": "Kelvin Keeper", "phone": None, "status": "ACTIVE",
                  "role": {"id": "{{stockKeeperRoleId}}", "name": "Stock Keeper"}, "location": None, "accessScope": "ALL_LOCATIONS", "invitedAt": NOW}},
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
       {"vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "rate": 7.50, "isActive": False, "activatedAt": "1970-01-01T00:00:00Z"},
       {"vatCategory": REF("vatCategory", "{{zeroRatedVatCategoryId}}"), "rate": 0.00, "isActive": False, "activatedAt": "1970-01-01T00:00:00Z"},
       {"vatCategory": REF("vatCategory", "{{exemptVatCategoryId}}"), "rate": 0.00, "isActive": False, "activatedAt": "1970-01-01T00:00:00Z"},
   ]},
   desc="Every tenant starts with none of the 3 categories activated -- run Select Tenant VAT Categories below before registering any stock item, which requires an active vatCategoryId.")

ep("04 - VAT Control", "Select Tenant VAT Categories", "PUT", "/api/v1/account/vat-control/tenant-vat-categories",
   body={"vatCategoryCodes": ["STANDARD_RATE", "ZERO_RATED", "EXEMPT"]},
   resp={"data": [
       {"vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "rate": 7.50, "isActive": True, "activatedAt": NOW},
       {"vatCategory": REF("vatCategory", "{{zeroRatedVatCategoryId}}"), "rate": 0.00, "isActive": True, "activatedAt": NOW},
       {"vatCategory": REF("vatCategory", "{{exemptVatCategoryId}}"), "rate": 0.00, "isActive": True, "activatedAt": NOW},
   ]},
   desc="Replaces the tenant's active VAT category set with exactly the codes given -- this call activates all 3. A stock item's vatCategoryId must be one the tenant has activated here, or registration fails with VAT_CATEGORY_NOT_ACTIVE.")

# ============================== SETTINGS ==============================
F = "05 - Settings"
PROFILE = {
    "tradingName": "Apex Industries Ltd", "legalName": "Apex Industries Limited", "registrationNumber": "RC-1849302",
    "tin": "33467982-0001", "vatRegistrationNumber": "NG-VAT-4471029", "nrsBusinessId": None,
    "businessDescription": "Beverage manufacturing and distribution", "email": "info@apex.ng", "phone": "+234 803 412 7781",
    "website": "https://apex.ng", "streetAddress": "12 Industrial Way", "city": "Aba", "postalCode": "450001",
    "stateCode": "NG-AB", "stateName": "Abia", "lgaCode": "NG-AB-ANO", "lgaName": "Aba North", "countryCode": "NG",
    "hasLogo": False, "logoUrl": None, "logoUrlExpiresAt": None, "logoContentType": None, "logoInDocuments": False, "logoUpdatedAt": None, "documentFooter": "Goods sold in good condition are not returnable except under our returns policy.",
    "financialYearStartMonth": 1, "nrsReadiness": {"ready": False, "missing": ["nrsBusinessId"]},
    "updatedBy": USER, "updatedAt": NOW,
}
PROFILE_BODY = {k: PROFILE[k] for k in (
    "tradingName", "legalName", "registrationNumber", "tin", "vatRegistrationNumber", "businessDescription", "email", "phone",
    "website", "streetAddress", "city", "postalCode", "stateCode", "lgaCode", "countryCode", "documentFooter", "financialYearStartMonth")}
CUSTOM_FIELD = {"id": "{{customFieldId}}", "entityType": "CUSTOMER", "fieldKey": "preferred_delivery_day", "label": "Preferred Delivery Day",
                "dataType": "SELECT", "options": ["Monday", "Wednesday", "Friday"], "required": False, "status": "ACTIVE",
                "sortOrder": 10, "usageCount": 0, "createdAt": NOW}

ep(F, "A1 Get Company Profile", "GET", "/api/v1/settings/company",
   resp={"data": dict(PROFILE, tradingName="Apex Industries", legalName=None, registrationNumber=None, tin=None, vatRegistrationNumber=None,
                      businessDescription=None, email=None, phone="", website=None, streetAddress="", city=None, postalCode=None,
                      stateCode=None, stateName=None, lgaCode=None, lgaName=None, documentFooter=None, updatedBy=None,
                      nrsReadiness={"ready": False, "missing": ["tin", "email", "streetAddress", "stateCode", "lgaCode", "nrsBusinessId", "businessDescription"]})},
   tests=["const json = pm.response.json();",
          "pm.test('the profile starts from the registered company name', () => pm.expect(json.data.tradingName).to.be.a('string').and.not.empty);"],
   desc="Any member can read it (every screen shows the company name). Every company gets a profile when it registers, named after the company. "
        "nrsReadiness lists the e-invoicing issuer fields still empty: informational only, nothing is blocked this phase.")

ep(F, "A2 Update Company Profile", "PUT", "/api/v1/settings/company", body=PROFILE_BODY, resp={"data": PROFILE},
   tests=["const json = pm.response.json();",
          "pm.test('state and LGA names are resolved', () => { pm.expect(json.data.stateName).to.eql('Abia'); pm.expect(json.data.lgaName).to.eql('Aba North'); });",
          "pm.test('only the NRS business id is still missing', () => pm.expect(json.data.nrsReadiness.missing).to.eql(['nrsBusinessId']));"],
   desc="Perm settings.manage. Replaces the whole profile: optional fields left out are cleared. Required: tradingName (2-200), phone, streetAddress. "
        "tin is 6-30 letters/digits/hyphens (stored upper-case); website must start with http(s)://; the LGA must belong to the state. "
        "New documents use the new values at once; documents already issued keep the issuer they were issued with.")

ep(F, "A3 Update Company Profile (error: LGA not in state)", "PUT", "/api/v1/settings/company",
   body=dict(PROFILE_BODY, stateCode="NG-LA"), status=400, status_text="Bad Request",
   resp={"error": {"code": "INVALID_LGA_CODE", "message": "Unknown LGA code, or the LGA is not in the selected state", "details": {}}},
   expect_error={"status": 400, "code": "INVALID_LGA_CODE"})

ep(F, "A4 Upload Company Logo", "PUT", "/api/v1/settings/company/logo",
   form=[{"key": "file", "src": "postman/fixtures/company-logo.png"}],
   resp={"data": dict(PROFILE, hasLogo=True, logoUrl="https://zifiq-files.s3.eu-north-1.amazonaws.com/tenants/{{tenantId}}/settings.company-logo/2026/10/{{logoFileId}}?X-Amz-Signature=...", logoUrlExpiresAt="2026-10-01T11:15:30Z",
                      logoContentType="image/png", logoInDocuments=True, logoUpdatedAt=NOW)},
   tests=["const json = pm.response.json();",
          "pm.test('logo is set and linked from storage', () => { pm.expect(json.data.hasLogo).to.eql(true); pm.expect(json.data.logoUrl).to.be.a('string'); pm.expect(json.data.logoContentType).to.eql('image/png'); });"],
   desc="multipart/form-data, part `file`: PNG, JPEG, WebP, AVIF or SVG of at most 1 MB, stored in S3 (purpose settings.company-logo). The type is "
        "checked from the file's content, not its name; an SVG with script, event handlers, foreignObject, a DOCTYPE or links to other files "
        "is refused (400 FILE_UNSAFE). The profile's logoUrl is a short-lived (1 h) S3 link that shows the image in place — use it as an "
        "<img src>, and read the profile again for a fresh one. logoInDocuments is false for AVIF and WebP, which the PDF engine can't draw: "
        "invoices and receipts then print the trading name. DELETE on the same path removes the logo (perm settings.manage). "
        "Newman uses postman/fixtures/company-logo.png.")

ep(F, "A5 Upload Company Logo (SVG)", "PUT", "/api/v1/settings/company/logo",
   form=[{"key": "file", "src": "postman/fixtures/company-logo.svg"}],
   resp={"data": dict(PROFILE, hasLogo=True, logoUrl="https://zifiq-files.s3.eu-north-1.amazonaws.com/tenants/{{tenantId}}/settings.company-logo/2026/10/{{logoFileId}}?X-Amz-Signature=...", logoUrlExpiresAt="2026-10-01T11:15:30Z",
                      logoContentType="image/svg+xml", logoInDocuments=True, logoUpdatedAt=NOW)},
   tests=["const json = pm.response.json();",
          "pm.test('the SVG replaced the PNG', () => { pm.expect(json.data.logoContentType).to.eql('image/svg+xml'); pm.expect(json.data.logoInDocuments).to.eql(true); });"],
   desc="SVG logos are drawn as vectors on PDFs. Newman uses postman/fixtures/company-logo.svg.")

ep(F, "A6 Upload Company Logo (error: not an image)", "PUT", "/api/v1/settings/company/logo",
   form=[{"key": "file", "src": "postman/fixtures/sample-attachment.pdf"}], status=400, status_text="Bad Request",
   resp={"error": {"code": "FILE_TYPE_NOT_ALLOWED", "message": "This kind of file (application/pdf) can't be uploaded here",
                    "details": {"detected": "application/pdf", "allowed": "image/avif,image/jpeg,image/png,image/svg+xml,image/webp"}}},
   expect_error={"status": 400, "code": "FILE_TYPE_NOT_ALLOWED"})

ep(F, "B1 Get Onboarding Status", "GET", "/api/v1/settings/onboarding",
   resp={"data": {"completed": False, "completedAt": None, "steps": [
       {"key": "COMPANY", "done": True, "skipped": False}, {"key": "BRANCH", "done": False, "skipped": False},
       {"key": "FACTORY", "done": False, "skipped": False}]}},
   tests=["const json = pm.response.json();",
          "pm.test('company step is done after A2', () => pm.expect(json.data.steps[0]).to.include({key: 'COMPANY', done: true}));"],
   desc="COMPANY is done once trading name, phone and street address are set; BRANCH once the company has a showroom; FACTORY once it has a factory.")

ep(F, "B2 Complete Onboarding (error: unfinished step not skipped)", "POST", "/api/v1/settings/onboarding/complete",
   body={"skippedSteps": ["FACTORY"]}, status=409, status_text="Conflict",
   resp={"error": {"code": "ONBOARDING_INCOMPLETE", "message": "Complete the company details before finishing onboarding", "details": {"missing": "BRANCH"}}},
   expect_error={"status": 409, "code": "ONBOARDING_INCOMPLETE"},
   desc="Every step must be done or listed in skippedSteps. No showroom exists yet at this point of the run, so BRANCH is unfinished.")

ep(F, "B3 Complete Onboarding", "POST", "/api/v1/settings/onboarding/complete",
   body={"skippedSteps": ["BRANCH", "FACTORY"]},
   resp={"data": {"completed": True, "completedAt": NOW, "steps": [
       {"key": "COMPANY", "done": True, "skipped": False}, {"key": "BRANCH", "done": False, "skipped": True},
       {"key": "FACTORY", "done": False, "skipped": True}]}},
   tests=["const json = pm.response.json();", "pm.test('completed', () => pm.expect(json.data.completed).to.eql(true));"],
   desc="Perm settings.manage. Only BRANCH and FACTORY can be skipped. The wizard's branch and factory forms call the Showroom and Factory Setup create endpoints.")

ep(F, "C1 List Module Settings", "GET", "/api/v1/settings/module-settings",
   resp={"data": [{"module": "inventory", "editable": True, "settings": [
       {"key": "inventory.adjustment.otpThresholdQty", "label": "Quantity adjustments above this quantity need an Admin-issued OTP",
        "type": "DECIMAL", "value": 50, "default": 50, "isDefault": True, "min": 1, "max": 1000000, "options": [],
        "updatedBy": None, "updatedAt": None}]}]},
   desc="Grouped by module. Holders of settings.module-settings.manage see and may change everything; anyone else sees, read-only (editable: false), "
        "the settings of modules where they hold a <module>.*.manage permission. Filters: q (key, label, module), module, type, modified=true (changed from default).")

ep(F, "C2 Change Module Setting", "PUT", "/api/v1/settings/module-settings/inventory.adjustment.otpThresholdQty",
   body={"value": 100},
   resp={"data": {"key": "inventory.adjustment.otpThresholdQty", "label": "Quantity adjustments above this quantity need an Admin-issued OTP",
                  "type": "DECIMAL", "value": 100, "default": 50, "isDefault": False, "min": 1, "max": 1000000, "options": [],
                  "updatedBy": USER, "updatedAt": NOW}},
   tests=["const json = pm.response.json();", "pm.test('value changed', () => pm.expect(json.data.isDefault).to.eql(false));"],
   desc="Perm settings.module-settings.manage. Validated against the setting's type and bounds; takes effect on the next request, no restart.")

ep(F, "C3 Change Module Setting (error: out of range)", "PUT", "/api/v1/settings/module-settings/inventory.adjustment.otpThresholdQty",
   body={"value": 0}, status=400, status_text="Bad Request",
   resp={"error": {"code": "SETTING_VALUE_INVALID", "message": "Invalid value for 'inventory.adjustment.otpThresholdQty': must be at least 1",
                   "details": {"key": "inventory.adjustment.otpThresholdQty", "reason": "must be at least 1"}}},
   expect_error={"status": 400, "code": "SETTING_VALUE_INVALID"})

ep(F, "C4 Reset Module Setting", "DELETE", "/api/v1/settings/module-settings/inventory.adjustment.otpThresholdQty",
   resp={"data": {"key": "inventory.adjustment.otpThresholdQty", "label": "Quantity adjustments above this quantity need an Admin-issued OTP",
                  "type": "DECIMAL", "value": 50, "default": 50, "isDefault": True, "min": 1, "max": 1000000, "options": [],
                  "updatedBy": None, "updatedAt": None}},
   tests=["const json = pm.response.json();", "pm.test('back to the default', () => pm.expect(json.data.isDefault).to.eql(true));"],
   desc="Back to the default (the later Inventory folders rely on the default threshold of 50).")

ep(F, "D1 Create Custom Field", "POST", "/api/v1/settings/custom-fields",
   body={"entityType": "CUSTOMER", "label": "Preferred Delivery Day", "dataType": "SELECT", "options": ["Monday", "Wednesday", "Friday"], "required": False},
   resp={"data": CUSTOM_FIELD},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('customFieldId', json.data.id);",
          "pm.test('key derived from the label', () => pm.expect(json.data.fieldKey).to.eql('preferred_delivery_day'));"],
   desc="Perm settings.custom-fields.manage. entityType STOCK_ITEM, CUSTOMER or SUPPLIER; dataType TEXT, NUMBER, DATE, BOOLEAN or SELECT "
        "(options only for SELECT: 1-50 distinct values). The key is derived from the label and never changes, nor does the type. "
        "A required field can only be created when no existing record would be left blank (409 FIELD_HAS_BLANKS).")

ep(F, "D2 Create Custom Field (error: duplicate label)", "POST", "/api/v1/settings/custom-fields",
   body={"entityType": "CUSTOMER", "label": "preferred delivery day", "dataType": "TEXT"}, status=409, status_text="Conflict",
   resp={"error": {"code": "DUPLICATE_NAME", "message": "A custom field called 'preferred delivery day' already exists",
                   "details": {"name": "preferred delivery day", "existingId": "{{customFieldId}}"}}},
   expect_error={"status": 409, "code": "DUPLICATE_NAME"},
   desc="Labels are unique per entity type, ignoring case. details.existingId lets the stock-item form's \"Add custom field\" reuse the existing field.")

ep(F, "D3 List Custom Fields", "GET", "/api/v1/settings/custom-fields",
   query=[{"key": "entityType", "value": "CUSTOMER"}],
   resp={"data": [CUSTOM_FIELD]},
   desc="Any member (forms need the definitions). Unpaged. Filters: q (key, label, options), entityType, status, dataType, required, inUse; "
        "sort=sortOrder (default), label or usageCount[,asc|desc]. usageCount = records holding a value.")

ep(F, "D4 Update Custom Field", "PUT", "/api/v1/settings/custom-fields/{{customFieldId}}",
   body={"label": "Preferred Delivery Day", "options": ["Monday", "Wednesday", "Friday", "Saturday"], "required": False},
   resp={"data": dict(CUSTOM_FIELD, options=["Monday", "Wednesday", "Friday", "Saturday"])},
   desc="label and required are replaced; options and sortOrder are kept when left out. Removing an option some record uses is refused "
        "(409 OPTION_IN_USE), as is making the field required while records lack a value (409 FIELD_HAS_BLANKS).")

ep(F, "D5 Deactivate Custom Field", "PUT", "/api/v1/settings/custom-fields/{{customFieldId}}/status",
   body={"status": "INACTIVE"}, resp={"data": dict(CUSTOM_FIELD, options=["Monday", "Wednesday", "Friday", "Saturday"], status="INACTIVE")},
   desc="Hidden from forms; values already stored stay and are still shown read-only. Send ACTIVE to bring it back.")

ep(F, "E1 Search Settings", "GET", "/api/v1/settings/search", query=[{"key": "q", "value": "logo"}],
   resp={"data": [{"type": "screen", "label": "Company logo", "detail": None, "route": "/settings/company/logo"}]},
   tests=["const json = pm.response.json();",
          "pm.test('finds the logo screen', () => pm.expect(json.data.map(h => h.route)).to.include('/settings/company/logo'));"],
   desc="Settings screens, module settings and custom fields the caller can see (e.g. q=otp finds the adjustment threshold). "
        "Also part of the header search (GET /api/v1/search) as type 'setting'.")

# ============================== FACTORY SETUP ==============================
ep("06 - Factory Setup", "Create Factory", "POST", "/api/v1/factory/setup/factories",
   body={"name": "Apex Plant A", "address": "12 Industrial Way, Lagos", "state": "Lagos", "phone": "+234-800-100-0001",
         "vatRate": 7.5, "vatEnabled": True, "vatRegistrationNumber": "VAT-APEX-001"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A", "address": "12 Industrial Way, Lagos", "state": "Lagos",
                  "phone": "+234-800-100-0001", "manager": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('factoryId', json.data.id);"],
   desc="code auto-generates (FAC-1, FAC-2, ...) when omitted.")

ep("06 - Factory Setup", "Create Second Factory (for Transfers/Trade)", "POST", "/api/v1/factory/setup/factories",
   body={"name": "Apex Plant B", "address": "4 Coastal Road, Port Harcourt", "state": "Rivers"},
   resp={"data": {"id": "{{factoryId2}}", "code": "FAC-2", "name": "Apex Plant B", "address": "4 Coastal Road, Port Harcourt",
                  "state": "Rivers", "phone": None, "manager": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": None, "status": "ACTIVE"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('factoryId2', json.data.id);"])

ep("06 - Factory Setup", "List Locations to Switch Into", "GET", "/api/v1/auth/locations",
   resp={"data": [{"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A", "type": "FACTORY", "active": True},
                  {"id": "{{factoryId2}}", "code": "FAC-2", "name": "Apex Plant B", "type": "FACTORY", "active": True}]},
   tests=["const json = pm.response.json();", "pm.test('both factories can be entered', () => pm.expect(json.data.map(l => l.id)).to.include(pm.collectionVariables.get('factoryId')));"],
   desc="The factory/showroom switcher: a user assigned to a location gets only that one; anyone else every factory and showroom of the tenant.")

ep("06 - Factory Setup", "Switch Location (work inside one factory)", "POST", "/api/v1/auth/switch-location",
   body={"locationId": "{{factoryId}}"},
   resp={"data": {"accessToken": "{{accessToken}}", "refreshToken": "{{refreshToken}}", "tokenType": "Bearer",
                  "expiresInSeconds": 3600, "activeTenantId": "{{tenantId}}", "userId": "{{userId}}", "activeLocationId": "{{factoryId}}"}},
   tests=["const json = pm.response.json();", "pm.test('the session now works in the factory', () => pm.expect(json.data.activeLocationId).to.eql(pm.collectionVariables.get('factoryId')));"],
   desc="New tokens confined to one factory or showroom: from then on every list, report, read and write in every module shows and touches only "
        "that location's data (another factory's records are 'not found', writes there are 403 LOCATION_ACCESS_DENIED). The location survives "
        "token refresh; switch-tenant clears it. {\"locationId\": null} returns to the whole company (only for users not assigned to a location). "
        "This collection keeps its company-wide token so the later folders can work across factories; a client stores the returned tokens.")

ep("06 - Factory Setup", "List Factories", "GET", "/api/v1/factory/setup/factories",
   resp={"data": [{"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A", "address": "12 Industrial Way, Lagos", "state": "Lagos",
                   "phone": "+234-800-100-0001", "manager": None, "vatRate": 7.5, "vatEnabled": True,
                   "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}]})

ep("06 - Factory Setup", "Update Factory", "PUT", "/api/v1/factory/setup/factories/{{factoryId}}",
   body={"name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos", "state": "Lagos", "phone": "+234-800-100-0099",
         "vatRate": 7.5, "vatEnabled": True, "vatRegistrationNumber": "VAT-APEX-001"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos",
                  "state": "Lagos", "phone": "+234-800-100-0099", "manager": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "ACTIVE"}})

ep("06 - Factory Setup", "Update Factory Status", "PUT", "/api/v1/factory/setup/factories/{{factoryId}}/status",
   body={"status": "SUSPENDED"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos",
                  "state": "Lagos", "phone": "+234-800-100-0099", "manager": None, "vatRate": 7.5, "vatEnabled": True,
                  "vatRegistrationNumber": "VAT-APEX-001", "status": "SUSPENDED"}})

ep("06 - Factory Setup", "Reactivate Factory", "PUT", "/api/v1/factory/setup/factories/{{factoryId}}/status",
   body={"status": "ACTIVE"},
   resp={"data": {"id": "{{factoryId}}", "code": "FAC-1", "name": "Apex Plant A (Updated)", "address": "12 Industrial Way, Lagos",
                  "state": "Lagos", "phone": "+234-800-100-0099", "manager": None, "vatRate": 7.5, "vatEnabled": True,
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
   desc="type is one of FINISHED_PRODUCT, RAW_MATERIAL, PACKAGING (case-insensitive). Names are unique per company, ignoring case (409 DUPLICATE_NAME).")

ep("06 - Factory Setup", "Register Category (error: duplicate name)", "POST", "/api/v1/factory/setup/categories",
   body={"name": "chemicals", "type": "RAW_MATERIAL"},
   status=409, status_text="Conflict", resp={"error": {"code": "DUPLICATE_NAME", "message": "'chemicals' is already in use", "details": {"name": "chemicals"}}},
   expect_error={"status": 409, "code": "DUPLICATE_NAME"},
   desc="'Chemicals' already exists; names are compared ignoring case.")

ep("06 - Factory Setup", "Register Category (error: unknown type)", "POST", "/api/v1/factory/setup/categories",
   body={"name": "Bottles", "type": "PACKAGING_MATERIAL"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "VALIDATION_FAILED", "message": "Request validation failed", "details": {"type": "must be FINISHED_PRODUCT, RAW_MATERIAL or PACKAGING"}}},
   expect_error={"status": 400, "code": "VALIDATION_FAILED"})

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
   resp={"data": {"id": "{{rawMaterialId}}", "location": REF("location", "{{factoryId}}"), "itemType": "RAW_MATERIAL", "code": "ITM-1",
                  "name": "Cocoa Powder", "category": REF("category", "{{categoryId}}"), "brand": None, "unitOfMeasure": REF("unit", "{{unitId}}"),
                  "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                  "vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "hsnSection": REF("hsnSection", "{{hsnSectionId}}"), "isicSection": REF("isicSection", "{{isicSectionId}}"),
                  "status": "ACTIVE", "createdAt": NOW, "quantityOnHand": 0.000}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('rawMaterialId', json.data.id);"],
   desc="itemType is one of FINISHED_PRODUCT, RAW_MATERIAL, IN_HOUSE_RECIPE, OUTSOURCED. vatCategoryId/hsnSectionId/isicSectionId are required on every product -- run 04 - VAT Control first (List VAT Categories, List HSN Sections, List ISIC Sections, Select Tenant VAT Categories) to populate these variables. vatCategoryId must be one the active tenant has activated, or this returns 400 VAT_CATEGORY_NOT_ACTIVE.")

ep("07 - Inventory Stock", "Register Finished Product", "POST", "/api/v1/inventory/stock-items",
   body={"locationId": "{{factoryId}}", "itemType": "FINISHED_PRODUCT", "code": None, "name": "Premium Blend",
         "categoryId": None, "brand": None, "unitOfMeasureId": "{{unitId}}", "reorderLevel": 5, "barcode": None,
         "landingCost": None, "markupCost": None,
         "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}"},
   resp={"data": {"id": "{{finishedProductId}}", "location": REF("location", "{{factoryId}}"), "itemType": "FINISHED_PRODUCT", "code": "ITM-2",
                  "name": "Premium Blend", "category": None, "brand": None, "unitOfMeasure": REF("unit", "{{unitId}}"),
                  "reorderLevel": 5.000, "barcode": None, "landingCost": None, "markupCost": None,
                  "vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "hsnSection": REF("hsnSection", "{{hsnSectionId}}"), "isicSection": REF("isicSection", "{{isicSectionId}}"),
                  "status": "ACTIVE", "createdAt": NOW, "quantityOnHand": 0.000}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('finishedProductId', json.data.id);"])

ep("07 - Inventory Stock", "List Stock Items", "GET", "/api/v1/inventory/stock-items",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"id": "{{rawMaterialId}}", "location": REF("location", "{{factoryId}}"), "itemType": "RAW_MATERIAL", "code": "ITM-1",
                   "name": "Cocoa Powder", "category": REF("category", "{{categoryId}}"), "brand": None, "unitOfMeasure": REF("unit", "{{unitId}}"),
                   "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                   "vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "hsnSection": REF("hsnSection", "{{hsnSectionId}}"), "isicSection": REF("isicSection", "{{isicSectionId}}"),
                   "status": "ACTIVE", "createdAt": NOW, "quantityOnHand": 0.000}]),
   tests=["const json = pm.response.json();",
          "pm.test('every item carries its quantity on hand', () => json.data.items.forEach(i => pm.expect(i.quantityOnHand).to.be.a('number')));"],
   desc="The item register for a location. quantityOnHand is the stock at locationId (including its storage areas); without locationId it is "
        "the total across every location you can see. Filters: locationId (the items' home location), itemType, status.")

ep("07 - Inventory Stock", "Update Stock Item Status", "PUT", "/api/v1/inventory/stock-items/{{rawMaterialId}}/status",
   body={"status": "SUSPENDED"},
   resp={"data": {"id": "{{rawMaterialId}}", "location": REF("location", "{{factoryId}}"), "itemType": "RAW_MATERIAL", "code": "ITM-1",
                  "name": "Cocoa Powder", "category": REF("category", "{{categoryId}}"), "brand": None, "unitOfMeasure": REF("unit", "{{unitId}}"),
                  "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                  "vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "hsnSection": REF("hsnSection", "{{hsnSectionId}}"), "isicSection": REF("isicSection", "{{isicSectionId}}"),
                  "status": "SUSPENDED", "createdAt": NOW, "quantityOnHand": 0.000}})

ep("07 - Inventory Stock", "Reactivate Stock Item", "PUT", "/api/v1/inventory/stock-items/{{rawMaterialId}}/status",
   body={"status": "ACTIVE"},
   resp={"data": {"id": "{{rawMaterialId}}", "location": REF("location", "{{factoryId}}"), "itemType": "RAW_MATERIAL", "code": "ITM-1",
                  "name": "Cocoa Powder", "category": REF("category", "{{categoryId}}"), "brand": None, "unitOfMeasure": REF("unit", "{{unitId}}"),
                  "reorderLevel": 10.000, "barcode": None, "landingCost": 5.00, "markupCost": None,
                  "vatCategory": REF("vatCategory", "{{standardRateVatCategoryId}}"), "hsnSection": REF("hsnSection", "{{hsnSectionId}}"), "isicSection": REF("isicSection", "{{isicSectionId}}"),
                  "status": "ACTIVE", "createdAt": NOW}},
   desc="Reverses the previous request -- everything from here on (Refill, Transfers, Production, Trade) needs this item ACTIVE.")

ep("07 - Inventory Stock", "Refill Finished Product", "POST", "/api/v1/inventory/stock/refill/finished-product",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{finishedProductId}}", "quantity": 50, "note": "Initial stocking"},
   resp={"data": {"ref": "RF-1", "stockItem": REF("stockItem", "{{finishedProductId}}"), "quantity": 50, "performedBy": USER, "at": NOW}})

ep("07 - Inventory Stock", "Refill Raw Material (Receive)", "POST", "/api/v1/inventory/stock/refill/raw-material",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "supplierId": "{{supplierId}}",
         "manufacturerId": "{{manufacturerId}}", "quantity": 100, "unitCost": 5.00,
         "manufacturedDate": "2026-09-01", "expiryDate": "2027-09-01"},
   resp={"data": {"id": "{{stockReceiptId}}", "ref": "DL-1", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "supplier": REF("supplier", "{{supplierId}}"),
                  "manufacturer": REF("manufacturer", "{{manufacturerId}}"), "quantity": 100.000, "unitCost": 5.00, "manufacturedDate": "2026-09-01",
                  "expiryDate": "2027-09-01", "verifiedBy": None, "offloadedAt": None, "condition": "PENDING_VERIFICATION"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('stockReceiptId', json.data.id);"],
   desc="This is also the Material/Recipe Receive Report's data source (same table, see List Stock Receipts).")

ep("07 - Inventory Stock", "List Stock Receipts (Material Receive Report)", "GET", "/api/v1/inventory/stock/receipts",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"id": "{{stockReceiptId}}", "ref": "DL-1", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "supplier": REF("supplier", "{{supplierId}}"),
                   "manufacturer": REF("manufacturer", "{{manufacturerId}}"), "quantity": 100.000, "unitCost": 5.00, "manufacturedDate": "2026-09-01",
                   "expiryDate": "2027-09-01", "verifiedBy": None, "offloadedAt": None, "condition": "PENDING_VERIFICATION"}]))

ep("07 - Inventory Stock", "Verify Stock Receipt", "POST", "/api/v1/inventory/stock/receipts/{{stockReceiptId}}/verify",
   body={"condition": "ACCEPTED"},
   resp={"data": {"id": "{{stockReceiptId}}", "ref": "DL-1", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "supplier": REF("supplier", "{{supplierId}}"),
                  "manufacturer": REF("manufacturer", "{{manufacturerId}}"), "quantity": 100.000, "unitCost": 5.00, "manufacturedDate": "2026-09-01",
                  "expiryDate": "2027-09-01", "verifiedBy": USER, "offloadedAt": NOW, "condition": "ACCEPTED"}})

ep("07 - Inventory Stock", "Stock Card (per-transaction)", "GET", "/api/v1/inventory/stock/card/{{rawMaterialId}}",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"sn": 1, "date": "2026-10-01", "remark": "DL-1", "qtyIn": 100.000, "qtyOut": 0.000, "runningBalance": 100.000}]))

ep("07 - Inventory Stock", "Bin Card (daily totals)", "GET", "/api/v1/inventory/stock/bin-card/{{rawMaterialId}}",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"sn": 1, "date": "2026-10-01", "qtyIn": 100.000, "qtyOut": 0.000, "balance": 100.000}]))

ep("07 - Inventory Stock", "Comprehensive Stock Report", "GET", "/api/v1/inventory/stock/comprehensive-report",
   query=[{"key": "locationId", "value": "{{factoryId}}"}, {"key": "view", "value": "CATEGORY"}],
   resp=PAGED([{"lineNumber": 1, "groupingLabel": "Chemicals", "stockDetail": "Cocoa Powder", "unitCost": 5.00,
                   "quantityLeft": 100.000, "totalCost": 500.00}]),
   desc="unitCost/totalCost show as 0 for callers without factory.costing.read.")

ep("07 - Inventory Stock", "Stock Movement Report", "GET", "/api/v1/inventory/stock/movement-report",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([
       {"sn": 1, "date": "2026-10-01", "location": REF("location", "{{factoryId}}"), "stockItem": REF("stockItem", "{{rawMaterialId}}"), 
        "sourceType": "RECEIVE", "sourceReference": "DL-1", "qtyIn": 100.000, "qtyOut": 0.000},
       {"sn": 2, "date": "2026-10-01", "location": REF("location", "{{factoryId}}"), "stockItem": REF("stockItem", "{{rawMaterialId}}"), 
        "sourceType": "TRANSFER_OUT", "sourceReference": "WB-1", "qtyIn": 0.000, "qtyOut": 10.000},
   ]),
   desc="Cross-item, cross-movement-type ledger view (unlike Stock Card, which is scoped to a single item) -- every stock-affecting action across every module writes to the same ledger this reads from. All filters (locationId, stockItemId, sourceType, from, to) are optional. sourceType is one of REFILL, RECEIVE, PRODUCTION_RUN, TRANSFER_OUT, TRANSFER_IN, QUANTITY_ADJUSTMENT, TRADE_OUT, TRADE_IN.")

ep("07 - Inventory Stock", "Re-Order Report", "GET", "/api/v1/inventory/stock/re-order-report",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([]),
   desc="Returns items at or below their reorderLevel. Empty here since the sample data stays above threshold.")

# ============================== INVENTORY OPERATIONS ==============================
ep("08 - Inventory Operations", "Dispatch Stock Transfer", "POST", "/api/v1/inventory/stock/transfers",
   body={"sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}", "driverName": "John Driver",
         "driverVehicle": "LAG-123-XY", "lines": [{"stockItemId": "{{rawMaterialId}}", "quantity": 10, "unitPrice": None}]},
   resp={"data": {"id": "{{transferId}}", "waybillRef": "WB-1", "sourceLocation": REF("location", "{{factoryId}}"), "destinationLocation": REF("location", "{{factoryId2}}"),
                  "status": "DISPATCHED", "dispatchedAt": NOW, "receivedAt": None,
                  "lines": [{"id": "{{transferLineId}}", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "qtySent": 10.000, "qtyReceived": None, "qtyRejected": None}]}},
   tests=[
       "const json = pm.response.json();",
       "pm.collectionVariables.set('transferId', json.data.id);",
       "pm.collectionVariables.set('transferLineId', json.data.lines[0].id);",
       "pm.collectionVariables.set('waybillRef', json.data.waybillRef);",
   ],
   desc="Run Create Second Factory first so {{factoryId2}} is populated.")

ep("08 - Inventory Operations", "Receive Stock Transfer", "POST", "/api/v1/inventory/stock/transfers/{{transferId}}/receive",
   body={"lines": [{"stockTransferLineId": "{{transferLineId}}", "qtyReceived": 10, "qtyRejected": None}]},
   resp={"data": {"id": "{{transferId}}", "waybillRef": "WB-1", "sourceLocation": REF("location", "{{factoryId}}"), "destinationLocation": REF("location", "{{factoryId2}}"),
                  "status": "RECEIVED", "dispatchedAt": NOW, "receivedAt": NOW,
                  "lines": [{"id": "{{transferLineId}}", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "qtySent": 10.000, "qtyReceived": 10.000, "qtyRejected": None}]}})

ep("08 - Inventory Operations", "List Stock Transfers", "GET", "/api/v1/inventory/stock/transfers",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"id": "{{transferId}}", "waybillRef": "WB-1", "sourceLocation": REF("location", "{{factoryId}}"), "destinationLocation": REF("location", "{{factoryId2}}"),
                   "status": "RECEIVED", "dispatchedAt": NOW, "receivedAt": NOW,
                   "lines": [{"id": "{{transferLineId}}", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "qtySent": 10.000, "qtyReceived": 10.000, "qtyRejected": None}]}]))

ep("08 - Inventory Operations", "Post Quantity Adjustment (below OTP threshold)", "POST", "/api/v1/inventory/quantity-adjustments",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 3,
         "reason": "DAMAGED_BROKEN", "note": "Bag torn in storage", "otpChallengeId": None},
   resp={"data": {"ref": "QA-1", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "direction": "NEGATIVE", "quantity": 3, "reason": "DAMAGED_BROKEN",
                  "note": "Bag torn in storage", "performedBy": USER, "at": NOW}},
   desc="Adjustments at or below the 50-unit threshold (FactoryOperationsService.ADJUSTMENT_OTP_THRESHOLD) need no OTP.")

ep("08 - Inventory Operations", "Post Quantity Adjustment (above threshold, OTP-gated)", "POST", "/api/v1/inventory/quantity-adjustments",
   body={"locationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}", "direction": "NEGATIVE", "quantity": 60,
         "reason": "STOCKTAKE_VARIANCE", "note": "Annual stocktake correction", "otpChallengeId": "{{otpChallengeId}}"},
   resp={"data": {"ref": "QA-2", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "direction": "NEGATIVE", "quantity": 60, "reason": "STOCKTAKE_VARIANCE",
                  "note": "Annual stocktake correction", "performedBy": USER, "at": NOW}},
   desc="Run the OTP folder's three requests first (request -> issue -> verify) so otpChallengeId is USED, or this returns 403 OTP_REQUIRED.")

ep("08 - Inventory Operations", "List Quantity Adjustments", "GET", "/api/v1/inventory/quantity-adjustments",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": [{"ref": "QA-1", "stockItem": REF("stockItem", "{{rawMaterialId}}"), "direction": "NEGATIVE", "quantity": 3, "reason": "DAMAGED_BROKEN",
                   "note": "Bag torn in storage", "performedBy": USER, "at": NOW}]})

# ============================== FACTORY PRODUCTION ==============================
ep("09 - Factory Production", "Register Recipe", "POST", "/api/v1/factory/recipes",
   body={"locationId": "{{factoryId}}", "code": None, "name": "Premium Blend Base", "description": "Base recipe for Premium Blend",
         "sourcing": "IN_HOUSE_PRODUCTION", "yieldQty": 100, "yieldUnitId": "{{unitId}}", "shelfLifeDays": 365,
         "reorderLevel": 20, "ingredients": [{"rawMaterialStockItemId": "{{rawMaterialId}}", "quantity": 10, "unitId": "{{unitId}}"}]},
   resp={"data": {"id": "{{recipeId}}", "code": "RCP-1", "name": "Premium Blend Base", "location": REF("location", "{{factoryId}}"),
                  "sourcing": "IN_HOUSE_PRODUCTION", "yieldQty": 100, "yieldUnit": REF("unit", "{{unitId}}"), "status": "ACTIVE",
                  "ingredients": [{"rawMaterialStockItem": REF("stockItem", "{{rawMaterialId}}"), "quantity": 10, "unit": REF("unit", "{{unitId}}")}]}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('recipeId', json.data.id);"],
   desc="sourcing is IN_HOUSE_PRODUCTION or DIRECT_IMPORTATION.")

ep("09 - Factory Production", "List Recipes", "GET", "/api/v1/factory/recipes",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"id": "{{recipeId}}", "code": "RCP-1", "name": "Premium Blend Base", "location": REF("location", "{{factoryId}}"),
                   "sourcing": "IN_HOUSE_PRODUCTION", "yieldQty": 100, "yieldUnit": REF("unit", "{{unitId}}"), "status": "ACTIVE",
                   "ingredients": [{"rawMaterialStockItem": REF("stockItem", "{{rawMaterialId}}"), "quantity": 10, "unit": REF("unit", "{{unitId}}")}]}]))

ep("09 - Factory Production", "Run Cost Calculator (QC - blind)", "POST", "/api/v1/factory/production/cost-calculator",
   body={"locationId": "{{factoryId}}", "targetStockItemId": "{{finishedProductId}}", "recipeId": None, "batchSizeQty": 50,
         "materials": [{"stockItemId": "{{rawMaterialId}}", "quantity": 20}], "overheads": [{"label": "Labour", "amount": 30}]},
   resp={"data": {"id": "{{costingId}}", "targetStockItem": REF("stockItem", "{{finishedProductId}}"), "batchSizeQty": 50, "status": "PENDING",
                  "computedBy": USER, "createdAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('costingId', json.data.id);"],
   desc="Deliberately omits computedUnitCost/computedTotalCost -- QC (the typical caller) completes costing blind per the PRD. Pass either materials[] or recipeId.")

ep("09 - Factory Production", "Get Costing Detail (Management/Admin only)", "GET", "/api/v1/factory/production/cost-calculator/{{costingId}}",
   resp={"data": {"id": "{{costingId}}", "targetStockItem": REF("stockItem", "{{finishedProductId}}"), "recipe": None, "batchSizeQty": 50.000,
                  "materialsSnapshot": [{"stockItem": REF("stockItem", "{{rawMaterialId}}"), "name": "Cocoa Powder", "quantity": 20, "unitCost": 5.0}],
                  "overheadSnapshot": [{"label": "Labour", "amount": 30}], "computedUnitCost": 2.60, "computedTotalCost": 130.00,
                  "status": "PENDING", "computedBy": USER, "approvedBy": None, "approvedAt": None, "rejectionReason": None}},
   desc="Requires factory.costing.read -- this is the one endpoint that reveals the figure QC's own calculator just produced.")

ep("09 - Factory Production", "Approve Costing", "POST", "/api/v1/factory/production/cost-calculator/{{costingId}}/approve",
   resp={"data": {"id": "{{costingId}}", "targetStockItem": REF("stockItem", "{{finishedProductId}}"), "recipe": None, "batchSizeQty": 50.000,
                  "materialsSnapshot": [{"stockItem": REF("stockItem", "{{rawMaterialId}}"), "name": "Cocoa Powder", "quantity": 20, "unitCost": 5.0}],
                  "overheadSnapshot": [{"label": "Labour", "amount": 30}], "computedUnitCost": 2.60, "computedTotalCost": 130.00,
                  "status": "APPROVED", "computedBy": USER, "approvedBy": USER, "approvedAt": NOW, "rejectionReason": None}})

ep("09 - Factory Production", "Reject Costing (alternative to Approve)", "POST", "/api/v1/factory/production/cost-calculator/{{costingId}}/reject",
   body={"rejectionReason": "Material substitution needed -- cost too high"},
   resp={"data": {"id": "{{costingId}}", "targetStockItem": REF("stockItem", "{{finishedProductId}}"), "recipe": None, "batchSizeQty": 50.000,
                  "materialsSnapshot": [{"stockItem": REF("stockItem", "{{rawMaterialId}}"), "name": "Cocoa Powder", "quantity": 20, "unitCost": 5.0}],
                  "overheadSnapshot": [{"label": "Labour", "amount": 30}], "computedUnitCost": 2.60, "computedTotalCost": 130.00,
                  "status": "REJECTED", "computedBy": USER, "approvedBy": None, "approvedAt": None,
                  "rejectionReason": "Material substitution needed -- cost too high"}},
   expect_error={"status": 409, "code": "ALREADY_DECIDED"},
   desc="Mutually exclusive with Approve -- in a full sequential run, Approve Costing (just above) already decided this same costing, "
        "so this is expected to get 409 ALREADY_DECIDED, asserted below. Run this in isolation against a fresh costing instead if you "
        "want to see the actual rejection path succeed.")

ep("09 - Factory Production", "Commit Production Run", "POST", "/api/v1/factory/production/runs",
   body={"productionCostingId": "{{costingId}}"},
   resp={"data": {"batchRef": "PB-1", "targetStockItem": REF("stockItem", "{{finishedProductId}}"), "quantityProduced": 50.000,
                  "unitLandingCost": 2.60, "totalLandingCost": 130.00, "runAt": NOW}},
   desc="productionCostingId must reference an APPROVED costing. Consumes the raw materials and credits the finished product in the same transaction.")

ep("09 - Factory Production", "List Production Runs", "GET", "/api/v1/factory/production/runs",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"batchRef": "PB-1", "targetStockItem": REF("stockItem", "{{finishedProductId}}"), "quantityProduced": 50.000,
                   "unitLandingCost": 2.60, "totalLandingCost": 130.00, "runAt": NOW}]))

ep("09 - Factory Production", "Total Cost for Selected Recipes", "GET", "/api/v1/factory/production/total-cost",
   query=[{"key": "recipeIds", "value": "{{recipeId}}"}],
   resp={"data": [{"recipe": REF("recipe", "{{recipeId}}"), "qtyUsed": 50.000, "totalCost": 130.00}]},
   desc="Perm factory.costing.read. Quantity produced and total landing cost over every run costed from each recipe; recipeIds is repeatable "
        "or comma-separated (at most 200). Every id must be a recipe of this tenant, else 404 RECIPE_NOT_FOUND.")

ep("09 - Factory Production", "Total Cost for Selected Recipes (error: unknown recipe)", "GET", "/api/v1/factory/production/total-cost",
   query=[{"key": "recipeIds", "value": "00000000-0000-4000-8000-000000000000"}], status=404, status_text="Not Found",
   resp={"error": {"code": "RECIPE_NOT_FOUND", "message": "Recipe not found", "details": {}}},
   expect_error={"status": 404, "code": "RECIPE_NOT_FOUND"},
   desc="A recipe id that isn't this tenant's (another tenant's recipe is invisible under row-level security) answers 404, not 500.")

ep("09 - Factory Production", "Register Special-Production Client", "POST", "/api/v1/factory/special-production/clients",
   body={"name": "Acme Contractors Ltd", "terms": "Net 30"},
   resp={"data": {"id": "{{specialClientId}}", "name": "Acme Contractors Ltd", "terms": "Net 30"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('specialClientId', json.data.id);"])

ep("09 - Factory Production", "Create Special Production Run", "POST", "/api/v1/factory/special-production/runs",
   body={"locationId": "{{factoryId}}", "clientId": "{{specialClientId}}", "targetStockItemId": None,
         "productDescription": "Custom emulsion batch for Acme", "quantity": 25, "unitId": "{{unitId}}",
         "unitCost": 4.00, "price": 7.50, "materials": [{"stockItemId": "{{rawMaterialId}}", "quantity": 5}]},
   resp={"data": {"id": "{{specialRunId}}", "orderRef": "SO-1", "batchRef": "SB-1", "client": REF("specialProductionClient", "{{specialClientId}}"),
                  "quantity": 25.000, "price": 7.50, "status": "IN_PRODUCTION"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('specialRunId', json.data.id);"])

ep("09 - Factory Production", "Update Special Production Status", "PUT", "/api/v1/factory/special-production/runs/{{specialRunId}}/status",
   body={"status": "AWAITING_DISPATCH"},
   resp={"data": {"id": "{{specialRunId}}", "orderRef": "SO-1", "batchRef": "SB-1", "client": REF("specialProductionClient", "{{specialClientId}}"),
                  "quantity": 25.000, "price": 7.50, "status": "AWAITING_DISPATCH"}},
   desc="status is one of IN_PRODUCTION, AWAITING_DISPATCH, DELIVERED.")

ep("09 - Factory Production", "List Special Production Runs", "GET", "/api/v1/factory/special-production/runs",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"id": "{{specialRunId}}", "orderRef": "SO-1", "batchRef": "SB-1", "client": REF("specialProductionClient", "{{specialClientId}}"),
                   "quantity": 25.000, "price": 7.50, "status": "AWAITING_DISPATCH"}]))

# ============================== FACTORY TRADING ==============================
ep("10 - Factory Trading", "Offer Trade", "POST", "/api/v1/factory/trades/offer",
   body={"fromLocationId": "{{factoryId2}}", "toLocationId": "{{factoryId}}", "stockItemId": "{{rawMaterialId}}",
         "quantity": 5, "reason": "SURPLUS", "expiresAt": "2026-12-31T00:00:00Z"},
   resp={"data": {"id": "{{tradeOfferId}}", "ref": "FT-1", "fromLocation": REF("location", "{{factoryId2}}"), "toLocation": REF("location", "{{factoryId}}"),
                  "stockItem": REF("stockItem", "{{rawMaterialId}}"), "quantity": 5.000, "reason": "SURPLUS", "status": "PENDING",
                  "offeredAt": NOW, "expiresAt": "2026-12-31T00:00:00Z"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('tradeOfferId', json.data.id);"],
   desc="reason is one of SURPLUS, EXPIRY, CHANGEOVER, SCHEDULE, DISCONTINUED. Requires the receiving factory (fromLocationId) to actually hold the stock.")

ep("10 - Factory Trading", "Respond to Trade (Accept)", "POST", "/api/v1/factory/trades/{{tradeOfferId}}/respond",
   body={"decision": "ACCEPTED"},
   resp={"data": {"id": "{{tradeOfferId}}", "ref": "FT-1", "fromLocation": REF("location", "{{factoryId2}}"), "toLocation": REF("location", "{{factoryId}}"),
                  "stockItem": REF("stockItem", "{{rawMaterialId}}"), "quantity": 5.000, "reason": "SURPLUS", "status": "ACCEPTED",
                  "offeredAt": NOW, "expiresAt": "2026-12-31T00:00:00Z"}})

ep("10 - Factory Trading", "List Trade Offers", "GET", "/api/v1/factory/trades",
   query=[{"key": "locationId", "value": "{{factoryId}}"}],
   resp=PAGED([{"id": "{{tradeOfferId}}", "ref": "FT-1", "fromLocation": REF("location", "{{factoryId2}}"), "toLocation": REF("location", "{{factoryId}}"),
                   "stockItem": REF("stockItem", "{{rawMaterialId}}"), "quantity": 5.000, "reason": "SURPLUS", "status": "ACCEPTED",
                   "offeredAt": NOW, "expiresAt": "2026-12-31T00:00:00Z"}]))

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
   desc="postal_address.lga, filtered by Nigeria state code. All 774 LGAs, under NRS's own codes (V14_11); names corrected where NRS misspells them.")


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


def ERR(code, message, details=None):
    return {"error": {"code": code, "message": message, "details": details or {}}}


CORR = {"id": "{{correspondentId}}", "code": "CR-1", "name": "Bisi Adeyemi", "phone": "0802 123 4567", "email": None,
        "region": "Lagos Mainland", "location": REF("location", "{{customerLocationId}}"), "status": "ASSIGNED",
        "assignedAt": "2026-10-08", "customerCount": 1, "dealerCount": 1, "responsibilities": "Lagos dealers", "userLinked": False}
CORR2 = dict(CORR, id="{{correspondent2Id}}", code="CR-2", name="Emeka Eze", phone="0805 999 0000", region="South West",
             responsibilities="Ibadan and Abeokuta walk-in accounts", customerCount=0, dealerCount=0)
HISTORY_ROW = {"action": "ASSIGNED", "region": "Lagos Mainland", "location": REF("location", "{{customerLocationId}}"), 
               "effectiveFrom": "2026-10-08", "note": None, "performedBy": USER, "createdAt": NOW}
CATEGORY = {"id": "{{diamondCategoryId}}", "name": "Diamond", "discountPct": 7.50, "suggestedCreditLimit": 5000000.00, "sortOrder": 5,
            "status": "ACTIVE", "dealerCount": 0}
ENTRY = {"id": "{{adjustmentEntryId}}", "customer": REF("customer", "{{dealerId}}"), "balanceType": "WALLET", "entryType": "ADJUSTMENT_CREDIT",
         "amount": 2500.00, "balanceAfter": 2500.00, "reference": "WLT-1"}
RECLASS = {"id": "{{reclassificationId}}", "customer": REF("customer", "{{customerId}}"), "customerCode": "CU-1", "customerName": "Chidi Nwosu",
           "fromType": "ORDINARY_CUSTOMER", "toType": "SPECIAL_CUSTOMER", "direction": "PROMOTION", "reason": "MET_PURCHASE_VOLUME",
           "note": "Crossed N5m in 90 days", "effectiveFrom": "2026-10-08", "status": "APPLIED", "fromCreditLimit": 0.00,
           "toCreditLimit": 0.00, "toDealerCategoryName": None, "walletBalanceCarriedOver": 0.00, "authorisedBy": USER,
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
   resp=PAGED([CORR, CORR2]),
   desc="Paged (page, size). Query: q (code, name, phone, e-mail, responsibilities), status (repeatable), region (repeatable), locationId, userLinked, hasCustomers, "
        "sort=name|customerCount|dealerCount|assignedAt. customerCount/dealerCount are kept on the correspondent row, so sorting by them is a plain index read.")

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
   resp=PAGED([SUMMARY], facets={"type": [{"value": "AUTHORISED_DEALER", "label": "Authorised Dealer", "count": 1}],
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
   resp=PAGED([SUMMARY]),
   tests=["const json = pm.response.json();",
          "pm.test('phone matches regardless of format', () => pm.expect(json.data.items.map(c => c.id)).to.eql([pm.collectionVariables.get('dealerId')]));"],
   desc="The dealer was registered as \"0803 412 7781\"; 08034127781, +2348034127781 and 0803 412 7781 all find it.")

ep(C, "E3 Filter Customers (simple filters)", "GET", "/api/v1/customers",
   query=[{"key": "type", "value": "AUTHORISED_DEALER"}, {"key": "type", "value": "SPECIAL_CUSTOMER"},
          {"key": "creditLimit.gte", "value": "200000"}, {"key": "sort", "value": "creditLimit,desc"}],
   resp=PAGED([SUMMARY]),
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
                  "balanceAfter": 2500.00, "location": REF("location", "{{customerLocationId}}"), "sourceModule": "customers", "performedBy": USER,
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
   resp=PAGED([dict(SUMMARY, id="{{specialId}}", code="CU-3", type="SPECIAL_CUSTOMER", name="Adaeze Foods Ltd")]),
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
   resp=PAGED([dict(RECLASS, status="CANCELLED"), RECLASS]),
   tests=["const json = pm.response.json();", "pm.test('both kept in history', () => pm.expect(json.data.totalItems).to.eql(2));"],
   desc="History, newest effective date first. Filters: customerId, direction, fromType, toType, reason, status, effectiveFrom (range/period), "
        "authorisedBy, createdAt; q = customer code or name, note.")

ep(C, "G8 Search Reclassifications (advanced)", "POST", "/api/v1/customers/reclassifications/search",
   body={"filter": {"all": [{"field": "direction", "op": "eq", "value": "PROMOTION"}, {"field": "status", "op": "eq", "value": "APPLIED"}]}},
   resp=PAGED([RECLASS]))

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
   resp=PAGED([SUMMARY]),
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
       "sourceId": "{{transferId}}", "location": REF("location", "{{factoryId}}"), 
       "partyName": "Apex Plant B", "partyCustomer": None, "valueAmount": None, "issuedBy": USER,
       "issuedAt": NOW, "status": "ISSUED", "reprintCount": 0, "pdfUrl": None}

ep(D, "List Issued Documents", "GET", "/api/v1/documents",
   query=[{"key": "type", "value": "WAYBILL"}],
   resp=PAGED([DOC]),
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
   resp=PAGED([dict(DOC, reprintCount=1)]))

ep(D, "Document Filter Metadata", "GET", "/api/v1/documents/filters",
   resp={"data": {"fields": [], "collections": [], "primaryDate": "issuedAt", "defaultSort": [{"field": "issuedAt", "dir": "desc"}], "periodPresets": []}})

# ============================== FILES (docs/03 §8.4) ==============================
F = "14 - Files"
FILE = {"id": "{{fileId}}", "purpose": "general.attachment", "originalName": "sample-attachment.pdf", "contentType": "application/pdf",
        "sizeBytes": 160, "sha256": "9f2b5c0e7a1d4b3c8e6f2a9d0c1b7e4f3a2d5c8b9e0f1a2b3c4d5e6f7a8b9c0d1", "status": "ACTIVE",
        "linkedType": None, "linkedId": None, "uploadedBy": USER, "uploadedAt": NOW}

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
TRANSFER2 = {"id": "{{transfer2Id}}", "waybillRef": "WB-2", "sourceLocation": REF("location", "{{factoryId}}"), 
             "destinationLocation": REF("location", "{{factoryId2}}"), 
             "status": "DISPATCHED", "driver": REF("driver", "{{driverId}}"), 
             "driverVehicle": "KN-482-ABC", "transferDate": "2026-10-08", "dispatchedAt": NOW, "receivedDate": None, "receivedAt": None,
             "receiptNote": None, "totalSent": 5.000, "totalRejected": 0, "waybillUrl": "/api/v1/inventory/stock/transfers/{{transfer2Id}}/waybill.pdf",
             "lines": [{"id": "{{transfer2LineId}}", "stockItem": REF("stockItem", "{{finishedProductId}}"), 
                        "unit": "Kilogram", "qtySent": 5.000, "qtyReceived": None, "qtyRejected": None, "outstanding": 5.000, "fromLocation": None,
                        "receivedIntoLocation": None, "batchNo": "B-2026-10-01",
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

ep(I, "List Drivers", "GET", "/api/v1/inventory/drivers", query=[{"key": "q", "value": "musa"}], resp=PAGED([DRIVER]),
   desc="Perm inventory.drivers.read. q: name, vehicle number, phone, licence. status=ACTIVE|INACTIVE. GET /drivers/lookup is the picker (active only).")

ep(I, "Dispatch Transfer (with driver and batch)", "POST", "/api/v1/inventory/stock/transfers",
   body={"sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{factoryId2}}", "driverId": "{{driverId}}", "driverName": None,
         "driverVehicle": None, "transferDate": None,
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 5, "unitPrice": None, "fromLocationId": None, "batchNo": "B-2026-10-01"}]},
   resp={"data": TRANSFER2},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('transfer2Id', json.data.id);",
          "pm.collectionVariables.set('transfer2LineId', json.data.lines[0].id);",
          "pm.test('driver taken from the register', () => pm.expect(json.data.driver.name).to.eql('Musa Bello'));"],
   desc="With driverId the driver's name and vehicle are filled in. Per line, fromLocationId picks the area the goods leave from (default the "
        "source) and availability is checked there. Rules: source must be ACTIVE (409 LOCATION_NOT_TRADING when pending opening), destination "
        "not SUSPENDED (409 LOCATION_SUSPENDED), and not the same location (400 SAME_LOCATION — use an area move). The waybill is registered "
        "in Transaction Receipts.")

ep(I, "List Incoming Consignments", "GET", "/api/v1/inventory/stock/transfers",
   query=[{"key": "locationId", "value": "{{factoryId2}}"}, {"key": "direction", "value": "INTO"}, {"key": "status", "value": "DISPATCHED,PARTIALLY_RECEIVED"}],
   resp=PAGED([TRANSFER2]),
   tests=["const json = pm.response.json();",
          "pm.test('the new transfer is awaiting receipt', () => pm.expect(json.data.items.map(t => t.id)).to.include(pm.collectionVariables.get('transfer2Id')));"],
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
   resp={"data": {"id": "5c1d2e3f-4444-4c4c-9a9a-000000000004", "ref": "QA-3", "location": REF("location", "{{factoryId2}}"), 
                  "rootLocationName": "Apex Plant B", "stockItem": REF("stockItem", "{{finishedProductId}}"), 
                  "unit": "Kilogram", "mode": "COUNTED", "direction": "NEGATIVE", "quantity": 2.000, "recordedQuantity": 4.000, "newQuantity": 2.000,
                  "reason": "STOCKTAKE_VARIANCE", "note": "Monthly count", "otpVerified": False, "performedBy": USER,
                  "at": NOW}},
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
   resp=PAGED([], summary={"count": 2, "qtyAdded": 0, "qtyRemoved": 62, "netEffect": -62}),
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
   resp=PAGED([{"stockItem": REF("stockItem", "{{finishedProductId}}"), "category": None,
               "unit": "Kilogram", "status": "ACTIVE", "priceId": "9e0f1a2b-5555-4c4c-9a9a-000000000005", "retailPrice": 5000.00,
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
   resp=PAGED([{"priceId": "{{scheduledPriceId}}", "retailPrice": 5500.00, "dealerPrice": 5000.00, "effectiveFrom": "2099-01-01", "batchRef": "PL-2",
                   "note": "Long-term test price", "setBy": USER, "createdAt": NOW, "state": "SCHEDULED"}]),
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('scheduledPriceId', json.data.items[0].id || json.data.items[0].priceId);",
          "pm.test('newest first: scheduled, then current', () => pm.expect(json.data.items.map(p => p.state)).to.eql(['SCHEDULED', 'CURRENT']));"])

ep(I, "Change Scheduled Price", "PUT", "/api/v1/inventory/price-list/entries/{{scheduledPriceId}}",
   body={"retailPrice": 5600, "dealerPrice": 5100, "note": "Revised"},
   resp={"data": {"priceId": "{{scheduledPriceId}}", "retailPrice": 5600.00, "dealerPrice": 5100.00, "effectiveFrom": "2099-01-01", "batchRef": "PL-2",
                  "note": "Revised", "setBy": USER, "createdAt": NOW, "state": "SCHEDULED"}},
   desc="Only a price not yet in force can change (409 PRICE_ALREADY_EFFECTIVE otherwise).")

ep(I, "Group Stock Card", "GET", "/api/v1/inventory/stock/group-card",
   query=[{"key": "groupBy", "value": "CATEGORY"}, {"key": "groupId", "value": "{{categoryId}}"}, {"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": {"header": {"groupType": "CATEGORY", "group": REF("category", "{{categoryId}}"), "members": [], "openingBalance": 0,
                             "memberCount": 0, "totalReceived": 0, "totalIssued": 0, "closingBalance": 0}, "rows": PAGED([])["data"]}},
   desc="Every movement of every product in the category, one running total. A showroom/factory includes its storage areas; from/to optional. "
        "Products with different units can't be added up (400 GROUP_MIXED_UNITS). groupBy=PARENT_PRODUCT needs product variants (not built yet).")

ep(I, "Group Bin Card", "GET", "/api/v1/inventory/stock/group-bin-card",
   query=[{"key": "groupBy", "value": "CATEGORY"}, {"key": "groupId", "value": "{{categoryId}}"}, {"key": "locationId", "value": "{{factoryId}}"}],
   resp={"data": {"header": {"groupType": "CATEGORY", "group": REF("category", "{{categoryId}}"), "members": [], "openingBalance": 0,
                             "memberCount": 0, "totalReceived": 0, "totalIssued": 0, "closingBalance": 0}, "rows": PAGED([])["data"]}},
   desc="Daily totals for the group.")

# ============================== SHOWROOM (docs/05) ==============================
S = "16 - Showroom"
AREAS = [{"id": "{{floorAreaId}}", "code": "SHR-1-SF", "name": "Shop Floor", "kind": "SHOP_FLOOR", "status": "ACTIVE", "unitsOnHand": 0},
         {"id": "{{warehouseAreaId}}", "code": "SHR-1-WH", "name": "Warehouse", "kind": "WAREHOUSE", "status": "ACTIVE", "unitsOnHand": 0}]
SHOWROOM = {"id": "{{showroomId}}", "code": "SHR-1", "name": "Showroom Ikeja", "stateCode": "NG-LA", "stateName": "Lagos",
            "streetAddress": "48 Allen Avenue", "city": "Ikeja", "phone": "0803 000 1111", "email": "ikeja@apex.ng", "managerName": "Ngozi Eze",
            "manager": None, "dateOpened": "2026-10-01", "storageCapacity": "1,200 cartons", "operatingHours": "Mon–Sat 8am–7pm",
            "status": "ACTIVE", "vatRate": 7.50, "vatEnabled": True, "vatRegistrationNumber": None, "areas": AREAS,
            "defaultReceivingLocation": REF("location", "{{warehouseAreaId}}"), "defaultSellingLocation": REF("location", "{{floorAreaId}}"), "createdAt": NOW, "warnings": []}
ENTRY_LINE = {"lineNo": 1, "stockItem": REF("stockItem", "{{directItemId}}"), "code": "ITM-9", "description": "Local Groundnuts 500g", "quantity": 20.000,
              "unit": "Kilogram", "unitCost": 800.00, "lineCost": 16000.00, "inputVatRate": 7.50, "inputVatAmount": 1200.00,
              "sellingPrice": 1200.00, "batchNo": None, "expiryDate": None}
RETURN = {"id": "{{returnId}}", "ref": "RT-1", "returnType": "NON_DEALER", "location": REF("location", "{{showroomId}}"), 
          "customer": {"id": "8f8f8f8f-6666-4c4c-9a9a-000000000006", "code": "CU-5", "name": "Ada Nwosu", "type": "NO_CREDIT_WALKIN", "temporary": True},
          "originalDocumentRef": "RCT-552", "returnDate": "2026-10-09", "note": None,
          "lines": [{"lineNo": 1, "stockItem": REF("stockItem", "{{finishedProductId}}"), "code": "ITM-2", "name": "Premium Blend", "quantity": 1.000, "unit": "Kilogram",
                     "unitPrice": 5000.00, "lineValue": 5000.00, "reason": "PRODUCT_DEFECT", "condition": "RESALEABLE",
                     "intoLocation": REF("location", "{{warehouseAreaId}}"), "note": None}],
          "totalValue": 10000.00, "writeOffValue": 5000.00, "walletBalanceBefore": 0.00, "walletCredit": 10000.00, "walletBalanceAfter": 10000.00,
          "status": "POSTED", "reversedReason": None, "reversedAt": None, "processedBy": USER, "createdAt": NOW}

# ---- A. Showroom register ----
ep(S, "A1 Register Showroom", "POST", "/api/v1/showroom/showrooms",
   body={"name": "Showroom Ikeja {{$randomInt}}", "stateCode": "NG-LA", "streetAddress": "48 Allen Avenue", "city": "Ikeja", "phone": "0803 000 1111",
         "email": "ikeja@apex.ng", "managerName": "Ngozi Eze", "managerUserId": None, "dateOpened": "2026-10-01", "storageCapacity": "1,200 cartons",
         "operatingHours": "Mon–Sat 8am–7pm", "active": True, "createDefaultAreas": True},
   resp={"data": SHOWROOM},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('showroomId', json.data.id);",
          "pm.collectionVariables.set('showroomName', json.data.name);",
          "pm.collectionVariables.set('floorAreaId', json.data.areas.find(a => a.kind === 'SHOP_FLOOR').id);",
          "pm.collectionVariables.set('warehouseAreaId', json.data.areas.find(a => a.kind === 'WAREHOUSE').id);",
          "pm.collectionVariables.set('today', new Date(Date.now() + 3600 * 1000).toISOString().slice(0, 10));",
          "pm.test('default areas created', () => pm.expect(json.data.areas.map(a => a.kind).sort()).to.eql(['SHOP_FLOOR', 'WAREHOUSE']));"],
   desc="Perm showroom.register.manage. Code SHR-n (system-generated, never edited). createDefaultAreas adds the areas in the module setting "
        "showroom.defaultAreas (Shop Floor + Warehouse by default); Warehouse becomes the default receiving area and Shop Floor the default selling area. "
        "active=false registers it as 'pending opening': it can receive stock but can't trade, dispatch or take returns.")

ep(S, "A2 Register Showroom (pending opening)", "POST", "/api/v1/showroom/showrooms",
   body={"name": "Showroom Kano {{$randomInt}}", "stateCode": "NG-KN", "streetAddress": "12 Murtala Mohammed Way", "city": "Kano", "phone": "0803 000 2222", "active": False},
   resp={"data": dict(SHOWROOM, id="{{pendingShowroomId}}", code="SHR-2", name="Showroom Kano", status="PENDING")},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('pendingShowroomId', json.data.id);",
          "pm.test('pending opening', () => pm.expect(json.data.status).to.eql('PENDING'));"])

ep(S, "A3 Register Showroom (error: duplicate name)", "POST", "/api/v1/showroom/showrooms",
   body={"name": "{{showroomName}}", "stateCode": "NG-LA", "streetAddress": "1 Road", "city": "Ikeja", "phone": "08030000000"},
   status=409, status_text="Conflict", resp={"error": {"code": "DUPLICATE_NAME", "message": "'Showroom Ikeja' is already in use"}},
   expect_error={"status": 409, "code": "DUPLICATE_NAME"})

ep(S, "A4 List Showrooms", "GET", "/api/v1/showroom/showrooms", query=[{"key": "q", "value": "ikeja"}],
   resp={"data": {"items": [{"id": "{{showroomId}}", "code": "SHR-1", "name": "Showroom Ikeja", "streetAddress": "48 Allen Avenue", "city": "Ikeja",
                             "stateCode": "NG-LA", "stateName": "Lagos", "managerName": "Ngozi Eze", "phone": "0803 000 1111", "dateOpened": "2026-10-01",
                             "vatRate": 7.50, "vatEnabled": True, "status": "ACTIVE", "areaCount": 2}],
                  "stats": {"active": 1, "pendingOpening": 1, "suspended": 0}}},
   desc="Perm showroom.stock.read. Unpaged. Query: q (code, name, city, manager), status (repeatable), stateCode (repeatable). stats counts every showroom in scope.")

ep(S, "A5 Get Showroom", "GET", "/api/v1/showroom/showrooms/{{showroomId}}", resp={"data": SHOWROOM})

ep(S, "A6 Update Showroom", "PUT", "/api/v1/showroom/showrooms/{{showroomId}}",
   body={"name": "{{showroomName}}", "stateCode": "NG-LA", "streetAddress": "48 Allen Avenue", "city": "Ikeja", "phone": "0803 000 1111",
         "email": "ikeja@apex.ng", "managerName": "Ngozi Eze", "operatingHours": "Mon–Sun 8am–8pm"},
   resp={"data": dict(SHOWROOM, operatingHours="Mon–Sun 8am–8pm")},
   desc="Same fields as register (active/createDefaultAreas are ignored). Status changes go through A9.")

ep(S, "A7 Add Storage Area", "POST", "/api/v1/showroom/showrooms/{{showroomId}}/areas",
   body={"name": "Cold Room", "kind": "OTHER"},
   resp={"data": dict(SHOWROOM, areas=AREAS + [{"id": "{{coldRoomId}}", "code": "SHR-1-A1", "name": "Cold Room", "kind": "OTHER", "status": "ACTIVE", "unitsOnHand": 0}])},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('coldRoomId', json.data.areas.find(a => a.name === 'Cold Room').id);"],
   desc="kind: SHOP_FLOOR | WAREHOUSE | RECEIVING | QUARANTINE | OTHER. Codes follow the showroom: SHR-1-SF, -WH, -RC, -QR, -A1…")

ep(S, "A8 Deactivate Storage Area", "PUT", "/api/v1/showroom/showrooms/areas/{{coldRoomId}}/status", body={"status": "SUSPENDED"},
   resp={"data": SHOWROOM},
   desc="An area must be empty (409 AREA_HAS_STOCK — move the stock first) and not a default receiving/selling area.")

ep(S, "A9 Open Pending Showroom", "PUT", "/api/v1/showroom/showrooms/{{pendingShowroomId}}/status", body={"status": "ACTIVE", "reason": "Opening day"},
   resp={"data": dict(SHOWROOM, id="{{pendingShowroomId}}", name="Showroom Kano")},
   desc="status ACTIVE | PENDING | SUSPENDED. Suspending a showroom that holds stock is allowed but the response carries a warning; nothing moves while suspended.")

ep(S, "A10 Showroom Lookup", "GET", "/api/v1/showroom/showrooms/lookup", query=[{"key": "q", "value": "ikeja"}],
   resp={"data": [{"id": "{{showroomId}}", "code": "SHR-1", "label": "Showroom Ikeja", "status": "ACTIVE", "areas": AREAS}]},
   desc="Picker with areas as children (active showrooms by default).")

# ---- B. Stock intake ----
ep(S, "B1 Direct Stock Entry", "POST", "/api/v1/showroom/direct-entries",
   body={"locationId": "{{showroomId}}", "intoLocationId": None, "supplierId": None, "supplierName": "Independent local vendor",
         "supplierInvoiceNo": "INV-7781", "paymentTerms": "Cash on delivery", "receivedDate": "{{today}}", "vatCategoryId": None,
         "lines": [{"description": "Local Groundnuts 500g", "quantity": 20, "unitOfMeasureId": "{{unitId}}", "unitCost": 800, "inputVatRate": 7.5,
                    "sellingPrice": 1200, "dealerPrice": 1100}]},
   resp={"data": {"id": "{{entryId}}", "ref": "DE-1", "kind": "DIRECT_ENTRY", "location": REF("location", "{{showroomId}}"), 
                  "intoLocation": REF("location", "{{warehouseAreaId}}"), "supplier": None,
                  "manufacturer": None, "partnerName": None, "partnerReference": "INV-7781",
                  "paymentTerms": "Cash on delivery", "checkedBy": None, "receivedDate": "2026-10-09", "lines": [ENTRY_LINE], "totalCost": 16000.00,
                  "totalInputVat": 1200.00, "enteredBy": USER, "createdAt": NOW, "costVisible": True, "warnings": []}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('entryId', json.data.id);",
          "pm.collectionVariables.set('directItemId', json.data.lines[0].stockItem.id);",
          "pm.test('goes to the warehouse by default', () => pm.expect(json.data.intoLocation.id).to.eql(pm.collectionVariables.get('warehouseAreaId')));"],
   desc="Perm showroom.intake.manage. Third-party goods not in the product register: each description becomes a DIRECT_ENTRY item held only at this "
        "showroom (the same description + unit restocks the same item), priced from sellingPrice/dealerPrice, costed at unitCost. Goods go to "
        "intoLocationId or the showroom's receiving area. Give supplierId (registered) or supplierName. inputVatRate is the VAT the supplier charged.")

ep(S, "B2 Direct Entries Report", "GET", "/api/v1/showroom/direct-entries", query=[{"key": "q", "value": "groundnuts"}],
   resp=PAGED([{"entryId": "{{entryId}}", "lineId": "1a2b3c4d-7777-4c4c-9a9a-000000000007", "date": "2026-10-09", "ref": "DE-1", "kind": "DIRECT_ENTRY",
               "location": REF("location", "{{showroomId}}"), "stockItem": REF("stockItem", "{{directItemId}}"), 
               "product": "Local Groundnuts 500g", "party": "Independent local vendor", "partnerReference": "INV-7781", "batchNo": None, "expiryDate": None,
               "quantity": 20.000, "unit": "Kilogram", "unitCost": 800.00, "lineCost": 16000.00, "inputVatAmount": 1200.00}],
             summary={"entries": 1, "lines": 1, "units": 20, "totalCost": 16000.00, "inputVat": 1200.00, "costVisible": True}),
   desc="One row per line. Filters: locationId, supplierId, stockItemId, receivedDate/from/to/period, expiryDate, hasBatch, inputVatRate, quantity, lineCost "
        "(cost), enteredBy. q: reference, invoice no., product, supplier, batch. Costs are 0 without factory.costing.read.")

ep(S, "B3 Get Stock Entry", "GET", "/api/v1/showroom/stock-entries/{{entryId}}",
   resp={"data": {"id": "{{entryId}}", "ref": "DE-1", "kind": "DIRECT_ENTRY", "lines": [ENTRY_LINE]}})

ep(S, "B4 Register Outsourced Product", "POST", "/api/v1/inventory/stock-items",
   body={"locationId": "{{factoryId}}", "itemType": "OUTSOURCED", "code": None, "name": "Partner Cola 35cl", "categoryId": None, "brand": "Partner",
         "unitOfMeasureId": "{{unitId}}", "reorderLevel": 0, "barcode": None, "landingCost": 2000, "markupCost": None,
         "vatCategoryId": "{{standardRateVatCategoryId}}", "hsnSectionId": "{{hsnSectionId}}", "isicSectionId": "{{isicSectionId}}"},
   resp={"data": {"id": "{{outsourcedItemId}}", "itemType": "OUTSOURCED", "name": "Partner Cola 35cl", "landingCost": 2000.00}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('outsourcedItemId', json.data.id);"],
   desc="Setup for B5: outsourced products are registered in the product register with their landing cost.")

ep(S, "B5 Outsource Stock Receipt", "POST", "/api/v1/showroom/outsource-receipts",
   body={"locationId": "{{showroomId}}", "intoLocationId": "{{floorAreaId}}", "manufacturerId": "{{manufacturerId}}", "partnerReference": "PW-5521",
         "checkedBy": "Musa", "receivedDate": "{{today}}",
         "lines": [{"stockItemId": "{{outsourcedItemId}}", "quantity": 12, "inputVatRate": 0, "batchNo": "PC-2610", "expiryDate": "2099-06-30"}]},
   resp={"data": {"ref": "OS-1", "kind": "OUTSOURCE_RECEIPT", "partnerName": "Partner Bottlers", "lines": [dict(ENTRY_LINE, unitCost=2000.00, lineCost=24000.00)]}},
   tests=["const json = pm.response.json();", "pm.test('costed at the registered landing cost', () => pm.expect(json.data.lines[0].unitCost).to.eql(2000));"],
   desc="Perm showroom.intake.manage. Only registered OUTSOURCED products (400 ITEM_NOT_OUTSOURCED otherwise; use Direct Entry for unregistered goods), "
        "ACTIVE, with a landing cost (400 OUTSOURCE_COST_NOT_SET). Unit cost is the registered landing cost, not input. Expiry must be after the received date.")

ep(S, "B6 Outsource Stock Receipt (error: not outsourced)", "POST", "/api/v1/showroom/outsource-receipts",
   body={"locationId": "{{showroomId}}", "manufacturerId": "{{manufacturerId}}", "receivedDate": "{{today}}", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1}]},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "ITEM_NOT_OUTSOURCED", "message": "Only registered outsourced products can be received here; for third-party goods not in the register use Stock Entry (Direct)"}},
   expect_error={"status": 400, "code": "ITEM_NOT_OUTSOURCED"})

ep(S, "B7 Outsource Receipts Report", "GET", "/api/v1/showroom/outsource-receipts", query=[{"key": "locationId", "value": "{{showroomId}}"}],
   resp=PAGED([]), desc="Filters: locationId, manufacturerId, stockItemId, receivedDate, expiryDate (expiryDate.lte = expiring soon), hasBatch, quantity.")

# ---- C. Stock from the factory ----
ep(S, "C1 Dispatch Factory → Showroom", "POST", "/api/v1/inventory/stock/transfers",
   body={"sourceLocationId": "{{factoryId}}", "destinationLocationId": "{{showroomId}}", "driverId": "{{driverId}}",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 6, "unitPrice": None}]},
   resp={"data": dict(TRANSFER2, id="{{showroomTransferId}}", destinationLocationId="{{showroomId}}", destinationLocationType="SHOWROOM")},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('showroomTransferId', json.data.id);",
          "pm.collectionVariables.set('showroomTransferLineId', json.data.lines[0].id);"])

ep(S, "C2 Receive at Showroom", "POST", "/api/v1/inventory/stock/transfers/{{showroomTransferId}}/receive",
   body={"lines": [{"stockTransferLineId": "{{showroomTransferLineId}}", "qtyReceived": 6, "qtyRejected": 0}]},
   resp={"data": dict(TRANSFER2, status="RECEIVED")},
   tests=["const json = pm.response.json();",
          "pm.test('received into the showroom warehouse by default', () => pm.expect(json.data.lines[0].receivedIntoLocation.id).to.eql(pm.collectionVariables.get('warehouseAreaId')));"],
   desc="With no receivedIntoLocationId, goods go to the showroom's default receiving area (Warehouse).")

# ---- D. Area moves ----
ep(S, "D1 Move Warehouse → Shop Floor", "POST", "/api/v1/showroom/area-moves",
   body={"fromLocationId": "{{warehouseAreaId}}", "toLocationId": "{{floorAreaId}}", "movedDate": None, "note": "Restock the floor",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 2}]},
   resp={"data": {"id": "2b3c4d5e-8888-4c4c-9a9a-000000000008", "ref": "AM-1", "rootLocation": REF("location", "{{showroomId}}"), 
                  "fromLocation": REF("location", "{{warehouseAreaId}}"), "toLocation": REF("location", "{{floorAreaId}}"), 
                  "movedDate": "2026-10-09", "note": "Restock the floor",
                  "lines": [{"lineNo": 1, "stockItem": REF("stockItem", "{{finishedProductId}}"), "code": "ITM-2", "name": "Premium Blend", "quantity": 2.000, "unit": "Kilogram"}],
                  "movedBy": USER, "createdAt": NOW}},
   desc="Perm showroom.stock.manage. Both areas must belong to the same showroom/factory (400 NOT_SAME_LOCATION). Leaving a QUARANTINE area needs a note.")

ep(S, "D2 Move (error: more than available)", "POST", "/api/v1/showroom/area-moves",
   body={"fromLocationId": "{{warehouseAreaId}}", "toLocationId": "{{floorAreaId}}", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 9999}]},
   status=400, status_text="Bad Request", resp={"error": {"code": "INSUFFICIENT_STOCK", "message": "Insufficient stock"}},
   expect_error={"status": 400, "code": "INSUFFICIENT_STOCK"})

ep(S, "D3 List Area Moves", "GET", "/api/v1/showroom/area-moves", query=[{"key": "locationId", "value": "{{showroomId}}"}], resp=PAGED([]))

# ---- E. Returns ----
ep(S, "E1 Walk-in Return (non-dealer)", "POST", "/api/v1/showroom/returns",
   body={"returnType": "NON_DEALER", "locationId": "{{showroomId}}", "customerId": None, "walkIn": {"name": "Ada Nwosu", "phone": "0809 111 {{$randomInt}}"},
         "originalDocumentRef": "RCT-552", "returnDate": None, "note": None,
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1, "reason": "PRODUCT_DEFECT", "condition": "RESALEABLE"},
                   {"stockItemId": "{{finishedProductId}}", "quantity": 1, "reason": "DAMAGED_IN_TRANSIT", "condition": "DAMAGED_WRITE_OFF"}]},
   resp={"data": RETURN},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('returnId', json.data.id);",
          "pm.test('temporary customer credited for both lines', () => { pm.expect(json.data.customer.temporary).to.eql(true); pm.expect(json.data.walletCredit).to.eql(10000); });"],
   desc="Perm showroom.returns.manage; the showroom must be ACTIVE. No customer record? Give walkIn {name, phone}: an existing non-dealer with that phone "
        "is used, otherwise a temporary customer is created (removed once its wallet is spent). Every line is credited to the wallet at today's price "
        "(retail for non-dealers, dealer price for dealers; 400 PRICE_NOT_SET if none). RESALEABLE goes back into stock (receiving area), QUARANTINE "
        "into the quarantine area (created on first use, never sold from), DAMAGED_WRITE_OFF is recorded as a loss.")

ep(S, "E2 Dealer Return", "POST", "/api/v1/showroom/returns",
   body={"returnType": "DEALER", "locationId": "{{showroomId}}", "customerId": "{{dealerId}}", "originalDocumentRef": "INV-9001",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1, "reason": "EXPIRED_OR_NEAR_EXPIRY", "condition": "QUARANTINE"}]},
   resp={"data": dict(RETURN, id="{{dealerReturnId}}", ref="RT-2", returnType="DEALER", totalValue=4500.00, writeOffValue=0.00, walletCredit=4500.00)},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('dealerReturnId', json.data.id);",
          "pm.test('credited at the dealer price', () => pm.expect(json.data.totalValue).to.eql(4500));"])

ep(S, "E3 Return (error: dealer as non-dealer)", "POST", "/api/v1/showroom/returns",
   body={"returnType": "NON_DEALER", "locationId": "{{showroomId}}", "customerId": "{{dealerId}}",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1, "reason": "PRODUCT_DEFECT", "condition": "RESALEABLE"}]},
   status=400, status_text="Bad Request", resp={"error": {"code": "USE_DEALER_RETURN", "message": "This customer is an Authorised Dealer; record a dealer return instead"}},
   expect_error={"status": 400, "code": "USE_DEALER_RETURN"})

ep(S, "E4 Get Return", "GET", "/api/v1/showroom/returns/{{returnId}}", resp={"data": RETURN})

ep(S, "E5 Reverse Dealer Return", "POST", "/api/v1/showroom/returns/{{dealerReturnId}}/reverse", body={"reason": "Recorded against the wrong dealer"},
   resp={"data": dict(RETURN, id="{{dealerReturnId}}", status="REVERSED", reversedReason="Recorded against the wrong dealer", reversedAt=NOW)},
   tests=["const json = pm.response.json();", "pm.test('reversed', () => pm.expect(json.data.status).to.eql('REVERSED'));"],
   desc="Perm showroom.returns.manage + customers.wallet.adjust. Only while the wallet still holds the credit (400 INSUFFICIENT_WALLET_BALANCE) and the "
        "goods are still where they went (400 INSUFFICIENT_STOCK). Takes the credit and the goods back.")

ep(S, "E6 Reverse Return (error: already reversed)", "POST", "/api/v1/showroom/returns/{{dealerReturnId}}/reverse", body={"reason": "Again"},
   status=409, status_text="Conflict", resp={"error": {"code": "RETURN_ALREADY_REVERSED", "message": "This return has already been reversed"}},
   expect_error={"status": 409, "code": "RETURN_ALREADY_REVERSED"})

ep(S, "E7 Return Reports", "GET", "/api/v1/showroom/returns", query=[{"key": "locationId", "value": "{{showroomId}}"}],
   resp=PAGED([], summary={"returns": 1, "units": 2, "valueCredited": 10000.00, "resaleableValue": 5000.00, "quarantineValue": 0.00, "writeOffValue": 5000.00,
                         "byReason": [{"reason": "PRODUCT_DEFECT", "lines": 1, "units": 1, "value": 5000.00}]}),
   tests=["const json = pm.response.json();", "pm.test('write-off counted as the net cost of returns', () => pm.expect(json.data.summary.writeOffValue).to.eql(5000));"],
   desc="Perm showroom.returns.read. One row per line; POSTED returns unless status is filtered. Filters: locationId, returnType, condition, reason, customerId, "
        "customerType, status, stockItemId, lineValue, creditedTo, returnDate/from/to/period. q: RT ref, original document, customer code/name/phone, product, note.")

# ---- F. Reports ----
ep(S, "F1 Comprehensive Stock (showroom)", "GET", "/api/v1/showroom/reports/comprehensive-stock", query=[{"key": "locationId", "value": "{{showroomId}}"}],
   resp=PAGED([{"ln": 1, "stockItem": REF("stockItem", "{{finishedProductId}}"), "group": "Ungrouped", "source": "Finished Product",
               "location": REF("location", "{{warehouseAreaId}}"), "area": "Warehouse", "qty": 5.000, "unit": "Kilogram", "unitCost": 0.00, "totalCost": 0.00,
               "sellingPrice": 5000.00, "retailValue": 25000.00}],
             summary={"costVisible": True, "asAt": "2026-10-09", "lineItems": 4, "totalQuantity": 39, "totalStockValue": 64000.00, "retailValue": 115400.00, "storageAreas": 3}),
   tests=["const json = pm.response.json();", "pm.test('rows per area', () => pm.expect(json.data.items.map(r => r.area)).to.include('Warehouse'));"],
   desc="One row per item × area. Query: locationId (or areaId), source (FINISHED_PRODUCT|OUTSOURCED|DIRECT_ENTRY, repeatable), categoryId, asAt (past dates "
        "read the ledger), includeZero, q, sort (group, detail, qty, retailValue, totalCost). Cost columns are 0 and summary.costVisible false without "
        "factory.costing.read.")

ep(S, "F2 General Transfer Report", "GET", "/api/v1/showroom/reports/transfers", query=[{"key": "route", "value": "FACTORY_TO_SHOWROOM"}],
   resp=PAGED([], summary={"transfers": 1, "totalQuantity": 6, "inTransit": 0, "locationsInvolved": 2}),
   tests=["const json = pm.response.json();", "pm.test('the factory → showroom transfer is listed', () => pm.expect(json.data.items.map(r => r.transfer.id)).to.include(pm.collectionVariables.get('showroomTransferId')));"],
   desc="One row per transfer line. Filters: route (SHOWROOM_TO_SHOWROOM, FACTORY_TO_SHOWROOM, SHOWROOM_TO_FACTORY, FACTORY_TO_FACTORY), sourceLocationId, "
        "destinationLocationId, driverId, status (IN_TRANSIT|RECEIVED), hasRejections, stockItemId, transferDate/from/to/period. q: waybill, product, driver, vehicle.")

ep(S, "F3 Deactivate Area (error: holds stock)", "PUT", "/api/v1/showroom/showrooms/areas/{{warehouseAreaId}}/status", body={"status": "SUSPENDED"},
   status=409, status_text="Conflict", resp={"error": {"code": "AREA_HAS_STOCK", "message": "This area still holds 23 units; move them first"}},
   expect_error={"status": 409, "code": "AREA_HAS_STOCK"})

print(f"TOTAL ENDPOINTS: {len(ENDPOINTS)}")

# =====================================================================================
# ---------------------------- Collection / Environment builders ---------------------
# =====================================================================================

# ============================== FINANCE & ACCOUNTING ==============================
A = "17 - Finance & Accounting"
CATEGORY = {"id": "{{expenseCategoryId}}", "code": "EX-11", "name": "Generator Diesel", "description": "Diesel for the standby generators",
            "accountHead": "LOGISTICS", "monthlyCap": 400000.00, "pettyCashAllowed": True, "status": "ACTIVE", "spentThisMonth": 0.00,
            "overCap": False, "createdAt": NOW}
BANK = {"id": "{{bankAccountId}}", "code": "BA-1", "bankName": "Guaranty Trust Bank", "branch": "Ikeja GRA", "accountName": "Apex Industries Ltd",
        "accountNumber": "0123456789", "accountNumberMasked": "•••• 6789", "accountType": "COLLECTIONS", "currencyCode": "NGN",
        "sortCodeOrBic": "058152052", "authorisedSignatory": "Alice Admin", "relationshipManager": "Tunde Bakare", "dateOpened": "2024-02-01",
        "openingBalance": 2000000.00, "openingBalanceDate": "2026-10-01", "currentBalance": 2000000.00, "location": None, 
        "status": "ACTIVE", "createdAt": NOW}
PETTY = {"id": "{{pettyCashAccountId}}", "code": "PC-IKJ", "name": "Ikeja Petty Cash", "kind": "PETTY_CASH", "location": REF("location", "{{showroomId}}"),
         "currencyCode": "NGN", "openingBalance": 150000.00, "currentBalance": 150000.00, "floatLimit": 300000.00,
         "belowFloat": False, "posTerminalRef": None, "posSettlementBankAccount": None, "status": "ACTIVE", "createdAt": NOW}

ep(A, "A1 List Expense Categories", "GET", "/api/v1/account/expense-categories",
   resp={"data": [dict(CATEGORY, id="{{bankChargesCategoryId}}", code="EX-10", name="Bank Charges", description=None, accountHead="FINANCE",
                       monthlyCap=None, pettyCashAllowed=False)]},
   tests=["const json = pm.response.json();",
          "pm.test('every company starts with the PRD list', () => pm.expect(json.data.length).to.be.at.least(10));",
          "pm.collectionVariables.set('bankChargesCategoryId', json.data.find(c => c.name === 'Bank Charges').id);"],
   desc="Perm accounting.read. Unpaged. Every company starts with the PRD's ten categories (Bank Charges can't be paid from petty cash). "
        "spentThisMonth = bank debits and cash paid out under the category this month, less reversals. "
        "Filters: q, accountHead, status, pettyCashAllowed, overCap; sort=name (default) or spentThisMonth.")

ep(A, "A2 Create Expense Category", "POST", "/api/v1/account/expense-categories",
   body={"name": "Generator Diesel", "description": "Diesel for the standby generators", "accountHead": "LOGISTICS", "monthlyCap": 400000, "pettyCashAllowed": True},
   resp={"data": CATEGORY},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('expenseCategoryId', json.data.id);"],
   desc="Perm accounting.expense-categories.manage. code is optional (EX-n otherwise) and never changes. accountHead: UTILITIES, LOGISTICS, MAINTENANCE, ADMINISTRATIVE or FINANCE.")

ep(A, "A3 Create Expense Category (error: duplicate name)", "POST", "/api/v1/account/expense-categories",
   body={"name": "generator diesel", "accountHead": "LOGISTICS"}, status=409, status_text="Conflict",
   resp={"error": {"code": "DUPLICATE_NAME", "message": "'generator diesel' is already in use", "details": {"name": "generator diesel"}}},
   expect_error={"status": 409, "code": "DUPLICATE_NAME"})

ep(A, "A4 Update Expense Category", "PUT", "/api/v1/account/expense-categories/{{expenseCategoryId}}",
   body={"name": "Generator Diesel", "description": "Diesel for the standby generators", "accountHead": "LOGISTICS", "monthlyCap": 500000, "pettyCashAllowed": True},
   resp={"data": dict(CATEGORY, monthlyCap=500000.00)},
   desc="PUT /{id}/status with ACTIVE or INACTIVE deactivates one: it stays in historic reports but can't be used on new entries.")

ep(A, "A5 Expense Category Lookup", "GET", "/api/v1/account/expense-categories/lookup", query=[{"key": "q", "value": "diesel"}],
   resp={"data": [{"id": "{{expenseCategoryId}}", "code": "EX-11", "label": "Generator Diesel", "sublabel": "₦0.00 of ₦500,000.00 this month", "status": "ACTIVE"}]},
   desc="Picker. pettyCashAllowed=true limits it to categories payable from petty cash; active=false includes inactive ones.")

ep(A, "B1 Register Bank Account", "POST", "/api/v1/account/bank-accounts",
   body={"bankName": "Guaranty Trust Bank", "branch": "Ikeja GRA", "accountName": "Apex Industries Ltd", "accountNumber": "0123456789",
         "accountType": "COLLECTIONS", "sortCodeOrBic": "058152052", "authorisedSignatory": "Alice Admin", "relationshipManager": "Tunde Bakare",
         "dateOpened": "2024-02-01", "openingBalance": 2000000, "openingBalanceDate": "{{today}}"},
   resp={"data": BANK},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('bankAccountId', json.data.id);",
          "pm.test('opening balance posted', () => pm.expect(json.data.currentBalance).to.eql(2000000));"],
   desc="Perm accounting.bank.manage. accountNumber 6-30 digits, unique per bank. accountType CURRENT, COLLECTIONS, PAYROLL, SAVINGS or DOMICILIARY "
        "(only DOMICILIARY takes currencyCode, which must be a tracked currency). A non-zero openingBalance becomes the account's first ledger entry "
        "(OPENING), dated openingBalanceDate. locationId = a showroom/factory for a branch account; leave it out for head office.")

ep(A, "B2 Register Bank Account (error: duplicate number)", "POST", "/api/v1/account/bank-accounts",
   body={"bankName": "guaranty trust bank", "accountName": "Apex Industries Ltd", "accountNumber": "0123456789", "accountType": "CURRENT"},
   status=409, status_text="Conflict",
   resp={"error": {"code": "DUPLICATE_ACCOUNT_NUMBER", "message": "Account 0123456789 at guaranty trust bank is already registered", "details": {"bankName": "guaranty trust bank"}}},
   expect_error={"status": 409, "code": "DUPLICATE_ACCOUNT_NUMBER"})

ep(A, "B3 List Bank Accounts", "GET", "/api/v1/account/bank-accounts", resp={"data": [BANK]},
   desc="Perm accounting.read. Unpaged. Account numbers are shown in full only to holders of accounting.bank.manage (others get •••• 6789, and can search "
        "only on those last four digits). Filters: q, accountType, status, locationId, currencyCode; sort=bankName (default), currentBalance or dateOpened[,desc]. "
        "A user tied to one location sees only that location's accounts.")

ep(A, "B4 Get Bank Account", "GET", "/api/v1/account/bank-accounts/{{bankAccountId}}", resp={"data": BANK})

ep(A, "B5 Update Bank Account", "PUT", "/api/v1/account/bank-accounts/{{bankAccountId}}",
   body={"bankName": "Guaranty Trust Bank", "branch": "Allen Avenue", "accountName": "Apex Industries Ltd", "accountNumber": "0123456789",
         "sortCodeOrBic": "058152052", "authorisedSignatory": "Alice Admin", "relationshipManager": "Ngozi Eze", "dateOpened": "2024-02-01"},
   resp={"data": dict(BANK, branch="Allen Avenue", relationshipManager="Ngozi Eze")},
   desc="Type, currency and opening balance can't change. PUT /{id}/status with DORMANT keeps the account in historic statements but stops new entries (409 ACCOUNT_NOT_ACTIVE).")

ep(A, "B6 Post Manual Bank Entry", "POST", "/api/v1/account/bank-accounts/{{bankAccountId}}/manual-entries",
   body={"entryType": "DEBIT", "amount": 1500, "entryDate": "{{today}}", "description": "SMS alert and maintenance fee", "expenseCategoryId": "{{bankChargesCategoryId}}"},
   resp={"data": {"id": "{{manualEntryId}}", "reference": "MAN-1", "entryType": "DEBIT", "amount": 1500.00, "balanceAfter": 1998500.00,
                  "entryDate": "2026-10-12", "description": "SMS alert and maintenance fee"}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('manualEntryRef', json.data.reference);",
          "pm.test('balance reduced', () => pm.expect(json.data.balanceAfter).to.eql(1998500));"],
   desc="Bank charges, interest and reconciling items that appear on the real bank statement. A DEBIT needs an active expense category. "
        "entryDate can be back-dated (not before the opening balance date, not in the future); the statement still reads in date order.")

ep(A, "B7 Post Manual Bank Entry (error: debit without category)", "POST", "/api/v1/account/bank-accounts/{{bankAccountId}}/manual-entries",
   body={"entryType": "DEBIT", "amount": 500, "entryDate": "{{today}}", "description": "Unclassified charge"}, status=400, status_text="Bad Request",
   resp={"error": {"code": "VALIDATION_FAILED", "message": "Request validation failed", "details": {"expenseCategory": REF("expenseCategory", "is required for a debit")}}},
   expect_error={"status": 400, "code": "VALIDATION_FAILED"})

ep(A, "B8 Bank Statement", "GET", "/api/v1/account/bank-accounts/{{bankAccountId}}/statement", query=[{"key": "period", "value": "THIS_MONTH"}],
   resp={"data": {"items": [
       {"id": "{{openingEntryId}}", "date": "2026-10-12", "reference": "BA-1", "description": "Opening balance", "type": "Credit", "credit": 2000000.00, "debit": None,
        "balance": 2000000.00, "sourceType": "OPENING", "expenseCategoryName": None, "createdAt": NOW},
       {"id": "{{manualEntryId}}", "date": "2026-10-12", "reference": "MAN-1", "description": "SMS alert and maintenance fee", "type": "Debit", "credit": None, "debit": 1500.00,
        "balance": 1998500.00, "sourceType": "MANUAL", "expenseCategoryName": "Bank Charges", "createdAt": NOW}],
       "page": 0, "size": 50, "totalItems": 2, "totalPages": 1, "totalIsEstimate": False, "facets": None,
       "summary": {"account": {"id": "{{bankAccountId}}", "code": "BA-1", "bankName": "Guaranty Trust Bank", "accountName": "Apex Industries Ltd",
                               "accountNumberMasked": "•••• 6789", "accountType": "COLLECTIONS", "currencyCode": "NGN", "branch": "Allen Avenue",
                               "signatory": "Alice Admin", "status": "ACTIVE"},
                   "period": {"from": "2026-10-01", "to": "2026-10-31"}, "openingBalance": 0.00, "closingBalance": 1998500.00,
                   "totalCredits": 2000000.00, "totalDebits": 1500.00},
       "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   tests=["const json = pm.response.json();",
          "pm.test('closing balance matches the last running balance', () => pm.expect(json.data.summary.closingBalance).to.eql(json.data.items[json.data.items.length - 1].balance));"],
   desc="Perm accounting.read. Entries in date order with the balance after each. from/to or period (TODAY, THIS_WEEK, THIS_MONTH, LAST_MONTH, …); "
        "none = this month to date. q matches reference, description words and amounts. Filters: entryType, sourceType, amount, expenseCategoryId. "
        "summary: account header, period, openingBalance (before from), closingBalance (up to to), totalCredits, totalDebits. "
        "POST …/statement/search takes the advanced filter body.")

ep(A, "B9 Bank Statement Filters", "GET", "/api/v1/account/bank-accounts/statement/filters",
   resp={"data": {"fields": [{"key": "entryType", "label": "Type", "type": "ENUM", "operators": ["eq", "ne", "in", "not_in", "is_null", "is_not_null"],
                              "options": [{"value": "CREDIT", "label": "Credit"}, {"value": "DEBIT", "label": "Debit"}], "lookup": None,
                              "facetable": True, "sortable": False, "qualifierAliases": []}],
                  "collections": [], "primaryDate": "entryDate", "defaultSort": [], "periodPresets": ["TODAY", "YESTERDAY", "THIS_WEEK"]}})

ep(A, "B10 Bank Account Lookup", "GET", "/api/v1/account/bank-accounts/lookup",
   resp={"data": [{"id": "{{bankAccountId}}", "code": "BA-1", "label": "Guaranty Trust Bank · Apex Industries Ltd", "sublabel": "•••• 6789 · NGN", "status": "ACTIVE"}]},
   desc="Picker; active accounts unless active=false.")

ep(A, "C1 Register Petty Cash Account", "POST", "/api/v1/account/cash-accounts",
   body={"code": "PC-IKJ", "name": "Ikeja Petty Cash", "kind": "PETTY_CASH", "locationId": "{{showroomId}}", "openingBalance": 150000, "floatLimit": 300000},
   resp={"data": PETTY},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('pettyCashAccountId', json.data.id);"],
   desc="Perm accounting.cash-accounts.manage. kind TILL (a cashier's drawer), PETTY_CASH or VAULT. locationId = a showroom or factory (not a storage area); "
        "leave it out for head office. floatLimit is for petty cash only; posTerminalRef and posSettlementBankAccountId for tills only. "
        "Code, kind and location never change. Uses the showroom from folder 16.")

ep(A, "C2 Register Till", "POST", "/api/v1/account/cash-accounts",
   body={"code": "TILL-IKJ-01", "name": "Ikeja Till 1", "kind": "TILL", "locationId": "{{showroomId}}", "posTerminalRef": "T-55",
         "posSettlementBankAccountId": "{{bankAccountId}}"},
   resp={"data": dict(PETTY, id="{{tillAccountId}}", code="TILL-IKJ-01", name="Ikeja Till 1", kind="TILL", openingBalance=0.00, currentBalance=0.00,
                      floatLimit=None, posTerminalRef="T-55", posSettlementBankAccountId="{{bankAccountId}}", posSettlementBankCode="BA-1")},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('tillAccountId', json.data.id);"],
   desc="Card payments taken on terminal T-55 settle into the bank account given.")

ep(A, "C3 Register Cash Account (error: float limit on a till)", "POST", "/api/v1/account/cash-accounts",
   body={"code": "TILL-IKJ-02", "name": "Ikeja Till 2", "kind": "TILL", "locationId": "{{showroomId}}", "floatLimit": 50000},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "VALIDATION_FAILED", "message": "Request validation failed", "details": {"floatLimit": "only petty-cash accounts have a float limit"}}},
   expect_error={"status": 400, "code": "VALIDATION_FAILED"})

ep(A, "C4 List Cash Accounts", "GET", "/api/v1/account/cash-accounts", query=[{"key": "locationId", "value": "{{showroomId}}"}],
   resp={"data": [PETTY]},
   desc="Perm accounting.read. Unpaged. Filters: q (code, name, terminal), kind, locationId, status, belowFloat (petty cash under half its float limit). "
        "Head-office accounts are visible only to users not tied to a location.")

ep(A, "C5 Update Cash Account", "PUT", "/api/v1/account/cash-accounts/{{pettyCashAccountId}}",
   body={"name": "Ikeja Petty Cash Box", "floatLimit": 250000},
   resp={"data": dict(PETTY, name="Ikeja Petty Cash Box", floatLimit=250000.00)})

ep(A, "C6 Record Cash Count", "POST", "/api/v1/account/cash-accounts/{{pettyCashAccountId}}/cash-counts",
   body={"countedAmount": 149000, "countDate": "{{today}}", "note": "One ₦1,000 receipt missing"},
   resp={"data": {"account": dict(PETTY, name="Ikeja Petty Cash Box", floatLimit=250000.00, currentBalance=149000.00),
                  "bookBalance": 150000.00, "countedAmount": 149000.00, "variance": -1000.00, "adjustmentReference": "CC-1"}},
   tests=["const json = pm.response.json();", "pm.test('shortage posted', () => pm.expect(json.data.variance).to.eql(-1000));"],
   desc="Perm accounting.cash-accounts.manage. A difference from the book balance is posted as a CASH_COUNT_ADJUSTMENT (in for a surplus, out for a shortage) "
        "and notifies Management; note is required when there is a difference.")

ep(A, "C7 Deactivate Cash Account (error: still holds cash)", "PUT", "/api/v1/account/cash-accounts/{{pettyCashAccountId}}/status",
   body={"status": "INACTIVE"}, status=409, status_text="Conflict",
   resp={"error": {"code": "ACCOUNT_HAS_BALANCE", "message": "Account PC-IKJ still holds 149000.00; empty it before deactivating",
                   "details": {"account": "PC-IKJ", "balance": "149000.00"}}},
   expect_error={"status": 409, "code": "ACCOUNT_HAS_BALANCE"})

ep(A, "C8 Cash Inflow/Outflow Statement", "GET", "/api/v1/account/cash-flow", query=[{"key": "locationId", "value": "{{showroomId}}"}],
   resp={"data": {"items": [
       {"sn": 1, "id": "{{cashEntryId}}", "date": "2026-10-12", "reference": "PC-IKJ", "remark": "Opening balance", "inflow": 150000.00, "outflow": None,
        "balance": 150000.00, "accountCode": "PC-IKJ", "accountName": "Ikeja Petty Cash Box", "kind": "PETTY_CASH", "sourceType": "OPENING", "expenseCategoryName": None},
       {"sn": 2, "id": "{{cashEntryId}}", "date": "2026-10-12", "reference": "CC-1", "remark": "Cash count shortage: One ₦1,000 receipt missing", "inflow": None,
        "outflow": 1000.00, "balance": 149000.00, "accountCode": "PC-IKJ", "accountName": "Ikeja Petty Cash Box", "kind": "PETTY_CASH",
        "sourceType": "CASH_COUNT_ADJUSTMENT", "expenseCategoryName": None}],
       "page": 0, "size": 50, "totalItems": 2, "totalPages": 1, "totalIsEstimate": False, "facets": None,
       "summary": {"scope": {"location": REF("location", "{{showroomId}}"), 
                             "accounts": [{"id": "{{pettyCashAccountId}}", "code": "PC-IKJ", "name": "Ikeja Petty Cash Box", "kind": "PETTY_CASH"},
                                          {"id": "{{tillAccountId}}", "code": "TILL-IKJ-01", "name": "Ikeja Till 1", "kind": "TILL"}]},
                   "period": {"from": "2026-10-01", "to": "2026-10-12"}, "openingCash": 0.00, "totalInflow": 150000.00, "totalOutflow": 1000.00, "closingCash": 149000.00},
       "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   tests=["const json = pm.response.json();",
          "pm.test('closing cash = opening + in - out', () => { const s = json.data.summary; pm.expect(Math.round((s.openingCash + s.totalInflow - s.totalOutflow) * 100)).to.eql(Math.round(s.closingCash * 100)); });"],
   desc="Perm accounting.read. Every cash movement across the accounts in scope, in date order, with a running balance over all of them "
        "(the PRD's Date / Remark / In-Flow / Out-Flow / Balance, plus reference and account). Scope: locationId (a user tied to one location always "
        "gets their own; none = company-wide), cashAccountId, kind. from/to or period; none = this month to date. Filters: direction, sourceType, "
        "expenseCategoryId, amount; q matches reference, remark words and amounts. POST /cash-flow/search takes the advanced body; GET /cash-flow/filters lists the filters.")

ep(A, "C9 Cash Flow Filters", "GET", "/api/v1/account/cash-flow/filters",
   resp={"data": {"fields": [{"key": "direction", "label": "Direction", "type": "ENUM", "operators": ["eq", "ne", "in", "not_in", "is_null", "is_not_null"],
                              "options": [{"value": "IN", "label": "In-flow"}, {"value": "OUT", "label": "Out-flow"}], "lookup": None,
                              "facetable": True, "sortable": False, "qualifierAliases": []}],
                  "collections": [], "primaryDate": "entryDate", "defaultSort": [], "periodPresets": ["TODAY", "YESTERDAY", "THIS_WEEK"]}})

ep(A, "D1 Current Exchange Rates", "GET", "/api/v1/account/exchange-rates",
   resp={"data": [{"code": "USD", "currency": "US Dollar", "symbol": "$", "buy": None, "sell": None, "spread": None, "previousBuy": None,
                   "move": None, "movePct": None, "updatedAt": None, "updatedBy": None, "freshness": None}]},
   desc="One row per tracked currency (module setting accounting.fx.currencies: USD, GBP, EUR, CNY, ZAR, GHS by default). Rates are naira per unit. "
        "freshness: Current (published today) or Stale. Readable with accounting.read, invoicing.invoice.read or cashier.sales.read.")

ep(A, "D2 Publish Exchange Rates", "POST", "/api/v1/account/exchange-rates/publish",
   body={"rates": [{"currencyCode": "USD", "buyRate": 1505.50, "sellRate": 1525.00}, {"currencyCode": "GBP", "buyRate": 1910.00, "sellRate": 1945.00}]},
   resp={"data": {"id": "{{fxPublicationId}}", "ref": "FX-1", "publishedAt": NOW, "publishedBy": USER, "rates": [
       {"id": "{{fxRateId}}", "currencyCode": "USD", "buyRate": 1505.500000, "sellRate": 1525.000000},
       {"id": "{{fxRateId}}", "currencyCode": "GBP", "buyRate": 1910.000000, "sellRate": 1945.000000}]}},
   tests=["const json = pm.response.json();", "pm.test('one publication with both rates', () => pm.expect(json.data.rates.length).to.eql(2));"],
   desc="Perm accounting.fx.manage. Any subset of the tracked currencies; sellRate must not be below buyRate (400 INVALID_RATE). Rates are append-only: "
        "transactions store the rate they used, so publishing never changes history.")

ep(A, "D3 Publish Exchange Rates (error: untracked currency)", "POST", "/api/v1/account/exchange-rates/publish",
   body={"rates": [{"currencyCode": "JPY", "buyRate": 10.2, "sellRate": 10.6}]}, status=400, status_text="Bad Request",
   resp={"error": {"code": "CURRENCY_NOT_TRACKED", "message": "JPY is not a tracked currency", "details": {"currency": "JPY", "tracked": "USD,GBP,EUR,CNY,ZAR,GHS"}}},
   expect_error={"status": 400, "code": "CURRENCY_NOT_TRACKED"},
   desc="Track more currencies with the module setting accounting.fx.currencies (Settings > module settings).")

ep(A, "D4 Exchange Rate History", "GET", "/api/v1/account/exchange-rates/history", query=[{"key": "currencyCode", "value": "USD"}],
   resp={"data": {"items": [{"id": "{{fxRateId}}", "publicationRef": "FX-1", "currencyCode": "USD", "buyRate": 1505.500000, "sellRate": 1525.000000,
                             "publishedAt": NOW, "publishedBy": USER}],
                  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "totalIsEstimate": False, "facets": None, "summary": None,
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="Paged, newest first. Filters: currencyCode, publishedBy, buyRate, from/to (publishedAt); q matches the FX-n reference.")

FUNDING = {"id": "{{pettyFundingId}}", "ref": "PCF-1", "date": "2026-10-12", "pettyCashAccount": REF("cashAccount", "{{pettyCashAccountId}}"), 
           "sourceKind": "BANK", "sourceName": "BA-1 Guaranty Trust Bank", "amount": 50000.00, "transferOrChequeReference": "TRF-20931",
           "authorisedBy": "Alice Admin", "description": "Weekly top-up", "fundedBy": USER, "status": "POSTED", "reversalReason": None, "createdAt": NOW}
PAYMENT = {"id": "{{pettyPaymentId}}", "ref": "PCP-1", "date": "2026-10-12", "pettyCashAccount": REF("cashAccount", "{{pettyCashAccountId}}"), 
           "expenseCategory": REF("expenseCategory", "{{expenseCategoryId}}"), "amount": 12000.00, "paidTo": "Total filling station",
           "approvedBy": "Mrs Okafor", "description": "20 litres", "status": "POSTED", "reversalReason": None, "balanceAfter": 187000.00, "createdAt": NOW}
ALLOCATION = {"id": "{{allocationId}}", "ref": "PPC-1", "date": NOW, "sourceName": "BA-1 Guaranty Trust Bank", "destinationLocation": REF("location", "{{showroomId}}"),
              "destinationAccountCode": "PC-IKJ", "purpose": "Branch float for October", "sentBy": USER,
              "amount": 20000.00, "status": "PENDING", "respondedBy": None, "respondedOn": None, "note": None}
COST = {"id": "{{runningCostId}}", "ref": None, "date": "2026-10-12", "description": "Generator servicing", "expenseCategory": REF("expenseCategory", "{{expenseCategoryId}}"),
        "payee": "CoolAir Ltd", "method": "BANK_TRANSFER", "payingAccountId": "{{bankAccountId}}", "payingAccount": "BA-1",
        "amount": 45000.00, "inputVatAmount": 3139.53, "supplierTin": "12345678-0001", "invoiceOrReceiptNo": "INV-889", "chequeNumber": None,
        "location": None, "status": "DRAFT", "exceedsMonthlyCap": False, "submittedBy": None, "submittedAt": None,
        "approvedBy": None, "approvedAt": None, "rejectionReason": None, "reversalReason": None, "createdAt": NOW}
COST_BODY = {"expenseCategoryId": "{{expenseCategoryId}}", "payee": "CoolAir Ltd", "amount": 45000, "paymentMethod": "BANK_TRANSFER",
             "payingBankAccountId": "{{bankAccountId}}", "invoiceOrReceiptNo": "INV-889", "inputVatAmount": 3139.53, "supplierTin": "12345678-0001",
             "paymentDate": "{{today}}", "description": "Generator servicing", "submit": False}
DISBURSEMENT = {"id": "{{disbursementId}}", "ref": "BF-1", "date": NOW, "destinationLocation": REF("location", "{{showroomId}}"), 
                "purpose": "Restocking float", "channel": "BANK_TRANSFER", "sourceAccount": "BA-1", "destinationAccount": "BA-2", "sentBy": USER,
                "sent": 300000.00, "received": None, "shortfall": None, "shortfallReason": None, "status": "AWAITING", "confirmedBy": None,
                "dateReceived": None, "rejectionReason": None}
LODGEMENT = {"id": "{{lodgementId}}", "ref": "LDG-1", "date": "2026-10-12", "location": REF("location", "{{showroomId}}"), 
             "cashAccountCode": "TILL-IKJ-01", "takings": 32500.00, "banked": 30000.00, "retained": 2500.00, "bank": "BA-1 Guaranty Trust Bank",
             "accountNumberMasked": "•••• 6789", "depositedBy": USER, "teller": "TLR-8812", "status": "AWAITING_CONFIRMATION",
             "confirmedBy": None, "confirmedAt": None, "rejectionReason": None}

ep(A, "F1 Fund Petty Cash", "POST", "/api/v1/account/petty-cash/fundings",
   body={"pettyCashAccountId": "{{pettyCashAccountId}}", "sourceKind": "BANK", "sourceBankAccountId": "{{bankAccountId}}", "amount": 50000,
         "fundingDate": "{{today}}", "transferOrChequeReference": "TRF-20931", "authorisedBy": "Alice Admin", "description": "Weekly top-up"},
   resp={"data": FUNDING},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('pettyFundingId', json.data.id);"],
   desc="Perm accounting.petty-cash.fund. Credit/Fund Petty Cash: the source (sourceKind BANK with sourceBankAccountId, or CASH with sourceCashAccountId) is "
        "debited and the petty cash box credited, under one PCF-n. The box's float limit can't be passed (409 FLOAT_LIMIT_EXCEEDED). "
        "POST /fundings/{id}/reverse {reason} undoes it while the box still holds the money.")

ep(A, "F2 Fund Petty Cash (error: float limit)", "POST", "/api/v1/account/petty-cash/fundings",
   body={"pettyCashAccountId": "{{pettyCashAccountId}}", "sourceKind": "BANK", "sourceBankAccountId": "{{bankAccountId}}", "amount": 100000, "fundingDate": "{{today}}"},
   status=409, status_text="Conflict",
   resp={"error": {"code": "FLOAT_LIMIT_EXCEEDED", "message": "This would take PC-IKJ to 299000.00, above its float limit of 250000.00",
                   "details": {"account": "PC-IKJ", "floatLimit": "250000.00", "balanceAfter": "299000.00"}}},
   expect_error={"status": 409, "code": "FLOAT_LIMIT_EXCEEDED"})

ep(A, "F3 List Petty Cash Fundings", "GET", "/api/v1/account/petty-cash/fundings", query=[{"key": "pettyCashAccountId", "value": "{{pettyCashAccountId}}"}],
   resp={"data": {"items": [FUNDING], "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "totalIsEstimate": False, "facets": None,
                  "summary": {"pettyCashAccount": REF("cashAccount", "{{pettyCashAccountId}}"), "code": "PC-IKJ", "openingBalance": 150000.00, "fundedToDate": 50000.00,
                              "utilisedToDate": 0.00, "currentBalance": 199000.00},
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="Perm accounting.read. Paged, newest first. Filters: pettyCashAccountId, locationId, sourceKind, fundedBy, status, amount, from/to (fundingDate). "
        "With pettyCashAccountId the summary is that box's aside: opening balance, funded to date, utilised to date, current balance.")

ep(A, "F4 Pay From Petty Cash", "POST", "/api/v1/account/petty-cash/payments",
   body={"pettyCashAccountId": "{{pettyCashAccountId}}", "expenseCategoryId": "{{expenseCategoryId}}", "amount": 12000, "paidTo": "Total filling station",
         "paymentDate": "{{today}}", "approvedBy": "Mrs Okafor", "description": "20 litres"},
   resp={"data": PAYMENT},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('pettyPaymentId', json.data.id);"],
   desc="Perm accounting.petty-cash.pay. No approval step, so the rules are enforced on the spot: an active category payable from petty cash "
        "(400 CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH), the cash must be there (400 INSUFFICIENT_CASH: petty cash is never overdrawn), and the category's "
        "monthly cap can't be passed (409 MONTHLY_CAP_EXCEEDED). approvedBy is the name of whoever approved it verbally.")

ep(A, "F5 Pay From Petty Cash (error: category not payable from petty cash)", "POST", "/api/v1/account/petty-cash/payments",
   body={"pettyCashAccountId": "{{pettyCashAccountId}}", "expenseCategoryId": "{{bankChargesCategoryId}}", "amount": 500, "paidTo": "Bank", "paymentDate": "{{today}}"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH", "message": "'Bank Charges' can't be paid from petty cash; settle it by bank transfer or cheque instead",
                   "details": {"category": "Bank Charges"}}},
   expect_error={"status": 400, "code": "CATEGORY_NOT_PAYABLE_FROM_PETTY_CASH"})

ep(A, "F6 Reverse Petty Cash Payment", "POST", "/api/v1/account/petty-cash/payments/{{pettyPaymentId}}/reverse",
   body={"reason": "Paid twice"}, resp={"data": dict(PAYMENT, status="REVERSED", reversalReason="Paid twice", balanceAfter=199000.00)},
   desc="The cash goes back into the box; the payment stays on record as REVERSED.")

ep(A, "F7 Petty Cash Payment History", "GET", "/api/v1/account/petty-cash/payments/history", query=[{"key": "pettyCashAccountId", "value": "{{pettyCashAccountId}}"}],
   resp={"data": {"items": [{"sn": 1, "id": "{{pettyPaymentId}}", "date": "2026-10-12", "ref": "PCP-1", "description": "Generator Diesel — Total filling station: 20 litres",
                             "category": "Generator Diesel", "paidTo": "Total filling station", "approvedBy": "Mrs Okafor", "amount": 12000.00,
                             "source": "Petty cash payment", "status": "REVERSED", "pettyCashAccountCode": "PC-IKJ"}],
                  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "totalIsEstimate": False, "facets": None,
                  "summary": {"paymentsMade": 0, "totalPaidOut": 0.00, "categoriesUsed": 0, "largestPayment": 0.00},
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="Perm accounting.read. Everything paid out of petty cash: petty cash payments and running costs approved with the PETTY_CASH method. "
        "Filters: pettyCashAccountId, locationId, expenseCategoryId, source, status, amount, from/to. The summary counts only payments not reversed.")

ep(A, "F8 Petty Cash Statement", "GET", "/api/v1/account/petty-cash/statement", query=[{"key": "pettyCashAccountId", "value": "{{pettyCashAccountId}}"}],
   resp={"data": {"accounts": [{"id": "{{pettyCashAccountId}}", "code": "PC-IKJ", "name": "Ikeja Petty Cash Box", "locationName": "Showroom Ikeja"}],
                  "period": {"from": "2026-10-01", "to": "2026-10-12"},
                  "ledger": PAGED([{"date": "2026-10-12", "ref": "PCF-1", "description": "Funding from BA-1 Guaranty Trust Bank — PCF-1", "category": None,
                                    "direction": "Funding", "in": 50000.00, "out": 0.00, "balance": 199000.00, "accountCode": "PC-IKJ"}])["data"],
                  "statement": {"funding": {"openingBalance": 0.00, "entries": [{"ref": "PCF-1", "date": "2026-10-12", "amount": 50000.00}], "totalAvailable": 50000.00},
                                "utilisationByCategory": [], "totalUtilised": 0.00, "closingBalance": 199000.00, "utilisedPct": 0.0}}},
   desc="Perm accounting.read. One box (pettyCashAccountId) or every box at a location (locationId), for from/to (default this month to date): "
        "the ledger (paged: page, size) with a running balance, funding (opening + top-ups and accepted allocations), utilisation by category net of reversals, "
        "closing balance and the share used.")

ep(A, "F9 Send Petty Cash to a Branch", "POST", "/api/v1/account/petty-cash/allocations",
   body={"sourceKind": "BANK", "sourceBankAccountId": "{{bankAccountId}}", "destinationPettyCashAccountId": "{{pettyCashAccountId}}", "amount": 20000,
         "purpose": "Branch float for October"},
   resp={"data": ALLOCATION},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('allocationId', json.data.id);"],
   desc="Perm accounting.petty-cash.fund. The source pays now; the branch's petty cash is credited only when the branch accepts. "
        "Notifies holders of accounting.petty-cash.respond at the branch. The source must not be at the same location as the destination.")

ep(A, "F10 List Petty Cash Allocations", "GET", "/api/v1/account/petty-cash/allocations", query=[{"key": "status", "value": "PENDING"}],
   resp={"data": {"items": [ALLOCATION], "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "totalIsEstimate": False, "facets": None,
                  "summary": {"awaitingCount": 1, "valuePending": 20000.00, "acceptedValue": 0.00, "rejectedCount": 0},
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="The Pending / Accepted / Rejected tabs (status). A user tied to a branch sees only petty cash sent to that branch.")

ep(A, "F11 Accept Petty Cash Allocation", "POST", "/api/v1/account/petty-cash/allocations/{{allocationId}}/accept",
   resp={"data": dict(ALLOCATION, status="ACCEPTED", respondedBy="Alice Admin", respondedOn=NOW)},
   desc="Perm accounting.petty-cash.respond, at the destination branch. The whole amount lands in the branch's petty cash. "
        "POST …/reject {note} returns it to the source instead.")

ep(A, "F12 Accept Petty Cash Allocation (error: already decided)", "POST", "/api/v1/account/petty-cash/allocations/{{allocationId}}/accept",
   status=409, status_text="Conflict",
   resp={"error": {"code": "ALREADY_DECIDED", "message": "This petty cash allocation has already been decided", "details": {}}},
   expect_error={"status": 409, "code": "ALREADY_DECIDED"})

ep(A, "G1 Save Running Cost Draft", "POST", "/api/v1/account/running-costs", body=COST_BODY, resp={"data": COST},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('runningCostId', json.data.id);",
          "pm.test('a draft has no reference yet', () => pm.expect(json.data.ref).to.eql(null));"],
   desc="Perm accounting.running-costs.submit. submit=false saves a draft (no reference, only field checks); submit=true submits it straight away. "
        "paymentMethod PETTY_CASH or CASH needs payingCashAccountId (a petty cash box, or a till/vault); BANK_TRANSFER or CHEQUE needs payingBankAccountId "
        "(CHEQUE also chequeNumber). inputVatAmount is the VAT included in amount per the supplier's VAT invoice and needs supplierTin.")

ep(A, "G2 Submit Running Cost", "POST", "/api/v1/account/running-costs/{{runningCostId}}/submit",
   resp={"data": dict(COST, ref="RC-1", status="PENDING_APPROVAL", submittedBy="Alice Admin", submittedAt=NOW)},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('runningCostRef', json.data.ref);"],
   desc="Gives the RC-n reference and runs the checks: usable category, a paying account of the right kind (400 INVALID_PAYING_ACCOUNT), enough cash for "
        "cash methods. exceedsMonthlyCap flags (without blocking) a cost that would take the category past its cap. Notifies holders of "
        "accounting.running-costs.approve. PUT /{id} edits a draft or a rejected cost (submit=true resubmits it, keeping its reference).")

ep(A, "G3 Approve Running Cost", "POST", "/api/v1/account/running-costs/{{runningCostId}}/approve",
   resp={"data": dict(COST, ref="RC-1", status="APPROVED", submittedBy="Alice Admin", submittedAt=NOW, approvedBy="Alice Admin", approvedAt=NOW)},
   desc="Perm accounting.running-costs.approve. The money moves now: a bank debit (or cash out) tagged with the category. The approver must not be the submitter "
        "(409 SELF_APPROVAL_NOT_ALLOWED); an Administrator may approve their own while the setting accounting.runningCost.allowSelfApprovalForAdmin is on "
        "(the default, for one-person companies), which is what happens here. POST …/reject {rejectionReason} sends it back with nothing paid.")

ep(A, "G4 List Running Costs", "GET", "/api/v1/account/running-costs",
   resp={"data": {"items": [dict(COST, ref="RC-1", status="APPROVED")], "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "totalIsEstimate": False,
                  "facets": None, "summary": {"count": 1, "total": 45000.00, "pendingCount": 0, "pendingValue": 0.00},
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="Perm accounting.read. Filters: status, expenseCategoryId, paymentMethod, locationId, payingAccountId, submittedBy, approvedBy, exceedsMonthlyCap, "
        "hasInputVat, amount, paymentDate (from/to), submittedAt; awaitingMyApproval=true = pending ones you didn't submit. q matches reference, invoice "
        "and cheque numbers, supplier TIN, payee, description and amounts. GET /running-costs/filters lists them.")

ep(A, "G5 Reverse Running Cost", "POST", "/api/v1/account/running-costs/{{runningCostId}}/reverse",
   body={"reason": "Paid to the wrong supplier"},
   resp={"data": dict(COST, ref="RC-1", status="REVERSED", reversalReason="Paid to the wrong supplier")},
   desc="Perm accounting.running-costs.approve. Only an APPROVED cost; the money goes back to the paying account.")

ep(A, "G6 Save Another Draft", "POST", "/api/v1/account/running-costs", body=dict(COST_BODY, payee="Draft only", amount=1000, inputVatAmount=0, supplierTin=None),
   resp={"data": dict(COST, id="{{draftCostId}}", payee="Draft only", amount=1000.00, inputVatAmount=0.00, supplierTin=None)},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('draftCostId', json.data.id);"])

ep(A, "G7 Delete Running Cost Draft", "DELETE", "/api/v1/account/running-costs/{{draftCostId}}",
   resp={"data": {"id": "{{draftCostId}}", "deleted": True}},
   desc="Drafts only (409 NOT_EDITABLE otherwise): a draft isn't a document yet; submitted costs are never deleted.")

ep(A, "H1 Register Branch Bank Account", "POST", "/api/v1/account/bank-accounts",
   body={"bankName": "Access Bank", "accountName": "Apex Industries Ikeja", "accountNumber": "0698877665", "accountType": "CURRENT", "locationId": "{{showroomId}}"},
   resp={"data": dict(BANK, id="{{branchBankAccountId}}", code="BA-2", bankName="Access Bank", accountName="Apex Industries Ikeja", accountNumber="0698877665",
                      accountNumberMasked="•••• 7665", accountType="CURRENT", openingBalance=0.00, currentBalance=0.00, locationId="{{showroomId}}",
                      locationName="Showroom Ikeja", branch=None, sortCodeOrBic=None, authorisedSignatory=None, relationshipManager=None, dateOpened=None)},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('branchBankAccountId', json.data.id);"],
   desc="The showroom's own account, to receive the funds sent in H2.")

ep(A, "H2 Send Funds to a Branch", "POST", "/api/v1/account/fund-disbursements",
   body={"channel": "BANK_TRANSFER", "sourceBankAccountId": "{{bankAccountId}}", "destinationLocationId": "{{showroomId}}",
         "destinationBankAccountId": "{{branchBankAccountId}}", "purpose": "Restocking float", "sentAmount": 300000},
   resp={"data": DISBURSEMENT},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('disbursementId', json.data.id);"],
   desc="Perm accounting.funds.send. channel BANK_TRANSFER (source and destination bank accounts) or CASH (source cash account, destination vault or till). "
        "The destination account must belong to the destination location (400 INVALID_DESTINATION_ACCOUNT). The source pays now (BF-n); holders of "
        "accounting.funds.confirm at the destination are notified.")

ep(A, "H3 Confirm Funds (error: more than was sent)", "POST", "/api/v1/account/fund-disbursements/{{disbursementId}}/confirm",
   body={"receivedAmount": 300001, "dateReceived": "{{today}}"}, status=400, status_text="Bad Request",
   resp={"error": {"code": "RECEIVED_EXCEEDS_SENT", "message": "The amount received can't exceed the amount sent", "details": {}}},
   expect_error={"status": 400, "code": "RECEIVED_EXCEEDS_SENT"})

ep(A, "H4 Confirm Funds Received (with a shortfall)", "POST", "/api/v1/account/fund-disbursements/{{disbursementId}}/confirm",
   body={"receivedAmount": 299500, "dateReceived": "{{today}}", "bankCreditReference": "NIP-55120", "shortfallReason": "Bank deducted a transfer charge"},
   resp={"data": dict(DISBURSEMENT, received=299500.00, shortfall=500.00, shortfallReason="Bank deducted a transfer charge", status="PARTIALLY_CONFIRMED",
                      confirmedBy="Alice Admin", dateReceived="2026-10-12")},
   tests=["const json = pm.response.json();", "pm.test('shortfall recorded', () => pm.expect(json.data.shortfall).to.eql(500));"],
   desc="Perm accounting.funds.confirm, at the destination. The destination is credited with what arrived. Less than sent needs shortfallReason "
        "(400 SHORTFALL_REASON_REQUIRED); the shortfall is reported to the sender and Management, not re-credited anywhere. "
        "POST …/reject {reason} returns the full amount to the source instead.")

ep(A, "H5 List Fund Disbursements", "GET", "/api/v1/account/fund-disbursements",
   resp={"data": {"items": [dict(DISBURSEMENT, received=299500.00, shortfall=500.00, status="PARTIALLY_CONFIRMED")], "page": 0, "size": 50, "totalItems": 1,
                  "totalPages": 1, "totalIsEstimate": False, "facets": None, "summary": {"awaiting": 0, "valueAwaiting": 0.00, "shortfallTotal": 500.00},
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="Filters: locationId (destination), status, channel, hasShortfall, sentBy, sentAmount, from/to (sentAt). The destination's Awaiting cards are status=AWAITING.")

ep(A, "I1 Count the Till (puts the day's takings on the books)", "POST", "/api/v1/account/cash-accounts/{{tillAccountId}}/cash-counts",
   body={"countedAmount": 32500, "countDate": "{{today}}", "note": "Takings counted at close (no sales module yet)"},
   resp={"data": {"account": {"id": "{{tillAccountId}}", "code": "TILL-IKJ-01", "currentBalance": 32500.00}, "bookBalance": 0.00, "countedAmount": 32500.00,
                  "variance": 32500.00, "adjustmentReference": "CC-2"}},
   desc="Until Sales & POS posts takings into tills, a count gives this till cash to lodge in I2.")

ep(A, "I2 Record Cash Lodgement", "POST", "/api/v1/account/cash-lodgements",
   body={"locationId": "{{showroomId}}", "cashAccountId": "{{tillAccountId}}", "depositDate": "{{today}}", "takings": 32500, "banked": 30000,
         "bankAccountId": "{{bankAccountId}}", "tellerReference": "TLR-8812"},
   resp={"data": LODGEMENT},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('lodgementId', json.data.id);",
          "pm.test('retained = takings - banked', () => pm.expect(json.data.retained).to.eql(2500));"],
   desc="Perm accounting.lodgements.manage (Cashiers have it). The cash leaves the till now (it has physically gone to the bank); retained = takings − banked "
        "(400 BANKED_EXCEEDS_TAKINGS). The bank can be any active company account, including head office's collection account.")

ep(A, "I3 Record Cash Lodgement (error: banked exceeds takings)", "POST", "/api/v1/account/cash-lodgements",
   body={"locationId": "{{showroomId}}", "cashAccountId": "{{tillAccountId}}", "depositDate": "{{today}}", "takings": 1000, "banked": 2000, "bankAccountId": "{{bankAccountId}}"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "BANKED_EXCEEDS_TAKINGS", "message": "The amount banked can't exceed the takings", "details": {}}},
   expect_error={"status": 400, "code": "BANKED_EXCEEDS_TAKINGS"})

ep(A, "I4 Confirm Cash Lodgement", "POST", "/api/v1/account/cash-lodgements/{{lodgementId}}/confirm",
   resp={"data": dict(LODGEMENT, status="CONFIRMED", confirmedBy="Alice Admin", confirmedAt=NOW)},
   desc="Perm accounting.lodgements.confirm. Credits the bank: the deposit now shows on the Bank Statement as well as an out-flow on the showroom's "
        "Cash Inflow/Outflow. POST …/reject {reason} (the bank never got it) flags it to Management; the cash is not put back.")

ep(A, "I5 List Cash Lodgements", "GET", "/api/v1/account/cash-lodgements",
   resp={"data": {"items": [dict(LODGEMENT, status="CONFIRMED")], "page": 0, "size": 50, "totalItems": 1, "totalPages": 1, "totalIsEstimate": False,
                  "facets": None, "summary": {"takings": 32500.00, "banked": 30000.00, "retained": 2500.00},
                  "meta": {"appliedFilters": {}, "ignoredQualifiers": [], "suggestions": [], "matches": {}}}},
   desc="Cash Transfer History. Filters: locationId, bankAccountId, status, depositedBy, banked, from/to (depositDate); q matches reference, teller reference, amount.")

ep(A, "J1 Global Search: Running Cost", "GET", "/api/v1/search",
   query=[{"key": "q", "value": "{{runningCostRef}}"}, {"key": "types", "value": "running-cost"}],
   resp={"data": {"groups": [{"type": "running-cost", "label": "Running costs", "hits": [
       {"type": "running-cost", "id": "{{runningCostId}}", "title": "RC-1", "subtitle": "CoolAir Ltd · ₦45,000.00 · reversed", "ref": "RC-1",
        "route": "/account/running-costs/{{runningCostId}}"}], "more": False, "moreRoute": "/account/running-costs?q=RC-1"}], "timedOut": []}},
   tests=["const json = pm.response.json();",
          "pm.test('the running cost is found', () => pm.expect(json.data.groups.find(g => g.type === 'running-cost').hits.length).to.be.at.least(1));"],
   desc="Header search also finds running costs (by reference, payee or invoice number) and cash lodgements (type lodgement).")

ep(A, "E1 Global Search: Bank Entry by Reference", "GET", "/api/v1/search",
   query=[{"key": "q", "value": "{{manualEntryRef}}"}, {"key": "types", "value": "bank-transaction"}],
   resp={"data": {"groups": [{"type": "bank-transaction", "label": "Bank transactions", "hits": [
       {"type": "bank-transaction", "id": "{{manualEntryId}}", "title": "MAN-1", "subtitle": "BA-1 · debit ₦1,500.00 · 2026-10-12", "ref": "MAN-1",
        "route": "/account/bank-accounts/{{bankAccountId}}/statement?q=MAN-1"}], "more": False, "moreRoute": "/account/bank-accounts?q=MAN-1"}], "timedOut": []}},
   tests=["const json = pm.response.json();",
          "pm.test('the entry is found', () => pm.expect(json.data.groups.find(g => g.type === 'bank-transaction').hits.length).to.eql(1));"],
   desc="The header search finds a bank entry by its exact reference (perm accounting.read), within the accounts the caller can see.")

RECEIPT = {"id": "{{pendingReceiptId}}", "ref": "RCT-2", "kind": "RECEIPT", "receivedAt": NOW, "location": REF("location", "{{showroomId}}"), 
           "cashAccount": None, "cashier": USER, "customer": REF("customer", "{{dealerId}}"),
           "customerName": "Okafor & Sons Distribution", "channel": "FUND_TRANSFER", "amount": 200000.00, "sourceModule": "account",
           "sourceType": "WALLET_FUNDING", "sourceRef": "WF-2", "incomeCategory": None, "description": None, "receivingBankAccount": REF("bankAccount", "{{bankAccountId}}"),
           "terminalRef": None, "payerReference": "NIP-000123", "confirmationStatus": "PENDING",
           "reviewedBy": None, "reviewedAt": None, "declineNote": None, "status": "ISSUED", "voidReason": None}
WFUND = {"id": "{{walletFundingId}}", "ref": "WF-1", "date": NOW, "customer": REF("customer", "{{dealerId}}"), "customerCode": "DLR-1", "customerName": "Okafor & Sons Distribution",
         "balanceType": "WALLET", "channel": "CASH", "amount": 75000.00, "location": REF("location", "{{showroomId}}"), 
         "receipt": REF("receipt", "{{fundingReceiptId}}"), "confirmationStatus": "NOT_REQUIRED", "status": "CONFIRMED", "createdBy": USER}
WITHDRAWAL = {"id": "{{withdrawalId}}", "ref": "WW-1", "date": "2026-10-12", "customer": REF("customer", "{{dealerId}}"), "customerCode": "DLR-1",
              "customer": "Okafor & Sons Distribution", "balanceType": "WALLET", "initiatedBy": "DEALER", "reason": "DEALER_REQUEST",
              "balanceBefore": 275000.00, "amount": 20000.00, "balanceAfter": 255000.00, "payoutKind": "BANK", "payoutAccount": "BA-1",
              "paidVia": "Transfer to Access 0123456789", "otp": "Verified", "authorisedBy": None, "processedBy": USER,
              "auditStatus": "COMPLETED", "reversalReason": None, "note": "Dealer request by phone", "createdAt": NOW}
ep(A, "K1 Fund Dealer Wallet (cash)", "POST", "/api/v1/account/wallet-fundings",
   body={"customerId": "{{dealerId}}", "balanceType": "WALLET", "channel": "CASH", "amount": 75000, "locationId": "{{showroomId}}", "cashAccountId": "{{tillAccountId}}"},
   resp={"data": WFUND},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('walletFundingId', json.data.id);", "pm.collectionVariables.set('fundingReceiptId', json.data.receipt.id);",
          "pm.test('cash is credited at once', () => pm.expect(json.data.status).to.eql('CONFIRMED'));"],
   desc="Perm accounting.wallet.fund (Cashiers have it). WALLET for Authorised Dealers, DEPOSIT for Special Customers (400 BALANCE_TYPE_NOT_ALLOWED otherwise). "
        "Creates a receipt (RCT-n) and the funding (WF-n). CASH (into cashAccountId, a till or vault at locationId) credits the wallet at once; "
        "POS, FUND_TRANSFER and CHEQUE wait until Accounts confirms the receipt — a dealer can't spend money that hasn't been verified.")

ep(A, "K2 Fund Dealer Wallet (fund transfer, awaits confirmation)", "POST", "/api/v1/account/wallet-fundings",
   body={"customerId": "{{dealerId}}", "balanceType": "WALLET", "channel": "FUND_TRANSFER", "amount": 200000, "locationId": "{{showroomId}}",
         "receivingBankAccountId": "{{bankAccountId}}", "payerReference": "NIP-000123"},
   resp={"data": dict(WFUND, ref="WF-2", channel="FUND_TRANSFER", amount=200000.00, receiptId="{{pendingReceiptId}}", receiptRef="RCT-2",
                      confirmationStatus="PENDING", status="AWAITING_CONFIRMATION")},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('pendingReceiptId', json.data.receipt.id);",
          "pm.test('waits for Accounts', () => pm.expect(json.data.status).to.eql('AWAITING_CONFIRMATION'));"])

ep(A, "K3 Fund Wallet (error: not a dealer)", "POST", "/api/v1/account/wallet-fundings",
   body={"customerId": "{{specialId}}", "balanceType": "WALLET", "channel": "CASH", "amount": 1000, "locationId": "{{showroomId}}", "cashAccountId": "{{tillAccountId}}"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "BALANCE_TYPE_NOT_ALLOWED", "message": "Only Authorised Dealers can fund a wallet directly; other customers' wallets hold return credits", "details": {}}},
   expect_error={"status": 400, "code": "BALANCE_TYPE_NOT_ALLOWED"},
   desc="A Special Customer is funded with balanceType DEPOSIT instead.")

ep(A, "K4 Confirmation Queue (POS / Fund Transfer Confirmation)", "GET", "/api/v1/account/receipts",
   query=[{"key": "confirmationStatus", "value": "PENDING"}, {"key": "channel", "value": "POS,FUND_TRANSFER,CHEQUE"}],
   resp=PAGED([RECEIPT], {"count": 1, "totalByChannel": {"FUND_TRANSFER": 200000.00}, "pendingCount": 1, "pendingValue": 200000.00}),
   desc="Perm accounting.receipts.read (a Cashier sees only receipts they took). The screen's Pending tab; Approved and Declined tabs filter confirmationStatus. "
        "Filters: locationId, cashAccountId, cashierUserId, channel, kind, confirmationStatus, status, sourceType, customerId, receivingBankAccountId, "
        "incomeCategory, pendingOlderThanHours, amount, from/to (receivedAt); q matches receipt, source (INV-…, TXN-…), payer and terminal references, customer.")

ep(A, "K5 Confirmation Stats", "GET", "/api/v1/account/receipts/confirmation-stats",
   resp={"data": {"pendingCount": 1, "pendingValue": 200000.00, "approvedToday": 0, "declinedToday": 0}})

ep(A, "K6 Approve Receipt", "POST", "/api/v1/account/receipts/{{pendingReceiptId}}/approve",
   resp={"data": dict(RECEIPT, confirmationStatus="APPROVED", reviewedBy="Alice Admin", reviewedAt=NOW)},
   tests=["const json = pm.response.json();", "pm.test('approved', () => pm.expect(json.data.confirmationStatus).to.eql('APPROVED'));"],
   desc="Perm accounting.receipts.confirm, at the receipt's location. The bank is credited now; for a wallet/deposit funding the wallet is credited too. "
        "POST …/decline {note} records that nothing arrived: nothing is posted, a funding is never credited, and the cashier is notified.")

ep(A, "K7 Approve Receipt (error: already decided)", "POST", "/api/v1/account/receipts/{{pendingReceiptId}}/approve",
   status=409, status_text="Conflict",
   resp={"error": {"code": "ALREADY_DECIDED", "message": "This receipt has already been decided", "details": {}}},
   expect_error={"status": 409, "code": "ALREADY_DECIDED"})

ep(A, "K8 List Wallet Fundings", "GET", "/api/v1/account/wallet-fundings", query=[{"key": "customerId", "value": "{{dealerId}}"}],
   resp=PAGED([WFUND], {"total": 275000.00, "confirmed": 275000.00, "awaiting": 0.00}),
   desc="Perm accounting.read. Filters: customerId, balanceType, status, channel, locationId, amount, from/to.")

ep(A, "K9 Print Receipt", "GET", "/api/v1/account/receipts/{{fundingReceiptId}}/receipt.pdf", pdf=True,
   desc="A5 receipt with the company header and logo from Settings, the channel, amount and a PAID / PENDING CONFIRMATION / DECLINED / VOID stamp; "
        "\"DUPLICATE COPY\" once it has been reprinted. Every receipt is also in the document registry (GET /api/v1/documents).")

ep(A, "L1 Request Withdrawal OTP", "POST", "/api/v1/otp/challenges",
   body={"purpose": "WALLET_WITHDRAWAL", "targetReference": "{{dealerId}}", "context": {"amount": 20000, "targetLabel": "Okafor & Sons Distribution"}},
   resp={"data": {"challengeId": "{{withdrawalOtpId}}", "status": "AWAITING", "expiresAt": None}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('withdrawalOtpId', json.data.challengeId);"],
   desc="A dealer-requested withdrawal needs an Admin-issued code for that dealer (targetReference = the customer id) and that exact amount (context.amount).")

ep(A, "L2 Issue Withdrawal OTP (Admin)", "POST", "/api/v1/otp/challenges/{{withdrawalOtpId}}/issue",
   resp={"data": {"challengeId": "{{withdrawalOtpId}}", "code": "{{withdrawalOtpCode}}", "expiresAt": NOW}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('withdrawalOtpCode', json.data.code);"])

ep(A, "L3 Verify Withdrawal OTP", "POST", "/api/v1/otp/challenges/{{withdrawalOtpId}}/verify",
   body={"code": "{{withdrawalOtpCode}}"}, resp={"data": {"verified": True}})

ep(A, "L4 Withdraw (error: amount differs from the OTP)", "POST", "/api/v1/account/wallet-withdrawals",
   body={"customerId": "{{dealerId}}", "balanceType": "WALLET", "reason": "DEALER_REQUEST", "amount": 30000, "payoutKind": "BANK",
         "payoutBankAccountId": "{{bankAccountId}}", "otpChallengeId": "{{withdrawalOtpId}}", "withdrawalDate": "{{today}}"},
   status=403, status_text="Forbidden",
   resp={"error": {"code": "OTP_MISMATCH", "message": "This OTP was issued for a different withdrawal: it was issued for ₦20,000.00, not ₦30,000.00", "details": {}}},
   expect_error={"status": 403, "code": "OTP_MISMATCH"})

ep(A, "L5 Withdraw From Dealer Wallet", "POST", "/api/v1/account/wallet-withdrawals",
   body={"customerId": "{{dealerId}}", "balanceType": "WALLET", "reason": "DEALER_REQUEST", "amount": 20000, "payoutKind": "BANK",
         "payoutBankAccountId": "{{bankAccountId}}", "paidVia": "Transfer to Access 0123456789", "otpChallengeId": "{{withdrawalOtpId}}",
         "withdrawalDate": "{{today}}", "locationId": "{{showroomId}}", "note": "Dealer request by phone"},
   resp={"data": WITHDRAWAL},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('withdrawalId', json.data.id);",
          "pm.test('OTP verified', () => pm.expect(json.data.otp).to.eql('Verified'));"],
   desc="Perm accounting.wallet.withdraw for DEALER_REQUEST and DEPOSIT_REFUND (money paid out: payoutKind CASH or BANK); accounting.wallet.recover for "
        "EXCESS_COMMISSION_RECOVERY and OVER_CREDIT_CORRECTION (payoutKind NONE, authorisedByUserId required). A dealer request needs the verified OTP "
        "(403 OTP_REQUIRED / OTP_MISMATCH), usable once. The balance never goes negative (400 INSUFFICIENT_WALLET_BALANCE). "
        "\"Settle an outstanding invoice\" is an invoice payment by wallet, not a withdrawal (400 USE_INVOICE_PAYMENT).")

ep(A, "L6 List Wallet Withdrawals", "GET", "/api/v1/account/wallet-withdrawals",
   resp=PAGED([WITHDRAWAL], {"totalWithdrawn": 20000.00, "dealerInitiated": 20000.00, "companyInitiated": 0.00}),
   desc="Perm accounting.read. Filters: customerId, reason, initiatedBy, payoutKind, auditStatus, processedBy, otpVerified, amount, from/to.")

ep(A, "L7 Flag Withdrawal for Review", "PUT", "/api/v1/account/wallet-withdrawals/{{withdrawalId}}/audit-status",
   body={"auditStatus": "UNDER_REVIEW"}, resp={"data": dict(WITHDRAWAL, auditStatus="UNDER_REVIEW")},
   desc="Perm accounting.wallet.recover. POST …/reverse {reason} credits the balance back and returns the payout.")

ep(A, "L8 Withdraw (error: invoice settlement)", "POST", "/api/v1/account/wallet-withdrawals",
   body={"customerId": "{{dealerId}}", "balanceType": "WALLET", "reason": "INVOICE_SETTLEMENT", "amount": 1000, "payoutKind": "NONE", "withdrawalDate": "{{today}}"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "USE_INVOICE_PAYMENT", "message": "Settling an invoice from the wallet is an invoice payment (method WALLET), not a withdrawal", "details": {}}},
   expect_error={"status": 400, "code": "USE_INVOICE_PAYMENT"})

ep(A, "M1 Tenderable: Payable", "GET", "/api/v1/account/tenderable/payable", query=[{"key": "locationId", "value": "{{showroomId}}"}],
   resp=PAGED([{"date": "2026-10-12", "cashier": USER, "ftf": 200000.00, "cash": 75000.00, "pos": 0.00,
                 "wallet": 0.00, "deposit": 0.00, "refund": 0.00, "total": 275000.00, "pendingConfirmation": 0.00, "declined": 0.00}]),
   desc="Perm accounting.tenderable.read. One row per cashier per day, newest day first, paged (page, size): ftf (transfers and cheques), cash, pos, wallet, deposit, refund and total. "
        "Pending and declined receipts are shown apart and never counted in the total.")

ep(A, "M2 Tenderable: General Report", "GET", "/api/v1/account/tenderable/general-report",
   resp={"data": {"period": {"from": None, "to": None}, "rows": [{"cashier": USER, "daysActive": 1, "ftf": 200000.00,
                  "cash": 75000.00, "pos": 0.00, "wallet": 0.00, "deposit": 0.00, "refund": 0.00, "total": 275000.00}],
                  "totals": {"cashier": None, "daysActive": 1, "ftf": 200000.00,
                             "cash": 75000.00, "pos": 0.00, "wallet": 0.00, "deposit": 0.00, "refund": 0.00, "total": 275000.00}}},
   desc="Per cashier for the period (locationId, from, to), with grand totals. GET /tenderable/receipts is the Receipt Track (the receipts list).")

ep(A, "M3 Tenderable: Wallet Funds", "GET", "/api/v1/account/tenderable/wallet-funds", query=[{"key": "customerId", "value": "{{dealerId}}"}],
   resp=PAGED([{"ref": "WF-2", "date": NOW, "customer": REF("customer", "{{dealerId}}"), "dealer": "DLR-1 Okafor & Sons Distribution", "method": "Fund transfer",
                "bank": "BA-1 Guaranty Trust Bank", "amount": 200000.00, "confirmedBy": USER, "status": "Confirmed"}],
              {"total": 275000.00, "confirmed": 275000.00, "awaiting": 0.00}),
   desc="Dealers' wallet fundings plus the return credits Customers posted for them (method \"Return credit\").")

ep(A, "N1 Record Other Income", "POST", "/api/v1/account/other-income",
   body={"locationId": "{{showroomId}}", "incomeCategory": "SCRAP_SALES", "channel": "CASH", "amount": 18000, "cashAccountId": "{{tillAccountId}}",
         "payerName": "Mile 12 scrap dealers", "description": "Sale of damaged pallets", "receivedDate": "{{today}}"},
   resp={"data": dict(RECEIPT, id="{{otherIncomeId}}", ref="RCT-3", cashAccountId="{{tillAccountId}}", till="TILL-IKJ-01", customerId=None,
                      customerName="Mile 12 scrap dealers", channel="CASH", amount=18000.00, sourceType="OTHER_INCOME", sourceRef="RCT-3",
                      incomeCategory="SCRAP_SALES", description="Sale of damaged pallets", receivingBankAccountId=None, receivingBank=None,
                      payerReference=None, confirmationStatus="NOT_REQUIRED")},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('otherIncomeId', json.data.id);"],
   desc="Perm accounting.receipts.record. By-product and scrap sales, rent, interest received and other money that isn't a sale, invoice payment or wallet funding. "
        "incomeCategory BY_PRODUCT_SALES, SCRAP_SALES, RENTAL, INTEREST or OTHER; the usual channel rules apply (POS / transfer / cheque wait for confirmation).")

ep(A, "N2 List Other Income", "GET", "/api/v1/account/other-income", query=[{"key": "incomeCategory", "value": "SCRAP_SALES"}],
   resp=PAGED([dict(RECEIPT, id="{{otherIncomeId}}", ref="RCT-3", channel="CASH", amount=18000.00, sourceType="OTHER_INCOME", incomeCategory="SCRAP_SALES",
                    confirmationStatus="NOT_REQUIRED")], {"count": 1, "totalByChannel": {"CASH": 18000.00}, "pendingCount": 0, "pendingValue": 0.00}))

ep(A, "N3 Void Other Income", "POST", "/api/v1/account/receipts/{{otherIncomeId}}/void", body={"reason": "Recorded twice"},
   resp={"data": dict(RECEIPT, id="{{otherIncomeId}}", ref="RCT-3", channel="CASH", amount=18000.00, sourceType="OTHER_INCOME", incomeCategory="SCRAP_SALES",
                      confirmationStatus="NOT_REQUIRED", status="VOIDED", voidReason="Recorded twice")},
   desc="Perm accounting.receipts.void. Wallet/deposit fundings and other income are voided here, undoing what they posted (a spent wallet funding can't be: "
        "400 INSUFFICIENT_WALLET_BALANCE); sales and invoice payments are voided in their own modules (409 REVERSE_IN_SOURCE_MODULE).")

ep(A, "O1 VAT per Location", "GET", "/api/v1/account/vat-control/locations",
   resp={"data": {"disabledCount": 0, "locations": [{"location": REF("location", "{{showroomId}}"), 
                  "registration": None, "rate": 7.50, "enabled": True, "effectiveFrom": "2026-10-12", "updatedBy": USER, "scheduled": None}]}},
   desc="Perm accounting.read. Each showroom/factory's VAT in force today and any scheduled change; disabledCount drives the screen's warning.")

ep(A, "O2 Schedule a VAT Change", "POST", "/api/v1/account/vat-control/locations/{{showroomId}}",
   body={"vatEnabled": True, "vatRate": 10, "vatRegistrationNumber": "NG-VAT-4471029", "effectiveFrom": "2030-01-01", "note": "Rate review"},
   resp={"data": {"disabledCount": 0, "locations": [{"location": REF("location", "{{showroomId}}"), 
                  "registration": None, "rate": 7.50, "enabled": True, "effectiveFrom": "2026-10-12", "updatedBy": USER,
                  "scheduled": {"rate": 10.00, "enabled": True, "effectiveFrom": "2030-01-01"}}]}},
   desc="Perm accounting.vat.manage. effectiveFrom today or later (past periods keep the VAT they were charged at); one change per location per date "
        "(409 VAT_SETTING_EXISTS_FOR_DATE). Today's change applies at once; later ones apply on their date. A showroom with collection disabled charges no output VAT.")

ep(A, "O3 Schedule a VAT Change (error: same date)", "POST", "/api/v1/account/vat-control/locations/{{showroomId}}",
   body={"vatEnabled": True, "vatRate": 12, "effectiveFrom": "2030-01-01"}, status=409, status_text="Conflict",
   resp={"error": {"code": "VAT_SETTING_EXISTS_FOR_DATE", "message": "A VAT setting already takes effect on 2030-01-01 for this location", "details": {"effectiveFrom": "2030-01-01"}}},
   expect_error={"status": 409, "code": "VAT_SETTING_EXISTS_FOR_DATE"})

ep(A, "O4 VAT History for a Location", "GET", "/api/v1/account/vat-control/locations/{{showroomId}}/history",
   resp={"data": [{"id": "{{vatSettingId}}", "enabled": True, "rate": 10.00, "registration": "NG-VAT-4471029", "effectiveFrom": "2030-01-01", "note": "Rate review",
                   "updatedBy": USER, "createdAt": NOW, "state": "Scheduled"}]})

# ============================== ELECTRONIC INVOICING (docs/08, slice 1) ==============================
I = "18 - Electronic Invoicing"

INVOICE_LINE = {"id": "6d1f0c2e-1b7a-4a8e-9f61-3d2a1c9b7e01", "lineNo": 1, "stockItem": REF("stockItem", "{{finishedProductId}}"), "code": "ITM-2", "name": "Citrus Drink 50cl",
                "quantity": 12.000, "unit": "Cartons", "nrsUnitCode": None, "unitPrice": 4100.00, "discountAmount": 0.00, "discountRate": None,
                "lineNet": 49200.00, "vatCategory": "STANDARD_RATE", "nrsTaxCategory": "STANDARD_VAT", "vatRate": 7.50, "vatAmount": 3690.00,
                "lineTotal": 52890.00, "isService": False, "hsnCode": None, "hsnSectionCode": "VI",
                "productCategory": "Products of the chemical or allied industries", "isicCode": None, "serviceCategory": None}

INVOICE = {"id": "{{invoiceId}}", "number": "INV-1", "status": "ISSUED", "displayStatus": "Issued", "invoiceTypeCode": "381", "invoiceKind": "B2C",
           "issueDate": "2026-10-15", "issueTime": "11:42:05", "dueDate": "2026-11-14", "paymentTermsDays": 30, "paymentTermsNote": None, "daysOverdue": 0,
           "issuedBy": USER, "issuedAt": NOW, "createdAt": NOW, "showroom": {"id": "{{showroomId}}", "name": "Showroom Ikeja"},
           "issuer": {"tradingName": "Apex Industries", "legalName": None, "tin": None, "vatRegistrationNumber": None, "nrsBusinessId": None,
                      "email": None, "phone": "08030000000", "address": "Lagos", "locationName": "Showroom Ikeja", "locationAddress": None},
           "buyer": {"customer": REF("customer", "{{dealerId}}"), "code": "DLR-1", "name": "Okafor & Sons Distribution", "type": "AUTHORISED_DEALER", "email": None,
                     "phone": "+2348031234567", "tin": None, "businessDescription": None, "address": None, "stateCode": "NG-LA", "lgaCode": None, "countryCode": "NG"},
           "lines": [INVOICE_LINE],
           "totals": {"lineExtension": 49200.00, "discount": 0.00, "vat": 3690.00, "total": 52890.00, "paid": 0.00, "credited": 0.00, "balance": 52890.00,
                      "pendingConfirmation": 0.00},
           "vat": {"enabled": True, "rate": 7.50},
           "linked": {"sourceType": "POS_SALE", "sourceRef": "SBX-1", "proForma": None, "waybills": [], "payments": [], "creditNotes": [],
                      "reissueOf": None, "replacedBy": None},
           "delivery": {"status": "No email on file", "lastAttemptAt": None, "recipient": None},
           "regulatory": {"irn": None, "cryptographicStampId": None, "qrCode": None, "accessPointSubmission": "Not in this phase", "nrsSubmissionStatus": "NOT_SUBMITTED"},
           "voidReason": None, "voidedBy": None, "voidedAt": None}

INVOICE_ROW = {"id": "{{invoiceId}}", "number": "INV-1", "status": "ISSUED", "displayStatus": "Issued", "issued": "2026-10-15", "due": "2026-11-14",
               "customer": REF("customer", "{{dealerId}}"), "customer": "Okafor & Sons Distribution", "customerType": "AUTHORISED_DEALER", "location": REF("location", "{{showroomId}}"),
               "sourceType": "POS_SALE", "source": "POS sale", "sourceRef": "SBX-1", "proForma": None, "waybills": [],
               "total": 52890.00, "paid": 0.00, "credited": 0.00, "balance": 52890.00, "daysOverdue": 0, "delivery": "No email on file"}

INVOICE_SUMMARY = {"invoicesOnFile": 3, "combinedValue": 105780.00, "outstanding": 105780.00, "overdue": 0.00, "fromProForma": 1, "fromPos": 2, "withWaybills": 0}

SAVE_INVOICE = lambda var: ["const json = pm.response.json();", f"pm.collectionVariables.set('{var}', json.data.id);"]

ep(I, "A1 Get Stock Item Classification", "GET", "/api/v1/inventory/stock-items/{{finishedProductId}}/classification",
   resp={"data": {"id": "{{finishedProductId}}", "code": "ITM-2", "name": "Citrus Drink 50cl", "hsnSectionCode": "VI", "hsnCode": None,
                  "isicSectionCode": "C", "isicCode": None, "nrsUnitCode": None, "isService": False}},
   desc="Perm accounting.vat.manage, inventory.stock.manage or inventory.stock.read. The item's HS and ISIC sections plus the optional granular "
        "codes that go on NRS invoice lines (docs/08 §2.1).")

ep(I, "A2 Set NRS Unit Code", "PATCH", "/api/v1/inventory/stock-items/{{finishedProductId}}/classification",
   body={"nrsUnitCode": "10", "isService": False},
   resp={"data": {"id": "{{finishedProductId}}", "code": "ITM-2", "name": "Citrus Drink 50cl", "hsnSectionCode": "VI", "hsnCode": None,
                  "isicSectionCode": "C", "isicCode": None, "nrsUnitCode": "10", "isService": False}},
   desc="Perm accounting.vat.manage or inventory.stock.manage. PATCH: an omitted field is unchanged, \"\" clears it. hsnCode (goods) and isicCode "
        "(services) are optional because V6 only sample-seeds those lists; an invoice line falls back to the item's section.")

ep(I, "A3 Set HS Code (error: other section)", "PATCH", "/api/v1/inventory/stock-items/{{finishedProductId}}/classification",
   body={"hsnCode": "0101.21"}, status=400, status_text="Bad Request",
   resp={"error": {"code": "HSN_SECTION_MISMATCH", "message": "The HS/ISIC code's section must match the item's classification section", "details": {}}},
   expect_error={"status": 400, "code": "HSN_SECTION_MISMATCH"},
   desc="0101.21 (live horses) is in HS section I; this item is in section VI.")

ep(I, "B1 Issue Invoice (sandbox POS sale)", "POST", "/api/v1/invoicing/sandbox/invoices",
   body={"sourceType": "POS_SALE", "locationId": "{{showroomId}}", "customerId": "{{dealerId}}",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 12, "unitPrice": 4100}]},
   resp={"data": INVOICE},
   tests=SAVE_INVOICE("invoiceId") + ["pm.collectionVariables.set('invoiceNumber', json.data.number);",
                                      "pm.collectionVariables.set('invoiceSourceRef', json.data.linked.sourceRef);",
                                      "pm.test('numbered INV-n', () => pm.expect(json.data.number).to.match(/^INV-\\d+$/));",
                                      "pm.test('total = net + VAT', () => pm.expect(json.data.totals.total).to.eql(json.data.totals.lineExtension + json.data.totals.vat));"],
   desc="Test support until Sales & POS (docs/09) exists: it assembles the lines and calls the InvoiceIssuer spi, which this endpoint stands in for. "
        "Perm invoicing.invoice.issue; off when ZIFIQ_INVOICING_SANDBOX=false. sourceRef defaults to SBX-n. "
        "Issuing assigns the next gap-free INV number in the same transaction, snapshots the company profile, showroom and buyer, and prices VAT per line: "
        "the showroom's rate for Standard-Rate items while it collects VAT, 0 for zero-rated and exempt items. Each line is rounded on its own and the "
        "totals are sums of lines. Payment terms default to invoicing.defaultPaymentTermsDays (30).")

ep(I, "B2 Issue Invoice to a Walk-in", "POST", "/api/v1/invoicing/sandbox/invoices",
   body={"locationId": "{{showroomId}}", "walkInBuyer": {"name": "Ada Obi", "phone": "08031234567", "email": "ada@example.com"},
         "paymentTermsDays": 0, "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 2, "unitPrice": 4100, "discountRate": 5}]},
   resp={"data": dict(INVOICE, id="{{walkInInvoiceId}}", number="INV-2", dueDate="2026-10-15", paymentTermsDays=0,
                      buyer={"customer": None, "code": None, "name": "Ada Obi", "type": None, "email": "ada@example.com", "phone": "08031234567", "tin": None,
                             "businessDescription": None, "address": None, "stateCode": None, "lgaCode": None, "countryCode": "NG"},
                      lines=[dict(INVOICE_LINE, quantity=2.000, discountAmount=410.00, discountRate=5.0000, lineNet=7790.00, vatAmount=584.25, lineTotal=8374.25)],
                      totals={"lineExtension": 7790.00, "discount": 410.00, "vat": 584.25, "total": 8374.25, "paid": 0.00, "credited": 0.00, "balance": 8374.25,
                              "pendingConfirmation": 0.00},
                      delivery={"status": "Not sent", "lastAttemptAt": None, "recipient": "ada@example.com"})},
   tests=SAVE_INVOICE("walkInInvoiceId"),
   desc="Exactly one of customerId or walkInBuyer. A buyer with a TIN makes the invoice B2B, otherwise B2C. "
        "discountRate (%) is turned into the line's discountAmount; a discount can't exceed the line value.")

ep(I, "B3 Save a Draft (sandbox pro-forma)", "POST", "/api/v1/invoicing/sandbox/invoices",
   body={"sourceType": "PRO_FORMA", "asDraft": True, "locationId": "{{showroomId}}", "customerId": "{{specialId}}",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 20, "unitPrice": 4000}]},
   resp={"data": dict(INVOICE, id="{{draftInvoiceId}}", number=None, status="DRAFT", displayStatus="Draft", issueDate=None, issueTime=None, dueDate=None,
                      issuedBy=None, issuedAt=None, linked=dict(INVOICE["linked"], sourceType="PRO_FORMA", sourceRef="SBX-3", proForma="SBX-3"))},
   tests=SAVE_INVOICE("draftInvoiceId") + ["pm.test('a draft has no number', () => pm.expect(json.data.number).to.eql(null));"],
   desc="A pro-forma converted to a draft. Drafts carry no number, so discarding one never leaves a gap in the INV sequence.")

ep(I, "B4 Issue (error: not a showroom)", "POST", "/api/v1/invoicing/sandbox/invoices",
   body={"locationId": "{{factoryId}}", "customerId": "{{dealerId}}", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1, "unitPrice": 4100}]},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "INVALID_LOCATION", "message": "Invoices are issued by a showroom", "details": {}}},
   expect_error={"status": 400, "code": "INVALID_LOCATION"},
   desc="Invoices are issued by a showroom that is trading: a factory or storage area gives 400 INVALID_LOCATION, a showroom pending opening "
        "or suspended 409 LOCATION_NOT_TRADING / LOCATION_SUSPENDED.")

ep(I, "B5 Issue (error: discount above line value)", "POST", "/api/v1/invoicing/sandbox/invoices",
   body={"locationId": "{{showroomId}}", "customerId": "{{dealerId}}",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1, "unitPrice": 4100, "discountAmount": 5000}]},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "DISCOUNT_EXCEEDS_LINE", "message": "A line discount can't exceed the line value", "details": {}}},
   expect_error={"status": 400, "code": "DISCOUNT_EXCEEDS_LINE"})

ep(I, "C1 Invoice History", "GET", "/api/v1/invoicing/invoices", query=[{"key": "facets", "value": "status,sourceType"}],
   resp=PAGED([INVOICE_ROW], INVOICE_SUMMARY),
   tests=["const json = pm.response.json();", "pm.test('has the summary header', () => pm.expect(json.data.summary).to.have.property('invoicesOnFile'));"],
   desc="Perm invoicing.invoice.read; location-scoped (a single-location user never sees another showroom's invoices). status takes DRAFT, ISSUED, "
        "PARTIALLY_PAID, PAID, OVERDUE, VOID. The derived ones are computed in SQL from payments, credit notes, the due date and invoicing.paidTolerance, "
        "so they filter, facet and sort like stored columns. Other filters: customerId, customerType, locationId, sourceType, invoiceKind, issueDate/dueDate "
        "(from, to, period), total, paid, balance, daysOverdue, issuedBy, correspondentId, vatEnabled, hasWaybills, hasReplacement. "
        "q matches the number (a bare 4031 finds INV-4031), the source/pro-forma reference, buyer name, phone, TIN or e-mail. "
        "Sort issuedAt (default, newest first), number, total, balance, dueDate, daysOverdue, buyerName.")

ep(I, "C2 Invoice History: Issued & Unpaid", "GET", "/api/v1/invoicing/invoices",
   query=[{"key": "status", "value": "ISSUED"}, {"key": "customerId", "value": "{{dealerId}}"}], resp=PAGED([INVOICE_ROW], INVOICE_SUMMARY))

ep(I, "C3 Search Invoices (advanced body)", "POST", "/api/v1/invoicing/invoices/search",
   body={"q": "{{invoiceNumber}}", "filter": {"all": [{"field": "total", "op": "gte", "value": 1000}, {"field": "status", "op": "in", "value": ["ISSUED", "OVERDUE"]}]},
         "sort": [{"field": "total", "dir": "desc"}], "summary": True},
   resp=PAGED([INVOICE_ROW], INVOICE_SUMMARY),
   tests=["const json = pm.response.json();", "pm.test('finds the invoice by number', () => pm.expect(json.data.items[0].id).to.eql(pm.collectionVariables.get('invoiceId')));"])

ep(I, "C4 Invoice Filters", "GET", "/api/v1/invoicing/invoices/filters",
   resp={"data": {"fields": [{"key": "status", "label": "Status", "type": "ENUM",
                              "options": [{"value": "DRAFT", "label": "Draft"}, {"value": "ISSUED", "label": "Issued"}, {"value": "PARTIALLY_PAID", "label": "Partially Paid"},
                                          {"value": "PAID", "label": "Paid"}, {"value": "OVERDUE", "label": "Overdue"}, {"value": "VOID", "label": "Void"}]}]}})

ep(I, "C5 Get Invoice", "GET", "/api/v1/invoicing/invoices/{{invoiceId}}", resp={"data": INVOICE},
   desc="Lines with their VAT treatment and NRS classification, totals, the issuer and buyer as snapshotted at issue, linked documents (source, "
        "pro-forma, waybills; payments and credit notes arrive with slice 2) and the reserved NRS identifiers (always null this phase).")

ep(I, "C6 Invoice PDF", "GET", "/api/v1/invoicing/invoices/{{invoiceId}}/invoice.pdf", pdf=True,
   desc="A4 Tax Invoice rendered from the same data as C5. Drafts and voided invoices are stamped; reprints through the document registry say DUPLICATE COPY. "
        "Footer: not transmitted to the NRS/FIRS Merchant-Buyer Solution this phase.")

ep(I, "C7 Invoice Picker (open only)", "GET", "/api/v1/invoicing/invoices/lookup",
   query=[{"key": "customerId", "value": "{{dealerId}}"}, {"key": "openOnly", "value": "true"}],
   resp={"data": [{"id": "{{invoiceId}}", "number": "INV-1", "customer": REF("customer", "{{dealerId}}"), "customer": "Okafor & Sons Distribution", "issued": "2026-10-15",
                   "total": 52890.00, "balance": 52890.00, "displayStatus": "Issued", "label": "INV-1 · Okafor & Sons Distribution · ₦52,890.00"}]})

ep(I, "C8 Invoice for a Sale or Pro-forma Reference", "GET", "/api/v1/invoicing/invoices/lookup-source",
   query=[{"key": "ref", "value": "{{invoiceSourceRef}}"}], resp={"data": INVOICE_ROW},
   desc="Resolves a TXN-/PF- reference to its invoice: the live one, else the most recent voided one.")

ep(I, "D1 Edit Draft Header", "PUT", "/api/v1/invoicing/invoices/{{draftInvoiceId}}",
   body={"paymentTermsDays": 14, "paymentTermsNote": "Net 14 days", "buyerEmail": "accounts@gracefoods.ng"},
   resp={"data": dict(INVOICE, id="{{draftInvoiceId}}", number=None, status="DRAFT", displayStatus="Draft", paymentTermsDays=14, paymentTermsNote="Net 14 days")},
   desc="Perm invoicing.invoice.issue. Header only: planned issue date, terms, due date, note, buyer e-mail. Lines come from the source (change the pro-forma).")

ep(I, "D2 Issue Draft", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/issue", body={},
   resp={"data": dict(INVOICE, id="{{draftInvoiceId}}", number="INV-3", paymentTermsDays=14, paymentTermsNote="Net 14 days", dueDate="2026-10-29")},
   tests=["const json = pm.response.json();", "pm.test('issued with a number', () => pm.expect(json.data.number).to.match(/^INV-\\d+$/));"],
   desc="Re-validates as at issue (customer still active, showroom trading, items active), recomputes VAT at the issue date's rate with prices kept, "
        "assigns the next number. Body optional: issueDate (default today; not in the future or before the source date), paymentTermsDays, dueDate, buyerEmail.")

ep(I, "D3 Issue Draft (error: already issued)", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/issue", body={},
   status=409, status_text="Conflict",
   resp={"error": {"code": "INVOICE_NOT_DRAFT", "message": "This invoice is already issued; only a draft can be changed or issued", "details": {}}},
   expect_error={"status": 409, "code": "INVOICE_NOT_DRAFT"})

ep(I, "D4 Save Another Draft", "POST", "/api/v1/invoicing/sandbox/invoices",
   body={"sourceType": "PRO_FORMA", "asDraft": True, "locationId": "{{showroomId}}", "customerId": "{{specialId}}",
         "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 5, "unitPrice": 4000}]},
   resp={"data": dict(INVOICE, id="{{discardDraftId}}", number=None, status="DRAFT", displayStatus="Draft")},
   tests=SAVE_INVOICE("discardDraftId"))

ep(I, "D5 Discard Draft", "DELETE", "/api/v1/invoicing/invoices/{{discardDraftId}}",
   resp={"data": {"id": "{{discardDraftId}}", "deleted": True}},
   desc="Perm invoicing.invoice.issue. The only invoice delete: a draft has no number. Emits invoicing.invoice.discarded, which Sales & POS will use to "
        "cancel the pro-forma and release its reservation.")

ep(I, "D6 Get Discarded Draft (error: gone)", "GET", "/api/v1/invoicing/invoices/{{discardDraftId}}",
   status=404, status_text="Not Found", resp={"error": {"code": "INVOICE_NOT_FOUND", "message": "Invoice not found", "details": {}}},
   expect_error={"status": 404, "code": "INVOICE_NOT_FOUND"})

ep(I, "E1 Void (error: reason too short)", "POST", "/api/v1/invoicing/invoices/{{walkInInvoiceId}}/void", body={"reason": "Wrong"},
   status=400, status_text="Bad Request",
   resp={"error": {"code": "VALIDATION_FAILED", "message": "Request validation failed", "details": {"reason": "explain the void in at least 10 characters"}}},
   expect_error={"status": 400, "code": "VALIDATION_FAILED"})

ep(I, "E2 Void Invoice", "POST", "/api/v1/invoicing/invoices/{{walkInInvoiceId}}/void", body={"reason": "Raised against the wrong sale"},
   resp={"data": dict(INVOICE, id="{{walkInInvoiceId}}", status="VOID", displayStatus="Void", voidReason="Raised against the wrong sale",
                      voidedBy="Alice Admin", voidedAt=NOW)},
   tests=["const json = pm.response.json();", "pm.test('voided', () => pm.expect(json.data.status).to.eql('VOID'));"],
   desc="Perm invoicing.invoice.void. A reason of 10+ characters. Only while nothing is paid or credited (409 INVOICE_HAS_PAYMENTS: refund or credit first, "
        "or void and reissue). The invoice is kept, shown as Void with its reason; the registry document is marked voided. Voiding an invoice doesn't reverse "
        "the sale or its stock (a sale void in Sales & POS does, and voids the invoice through the spi).")

ep(I, "E3 Void Again (error: not issued)", "POST", "/api/v1/invoicing/invoices/{{walkInInvoiceId}}/void", body={"reason": "Raised against the wrong sale"},
   status=409, status_text="Conflict", resp={"error": {"code": "INVOICE_NOT_ISSUED", "message": "This invoice is not issued", "details": {}}},
   expect_error={"status": 409, "code": "INVOICE_NOT_ISSUED"})

ep(I, "E4 Void and Reissue to Another Customer", "POST", "/api/v1/invoicing/invoices/{{invoiceId}}/void",
   body={"reason": "Billed to the wrong customer at the till", "reissue": {"customerId": "{{specialId}}"}},
   resp={"data": dict(INVOICE, id="{{reissuedInvoiceId}}", number="INV-4",
                      linked=dict(INVOICE["linked"], reissueOf={"id": "{{invoiceId}}", "number": "INV-1"}))},
   tests=SAVE_INVOICE("reissuedInvoiceId") + [
       "pm.test('replacement links back to the voided invoice', () => pm.expect(json.data.linked.reissueOf.id).to.eql(pm.collectionVariables.get('invoiceId')));"],
   desc="Voids the invoice and issues a replacement for the same sale with a new number; both point at each other (reissueOf / replacedBy). "
        "reissue takes customerId or walkInBuyer, and buyerEmail. Corrected quantities or prices come from Sales & POS through the spi. "
        "Returns the replacement.")

ep(I, "E5 Voided Invoices", "GET", "/api/v1/invoicing/invoices/voided",
   resp=PAGED([{"id": "{{invoiceId}}", "number": "INV-1", "voidReason": "Billed to the wrong customer at the till", "customer": "Okafor & Sons Distribution",
                "issued": "2026-10-15", "total": 52890.00, "voidedBy": USER, "voidedAt": NOW, "replacedBy": REF("invoice", "{{reissuedInvoiceId}}")}],
              {"count": 2, "value": 61264.25}))

ep(I, "E6 Global Search: Invoice", "GET", "/api/v1/search", query=[{"key": "q", "value": "{{invoiceNumber}}"}, {"key": "types", "value": "invoice"}],
   resp={"data": {"q": "INV-1", "groups": [{"type": "invoice", "label": "Invoices", "hits": [
       {"type": "invoice", "id": "{{invoiceId}}", "title": "INV-1", "subtitle": "Okafor & Sons Distribution · ₦52,890.00 · Void", "ref": "INV-1",
        "route": "/invoicing/invoices/{{invoiceId}}"}], "more": False, "moreRoute": "/invoicing/invoices?q=INV-1"}]}},
   desc="Global search provider invoice (perm invoicing.invoice.read).")

PAYMENT_VIEW = {"id": "{{cashPaymentId}}", "date": NOW, "method": "CASH", "methodLabel": "Cash", "amount": 20000.00, "receipt": REF("receipt", "6c1f2d40-8a8e-4f7a-9d0e-0b1c2d3e4f50"),
                "status": "Confirmed", "source": "RECORDED", "reversalReason": None}
CREDIT_NOTE = {"id": "{{creditNoteId}}", "number": "CN-1", "invoiceTypeCode": "380", "invoice": {"id": "{{draftInvoiceId}}", "number": "INV-3"},
               "invoiceIssueDate": "2026-10-15", "customer": REF("customer", "{{specialId}}"), "customer": "Grace Foods", "showroom": {"id": "{{showroomId}}", "name": "Showroom Ikeja"},
               "reason": "PRICING_ERROR", "reasonNote": "Agreed price was 3,800 a carton",
               "lines": [{"id": "0d7e3b8a-2c1f-4e5a-9b6c-7d8e9f0a1b2c", "invoiceLineId": "{{creditLineId}}", "lineNo": 1, "code": "ITM-2", "name": "Citrus Drink 50cl",
                          "quantity": None, "amount": 4000.00, "unitPrice": 4000.00, "vatRate": 7.50, "lineNet": 4000.00, "vatAmount": 300.00, "lineTotal": 4300.00}],
               "lineExtension": 4000.00, "vat": 300.00, "total": 4300.00, "appliedToInvoice": 4300.00, "excessRefunded": 0.00, "issuedBy": USER, "issuedAt": NOW}
DELIVERY_VIEW = {"id": "5b2c9d1e-3f4a-4b5c-8d6e-7f8091a2b3c4", "invoice": REF("invoice", "{{draftInvoiceId}}"), "recipient": "accounts@gracefoods.ng",
                 "template": "STANDARD", "trigger": "MANUAL", "status": "Queued", "requestedAt": NOW, "sentAt": None, "requestedBy": USER}

ep(I, "F1 Record Cash Payment", "POST", "/api/v1/invoicing/invoices/{{reissuedInvoiceId}}/payments",
   body={"method": "CASH", "amount": 20000, "cashAccountId": "{{tillAccountId}}"},
   resp={"data": {"paymentId": "{{cashPaymentId}}", "receipt": REF("receipt", "6c1f2d40-8a8e-4f7a-9d0e-0b1c2d3e4f50"), "confirmationStatus": "NOT_REQUIRED",
                  "invoice": dict(INVOICE, id="{{reissuedInvoiceId}}", displayStatus="Partially Paid")}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('cashPaymentId', json.data.paymentId);",
          "pm.test('partially paid', () => pm.expect(json.data.invoice.displayStatus).to.eql('Partially Paid'));"],
   desc="Perm invoicing.payment.record. Every payment is an Account receipt (RCT-n, source INVOICE_PAYMENT): CASH needs the till or vault taking it "
        "(cashAccountId) and posts at once; POS, FUND_TRANSFER and CHEQUE wait for Accounts to confirm (receivingBankAccountId for transfers and cheques); "
        "WALLET and DEPOSIT debit the customer's balance first (never below zero). Never more than the balance (400 OVERPAYMENT); an invoice already "
        "paid gives 409 INVOICE_ALREADY_PAID. Returns the receipt and the updated invoice.")

ep(I, "F2 Record Transfer Payment (awaits confirmation)", "POST", "/api/v1/invoicing/invoices/{{reissuedInvoiceId}}/payments",
   body={"method": "FUND_TRANSFER", "amount": 10000, "receivingBankAccountId": "{{bankAccountId}}", "payerReference": "GTB/77310"},
   resp={"data": {"paymentId": "8e9f0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b", "receipt": REF("receipt", "{{transferReceiptId}}"), "confirmationStatus": "PENDING",
                  "invoice": dict(INVOICE, id="{{reissuedInvoiceId}}", displayStatus="Partially Paid")}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('transferReceiptId', json.data.receipt.id);",
          "pm.test('awaits confirmation', () => pm.expect(json.data.confirmationStatus).to.eql('PENDING'));"],
   desc="Counted as paid at once and flagged in totals.pendingConfirmation until Accounts confirms it in the receipt queue.")

ep(I, "F3 Record Payment (error: more than the balance)", "POST", "/api/v1/invoicing/invoices/{{reissuedInvoiceId}}/payments",
   body={"method": "CASH", "amount": 9999999, "cashAccountId": "{{tillAccountId}}"}, status=400, status_text="Bad Request",
   resp={"error": {"code": "OVERPAYMENT", "message": "The payment is more than the balance of 22890.00", "details": {"balance": "22890.00"}}},
   expect_error={"status": 400, "code": "OVERPAYMENT"})

ep(I, "F4 Invoice Payments", "GET", "/api/v1/invoicing/invoices/{{reissuedInvoiceId}}/payments",
   resp={"data": [PAYMENT_VIEW, dict(PAYMENT_VIEW, id="8e9f0a1b-2c3d-4e5f-8a9b-0c1d2e3f4a5b", method="FUND_TRANSFER", methodLabel="Fund transfer", amount=10000.00,
                                     receiptId="{{transferReceiptId}}", receipt="RCT-5", status="Pending confirmation")]},
   desc="Status per payment: Confirmed, Pending confirmation, Declined or Voided (mirrored from the Account receipt).")

ep(I, "F5 Decline the Transfer (Account)", "POST", "/api/v1/account/receipts/{{transferReceiptId}}/decline", body={"note": "No credit on the bank statement"},
   resp={"data": {"id": "{{transferReceiptId}}", "ref": "RCT-5", "confirmationStatus": "DECLINED"}},
   desc="Perm accounting.receipts.confirm. Invoicing's receipt reconciler picks this up within one consumer cycle (about 15 s): the payment shows Declined, "
        "the balance comes back, and holders of invoicing.receivables.read at the showroom are alerted.")

ep(I, "F6 Reverse a Payment", "POST", "/api/v1/invoicing/invoices/{{reissuedInvoiceId}}/payments/{{cashPaymentId}}/reverse",
   body={"reason": "Recorded against the wrong invoice"},
   resp={"data": dict(INVOICE, id="{{reissuedInvoiceId}}")},
   desc="Perm invoicing.invoice.void. For a payment recorded here by mistake: Account voids its receipt (the till entry is undone) and a wallet/deposit "
        "debit is credited back. Payments taken at a POS checkout are reversed by voiding the sale (409 REVERSE_IN_SOURCE_MODULE).")

ep(I, "G1 Get Invoice Lines (for a credit note)", "GET", "/api/v1/invoicing/invoices/{{draftInvoiceId}}", resp={"data": dict(INVOICE, id="{{draftInvoiceId}}")},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('creditLineId', json.data.lines[0].id);"])

ep(I, "G2 Issue Credit Note (value)", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/credit-notes",
   body={"reason": "PRICING_ERROR", "reasonNote": "Agreed price was 3,800 a carton", "lines": [{"invoiceLineId": "{{creditLineId}}", "amount": 4000}]},
   resp={"data": CREDIT_NOTE},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('creditNoteId', json.data.id);",
          "pm.test('VAT follows the line', () => pm.expect(json.data.vat).to.eql(300));"],
   desc="Perm invoicing.credit-note.issue; the invoice must be ISSUED. Per line give quantity (at the line's own price) or amount (a net value). VAT reverses "
        "the original line's rate. Cumulative credits never exceed a line (400 CREDIT_EXCEEDS_LINE). The part within the balance reduces it; anything "
        "beyond a balance already paid goes to the customer's wallet (CREDIT_NOTE entry), or 409 CREDIT_EXCEEDS_BALANCE for a walk-in buyer or when "
        "invoicing.creditNoteExcessTo is BLOCK. Credit notes never move stock: physical returns go through Showroom Returns.")

ep(I, "G3 Issue Credit Note (quantity)", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/credit-notes",
   body={"reason": "OVERCHARGE", "reasonNote": "Two cartons short on delivery", "lines": [{"invoiceLineId": "{{creditLineId}}", "quantity": 2}]},
   resp={"data": dict(CREDIT_NOTE, id="3f4a5b6c-7d8e-4f9a-8b0c-1d2e3f4a5b6c", number="CN-2", reason="OVERCHARGE", reasonNote="Two cartons short on delivery",
                      lineExtension=8000.00, vat=600.00, total=8600.00, appliedToInvoice=8600.00)})

ep(I, "G4 Issue Credit Note (error: more than the line)", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/credit-notes",
   body={"reason": "OTHER", "reasonNote": "Too many", "lines": [{"invoiceLineId": "{{creditLineId}}", "quantity": 50}]}, status=400, status_text="Bad Request",
   resp={"error": {"code": "CREDIT_EXCEEDS_LINE", "message": "Line 1 can't be credited for more than was invoiced on it", "details": {"lineNo": "1"}}},
   expect_error={"status": 400, "code": "CREDIT_EXCEEDS_LINE"})

ep(I, "G5 List Credit Notes", "GET", "/api/v1/invoicing/credit-notes", query=[{"key": "invoiceId", "value": "{{draftInvoiceId}}"}, {"key": "facets", "value": "reason"}],
   resp=PAGED([{"id": "{{creditNoteId}}", "number": "CN-1", "invoice": REF("invoice", "{{draftInvoiceId}}"), "customer": REF("customer", "{{specialId}}"),
                "customer": "Grace Foods", "showroom": "Showroom Ikeja", "reason": "PRICING_ERROR", "reasonNote": "Agreed price was 3,800 a carton",
                "total": 4300.00, "appliedToInvoice": 4300.00, "excessRefunded": 0.00, "issuedBy": USER, "issuedAt": NOW}],
              {"count": 2, "total": 12900.00, "excessToWallets": 0.00}),
   desc="Perm invoicing.invoice.read. Filters invoiceId, customerId, reason, locationId, issuedBy, total, from/to, hasExcessRefunded; q matches the CN or "
        "invoice number, customer and reason note.")

ep(I, "G6 Get Credit Note", "GET", "/api/v1/invoicing/credit-notes/{{creditNoteId}}", resp={"data": CREDIT_NOTE})

ep(I, "G7 Credit Note PDF", "GET", "/api/v1/invoicing/credit-notes/{{creditNoteId}}/credit-note.pdf", pdf=True)

ep(I, "H1 E-mail an Invoice", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/deliveries", body={"template": "STANDARD", "message": "Thank you for your order."},
   resp={"data": DELIVERY_VIEW},
   desc="Perm invoicing.delivery.manage. Queues the e-mail in the notification outbox; the PDF is rendered when it is sent, so a retry never "
        "regenerates or renumbers the invoice. recipientEmail defaults to the buyer's (a different one is a REDIRECT; resending after a failure is a RETRY). "
        "template STANDARD, REMINDER or STATEMENT (the customer's statement of open invoices and ageing). No address at all: 400 NO_EMAIL_ON_FILE. "
        "invoicing.autoEmailOnIssue (on by default) sends one automatically at issue. The shipped e-mail adapter only logs until a provider is configured.")

ep(I, "H2 E-mail the Customer's Statement", "POST", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/deliveries", body={"template": "STATEMENT"},
   resp={"data": dict(DELIVERY_VIEW, template="STATEMENT")})

ep(I, "H3 E-mail (error: void invoice)", "POST", "/api/v1/invoicing/invoices/{{walkInInvoiceId}}/deliveries", body={},
   status=409, status_text="Conflict", resp={"error": {"code": "INVOICE_NOT_ISSUED", "message": "This invoice is not issued", "details": {}}},
   expect_error={"status": 409, "code": "INVOICE_NOT_ISSUED"},
   desc="A void invoice can still be downloaded, but never e-mailed.")

ep(I, "H4 Invoice E-mail History", "GET", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/deliveries", resp={"data": [DELIVERY_VIEW]})

ep(I, "H5 Deliveries", "GET", "/api/v1/invoicing/deliveries", query=[{"key": "facets", "value": "delivery"}],
   resp=PAGED([{"invoice": REF("invoice", "{{draftInvoiceId}}"), "number": "INV-3", "issued": "2026-10-15", "customer": "Grace Foods", "email": "accounts@gracefoods.ng",
                "showroom": "Showroom Ikeja", "total": 86000.00, "delivery": "Not sent", "deliveryCode": "NOT_SENT", "lastAttemptAt": NOW, "attempts": 2}],
              {"issued": 3, "delivered": 0, "deliveredPct": 0.0, "failed": 0, "noEmailCount": 1, "noEmailValue": 52890.00}),
   desc="Issued invoices with their e-mail state: delivery SENT, FAILED, NO_EMAIL or NOT_SENT (queued or never sent). The same delivery filter works on the history list.")

ep(I, "H6 Bulk Export (ZIP of PDFs)", "GET", "/api/v1/invoicing/invoices/export.zip", query=[{"key": "status", "value": "ISSUED,PARTIALLY_PAID,PAID,OVERDUE"}],
   binary="application/zip",
   desc="Perm invoicing.delivery.manage. Same filters and the same location scope as the list (a single-location user only gets their showroom's invoices). "
        "More than invoicing.bulkExportMaxInvoices (200) gives 400 EXPORT_TOO_LARGE: narrow the range.")

ep(I, "I1 Outstanding Receivables", "GET", "/api/v1/invoicing/receivables", query=[{"key": "facets", "value": "ageingBucket"}],
   resp=PAGED([{"id": "{{draftInvoiceId}}", "number": "INV-3", "issued": "2026-10-15", "due": "2026-10-29", "customer": REF("customer", "{{specialId}}"), "customer": "Grace Foods",
                "customerType": "SPECIAL_CUSTOMER", "showroom": "Showroom Ikeja", "total": 86000.00, "paid": 0.00, "credited": 12900.00, "balance": 73100.00,
                "status": "Partially Paid", "daysOverdue": 0, "ageingBucket": "NOT_DUE"}],
              {"outstandingTotal": 73100.00, "outstandingCount": 1, "overdueTotal": 0.00, "overdueCount": 0, "partiallyPaidTotal": 73100.00, "partiallyPaidCount": 1,
               "voidedCount": 2, "outstandingByCustomer": [{"customer": REF("customer", "{{specialId}}"), "customer": "Grace Foods", "invoiceCount": 1, "balance": 73100.00}]}),
   desc="Perm invoicing.receivables.read. Issued invoices with more than invoicing.paidTolerance left to pay, most overdue first. ageingBucket NOT_DUE, "
        "WITHIN_30, DAYS_31_60, OVER_60 (days past due); overdueOnly=true; plus every invoice filter. The header is computed from the same rows, so it "
        "reconciles exactly.")

ep(I, "I2 Overdue Receivables Only", "GET", "/api/v1/invoicing/receivables", query=[{"key": "overdueOnly", "value": "true"}],
   resp=PAGED([], {"outstandingTotal": 0.00, "outstandingCount": 0, "overdueTotal": 0.00, "overdueCount": 0, "partiallyPaidTotal": 0.00,
                   "partiallyPaidCount": 0, "voidedCount": 2, "outstandingByCustomer": []}))

ep(I, "I3 Debtors List", "GET", "/api/v1/invoicing/debtors",
   resp=PAGED([{"customer": REF("customer", "{{specialId}}"), "phone": None, "location": REF("location", "{{customerLocationId}}"),
                "correspondent": None, "openInvoices": 1, "outstanding": 73100.00, "creditLimit": 0.00, "overLimit": True,
                "oldestInvoiceDays": 0, "ageing": "WITHIN_30", "ageingLabel": "Within 30 days", "lastPaymentAt": None}],
              {"debtors": 1, "outstandingTotal": 73100.00, "overLimitCount": 1}),
   desc="Perm invoicing.receivables.read. One row per customer with an open balance; ageing on the oldest open invoice's issue date. Filters locationId "
        "(the customer's home showroom), customerType, ageing, correspondentId, overLimitOnly, outstanding.gte/lte, q (code, name, phone); sort outstanding "
        "(default), oldestInvoiceDays, name, lastPaymentAt.")

ep(I, "I4 Debtor Account", "GET", "/api/v1/invoicing/debtors/{{specialId}}", query=[{"key": "openOnly", "value": "false"}, {"key": "page", "value": "0"}, {"key": "size", "value": "50"}],
   resp={"data": {"customer": {"customer": REF("customer", "{{specialId}}"), "phone": None,
                               "location": REF("location", "{{customerLocationId}}"), "correspondent": None, "openInvoices": 1,
                               "outstanding": 73100.00, "creditLimit": 0.00, "overLimit": True, "oldestInvoiceDays": 0, "ageing": "WITHIN_30",
                               "ageingLabel": "Within 30 days", "lastPaymentAt": None},
                  "account": {"openInvoices": 1, "lastPaymentAt": None, "totalInvoiced": 86000.00, "totalPaid": 0.00, "totalCredited": 12900.00,
                              "outstanding": 73100.00, "creditLimit": 0.00, "overLimit": True},
                  "transactions": PAGED([{"invoice": REF("invoice", "{{draftInvoiceId}}"), "date": "2026-10-15", "description": "Invoice from pro-forma SBX-3",
                                          "value": 86000.00, "paid": 12900.00, "balance": 73100.00, "dueDate": "2026-10-29", "status": "Partially Paid"}])["data"],
                  "warning": "Further credit sales should be withheld until the balance is brought within limit."}},
   desc="The customer's account and their invoice history, newest first and paged (from, to, openOnly, page, size). warning is set when they are over their "
        "credit limit; Sales & POS enforces it at the till.")

ep(I, "J1 NRS Preview", "GET", "/api/v1/invoicing/invoices/{{draftInvoiceId}}/nrs-preview",
   resp={"data": {"ready": False, "missing": ["issuer.nrsBusinessId", "lines[1].hsn_code (fallback used)", "lines[1].price_unit"],
                  "invoice": {"business_id": None, "irn": None, "issue_date": "2026-10-15", "invoice_type_code": "381", "invoice_kind": "B2C",
                              "payment_status": "PENDING", "document_currency_code": "NGN"}}},
   tests=["const json = pm.response.json();", "pm.test('has the NRS invoice body', () => pm.expect(json.data.invoice.invoice_type_code).to.eql('381'));"],
   desc="Perm invoicing.invoice.read. The NRS Invoice Schema JSON this invoice would be validated as, built from the snapshot taken at issue, and what is "
        "missing for a real submission (issuer identifiers, granular HS/ISIC codes where the section was used, NRS unit codes). Nothing is transmitted this phase.")

# =============================================================================================
# 19 - Sales & POS (docs/09, slice 1): till sessions, the cart, checkout, void, receipt.
# Runs on folder 17's till (TILL-IKJ-01 at the showroom) and folder 15's priced product, which
# folder 16 moved onto the showroom's shop floor (its default selling area).
# =============================================================================================
P = "19 - Sales & POS"

TILL_SESSION = {"id": "{{tillSessionId}}", "till": REF("cashAccount", "{{tillAccountId}}"), "location": REF("location", "{{showroomId}}"), "cashier": USER,
                "status": "OPEN", "openedAt": NOW, "openingFloat": 20000.00, "closedAt": None, "closedBy": None, "countedCash": None,
                "expectedCash": None, "variance": None, "closeNote": None, "forceClosed": False,
                "running": {"salesCount": 0, "cashTaken": 0.00, "cardTaken": 0.00, "transferTaken": 0.00, "walletTaken": 0.00, "depositTaken": 0.00,
                            "onAccount": 0.00, "expectedCashNow": 20000.00}}
SALE_LINE = {"lineNo": 1, "stockItem": REF("stockItem", "{{finishedProductId}}"), "code": "ITM-2", "name": "Premium Blend", "unit": "Kilogram",
             "category": "Beverages", "quantity": 1.000, "unitPrice": 5000.00, "priceBasis": "RETAIL", "lineDiscount": 0.00, "instantDiscountShare": 0.00,
             "lineNet": 5000.00, "vatRate": 7.50, "vatAmount": 375.00, "lineTotal": 5375.00, "available": 2.000, "insufficient": False}
SALE = {"id": "{{saleId}}", "ref": None, "status": "OPEN", "version": 1, "channel": "POS", "till": REF("cashAccount", "{{tillAccountId}}"),
        "location": REF("location", "{{showroomId}}"), "cashier": USER, "customer": None, "customerName": "Walk-in customer", "priceBasis": "RETAIL",
        "lines": [SALE_LINE],
        "totals": {"gross": 5000.00, "lineDiscounts": 0.00, "instantDiscount": 0.00, "net": 5000.00, "vatEnabled": True, "vatRate": 7.50, "vat": 375.00, "total": 5375.00},
        "payments": [], "paidNow": 0.00, "onAccount": 0.00, "cashTendered": None, "changeGiven": None, "allowedMethods": ["CASH", "POS", "FUND_TRANSFER"],
        "invoice": None, "invoiceNumber": None, "correspondent": None, "saleDate": None, "startedAt": NOW, "completedAt": None,
        "creditOverrideReason": None, "creditOverrideBy": None, "voidedAt": None, "voidedBy": None, "voidReason": None, "voidableUntil": None}
SAVE_SALE = ["const json = pm.response.json();", "pm.collectionVariables.set('saleVersion', json.data.version);"]

ep(P, "A1 Open Till", "POST", "/api/v1/cashier/till-sessions", body={"cashAccountId": "{{tillAccountId}}", "openingFloat": 20000},
   resp={"data": TILL_SESSION},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('tillSessionId', json.data.id);",
          "pm.test('open with the float', () => { pm.expect(json.data.status).to.eql('OPEN'); pm.expect(json.data.openingFloat).to.eql(20000); });"],
   desc="Perm cashier.sales.manage. The till is a TILL cash account at a showroom the caller may work in. One open session per till and per cashier: "
        "409 TILL_ALREADY_OPEN / CASHIER_SESSION_OPEN.")

ep(P, "A2 Open Till (error: already open)", "POST", "/api/v1/cashier/till-sessions", body={"cashAccountId": "{{tillAccountId}}", "openingFloat": 0},
   resp={"error": {"code": "TILL_ALREADY_OPEN", "message": "This till is already open (by Alice Admin)", "details": {"openedBy": "Alice Admin", "openedAt": NOW}}},
   expect_error={"status": 409, "code": "TILL_ALREADY_OPEN"})

ep(P, "A3 Current Till Session", "GET", "/api/v1/cashier/till-sessions/current", resp={"data": TILL_SESSION},
   desc="The caller's open session with what it has taken so far by method; expectedCashNow = float + everything Account posted to the till since it opened. "
        "404 NO_OPEN_TILL_SESSION when none.")

ep(P, "B1 Till Catalogue", "GET", "/api/v1/cashier/catalogue", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [{"stockItem": REF("stockItem", "{{finishedProductId}}"), "code": "ITM-2", "barcode": None, "name": "Premium Blend",
                             "category": "Beverages", "unit": "Kilogram", "price": 5000.00, "retailPrice": 5000.00, "dealerPrice": 4500.00,
                             "available": 2.000, "lowStock": False}],
                  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1}},
   tests=["const json = pm.response.json();", "pm.test('the priced product is sellable', () => pm.expect(json.data.items.map(i => i.stockItem.id)).to.include(pm.collectionVariables.get('finishedProductId')));"],
   desc="Perm cashier.sales.manage. Defaults to the caller's open till's showroom (or locationId). q: exact code/barcode first, then code prefix, then name "
        "(typo-tolerant); categoryId; customerId prices for that customer (dealer price for an authorised dealer); includeOutOfStock=true to show items with none.")

ep(P, "B2 Start a Sale", "POST", "/api/v1/cashier/sales", body={},
   resp={"data": dict(SALE, version=0, lines=[], totals=dict(SALE["totals"], gross=0.00, net=0.00, vat=0.00, total=0.00))},
   tests=SAVE_SALE + ["pm.collectionVariables.set('saleId', json.data.id);", "pm.test('an open, empty cart', () => pm.expect(json.data.status).to.eql('OPEN'));"],
   desc="Perm cashier.sales.manage. On the caller's open till (409 NO_OPEN_TILL_SESSION otherwise). Optional customerId; walk-in when none.")

ep(P, "B3 Set Lines", "PUT", "/api/v1/cashier/sales/{{saleId}}/lines",
   body={"version": "{{saleVersion}}", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1}]},
   resp={"data": SALE},
   tests=SAVE_SALE + ["pm.collectionVariables.set('saleTotal', json.data.totals.total);",
                      "pm.test('priced from the price list', () => pm.expect(json.data.lines[0].unitPrice).to.eql(5000));",
                      "pm.test('total = net + VAT', () => pm.expect(json.data.totals.total).to.eql(json.data.totals.net + json.data.totals.vat));"],
   desc="Full replace; lines for the same item merge. Prices come from the price list in force today (dealer price for an authorised dealer); the cashier "
        "never types one. More than the showroom has: the line is kept with insufficient=true (refused at completion). 400 ITEM_NOT_SELLABLE, 409 NO_PRICE_SET.")

ep(P, "B4 Set Lines (error: stale version)", "PUT", "/api/v1/cashier/sales/{{saleId}}/lines",
   body={"version": 0, "lines": []},
   resp={"error": {"code": "SALE_CHANGED", "message": "The sale changed since you last loaded it; reload it and try again", "details": {"version": "1"}}},
   expect_error={"status": 409, "code": "SALE_CHANGED"},
   desc="Every change carries the version the client saw; a mismatch returns the current version to reload.")

ep(P, "B5 Complete (error: payments don't settle)", "POST", "/api/v1/cashier/sales/{{saleId}}/complete",
   body={"version": "{{saleVersion}}", "payments": [{"method": "CASH", "amount": 100}]},
   resp={"error": {"code": "PAYMENT_NOT_SETTLED", "message": "Payments must add up to the total", "details": {"total": "5375.00", "allocated": "100.00", "remaining": "5275.00"}}},
   expect_error={"status": 400, "code": "PAYMENT_NOT_SETTLED"})

ep(P, "B6 Complete the Sale (cash)", "POST", "/api/v1/cashier/sales/{{saleId}}/complete",
   body={"version": "{{saleVersion}}", "payments": [{"method": "CASH", "amount": "{{saleTotal}}", "tendered": 10000}]},
   resp={"data": {"sale": dict(SALE, ref="TXN-1", status="COMPLETED", version=2, saleDate="2026-10-21", completedAt=NOW, paidNow=5375.00,
                               cashTendered=10000.00, changeGiven=4625.00, allowedMethods=[], voidableUntil="2026-10-22T10:15:30Z",
                               lines=[dict(SALE_LINE, available=None)],
                               payments=[{"method": "CASH", "methodLabel": "Cash", "amount": 5375.00, "receipt": REF("receipt", "2b3c4d5e-9999-4c4c-9a9a-000000000001"),
                                          "receiptRef": "RCT-9", "confirmationStatus": "NOT_REQUIRED", "terminalRef": None, "payerReference": None}]),
                  "invoice": None, "receiptUrl": "/api/v1/cashier/sales/{{saleId}}/receipt.pdf", "changeGiven": 4625.00}},
   tests=["const json = pm.response.json();",
          "pm.test('completed with a TXN number', () => { pm.expect(json.data.sale.status).to.eql('COMPLETED'); pm.expect(json.data.sale.ref).to.match(/^TXN-\\d+$/); });",
          "pm.test('change given', () => pm.expect(json.data.changeGiven).to.eql(10000 - json.data.sale.totals.total));"],
   desc="One transaction: prices re-checked (409 PRICES_CHANGED re-prices the cart for the cashier to confirm), stock checked under row locks (409 "
        "INSUFFICIENT_STOCK) and taken off the selling area (POS_SALE), credit checked for onAccount (409 CREDIT_LIMIT_EXCEEDED unless "
        "creditOverrideReason + cashier.credit.override), wallet/deposit debited, one Account receipt per payment (card/transfer pending confirmation), "
        "and an invoice for a registered customer (walk-in: invoiceBuyer). Payments + onAccount must equal the total. Nothing is written if anything fails.")

ep(P, "B7 Get Sale", "GET", "/api/v1/cashier/sales/{{saleId}}",
   resp={"data": dict(SALE, ref="TXN-1", status="COMPLETED", version=2)},
   tests=SAVE_SALE)

ep(P, "B8 List Sales", "GET", "/api/v1/cashier/sales", query=[{"key": "from", "value": "{{today}}"}, {"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [{"id": "{{saleId}}", "ref": "TXN-1", "status": "COMPLETED", "channel": "POS", "date": "2026-10-21", "completedAt": NOW,
                             "location": REF("location", "{{showroomId}}"), "till": REF("cashAccount", "{{tillAccountId}}"), "cashier": USER, "customer": None,
                             "customerName": "Walk-in customer", "customerType": None, "items": 1, "units": 1.000, "net": 5000.00, "vat": 375.00, "total": 5375.00,
                             "methods": ["Cash"], "onAccount": 0.00, "invoice": None, "invoiceNumber": None}],
                  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1}},
   tests=["const json = pm.response.json();", "pm.test('lists the sale', () => pm.expect(json.data.items.map(s => s.id)).to.include(pm.collectionVariables.get('saleId')));"],
   desc="Perm cashier.sales.read: own sales only; cashier.sales.read-all: every sale in location scope. status (default COMPLETED), channel, locationId, "
        "cashierUserId, customerId, from/to (sale date), ref (a TXN or INV number). Newest first.")

ep(P, "B9 Sales Receipt PDF", "GET", "/api/v1/cashier/sales/{{saleId}}/receipt.pdf", pdf=True,
   desc="Itemised receipt: lines, VAT, total, each payment with its RCT number (card/transfer marked pending confirmation), change. DUPLICATE COPY once a "
        "reprint is recorded (POST /api/v1/documents/{ref}/reprints).")

ep(P, "B10 Invoice a Walk-in Sale on Request", "POST", "/api/v1/cashier/sales/{{saleId}}/invoice",
   body={"buyer": {"name": "Mrs Adeyemi", "phone": "08031234567", "email": None, "tin": None, "address": "4 Allen Avenue, Ikeja", "stateCode": "NG-LA"}},
   resp={"data": dict(SALE, ref="TXN-1", status="COMPLETED", version=3, invoice=REF("invoice", "2b3c4d5e-9999-4c4c-9a9a-000000000002"), invoiceNumber="INV-9")},
   tests=SAVE_SALE + ["pm.test('invoiced', () => pm.expect(json.data.invoiceNumber).to.match(/^INV-\\d+$/));"],
   desc="Perm cashier.sales.manage or invoicing.invoice.issue. Walk-in sales only (a registered customer is invoiced at completion); the invoice carries the "
        "sale's own lines and receipts. 409 INVOICE_EXISTS_FOR_SOURCE when already invoiced.")

ep(P, "C1 Void the Sale", "POST", "/api/v1/cashier/sales/{{saleId}}/void", body={"reason": "Customer returned at the door before leaving"},
   resp={"data": dict(SALE, ref="TXN-1", status="VOIDED", version=4, voidedAt=NOW, voidedBy=USER, voidReason="Customer returned at the door before leaving")},
   tests=["const json = pm.response.json();", "pm.test('voided', () => pm.expect(json.data.status).to.eql('VOIDED'));"],
   desc="Perm cashier.sales.void, within cashier.sale.voidWindowHours (409 VOID_WINDOW_PASSED: take a return and issue a credit note instead). Stock goes "
        "back, receipts are voided (the cash leaves the till's books), wallet debits reversed, the invoice voided.")

ep(P, "C2 Void (error: already voided)", "POST", "/api/v1/cashier/sales/{{saleId}}/void", body={"reason": "Trying to void it a second time"},
   resp={"error": {"code": "INVALID_STATUS_TRANSITION", "message": "Cannot change status from VOIDED to VOIDED", "details": {"from": "VOIDED", "to": "VOIDED"}}},
   expect_error={"status": 409, "code": "INVALID_STATUS_TRANSITION"})

# ---- E. Deliver-later: a dealer on-account sale, partly reserved, released by waybill ----
ep(P, "E1 Start Sale (deliver-later)", "POST", "/api/v1/cashier/sales", body={"customerId": "{{dealerId}}"},
   resp={"data": dict(SALE, version=0, customer=None, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER",
                      lines=[], totals=dict(SALE["totals"], gross=0.00, net=0.00, vat=0.00, total=0.00),
                      allowedMethods=["CASH", "POS", "FUND_TRANSFER", "ON_ACCOUNT"])},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('saleId2', json.data.id);", "pm.collectionVariables.set('saleVersion2', json.data.version);"],
   desc="A sale to a registered dealer: dealer prices, and ON_ACCOUNT available within the credit limit.")

ep(P, "E2 Set Lines", "PUT", "/api/v1/cashier/sales/{{saleId2}}/lines",
   body={"version": "{{saleVersion2}}", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1}]},
   resp={"data": dict(SALE, version=1, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER",
                      lines=[dict(SALE_LINE, priceBasis="DEALER", unitPrice=4500.00, supplied=0.000, outstanding=1.000)])},
   tests=SAVE_SALE + ["pm.collectionVariables.set('saleVersion2', json.data.version);", "pm.collectionVariables.set('saleTotal2', json.data.totals.total);"],
   desc="Full replace; priced at the dealer price.")

ep(P, "E3 Complete on Account, Deliver Later", "POST", "/api/v1/cashier/sales/{{saleId2}}/complete",
   body={"version": "{{saleVersion2}}", "payments": [], "onAccount": "{{saleTotal2}}", "supply": [{"lineNo": 1, "supplyNow": 0}]},
   resp={"data": {"sale": dict(SALE, ref="TXN-2", status="COMPLETED", version=2, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER",
                               supplyStatus="NOT_SUPPLIED", onAccount=4837.50, paidNow=0.00, allowedMethods=[],
                               lines=[dict(SALE_LINE, priceBasis="DEALER", unitPrice=4500.00, available=None, supplied=0.000, outstanding=1.000)]),
                  "invoice": REF("invoice", "2b3c4d5e-9999-4c4c-9a9a-000000000003"), "receiptUrl": "/api/v1/cashier/sales/{{saleId2}}/receipt.pdf", "changeGiven": None}},
   tests=["const json = pm.response.json();",
          "pm.test('nothing supplied yet, all reserved', () => { pm.expect(json.data.sale.supplyStatus).to.eql('NOT_SUPPLIED'); pm.expect(json.data.sale.lines[0].outstanding).to.eql(1); });",
          "pm.collectionVariables.set('saleTxn2', json.data.sale.ref);",
          "pm.test('invoiced on account', () => pm.expect(json.data.invoice).to.not.be.null);"],
   desc="supply[].supplyNow = 0 reserves the whole line for later delivery. The reserved stock is held (it still counts in a stocktake) but is not "
        "available to sell again. The dealer is invoiced on account.")

ep(P, "E4 Supply Lookup", "GET", "/api/v1/cashier/supply/lookup", query=[{"key": "ref", "value": "{{saleTxn2}}"}],
   resp={"data": {"sale": REF("sale", "{{saleId2}}"), "saleRef": "TXN-2", "invoiceNumber": "INV-2", "customer": REF("customer", "{{dealerId}}"),
                  "customerName": "Okafor & Sons Distribution 482", "customerType": "AUTHORISED_DEALER", "address": None, "contact": None,
                  "lines": [{"saleLineId": "{{saleLineId2}}", "stockItem": REF("stockItem", "{{finishedProductId}}"), "product": "Premium Blend", "unit": "Kilogram",
                             "purchased": 1.000, "supplied": 0.000, "outstanding": 1.000}],
                  "totals": {"purchased": 1.000, "supplied": 0.000, "outstanding": 1.000, "progressPct": 0},
                  "history": [], "waybillCount": 0, "fullySupplied": False}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('saleLineId2', json.data.lines[0].saleLineId);",
          "pm.test('one line outstanding', () => pm.expect(json.data.totals.outstanding).to.eql(1));"],
   desc="The outstanding position of a sale by its TXN or INV reference, with the saleLineId to release against. Perm cashier.waybill.manage or cashier.supply.read.")

ep(P, "E5 Release by Waybill (collection)", "POST", "/api/v1/cashier/waybills",
   body={"saleId": "{{saleId2}}", "releaseMethod": "COLLECTION", "releaseDate": "{{today}}", "collectedBy": "Tunde Okafor",
         "lines": [{"saleLineId": "{{saleLineId2}}", "quantity": 1}]},
   resp={"data": {"id": "{{waybillId}}", "ref": "WB-2", "sale": REF("sale", "{{saleId2}}"), "saleRef": "TXN-2", "invoiceNumber": "INV-2",
                  "location": REF("location", "{{showroomId}}"), "customerName": "Okafor & Sons Distribution 482", "releaseMethod": "COLLECTION",
                  "releaseDate": "2026-10-22", "driver": None, "driverName": None, "vehicleNumber": None, "collectedBy": "Tunde Okafor", "deliveryAddress": None,
                  "status": "ISSUED", "receivedBy": None, "receivedAt": None, "acknowledgementNote": None, "cancelledReason": None, "issuedBy": USER,
                  "lines": [{"stockItem": REF("stockItem", "{{finishedProductId}}"), "product": "Premium Blend", "unit": "Kilogram", "quantity": 1.000,
                             "from": REF("location", "{{floorAreaId}}"), "stillOutstanding": 0.000}]}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('waybillId', json.data.id);",
          "pm.test('issued with a WB number', () => { pm.expect(json.data.ref).to.match(/^WB-\\d+$/); pm.expect(json.data.status).to.eql('ISSUED'); });"],
   desc="Perm cashier.waybill.manage. Releases outstanding goods, posting the reserved stock out. A release can't exceed the line's outstanding "
        "(400 RELEASE_EXCEEDS_OUTSTANDING); a delivery needs an active driver, a collection needs who collected. Shares the WB-n numbering with stock transfers.")

ep(P, "E6 Pending Supply Report", "GET", "/api/v1/cashier/reports/pending-supply", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [{"sale": REF("sale", "{{saleId2}}"), "saleRef": "TXN-2", "invoice": REF("invoice", "2b3c4d5e-9999-4c4c-9a9a-000000000003"), "invoiceNumber": "INV-2",
                             "customer": REF("customer", "{{dealerId}}"), "customerName": "Okafor & Sons Distribution 482", "date": "2026-10-22", "lines": 1,
                             "ordered": 1.000, "supplied": 1.000, "outstanding": 0.000, "progressPct": 100, "deliveries": 1, "lastSupply": "2026-10-22", "status": "FULLY_SUPPLIED"}],
                  "page": 0, "size": 20, "totalItems": 0, "totalPages": 0,
                  "summary": {"invoicesPending": 0, "unitsOrdered": 0.000, "unitsSupplied": 0.000, "unitsOutstanding": 0.000, "fulfilledPct": 100}}},
   desc="Perm cashier.supply.read. Completed sales with goods still owed, oldest first; the summary totals cover the whole filtered set. "
        "Now that the sale is fully supplied it has dropped out of the default (NOT_SUPPLIED/PARTIALLY_SUPPLIED) list. Filters: invoice, customerId, status, locationId, from/to.")

ep(P, "E7 Acknowledge Waybill", "POST", "/api/v1/cashier/waybills/{{waybillId}}/acknowledge",
   body={"receivedBy": "Tunde Okafor", "note": "Collected in full"},
   resp={"data": {"id": "{{waybillId}}", "ref": "WB-2", "status": "DELIVERED", "receivedBy": "Tunde Okafor", "sale": REF("sale", "{{saleId2}}"),
                  "location": REF("location", "{{showroomId}}"), "customerName": "Okafor & Sons Distribution 482", "releaseMethod": "COLLECTION",
                  "releaseDate": "2026-10-22", "issuedBy": USER, "lines": []}},
   tests=["const json = pm.response.json();", "pm.test('delivered', () => pm.expect(json.data.status).to.eql('DELIVERED'));"],
   desc="Records the signed copy. Only ISSUED. Cancel (POST .../cancel, same day before the goods left) puts the stock and the reservation back.")

ep(P, "E8 Customer Waybill PDF", "GET", "/api/v1/cashier/waybills/{{waybillId}}/waybill.pdf", pdf=True,
   desc="The customer waybill as a PDF: products, quantity released and still outstanding, release method, and a signature block.")

ep(P, "E9 List Waybills", "GET", "/api/v1/cashier/waybills", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [{"id": "{{waybillId}}", "ref": "WB-2", "sale": REF("sale", "{{saleId2}}"), "saleRef": "TXN-2", "invoiceNumber": "INV-2",
                             "location": REF("location", "{{showroomId}}"), "customerName": "Okafor & Sons Distribution 482", "releaseMethod": "COLLECTION",
                             "releaseDate": "2026-10-22", "driverName": None, "collectedBy": "Tunde Okafor", "units": 1.000, "status": "DELIVERED"}],
                  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1}},
   desc="Perm cashier.supply.read. Filters: locationId, saleId, driverId, status, ref (a WB or INV number). Newest first.")


# ---- F. Instant Discount (slice 3): an Admin's discount on a live sale ----
ep(P, "F1 Start Sale for Discount", "POST", "/api/v1/cashier/sales", body={"customerId": "{{dealerId}}"},
   resp={"data": dict(SALE, version=0, customer=None, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER", lines=[],
                      totals=dict(SALE["totals"], gross=0.00, net=0.00, vat=0.00, total=0.00), allowedMethods=["CASH", "POS", "FUND_TRANSFER", "ON_ACCOUNT"])},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('saleId3', json.data.id);", "pm.collectionVariables.set('saleVersion3', json.data.version);"],
   desc="A fresh sale to demonstrate Instant Discount.")

ep(P, "F2 Set Lines", "PUT", "/api/v1/cashier/sales/{{saleId3}}/lines",
   body={"version": "{{saleVersion3}}", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 1}]},
   resp={"data": dict(SALE, version=1, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER",
                      lines=[dict(SALE_LINE, priceBasis="DEALER", unitPrice=4500.00, supplied=0.000, outstanding=1.000)])},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('saleVersion3', json.data.version);"],
   desc="One line at the dealer price.")

ep(P, "F3 Apply Instant Discount (5%)", "POST", "/api/v1/cashier/sales/{{saleId3}}/instant-discount",
   body={"mode": "PERCENT", "value": 5, "reason": "LOYALTY", "note": "Long-standing dealer", "version": "{{saleVersion3}}"},
   resp={"data": {"id": "{{discountId}}", "ref": "IDS-1", "sale": REF("sale", "{{saleId3}}"), "mode": "PERCENT", "value": 5.0000, "discountAmount": 225.00,
                  "baseAmount": 4500.00, "pctOfBase": 5.0000, "reason": "LOYALTY", "note": "Long-standing dealer", "status": "APPLIED", "appliedBy": USER,
                  "approvedBy": USER, "rejectionReason": None}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('discountId', json.data.id);",
          "pm.test('applied within the ceiling', () => { pm.expect(json.data.status).to.eql('APPLIED'); pm.expect(json.data.ref).to.match(/^IDS-\\d+$/); });"],
   desc="Perm cashier.discount.apply (Administrator). base = gross − line discounts; a reduction within the ceiling (default 10%) applies at once, above it "
        "goes to Management (status PENDING_APPROVAL, and completion is blocked with 409 DISCOUNT_PENDING_APPROVAL until approved). The discount is "
        "allocated across the lines, so VAT and the invoice are right. 409 DISCOUNT_ALREADY_ACTIVE if one is already live; 400 DISCOUNT_EXCEEDS_TRANSACTION.")

ep(P, "F4 Get Sale (discounted total)", "GET", "/api/v1/cashier/sales/{{saleId3}}",
   resp={"data": dict(SALE, version=2, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER",
                      totals=dict(SALE["totals"], gross=4500.00, instantDiscount=225.00, net=4275.00, vat=320.63, total=4595.63),
                      instantDiscount={"id": "{{discountId}}", "ref": "IDS-1", "amount": 225.00, "pctOfBase": 5.0000, "reason": "LOYALTY", "status": "APPLIED"})},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('saleVersion3', json.data.version);",
          "pm.test('the discount shows on the sale', () => pm.expect(json.data.totals.instantDiscount).to.be.above(0));"],
   desc="The sale now carries the discount (totals.instantDiscount) and the live discount summary (instantDiscount).")

ep(P, "F5 Recent Instant Discounts", "GET", "/api/v1/cashier/instant-discounts", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [{"id": "{{discountId}}", "ref": "IDS-1", "sale": REF("sale", "{{saleId3}}"), "discountAmount": 225.00, "pctOfBase": 5.0000,
                             "reason": "LOYALTY", "status": "APPLIED", "appliedBy": USER, "approvedBy": USER, "createdAt": NOW}],
                  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1}},
   desc="Perm cashier.discount.apply or cashier.sales.read-all. Filters: status, reason, saleId. Approve/reject a pending one at POST /instant-discounts/{id}/approve|reject.")

ep(P, "F6 Withdraw the Discount", "POST", "/api/v1/cashier/instant-discounts/{{discountId}}/withdraw",
   resp={"data": {"id": "{{discountId}}", "ref": "IDS-1", "sale": REF("sale", "{{saleId3}}"), "mode": "PERCENT", "value": 5.0000, "discountAmount": 225.00,
                  "baseAmount": 4500.00, "pctOfBase": 5.0000, "reason": "LOYALTY", "note": "Long-standing dealer", "status": "WITHDRAWN", "appliedBy": USER,
                  "approvedBy": USER, "rejectionReason": None}},
   tests=["const json = pm.response.json();", "pm.test('withdrawn', () => pm.expect(json.data.status).to.eql('WITHDRAWN'));"],
   desc="The Administrator removes a live discount; the sale returns to full price.")

ep(P, "F6b Get Sale (full price restored)", "GET", "/api/v1/cashier/sales/{{saleId3}}",
   resp={"data": dict(SALE, version=4, customerName="Okafor & Sons Distribution 482", priceBasis="DEALER",
                      totals=dict(SALE["totals"], gross=4500.00, instantDiscount=0.00, net=4500.00, vat=337.50, total=4837.50))},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('saleVersion3', json.data.version);",
          "pm.test('the discount is gone', () => pm.expect(json.data.totals.instantDiscount).to.eql(0));"],
   desc="After the withdraw the till re-fetches the sale (no SSE): the discount is gone, totals are back to full price, and the version has moved on.")

ep(P, "F7 Abandon the Sale", "POST", "/api/v1/cashier/sales/{{saleId3}}/abandon", body={"version": "{{saleVersion3}}"},
   resp={"data": dict(SALE, status="ABANDONED", customerName="Okafor & Sons Distribution 482")},
   desc="Clears the demo sale (no stock or money was touched), so the till can close.")

# ---- G. Pro-forma invoices (slice 3): a quotation (commits no stock) ----
ep(P, "G1 Create & Send Pro-forma", "POST", "/api/v1/cashier/pro-formas?send=true",
   body={"locationId": "{{showroomId}}", "customerId": "{{dealerId}}", "issueDate": "{{today}}", "paymentTerms": "50% deposit, balance on delivery",
         "deliveryTerms": "Delivered to customer warehouse", "lines": [{"stockItemId": "{{finishedProductId}}", "quantity": 20, "lineDiscount": 1600}]},
   resp={"data": {"id": "{{proFormaId3}}", "ref": "PF-1", "status": "SENT", "location": REF("location", "{{showroomId}}"), "customer": REF("customer", "{{dealerId}}"),
                  "customerName": "Okafor & Sons Distribution 482", "customerType": "AUTHORISED_DEALER", "prospect": None, "priceBasis": "DEALER",
                  "issueDate": "2026-10-23", "validUntil": "2026-10-30", "paymentTerms": "50% deposit, balance on delivery", "deliveryTerms": "Delivered to customer warehouse",
                  "note": None, "lines": [{"lineNo": 1, "stockItem": REF("stockItem", "{{finishedProductId}}"), "name": "Premium Blend", "unit": "Kilogram",
                                           "quantity": 20.000, "unitPrice": 4500.00, "lineDiscount": 1600.00, "lineNet": 88400.00, "vatRate": 7.50, "vatAmount": 6630.00,
                                           "lineTotal": 95030.00, "availabilityWarning": True}],
                  "totals": {"gross": 90000.00, "discount": 1600.00, "net": 88400.00, "vatEnabled": True, "vatRate": 7.50, "vat": 6630.00, "total": 95030.00},
                  "preparedBy": USER, "declineReason": None, "convertedSale": None}},
   tests=["const json = pm.response.json();", "pm.collectionVariables.set('proFormaId3', json.data.id);",
          "pm.test('sent quotation', () => { pm.expect(json.data.status).to.eql('SENT'); pm.expect(json.data.ref).to.match(/^PF-\\d+$/); });"],
   desc="Perm cashier.pro-forma.manage. Prices from the price list on the issue date (dealer price for a dealer); line discounts allowed. A pro-forma commits "
        "NO stock (lines show availabilityWarning only). validUntil defaults to the issue date + the module setting. Total line discounts above the ceiling need "
        "Management approval (status PENDING_APPROVAL). Omit ?send to save a DRAFT. A prospect (name/contact) can stand in for a registered customer.")

ep(P, "G2 Get Pro-forma", "GET", "/api/v1/cashier/pro-formas/{{proFormaId3}}",
   resp={"data": {"id": "{{proFormaId3}}", "ref": "PF-1", "status": "SENT", "location": REF("location", "{{showroomId}}"), "customer": REF("customer", "{{dealerId}}"),
                  "customerName": "Okafor & Sons Distribution 482", "customerType": "AUTHORISED_DEALER", "prospect": None, "priceBasis": "DEALER",
                  "issueDate": "2026-10-23", "validUntil": "2026-10-30", "paymentTerms": "50% deposit, balance on delivery", "deliveryTerms": None, "note": None,
                  "lines": [], "totals": {"gross": 90000.00, "discount": 1600.00, "net": 88400.00, "vatEnabled": True, "vatRate": 7.50, "vat": 6630.00, "total": 95030.00},
                  "preparedBy": USER, "declineReason": None, "convertedSale": None}},
   desc="Edit a DRAFT with PUT; send with POST .../send; decline/cancel/reissue; approve/reject its discounts. Convert a SENT one with POST .../convert "
        "(needs cashier.pro-forma.manage + invoicing.invoice.issue): it becomes a reserved PRO_FORMA_ORDER sale, invoiced on account.")

ep(P, "G3 Pro-forma PDF", "GET", "/api/v1/cashier/pro-formas/{{proFormaId3}}/pro-forma.pdf", pdf=True,
   desc="The pro-forma as a 'QUOTATION'-stamped PDF, with the 'not a demand for payment' footer.")

ep(P, "G4 List Pro-formas", "GET", "/api/v1/cashier/pro-formas", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [{"id": "{{proFormaId3}}", "ref": "PF-1", "status": "SENT", "location": REF("location", "{{showroomId}}"), "customer": REF("customer", "{{dealerId}}"),
                             "customerName": "Okafor & Sons Distribution 482", "date": "2026-10-23", "validUntil": "2026-10-30", "items": 1, "value": 95030.00,
                             "preparedBy": USER, "convertedSale": None}],
                  "page": 0, "size": 20, "totalItems": 1, "totalPages": 1}},
   desc="Perm cashier.pro-forma.manage or cashier.sales.read-all. Filters: status, locationId, customerId, ref, from/to.")

# ---- H. Reports (slice 3) ----
ep(P, "H1 Sales Report (daily)", "GET", "/api/v1/cashier/reports/sales-daily",
   query=[{"key": "from", "value": "{{today}}"}, {"key": "to", "value": "{{today}}"}],
   resp={"data": {"rows": [{"date": "2026-10-23", "showroom": REF("location", "{{showroomId}}"), "transactions": 1, "units": 1.000, "gross": 5000.00,
                            "discount": 0.00, "net": 5000.00, "vat": 375.00, "avgSale": 5000.00}],
                  "totals": {"date": "2026-10-23", "showroom": REF("location", "{{showroomId}}"), "transactions": 1, "units": 1.000, "gross": 5000.00,
                             "discount": 0.00, "net": 5000.00, "vat": 375.00, "avgSale": 5000.00},
                  "stats": {"netSales": 5000.00, "transactions": 1, "units": 1.000, "averageTransaction": 5000.00, "bestDay": "2026-10-23"}}},
   tests=["const json = pm.response.json();", "pm.test('has a stats block', () => pm.expect(json.data.stats).to.have.property('netSales'));"],
   desc="Perm cashier.sales.read-all. One row per day and showroom over completed sales; from/to required (max 366 days). Optional locationId, cashierUserId, channel.")

ep(P, "H2 Customers/Distributors Comprehensive", "GET", "/api/v1/cashier/reports/customer-activity",
   query=[{"key": "kind", "value": "DISTRIBUTORS"}, {"key": "from", "value": "{{today}}"}, {"key": "to", "value": "{{today}}"}],
   resp={"data": {"rows": [{"date": "2026-10-23", "sale": REF("sale", "{{saleId2}}"), "saleRef": "TXN-2", "invoiceNumber": "INV-2", "customer": REF("customer", "{{dealerId}}"),
                            "customerName": "Okafor & Sons Distribution 482", "items": 1, "units": 1.000, "value": 4837.50, "cash": 0.00, "pos": 0.00, "transfer": 0.00,
                            "wallet": 0.00, "deposit": 0.00, "onAccount": 4837.50, "waybills": ["WB-2"]}],
                  "transactions": 1, "units": 1.000, "value": 4837.50, "averageTransaction": 4837.50}},
   desc="Perm cashier.sales.read-all. kind = DISTRIBUTORS | NO_CREDIT | SPECIAL. Per-sale rows with the payment breakdown by method and the waybill refs.")


ep(P, "D1 Close Till", "POST", "/api/v1/cashier/till-sessions/{{tillSessionId}}/close", body={"countedCash": 20000, "note": "End of shift"},
   resp={"data": dict(TILL_SESSION, status="CLOSED", closedAt=NOW, closedBy=USER, countedCash=20000.00, expectedCash=20000.00, variance=0.00,
                      closeNote="End of shift", running=None)},
   tests=["const json = pm.response.json();", "pm.test('closed balanced', () => { pm.expect(json.data.status).to.eql('CLOSED'); pm.expect(json.data.variance).to.eql(0); });"],
   desc="Perm cashier.sales.manage (own) or cashier.till.supervise (any, forceClosed). Open sales must be completed or cleared first (409 OPEN_SALES_EXIST). "
        "Variance = counted - expected; a non-zero variance alerts Accounts, who resolve it in Account (nothing is posted automatically).")

ep(P, "D2 Till Sessions", "GET", "/api/v1/cashier/till-sessions", query=[{"key": "page", "value": "0"}, {"key": "size", "value": "20"}],
   resp={"data": {"items": [dict(TILL_SESSION, status="CLOSED", running=None)], "page": 0, "size": 20, "totalItems": 1, "totalPages": 1}},
   desc="Own sessions unless cashier.sales.read-all. Filters: locationId, cashierUserId, status, from/to (opened), withVarianceOnly.")

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
    elif e.get("binary"):
        full_tests = custom_tests + [
            "pm.test('status is 200', () => pm.response.to.have.status(200));",
            f"pm.test('response is {e['binary']}', () => pm.expect(pm.response.headers.get('Content-Type')).to.include('{e['binary']}'));",
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
            "body": ("%PDF-1.7 ... (binary PDF)" if e.get("pdf") else f"(binary {e['binary']})" if e.get("binary") else json.dumps(e["resp"], indent=2) if e["resp"] is not None else ""),
        }
        item["response"] = [example]
    return item

FOLDER_DESCRIPTIONS = {
    "18 - Electronic Invoicing": (
        "Electronic Invoicing (backend docs/08): A NRS classification on a stock item, B raising invoices through the sandbox endpoint "
        "(which stands in for Sales & POS until docs/09 exists), C history, search, detail, PDF and pickers, D drafts (edit, issue, discard), "
        "E void and void-and-reissue, voided list and global search, F payments (cash, transfer awaiting confirmation, decline, reversal), "
        "G credit notes, H e-mail delivery and bulk export, I receivables and the Debtors List, J NRS preview. "
        "Run after folders 06 (factoryId, finishedProductId), 12 (dealerId, specialId), 16 (showroomId) and 17 (tillAccountId, bankAccountId). "
        "Captured variables: invoiceId, invoiceNumber, invoiceSourceRef, walkInInvoiceId, draftInvoiceId, discardDraftId, reissuedInvoiceId, "
        "cashPaymentId, transferReceiptId, creditLineId, creditNoteId."
    ),
    "17 - Finance & Accounting": (
        "Finance & Accounting (backend docs/in-progress/07): A expense categories, B bank accounts, manual entries and the Bank Statement, "
        "C cash accounts (petty cash, tills), cash counts and the Cash Inflow/Outflow Statement, D exchange rates, E global search, "
        "F petty cash (fund, pay, history, statement, send to a branch and accept), G running costs (draft, submit, approve, reverse), "
        "H funds sent to a branch and confirmed, I cash lodgements, J search, K wallet funding and the receipt confirmation queue, "
        "L OTP-gated wallet withdrawals, M tenderable, N other income, O VAT per location. "
        "Run after folder 16 (it uses the showroom created there) and 12 (for the today variable). "
        "Captured variables: bankChargesCategoryId, expenseCategoryId, bankAccountId, manualEntryRef, pettyCashAccountId, tillAccountId, pettyFundingId, "
        "pettyPaymentId, allocationId, runningCostId, runningCostRef, draftCostId, branchBankAccountId, disbursementId, lodgementId, walletFundingId, "
        "fundingReceiptId, pendingReceiptId, withdrawalOtpId, withdrawalOtpCode, withdrawalId, otherIncomeId. Uses dealerId and specialId from folder 12."
    ),
    "05 - Settings": (
        "Settings (backend docs/06): A company profile and logo, B onboarding, C module settings, D custom fields, E settings search. "
        "Needs 01 - Auth > Register Tenant first. Run top to bottom; C4 puts the inventory OTP threshold back to its default for the later folders. "
        "Captured variable: customFieldId."
    ),
    "16 - Showroom": (
        "Showroom (backend docs/05). Run after folders 06-15: it uses the factory, units, finished product (with the price published in 15), "
        "manufacturer, driver and dealer they create. A: register showrooms and areas. B: direct entries and outsource receipts. "
        "C: stock from the factory into the showroom warehouse. D: area moves. E: walk-in and dealer returns credited to wallets, reversal, "
        "Return Reports. F: comprehensive stock and transfer reports."
    ),
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
        "showroomId", "showroomName", "pendingShowroomId", "floorAreaId", "warehouseAreaId", "coldRoomId", "entryId", "directItemId",
        "outsourcedItemId", "showroomTransferId", "showroomTransferLineId", "returnId", "dealerReturnId",
        "invoiceId", "invoiceNumber", "invoiceSourceRef", "walkInInvoiceId", "draftInvoiceId", "discardDraftId", "reissuedInvoiceId",
        "cashPaymentId", "transferReceiptId", "creditLineId", "creditNoteId",
        "tillSessionId", "saleId", "saleVersion", "saleTotal",
        "saleId2", "saleVersion2", "saleTotal2", "saleTxn2", "saleLineId2", "waybillId",
        "saleId3", "saleVersion3", "discountId", "proFormaId3",
    ]

    collection = {
        "info": {
            "name": "ZifIQ Backend API",
            "description": "Test collection for the ZifIQ Kotlin/Spring Boot backend: every module with real persistence and business logic (Auth, Tenants & Members, OTP, VAT Control, Settings, Factory, Inventory, NRS Reference Data, Customers, Documents & Files, Showroom, Finance & Accounting, Electronic Invoicing, Sales & POS). Modules still returning mock data (AI, Barcode, History, Process Guide) are left out until they are built. Run the folders top to bottom, 01 through 19: each request's Tests script captures IDs and tokens into collection variables that later requests reuse, so the whole collection runs end to end in the Collection Runner or Newman with no manual edits. See docs/ in the backend repo for the design each module implements.",
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
