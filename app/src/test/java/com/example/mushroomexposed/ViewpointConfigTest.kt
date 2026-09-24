package com.example.mushroomexposed

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Vertrag, der bestimmt, wann eine Ansicht grün werden darf.
 *
 * Diese Klasse ist fail-closed gebaut: fehlt ein Feld, gibt es keine
 * Akzeptanz. Ein erfundener Ersatzwert würde Ansichten grün melden, für die
 * es keine Messung gibt — die Richtung, die dieses Projekt als gefährlich
 * behandelt.
 */
class ViewpointConfigTest {

    private val valid = JSONObject(
        mapOf(
            "min_coverage_by_view" to JSONObject(
                mapOf("cap" to 0.005, "underside" to 0.01, "stipe_ring" to 0.005),
            ),
            "min_sharpness" to 0.05,
        ),
    ).toString()

    @Test
    fun `every view of the contract is read`() {
        val acceptance = ViewpointConfig.parseOrNull(valid)

        assertNotNull(acceptance)
        assertEquals(0.005f, acceptance!!.minCoverageFor(ViewStep.CAP), 1e-6f)
        assertEquals(0.01f, acceptance.minCoverageFor(ViewStep.UNDERSIDE), 1e-6f)
        assertEquals(0.005f, acceptance.minCoverageFor(ViewStep.STIPE_RING), 1e-6f)
        assertEquals(0.05f, acceptance.minSharpness, 1e-6f)
    }

    @Test
    fun `a threshold is per view, not global`() {
        // Genau der Grund für die Umstellung: eine Zahl kann nicht sowohl die
        // schwache Ansicht auslösen lassen als auch cap-Fehlalarme begrenzen.
        val perView = JSONObject(valid).getJSONObject("min_coverage_by_view")
        perView.put("underside", 0.20)
        val acceptance = ViewpointConfig.parseOrNull(
            JSONObject(valid).put("min_coverage_by_view", perView).toString(),
        )

        assertNotNull(acceptance)
        assertEquals(0.20f, acceptance!!.minCoverageFor(ViewStep.UNDERSIDE), 1e-6f)
        assertEquals(0.005f, acceptance.minCoverageFor(ViewStep.CAP), 1e-6f)
    }

    @Test
    fun `a missing view in the contract is refused`() {
        val incomplete = JSONObject(
            mapOf(
                "min_coverage_by_view" to JSONObject(mapOf("cap" to 0.005, "underside" to 0.01)),
                "min_sharpness" to 0.05,
            ),
        ).toString()

        assertNull(ViewpointConfig.parseOrNull(incomplete))
    }

    @Test
    fun `a missing sharpness is refused`() {
        val noSharpness = JSONObject(
            mapOf(
                "min_coverage_by_view" to JSONObject(
                    mapOf("cap" to 0.005, "underside" to 0.01, "stipe_ring" to 0.005),
                ),
            ),
        ).toString()

        assertNull(ViewpointConfig.parseOrNull(noSharpness))
    }

    @Test
    fun `a coverage outside zero to one is refused`() {
        val absurd = JSONObject(valid)
            .getJSONObject("min_coverage_by_view")
            .put("cap", 1.5)

        assertNull(ViewpointConfig.parseOrNull(JSONObject(valid).put("min_coverage_by_view", absurd).toString()))
    }

    @Test
    fun `broken json is refused instead of throwing`() {
        // Ein kaputtes Asset darf die bestehende Einzelbild-Installation nicht
        // lahmlegen; es darf nur keine Ansicht belegen.
        assertNull(ViewpointConfig.parseOrNull("{ das ist kein json"))
        assertNull(ViewpointConfig.parseOrNull(""))
    }

    @Test
    fun `the acceptance refuses an incomplete map on its own`() {
        // Der Konstruktor ist die zweite Sicherung: auch wer die Map direkt
        // baut, kann keine Ansicht ohne Grenze einsetzen.
        val thrown = runCatching {
            ViewAcceptance(minCoverageByStep = mapOf(ViewStep.CAP to 0.05f), minSharpness = 0.05f)
        }.isFailure

        assertTrue(thrown)
    }
}
