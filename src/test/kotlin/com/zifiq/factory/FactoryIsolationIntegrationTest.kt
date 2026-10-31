package com.zifiq.factory

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import com.zifiq.platform.security.JwtTokenProvider
import com.zifiq.platform.identity.LocationSwitchService
import com.zifiq.platform.identity.AuthService
import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.paging.PageRequest
import com.zifiq.factory.dto.FactoryRequest
import com.zifiq.factory.service.FactorySetupService
import com.zifiq.factory.service.ProductionService
import com.zifiq.inventory.repository.StockLedgerRepository
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.notifications.Notify
import com.zifiq.platform.notifications.Recipients
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CostVisibility
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.LocationAccessService
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Factory data stays inside its factory: a user assigned to one factory never sees or touches
 * another's — the other factory is "not found", so its existence isn't revealed. (Between tenants,
 * row-level security and tenant-scoped foreign keys already keep every factory record apart.)
 */
class FactoryIsolationIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val keeperOfA = UUID.randomUUID()
        private val factoryA = UUID.randomUUID()
        private val factoryB = UUID.randomUUID()
        private val recipeA = UUID.randomUUID()
        private val recipeB = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
            exec("insert into app_users (id, email, display_name) values ('$admin', 'fi-$admin@test.com', 'Admin'), ('$keeperOfA', 'fi-$keeperOfA@test.com', 'Keeper A')")
            exec("insert into tenants (id, name) values ('$tenantId', 'Factory Iso')")
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$admin'), ('$tenantId', '$keeperOfA')")
            exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values " +
                "('$factoryA', '$tenantId', 'FACTORY', 'FI-A', 'Plant A', '$admin'), ('$factoryB', '$tenantId', 'FACTORY', 'FI-B', 'Plant B', '$admin')")
            exec("insert into user_location_assignments (tenant_id, user_id, location_id, access_scope, assigned_by_user_id) values ('$tenantId', '$keeperOfA', '$factoryA', 'SINGLE_LOCATION', '$admin')")
            val unit = UUID.randomUUID()
            exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'KG', 'Kilogram')")
            exec("insert into recipes (id, tenant_id, location_id, code, name, sourcing, yield_qty, yield_unit_id, created_by_user_id) values " +
                "('$recipeA', '$tenantId', '$factoryA', 'R-A', 'Recipe A', 'IN_HOUSE_PRODUCTION', 10, '$unit', '$admin'), " +
                "('$recipeB', '$tenantId', '$factoryB', 'R-B', 'Recipe B', 'IN_HOUSE_PRODUCTION', 10, '$unit', '$admin')")
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val access = LocationAccessService(actors)
    private val sequences = DocumentSequenceService(actors, tx)
    private val setup = FactorySetupService(actors, tx, sequences, access)
    private val production = ProductionService(
        actors, tx, PermissionAuthorizer(actors, tx), sequences, StockLedgerRepository(Notify(tenantContext, Recipients(tenantContext))),
        CostVisibility(), access, ModuleSettings(listOf(PlatformSettings()), tenantContext),
    )

    private val auth = AuthService(database, BCryptPasswordEncoder(), JwtTokenProvider("test-secret-key-must-be-at-least-256-bits-long-for-hmac-sha256!"), actors)
    private val switcher = LocationSwitchService(tx, actors, access, auth)

    /** Signs in [user]; [workingIn] is the factory their session switched to (the token's location_id), if any. */
    private fun signIn(user: UUID, workingIn: UUID? = null) {
        tenantContext.set(tenantId)
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString())
            .apply { if (workingIn != null) claim("location_id", workingIn.toString()) }.build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    @Test
    fun `working in a factory, even an administrator sees and touches only that factory`() {
        signIn(admin, workingIn = factoryB)
        assertThat(setup.getFactories().map { it.id }).containsExactly(factoryB)
        assertThat(production.listRecipes(null, null, PageRequest.of(0, 50)).items.map { it.id }).containsExactly(recipeB)
        failsWith { production.listRecipes(factoryA, null, PageRequest.of(0, 50)) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        failsWith { production.totalCostForRecipes(listOf(recipeA)) }.isEqualTo(ErrorCode.RECIPE_NOT_FOUND)
        failsWith { setup.updateFactoryStatus(factoryA, "SUSPENDED") }.isEqualTo(ErrorCode.FACTORY_NOT_FOUND)
        assertThat(production.totalCostForRecipes(listOf(recipeB)).map { it.recipe.id }).containsExactly(recipeB)

        // Switching to factory A turns the view around.
        signIn(admin, workingIn = factoryA)
        assertThat(setup.getFactories().map { it.id }).containsExactly(factoryA)
        assertThat(production.listRecipes(null, null, PageRequest.of(0, 50)).items.map { it.id }).containsExactly(recipeA)
    }

    @Test
    fun `only delivered special-production runs surface as derived output VAT lines (docs 10 §4_6)`() {
        val client = UUID.randomUUID()
        val deliveredA = UUID.randomUUID()
        val inProductionA = UUID.randomUUID()
        val unitSub = "(select id from units_of_measure where tenant_id='$tenantId' limit 1)"
        transaction(database) {
            exec("select set_config('app.tenant_id', '$tenantId', true)")
            exec("insert into special_production_clients (id, tenant_id, name) values ('$client', '$tenantId', 'Acme Co')")
            exec(
                "insert into special_production_runs (id, tenant_id, location_id, order_ref, batch_ref, client_id, quantity, unit_id, unit_cost, price, status, created_by_user_id) values " +
                    "('$deliveredA', '$tenantId', '$factoryA', 'SP-A1', 'B-A1', '$client', 5, $unitSub, 100, 1500, 'DELIVERED', '$admin'), " +
                    "('$inProductionA', '$tenantId', '$factoryA', 'SP-A2', 'B-A2', '$client', 5, $unitSub, 100, 999, 'IN_PRODUCTION', '$admin')",
            )
        }
        signIn(admin, workingIn = factoryA)
        val today = java.time.LocalDate.now()
        val lines = production.specialProductionDeliveries(factoryA, today.minusDays(1), today.plusDays(1))
        // The IN_PRODUCTION run is excluded; the delivered one carries its agreed price as the VAT base.
        assertThat(lines.map { it.ref }).containsExactly("SP-A1")
        assertThat(lines.single().clientName).isEqualTo("Acme Co")
        assertThat(lines.single().base).isEqualByComparingTo(java.math.BigDecimal("1500"))
        // A delivery outside the window is not reported.
        assertThat(production.specialProductionDeliveries(factoryA, today.plusDays(5), today.plusDays(10))).isEmpty()
    }

    @Test
    fun `switching location - anyone may enter a factory, a user assigned to one can never leave it`() {
        signIn(admin)
        assertThat(switcher.switchable().map { it.id }).contains(factoryA, factoryB)
        val inB = switcher.switch(factoryB)
        assertThat(inB.activeLocationId).isEqualTo(factoryB)
        assertThat(switcher.switch(null).activeLocationId).isNull()
        failsWith { switcher.switch(UUID.randomUUID()) }.isEqualTo(ErrorCode.LOCATION_NOT_FOUND)

        signIn(keeperOfA)
        assertThat(switcher.switchable().map { it.id }).containsExactly(factoryA)
        assertThat(switcher.switch(factoryA).activeLocationId).isEqualTo(factoryA)
        failsWith { switcher.switch(factoryB) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        failsWith { switcher.switch(null) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        // Even a token claiming factory B can't take them there: their assignment wins.
        signIn(keeperOfA, workingIn = factoryB)
        assertThat(setup.getFactories().map { it.id }).containsExactly(factoryA)
    }

    @Test
    fun `an unrestricted user sees every factory and its recipes`() {
        signIn(admin)
        assertThat(setup.getFactories().map { it.id }).contains(factoryA, factoryB)
        assertThat(production.listRecipes(null, null, PageRequest.of(0, 50)).items.map { it.id }).contains(recipeA, recipeB)
        assertThat(production.totalCostForRecipes(listOf(recipeA, recipeB)).map { it.recipe.id }).containsExactly(recipeA, recipeB)
    }

    @Test
    fun `a user assigned to one factory never sees or touches another's`() {
        signIn(keeperOfA)
        assertThat(setup.getFactories().map { it.id }).containsExactly(factoryA)
        assertThat(production.listRecipes(null, null, PageRequest.of(0, 50)).items.map { it.id }).containsExactly(recipeA)
        failsWith { production.listRecipes(factoryB, null, PageRequest.of(0, 50)) }.isEqualTo(ErrorCode.LOCATION_ACCESS_DENIED)
        assertThat(production.totalCostForRecipes(listOf(recipeA)).map { it.recipe.id }).containsExactly(recipeA)
        failsWith { production.totalCostForRecipes(listOf(recipeB)) }.isEqualTo(ErrorCode.RECIPE_NOT_FOUND)
        failsWith { production.totalCostForRecipes(listOf(recipeA, recipeB)) }.isEqualTo(ErrorCode.RECIPE_NOT_FOUND)
        val rename = FactoryRequest(null, "Plant B (renamed)", null, null, null, null, null, null, null)
        failsWith { setup.updateFactory(factoryB, rename) }.isEqualTo(ErrorCode.FACTORY_NOT_FOUND)
        failsWith { setup.updateFactoryStatus(factoryB, "SUSPENDED") }.isEqualTo(ErrorCode.FACTORY_NOT_FOUND)
    }
}
