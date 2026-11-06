package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Die Semantik der Evidenz: eine Ansicht ist nur Kandidat, wenn mindestens
 * einer ihrer eigenen Kanäle seine eigene Flächenschwelle erreicht. v1 bleibt
 * beim alten Verhalten.
 */
class ViewpointSegmenterTest {

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

    private val v1Acceptance = ViewAcceptance(
        minCoverageByStep = mapOf(
            ViewStep.CAP to 0.005f,
            ViewStep.UNDERSIDE to 0.01f,
            ViewStep.STIPE_RING to 0.005f,
        ),
        minSharpness = 0.05f,
        dominanceFactorByStep = mapOf(
            ViewStep.CAP to 0f,
            ViewStep.UNDERSIDE to 0f,
            ViewStep.STIPE_RING to 0f,
        ),
    )

    /**
     * Bauen eine Maske, deren Pixel den gewünschten Kanalanteil bei gegebenem
     * tau ergeben. Der Segmentierer zählt Pixel > tau; mit tau=0.5 sind das
     * genau die mit Wert 1.0.
     */
    private fun mask(coverage: Float, size: Int = 10000): FloatArray {
        val active = (coverage * size).toInt().coerceIn(0, size)
        return FloatArray(size) { index -> if (index < active) 0.75f else 0.25f }
    }

    private fun masks(
        cap: Float = 0f,
        gills: Float = 0f,
        pores: Float = 0f,
        stipe: Float = 0f,
        ring: Float = 0f,
    ): Array<FloatArray> {
        return Array(6) { index ->
            when (index) {
                ViewpointContract.CHANNEL_CAP -> mask(cap)
                ViewpointContract.CHANNEL_GILLS -> mask(gills)
                ViewpointContract.CHANNEL_PORES -> mask(pores)
                ViewpointContract.CHANNEL_STIPE -> mask(stipe)
                ViewpointContract.CHANNEL_RING -> mask(ring)
                else -> FloatArray(10000) { 0f }
            }
        }
    }

    @Test
    fun `v2 underside evidence is null when neither own channel qualifies`() {
        val segmenter = ViewpointSegmenter(null, 300, 300, 6, v2Acceptance)
        val masks = masks(gills = 0.009f, pores = 0.002f)

        val evidence = segmenter.evidenceFor(masks, 1f, 1L)

        assertNull(evidence.firstOrNull { it.step == ViewStep.UNDERSIDE })
    }

    @Test
    fun `v2 evidence value is the max qualified own coverage not the raw max`() {
        val segmenter = ViewpointSegmenter(null, 300, 300, 6, v2Acceptance)
        // gills hat mehr Rohabdeckung, erreicht aber nicht seine 0.01-Schwelle.
        // pores qualifiziert mit 0.003 und sollte den Evidenzwert liefern.
        val masks = masks(gills = 0.009f, pores = 0.004f)

        val evidence = segmenter.evidenceFor(masks, 1f, 1L)
            .first { it.step == ViewStep.UNDERSIDE }

        assertEquals(0.004f, evidence.coverage, 0.0001f)
    }

    @Test
    fun `v1 behavior emits evidence for any positive coverage unchanged`() {
        val segmenter = ViewpointSegmenter(null, 300, 300, 6, v1Acceptance)
        val masks = masks(gills = 0.001f, pores = 0f)

        val evidence = segmenter.evidenceFor(masks, 1f, 1L)

        assertEquals(ViewStep.UNDERSIDE, evidence.first().step)
        assertEquals(0.001f, evidence.first().coverage, 0.0001f)
    }
}
