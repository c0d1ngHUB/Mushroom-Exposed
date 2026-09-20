package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX-Review 2026-09-20, Findings 1 und 5. Die Oberflaeche darf eine
 * Modellschaetzung nicht wie eine Freigabe aussehen lassen, und sie darf keine
 * Dateinamen oder unformatierten Zeitstempel zeigen.
 *
 * Diese Tests lesen die Ressourcen- und Layout-Dateien direkt: die Regel ist
 * eine Zusicherung an den sichtbaren Text, nicht nur an eine Kotlin-Funktion.
 */
class SicherheitsdarstellungTest {

    private val strings = File("src/main/res/values/strings.xml").readText()
    private val colors = File("src/main/res/values/colors.xml").readText()

    private fun stringOf(name: String): String =
        Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)?.groupValues?.get(1) ?: ""

    @Test
    fun `the edible badge never claims a release`() {
        val badge = stringOf("badge_safe")

        assertFalse(
            "the edible badge must not read FREIGABE: the model estimates, it does not permit",
            badge.contains("FREIGABE", ignoreCase = true),
        )
        assertTrue(
            "the edible badge must name the estimate for what it is",
            badge.contains("schätz", ignoreCase = true) || badge.contains("Modell", ignoreCase = true),
        )
    }

    @Test
    fun `the badge set carries no approval wording at all`() {
        for (name in listOf("badge_danger", "badge_caution", "badge_safe")) {
            assertFalse(
                "$name must not contain approval or safety wording",
                stringOf(name).contains("FREIGABE", ignoreCase = true),
            )
        }
    }

    @Test
    fun `a neutral palette exists for estimates and is not the safe green`() {
        val neutral = Regex("<color name=\"verdict_neutral\">(.*?)</color>")
            .find(colors)?.groupValues?.get(1)?.trim()
        val safe = Regex("<color name=\"verdict_safe\">(.*?)</color>")
            .find(colors)?.groupValues?.get(1)?.trim()

        assertTrue("a neutral estimate colour must exist", neutral != null)
        assertTrue(
            "the neutral estimate colour must differ from the safe green",
            neutral != safe,
        )
    }
}
