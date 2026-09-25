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

    private val validV1 = JSONObject(
        mapOf(
            "min_coverage_by_view" to JSONObject(
                mapOf("cap" to 0.005, "underside" to 0.01, "stipe_ring" to 0.005),
            ),
            "dominance_factor_by_view" to JSONObject(
                mapOf("cap" to 1.0, "underside" to 0.0, "stipe_ring" to 0.0),
            ),
            "min_sharpness" to 0.05,
        ),
    ).toString()

    /**
     * v2-Vertrag: fünf Kanäle mit je eigenem Aktivierungs- und Flächenpunkt.
     * Die App muss exakt dieses Schema akzeptieren und an den Segmentierer
     * durchreichen; sonst bleibt die Mehransichten-Erfassung aus.
     */
    private val validV2 = JSONObject(
        mapOf(
            "schema_version" to 2,
            "mask_on_by_channel" to JSONObject(
                mapOf(
                    "cap" to 0.3,
                    "gills" to 0.5,
                    "pores" to 0.5,
                    "stipe" to 0.3,
                    "ring" to 0.3,
                ),
            ),
            "min_coverage_by_channel" to JSONObject(
                mapOf(
                    "cap" to 0.002,
                    "gills" to 0.01,
                    "pores" to 0.003,
                    "stipe" to 0.001,
                    "ring" to 0.001,
                ),
            ),
            "dominance_factor_by_view" to JSONObject(
                mapOf("cap" to 1.0, "underside" to 0.0, "stipe_ring" to 0.0),
            ),
            "min_sharpness" to 0.05,
            "input_size" to 300,
            "output_channels" to 6,
            "tflite_sha256" to "a".repeat(64),
        ),
    ).toString()

    private val modelSha = "a".repeat(64)

    @Test
    fun `v1 contract parses unchanged`() {
        val acceptance = ViewpointConfig.parseOrNull(validV1, modelSha, 300, 6)

        assertNotNull(acceptance)
        assertEquals(0.005f, acceptance!!.minCoverageFor(ViewStep.CAP), 1e-6f)
        assertEquals(0.01f, acceptance.minCoverageFor(ViewStep.UNDERSIDE), 1e-6f)
        assertEquals(0.005f, acceptance.minCoverageFor(ViewStep.STIPE_RING), 1e-6f)
        assertEquals(0.05f, acceptance.minSharpness, 1e-6f)
        assertEquals(1.0f, acceptance.dominanceFactorFor(ViewStep.CAP), 1e-6f)
        assertEquals(0.0f, acceptance.dominanceFactorFor(ViewStep.UNDERSIDE), 1e-6f)
        assertEquals(0.0f, acceptance.dominanceFactorFor(ViewStep.STIPE_RING), 1e-6f)
        // v1 hat keine per-channel Punkte; der Segmentierer benutzt dann MASK_ON.
        assertNull(acceptance.channelPoints)
    }

    @Test
    fun `v2 threshold is per channel and shapes evidence`() {
        val acceptance = ViewpointConfig.parseOrNull(validV2, modelSha, 300, 6)!!

        val points = acceptance.channelPoints!!
        assertEquals(0.3f, points.getValue(ViewpointChannel.CAP).tau, 1e-6f)
        assertEquals(0.002f, points.getValue(ViewpointChannel.CAP).area, 1e-6f)
        assertEquals(0.5f, points.getValue(ViewpointChannel.GILLS).tau, 1e-6f)
        assertEquals(0.01f, points.getValue(ViewpointChannel.GILLS).area, 1e-6f)
        assertEquals(0.5f, points.getValue(ViewpointChannel.PORES).tau, 1e-6f)
        assertEquals(0.003f, points.getValue(ViewpointChannel.PORES).area, 1e-6f)
        assertEquals(0.3f, points.getValue(ViewpointChannel.STIPE).tau, 1e-6f)
        assertEquals(0.001f, points.getValue(ViewpointChannel.STIPE).area, 1e-6f)
        assertEquals(0.3f, points.getValue(ViewpointChannel.RING).tau, 1e-6f)
        assertEquals(0.001f, points.getValue(ViewpointChannel.RING).area, 1e-6f)
    }

    @Test
    fun `v1 contract still parses without model hash check`() {
        // v1-Assets haben keinen Hash; sie dürfen weiterhin einfach funktionieren.
        assertNotNull(ViewpointConfig.parseOrNull(validV1, "wrong-sha", 300, 6))
    }

    @Test
    fun `v2 refuses hash mismatch`() {
        assertNull(ViewpointConfig.parseOrNull(validV2, "b".repeat(64), 300, 6))
    }

    @Test
    fun `v2 refuses input size mismatch`() {
        assertNull(ViewpointConfig.parseOrNull(validV2, modelSha, 448, 6))
    }

    @Test
    fun `v2 refuses output channel mismatch`() {
        assertNull(ViewpointConfig.parseOrNull(validV2, modelSha, 300, 5))
    }

    @Test
    fun `v2 refuses missing channel`() {
        val payload = JSONObject(validV2)
        payload.getJSONObject("mask_on_by_channel").remove("pores")
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses extra channel`() {
        val payload = JSONObject(validV2)
        payload.getJSONObject("mask_on_by_channel").put("spores", 0.5)
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses NaN threshold`() {
        // JSONObject.toString() schreibt keinen Raum nach dem Doppelpunkt;
        // der Text wird deshalb direkt gebaut, um NaN als invaliden Wert zu
        // testen.
        val payload = JSONObject(validV2).toString()
            .replace("\"cap\":0.3", "\"cap\":NaN")
        assertNull(ViewpointConfig.parseOrNull(payload, modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses threshold outside zero to one`() {
        val payload = JSONObject(validV2)
        payload.getJSONObject("min_coverage_by_channel").put("cap", 1.5)
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses cap dominance below one`() {
        val payload = JSONObject(validV2)
        payload.getJSONObject("dominance_factor_by_view").put("cap", 0.9)
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses unknown schema version`() {
        val payload = JSONObject(validV2)
        payload.put("schema_version", 3)
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses missing schema version but v2 keys`() {
        val payload = JSONObject(validV2)
        payload.remove("schema_version")
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses zero min coverage area`() {
        // Ein Bereich von 0 würde 0 >= 0 als Treffer zählen — das ist keine
        // Evidenz, sondern ein stiller Selbstlauf.
        val payload = JSONObject(validV2)
        payload.getJSONObject("min_coverage_by_channel").put("stipe", 0.0)
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses missing dominance block`() {
        val payload = JSONObject(validV2)
        payload.remove("dominance_factor_by_view")
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `v2 refuses missing sharpness`() {
        val payload = JSONObject(validV2)
        payload.remove("min_sharpness")
        assertNull(ViewpointConfig.parseOrNull(payload.toString(), modelSha, 300, 6))
    }

    @Test
    fun `broken json is refused instead of throwing`() {
        assertNull(ViewpointConfig.parseOrNull("{ das ist kein json", modelSha, 300, 6))
        assertNull(ViewpointConfig.parseOrNull("", modelSha, 300, 6))
    }

    @Test
    fun `the acceptance refuses an incomplete map on its own`() {
        val thrown = runCatching {
            ViewAcceptance(
                minCoverageByStep = mapOf(ViewStep.CAP to 0.05f),
                minSharpness = 0.05f,
                dominanceFactorByStep = mapOf(ViewStep.CAP to 1f),
            )
        }.isFailure

        assertTrue(thrown)
    }

    @Test
    fun `v2 channel operating point requires positive area`() {
        val thrown = runCatching {
            ChannelOperatingPoint(tau = 0.3f, area = 0f)
        }.isFailure
        assertTrue(thrown)
    }

    @Test
    fun `v2 channel operating point requires tau in zero to one`() {
        val thrown = runCatching {
            ChannelOperatingPoint(tau = 1.5f, area = 0.01f)
        }.isFailure
        assertTrue(thrown)
    }
}