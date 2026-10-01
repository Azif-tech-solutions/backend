package com.zifiq.platform.numbering

import com.zifiq.platform.numbering.model.DocumentSequencesTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service

/**
 * Atomic per-tenant, per-document-type reference number generator backing document_sequences.
 * Every module that needs a reference number (RF-, DL-, WB-, INV-, QA-, FT-, ...) calls
 * [next] instead of re-implementing numbering — see docs/00-platform-foundation.md §6.1.
 *
 * Seeds the row at 0 (no-op if already present), then locks it with SELECT ... FOR UPDATE and
 * increments it in Kotlin before writing back — the row lock is what makes concurrent callers for
 * the same prefix serialize correctly within a transaction.
 */
@Service
class DocumentSequenceService(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun next(prefix: String): String = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId

        DocumentSequencesTable.insertIgnore {
            it[DocumentSequencesTable.tenantId] = tenantId
            it[DocumentSequencesTable.prefix] = prefix
            it[DocumentSequencesTable.nextValue] = 0
        }

        val currentValue = DocumentSequencesTable
            .select(DocumentSequencesTable.nextValue)
            .andWhere { DocumentSequencesTable.tenantId eq tenantId }
            .andWhere { DocumentSequencesTable.prefix eq prefix }
            .forUpdate()
            .single()[DocumentSequencesTable.nextValue]

        val newValue = currentValue + 1

        DocumentSequencesTable.update({
            (DocumentSequencesTable.tenantId eq tenantId) and (DocumentSequencesTable.prefix eq prefix)
        }) {
            it[DocumentSequencesTable.nextValue] = newValue
        }

        "$prefix-$newValue"
    }
}
