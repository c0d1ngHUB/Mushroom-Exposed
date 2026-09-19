package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Der Notfall-Wortlaut ist sicherheitsrelevant und darf sich nicht unbemerkt
 * aendern. Er ist an drei Stellen hinterlegt und muss dort wortgleich bleiben:
 * als Konstante im Code (gepinnt), als Ressource fuer Vorlesehilfen und als
 * Zusammensetzung aus den Teilen, aus denen die Anruf-Flaechen gebaut werden.
 */
class EmergencyWordingTest {
    @Test
    fun `the pinned wording stays byte identical to the resource used by screen readers`() {
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        val resource = Regex("<string name=\"emergency_text\">(.*?)</string>")
            .find(strings)?.groupValues?.get(1)

        assertEquals(
            "R.string.emergency_text and ResultFormatter.EMERGENCY_TEXT must not drift apart",
            ResultFormatter.EMERGENCY_TEXT,
            resource,
        )
    }

    @Test
    fun `the parts still rebuild the pinned wording exactly`() {
        assertEquals(ResultFormatter.EMERGENCY_TEXT, emergencyTextFromParts())
    }

    @Test
    fun `the call targets are the numbers named in the pinned wording`() {
        val numbers = ResultFormatter.EMERGENCY_CONTACTS.map { it.number }
        assertEquals(listOf(ResultFormatter.POISON_CONTROL_NUMBER, ResultFormatter.EMERGENCY_NUMBER), numbers)
        for (number in numbers) {
            org.junit.Assert.assertTrue(
                "$number must appear in the pinned wording so the button cannot show a number nobody else knows",
                ResultFormatter.EMERGENCY_TEXT.contains(number),
            )
        }
    }
}
