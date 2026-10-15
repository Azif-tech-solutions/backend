package com.zifiq.platform.directory

import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.tenancy.model.LocationsTable
import java.util.UUID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList

/**
 * Display names for ids a list already holds, one query per kind (never one per row). Runs in the
 * caller's transaction; location reads are tenant-scoped by RLS.
 */
object Names {
    fun users(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else AppUsersTable.select(AppUsersTable.id, AppUsersTable.displayName).where { AppUsersTable.id inList ids.toSet() }
            .associate { it[AppUsersTable.id].value to it[AppUsersTable.displayName] }

    fun locations(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else LocationsTable.select(LocationsTable.id, LocationsTable.name).where { LocationsTable.id inList ids.toSet() }
            .associate { it[LocationsTable.id].value to it[LocationsTable.name] }
}
