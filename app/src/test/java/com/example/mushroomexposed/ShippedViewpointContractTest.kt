package com.example.mushroomexposed

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der ausgelieferte Vertrag, gegen den echten Parser geprüft.
 *
 * Die anderen Tests bauen ihre JSON-Texte selbst. Damit prüfen sie den Parser,
 * aber nicht die **Datei**, die im APK landet. Genau dort kann es auseinander
 * laufen: schreibt der Trainingslauf ein Feld anders, als die App es liest,
 * bleiben alle Fixture-Tests grün und die App belegt trotzdem keine Ansicht.
 *
 * Deshalb dieser Test: er liest `src/main/assets/viewpoint.json` so, wie die
 * App es zur Laufzeit tut, und hält die Werte gegen die Messung fest, aus der
 * sie stammen (Lauf skip-300-seed42, Gate bestanden am 25.09.2026).
 */
class ShippedViewpointContractTest {

    private val contract = File("src/main/assets/viewpoint.json")
    private val model = File("src/main/assets/viewpoint.tflite")

    @Test
    fun `the shipped contract parses into an acceptance the app can run`() {
        assertTrue("viewpoint.json must ship with the segmenter", contract.isFile)

        val acceptance = ViewpointConfig.parseOrNull(contract.readText())

        assertNotNull("the shipped contract must parse; a null means fail-closed", acceptance)
        // Der Hut ist der Grund für die Dominanzregel: er darf nicht unter 1,0
        // stehen, sonst wäre die gemessene Grenze im Asset unterschritten.
        assertTrue(
            "the shipped cap dominance must reach the agreed floor",
            acceptance!!.dominanceFactorFor(ViewStep.CAP) >= 1.0f,
        )
        assertEquals(0.0f, acceptance.dominanceFactorFor(ViewStep.UNDERSIDE), 1e-6f)
        assertEquals(0.0f, acceptance.dominanceFactorFor(ViewStep.STIPE_RING), 1e-6f)
    }

    @Test
    fun `the shipped floors are the measured ones, not round placeholders`() {
        val acceptance = ViewpointConfig.parseOrNull(contract.readText())!!

        // Werte aus calibrate_views auf dem val-Bericht. Bewusst als exakte
        // Zahlen gepinnt: eine stille Änderung an der Kalibrierung würde sonst
        // unbemerkt ein anderes Betriebsverhalten ausliefern.
        assertEquals(0.001f, acceptance.minCoverageFor(ViewStep.CAP), 1e-6f)
        assertEquals(0.01f, acceptance.minCoverageFor(ViewStep.UNDERSIDE), 1e-6f)
        assertEquals(0.001f, acceptance.minCoverageFor(ViewStep.STIPE_RING), 1e-6f)
    }

    @Test
    fun `the contract names the model it belongs to`() {
        val payload = JSONObject(contract.readText())

        assertEquals("skip-300-seed42", payload.getString("run_id"))
        assertTrue(
            "the contract must name the model hash, so a swap is detectable",
            payload.getString("tflite_sha256").length == 64,
        )
        assertEquals(
            "the contract must state where its numbers come from",
            "frame-level view evidence, chosen on val",
            payload.getString("thresholds_basis"),
        )
    }

    @Test
    fun `the shipped model matches the hash in its contract`() {
        assertTrue("viewpoint.tflite must ship", model.isFile)
        val payload = JSONObject(contract.readText())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(model.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            "the shipped model must be the measured one, not a swapped file",
            payload.getString("tflite_sha256"),
            digest,
        )
    }
}
