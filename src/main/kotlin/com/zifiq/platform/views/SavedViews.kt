package com.zifiq.platform.views

import com.zifiq.platform.directory.UserRef
import com.fasterxml.jackson.databind.JsonNode
import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.json.Json
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.security.PermissionAuthorizer
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantsTable
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

object SavedViewsTable : UUIDTable("saved_views") {
    val tenantId = reference("tenant_id", TenantsTable)
    val listKey = varchar("list_key", 80)
    val name = varchar("name", 80)
    val ownerUserId = uuid("owner_user_id")
    val visibility = varchar("visibility", 10)
    val definition = jsonb<JsonNode>("definition", Json::write, Json::readTree)
    val isDefault = bool("is_default")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

enum class ViewVisibility { PRIVATE, SHARED }

data class SavedViewRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9.-]{2,79}$") val listKey: String,
    @field:NotBlank @field:Size(max = 80) val name: String,
    val visibility: ViewVisibility = ViewVisibility.PRIVATE,
    /** `{ q, filter, sort, columns, period }` — the same shapes the list's search endpoint accepts. */
    @field:NotNull val definition: JsonNode?,
)

data class SavedView(
    val id: UUID,
    val listKey: String,
    val name: String,
    val visibility: ViewVisibility,
    val definition: JsonNode,
    val isDefault: Boolean,
    val owner: UserRef,
    val mine: Boolean,
    val updatedAt: Instant,
)

/**
 * Saved list views (docs/03 §5.8). A view stores only filter/sort/column choices, never data or
 * scope: whoever applies a shared view still sees only what their own permissions and location
 * allow, and the list endpoint re-validates the definition every time it is used.
 */
