package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX-Review 2026-09-20, Finding 1 und 5.
 *
 * Finding 1: neben einem Treffer darf kein Haken stehen, der wie eine Freigabe
 * wirkt — auch nicht im gelben Achtung-Zustand. Das giftige Zeichen bleibt.
 *
 * Finding 5: die Oberflaeche zeigt keine Dateinamen und keine unformatierten
 * Zeitstempel.
 */
class DarstellungsFormatTest {

    @Test
    fun `an edible hit carries no check mark`() {
        assertEquals(
            "an edible estimate must not be marked with a check",
            "",
            ResultFormatter.markOf("essbar"),
        )
    }

    @Test
    fun `a poisonous hit keeps its warning mark`() {
        assertEquals("☠", ResultFormatter.markOf("giftig"))
    }

    @Test
    fun `an unknown hit carries no mark`() {
        assertEquals("", ResultFormatter.markOf("unbekannt"))
    }

    @Test
    fun `no top row in a caution result shows a check next to a low confidence`() {
        // Der Review-Fall: gelber Achtung-Zustand mit 57 % und 19 % Kandidaten.
        val ranked = listOf(
            RankedSpecies("Wiesen-Champignon", "Agaricus_campestris", "essbar", 0.57f),
            RankedSpecies("Fahler Röhrling", "Boletus_pallidus", "essbar", 0.19f),
        )
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.57f))

        for (row in view.tops) {
            assertFalse(
                "no row may carry a release-style check: ${row.label} -> '${row.mark}'",
                row.mark.contains("✓"),
            )
        }
        for (line in view.topLines) {
            assertFalse("no top line may carry a check: $line", line.contains("✓"))
        }
    }

    @Test
    fun `a poisonous species in the shortlist keeps its own mark`() {
        val ranked = listOf(
            RankedSpecies("Steinpilz", "Boletus_edulis", "essbar", 0.71f),
            RankedSpecies("Schönfuß-Röhrling", "Boletus_calopus", "giftig", 0.12f),
        )
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals("", view.tops[0].mark)
        assertEquals("☠", view.tops[1].mark)
        assertTrue(view.tops[1].toxic)
    }

    @Test
    fun `species names lose their file-name underscores`() {
        assertEquals("Cantharellus cibarius", ResultFormatter.displayName("Cantharellus_cibarius"))
        assertEquals("Amanita phalloides", ResultFormatter.displayName("Amanita_phalloides"))
        assertEquals("", ResultFormatter.displayName(""))
    }

    @Test
    fun `the subline shows the readable species name`() {
        val ranked = listOf(
            RankedSpecies("Pfifferling", "Cantharellus_cibarius", "essbar", 0.71f),
        )
        val view = ResultFormatter.format(ranked, VerdictPolicy.decide("essbar", 0.71f))

        assertEquals("Cantharellus cibarius · 71 %", view.subline)
    }

    @Test
    fun `a stored timestamp is shown as a local date`() {
        assertEquals(
            "20.09.2026, 09:04",
            ResultFormatter.displayTimestamp("2026-09-20T09:04:06"),
        )
    }

    @Test
    fun `an unparsable timestamp falls through instead of vanishing`() {
        assertEquals("kaputt", ResultFormatter.displayTimestamp("kaputt"))
    }
}
