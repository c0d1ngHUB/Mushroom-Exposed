package com.example.mushroomexposed

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full Seed-42 val/test comparison of Python and Android sharpness.
 *
 * The hard criterion is decision parity at the unchanged 0.05 gate. The raw
 * delta is only asserted inside the decision band: above it Android saturates
 * at 1.0 while Python keeps the unclamped value, so a large delta there cannot
 * flip the gate and is reported, not asserted. The band is deliberately wider
 * than the gate (values up to twice the threshold) so drift is caught before
 * it can reach the decision.
 */
class SharpnessParityTest {
    @Test
    fun allValAndTestFramesAgreeOnTheSharpnessGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val report = File(
            instrumentation.targetContext.filesDir,
            "viewpoint-sharpness-parity.json",
        )
        report.delete()

        val manifestBytes = assets.open("sharpness_parity/manifest.json").use { it.readBytes() }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        assertEquals(1, manifest.getInt("schema_version"))
        assertEquals("sharpness_parity_input", manifest.getString("report_kind"))
        assertEquals("skip-300-seed42", manifest.getString("run_id"))
        val frames = manifest.getJSONArray("frames")
        assertEquals(4140, frames.length())

        val segmenter = ViewpointSegmenter(null, 300, 300, 6)
        val measurements = ArrayList<Measurement>(frames.length())
        for (index in 0 until frames.length()) {
            val frame = frames.getJSONObject(index)
            val filename = frame.getString("filename")
            val bytes = assets.open("sharpness_parity/images/$filename").use { it.readBytes() }
            assertEquals(frame.getString("image_sha256"), sha256(bytes))
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("cannot decode $filename")
            val androidSharpness = try {
                segmenter.normalisedSharpness(bitmap).toDouble()
            } finally {
                bitmap.recycle()
            }
            val pythonSharpness = frame.getDouble("python_sharpness")
            val delta = abs(androidSharpness - pythonSharpness)
            measurements += Measurement(
                filename = filename,
                observationId = frame.getString("observation_id"),
                split = frame.getString("split"),
                pythonSharpness = pythonSharpness,
                androidSharpness = androidSharpness,
                absoluteDelta = delta,
                gateMatches = (pythonSharpness >= MIN_SHARPNESS) ==
                    (androidSharpness >= MIN_SHARPNESS),
            )
        }

        val gateMismatches = measurements.filterNot { it.gateMatches }
        val decisionBand = measurements.filter { it.pythonSharpness <= DECISION_BAND_MAX }
        val bandMismatches = decisionBand.filter { it.absoluteDelta > MAX_ABS_DELTA }
        val worst = measurements.sortedByDescending { it.absoluteDelta }.take(20)
        val splitStats = JSONObject()
        for (split in listOf("val", "test")) {
            val selected = measurements.filter { it.split == split }
            splitStats.put(split, JSONObject().apply {
                put("frames", selected.size)
                put("max_abs_delta", selected.maxOf { it.absoluteDelta })
                put("mean_abs_delta", selected.sumOf { it.absoluteDelta } / selected.size)
                put("sharpness_gate_mismatches", selected.count { !it.gateMatches })
            })
        }

        val output = JSONObject().apply {
            put("schema_version", 1)
            put("report_kind", "android_sharpness_parity")
            put("release_eligible", false)
            put("run_id", manifest.getString("run_id"))
            put("min_sharpness", MIN_SHARPNESS)
            put("sharpness_abs_tolerance", MAX_ABS_DELTA)
            put("decision_band_max_python_sharpness", DECISION_BAND_MAX)
            put("input_manifest_sha256", sha256(manifestBytes))
            put("split_bindings", manifest.getJSONObject("splits"))
            put("frames", measurements.size)
            put("frames_in_decision_band", decisionBand.size)
            put("max_abs_delta", measurements.maxOf { it.absoluteDelta })
            put("mean_abs_delta", measurements.sumOf { it.absoluteDelta } / measurements.size)
            put("max_abs_delta_in_decision_band", decisionBand.maxOfOrNull { it.absoluteDelta } ?: 0.0)
            put("saturated_android_frames", measurements.count { it.androidSharpness >= 1.0 })
            put("sharpness_gate_mismatches", gateMismatches.size)
            put("decision_band_delta_mismatches", bandMismatches.size)
            put("stats_by_split", splitStats)
            put("worst_frames", JSONArray(worst.map { it.toJson() }))
            put("gate_mismatch_frames", JSONArray(gateMismatches.map { it.toJson() }))
        }
        val temp = File(report.parentFile, report.name + ".tmp")
        try {
            temp.writeText(output.toString(2))
            check(temp.renameTo(report)) { "cannot publish sharpness parity report" }
        } finally {
            temp.delete()
        }

        assertTrue(
            "Python/Android disagree at the $MIN_SHARPNESS gate: " +
                gateMismatches.joinToString { it.filename },
            gateMismatches.isEmpty(),
        )
        assertTrue(
            "raw sharpness delta exceeded $MAX_ABS_DELTA inside the decision band " +
                "(${decisionBand.size} frames); measured " +
                (decisionBand.maxOfOrNull { it.absoluteDelta } ?: 0.0),
            bandMismatches.isEmpty(),
        )
    }

    private data class Measurement(
        val filename: String,
        val observationId: String,
        val split: String,
        val pythonSharpness: Double,
        val androidSharpness: Double,
        val absoluteDelta: Double,
        val gateMatches: Boolean,
    ) {
        fun toJson() = JSONObject().apply {
            put("filename", filename)
            put("observation_id", observationId)
            put("split", split)
            put("python_sharpness", pythonSharpness)
            put("android_sharpness", androidSharpness)
            put("absolute_delta", absoluteDelta)
            put("sharpness_gate_matches", gateMatches)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_SHARPNESS = 0.05
        const val MAX_ABS_DELTA = 0.012
        const val DECISION_BAND_MAX = 0.10
    }
}
