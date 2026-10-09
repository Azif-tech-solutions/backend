package com.zifiq.settings

import com.zifiq.common.errors.ErrorCode
import com.zifiq.customers.service.CustomerAttributeUsage
import com.zifiq.customers.service.CustomersSettings
import com.zifiq.inventory.service.InventorySettings
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.dto.CompanyProfileRequest
import com.zifiq.settings.dto.CustomFieldCreateRequest
import com.zifiq.settings.dto.CustomFieldUpdateRequest
import com.zifiq.settings.dto.OnboardingCompleteRequest
import com.zifiq.settings.service.CompanyProfileService
import com.zifiq.settings.service.CustomFieldService
import com.zifiq.settings.service.ModuleSettingsService
import com.zifiq.settings.service.OnboardingService
import com.zifiq.settings.service.SettingsSearchService
import java.util.UUID
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/** Settings (docs/06 §10) against real PostgreSQL with RLS. Each test uses its own tenant. */
class SettingsIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val admin: UUID = UUID.randomUUID()
        private val customersClerk: UUID = UUID.randomUUID()
        private val viewer: UUID = UUID.randomUUID()
        private val profileTenant: UUID = UUID.randomUUID()
        private val onboardingTenant: UUID = UUID.randomUUID()
        private val fieldsTenant: UUID = UUID.randomUUID()
        private val otherTenant: UUID = UUID.randomUUID()
        private val tenants = listOf(profileTenant, onboardingTenant, fieldsTenant, otherTenant)

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                listOf(admin, customersClerk, viewer).forEach {
                    exec("insert into app_users (id, email, display_name) values ('$it', 'st-$it@test.com', 'User ${it.toString().take(4)}')")
                }
                exec("update app_users set display_name = 'Alice Admin' where id = '$admin'")
                tenants.forEachIndexed { index, tenant -> exec("insert into tenants (id, name) values ('$tenant', 'Settings Co $index')") }
            }
            tenants.forEach { tenant ->
                inTenant(tenant) {
                    listOf(admin, customersClerk, viewer).forEach { exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenant', '$it')") }
                    grant(tenant, admin, "select id from permission_catalog")
                    grant(tenant, customersClerk, "select id from permission_catalog where code in ('customers.read', 'customers.manage')")
                    grant(tenant, viewer, "select id from permission_catalog where code = 'customers.read'")
                }
            }
        }

        private fun inTenant(tenant: UUID, block: org.jetbrains.exposed.sql.Transaction.() -> Unit) = transaction(database) {
            exec("select set_config('app.tenant_id', '$tenant', true)")
            block()
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(tenant: UUID, user: UUID, permissionIds: String) {
            val role = UUID.randomUUID()
            exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenant', 'Role $user', '$admin')")
            exec("insert into role_permissions (role_id, permission_id) $permissionIds".replace("select id", "select '$role', id"))
            exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenant', '$user', '$role', '$admin')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val events = DomainEvents(tenantContext, actors)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), CustomersSettings(), InventorySettings()), tenantContext)
    private val profiles = CompanyProfileService(tx, tenantContext, actors, events)
    private val onboarding = OnboardingService(tx, tenantContext, profiles, events)
    private val moduleSettingsService = ModuleSettingsService(moduleSettings, tx, actors, events, permissions)
    private val fields = CustomFieldService(tx, tenantContext, actors, events, listOf(CustomerAttributeUsage(tenantContext)))
    private val search = SettingsSearchService(tx, tenantContext, moduleSettings, moduleSettingsService)

    init {
        // What the TenantProvisioner does at registration (idempotent).
        tenants.forEach { tenant -> tx.executeForTenant(tenant) { profiles.provision(tenant, admin) } }
    }

    private fun signIn(user: UUID, tenant: UUID) {
        tenantContext.set(tenant)
        val jwt = Jwt.withTokenValue("test").header("alg", "none").subject(user.toString()).claim("email", "$user@test.com").build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private fun <T> sql(query: String, read: (java.sql.ResultSet) -> T): T = tx.execute { TransactionManager.current().exec(query) { read(it) }!! }

    private fun profileRequest(stateCode: String? = "NG-AB", lgaCode: String? = "NG-AB-ANO") = CompanyProfileRequest(
        tradingName = "Zif Manufacturing Ltd", legalName = "Zif Manufacturing Limited", tin = "33467982-0001",
        email = "Info@Zif.ng", phone = "0803 412 7781", website = "https://zif.ng", streetAddress = "32 Owonikoko Street",
        city = "Aba", stateCode = stateCode, lgaCode = lgaCode, documentFooter = "Thank you for your business.",
    )

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { it.toByte() }

    @Test
    fun `a new tenant's profile carries its registered name and starts with every NRS field missing`() {
        signIn(viewer, profileTenant)
        val view = profiles.view()
        assertThat(view.tradingName).startsWith("Settings Co")
        assertThat(view.hasLogo).isFalse()
        assertThat(view.nrsReadiness.ready).isFalse()
        assertThat(view.nrsReadiness.missing).contains("tin", "streetAddress", "nrsBusinessId")
    }

    @Test
    fun `updating the profile normalises values, validates the region, and records which fields changed`() {
        signIn(admin, otherTenant)
        failsWith { profiles.update(profileRequest(stateCode = "NG-LA", lgaCode = "NG-AB-ANO")) }.isEqualTo(ErrorCode.INVALID_LGA_CODE)
        failsWith { profiles.update(profileRequest(stateCode = "NG-XX", lgaCode = null)) }.isEqualTo(ErrorCode.INVALID_STATE_CODE)

        val view = profiles.update(profileRequest())
        assertThat(view.tradingName).isEqualTo("Zif Manufacturing Ltd")
        assertThat(view.email).isEqualTo("info@zif.ng")
        assertThat(view.stateName).isEqualTo("Abia")
        assertThat(view.lgaName).isEqualTo("Aba North")
        assertThat(view.updatedBy).isEqualTo("Alice Admin")
        assertThat(view.nrsReadiness.missing).containsExactlyInAnyOrder("nrsBusinessId", "businessDescription")
        assertThat(sql("select phone_e164 from company_profiles") { it.next(); it.getString(1) }).isEqualTo("+2348034127781")

        val payload = sql("select payload::text from domain_events where event_type = 'settings.company.updated' order by occurred_at desc limit 1") { it.next(); it.getString(1) }
        assertThat(payload).contains("tradingName", "tin").doesNotContain("Zif Manufacturing")

        // The spi gives documents the same issuer.
        val issuer = tx.execute { profiles.get() }
        assertThat(issuer.tin).isEqualTo("33467982-0001")
        assertThat(issuer.stateName).isEqualTo("Abia")
    }

    @Test
    fun `the logo must really be a small PNG or JPEG, and can be removed`() {
        signIn(admin, profileTenant)
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
        failsWith { profiles.setLogo(svg, "logo.png") }.isEqualTo(ErrorCode.INVALID_LOGO)
        failsWith { profiles.setLogo(png + ByteArray(CompanyProfileService.MAX_LOGO_BYTES), "big.png") }.isEqualTo(ErrorCode.INVALID_LOGO)
        failsWith { profiles.logo() }.isEqualTo(ErrorCode.LOGO_NOT_SET)

        assertThat(profiles.setLogo(png, "logo.bin").hasLogo).isTrue()
        val logo = profiles.logo()
        assertThat(logo.contentType).isEqualTo("image/png")
        assertThat(logo.bytes).isEqualTo(png)
        assertThat(tx.execute { profiles.get() }.logo).isEqualTo(png)

        assertThat(profiles.removeLogo().hasLogo).isFalse()
        failsWith { profiles.logo() }.isEqualTo(ErrorCode.LOGO_NOT_SET)
    }

    @Test
    fun `onboarding needs the company step, and unfinished steps must be skipped explicitly`() {
        signIn(admin, onboardingTenant)
        val start = onboarding.status()
        assertThat(start.completed).isFalse()
        assertThat(start.steps.map { it.key to it.done }).containsExactly("COMPANY" to false, "BRANCH" to false, "FACTORY" to false)
        failsWith { onboarding.complete(OnboardingCompleteRequest(listOf("BRANCH", "FACTORY"))) }.isEqualTo(ErrorCode.ONBOARDING_INCOMPLETE)
        failsWith { onboarding.complete(OnboardingCompleteRequest(listOf("COMPANY"))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        profiles.update(profileRequest())
        failsWith { onboarding.complete(OnboardingCompleteRequest(listOf("FACTORY"))) }.isEqualTo(ErrorCode.ONBOARDING_INCOMPLETE)

        inTenant(onboardingTenant) {
            exec("insert into locations (tenant_id, type, code, name, created_by_user_id) values ('$onboardingTenant', 'SHOWROOM', 'SHR-1', 'Ikeja', '$admin')")
        }
        val done = onboarding.complete(OnboardingCompleteRequest(listOf("factory")))
        assertThat(done.completed).isTrue()
        assertThat(done.steps.map { Triple(it.key, it.done, it.skipped) })
            .containsExactly(Triple("COMPANY", true, false), Triple("BRANCH", true, false), Triple("FACTORY", false, true))
        assertThat(sql("select count(*) from domain_events where event_type = 'settings.onboarding.completed'") { it.next(); it.getLong(1) }).isEqualTo(1)
    }

    @Test
    fun `custom fields keep stored values safe - in-use options and blanks block the edit`() {
        signIn(admin, fieldsTenant)
        val colour = fields.create(CustomFieldCreateRequest("CUSTOMER", "Colour", "SELECT", listOf("Red", "Blue", "Green")))
        assertThat(colour.fieldKey).isEqualTo("colour")
        assertThat(colour.sortOrder).isEqualTo(10)

        val duplicate = runCatching { fields.create(CustomFieldCreateRequest("CUSTOMER", "COLOUR", "TEXT")) }.exceptionOrNull() as ApiException
        assertThat(duplicate.code).isEqualTo(ErrorCode.DUPLICATE_NAME)
        assertThat(duplicate.details["existingId"]).isEqualTo(colour.id.toString())
        assertThat(fields.create(CustomFieldCreateRequest("CUSTOMER", "Colour!", "TEXT")).fieldKey).isEqualTo("colour_2")
        assertThat(fields.create(CustomFieldCreateRequest("STOCK_ITEM", "Colour", "TEXT")).fieldKey).isEqualTo("colour")
        assertThat(fields.create(CustomFieldCreateRequest("SUPPLIER", "2nd Phone (alt.)", "TEXT")).fieldKey).isEqualTo("f_2nd_phone_alt")
        failsWith { fields.create(CustomFieldCreateRequest("CUSTOMER", "Weight", "NUMBER", listOf("1"))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)
        failsWith { fields.create(CustomFieldCreateRequest("CUSTOMER", "Size", "SELECT", listOf("S", "s"))) }.isEqualTo(ErrorCode.VALIDATION_FAILED)

        inTenant(fieldsTenant) {
            exec("insert into customers (tenant_id, code, type, name, created_by_user_id, custom_attributes) values ('$fieldsTenant', 'CU-1', 'ORDINARY_CUSTOMER', 'Ada', '$admin', '{\"colour\": \"Red\"}')")
            exec("insert into customers (tenant_id, code, type, name, created_by_user_id) values ('$fieldsTenant', 'CU-2', 'ORDINARY_CUSTOMER', 'Bola', '$admin')")
        }
        assertThat(fields.get(colour.id).usageCount).isEqualTo(1)
        failsWith { fields.update(colour.id, CustomFieldUpdateRequest("Colour", listOf("Blue", "Green"))) }.isEqualTo(ErrorCode.OPTION_IN_USE)
        val trimmed = fields.update(colour.id, CustomFieldUpdateRequest("Colour", listOf("red", "Green", "Yellow")))
        assertThat(trimmed.options).containsExactly("red", "Green", "Yellow")
        failsWith { fields.update(colour.id, CustomFieldUpdateRequest("Colour", required = true)) }.isEqualTo(ErrorCode.FIELD_HAS_BLANKS)
        // A new required field would be blank on both customers.
        failsWith { fields.create(CustomFieldCreateRequest("CUSTOMER", "Region", "TEXT", required = true)) }.isEqualTo(ErrorCode.FIELD_HAS_BLANKS)

        fields.changeStatus(colour.id, "INACTIVE")
        assertThat(fields.list(null, setOf("CUSTOMER"), setOf("INACTIVE"), emptySet(), null, null, null).map { it.label }).containsExactly("Colour")
        assertThat(fields.list(null, emptySet(), emptySet(), emptySet(), null, true, null).map { it.id }).containsExactly(colour.id)
        assertThat(fields.list("yellow", emptySet(), emptySet(), emptySet(), null, null, null).map { it.id }).containsExactly(colour.id)
        failsWith { fields.list(null, emptySet(), emptySet(), emptySet(), null, null, "nope") }.isEqualTo(ErrorCode.INVALID_SORT_FIELD)

        // Another tenant sees none of it.
        signIn(admin, otherTenant)
        assertThat(fields.list(null, emptySet(), emptySet(), emptySet(), null, null, null)).isEmpty()
        failsWith { fields.get(colour.id) }.isEqualTo(ErrorCode.CUSTOM_FIELD_NOT_FOUND)
    }

    @Test
    fun `module settings show only the modules a user manages, read-only unless they hold the settings permission`() {
        signIn(admin, profileTenant)
        val all = moduleSettingsService.list()
        assertThat(all.map { it.module }).containsExactly("customers", "inventory", "platform")
        assertThat(all.all { it.editable }).isTrue()
        assertThat(moduleSettingsService.list(q = "timezone").flatMap { group -> group.settings.map { it.key } }).containsExactly("tenant.timezone")
        assertThat(moduleSettingsService.list(modified = true)).isEmpty()

        signIn(customersClerk, profileTenant)
        val mine = moduleSettingsService.list()
        assertThat(mine.map { it.module }).containsExactly("customers")
        assertThat(mine.single().editable).isFalse()

        signIn(viewer, profileTenant)
        assertThat(moduleSettingsService.list()).isEmpty()
    }

    @Test
    fun `settings search finds screens, settings and fields the caller can see`() {
        signIn(admin, profileTenant)
        assertThat(search.search("logo").map { it.route }).contains("/settings/company/logo")
        assertThat(search.search("otp").map { it.type }).contains("module-setting")
        assertThat(search.search("")).isEmpty()

        signIn(viewer, profileTenant)
        assertThat(search.search("otp").map { it.type }).doesNotContain("module-setting")
        assertThat(search.search("vat").map { it.label }).doesNotContain("VAT Control")
    }
}
