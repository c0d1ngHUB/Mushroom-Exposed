package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Der Segmentierer ist ein eigener Modellvertrag. Sechs Kanäle sind keine
 * Geschmacksfrage: Hut, Lamellen, Poren, Stiel und Ring sind fünf
 * morphologische Nachweise plus Hintergrund. Ein Kanal zu wenig oder zu viel
 * bedeutet, dass das Asset nicht das ist, wofür die App es hält — dann darf
 * keine Ansicht grün werden.
 */
class ViewpointContractTest {

    @Test
    fun `five segmentation channels fail closed`() {
        assertThrows(IllegalStateException::class.java) {
            ViewpointContract.requireOutputChannels(5)
        }
    }

    @Test
    fun `seven segmentation channels fail closed`() {
        assertThrows(IllegalStateException::class.java) {
            ViewpointContract.requireOutputChannels(7)
        }
    }

    @Test
    fun `six segmentation channels are accepted unchanged`() {
        assertEquals(6, ViewpointContract.requireOutputChannels(6))
    }

    @Test
    fun `cap channel maps to the cap view`() {
        assertEquals(ViewStep.CAP, ViewpointContract.stepOfChannel(ViewpointContract.CHANNEL_CAP))
    }

    @Test
    fun `gills and pores both map to the underside view`() {
        assertEquals(
            ViewStep.UNDERSIDE,
            ViewpointContract.stepOfChannel(ViewpointContract.CHANNEL_GILLS),
        )
        assertEquals(
            ViewStep.UNDERSIDE,
            ViewpointContract.stepOfChannel(ViewpointContract.CHANNEL_PORES),
        )
    }

    @Test
    fun `stipe and ring both map to the stipe ring view`() {
        assertEquals(
            ViewStep.STIPE_RING,
            ViewpointContract.stepOfChannel(ViewpointContract.CHANNEL_STIPE),
        )
        assertEquals(
            ViewStep.STIPE_RING,
            ViewpointContract.stepOfChannel(ViewpointContract.CHANNEL_RING),
        )
    }

    @Test
    fun `the background channel proves no view`() {
        assertNull(ViewpointContract.stepOfChannel(ViewpointContract.CHANNEL_BACKGROUND))
    }

    @Test
    fun `a channel outside the contract proves no view`() {
        assertNull(ViewpointContract.stepOfChannel(99))
    }
}
