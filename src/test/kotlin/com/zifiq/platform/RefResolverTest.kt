package com.zifiq.platform

import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.directory.Ref
import com.zifiq.platform.directory.RefResolver
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.hamcrest.Matchers.nullValue
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Every record a response points at comes back with its details (`{ id, code, name, … }`), however
 * deeply it is nested — one query per kind — and tenant records only ever under the request's own
 * tenant (row-level security).
 */
class RefResolverTest : PostgresIntegrationTest() {
    data class Row(val ref: String, val recordedBy: UserRef, val approvedBy: UserRef?, val location: Ref?)
    data class Detail(val owner: UserRef, val history: Map<String, List<Row>>)

    private val alice = UUID.randomUUID()
    private val bola = UUID.randomUUID()
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val ikeja = UUID.randomUUID()
    private val context = TenantContext()

    @RestController
    inner class Probe {
        @GetMapping("/page")
        fun page() = ApiEnvelope(
            PageResult(listOf(Row("R-1", UserRef(alice), UserRef(bola), Ref.location(ikeja)), Row("R-2", UserRef(bola), null, null)), 0, 50, 2, 1, false),
        )

        @GetMapping("/detail")
        fun detail() = ApiEnvelope(Detail(UserRef(alice), mapOf("approved" to listOf(Row("R-3", UserRef(bola), UserRef(alice), Ref.location(ikeja))))))
    }

    private val mvc by lazy {
        transaction(database) {
            exec("insert into app_users (id, email, display_name) values ('$alice', 'alice-$alice@test.com', 'Alice Admin'), ('$bola', 'bola-$bola@test.com', 'Bola Cashier')")
            exec("insert into tenants (id, name) values ('$tenantA', 'Ref A'), ('$tenantB', 'Ref B')")
            exec("select set_config('app.tenant_id', '$tenantA', true)")
            exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$ikeja', '$tenantA', 'SHOWROOM', 'SHR-RF', 'Showroom Ikeja', '$alice')")
        }
        MockMvcBuilders.standaloneSetup(Probe()).setControllerAdvice(RefResolver(database, context, TenantScopedTransaction(database, context), emptyList())).build()
    }

    @AfterEach
    fun clearTenant() = context.clear()

    @Test
    fun `references in a page are filled in, and a missing one stays null`() {
        context.set(tenantA)
        mvc.perform(get("/page"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].recordedBy.id").value(alice.toString()))
            .andExpect(jsonPath("$.data.items[0].recordedBy.name").value("Alice Admin"))
            .andExpect(jsonPath("$.data.items[0].recordedBy.email").value("alice-$alice@test.com"))
            .andExpect(jsonPath("$.data.items[0].approvedBy.name").value("Bola Cashier"))
            .andExpect(jsonPath("$.data.items[0].location.id").value(ikeja.toString()))
            .andExpect(jsonPath("$.data.items[0].location.code").value("SHR-RF"))
            .andExpect(jsonPath("$.data.items[0].location.name").value("Showroom Ikeja"))
            .andExpect(jsonPath("$.data.items[0].location.type").value("SHOWROOM"))
            .andExpect(jsonPath("$.data.items[1].approvedBy").value(nullValue()))
            .andExpect(jsonPath("$.data.items[1].location").value(nullValue()))
    }

    @Test
    fun `references nested in maps and lists are filled in`() {
        context.set(tenantA)
        mvc.perform(get("/detail"))
            .andExpect(jsonPath("$.data.owner.name").value("Alice Admin"))
            .andExpect(jsonPath("$.data.history.approved[0].recordedBy.name").value("Bola Cashier"))
            .andExpect(jsonPath("$.data.history.approved[0].approvedBy.email").value("alice-$alice@test.com"))
            .andExpect(jsonPath("$.data.history.approved[0].location.name").value("Showroom Ikeja"))
    }

    @Test
    fun `another tenant's record is never described - only its id is echoed`() {
        context.set(tenantB)
        mvc.perform(get("/page"))
            .andExpect(jsonPath("$.data.items[0].location.id").value(ikeja.toString()))
            .andExpect(jsonPath("$.data.items[0].location.name").value(nullValue()))
            .andExpect(jsonPath("$.data.items[0].location.code").value(nullValue()))
    }
}
