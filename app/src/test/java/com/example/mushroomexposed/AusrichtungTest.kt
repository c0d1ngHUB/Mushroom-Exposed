package com.example.mushroomexposed

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI/UX-Review 2026-09-20, Finding 2. Im Querformat ueberschnitten sich
 * Qualitaetshinweis und Statusbereich, die Top-3 wurde abgeschnitten und der
 * Notfallblock war erst nach manuellem Scrollen erreichbar.
 *
 * Kurzfristig wird deshalb das Querformat gesperrt. Diese Tests halten die
 * Sperre fest, damit sie nicht still verschwindet: sobald jemand sie entfernt,
 * muss ein eigenes Landscape-Layout gebaut und der Notfallblock dauerhaft
 * sichtbar gehalten werden.
 */
class AusrichtungTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `the main activity is locked to portrait`() {
        val activityBlock = manifest.substringAfter("<activity").substringBefore("</activity>")

        assertTrue(
            "MainActivity must be locked to portrait until a dedicated landscape layout exists",
            "android:screenOrientation=\"portrait\"" in activityBlock,
        )
    }

    @Test
    fun `a portrait lock is declared for every activity`() {
        val activities = Regex("<activity[\\s\\S]*?>").findAll(manifest).map { it.value }.toList()

        assertTrue("the manifest must declare at least the main activity", activities.isNotEmpty())
        for (activity in activities) {
            assertTrue(
                "every activity needs the portrait lock, found: $activity",
                "android:screenOrientation=\"portrait\"" in activity,
            )
        }
    }
}
