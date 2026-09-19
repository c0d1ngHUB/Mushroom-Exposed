package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("Steinpilz", view.name)
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
    fun `tops carry rank, label and toxicity for the sheet rows`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals(3, view.tops.size)
        assertEquals(TopRow(1, "Steinpilz — 71 %", "✓", false), view.tops[0])
        assertEquals(TopRow(2, "Schönfuß-Röhrling — 12 %", "☠", true), view.tops[1])
        assertEquals(TopRow(3, "Fahler Röhrling — 0,8 %", "", false), view.tops[2])
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

    /**
     * Der Notfallblock zeigt die Nummern als eigene Anruf-Flaechen. Dieser Test
     * bindet sie an den gepinnten Satz: eine geaenderte Nummer, die nur in der
     * einen oder nur in der anderen Stelle landet, faellt hier auf.
     */
    @Test
    fun `the call surfaces are built from the pinned emergency wording`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("giftig", 0.12f))

        assertEquals(2, view.contacts.size)
        assertEquals("01 406 43 43", view.contacts[0].number)
        assertEquals("144", view.contacts[1].number)
        assertEquals(ResultFormatter.POISON_CONTROL_NUMBER, view.contacts[0].number)
        assertEquals(ResultFormatter.EMERGENCY_NUMBER, view.contacts[1].number)
    }

    @Test
    fun `reassembling the wording from its parts reproduces the pinned sentence`() {
        // Wenn Nummer, Titel oder Hinweis an einer Stelle wandern, bricht das hier.
        assertEquals(ResultFormatter.EMERGENCY_TEXT, emergencyTextFromParts())
        assertTrue(ResultFormatter.EMERGENCY_TEXT.contains(ResultFormatter.POISON_CONTROL_NUMBER))
        assertTrue(ResultFormatter.EMERGENCY_TEXT.contains(ResultFormatter.EMERGENCY_NUMBER))
        assertTrue(ResultFormatter.EMERGENCY_TEXT.contains(ResultFormatter.EMERGENCY_NOTE))
        assertTrue(ResultFormatter.EMERGENCY_TEXT.startsWith(ResultFormatter.EMERGENCY_TITLE))
    }

    @Test
    fun `a safe verdict carries no emergency contacts block`() {
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertNull(view.emergency)
    }

    @Test
    fun `empty ranking falls back to the policy headline`() {
        val view = ResultFormatter.format(emptyList(), VerdictPolicy.decide(null, 0f))

        assertEquals("unsicher", view.headline)
        assertEquals("", view.subline)
        assertEquals(emptyList<String>(), view.topLines)
        assertEquals(emptyList<TopRow>(), view.tops)
        assertEquals("", view.name)
    }
}
