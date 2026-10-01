package com.zifiq.menu.api

import com.zifiq.menu.dto.*
import com.zifiq.platform.api.ApiEnvelope
import com.zifiq.platform.identity.dto.InviteMemberRequest
import com.zifiq.platform.identity.dto.MemberSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag

@Tag(name = "Menu Button & User Management", description = "Dealer Registration, POS Terminals, Debtors List & User Registration")
@SecurityRequirement(name = "bearerAuth")
interface MenuApi {
    @Operation(summary = "Register a dealer or distributor")
    fun registerDealer(request: DealerRegistrationRequest): ApiEnvelope<DealerRegistrationSummary>

    @Operation(summary = "List outstanding customer and dealer debt balances")
    fun getDebtorsList(): ApiEnvelope<List<DebtorItem>>

    @Operation(summary = "Create user and assign tenant role (thin pass-through to the platform member-invite flow)")
    fun registerUserAndAssignRole(request: InviteMemberRequest): ApiEnvelope<MemberSummary>
}
