package com.zifiq.platform.identity

import com.zifiq.common.errors.ApiErrors
import com.zifiq.platform.identity.model.AppUsersTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import com.zifiq.platform.tenancy.model.TenantMembershipsTable
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.springframework.stereotype.Service

data class UserSummary(
    val id: UUID,
    val email: String,
    val displayName: String,
)

private fun rowToSummary(row: ResultRow) = UserSummary(
    id = row[AppUsersTable.id].value,
    email = row[AppUsersTable.email],
    displayName = row[AppUsersTable.displayName],
)

@Service
class UserDirectory(
    private val currentActorProvider: CurrentActorProvider,
    private val tenantScopedTransaction: TenantScopedTransaction,
) {
    fun getCurrentUser(): UserSummary {
        val actor = currentActorProvider.current()
        return findInCurrentTenant(actor.userId)
    }

    fun findInCurrentTenant(userId: UUID): UserSummary = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        AppUsersTable
            .join(TenantMembershipsTable, JoinType.INNER) { AppUsersTable.id eq TenantMembershipsTable.userId }
            .selectAll()
            .andWhere { AppUsersTable.id eq userId }
            .andWhere { TenantMembershipsTable.tenantId eq tenantId }
            .map(::rowToSummary)
            .firstOrNull() ?: throw ApiErrors.userNotFound()
    }

    fun listTenantMembers(): List<UserSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        AppUsersTable
            .join(TenantMembershipsTable, JoinType.INNER) { AppUsersTable.id eq TenantMembershipsTable.userId }
            .selectAll()
            .andWhere { TenantMembershipsTable.tenantId eq tenantId }
            .orderBy(AppUsersTable.displayName)
            .map(::rowToSummary)
    }

    fun searchTenantMembers(query: String): List<UserSummary> = tenantScopedTransaction.execute {
        val tenantId = currentActorProvider.current().tenantId
        val searchPattern = "%${query.trim().lowercase()}%"
        AppUsersTable
            .join(TenantMembershipsTable, JoinType.INNER) { AppUsersTable.id eq TenantMembershipsTable.userId }
            .selectAll()
            .andWhere { TenantMembershipsTable.tenantId eq tenantId }
            .andWhere {
                (AppUsersTable.displayName.lowerCase() like searchPattern) or
                    (AppUsersTable.email.lowerCase() like searchPattern)
            }
            .orderBy(AppUsersTable.displayName)
            .map(::rowToSummary)
    }
}
