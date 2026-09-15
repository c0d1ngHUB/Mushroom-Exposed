package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultFormatterTest {
    private val ranked = listOf(
        RankedSpecies("Steinpilz", "Boletus_edulis", "essbar", 0.71f),
        RankedSpecies("Schönfuß-Röhrling", "Boletus_calopus", "giftig", 0.12f),
        RankedSpecies("Fahler Röhrling", "Boletus_pallidus", "unbekannt", 0.008f),
    )

    @Test
    fun `headline carries the name and the subline the confidence`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals("essbar — Steinpilz", view.headline)
        assertEquals("Boletus_edulis · 71 %", view.subline)
        assertEquals(VerdictTone.SAFE, view.tone)
        assertNull(view.emergency)
    }

    @Test
    fun `top lines mark the verdict and format small confidences with one decimal`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals(
            listOf(
                "1. Steinpilz — 71 % ✓",
                "2. Schönfuß-Röhrling — 12 % ☠",
                "3. Fahler Röhrling — 0,8 %",
            ),
            view.topLines,
        )
    }

    @Test
    fun `caution and danger show the emergency block`() {
        val danger = ResultFormatter.format(ranked, VerdictPolicy.decide("giftig", 0.12f))
        val caution = ResultFormatter.format(ranked, VerdictPolicy.decide("unbekannt", 0.90f))

        assertEquals(VerdictTone.DANGER, danger.tone)
        assertEquals(ResultFormatter.EMERGENCY_TEXT, danger.emergency)
        assertEquals(ResultFormatter.EMERGENCY_TEXT, caution.emergency)
        assertEquals(
            "Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale 01 406 43 43 (24 h) " +
                "oder Notruf 144. Restpilz und Erbrochenes aufbewahren.",
            ResultFormatter.EMERGENCY_TEXT,
        )
    }

    @Test
    fun `empty ranking falls back to the policy headline`() {
        val view = ResultFormatter.format(emptyList(), VerdictPolicy.decide(null, 0f))

        assertEquals("unsicher", view.headline)
        assertEquals("", view.subline)
        assertEquals(emptyList<String>(), view.topLines)
    }
}
