package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Konsens ist der letzte Schritt vor `VerdictPolicy`: erst wenn alle drei
 * Ansichten belegt sind, darf überhaupt eine Art stabil erscheinen. Jede
 * Ansicht trägt genau einen ausgewählten Frame bei — kein Frame darf doppelt
 * zählen, sonst wiegt eine Ansicht schwerer als die anderen.
 */
class SpeciesConsensusTest {

    private fun consensus(species: Int = 3) = SpeciesConsensus(species)

    @Test
    fun `consensus needs all three views`() {
        val consensus = consensus()

        consensus.add(ViewStep.CAP, floatArrayOf(0.9f, 0.1f, 0.0f))

        assertNull(consensus.result())
    }

    @Test
    fun `two views are still not enough`() {
        val consensus = consensus()

        consensus.add(ViewStep.CAP, floatArrayOf(0.9f, 0.1f, 0.0f))
        consensus.add(ViewStep.UNDERSIDE, floatArrayOf(0.8f, 0.2f, 0.0f))

        assertNull(consensus.result())
    }

    @Test
    fun `all three views produce a consensus of the species class count`() {
        val consensus = consensus()

        consensus.add(ViewStep.CAP, floatArrayOf(0.9f, 0.05f, 0.05f))
        consensus.add(ViewStep.UNDERSIDE, floatArrayOf(0.9f, 0.05f, 0.05f))
        consensus.add(ViewStep.STIPE_RING, floatArrayOf(0.9f, 0.05f, 0.05f))

        val result = consensus.result()

        assertEquals(3, result?.size)
    }

    @Test
    fun `a second vector for the same view replaces the first instead of counting twice`() {
        val consensus = consensus(2)

        consensus.add(ViewStep.CAP, floatArrayOf(0.99f, 0.01f))
        consensus.add(ViewStep.CAP, floatArrayOf(0.01f, 0.99f))
        consensus.add(ViewStep.UNDERSIDE, floatArrayOf(0.01f, 0.99f))
        consensus.add(ViewStep.STIPE_RING, floatArrayOf(0.01f, 0.99f))

        val result = consensus.result()

        // Läge der erste Hut-Frame noch im Topf, zöge er Klasse 1 nach oben.
        assertTrue("the replaced frame must not be aggregated", (result?.get(1) ?: 0f) > (result?.get(0) ?: 1f))
    }

    @Test
    fun `a vector of the wrong species count is rejected`() {
        val consensus = consensus(3)

        assertThrows(IllegalArgumentException::class.java) {
            consensus.add(ViewStep.CAP, floatArrayOf(0.5f, 0.5f))
        }
    }

    @Test
    fun `a negative probability is rejected`() {
        val consensus = consensus(2)

        assertThrows(IllegalArgumentException::class.java) {
            consensus.add(ViewStep.CAP, floatArrayOf(-0.5f, 1.5f))
        }
    }

    @Test
    fun `an underflowed zero is floored instead of rejected`() {
        val consensus = consensus(2)

        // Softmax kann legitim auf 0 underflowen; die Aggregation floot das.
        consensus.add(ViewStep.CAP, floatArrayOf(0f, 1f))
        consensus.add(ViewStep.UNDERSIDE, floatArrayOf(0f, 1f))
        consensus.add(ViewStep.STIPE_RING, floatArrayOf(0f, 1f))

        assertTrue("the agreed class must win despite the floor", (consensus.result()?.get(1) ?: 0f) > 0.99f)
    }

    @Test
    fun `the consensus ranks consistently across identical views`() {
        val first = consensus(3).apply {
            add(ViewStep.CAP, floatArrayOf(0.80f, 0.15f, 0.05f))
            add(ViewStep.UNDERSIDE, floatArrayOf(0.70f, 0.25f, 0.05f))
            add(ViewStep.STIPE_RING, floatArrayOf(0.60f, 0.35f, 0.05f))
        }.result()

        val second = consensus(3).apply {
            add(ViewStep.STIPE_RING, floatArrayOf(0.60f, 0.35f, 0.05f))
            add(ViewStep.UNDERSIDE, floatArrayOf(0.70f, 0.25f, 0.05f))
            add(ViewStep.CAP, floatArrayOf(0.80f, 0.15f, 0.05f))
        }.result()

        assertEquals(first!!.toList(), second!!.toList())
        assertTrue("the agreed class must win", first[0] > first[1])
    }

    @Test
    fun `a single confident view cannot outvote two disagreeing views`() {
        val result = consensus(2).apply {
            add(ViewStep.CAP, floatArrayOf(1e-8f, 1f))
            add(ViewStep.UNDERSIDE, floatArrayOf(1e-8f, 1f))
            add(ViewStep.STIPE_RING, floatArrayOf(1f, 1e-8f))
        }.result()

        // Zwei gegen einen: die Mehrheit der Ansichten entscheidet, nicht das Maximum.
        assertTrue(result!![1] > result[0])
    }

    @Test
    fun `the consensus is a probability distribution`() {
        val result = consensus(3).apply {
            add(ViewStep.CAP, floatArrayOf(0.5f, 0.3f, 0.2f))
            add(ViewStep.UNDERSIDE, floatArrayOf(0.4f, 0.4f, 0.2f))
            add(ViewStep.STIPE_RING, floatArrayOf(0.6f, 0.2f, 0.2f))
        }.result()

        assertEquals(1.0f, result!!.sum(), 1e-4f)
    }
}
