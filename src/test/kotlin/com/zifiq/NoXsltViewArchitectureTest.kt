package com.zifiq

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Guards the CVE-2026-47884 exception in `.trivyignore`: that vulnerability is only exploitable
 * through Spring MVC's XsltView, so the exception is safe only while no code uses it. If this test
 * fails, remove the exception's justification first — upgrade Spring instead of rendering XSLT.
 */
class NoXsltViewArchitectureTest {
    @Test
    fun `no class uses Spring MVC XSLT views`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.zifiq")

        noClasses()
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web.servlet.view.xslt..")
            .because("CVE-2026-47884 is ignored in .trivyignore on the basis that XsltView is never used")
            .check(classes)
    }
}
