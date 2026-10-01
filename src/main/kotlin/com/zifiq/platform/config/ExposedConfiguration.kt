package com.zifiq.platform.config

import org.jetbrains.exposed.sql.Database
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class ExposedConfiguration {
    @Bean
    fun exposedDatabase(dataSource: DataSource): Database = Database.connect(dataSource)
}
