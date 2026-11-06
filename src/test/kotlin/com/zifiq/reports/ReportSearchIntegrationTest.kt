package com.zifiq.reports

import com.zifiq.inventory.reports.StockRegisterReportProvider
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.reports.service.ReportSearchProvider
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Reports & Analytics slice 4e: the report catalogue is searchable from the global header, returning
 * only reports the caller can run, with a few name synonyms.
 */
class ReportSearchIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val reader = UUID.randomUUID()
        private val outsider = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$reader', 'rs-r-$reader@test.com', 'Reader'), ('$outsider', 'rs-o-$outsider@test.com', 'Outsider')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Search Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into tenant_memberships (tenant_id, user_id) values ('$tenantId', '$reader'), ('$tenantId', '$outsider')")
                grant(reader, "Stock Keeper", "'inventory.stock.read'")
                grant(outsider, "Clerk", "'customers.read'")
            }
        }

        private fun org.jetbrains.exposed.sql.Transaction.grant(user: UUID, roleName: String, permissionCodes: String) {
            val role = UUID.randomUUID()
            TransactionManager.current().exec("insert into tenant_roles (id, tenant_id, name, created_by_user_id) values ('$role', '$tenantId', '$roleName', '$reader')")
            TransactionManager.current().exec("insert into role_permissions (role_id, permission_id) select '$role', id from permission_catalog where code in ($permissionCodes)")
            TransactionManager.current().exec("insert into user_role_assignments (tenant_id, user_id, role_id, granted_by_user_id) values ('$tenantId', '$user', '$role', '$reader')")
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val permissions = PermissionAuthorizer(actors, tx)
    private val provider = StockRegisterReportProvider(actors, permissions)
    private val search = ReportSearchProvider(listOf(provider))

    private fun signIn(user: UUID) {
        tenantContext.set(tenantId)
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build())
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    @Test
    fun `finds a report the caller can run, directly and by synonym`() {
        signIn(reader)
        assertThat(search.search("stock register", 10).map { it.ref }).containsExactly("inventory.stock-register")
        assertThat(search.search("bin card", 10).map { it.ref }).containsExactly("inventory.stock-register")  // synonym → stock
        val hit = search.search("stock", 10).single()
        assertThat(hit.title).isEqualTo("Stock Register")
        assertThat(hit.route).isEqualTo("/inventory/stock-register")
    }

    @Test
    fun `returns nothing for a caller who cannot run the report`() {
        signIn(outsider)
        assertThat(search.search("stock register", 10)).isEmpty()
    }

    @Test
    fun `returns nothing when nothing matches`() {
        signIn(reader)
        assertThat(search.search("zzz-no-such-report", 10)).isEmpty()
    }
}
