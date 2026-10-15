package com.zifiq.settings.service

import com.zifiq.common.paging.PagingPolicy
import com.zifiq.platform.customfields.CustomFieldDefinitionsTable
import com.zifiq.platform.search.SearchHit
import com.zifiq.platform.search.SearchProvider
import com.zifiq.platform.settings.ModuleSettings
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.settings.dto.SettingsSearchHit
import java.text.Normalizer
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/** Accent- and case-insensitive word matching for the small, in-memory settings lists. */
internal object SettingsText {
    fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    fun words(q: String?): List<String> = q?.let(::normalise)?.split(Regex("[^\\p{L}\\p{N}]+"))?.filter { it.isNotEmpty() }.orEmpty()

    /** Every word appears somewhere in [fields] (no words = everything matches). */
    fun matches(words: List<String>, vararg fields: String?): Boolean {
        if (words.isEmpty()) return true
        val haystack = normalise(fields.filterNotNull().joinToString(" "))
        return words.all { it in haystack }
    }
}

/**
 * The Settings search box (docs/06 §9): settings screens, module settings and custom fields, so
 * typing "otp" jumps to the adjustment-threshold setting. Only what the caller may see is returned.
 */
@Service
class SettingsSearchService(
    private val tx: TenantScopedTransaction,
    private val tenantContext: TenantContext,
    private val moduleSettings: ModuleSettings,
    private val moduleSettingsService: ModuleSettingsService,
) {
    private data class Screen(val label: String, val keywords: String, val route: String, val permission: String?)

    fun search(q: String?, limit: Int = PagingPolicy.LOOKUP_DEFAULT): List<SettingsSearchHit> {
        val words = SettingsText.words(q)
        if (words.isEmpty()) return emptyList()
        return tx.execute {
            val visibility = moduleSettingsService.visibility()
            val screens = SCREENS
                .filter { it.permission == null || it.permission == "module-settings" || visibility.has(it.permission) }
                .filter { it.permission != "module-settings" || moduleSettings.definitions().any { d -> visibility.canSee(d.module) } }
                .filter { SettingsText.matches(words, it.label, it.keywords) }
                .map { SettingsSearchHit("screen", it.label, null, it.route) }
            val settings = moduleSettings.definitions()
                .filter { visibility.canSee(it.module) && SettingsText.matches(words, it.key, it.label, it.module) }
                .map { SettingsSearchHit("module-setting", it.label, it.key, "/settings/module-settings?key=${it.key}") }
            val fields = CustomFieldDefinitionsTable.selectAll()
                .where { CustomFieldDefinitionsTable.tenantId eq tenantContext.requireTenantId() }
                .filter { SettingsText.matches(words, it[CustomFieldDefinitionsTable.label], it[CustomFieldDefinitionsTable.fieldKey]) }
                .map { row ->
                    SettingsSearchHit(
                        type = "custom-field",
                        label = row[CustomFieldDefinitionsTable.label],
                        detail = "${row[CustomFieldDefinitionsTable.entityType]} · ${row[CustomFieldDefinitionsTable.dataType]}",
                        route = "/settings/custom-fields/${row[CustomFieldDefinitionsTable.id].value}",
                    )
                }
            (screens + settings + fields).take(limit)
        }
    }

    companion object {
        /** permission: null = any member; "module-settings" = can see at least one module's settings. */
        private val SCREENS = listOf(
            Screen("Company profile", "company name address phone email tin tax vat registration nrs footer financial year", "/settings/company", null),
            Screen("Company logo", "logo branding image", "/settings/company/logo", null),
            Screen("Onboarding", "setup wizard getting started branch factory", "/settings/onboarding", null),
            Screen("Module settings", "thresholds limits configuration options", "/settings/module-settings", "module-settings"),
            Screen("Custom fields", "field builder attributes stock item customer supplier", "/settings/custom-fields", null),
            Screen("VAT Control", "vat tax categories rates", "/settings/vat-control", "accounting.read"),
        )
    }
}

/** Global search provider `setting` (docs/06 §9). */
@Component
class SettingsSearchProvider(private val searchService: SettingsSearchService) : SearchProvider {
    override val type = "setting"
    override val label = "Settings"
    override val listRoute = "/settings/search"

    override fun canSearch() = true

    override fun search(q: String, limit: Int): List<SearchHit> = searchService.search(q, limit).map { hit ->
        SearchHit(
            type = type,
            id = UUID.nameUUIDFromBytes(hit.route.toByteArray()),
            title = hit.label,
            subtitle = hit.detail,
            ref = null,
            route = hit.route,
        )
    }
}
