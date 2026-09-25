package com.example.mushroomexposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter

/**
 * Laufzeitparität: echte, beschriftete FungiTastic-Mini-Trainframes
 * (train/300p) werden vom ausgelieferten TFLite-Segmentierer mit einem
 * v2-Vertrag verarbeitet. Ergebnis wird gegen das Python-Golden desselben
 * Bildes geprüft.
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
     * Python-Golden: sharpness=0.023820, cap=false, underside=false, stipe_ring=false
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
     * Python-Golden: sharpness=0.487850, cap=false, underside=true, stipe_ring=false
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
        val descriptor = context.assets.openFd("viewpoint.tflite")
        val stream = FileInputStream(descriptor.fileDescriptor)
        val mapped = stream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            descriptor.startOffset,
            descriptor.declaredLength,
        )
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
