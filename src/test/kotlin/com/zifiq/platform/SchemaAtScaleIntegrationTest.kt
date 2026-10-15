package com.zifiq.platform

import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/**
 * Schema guarantees that only show at scale: per-tenant migration helpers reach every tenant, not
 * just the first batch a cursor fetches (V14_4) — they run as the application role, so row-level
 * security hides the other tenants once a loop sets app.tenant_id; and deleting a parent row never
 * scans a child table for references (V14_6).
 */
class SchemaAtScaleIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `granting a permission to default roles reaches every tenant`() {
        val user = UUID.randomUUID()
        val tenants = List(25) { UUID.randomUUID() }
        val code = "test.backfill.${UUID.randomUUID().toString().take(8)}"
        transaction(database) {
            exec("insert into app_users (id, email, display_name) values ('$user', 'backfill-$user@test.com', 'Backfill')")
            exec("insert into permission_catalog (code, description) values ('$code', 'Backfill test')")
            tenants.forEach { tenant ->
                exec("select set_config('app.tenant_id', '', true)")
                exec("insert into tenants (id, name) values ('$tenant', 'Backfill $tenant')")
                exec("select set_config('app.tenant_id', '$tenant', true)")
                exec("insert into tenant_roles (tenant_id, name, created_by_user_id) values ('$tenant', 'Administrator', '$user')")
            }
            exec("select set_config('app.tenant_id', '', true)")
        }

        transaction(database) { exec("select grant_permission_to_default_roles('$code', array['Administrator'])") { } }

        val granted = tenants.count { tenant ->
            transaction(database) {
                exec("select set_config('app.tenant_id', '$tenant', true)")
                TransactionManager.current().exec(
                    "select count(*) from role_permissions rp join tenant_roles r on r.id = rp.role_id join permission_catalog p on p.id = rp.permission_id " +
                        "where r.tenant_id = '$tenant' and p.code = '$code'",
                ) { rs -> rs.next(); rs.getLong(1) }!! == 1L
            }
        }
        assertThat(granted).isEqualTo(tenants.size)
    }

    @Test
    fun `every foreign key to a table whose rows get deleted has an index leading with it`() {
        // Deleting a parent row checks each referencing table by the foreign-key column alone; without
        // an index leading with it, that check scans the whole child table (V14_6).
        val deletable = listOf("invoices", "invoice_lines", "operating_costs", "module_settings", "saved_views")
        val unindexed = transaction(database) {
            TransactionManager.current().exec(
                "select c.conrelid::regclass || '.' || a.attname from pg_constraint c " +
                    // The referencing column is the key's last one (a tenant-scoped key is (tenant_id, x_id), V14_9);
                    // an index leading with it, or with (tenant_id, it), serves the delete's check.
                    "join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[array_length(c.conkey, 1)] " +
                    "where c.contype = 'f' and c.confrelid::regclass::text in (${deletable.joinToString { "'$it'" }}) " +
                    "and not exists (select 1 from pg_index i where i.indrelid = c.conrelid and (i.indkey[0] = a.attnum " +
                    "or (i.indkey[1] = a.attnum and i.indkey[0] = (select attnum from pg_attribute where attrelid = c.conrelid and attname = 'tenant_id'))))",
            ) { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }!!
        }
        assertThat(unindexed).isEmpty()
    }

    @Test
    fun `every reference between tenant records is tenant-scoped`() {
        // A single-column foreign key between two tenant tables would let a row point at another tenant's
        // record (foreign-key checks ignore row-level security). They are (tenant_id, x_id) keys (V14_9).
        val crossable = transaction(database) {
            TransactionManager.current().exec(
                "select c.conrelid::regclass || '.' || c.conname from pg_constraint c " +
                    "where c.contype = 'f' and array_length(c.conkey, 1) = 1 and c.confrelid <> 'tenants'::regclass " +
                    "and exists (select 1 from pg_attribute where attrelid = c.conrelid and attname = 'tenant_id' and not attisdropped) " +
                    "and exists (select 1 from pg_attribute where attrelid = c.confrelid and attname = 'tenant_id' and not attisdropped)",
            ) { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }!!
        }
        assertThat(crossable).describedAs("single-column foreign keys between tenant tables").isEmpty()
    }

    @Test
    fun `a row can never point at another tenant's record`() {
        val user = UUID.randomUUID()
        val tenantA = UUID.randomUUID()
        val tenantB = UUID.randomUUID()
        val showroomOfB = UUID.randomUUID()
        transaction(database) {
            exec("insert into app_users (id, email, display_name) values ('$user', 'iso-fk-$user@test.com', 'Iso')")
            exec("insert into tenants (id, name) values ('$tenantA', 'FK A'), ('$tenantB', 'FK B')")
            exec("select set_config('app.tenant_id', '$tenantB', true)")
            exec("insert into locations (id, tenant_id, type, code, name, created_by_user_id) values ('$showroomOfB', '$tenantB', 'SHOWROOM', 'FKB-1', 'B showroom', '$user')")
        }
        // Tenant A knows B's location id (say, guessed): pointing a cash account at it is refused by the database.
        val refused = runCatching {
            transaction(database) {
                exec("select set_config('app.tenant_id', '$tenantA', true)")
                exec("insert into cash_accounts (tenant_id, code, name, kind, location_id, opening_balance, created_by_user_id) " +
                    "values ('$tenantA', 'TILL-X', 'Cross till', 'TILL', '$showroomOfB', 0, '$user')")
            }
        }
        assertThat(refused.exceptionOrNull()?.message).contains("violates foreign key constraint")
    }
}
