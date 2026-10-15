package com.zifiq.platform

import com.zifiq.common.paging.PageResult
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.directory.UserRef
import com.zifiq.platform.directory.UserRefResolver
import java.util.UUID
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Every user a response mentions comes back as { id, name, email }, however deeply it is nested. */
class UserRefResolverTest : PostgresIntegrationTest() {
    data class Row(val ref: String, val recordedBy: UserRef, val approvedBy: UserRef?)
    data class Detail(val owner: UserRef, val history: Map<String, List<Row>>)

    private val alice = UUID.randomUUID()
    private val bola = UUID.randomUUID()

    @RestController
    inner class Probe {
        @GetMapping("/page")
        fun page() = ApiEnvelope(PageResult(listOf(Row("R-1", UserRef(alice), UserRef(bola)), Row("R-2", UserRef(bola), null)), 0, 50, 2, 1, false))

        @GetMapping("/detail")
        fun detail() = ApiEnvelope(Detail(UserRef(alice), mapOf("approved" to listOf(Row("R-3", UserRef(bola), UserRef(alice))))))
    }

    private val mvc by lazy {
        transaction(database) {
            exec("insert into app_users (id, email, display_name) values ('$alice', 'alice-$alice@test.com', 'Alice Admin'), ('$bola', 'bola-$bola@test.com', 'Bola Cashier')")
        }
        MockMvcBuilders.standaloneSetup(Probe()).setControllerAdvice(UserRefResolver(database)).build()
    }

    @Test
    fun `user references in a page are filled in, and a missing one stays null`() {
        mvc.perform(get("/page"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].recordedBy.id").value(alice.toString()))
            .andExpect(jsonPath("$.data.items[0].recordedBy.name").value("Alice Admin"))
            .andExpect(jsonPath("$.data.items[0].recordedBy.email").value("alice-$alice@test.com"))
            .andExpect(jsonPath("$.data.items[0].approvedBy.name").value("Bola Cashier"))
            .andExpect(jsonPath("$.data.items[1].recordedBy.name").value("Bola Cashier"))
            .andExpect(jsonPath("$.data.items[1].approvedBy").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `user references nested in maps and lists are filled in`() {
        mvc.perform(get("/detail"))
            .andExpect(jsonPath("$.data.owner.name").value("Alice Admin"))
            .andExpect(jsonPath("$.data.history.approved[0].recordedBy.name").value("Bola Cashier"))
            .andExpect(jsonPath("$.data.history.approved[0].approvedBy.email").value("alice-$alice@test.com"))
    }
}
