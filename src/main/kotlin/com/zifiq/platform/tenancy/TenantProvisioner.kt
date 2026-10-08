package com.zifiq.platform.tenancy

import java.util.UUID

/**
 * Implemented by modules that seed default data for every new tenant (e.g. Customers' dealer
 * categories). Called inside the tenant-creation transaction, after the default roles exist and
 * with the RLS context already scoped to the new tenant, so a failure rolls the whole tenant back.
 * This keeps platform free of any dependency on business modules.
 */
interface TenantProvisioner {
    fun provision(tenantId: UUID, createdByUserId: UUID)
}
