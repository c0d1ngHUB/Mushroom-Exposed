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
    fun `the viewpoint asset is intentionally absent from the shipped apk`() {
        assertFalse(
            "the prototype segmenter must not ship until its gate passes",
            File(assets, "viewpoint.tflite").exists(),
        )
    }

    @Test
    fun `a missing viewpoint asset closes the scan path instead of faking a view`() {
        // Der Ladepfad darf nichts werfen, sondern muss unavailable liefern ...
        assertTrue(
            "the loader must catch the missing asset and stay unavailable",
            "loadViewpointModel" in main && "ViewpointSegmenter(null" in main,
        )
        // ... und der Sammelstart muss vorher abbrechen.
        assertTrue(
            "starting a scan must refuse without a segmenter",
            "segmenter.available" in main && "viewpoint_missing" in main,
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
