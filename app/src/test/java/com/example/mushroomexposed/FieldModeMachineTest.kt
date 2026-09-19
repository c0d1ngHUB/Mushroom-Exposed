package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldModeMachineTest {
    @Test
    fun `live primary action starts one analysis and suppresses quality`() {
        val machine = FieldModeMachine()

        assertEquals(FieldModeAction.CAPTURE, machine.onPrimaryAction())
        assertEquals(FieldMode.ANALYSING, machine.state)
        assertFalse(machine.acceptsQualityUpdates)
        assertEquals(FieldModeAction.IGNORE, machine.onPrimaryAction())
    }

    @Test
    fun `analysis completion freezes until primary action returns to live`() {
        val machine = FieldModeMachine()
        machine.onPrimaryAction()

        machine.onAnalysisFinished()

        assertEquals(FieldMode.FROZEN, machine.state)
        assertFalse(machine.acceptsQualityUpdates)
        assertEquals(FieldModeAction.RETURN_TO_LIVE, machine.onPrimaryAction())
        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }

    @Test
    fun `missing preview frame returns to live`() {
        val machine = FieldModeMachine()
        machine.onPrimaryAction()

        machine.onCaptureUnavailable()

        assertEquals(FieldMode.LIVE, machine.state)
        assertTrue(machine.acceptsQualityUpdates)
    }
}
