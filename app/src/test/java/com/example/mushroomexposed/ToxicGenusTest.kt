package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToxicGenusTest {

    @Test
    fun `genus is the first segment of the scientific name`() {
        assertEquals("Amanita", ToxicGenus.genus("Amanita_phalloides"))
        assertEquals("Clitocybe", ToxicGenus.genus("Clitocybe_phyllophila"))
        assertEquals("Xerocomellus", ToxicGenus.genus("Xerocomellus_chrysenteron"))
        assertEquals("", ToxicGenus.genus(""))
    }

    @Test
    fun `a genus with only poisonous members is a warning genus`() {
        // Ausschnitt aus dem echten Bestand: Amanita fuehrt 7 giftige und keine
        // essbare Art (der Rest der 22 Amanita-Zeilen steht auf "unbekannt").
        val verdicts = mapOf(
            "Amanita_phalloides" to "giftig",
            "Amanita_pantherina" to "giftig",
            "Amanita_ceciliae" to "unbekannt",
            "Amanita_excelsa" to "unbekannt",
        )
        assertEquals(setOf("Amanita"), ToxicGenus.riskyGenera(verdicts))
    }

    @Test
    fun `an edible member protects its whole genus`() {
        // Agaricus hat sieben essbare Arten -- eine einzige reicht, damit die
        // Gattung nicht als Warn-Gattung gilt, auch wenn ein Vertreter giftig ist.
        val verdicts = mapOf(
            "Agaricus_augustus" to "essbar",
            "Agaricus_campestris" to "essbar",
            "Agaricus_macrosporus" to "giftig",
        )
        assertTrue(ToxicGenus.riskyGenera(verdicts).isEmpty())
    }

    @Test
    fun `a single poisonous species does not make a warning genus`() {
        val verdicts = mapOf(
            "Boletus_edulis" to "essbar",
            "Boletus_satanas" to "giftig",
        )
        assertTrue(ToxicGenus.riskyGenera(verdicts).isEmpty())
    }

    @Test
    fun `the real label table yields exactly Amanita and Clitocybe`() {
        // Auszug der Gattungen, die im App-Bestand giftige Arten fuehren.
        val verdicts = mapOf(
            "Amanita_phalloides" to "giftig",
            "Amanita_pantherina" to "giftig",
            "Amanita_ceciliae" to "unbekannt",
            "Clitocybe_phyllophila" to "giftig",
            "Clitocybe_dealbata" to "giftig",
            "Clitocybe_odora" to "unbekannt",
            "Agaricus_augustus" to "essbar",
            "Cortinarius_orellanus" to "giftig",
            "Cortinarius_armillatus" to "giftig",
            "Cortinarius_violaceus" to "essbar",
        )
        assertEquals(setOf("Amanita", "Clitocybe"), ToxicGenus.riskyGenera(verdicts))
    }

    @Test
    fun `firstRisky finds the warning genus anywhere in the shortlist`() {
        val ranked = listOf("Agaricus_sylvaticus", "Amanita_ceciliae", "Amanita_phalloides")
        assertEquals("Amanita", ToxicGenus.firstRisky(ranked, setOf("Amanita", "Clitocybe")))
    }

    @Test
    fun `firstRisky returns null when no warning genus is ranked`() {
        val ranked = listOf("Agaricus_sylvaticus", "Boletus_edulis")
        assertNull(ToxicGenus.firstRisky(ranked, setOf("Amanita", "Clitocybe")))
    }
}
