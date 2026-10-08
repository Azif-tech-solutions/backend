package com.zifiq.platform.notifications.api

import com.zifiq.common.errors.ApiErrors
import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.common.paging.Paging
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.notifications.NotificationCategory
import com.zifiq.platform.notifications.model.UserNotificationsTable
import com.zifiq.platform.security.CurrentActorProvider
import com.zifiq.platform.tenancy.TenantScopedTransaction
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class NotificationView(
    val id: UUID,
    val category: String,
    val title: String,
    val body: String,
    val linkType: String?,
    val linkRef: String?,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class UnreadCount(val count: Long)

data class MarkedCount(val updated: Int)

/** The signed-in user's own in-app inbox (docs/03 §9.1). Every query is limited to the caller's rows. */
@Service
class NotificationInboxService(
    private val tenantScopedTransaction: TenantScopedTransaction,
    private val currentActorProvider: CurrentActorProvider,
) {
    fun list(unreadOnly: Boolean, category: NotificationCategory?, page: PageRequest): PageResult<NotificationView> =
        tenantScopedTransaction.execute {
            var condition = mine() and UserNotificationsTable.dismissedAt.isNull()
            if (unreadOnly) condition = condition and UserNotificationsTable.readAt.isNull()
            if (category != null) condition = condition and (UserNotificationsTable.category eq category.name)
            val query = UserNotificationsTable.selectAll().where(condition)
                .orderBy(UserNotificationsTable.createdAt to SortOrder.DESC, UserNotificationsTable.id to SortOrder.ASC)
            Paging.fetch(query, page, ::view)
        }

    fun unreadCount(): UnreadCount = tenantScopedTransaction.execute {
        UnreadCount(
            UserNotificationsTable.selectAll()
                .where { mine() and UserNotificationsTable.readAt.isNull() and UserNotificationsTable.dismissedAt.isNull() }
                .count(),
        )
    }

    fun markRead(id: UUID): NotificationView = tenantScopedTransaction.execute {
        UserNotificationsTable.update({ mine() and (UserNotificationsTable.id eq id) and UserNotificationsTable.readAt.isNull() }) {
            it[readAt] = Instant.now()
        }
        find(id)
    }

    fun markAllRead(): MarkedCount = tenantScopedTransaction.execute {
        MarkedCount(
            UserNotificationsTable.update({ mine() and UserNotificationsTable.readAt.isNull() and UserNotificationsTable.dismissedAt.isNull() }) {
                it[readAt] = Instant.now()
            },
        )
    }

    fun dismiss(id: UUID): NotificationView = tenantScopedTransaction.execute {
        val now = Instant.now()
        UserNotificationsTable.update({ mine() and (UserNotificationsTable.id eq id) and UserNotificationsTable.dismissedAt.isNull() }) {
            it[dismissedAt] = now
            it[readAt] = now
        }
        find(id)
    }

    private fun mine(): Op<Boolean> {
        val actor = currentActorProvider.current()
        return (UserNotificationsTable.tenantId eq actor.tenantId) and (UserNotificationsTable.userId eq actor.userId)
    }

    private fun find(id: UUID): NotificationView =
        UserNotificationsTable.selectAll().where { mine() and (UserNotificationsTable.id eq id) }.firstOrNull()?.let(::view)
            ?: throw ApiErrors.notificationNotFound()

    private fun view(row: ResultRow) = NotificationView(
        id = row[UserNotificationsTable.id].value,
        category = row[UserNotificationsTable.category],
        title = row[UserNotificationsTable.title],
        body = row[UserNotificationsTable.body],
        linkType = row[UserNotificationsTable.linkType],
        linkRef = row[UserNotificationsTable.linkRef],
        createdAt = row[UserNotificationsTable.createdAt],
        readAt = row[UserNotificationsTable.readAt],
    )
}

@Tag(name = "Notifications", description = "The signed-in user's in-app notification inbox")
interface NotificationInboxApi {
    @Operation(summary = "List my notifications (newest first, dismissed ones excluded)")
    fun list(unreadOnly: Boolean, category: NotificationCategory?, page: Int?, size: Int?): ApiEnvelope<PageResult<NotificationView>>

    @Operation(summary = "Count my unread notifications (for the header bell)")
    fun unreadCount(): ApiEnvelope<UnreadCount>

    @Operation(summary = "Mark one notification read")
    fun markRead(id: UUID): ApiEnvelope<NotificationView>

    @Operation(summary = "Mark all my notifications read")
    fun markAllRead(): ApiEnvelope<MarkedCount>

    @Operation(summary = "Dismiss a notification (hides it from the inbox)")
    fun dismiss(id: UUID): ApiEnvelope<NotificationView>
}

@RestController
@RequestMapping("/api/v1/me/notifications")
class NotificationInboxController(
    private val inboxService: NotificationInboxService,
) : NotificationInboxApi {
    @GetMapping
    override fun list(
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(required = false) category: NotificationCategory?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ApiEnvelope<PageResult<NotificationView>> = ApiEnvelope(inboxService.list(unreadOnly, category, PageRequest.of(page, size)))

    @GetMapping("/unread-count")
    override fun unreadCount(): ApiEnvelope<UnreadCount> = ApiEnvelope(inboxService.unreadCount())

    @PostMapping("/{id}/read")
    override fun markRead(@PathVariable id: UUID): ApiEnvelope<NotificationView> = ApiEnvelope(inboxService.markRead(id))

    @PostMapping("/read-all")
    override fun markAllRead(): ApiEnvelope<MarkedCount> = ApiEnvelope(inboxService.markAllRead())

    @PostMapping("/{id}/dismiss")
    override fun dismiss(@PathVariable id: UUID): ApiEnvelope<NotificationView> = ApiEnvelope(inboxService.dismiss(id))
}
