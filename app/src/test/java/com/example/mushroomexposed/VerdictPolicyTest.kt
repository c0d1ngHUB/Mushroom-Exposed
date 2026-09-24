package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `edible result at high confidence remains caution until a gated model exists`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.99f)

        assertEquals("Verzehr nicht bewertbar", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Verzehr nicht bewerten"))
        assertTrue(decision.warning.contains("nie auf die App verlassen"))
    }

    @Test
    fun `edible result below the former threshold remains equally conservative`() {
        val decision = VerdictPolicy.decide("essbar", confidence = 0.42f)

        assertEquals("Verzehr nicht bewertbar", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Verzehr nicht bewerten"))
    }

    @Test
    fun `unknown result remains caution without a clearance`() {
        val decision = VerdictPolicy.decide(null, confidence = 0.99f)

        assertEquals("Verzehr nicht bewertbar", decision.headline)
        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Verzehr nicht bewerten"))
    }

    @Test
    fun `toxic alternative is stated on a non poisonous finding`() {
        val decision = VerdictPolicy.decide(
            verdict = "essbar",
            confidence = 0.95f,
            toxicAlternative = "Grüner Knollenblätterpilz",
        )

        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Grüner Knollenblätterpilz"))
    }

    @Test
    fun `warning genus is stated on a non poisonous finding`() {
        val decision = VerdictPolicy.decide(
            verdict = "unbekannt",
            confidence = 0.95f,
            toxicGenus = "Amanita",
        )

        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Amanita"))
    }

    @Test
    fun `dangerous lookalike remains stated on a non poisonous finding`() {
        val lookalike = Lookalike(
            kind = LookalikeKind.GEFAEHRLICH,
            key = "Amanita_phalloides",
            name = "Grüner Knollenblätterpilz",
            evidence = "Junge Fruchtkörper ähneln tödlich giftigen Knollenblätterpilzen.",
        )

        val decision = VerdictPolicy.decide("essbar", confidence = 0.95f, lookalike = lookalike)

        assertEquals(VerdictTone.CAUTION, decision.tone)
        assertTrue(decision.warning.contains("Grüner Knollenblätterpilz"))
        assertTrue(decision.warning.contains("Knollenblätterpilzen"))
    }

    @Test
    fun `toxic alternative never softens a poisonous verdict`() {
        val decision = VerdictPolicy.decide(
            verdict = "giftig",
            confidence = 0.99f,
            toxicAlternative = "Wiesen-Champignon",
        )

        assertEquals("GIFTIG !!", decision.headline)
        assertEquals(VerdictTone.DANGER, decision.tone)
        assertFalse(decision.warning.contains("Wiesen-Champignon"))
    }

    @Test
    fun `dangerous lookalike of a poisonous finding keeps the danger tone`() {
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
}
