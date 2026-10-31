package com.zifiq.barcode

import com.zifiq.barcode.dto.AssignCodeRequest
import com.zifiq.barcode.dto.GenerateCodeRequest
import com.zifiq.barcode.dto.ReplacePrimaryRequest
import com.zifiq.barcode.dto.RetireCodeRequest
import com.zifiq.barcode.service.CodeManagementService
import com.zifiq.common.errors.ErrorCode
import com.zifiq.inventory.codes.BarcodeGenerator
import com.zifiq.inventory.codes.BarcodeSettings
import com.zifiq.inventory.codes.CheckDigits
import com.zifiq.inventory.codes.StockItemCodeService
import com.zifiq.inventory.model.StockItemsTable
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.api.ApiException
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.numbering.DocumentSequenceService
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.settings.PlatformSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.math.BigDecimal
import java.util.UUID
import org.assertj.core.api.AbstractObjectAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Barcode & QR slice 1: generated EAN-13s are valid, a code identifies exactly one product forever
 * (including retired ones), primary rules hold, and the primary mirrors to stock_items.barcode.
 */
class BarcodeCodesIntegrationTest : PostgresIntegrationTest() {
    companion object {
        private val tenantId = UUID.randomUUID()
        private val admin = UUID.randomUUID()
        private val factory = UUID.randomUUID()
        private val item = UUID.randomUUID()

        @JvmStatic
        @BeforeAll
        fun seed() {
            transaction(database) {
                exec("insert into app_users (id, email, display_name) values ('$admin', 'bc-$admin@test.com', 'Admin')")
                exec("insert into tenants (id, name) values ('$tenantId', 'Barcode Co')")
                exec("select set_config('app.tenant_id', '$tenantId', true)")
                exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$factory', '$tenantId', 'FACTORY', 'BC-F', 'Plant', '$admin')")
                val unit = UUID.randomUUID()
                exec("insert into units_of_measure (id, tenant_id, code, name) values ('$unit', '$tenantId', 'EA', 'Each')")
                exec(
                    "insert into stock_items (id, tenant_id, location_id, item_type, code, name, unit_of_measure_id, reorder_level, " +
                        "vat_category_id, hsn_section_id, isic_section_id, created_by_user_id, created_at) values " +
                        "('$item', '$tenantId', '$factory', 'RAW_MATERIAL', 'RM-06', 'Cocoa Powder', '$unit', 0, " +
                        "(select id from vat_categories where code = 'STANDARD_RATE'), " +
                        "(select id from hsn_sections where code = 'IV'), (select id from isic_sections where code = 'C'), " +
                        "'$admin', now())",
                )
            }
        }
    }

    private val tenantContext = TenantContext()
    private val tx = TenantScopedTransaction(database, tenantContext)
    private val actors = CurrentActorProvider(tenantContext)
    private val moduleSettings = ModuleSettings(listOf(PlatformSettings(), BarcodeSettings()), tenantContext)
    private val sequences = DocumentSequenceService(actors, tx)
    private val events = DomainEvents(tenantContext, actors)
    private val codeService = StockItemCodeService(actors, BarcodeGenerator(moduleSettings, sequences), events)
    private val codes = CodeManagementService(tx, codeService)

    private fun signIn() {
        tenantContext.set(tenantId)
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none").subject(admin.toString()).build())
    }

    @AfterEach
    fun signOut() {
        SecurityContextHolder.clearContext()
        tenantContext.clear()
    }

    private fun failsWith(block: () -> Any?): AbstractObjectAssert<*, *> =
        assertThatThrownBy { block() }.isInstanceOf(ApiException::class.java).extracting { (it as ApiException).code }

    private fun mirroredBarcode(): Pair<String?, String?> = tx.execute {
        val r = StockItemsTable.select(StockItemsTable.barcode, StockItemsTable.barcodeSymbology).where { StockItemsTable.id eq item }.single()
        r[StockItemsTable.barcode] to r[StockItemsTable.barcodeSymbology]
    }

    @Test
    fun `generate, alias, replace-primary and retire follow the code rules`() {
        signIn()
        // Generate the primary: a valid EAN-13 (restricted prefix 20) mirrored onto the stock item.
        val primary = codes.generate(item, GenerateCodeRequest(null))
        assertThat(primary.symbology).isEqualTo("EAN_13")
        assertThat(primary.isPrimary).isTrue()
        assertThat(CheckDigits.isValidGtin(primary.code)).isTrue()
        assertThat(primary.code).startsWith("20")
        assertThat(mirroredBarcode()).isEqualTo(primary.code to "EAN_13")

        // A second generate is refused — replace-primary is the path to change it.
        failsWith { codes.generate(item, GenerateCodeRequest(null)) }.isEqualTo(ErrorCode.PRIMARY_CODE_EXISTS)

        // A manufacturer code is assigned as an alias; a carton code multiplies quantity but can't be primary.
        val mfr = CheckDigits.withCheckDigit("590123412345")
        codes.assign(item, AssignCodeRequest(mfr, "EAN_13", "MANUFACTURER", isPrimary = false))
        val carton = CheckDigits.withCheckDigit("590123412346")
        codes.assign(item, AssignCodeRequest(carton, "EAN_13", "MANUFACTURER", isPrimary = false, packQuantity = BigDecimal(25), packLabel = "Pallet of 25"))
        failsWith { codes.assign(item, AssignCodeRequest(CheckDigits.withCheckDigit("590123412347"), "EAN_13", "MANUFACTURER", isPrimary = true, packQuantity = BigDecimal(25))) }
            .isEqualTo(ErrorCode.PRIMARY_MUST_BE_UNIT)

        // The same code can never point at two products (duplicates incl. the check-digit guard).
        failsWith { codes.assign(item, AssignCodeRequest(mfr, "EAN_13", "MANUFACTURER", isPrimary = false)) }.isEqualTo(ErrorCode.DUPLICATE_BARCODE)
        failsWith { codes.assign(item, AssignCodeRequest("5901234123450", "EAN_13", "MANUFACTURER", isPrimary = false)) }.isEqualTo(ErrorCode.INVALID_CHECK_DIGIT)

        // Promote the manufacturer code to primary: it mirrors, the old generated code stays an ACTIVE alias.
        val mfrRow = codes.list(item, null).single { it.code == mfr }
        val result = codes.replacePrimary(item, mfrRow.id, ReplacePrimaryRequest("manufacturer label preferred"))
        assertThat(result.reprintSuggested).isTrue()
        assertThat(mirroredBarcode().first).isEqualTo(mfr)
        assertThat(codes.list(item, "ACTIVE").count { it.isPrimary }).isEqualTo(1)

        // Retiring the primary without a replacement is refused; retiring an alias reserves the code forever.
        failsWith { codes.retire(item, mfrRow.id, RetireCodeRequest("stop using")) }.isEqualTo(ErrorCode.CANNOT_RETIRE_PRIMARY)
        codes.retire(item, cartonId(carton), RetireCodeRequest("pallets discontinued"))
        assertThat(codes.list(item, "RETIRED").map { it.code }).contains(carton)
        failsWith { codes.assign(item, AssignCodeRequest(carton, "EAN_13", "MANUFACTURER", isPrimary = false)) }.isEqualTo(ErrorCode.DUPLICATE_BARCODE)
    }

    private fun cartonId(code: String) = codes.list(item, null).single { it.code == code }.id
}
