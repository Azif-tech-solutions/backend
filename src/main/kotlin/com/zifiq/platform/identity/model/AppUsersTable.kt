package com.zifiq.platform.identity.model

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

object AppUsersTable : UUIDTable("app_users") {
    val email = varchar("email", 320).uniqueIndex()
    val displayName = varchar("display_name", 160)
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestamp("created_at")
}
