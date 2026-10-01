package com.zifiq.platform

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Shared base for integration tests that need a real Postgres instance with the actual Flyway
 * migrations applied — Exposed's DSL isn't interface-based, so platform/business services are
 * tested against a real (throwaway) database rather than a mocked JdbcTemplate. See
 * docs/00-platform-foundation.md §8.
 *
 * Connects through the non-superuser zifiq_app application role (created here, mirroring
 * docker/init/01-application-role.sql) so RLS policies are actually enforced, matching production.
 */
@Testcontainers
abstract class PostgresIntegrationTest {
    companion object {
        @JvmStatic
        protected val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("zifiq_test")
            .withUsername("zifiq_admin")
            .withPassword("zifiq-test")

        @JvmStatic
        protected lateinit var database: Database

        private lateinit var dataSource: HikariDataSource

        @JvmStatic
        @BeforeAll
        fun startContainerAndMigrate() {
            postgres.start()

            // Non-superuser application role, mirroring docker/init/01-application-role.sql, so
            // RLS (FORCE ROW LEVEL SECURITY) is actually exercised the same way it is in production.
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 1
                },
            ).use { admin ->
                admin.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("create role zifiq_app login password 'zifiq-test' nosuperuser nobypassrls")
                        statement.execute("grant all privileges on database ${postgres.databaseName} to zifiq_app")
                        statement.execute("grant all privileges on schema public to zifiq_app")
                        statement.execute("alter default privileges in schema public grant all privileges on tables to zifiq_app")
                    }
                }
            }

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()

            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = "zifiq_app"
                    password = "zifiq-test"
                    maximumPoolSize = 5
                },
            )
            database = Database.connect(dataSource)
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            if (::dataSource.isInitialized) dataSource.close()
            postgres.stop()
        }
    }
}
