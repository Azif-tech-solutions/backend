package com.zifiq

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {
    @Test
    fun `modules do not depend on internal packages of other modules`() {
        ApplicationModules.of(ZifiqApplication::class.java).verify()
    }
}
