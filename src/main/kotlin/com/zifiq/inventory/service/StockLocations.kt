package com.zifiq.inventory.service

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.tenancy.model.LocationsTable
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Component

/** A location with its factory/showroom ("root"); for a factory or showroom the root is itself. */
data class StockLocation(
    val id: UUID,
    val type: String,
    val code: String,
    val name: String,
    val status: String,
    val areaKind: String?,
    val rootId: UUID,
    val rootType: String,
    val rootName: String,
    val rootStatus: String,
) {
    val isArea: Boolean get() = type == "STORAGE_AREA"

    /** "Showroom Ikeja" or "Showroom Ikeja — Warehouse". */
    val label: String get() = if (isArea) "$rootName — $name" else name
}

/**
 * Location status rules that the stock-moving modules enforce (docs/05 §4.1): a PENDING location
 * ("pending opening") can receive stock but cannot dispatch, sell or take returns; a SUSPENDED one —
 * or a deactivated storage area — moves nothing. An area follows its showroom's status too.
 * Runs in the caller's transaction.
 */
@Component
class StockLocations {
    private val parent = LocationsTable.alias("parent_location")

    fun get(id: UUID): StockLocation {
        val row = LocationsTable
            .join(parent, JoinType.LEFT, LocationsTable.parentLocationId, parent[LocationsTable.id])
            .selectAll()
            .where { LocationsTable.id eq id }
            .firstOrNull() ?: throw ApiErrors.invalidLocation("Location not found")
        return toLocation(row)
    }

    private fun toLocation(row: ResultRow): StockLocation {
        val hasParent = row[LocationsTable.parentLocationId] != null
        return StockLocation(
            id = row[LocationsTable.id].value,
            type = row[LocationsTable.type],
            code = row[LocationsTable.code],
            name = row[LocationsTable.name],
            status = row[LocationsTable.status],
            areaKind = row[LocationsTable.areaKind],
            rootId = if (hasParent) row[parent[LocationsTable.id]].value else row[LocationsTable.id].value,
            rootType = if (hasParent) row[parent[LocationsTable.type]] else row[LocationsTable.type],
            rootName = if (hasParent) row[parent[LocationsTable.name]] else row[LocationsTable.name],
            rootStatus = if (hasParent) row[parent[LocationsTable.status]] else row[LocationsTable.status],
        )
    }

    /** May stock arrive here? Allowed while PENDING; not when the location (or its showroom) is suspended. */
    fun requireCanReceive(id: UUID): StockLocation = get(id).also { location ->
        if (location.rootStatus == "SUSPENDED" || location.status == "SUSPENDED") throw ApiErrors.locationSuspended(location.label)
    }

    /** May stock leave here (dispatch, sale, return, adjustment out)? Needs an ACTIVE location. */
    fun requireTrading(id: UUID): StockLocation = requireCanReceive(id).also { location ->
        if (location.rootStatus == "PENDING") throw ApiErrors.locationNotTrading(location.rootName)
    }

    /** [areaOrRoot] must be [rootId] itself or one of its storage areas. */
    fun requireWithin(rootId: UUID, areaOrRoot: UUID, field: String): StockLocation {
        val location = get(areaOrRoot)
        if (location.rootId != rootId) throw ApiErrors.invalidLocation("$field must be the location itself or one of its storage areas")
        return location
    }
}
