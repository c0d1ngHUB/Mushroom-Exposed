package com.example.mushroomexposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter

/**
 * Laufzeitparität: echte, beschriftete FungiTastic-Mini-Train- und Val-Frames
 * (physisch unter train/300p) werden vom ausgelieferten TFLite-Segmentierer
 * mit einem v2-Vertrag verarbeitet. Ergebnis wird gegen das Python-Golden
 * desselben Bildes geprüft.
 *
 * Was eine einzelne Frame-Parität beweist:
 * - Das ausgelieferte TFLite-Modell produziert auf einem konkreten
 *   Trainingsframe (incl. Skia-Resizing und ByteBuffer) Masken, deren
 *   Abdeckungszahlen bis zur Toleranz mit dem PyTorch-Checkpoint übereinstimmen.
 * - Die Android-Akkumulator-Entscheidungen (coverage, dominance, sharpness)
 *   reproduzieren die Python-Entscheidungen `frame_flags` für diesen Frame.
 *
 * Was sie NICHT beweist:
 * - Generelle Gleichheit aller Trainings-/Validationsframes. Skia, TFLite-GPU
 *   vs. CPU und das Android-Resizing können auf anderen Bildern abweichen.
 * - Pixel-exakte Identität der Sigmoid-Ausgaben; wir vergleichen nur
 *   Schwellen-abgeleitete Abdeckungen.
 * - Zeitliche Stabilität der UI-Mehransichten-Erkennung, denn hier wird nur ein
 *   einzelner Frame gefüttert, keine Frame-Sequenz oder Kamera-Preview.
 *
 * Herkunft des Frames:
 * - Datei: FungiTastic-Mini train/300p/0-2237852161.JPG
 * - observationID: 2237852161 (im Train-Split seed-42)
 * - Lizenz: FungiTastic-Mini CC BY-NC-SA 4.0
 * - Reproduzierbare lokale Erzeugung:
 *   `app/src/androidTest/python/generate_golden.py` (läuft gegen das
 *   bereits vorhandene Trainings-Worktree und den Checkpoint
 *   `out/viewpoint/runs/skip-300-seed42/checkpoint.pt`).
 * Das echte Bild ist lizenziert und darf **nicht** committed werden; dieser
 * Test failt deshalb bewusst, wenn das Asset fehlt.
 */
class ViewpointRuntimeTest {

    /**
     * v2-Betriebspunkt aus Task 3 (val-gewählt, aber noch nicht freigegeben).
     * Er wird inline gebaut, damit Task 3 `accept=false metrics_pass=false`
     * weiterhin gilt: die produktive `viewpoint.json` in `main/assets` bleibt
     * v1, aber der Test prüft den v2-Laufzeitpfad mit echten Daten.
     */
    private val v2Contract = JSONObject(
        mapOf(
            "schema_version" to 2,
            "mask_on_by_channel" to JSONObject(
                mapOf("cap" to 0.3, "gills" to 0.5, "pores" to 0.5, "stipe" to 0.3, "ring" to 0.3),
            ),
            "min_coverage_by_channel" to JSONObject(
                mapOf("cap" to 0.002, "gills" to 0.01, "pores" to 0.003, "stipe" to 0.001, "ring" to 0.001),
            ),
            "dominance_factor_by_view" to JSONObject(
                mapOf("cap" to 1.0, "underside" to 0.0, "stipe_ring" to 0.0),
            ),
            "min_sharpness" to 0.05,
            "input_size" to 300,
            "output_channels" to 6,
            "run_id" to "skip-300-seed42",
            "tflite_sha256" to "f1b42948fc5b0ac5983dbc2db86a4964655d1889436e4044ab60ffe13f2b275f",
        ),
    ).toString()

