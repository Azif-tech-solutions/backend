package com.zifiq.platform

import com.zifiq.common.errors.ErrorCode
import com.zifiq.common.json.Json
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.customfields.CustomFieldEntity
import com.zifiq.platform.customfields.CustomFieldValidator
import com.zifiq.platform.events.DomainEventRecord
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.events.EventConsumer
import com.zifiq.platform.events.EventConsumerRunner
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.settings.ModuleSettingProvider
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.settings.SettingDefinition
import com.zifiq.platform.settings.SettingType
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** Slice 1b of the platform foundation (docs/03 §7, §10, §12.2) against real PostgreSQL with RLS. */
class PlatformFoundationIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId: UUID = UUID.randomUUID()
        private val otherTenantId: UUID = UUID.randomUUID()
        private val userId: UUID = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$userId', 'pf-${userId}@test.com', 'PF User')")
                // Root tenants may be inserted with no tenant context (V3 policy).
                exec("insert into tenants (id, name) values ('$tenantId', 'Tenant A'), ('$otherTenantId', 'Tenant B')")
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val events = DomainEvents(tenantContext, CurrentActorProvider(tenantContext))

    private class Recording(override val name: String, override val eventTypes: Set<String>) : EventConsumer {
        val seen = mutableListOf<String>()
        override fun handle(event: DomainEventRecord) {
            seen += event.aggregateRef!!
        }
    }

    private fun record(tenant: UUID, ref: String, type: String = "test.thing.happened") =
        tx.executeForTenant(tenant) { events.record(type, "thing", UUID.randomUUID(), ref, "Thing $ref happened") }

    @Test
    fun `an event exists only if its transaction commits`() {
        runCatching {
            tx.executeForTenant(tenantId) {
                events.record("test.rollback", "thing", UUID.randomUUID(), "RB-1", "never committed")
                error("business failure after recording")
            }
        }
        val count = tx.executeForTenant(tenantId) {
            TransactionManager.current().exec("select count(*) from domain_events where aggregate_ref = 'RB-1'") { rs -> rs.next(); rs.getLong(1) }!!
        }
        assertThat(count).isZero()
    }

    @Test
    fun `consumers receive their event types in order, once, per tenant`() {
        record(tenantId, "A-1", "test.order")
        record(tenantId, "A-2", "test.other")
        record(tenantId, "A-3", "test.order")
        record(otherTenantId, "B-1", "test.order")

        val consumer = Recording("test.order-consumer", setOf("test.order"))
        val runner = EventConsumerRunner(database, tx, listOf(consumer))
        runner.drain(consumer, tenantId)
        runner.drain(consumer, tenantId) // nothing new: no duplicates
        assertThat(consumer.seen).containsExactly("A-1", "A-3")

        runner.drain(consumer, otherTenantId)
        assertThat(consumer.seen).containsExactly("A-1", "A-3", "B-1")
    }

    @Test
    fun `an event from a transaction still in progress is never skipped`() {
        val consumer = Recording("test.inflight-consumer", setOf("test.inflight"))
        val runner = EventConsumerRunner(database, tx, listOf(consumer))

        val firstRecorded = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        // Transaction 1 records first (lower txid and seq) but commits last.
        val slow = thread {
            val context = TenantContext()
            TenantScopedTransaction(database, context).executeForTenant(tenantId) {
                DomainEvents(context, CurrentActorProvider(context)).record("test.inflight", "thing", UUID.randomUUID(), "SLOW", "slow")
                firstRecorded.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
            }
        }
        firstRecorded.await(10, TimeUnit.SECONDS)
        record(tenantId, "FAST", "test.inflight") // commits immediately

        runner.drain(consumer, tenantId)
        assertThat(consumer.seen).describedAs("nothing may be delivered past an open transaction").isEmpty()

        releaseFirst.countDown()
        slow.join()
        runner.drain(consumer, tenantId)
        assertThat(consumer.seen).containsExactly("SLOW", "FAST")
    }

    @Test
    fun `module settings validate, persist per tenant and reset`() {
        val provider = object : ModuleSettingProvider {
            override fun settings() = listOf(
                SettingDefinition("test.threshold", "test", "Threshold", SettingType.DECIMAL, BigDecimal("50"), min = BigDecimal.ONE, max = BigDecimal("1000")),
                SettingDefinition("test.regions", "test", "Regions", SettingType.STRING_LIST, listOf("North", "South")),
            )
        }
        val settings = ModuleSettings(listOf(provider, PlatformSettings()), tenantContext)

        tx.executeForTenant(tenantId) {
            assertThat(settings.decimal("test.threshold")).isEqualByComparingTo("50")
            settings.set("test.threshold", Json.readTree("75.5"), userId)
            settings.set("test.regions", Json.readTree("""["Lagos Mainland", "Lagos Island"]"""), userId)
            assertThat(settings.decimal("test.threshold")).isEqualByComparingTo("75.5")
            assertThat(settings.stringList("test.regions")).containsExactly("Lagos Mainland", "Lagos Island")
            assertThat(settings.zone().id).isEqualTo("Africa/Lagos")

            assertThatThrownBy { settings.set("test.threshold", Json.readTree("0"), userId) }
                .extracting { (it as ApiException).code }.isEqualTo(ErrorCode.SETTING_VALUE_INVALID)
            assertThatThrownBy { settings.set("test.regions", Json.readTree("""["A", "a"]"""), userId) }
                .extracting { (it as ApiException).code }.isEqualTo(ErrorCode.SETTING_VALUE_INVALID)
            assertThatThrownBy { settings.set("test.unknown", Json.readTree("1"), userId) }
                .extracting { (it as ApiException).code }.isEqualTo(ErrorCode.SETTING_KEY_UNKNOWN)
        }
        // Tenant isolation: the other tenant still sees the default.
        tx.executeForTenant(otherTenantId) { assertThat(settings.decimal("test.threshold")).isEqualByComparingTo("50") }

        tx.executeForTenant(tenantId) {
            settings.reset("test.threshold")
            assertThat(settings.decimal("test.threshold")).isEqualByComparingTo("50")
        }
    }

    @Test
    fun `custom attributes are validated against the tenant's definitions`() {
        val validator = CustomFieldValidator(tenantContext)
        tx.executeForTenant(tenantId) {
            TransactionManager.current().exec(
                """
                insert into custom_field_definitions (tenant_id, entity_type, field_key, label, data_type, options, required, status, created_by_user_id)
                values ('$tenantId', 'CUSTOMER', 'trade_sector', 'Trade sector', 'SELECT', '["Hardware","Retail"]', true, 'ACTIVE', '$userId'),
                       ('$tenantId', 'CUSTOMER', 'staff_count', 'Staff count', 'NUMBER', null, false, 'ACTIVE', '$userId'),
                       ('$tenantId', 'CUSTOMER', 'legacy_ref', 'Legacy ref', 'TEXT', null, false, 'INACTIVE', '$userId')
                """.trimIndent(),
            )

            val stored = validator.validate(CustomFieldEntity.CUSTOMER, mapOf("trade_sector" to "hardware", "staff_count" to "12"))
            assertThat(stored).containsEntry("trade_sector", "Hardware").containsEntry("staff_count", BigDecimal("12"))

            fun code(block: () -> Unit) = assertThatThrownBy(block).extracting { (it as ApiException).code }
            code { validator.validate(CustomFieldEntity.CUSTOMER, mapOf("staff_count" to "12")) }.isEqualTo(ErrorCode.INVALID_CUSTOM_FIELD) // required missing
            code { validator.validate(CustomFieldEntity.CUSTOMER, mapOf("trade_sector" to "Farming")) }.isEqualTo(ErrorCode.INVALID_CUSTOM_FIELD) // not an option
            code { validator.validate(CustomFieldEntity.CUSTOMER, mapOf("trade_sector" to "Retail", "colour" to "red")) }.isEqualTo(ErrorCode.INVALID_CUSTOM_FIELD) // unknown
            code { validator.validate(CustomFieldEntity.CUSTOMER, mapOf("trade_sector" to "Retail", "legacy_ref" to "X9")) }.isEqualTo(ErrorCode.INVALID_CUSTOM_FIELD) // inactive change

            // An inactive field's existing value may stay as it was.
            val kept = validator.validate(CustomFieldEntity.CUSTOMER, mapOf("trade_sector" to "Retail"), previous = mapOf("legacy_ref" to "OLD-1"))
            assertThat(kept).containsEntry("legacy_ref", "OLD-1")
        }
        // Other tenants' definitions don't apply.
        tx.executeForTenant(otherTenantId) {
            assertThatThrownBy { validator.validate(CustomFieldEntity.CUSTOMER, mapOf("trade_sector" to "Retail")) }
                .extracting { (it as ApiException).code }.isEqualTo(ErrorCode.INVALID_CUSTOM_FIELD)
        }
    }
}
