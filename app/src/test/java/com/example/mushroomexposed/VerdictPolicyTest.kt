package com.example.mushroomexposed

import org.junit.Assert.assertEquals
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
}
