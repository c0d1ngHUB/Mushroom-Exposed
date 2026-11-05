package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewAccumulatorTest {
    private val acceptance = ViewAcceptance(
        minCoverageByStep = mapOf(
            ViewStep.CAP to 0.60f,
            ViewStep.UNDERSIDE to 0.60f,
            ViewStep.STIPE_RING to 0.60f,
        ),
        minSharpness = 0.70f,
        dominanceFactorByStep = mapOf(
            ViewStep.CAP to 0f,
            ViewStep.UNDERSIDE to 0f,
            ViewStep.STIPE_RING to 0f,
        ),
    )

    /**
     * v2-Akzeptanz mit gemessenen per-channel Punkten. Der Accumulator muss
     * für jede Ansicht den kleinsten zugehörigen Kanalbereich als
     * Mindestabdeckung verwenden, ohne den bereits qualifizierten Kanal
     * abzulehnen.
     */
    private val v2Acceptance = ViewAcceptance(
        minCoverageByStep = mapOf(
            ViewStep.CAP to 0.002f,
            ViewStep.UNDERSIDE to 0.003f,
            ViewStep.STIPE_RING to 0.001f,
        ),
        minSharpness = 0.05f,
        dominanceFactorByStep = mapOf(
            ViewStep.CAP to 1.0f,
            ViewStep.UNDERSIDE to 0f,
            ViewStep.STIPE_RING to 0f,
        ),
        channelPoints = mapOf(
            ViewpointChannel.CAP to ChannelOperatingPoint(0.3f, 0.002f),
            ViewpointChannel.GILLS to ChannelOperatingPoint(0.5f, 0.01f),
            ViewpointChannel.PORES to ChannelOperatingPoint(0.5f, 0.003f),
            ViewpointChannel.STIPE to ChannelOperatingPoint(0.3f, 0.001f),
            ViewpointChannel.RING to ChannelOperatingPoint(0.3f, 0.001f),
        ),
    )

    private fun evidence(
        step: ViewStep,
        coverage: Float = 0.80f,
        sharpness: Float = 0.90f,
        frameId: Long = 1L,
        competingCoverage: Float = 0f,
    ) = ViewEvidence(step, coverage, sharpness, frameId, competingCoverage)

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
    fun `the coverage floor applies per view, not globally`() {
        // Der Grund für den Umbau: `stipe_ring` wird vom Modell schwächer
        // vorhergesagt als `cap` und braucht deshalb eine eigene, lockerere
        // Grenze. Ein gemeinsamer Boden ließe entweder cap-Fehlalarme durch
        // oder erkannte stipe_ring-Frames fallen.
        val perView = ViewAcceptance(
            minCoverageByStep = mapOf(
                ViewStep.CAP to 0.20f,
                ViewStep.UNDERSIDE to 0.10f,
                ViewStep.STIPE_RING to 0.005f,
            ),
            minSharpness = 0.70f,
            dominanceFactorByStep = mapOf(
                ViewStep.CAP to 0f,
                ViewStep.UNDERSIDE to 0f,
                ViewStep.STIPE_RING to 0f,
            ),
        )
        val accumulator = ViewAccumulator(perView)

        // 0.15 liegt über der stipe_ring-Grenze, aber unter der cap-Grenze.
        val stipe = accumulator.accept(evidence(ViewStep.STIPE_RING, coverage = 0.15f, frameId = 1L))
        val cap = accumulator.accept(evidence(ViewStep.CAP, coverage = 0.15f, frameId = 2L))

        assertTrue(stipe.captured.contains(ViewStep.STIPE_RING))
        assertFalse(cap.captured.contains(ViewStep.CAP))
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

    @Test
    fun `a cap that does not dominate the underside is not evidence`() {
        // Der gemessene Fehlalarm-Fall: Hut 0,06, Lamellen 0,13. Der Hutkanal
        // findet den Pilz, nicht den Hut.
        val rule = ViewAcceptance(
            minCoverageByStep = mapOf(
                ViewStep.CAP to 0.001f,
                ViewStep.UNDERSIDE to 0.001f,
                ViewStep.STIPE_RING to 0.001f,
            ),
            minSharpness = 0.70f,
            dominanceFactorByStep = mapOf(
                ViewStep.CAP to 1.0f,
                ViewStep.UNDERSIDE to 0f,
                ViewStep.STIPE_RING to 0f,
            ),
        )
        val accumulator = ViewAccumulator(rule)

        val progress = accumulator.accept(
            evidence(ViewStep.CAP, coverage = 0.06f, competingCoverage = 0.13f),
        )

        assertFalse(progress.captured.contains(ViewStep.CAP))
    }

    @Test
    fun `a cap that reaches the underside is evidence`() {
        val rule = ViewAcceptance(
            minCoverageByStep = mapOf(
                ViewStep.CAP to 0.001f,
                ViewStep.UNDERSIDE to 0.001f,
                ViewStep.STIPE_RING to 0.001f,
            ),
            minSharpness = 0.70f,
            dominanceFactorByStep = mapOf(
                ViewStep.CAP to 1.0f,
                ViewStep.UNDERSIDE to 0f,
                ViewStep.STIPE_RING to 0f,
            ),
        )
        val accumulator = ViewAccumulator(rule)

        val progress = accumulator.accept(
            evidence(ViewStep.CAP, coverage = 0.20f, competingCoverage = 0.13f),
        )

        assertTrue(progress.captured.contains(ViewStep.CAP))
    }

    @Test
    fun `the dominance rule does not switch off the other views`() {
        // Unterseite und Stiel/Ring fahren Faktor 0: ihr eigener Nachweis darf
        // nicht an einem starken Hut scheitern.
        val rule = ViewAcceptance(
            minCoverageByStep = mapOf(
                ViewStep.CAP to 0.001f,
                ViewStep.UNDERSIDE to 0.001f,
                ViewStep.STIPE_RING to 0.001f,
            ),
            minSharpness = 0.70f,
            dominanceFactorByStep = mapOf(
                ViewStep.CAP to 1.0f,
                ViewStep.UNDERSIDE to 0f,
                ViewStep.STIPE_RING to 0f,
            ),
        )
        val accumulator = ViewAccumulator(rule)

        val progress = accumulator.accept(
            evidence(ViewStep.UNDERSIDE, coverage = 0.05f, competingCoverage = 0.40f),
        )

        assertTrue(progress.captured.contains(ViewStep.UNDERSIDE))
    }

    @Test
    fun `v2 underside floor is the lower of gills and pores areas`() {
        val ok = ViewAccumulator(v2Acceptance).accept(evidence(ViewStep.UNDERSIDE, coverage = 0.004f, sharpness = 1f))
        val tooLow = ViewAccumulator(v2Acceptance).accept(evidence(ViewStep.UNDERSIDE, coverage = 0.002f, sharpness = 1f))

        assertTrue(ok.captured.contains(ViewStep.UNDERSIDE))
        assertFalse(tooLow.captured.contains(ViewStep.UNDERSIDE))
    }

    @Test
    fun `v2 stipe ring floor is the lower of stipe and ring areas`() {
        val ok = ViewAccumulator(v2Acceptance).accept(evidence(ViewStep.STIPE_RING, coverage = 0.0015f, sharpness = 1f))
        val tooLow = ViewAccumulator(v2Acceptance).accept(evidence(ViewStep.STIPE_RING, coverage = 0.0005f, sharpness = 1f))

        assertTrue(ok.captured.contains(ViewStep.STIPE_RING))
        assertFalse(tooLow.captured.contains(ViewStep.STIPE_RING))
    }

    @Test
    fun `v2 already qualified channel is not rejected by view floor`() {
        // Der Poren-Kanal qualifiziert mit 0.003; der gemeinsame
        // Unterseite-Boden ist 0.003, der Poren-Boden auch 0.003. Gleichheit
        // zählt als Treffer.
        val accumulator = ViewAccumulator(v2Acceptance)

        val progress = accumulator.accept(evidence(ViewStep.UNDERSIDE, coverage = 0.003f, sharpness = 1f))

        assertTrue(progress.captured.contains(ViewStep.UNDERSIDE))
    }

    @Test
    fun `an acceptance without a dominance factor for every view is refused`() {
        // Der Konstruktor ist die Sicherung: ein Vertrag ohne die Regel fuer
        // eine Ansicht laesst die App nicht raten.
        val thrown = runCatching {
            ViewAcceptance(
                minCoverageByStep = mapOf(
                    ViewStep.CAP to 0.05f,
                    ViewStep.UNDERSIDE to 0.05f,
                    ViewStep.STIPE_RING to 0.05f,
                ),
                minSharpness = 0.05f,
                dominanceFactorByStep = mapOf(ViewStep.CAP to 1.0f),
            )
        }.isFailure

        assertTrue(thrown)
    }
}
