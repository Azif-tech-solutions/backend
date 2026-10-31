package com.zifiq.factory.spi

import java.util.UUID

/** A Factory Setup vocabulary term (unit or category) for Audit's Error Handler (docs/10 §4.8). */
data class VocabularyTerm(val id: UUID, val code: String?, val name: String)

/** Read and correct product-vocabulary names (units, categories) owned by Factory Setup. */
interface FactoryVocabulary {
    fun units(): List<VocabularyTerm>
    fun categories(): List<VocabularyTerm>

    /** Rename a unit of measure (the data-quality correction path). */
    fun renameUnit(id: UUID, name: String)

    /** Rename a category; the name stays unique per tenant (409 DUPLICATE_NAME otherwise). */
    fun renameCategory(id: UUID, name: String)
}
