package com.zifiq

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Standing rule (docs/03 §5.1.2): a response never carries a bare foreign key. A field that points at
 * another record is a `platform.directory.Ref` (`{ id, code, name, … }`), filled in by the response
 * resolver — so `stockItemId: UUID` in a response class fails the build. Request bodies keep plain
 * ids. The exceptions below are not references to show: each says why.
 */
class ResponseReferencesArchitectureTest {
    private val exceptions = mapOf(
        // The row's own identity, or a sub-row a client acts on (reverse a payment, edit a price, open a line).
        "PriceChange.priceId" to "the price row itself",
        "PriceListRow.priceId" to "the price row itself",
        "PriceHistoryRow.priceId" to "the price row itself",
        "RecordedPayment.paymentId" to "the payment just recorded (to reverse it)",
        "CreditNoteLineDetail.invoiceLineId" to "the credited line of the same invoice",
        "LedgerRow.reversedByEntryId" to "another row of the same ledger",
        "StockEntryLineRow.entryId" to "the entry the row belongs to; its ref is on the row",
        "SupplyPositionLine.saleLineId" to "the sale line a waybill releases against; the client sends it back",
        "StockEntryLineRow.lineId" to "the line itself",
        "ReturnLineRow.returnId" to "the return the row belongs to; its ref is on the row",
        "ReturnLineRow.lineId" to "the line itself",
        "TransferReportRow.lineId" to "the line itself",
        "DeliveryRow.invoiceId" to "each delivery row is the invoice itself (number, buyer and totals on the row)",
        // Polymorphic pointers that already carry their type and ref.
        "IssuedDocumentRow.sourceId" to "sourceModule/sourceType + ref describe it",
        "AuditReviewRow.recordId" to "the reviewed record across modules; recordType + recordRef describe it",
        "AuditReviewLookupRow.recordId" to "the reviewed record the caller already holds; recordType is the query",
        "StoredFileView.linkedId" to "linkedType describes it",
        "RunningCostView.payingAccountId" to "a cash or a bank account (method says which); payingAccount carries its code",
        // Session identity, external ids and one-time handles.
        "TenantRegistrationResponse.tenantId" to "the caller's new tenant",
        "TenantRegistrationResponse.userId" to "the caller",
        "TokenResponse.activeTenantId" to "the session's tenant",
        "TokenResponse.activeLocationId" to "the session's factory or showroom",
        "TokenResponse.userId" to "the caller",
        "MemberSummary.userId" to "the member row itself",
        "OtpChallengeSummary.challengeId" to "a one-time challenge handle",
        "OtpIssueResponse.challengeId" to "a one-time challenge handle",
        "CompanyProfileView.nrsBusinessId" to "an id issued by NRS, not a record here",
    )

    /** Response classes declared outside a dto package. */
    private val responsesElsewhere = setOf("IssuedDocumentRow", "RoleAssignmentSummary", "StoredFileView", "SavedView", "FileDownload")

    @Test
    fun `responses carry references, never bare foreign-key ids`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.zifiq")
        val violations = classes
            .filter { it.packageName.contains(".dto") || it.simpleName in responsesElsewhere }
            .filterNot(::isRequest)
            .flatMap { type ->
                type.fields
                    .filter { it.rawType.isEquivalentTo(UUID::class.java) && it.name.endsWith("Id") && it.name != "id" }
                    .map { "${type.simpleName}.${it.name}" }
            }
            .filterNot { it in exceptions }
        assertThat(violations).describedAs("bare foreign keys in responses: return a Ref (docs/03 §5.1.2)").isEmpty()
    }

    @Test
    fun `every exception still exists`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.zifiq")
        val present = classes.flatMap { type -> type.fields.map { "${type.simpleName}.${it.name}" } }.toSet()
        assertThat(exceptions.keys - present).describedAs("stale exceptions: remove them").isEmpty()
    }

    private fun isRequest(type: JavaClass) =
        type.simpleName.endsWith("Request") || type.simpleName.endsWith("Input") || type.simpleName.endsWith("Body") ||
            type.fields.any { field -> field.annotations.any { it.rawType.packageName.startsWith("jakarta.validation") } }
}
