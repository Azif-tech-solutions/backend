package com.zifiq.history.api

import com.zifiq.common.paging.PageRequest
import com.zifiq.common.paging.PageResult
import com.zifiq.history.dto.DeletedItemRow
import com.zifiq.history.dto.DeleteProductRequest
import com.zifiq.history.dto.PurgeRequest
import com.zifiq.history.dto.RecycleActionView
import com.zifiq.history.dto.RecycleNoteRequest
import com.zifiq.history.dto.RepriceAndResumeRequest
import com.zifiq.history.dto.ReviewRequest
import com.zifiq.history.dto.SuspendedItemRow
import com.zifiq.history.dto.SuspendProductRequest
import com.zifiq.history.service.RecycleBinService
import com.zifiq.platform.api.ApiEnvelope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val MANAGE = "@permissionAuthorizer.hasPermission(authentication, 'history.recycle-bin.manage')"
private const val PURGE = "@permissionAuthorizer.hasPermission(authentication, 'history.recycle-bin.purge')"

@Tag(name = "Audit — recycle bin", description = "Deleted and suspended products: restore, purge, resume, reprice (docs/10 §4.9)")
@SecurityRequirement(name = "bearerAuth")
interface RecycleBinApi {
    @Operation(summary = "Deleted products (in the bin, awaiting purge or restore)")
    fun deletedList(q: String?, page: Int?, size: Int?): ApiEnvelope<PageResult<DeletedItemRow>>

    @Operation(summary = "Delete a product into the Recycle Bin (blocked by reservations, in-transit transfers or open documents)")
    fun deleteProduct(request: DeleteProductRequest): ApiEnvelope<RecycleActionView>

    @Operation(summary = "Restore a deleted product")
    fun restore(stockItemId: UUID, request: RecycleNoteRequest): ApiEnvelope<RecycleActionView>

    @Operation(summary = "Purge a deleted product (irreversible; writes off any stock held)")
    fun purge(stockItemId: UUID, request: PurgeRequest): ApiEnvelope<RecycleActionView>

    @Operation(summary = "Products suspended from sale")
    fun suspendedList(q: String?, page: Int?, size: Int?): ApiEnvelope<PageResult<SuspendedItemRow>>

    @Operation(summary = "Suspend a product from sale")
    fun suspend(request: SuspendProductRequest): ApiEnvelope<RecycleActionView>

    @Operation(summary = "Resume a suspended product")
    fun resume(stockItemId: UUID, request: RecycleNoteRequest): ApiEnvelope<RecycleActionView>

    @Operation(summary = "Change a suspended product's review date")
    fun setReview(stockItemId: UUID, request: ReviewRequest): ApiEnvelope<RecycleActionView>

    @Operation(summary = "Publish a new price and resume, in one step")
    fun repriceAndResume(stockItemId: UUID, request: RepriceAndResumeRequest): ApiEnvelope<RecycleActionView>
}

@RestController
@RequestMapping("/api/v1/history/recycle-bin")
class RecycleBinController(private val recycleBin: RecycleBinService) : RecycleBinApi {
    @GetMapping("/deleted")
    @PreAuthorize(MANAGE)
    override fun deletedList(@RequestParam(required = false) q: String?, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?) =
        ApiEnvelope(recycleBin.deletedList(q, PageRequest.of(page, size)))

    @PostMapping("/deleted")
    @PreAuthorize(MANAGE)
    override fun deleteProduct(@Valid @RequestBody request: DeleteProductRequest) = ApiEnvelope(recycleBin.delete(request))

    @PostMapping("/deleted/{stockItemId}/restore")
    @PreAuthorize(MANAGE)
    override fun restore(@PathVariable stockItemId: UUID, @Valid @RequestBody request: RecycleNoteRequest) = ApiEnvelope(recycleBin.restore(stockItemId, request))

    @PostMapping("/deleted/{stockItemId}/purge")
    @PreAuthorize(PURGE)
    override fun purge(@PathVariable stockItemId: UUID, @Valid @RequestBody request: PurgeRequest) = ApiEnvelope(recycleBin.purge(stockItemId, request))

    @GetMapping("/suspended")
    @PreAuthorize(MANAGE)
    override fun suspendedList(@RequestParam(required = false) q: String?, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?) =
        ApiEnvelope(recycleBin.suspendedList(q, PageRequest.of(page, size)))

    @PostMapping("/suspended")
    @PreAuthorize(MANAGE)
    override fun suspend(@Valid @RequestBody request: SuspendProductRequest) = ApiEnvelope(recycleBin.suspend(request))

    @PostMapping("/suspended/{stockItemId}/resume")
    @PreAuthorize(MANAGE)
    override fun resume(@PathVariable stockItemId: UUID, @Valid @RequestBody request: RecycleNoteRequest) = ApiEnvelope(recycleBin.resume(stockItemId, request))

    @PutMapping("/suspended/{stockItemId}/review")
    @PreAuthorize(MANAGE)
    override fun setReview(@PathVariable stockItemId: UUID, @Valid @RequestBody request: ReviewRequest) = ApiEnvelope(recycleBin.setReview(stockItemId, request))

    @PostMapping("/suspended/{stockItemId}/reprice-and-resume")
    @PreAuthorize(MANAGE)
    override fun repriceAndResume(@PathVariable stockItemId: UUID, @Valid @RequestBody request: RepriceAndResumeRequest) = ApiEnvelope(recycleBin.repriceAndResume(stockItemId, request))
}
