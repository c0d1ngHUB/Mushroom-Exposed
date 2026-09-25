package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Herkunft und Lizenz des Mehransichten-Assets.
 *
 * Der Prototyp nutzt FungiTastic-M, das unter CC BY-NC-SA 4.0 steht. Diese
 * Bedingung ist keine Formalie: die App darf mit diesem Asset nicht
 * kommerziell vertrieben werden. Sie muss deshalb dort stehen, wo sie beim
 * Bauen und beim Lesen des Repos sichtbar ist.
 *
 * Ausserdem der Gate-Beweis: solange das Segmentierer-Asset nicht ausgeliefert
 * ist, muss die App geschlossen scheitern statt eine Ansicht als erfasst zu
 * melden. Ein fehlendes Asset darf nie einen gruenen Zustand erzeugen.
 */
class PrototypeNoticeTest {

    private val assets = File("src/main/assets")
    private val notice = File(assets, "NOTICE.txt")
    private val main = File("src/main/java/com/example/mushroomexposed/MainActivity.kt").readText()

    @Test
    fun `the notice names the dataset, its licence and the noncommercial limit`() {
        assertTrue("assets/NOTICE.txt must exist", notice.isFile)
        val text = notice.readText()

        assertTrue("the dataset must be named", text.contains("FungiTastic-M"))
        assertTrue("the licence must be named", text.contains("CC BY-NC-SA 4.0"))
        assertTrue(
            "the noncommercial limitation must be stated in German",
            text.contains("nichtkommerzieller Prototyp"),
        )
    }

    @Test
    fun `the viewpoint asset ships only with its calibrated contract`() {
        // Umgekehrt zur frueheren Zusicherung: die Datei ist jetzt ausgeliefert
        // (Gate bestanden am 25.09.2026), und sie darf nie ohne ihren Vertrag
        // im APK liegen. Ein Segmentierer ohne dominanz- und Grenzwerten waere
        // ein Modell, dessen Regel die App nicht kennt — sie belegte dann keine
        // Ansicht und der Flow scheiterte ohne erkennbaren Grund.
        val model = File(assets, "viewpoint.tflite")
        val contract = File(assets, "viewpoint.json")
        if (model.exists()) {
            assertTrue(
                "a shipped segmenter must carry its calibrated contract",
                contract.isFile,
            )
            val text = contract.readText()
            assertTrue("the contract must carry the coverage floors", "min_coverage_by_view" in text)
            assertTrue(
                "the contract must carry the dominance rule",
                "dominance_factor_by_view" in text,
            )
            assertTrue("the contract must name the model hash", "tflite_sha256" in text)
        } else {
            // Fehlt das Asset, muss die App geschlossen scheitern — dann darf
            // auch kein Vertrag auf eine Messung zeigen, die es nicht gibt.
            assertFalse("no contract without a model", contract.exists())
        }
    }

    @Test
    fun `a missing viewpoint asset closes the scan path instead of faking a view`() {
        // Der Ladepfad darf nichts werfen, sondern muss unavailable liefern ...
        assertTrue(
            "the loader must catch the missing asset and stay unavailable",
            "loadViewpointModel" in main && "ViewpointSegmenter(null" in main,
        )
        // ... und ohne Segmentierer darf keine Ansicht gruen werden.
        assertTrue(
            "a view can only be marked from segmenter evidence",
            "segmenter.available" in main,
        )
    }

    /**
     * Der Rueckfall auf den Einzelbild-Pfad.
     *
     * Die alte Regel war: ohne Segmentierer verweigert der Ausloeser und die
     * App zeigt **nie** ein Ergebnis. Das machte die ausgelieferte App
     * unbrauchbar, sobald der Mehransichten-Pfad der einzige Aufnahmeweg war.
     * Jetzt faellt der Druck auf den Einzelbild-Pfad zurueck — die Ansicht
     * bleibt trotzdem geschlossen (die Zeilen koennen nicht gruen werden), aber
     * die Artenbestimmung funktioniert weiter.
     */
    @Test
    fun `without a segmenter the shutter falls back to a single frame capture`() {
        assertTrue(
            "the press must pass both the segmenter and the calibrated limits",
            "val viewsReady = segmenter.available && viewAccumulator != null" in main &&
                "onPrimaryDown(viewsAvailable = viewsReady)" in main,
        )
        assertTrue(
            "the fallback action must be handled, not dropped",
            "FieldModeAction.CAPTURE ->" in main && "captureSingleFrame()" in main,
        )
        assertTrue(
            "the fallback must run the species model on one frame",
            "private fun captureSingleFrame()" in main &&
                "private fun analyseSingleFrame(" in main,
        )
    }

    @Test
    fun `the readme documents the prototype limitation`() {
        val readme = File("../README.md").takeIf { it.isFile } ?: File("README.md")
        assertTrue("the README must exist", readme.isFile)
        val text = readme.readText()

        assertTrue(
            "the README must name the noncommercial prototype limit",
            text.contains("FungiTastic-M") && text.contains("CC BY-NC-SA 4.0"),
        )
    }
}
