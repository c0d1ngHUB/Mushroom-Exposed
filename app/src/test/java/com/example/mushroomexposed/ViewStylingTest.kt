package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Das Wald-Design hat die Ampel von reiner Textfarbe auf drei Werte je Urteil
 * umgestellt: kraeftige Marke, zarte Flaeche, dunkle Schrift. Der haeufigste
 * Fehler dabei ist Text in Markenfarbe auf der zarten Flaeche — genau das war
 * im ersten Mockup nicht lesbar. Diese Tests halten die Lesbarkeitsregel fest.
 */
class ViewStylingTest {

    private val dangerMark = 0xFFB3261E.toInt()
    private val dangerTint = 0xFFFBE9E7.toInt()
    private val dangerInk = 0xFF8F1D14.toInt()

    private val cautionMark = 0xFFA86400.toInt()
    private val cautionTint = 0xFFFBF0DA.toInt()
    private val cautionInk = 0xFF7A4A00.toInt()

    private val safeMark = 0xFF2E7D32.toInt()
    private val safeTint = 0xFFE7F2E4.toInt()
    private val safeInk = 0xFF1F6B32.toInt()

    @Test
    fun `the ink variant is legible on its own tint for every verdict`() {
        assertTrue(
            "danger ink on danger tint",
            ViewStyling.tintIsLegibleFor(dangerTint, dangerInk, dangerMark),
        )
        assertTrue(
            "caution ink on caution tint",
            ViewStyling.tintIsLegibleFor(cautionTint, cautionInk, cautionMark),
        )
        assertTrue(
            "safe ink on safe tint",
            ViewStyling.tintIsLegibleFor(safeTint, safeInk, safeMark),
        )
    }

    @Test
    fun `the mark colour alone would not carry body text on every tint`() {
        // Gemessen, nicht angenommen: auf der Achtung- und der Freigabe-Flaeche
        // verfehlt die Markenfarbe die Lesbarkeitsschwelle. Deshalb gibt es eine
        // eigene dunkle Schriftvariante statt "Markenfarbe ueberall".
        assertTrue(
            "caution mark on caution tint must stay below 4.5:1",
            ViewStyling.contrastRatio(cautionMark, cautionTint) < 4.5,
        )
        assertTrue(
            "safe mark on safe tint must stay below 4.5:1",
            ViewStyling.contrastRatio(safeMark, safeTint) < 4.5,
        )
        assertTrue(
            "danger mark on danger tint is the one case that does pass",
            ViewStyling.contrastRatio(dangerMark, dangerTint) >= 4.5,
        )
    }

    @Test
    fun `the ink variant always beats the mark colour on its own tint`() {
        assertTrue(ViewStyling.contrastRatio(dangerInk, dangerTint) > ViewStyling.contrastRatio(dangerMark, dangerTint))
        assertTrue(ViewStyling.contrastRatio(cautionInk, cautionTint) > ViewStyling.contrastRatio(cautionMark, cautionTint))
        assertTrue(ViewStyling.contrastRatio(safeInk, safeTint) > ViewStyling.contrastRatio(safeMark, safeTint))
    }

    @Test
    fun `ink and mark are actually different tones`() {
        assertNotEquals(dangerInk, dangerMark)
        assertNotEquals(cautionInk, cautionMark)
        assertNotEquals(safeInk, safeMark)
    }

    @Test
    fun `contrast ratio is symmetric and at least one for identical colours`() {
        assertEquals(1.0, ViewStyling.contrastRatio(safeMark, safeMark), 0.0001)
        assertEquals(
            ViewStyling.contrastRatio(safeInk, safeTint),
            ViewStyling.contrastRatio(safeTint, safeInk),
            0.0001,
        )
    }

    @Test
    fun `white on the call surfaces stays readable`() {
        val white = 0xFFFFFFFF.toInt()
        assertTrue("poison call surface", ViewStyling.contrastRatio(white, 0xFFC2311F.toInt()) >= 4.5)
        assertTrue("emergency call surface", ViewStyling.contrastRatio(white, 0xFF8F1D14.toInt()) >= 4.5)
    }
}