    @Test
    fun realTrainFrameMatchesPythonGolden() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        // Lizenziertes echtes Trainingsbild — bewusst kein synthetischer Ersatz.
        val bitmap = testContext.assets.open("viewpoint_real_frame.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(
            "real test frame viewpoint_real_frame.jpg is missing; " +
                "generate it with generate_golden.py from the FungiTastic-Mini train split",
            bitmap,
        )

        val (interpreter, modelSha, inputSize, channels) = loadInterpreter(appContext)
        val acceptance = ViewpointConfig.parseOrNull(v2Contract, modelSha, inputSize, channels)
        assertNotNull("v2 contract must parse against the real model hash", acceptance)

        val v2Segmenter = ViewpointSegmenter(
            interpreter,
            inputSize,
            inputSize,
            channels,
            acceptance,
        )

        val masks = v2Segmenter.masksFor(bitmap!!)
        assertNotNull("masksFor must return real mask arrays", masks)

        val evidence = v2Segmenter.evidenceFor(bitmap, 1L)
        assertTrue("evidence must contain at least one view", evidence.isNotEmpty())

        // Python-Golden für FungiTastic-Mini train/300p/0-2237852161.JPG
        // (Berechnung mit PyTorch-Checkpoint skip-300-seed42, s. generate_golden.py)
        assertCoverage(masks!!, ViewpointContract.CHANNEL_CAP, 0.3f, 0.154856f, "cap")
        assertCoverage(masks, ViewpointContract.CHANNEL_GILLS, 0.5f, 0.027656f, "gills")
        assertCoverage(masks, ViewpointContract.CHANNEL_PORES, 0.5f, 0.000000f, "pores")
        assertCoverage(masks, ViewpointContract.CHANNEL_STIPE, 0.3f, 0.012989f, "stipe")
        assertCoverage(masks, ViewpointContract.CHANNEL_RING, 0.3f, 0.000000f, "ring")