@Service
class SavedViewService(
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val currentActorProvider: CurrentActorProvider,
    private val permissionAuthorizer: PermissionAuthorizer,
) {
    fun list(listKey: String?): List<SavedView> = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        var condition: Op<Boolean> = (SavedViewsTable.tenantId eq actor.tenantId) and
            ((SavedViewsTable.ownerUserId eq actor.userId) or (SavedViewsTable.visibility eq ViewVisibility.SHARED.name))
        if (listKey != null) condition = condition and (SavedViewsTable.listKey eq listKey)
        SavedViewsTable.selectAll().where(condition).orderBy(SavedViewsTable.listKey).orderBy(SavedViewsTable.name).map { view(it, actor.userId) }
    }

    fun create(request: SavedViewRequest): SavedView = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        checkDefinition(request.definition!!)
        checkSharing(request.visibility, actor.userId)
        ensureNameFree(actor.tenantId, actor.userId, request.listKey, request.name.trim(), exceptId = null)
        val id = SavedViewsTable.insertAndGetId {
                it[tenantId] = actor.tenantId
                it[listKey] = request.listKey
                it[name] = request.name.trim()
                it[ownerUserId] = actor.userId
                it[visibility] = request.visibility.name
                it[definition] = request.definition
                it[isDefault] = false
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }.value
        find(id, actor.userId)
    }

    fun update(id: UUID, request: SavedViewRequest): SavedView = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        checkDefinition(request.definition!!)
        checkSharing(request.visibility, actor.userId)
        ensureNameFree(actor.tenantId, actor.userId, request.listKey, request.name.trim(), exceptId = id)
        val updated = SavedViewsTable.update({ ownedBy(id, actor.tenantId, actor.userId) }) {
                it[listKey] = request.listKey
                it[name] = request.name.trim()
                it[visibility] = request.visibility.name
                it[definition] = request.definition
                it[updatedAt] = Instant.now()
            }
        if (updated == 0) throw ApiErrors.viewNotFound()
        find(id, actor.userId)
    }

    fun delete(id: UUID) = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        // A saved view is a personal preference, not a business record, so it is really deleted.
        if (SavedViewsTable.deleteWhere { ownedBy(id, actor.tenantId, actor.userId) } == 0) throw ApiErrors.viewNotFound()
    }

    /** Makes this the caller's default view for its list (one default per user and list). */
    fun makeDefault(id: UUID): SavedView = tenantScopedTransaction.execute {
        val actor = currentActorProvider.current()
        val target = SavedViewsTable.selectAll().where { ownedBy(id, actor.tenantId, actor.userId) }.firstOrNull()
            ?: throw ApiErrors.viewNotFound()
        SavedViewsTable.update({
            (SavedViewsTable.tenantId eq actor.tenantId) and (SavedViewsTable.ownerUserId eq actor.userId) and
                (SavedViewsTable.listKey eq target[SavedViewsTable.listKey])
        }) { it[isDefault] = false }
        SavedViewsTable.update({ SavedViewsTable.id eq id }) { it[isDefault] = true }
        find(id, actor.userId)
    }

    private fun ownedBy(id: UUID, tenantId: UUID, userId: UUID): Op<Boolean> =
        (SavedViewsTable.id eq id) and (SavedViewsTable.tenantId eq tenantId) and (SavedViewsTable.ownerUserId eq userId)

    private fun checkSharing(visibility: ViewVisibility, userId: UUID) {
        if (visibility == ViewVisibility.SHARED && !permissionAuthorizer.hasPermission(userId, "settings.views.share")) {
            throw ApiErrors.accessDenied()
        }
    }

    private fun checkDefinition(definition: JsonNode) {
        if (!definition.isObject) throw ApiErrors.validationFailed(mapOf("definition" to "must be a JSON object"))
        if (Json.write(definition).length > MAX_DEFINITION_CHARS) {
            throw ApiErrors.validationFailed(mapOf("definition" to "must be at most $MAX_DEFINITION_CHARS characters"))
        }
    }

    /** Names are unique per user and list (also enforced by a unique constraint). */
    private fun ensureNameFree(tenantId: UUID, userId: UUID, listKey: String, name: String, exceptId: UUID?) {
        val clash = SavedViewsTable.selectAll().where {
            (SavedViewsTable.tenantId eq tenantId) and (SavedViewsTable.ownerUserId eq userId) and
                (SavedViewsTable.listKey eq listKey) and (SavedViewsTable.name eq name)
        }.any { it[SavedViewsTable.id].value != exceptId }
        if (clash) throw ApiErrors.duplicateViewName()
    }

    private fun find(id: UUID, userId: UUID): SavedView =
        SavedViewsTable.selectAll().where { SavedViewsTable.id eq id }.single().let { view(it, userId) }

    private fun view(row: ResultRow, userId: UUID) = SavedView(
        id = row[SavedViewsTable.id].value,
        listKey = row[SavedViewsTable.listKey],
        name = row[SavedViewsTable.name],
        visibility = ViewVisibility.valueOf(row[SavedViewsTable.visibility]),
        definition = row[SavedViewsTable.definition],
        isDefault = row[SavedViewsTable.isDefault] && row[SavedViewsTable.ownerUserId] == userId,
        owner = UserRef(row[SavedViewsTable.ownerUserId]),
        mine = row[SavedViewsTable.ownerUserId] == userId,
        updatedAt = row[SavedViewsTable.updatedAt],
    )

    private companion object {
        const val MAX_DEFINITION_CHARS = 16_000
    }
}

@Tag(name = "Saved views", description = "Named filter/sort/column sets for list screens")
interface SavedViewsApi {
    @Operation(summary = "My saved views and views shared with the tenant (optionally for one list)")
    fun list(listKey: String?): ApiEnvelope<List<SavedView>>

    @Operation(summary = "Save a view")
    fun create(request: SavedViewRequest): ApiEnvelope<SavedView>

    @Operation(summary = "Update one of my views")
    fun update(id: UUID, request: SavedViewRequest): ApiEnvelope<SavedView>

    @Operation(summary = "Delete one of my views")
    fun delete(id: UUID)

    @Operation(summary = "Make one of my views my default for its list")
    fun makeDefault(id: UUID): ApiEnvelope<SavedView>
}

@RestController
@RequestMapping("/api/v1/views")
class SavedViewsController(private val service: SavedViewService) : SavedViewsApi {
    @GetMapping
    override fun list(@RequestParam(required = false) listKey: String?) = ApiEnvelope(service.list(listKey))

    @PostMapping
    override fun create(@Valid @RequestBody request: SavedViewRequest) = ApiEnvelope(service.create(request))

    @PutMapping("/{id}")
    override fun update(@PathVariable id: UUID, @Valid @RequestBody request: SavedViewRequest) = ApiEnvelope(service.update(id, request))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(@PathVariable id: UUID) = service.delete(id)

    @PostMapping("/{id}/default")
    override fun makeDefault(@PathVariable id: UUID) = ApiEnvelope(service.makeDefault(id))
}
