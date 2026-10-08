package com.zifiq

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Guards the CVE-2026-47890 exception in `.trivyignore`: that vulnerability is only exploitable when
 * Server-Sent Events stream rendered view fragments, so the exception is safe only while no code
 * emits SSE or renders fragments. If this test fails, remove the exception first — upgrade Spring
 * (7.0.9+) instead of streaming SSE on 6.2.x.
 */
class NoSseFragmentsArchitectureTest {
    @Test
    fun `no class streams server-sent events or renders view fragments`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.zifiq")

        noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.servlet.mvc.method.annotation.SseEmitter")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.codec.ServerSentEvent")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.servlet.view.FragmentsRendering")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.web.reactive.result.view.FragmentsRendering")
            .because("CVE-2026-47890 is ignored in .trivyignore on the basis that SSE and view fragments are never used")
            .check(classes)
    }
}
