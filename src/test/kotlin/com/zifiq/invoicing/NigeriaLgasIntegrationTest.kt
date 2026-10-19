package com.zifiq.invoicing

import com.zifiq.invoicing.service.NrsReferenceDataService
import com.zifiq.platform.PostgresIntegrationTest
import com.zifiq.platform.tenancy.TenantContext
import com.zifiq.platform.tenancy.TenantScopedTransaction
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every state's LGAs are seeded (V14_11): all 774 of the Constitution's First Schedule, under NRS's
 * own codes, with NRS's misspellings corrected and its two non-LGA entries left out.
 */
class NigeriaLgasIntegrationTest : PostgresIntegrationTest() {
    private val tx = TenantScopedTransaction(database, TenantContext())
    private val reference = NrsReferenceDataService(tx)

    private fun <T> lookup(block: () -> T): T = tx.executeForTenant(UUID.randomUUID(), block)

    @Test
    fun `all 774 LGAs are seeded, each state with its constitutional count`() {
        val lgas = lookup { reference.listLgas(null) }
        assertThat(lgas).hasSize(774)
        assertThat(lgas.map { it.code }).doesNotHaveDuplicates()
        assertThat(lgas.groupingBy { it.stateCode }.eachCount()).isEqualTo(
            mapOf(
                "NG-AB" to 17, "NG-AD" to 21, "NG-AK" to 31, "NG-AN" to 21, "NG-BA" to 20, "NG-BY" to 8, "NG-BE" to 23,
                "NG-BO" to 27, "NG-CR" to 18, "NG-DE" to 25, "NG-EB" to 13, "NG-ED" to 18, "NG-EK" to 16, "NG-EN" to 17,
                "NG-FC" to 6, "NG-GO" to 11, "NG-IM" to 27, "NG-JI" to 27, "NG-KD" to 23, "NG-KN" to 44, "NG-KT" to 34,
                "NG-KE" to 21, "NG-KO" to 21, "NG-KW" to 16, "NG-LA" to 20, "NG-NA" to 13, "NG-NI" to 25, "NG-OG" to 20,
                "NG-ON" to 18, "NG-OS" to 30, "NG-OY" to 33, "NG-PL" to 17, "NG-RI" to 23, "NG-SO" to 23, "NG-TA" to 16,
                "NG-YO" to 17, "NG-ZA" to 14,
            ),
        )
        assertThat(lgas.map { it.stateCode }.toSet()).isEqualTo(lookup { reference.listStates() }.map { it.code }.toSet())
    }

    @Test
    fun `a state's LGAs come back under NRS codes with corrected names`() {
        val lagos = lookup { reference.listLgas("ng-la") }.associate { it.code to it.name }
        assertThat(lagos).hasSize(20).containsEntry("NG-LA-IKE", "Ikeja").containsEntry("NG-LA-EOS", "Eti-Osa")

        val abia = lookup { reference.listLgas("NG-AB") }.associate { it.code to it.name }
        assertThat(abia).hasSize(17)
            .containsEntry("NG-AB-ANO", "Aba North")
            .containsEntry("NG-AB-ISU", "Isuikwuato")
            .containsEntry("NG-AB-ONW", "Obi Ngwa")
            .containsEntry("NG-AB-UNO", "Umuahia North")

        assertThat(lookup { reference.listLgas("NG-OG") }.map { it.name }).contains("Yewa North", "Yewa South").doesNotContain("Egbado North")
        assertThat(lookup { reference.listLgas("NG-OY") }.map { it.code }).doesNotContain("NG-OY-ICE")
        assertThat(lookup { reference.listLgas("NG-YO") }.map { it.code }).doesNotContain("NG-YO-KA1")
    }
}
