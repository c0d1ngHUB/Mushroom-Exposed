package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hold-to-scan ersetzt den Einzelschuss: der Finger sammelt, nicht ein Klick.
 *
 * Die Fälle, die der Plan als Review-Fokus nennt, sind hier gepinnt:
 * Loslassen während des Sammelns darf keinen eingefrorenen Zustand und keine
 * Datei hinterlassen, ein fehlender Kameraframe muss zurück in LIVE führen.
 */
class FieldModeMachineTest {

    @Test
    fun `pressing starts one scan and releasing cancels it back to live`() {
        val machine = FieldModeMachine()

        assertEquals(FieldModeAction.START_SCAN, machine.onPrimaryDown())
        assertEquals(FieldMode.SCANNING, machine.state)
        assertTrue("collecting must accept quality and viewpoint updates", machine.acceptsQualityUpdates)

        assertEquals(FieldModeAction.STOP_SCAN, machine.onPrimaryUp())
        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }

    @Test
    fun `repeated press events during a scan are ignored`() {
        val machine = FieldModeMachine()
        machine.onPrimaryDown()

        assertEquals(FieldModeAction.IGNORE, machine.onPrimaryDown())
        assertEquals(FieldMode.SCANNING, machine.state)
    }

    @Test
    fun `release during analysis is ignored so the result cannot be cancelled away`() {
        val machine = FieldModeMachine()
        machine.onPrimaryDown()
        machine.onViewsComplete()

        assertEquals(FieldMode.ANALYSING, machine.state)
        assertFalse(machine.acceptsQualityUpdates)
        assertEquals(FieldModeAction.IGNORE, machine.onPrimaryUp())
        assertEquals(FieldMode.ANALYSING, machine.state)
    }

    @Test
    fun `a complete view set moves into analysis and then freezes`() {
        val machine = FieldModeMachine()
        machine.onPrimaryDown()

        machine.onViewsComplete()
        assertEquals(FieldMode.ANALYSING, machine.state)

        machine.onAnalysisFinished()
        assertEquals(FieldMode.FROZEN, machine.state)
        assertFalse(machine.acceptsQualityUpdates)
    }

    @Test
    fun `releasing in the frozen result keeps it on screen`() {
        val machine = frozenMachine()

        assertEquals(FieldModeAction.IGNORE, machine.onPrimaryUp())
        assertEquals(FieldMode.FROZEN, machine.state)
    }

    @Test
    fun `the explicit return action is the only way back from a frozen result`() {
        val machine = frozenMachine()

        assertEquals(FieldModeAction.RETURN_TO_LIVE, machine.onReturnToLive())
        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
        assertEquals("a second return is a no-op", FieldModeAction.IGNORE, machine.onReturnToLive())
    }

    @Test
    fun `a missing preview frame returns to live instead of hanging in a scan`() {
        val machine = FieldModeMachine()
        machine.onPrimaryDown()

        machine.onCaptureUnavailable()

        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }

    @Test
    fun `a lost analysis returns to live without a frozen result`() {
        val machine = FieldModeMachine()
        machine.onPrimaryDown()
        machine.onViewsComplete()

        machine.onCaptureUnavailable()

        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }

    private fun frozenMachine(): FieldModeMachine = FieldModeMachine().apply {
        onPrimaryDown()
        onViewsComplete()
        onAnalysisFinished()
    }
}
