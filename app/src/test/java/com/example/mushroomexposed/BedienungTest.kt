package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX-Review 2026-09-20, Finding 3 und 4.
 *
 * Finding 3: Die Steuergruppe klebte links, obwohl rechts viel Flaeche leer
 * blieb — der Ausloeser lag 73 dp neben der Bildschirmmitte.
 *
 * Finding 4: Der rote Loeschknopf im Verlauf war auch ohne Eintraege aktiv und
 * loeschte ohne Rueckfrage.
 */
class BedienungTest {

    private val layout = File("src/main/res/layout/activity_main.xml").readText()
    private val main = File("src/main/java/com/example/mushroomexposed/MainActivity.kt").readText()

    private fun blockOf(id: String, span: Int = 900): String {
        val at = layout.indexOf("android:id=\"@+id/$id\"")
        if (at < 0) return ""
        return layout.substring(maxOf(0, at - 200), minOf(layout.length, at + span))
    }

    @Test
    fun `the control group is centred horizontally`() {
        val controls = blockOf("controls")

        assertTrue("the control bar must exist", controls.isNotEmpty())
        assertTrue(
            "the control group must be pinned to both horizontal edges so it centres",
            "layout_constraintStart_toStartOf=\"parent\"" in controls &&
                "layout_constraintEnd_toEndOf=\"parent\"" in controls,
        )
        assertTrue(
            "the controls must centre their own children, not pack them left",
            "android:gravity=\"center_horizontal\"" in controls ||
                "android:gravity=\"center\"" in controls,
        )
    }

    @Test
    fun `the control bar must not be pinned to one side only`() {
        val controls = blockOf("controls")

        assertFalse(
            "a one-sided constraint is exactly the 73-dp-left bug",
            "layout_constraintStart_toStartOf=\"parent\"" in controls &&
                "layout_constraintEnd_toEndOf=\"parent\"" !in controls,
        )
    }

    @Test
    fun `clearing the history asks for confirmation`() {
        assertTrue(
            "a destructive clear must ask before it deletes",
            "history_clear_confirm_title" in main &&
                "history_clear_confirm_message" in main &&
                "history_clear_confirm_ok" in main,
        )
        assertTrue(
            "the confirmation must be a real dialog",
            "AlertDialog" in main,
        )
    }

    @Test
    fun `the clear button is disabled while the history is empty`() {
        assertTrue(
            "the clear button must be switched off when there is nothing to delete",
            "clearHistoryButton.isEnabled" in main,
        )
    }
}