        // Akkumulator-Entscheidung muss mit Python-Golden übereinstimmen.
        val accumulator = ViewAccumulator(acceptance!!)
        var progress = accumulator.cancel()
        for (e in evidence) {
            progress = accumulator.accept(e)
        }
        assertTrue("cap must be captured", progress.captured.contains(ViewStep.CAP))
        assertTrue("underside must be captured", progress.captured.contains(ViewStep.UNDERSIDE))
        assertTrue("stipe_ring must be captured", progress.captured.contains(ViewStep.STIPE_RING))
    }

    /**
     * Niedrige Schärfe: kein Frame darf je eine Ansicht belegen.
     *
     * Frame: FungiTastic-Mini train/300p/3-3126950317.JPG
     * observationID: 3126950317 (Train-Split seed-42)
     * Python-Golden: sharpness=0.024861, cap=false, underside=false, stipe_ring=false
     */
    @Test
    fun lowSharpnessFrameRejectsAllViews() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val bitmap = testContext.assets.open("3-3126950317_frame.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(
            "low-sharpness test frame 3-3126950317_frame.jpg is missing; " +
                "generate it with generate_golden.py",
            bitmap,
        )

        val (interpreter, modelSha, inputSize, channels) = loadInterpreter(appContext)
        val acceptance = ViewpointConfig.parseOrNull(v2Contract, modelSha, inputSize, channels)
        assertNotNull(acceptance)

        val segmenter = ViewpointSegmenter(interpreter, inputSize, inputSize, channels, acceptance)
        val masks = segmenter.masksFor(bitmap!!)
        assertNotNull("masksFor must return real mask arrays", masks)
        logCoverages(masks!!)

        val evidence = segmenter.evidenceFor(bitmap, 1L)
        if (evidence.isNotEmpty()) {
            android.util.Log.d("ViewpointRuntimeTest", "sharpness: " + evidence.first().sharpness)
        }

        val accumulator = ViewAccumulator(acceptance!!)
        var progress = accumulator.cancel()
        for (e in evidence) {
            progress = accumulator.accept(e)
        }

        assertTrue("all views rejected when sharpness < min_sharpness", progress.captured.isEmpty())
    }

    /**
     * Dominanz-/Boden-Ablehnung: Hut dominiert durch Lamellen, Stiel/Ring erreicht
     * keinen eigenen Kanal.
     *
     * Frame: FungiTastic-Mini train/300p/0-3313851318.JPG
     * observationID: 3313851318 (Train-Split seed-42)
     * Python-Golden: sharpness=0.611200, cap=false, underside=true, stipe_ring=false
     *   cap coverage 0.165033 bei cap-dominance 1.0 vs gills 0.376789 -> rejected
     */
    @Test
    fun dominanceFloorFrameRejectsCapButAcceptsUnderside() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val bitmap = testContext.assets.open("0-3313851318_frame.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(
            "dominance-floor test frame 0-3313851318_frame.jpg is missing; " +
                "generate it with generate_golden.py",
            bitmap,
        )

        val (interpreter, modelSha, inputSize, channels) = loadInterpreter(appContext)
        val acceptance = ViewpointConfig.parseOrNull(v2Contract, modelSha, inputSize, channels)
        assertNotNull(acceptance)

        val segmenter = ViewpointSegmenter(interpreter, inputSize, inputSize, channels, acceptance)
        val masks = segmenter.masksFor(bitmap!!)
        assertNotNull("masksFor must return real mask arrays", masks)
        logCoverages(masks!!)

        val evidence = segmenter.evidenceFor(bitmap, 1L)
        if (evidence.isNotEmpty()) {
            android.util.Log.d("ViewpointRuntimeTest", "sharpness: " + evidence.first().sharpness)
        }

        val accumulator = ViewAccumulator(acceptance!!)
        var progress = accumulator.cancel()
        for (e in evidence) {
            progress = accumulator.accept(e)
        }

        assertFalse("cap must be rejected because gills dominates", progress.captured.contains(ViewStep.CAP))
        assertTrue("underside must be accepted", progress.captured.contains(ViewStep.UNDERSIDE))
        assertFalse("stipe_ring must be rejected", progress.captured.contains(ViewStep.STIPE_RING))
    }

    @Test
    fun measuredSampleReportContainsRealFrameIdentityAndBothActivationGrids() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "viewpoint-runtime-sample.json")
        report.delete()
        exportRuntimeSample(report)
        assertTrue("runtime measurements must be exported after validation", report.isFile)
        val json = JSONObject(report.readText())
        assertEquals(2, json.getInt("schema_version"))
        assertEquals("android_runtime_sample", json.getString("report_kind"))
        assertFalse("a local frame sample must never authorize release", json.getBoolean("release_eligible"))
        assertFalse("sample must not masquerade as split-bound parity", json.has("input_hashes"))
        assertEquals("f1b42948fc5b0ac5983dbc2db86a4964655d1889436e4044ab60ffe13f2b275f", json.getString("model_sha256"))
        assertEquals(0.012, json.getDouble("sharpness_abs_tolerance"), 0.0)
        assertTrue(json.getBoolean("flags_match_python_golden"))
        assertFalse(json.has("flags_match_train_golden"))
        val frames = json.getJSONArray("frames")
        assertEquals(5, frames.length())
        assertEquals("ad7236af757f29fdce744767515874ce25460a4e9bad8a1025ee9d35f916a475", frames.getJSONObject(0).getString("image_sha256"))
        assertEquals("767dcbed276fe0811971fe97cd2fc57fb7c42d327846a1e6b23fad92701e189e", frames.getJSONObject(1).getString("image_sha256"))
        assertEquals("5341c36e212c98754ec033bc8ec3a6c6fd0d193b919f551ff1bb3ead269314df", frames.getJSONObject(2).getString("image_sha256"))
        assertEquals("cab39916854922c73d10daf15cfe1f5aaec0c85289bfae85d35ed63c9ee8d48f", frames.getJSONObject(3).getString("image_sha256"))
        assertEquals("940ae952a925236436e8b4298dc735376c22408f9b26b3109d7f8b16cb98f956", frames.getJSONObject(4).getString("image_sha256"))
        assertEquals(listOf(1, 300, 300, 3), (0..3).map { json.getJSONArray("input_shape").getInt(it) })
        assertEquals(listOf(1, 300, 300, 6), (0..3).map { json.getJSONArray("output_shape").getInt(it) })
        val pythonSharpness = listOf(
            0.5265560713464371,
            0.02486122863859786,
            0.6112003551975399,
            0.05129240887285327,
            0.04772980201392636,
        )
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            assertEquals(if (i < 3) "train" else "val", frame.getString("source_split"))
            assertEquals(64, frame.getString("image_sha256").length)
            val androidSharpness = frame.getDouble("sharpness")
            assertTrue(androidSharpness.isFinite())
            assertEquals(pythonSharpness[i], frame.getDouble("python_sharpness"), 1e-9)
            assertEquals(
                abs(androidSharpness - pythonSharpness[i]),
                frame.getDouble("sharpness_abs_delta"),
                1e-9,
            )
            assertTrue(frame.getBoolean("sharpness_gate_matches_python"))
            assertTrue(frame.getInt("width") > 0)
            assertTrue(frame.getInt("height") > 0)
            val grids = frame.getJSONObject("coverage_by_tau")
            for (tau in listOf("0.3", "0.5")) {
                val values = grids.getJSONArray(tau)
                assertEquals(6, values.length())
                for (channel in 0 until values.length()) {
                    assertTrue(values.getDouble(channel) in 0.0..1.0)
                }
            }
        }
        assertTrue(frames.getJSONObject(0).getJSONObject("flags").getBoolean("cap"))
        assertFalse(frames.getJSONObject(1).getJSONObject("flags").getBoolean("cap"))
        assertFalse(frames.getJSONObject(2).getJSONObject("flags").getBoolean("cap"))
        assertTrue(frames.getJSONObject(2).getJSONObject("flags").getBoolean("underside"))
    }

    @Test
    fun failedFrameValidationInvalidatesAnOlderSuccessfulReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = File(context.filesDir, "viewpoint-runtime-sample-failure.json")
        report.writeText("previous successful measurement")
        val changedFrame = runtimeFrames.first().copy(sha = "0".repeat(64))
        var rejected = false
        try {
            exportRuntimeSample(report, listOf(changedFrame))
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty().contains("fixture bytes changed"))
            rejected = true
        }
        assertTrue("changed raw frame bytes must be rejected", rejected)
        assertFalse("failed validation must invalidate the previous report", report.exists())
        assertFalse("failed validation must leave no temporary report", File(report.parentFile, report.name + ".tmp").exists())
    }

    private fun exportRuntimeSample(report: File, fixtures: List<RuntimeFrame> = runtimeFrames) {
        // Remove the previous run before any decoding/inference. A failed run
        // must not leave an older successful report available to consumers.
        if (report.exists()) check(report.delete()) { "cannot invalidate stale runtime report" }
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val frames = JSONArray()
        val loaded = loadInterpreter(appContext)
        loaded.interpreter.use { interpreter ->
            val acceptance = requireNotNull(ViewpointConfig.parseOrNull(
                v2Contract, loaded.sha, loaded.inputSize, loaded.channels,
            ))
            val segmenter = ViewpointSegmenter(
                interpreter, loaded.inputSize, loaded.inputSize, loaded.channels, acceptance,
            )
            for ((index, fixture) in fixtures.withIndex()) {
                val bytes = testContext.assets.open(fixture.asset).use { it.readBytes() }
                val imageSha = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                assertEquals("fixture bytes changed: ${fixture.asset}", fixture.sha, imageSha)
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    val masks = requireNotNull(segmenter.masksFor(bitmap))
                    val sharpness = segmenter.normalisedSharpness(bitmap)
                    val sharpnessDelta = abs(sharpness.toDouble() - fixture.pythonSharpness)
                    val gateMatchesPython =
                        (sharpness >= acceptance.minSharpness) ==
                            (fixture.pythonSharpness >= acceptance.minSharpness)
                    assertTrue(
                        "sharpness differs from direct-raw Python path: ${fixture.asset} " +
                            "python=${fixture.pythonSharpness} android=$sharpness delta=$sharpnessDelta",
                        sharpnessDelta <= sharpnessParityTolerance,
                    )
                    assertTrue(
                        "sharpness gate differs between Python and Android: ${fixture.asset}",
                        gateMatchesPython,
                    )
                    val evidence = segmenter.evidenceFor(masks, sharpness, index.toLong())
                    val accumulator = ViewAccumulator(acceptance)
                    var progress = accumulator.cancel()
                    for (item in evidence) progress = accumulator.accept(item)
                    assertEquals("view decisions changed: ${fixture.asset}", fixture.captured, progress.captured)
                    val grids = JSONObject()
                    for (tau in listOf(0.3f, 0.5f)) {
                        val values = JSONArray()
                        for (mask in masks) {
                            assertTrue("empty mask", mask.isNotEmpty())
                            assertTrue("nonfinite mask", mask.all { it.isFinite() })
                            values.put(mask.count { it > tau }.toDouble() / mask.size)
                        }
                        grids.put(tau.toString(), values)
                    }
                    frames.put(JSONObject().apply {
                        put("asset", fixture.asset)
                        put("observation_id", fixture.observationId)
                        put("source_split", fixture.sourceSplit)
                        put("image_sha256", imageSha)
                        put("width", bitmap.width)
                        put("height", bitmap.height)
                        put("sharpness", sharpness.toDouble())
                        put("python_sharpness", fixture.pythonSharpness)
                        put("sharpness_abs_delta", sharpnessDelta)
                        put("sharpness_gate_matches_python", gateMatchesPython)
                        put("coverage_by_tau", grids)
                        put("flags", JSONObject(mapOf(
                            "cap" to (ViewStep.CAP in progress.captured),
                            "underside" to (ViewStep.UNDERSIDE in progress.captured),
                            "stipe_ring" to (ViewStep.STIPE_RING in progress.captured),
                        )))
                    })
                } finally {
                    bitmap.recycle()
                }
            }
            val json = JSONObject().apply {
                put("schema_version", 2)
                put("report_kind", "android_runtime_sample")
                put("release_eligible", false)
                put("scope", "three FungiTastic-Mini train/300p frames plus two val sharpness-boundary frames; no full val/test NPZ or field-test binding")
                put("license", "CC BY-NC-SA 4.0")
                put("model_sha256", loaded.sha)
                put("run_id", "skip-300-seed42")
                put("contract", JSONObject(v2Contract))
                put("input_shape", JSONArray(interpreter.getInputTensor(0).shape().toList()))
                put("output_shape", JSONArray(interpreter.getOutputTensor(0).shape().toList()))
                put("channel_order", JSONArray(listOf("background", "cap", "gills", "pores", "stipe", "ring")))
                put("android_api", android.os.Build.VERSION.SDK_INT)
                put("created_at_epoch_ms", System.currentTimeMillis())
                put("flags_match_python_golden", true)
                put("sharpness_abs_tolerance", sharpnessParityTolerance)
                put("frames", frames)
            }
            val temp = File(report.parentFile, report.name + ".tmp")
            try {
                temp.writeText(json.toString(2))
                check(temp.renameTo(report)) { "cannot publish validated runtime sample" }
            } finally {
                temp.delete()
            }
        }
    }

    private data class RuntimeFrame(
        val asset: String,
        val observationId: String,
        val sha: String,
        val sourceSplit: String,
        val pythonSharpness: Double,
        val captured: Set<ViewStep>,
    )

    private val sharpnessParityTolerance = 0.012

    private val runtimeFrames = listOf(
        RuntimeFrame("viewpoint_real_frame.jpg", "2237852161",
            "ad7236af757f29fdce744767515874ce25460a4e9bad8a1025ee9d35f916a475",
            "train",
            0.5265560713464371,
            setOf(ViewStep.CAP, ViewStep.UNDERSIDE, ViewStep.STIPE_RING)),
        RuntimeFrame("3-3126950317_frame.jpg", "3126950317",
            "767dcbed276fe0811971fe97cd2fc57fb7c42d327846a1e6b23fad92701e189e",
            "train",
            0.02486122863859786, emptySet()),
        RuntimeFrame("0-3313851318_frame.jpg", "3313851318",
            "5341c36e212c98754ec033bc8ec3a6c6fd0d193b919f551ff1bb3ead269314df",
            "train",
            0.6112003551975399, setOf(ViewStep.UNDERSIDE)),
        RuntimeFrame("1-3329734337_frame.jpg", "3329734337",
            "cab39916854922c73d10daf15cfe1f5aaec0c85289bfae85d35ed63c9ee8d48f",
            "val",
            0.05129240887285327, setOf(ViewStep.CAP, ViewStep.STIPE_RING)),
        RuntimeFrame("0-3008838331_frame.jpg", "3008838331",
            "940ae952a925236436e8b4298dc735376c22408f9b26b3109d7f8b16cb98f956",
            "val",
            0.04772980201392636, emptySet()),
    )

    private fun logCoverages(masks: Array<FloatArray>) {
        val names = listOf(
            Triple("cap", ViewpointContract.CHANNEL_CAP, 0.3f),
            Triple("gills", ViewpointContract.CHANNEL_GILLS, 0.5f),
            Triple("pores", ViewpointContract.CHANNEL_PORES, 0.5f),
            Triple("stipe", ViewpointContract.CHANNEL_STIPE, 0.3f),
            Triple("ring", ViewpointContract.CHANNEL_RING, 0.3f),
        )
        for ((name, channel, tau) in names) {
            val mask = masks[channel]
            val coverage = mask.count { it > tau }.toFloat() / mask.size
            android.util.Log.d("ViewpointRuntimeTest", "$name coverage: actual=$coverage tau=$tau")
        }
    }

    private fun assertCoverage(
        masks: Array<FloatArray>,
        channel: Int,
        tau: Float,
        expected: Float,
        name: String,
    ) {
        val mask = masks[channel]
        val coverage = mask.count { it > tau }.toFloat() / mask.size
        // Protokolliere realen Android-Wert neben Python-Golden; bei Abweichung
        // fail closed, weil Skia/TFLite-Parität noch nicht bewiesen ist.
        android.util.Log.d(
            "ViewpointRuntimeTest",
            "$name coverage: expected=$expected actual=$coverage tau=$tau",
        )
        assertEquals(
            "$name coverage at tau=$tau differs from Python golden (FAIL CLOSED until parity proven)",
            expected,
            coverage,
            0.005f,
        )
    }

    private fun loadInterpreter(context: Context): LoadedInterpreter {
        val mapped = context.assets.openFd("viewpoint.tflite").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
        val sha = sha256(mapped)
        mapped.rewind()
        val interpreter = Interpreter(mapped)
        val inputShape = interpreter.getInputTensor(0).shape()
        val channels = interpreter.getOutputTensor(0).shape().last()
        return LoadedInterpreter(
            interpreter,
            sha,
            inputShape[1],
            channels,
        )
    }

    private fun sha256(buffer: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(8192)
        val dup = buffer.duplicate()
        dup.rewind()
        while (dup.hasRemaining()) {
            val limit = minOf(bytes.size, dup.remaining())
            dup.get(bytes, 0, limit)
            digest.update(bytes, 0, limit)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class LoadedInterpreter(
        val interpreter: Interpreter,
        val sha: String,
        val inputSize: Int,
        val channels: Int,
    )
}
