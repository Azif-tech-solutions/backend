package com.zifiq.platform.documents

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.events.DomainEvents
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.model.TenantsTable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Component

/** Mirrors V9 (docs/03 §8.1). `search_vector` is generated and not mapped. */
object IssuedDocumentsTable : UUIDTable("issued_documents") {
    val tenantId = reference("tenant_id", TenantsTable)
    val ref = varchar("ref", 40)
    val docType = varchar("doc_type", 20)
    val sourceModule = varchar("source_module", 30)
    val sourceId = uuid("source_id")
    val locationId = uuid("location_id").nullable()
    val partyName = varchar("party_name", 200).nullable()
    val partyCustomerId = uuid("party_customer_id").nullable()
    val valueAmount = decimal("value_amount", 14, 2).nullable()
    val issuedByUserId = reference("issued_by_user_id", AppUsersTable)
    val issuedAt = timestamp("issued_at")
    val status = varchar("status", 20)
    val voidedAt = timestamp("voided_at").nullable()
    val reprintCount = integer("reprint_count")
}

object DocumentReprintsTable : UUIDTable("document_reprints") {
    val tenantId = reference("tenant_id", TenantsTable)
    val issuedDocumentId = reference("issued_document_id", IssuedDocumentsTable)
    val reprintedByUserId = reference("reprinted_by_user_id", AppUsersTable)
    val reprintedAt = timestamp("reprinted_at")
    val reason = varchar("reason", 200).nullable()
}

/** RECEIPT = an account payment receipt (RCT); SALE_RECEIPT = the itemised till receipt of a POS sale. */
enum class DocumentType { INVOICE, CREDIT_NOTE, WAYBILL, RECEIPT, SALE_RECEIPT, PRO_FORMA }

/**
 * Where a document's PDF lives. Each issuing module registers one (platform must not depend on
 * business modules); a document without a provider simply has no `pdfUrl` yet.
 */
interface DocumentLinkProvider {
    val sourceModule: String
    fun pdfUrl(docType: DocumentType, sourceId: UUID): String?
}

/**
 * The Transaction Receipts registry (docs/03 §8): every invoice, waybill, receipt and pro-forma is
 * recorded here by the module that issues it, in the same transaction as the document itself, so
 * one screen can list and reprint them all. Runs in the caller's transaction.
 */
@Component
class DocumentRegistry(
    private val tenantContext: TenantContext,
    private val currentActorProvider: CurrentActorProvider,
    private val domainEvents: DomainEvents,
) {
    fun register(
        ref: String,
        docType: DocumentType,
        sourceModule: String,
        sourceId: UUID,
        locationId: UUID?,
        partyName: String?,
        partyCustomerId: UUID? = null,
        valueAmount: BigDecimal? = null,
    ): UUID = IssuedDocumentsTable.insertAndGetId {
        it[tenantId] = tenantContext.requireTenantId()
        it[IssuedDocumentsTable.ref] = ref
        it[IssuedDocumentsTable.docType] = docType.name
        it[IssuedDocumentsTable.sourceModule] = sourceModule
        it[IssuedDocumentsTable.sourceId] = sourceId
        it[IssuedDocumentsTable.locationId] = locationId
        it[IssuedDocumentsTable.partyName] = partyName?.take(200)
        it[IssuedDocumentsTable.partyCustomerId] = partyCustomerId
        it[IssuedDocumentsTable.valueAmount] = valueAmount
        it[issuedByUserId] = currentActorProvider.current().userId
        it[issuedAt] = Instant.now()
        it[status] = "ISSUED"
        it[reprintCount] = 0
    }.value

    /** The document is kept (records are never deleted, docs/03 §13); it is shown as voided. */
    fun markVoided(ref: String) {
        val updated = IssuedDocumentsTable.update({
            (IssuedDocumentsTable.tenantId eq tenantContext.requireTenantId()) and (IssuedDocumentsTable.ref eq ref)
        }) {
            it[status] = "VOIDED"
            it[voidedAt] = Instant.now()
        }
        if (updated == 0) throw ApiErrors.documentNotFound()
    }

    /** True when the document has been printed before, so the next copy carries "DUPLICATE COPY". */
    fun isReprint(ref: String): Boolean =
        (find(ref)?.get(IssuedDocumentsTable.reprintCount) ?: 0) > 0

    fun find(ref: String) = IssuedDocumentsTable.selectAll()
        .where { (IssuedDocumentsTable.tenantId eq tenantContext.requireTenantId()) and (IssuedDocumentsTable.ref eq ref) }
        .firstOrNull()

    /** Records a reprint and returns the new reprint count. */
    fun recordReprint(ref: String, reason: String?): Int {
        val document = IssuedDocumentsTable.selectAll()
            .where { (IssuedDocumentsTable.tenantId eq tenantContext.requireTenantId()) and (IssuedDocumentsTable.ref eq ref) }
            .forUpdate()
            .firstOrNull() ?: throw ApiErrors.documentNotFound()
        val id = document[IssuedDocumentsTable.id].value
        IssuedDocumentsTable.update({ IssuedDocumentsTable.id eq id }) { it[reprintCount] = reprintCount + 1 }
        DocumentReprintsTable.insert {
            it[tenantId] = tenantContext.requireTenantId()
            it[issuedDocumentId] = id
            it[reprintedByUserId] = currentActorProvider.current().userId
            it[reprintedAt] = Instant.now()
            it[DocumentReprintsTable.reason] = reason?.trim()?.ifEmpty { null }
        }
        val count = document[IssuedDocumentsTable.reprintCount] + 1
        domainEvents.record(
            type = "platform.document.reprinted",
            aggregateType = "issued-document",
            aggregateId = id,
            aggregateRef = ref,
            summary = "$ref reprinted (copy ${count + 1})" + (reason?.trim()?.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: ""),
            payload = mapOf("docType" to document[IssuedDocumentsTable.docType], "reprintCount" to count),
            locationId = document[IssuedDocumentsTable.locationId],
        )
        return count
    }
}
