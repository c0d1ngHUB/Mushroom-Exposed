package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictPolicyTest {
    @Test
    fun `poisonous result remains danger even at low confidence`() {
        val decision = VerdictPolicy.decide("giftig", confidence = 0.01f)

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
        assertEquals("Nicht verzehren. Im Zweifel Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `edible result below threshold never grants eating approval`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.39f)

        assertEquals("unsicher — nicht essen", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertEquals("Zu unsicher für eine Freigabe — Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `edible result at threshold is cautiously marked edible`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.40f)

        assertEquals("essbar", decision.headline)
        assertEquals(VerdictTone.SAFE, decision.tone)
        assertEquals("Nur bei sicherer Bestimmung essen — nie auf App verlassen.", decision.warning)
    }

    @Test
    fun `unknown result remains caution at high confidence`() {
        val decision = VerdictPolicy.decide("unbekannt", confidence = 0.98f)

        assertEquals("nicht bewertet", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertEquals("Verzehr-Einschätzung unbekannt — Pilzberatung fragen.", decision.warning)
    }

    @Test
    fun `dangerous lookalike never yields a green edible verdict`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.GEFAEHRLICH,
            key = "Amanita_phalloides",
            name = "Grüner Knollenblätterpilz",
            evidence = "Junge Fruchtkörper ähneln tödlich giftigen Knollenblätterpilzen.",
        )

        val decision = VerdictPolicy.decide("essbar", confidence = 0.95f, lookalike = lookalike)

        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.startsWith("Nur bei sicherer Bestimmung essen"))
        assertTrue(decision.warning.contains("Grüner Knollenblätterpilz"))
        assertTrue(decision.warning.contains("Knollenblätterpilzen"))
    }

    @Test
    fun `attention lookalike keeps the tone and appends the hint`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.ACHTUNG,
            key = "Agaricus_xanthodermus",
            name = "Karbol-Champignon",
            evidence = "",
        )

        val decision = VerdictPolicy.decide("essbar", confidence = 0.80f, lookalike = lookalike)

        assertEquals(VerdictTone.SAFE, decision.tone)
        assertTrue(decision.warning.contains("Karbol-Champignon"))
    }

    @Test
    fun `dangerous lookalike of a poisonous find keeps the danger tone`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.GEFAEHRLICH,
            key = "Amanita_phalloides",
            name = "Grüner Knollenblätterpilz",
            evidence = "tödlich giftig",
        )

        val decision = VerdictPolicy.decide("giftig", confidence = 0.99f, lookalike = lookalike)

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
    }

    @Test
    fun `missing lookalike keeps the previous behaviour`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.80f, lookalike = null)

        assertEquals(VerdictTone.SAFE, decision.tone)
        assertEquals("Nur bei sicherer Bestimmung essen — nie auf App verlassen.", decision.warning)
    }
}
