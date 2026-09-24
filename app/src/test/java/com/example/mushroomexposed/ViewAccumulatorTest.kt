package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewAccumulatorTest {
    private val acceptance = ViewAcceptance(minCoverage = 0.60f, minSharpness = 0.70f)

    private fun evidence(
        step: ViewStep,
        coverage: Float = 0.80f,
        sharpness: Float = 0.90f,
        frameId: Long = 1L,
    ) = ViewEvidence(step, coverage, sharpness, frameId)

    @Test
    fun `underside evidence completes only the underside step`() {
        val progress = ViewAccumulator(acceptance).accept(evidence(ViewStep.UNDERSIDE))

        assertTrue(progress.captured.contains(ViewStep.UNDERSIDE))
        assertFalse(progress.captured.contains(ViewStep.CAP))
        assertFalse(progress.captured.contains(ViewStep.STIPE_RING))
        assertEquals(ViewStep.CAP, progress.next)
        assertFalse(progress.complete)
    }

    @Test
    fun `evidence below either quality threshold does not progress`() {
        val accumulator = ViewAccumulator(acceptance)

        val lowCoverage = accumulator.accept(evidence(ViewStep.CAP, coverage = 0.59f))
        val lowSharpness = accumulator.accept(evidence(ViewStep.CAP, sharpness = 0.69f, frameId = 2L))

        assertEquals(emptySet<ViewStep>(), lowCoverage.captured)
        assertEquals(emptySet<ViewStep>(), lowSharpness.captured)
        assertEquals(ViewStep.CAP, lowSharpness.next)
    }

    @Test
    fun `weaker replacement cannot displace the best accepted frame`() {
        val accumulator = ViewAccumulator(acceptance)
        accumulator.accept(evidence(ViewStep.CAP, coverage = 0.90f, sharpness = 0.90f, frameId = 10L))

        accumulator.accept(evidence(ViewStep.CAP, coverage = 0.70f, sharpness = 0.80f, frameId = 11L))

        assertEquals(10L, accumulator.best(ViewStep.CAP)?.frameId)
    }

    @Test
    fun `stronger replacement becomes the selected frame`() {
        val accumulator = ViewAccumulator(acceptance)
        accumulator.accept(evidence(ViewStep.CAP, coverage = 0.70f, sharpness = 0.80f, frameId = 10L))

        accumulator.accept(evidence(ViewStep.CAP, coverage = 0.90f, sharpness = 0.90f, frameId = 11L))

        assertEquals(11L, accumulator.best(ViewStep.CAP)?.frameId)
    }

    @Test
    fun `all verified views mark progress complete`() {
        val accumulator = ViewAccumulator(acceptance)
        accumulator.accept(evidence(ViewStep.CAP, frameId = 1L))
        accumulator.accept(evidence(ViewStep.UNDERSIDE, frameId = 2L))

        val progress = accumulator.accept(evidence(ViewStep.STIPE_RING, frameId = 3L))

        assertEquals(setOf(ViewStep.CAP, ViewStep.UNDERSIDE, ViewStep.STIPE_RING), progress.captured)
        assertNull(progress.next)
        assertTrue(progress.complete)
    }

    @Test
    fun `cancel clears every temporary capture`() {
        val accumulator = ViewAccumulator(acceptance)
        accumulator.accept(evidence(ViewStep.CAP))
        accumulator.accept(evidence(ViewStep.UNDERSIDE, frameId = 2L))

        val progress = accumulator.cancel()

        assertEquals(emptySet<ViewStep>(), progress.captured)
        assertEquals(ViewStep.CAP, progress.next)
        assertNull(accumulator.best(ViewStep.CAP))
        assertNull(accumulator.best(ViewStep.UNDERSIDE))
    }
}
