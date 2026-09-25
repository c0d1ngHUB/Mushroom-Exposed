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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter

/**
 * Laufzeitparität: ein echter, beschrifteter FungiTastic-Mini-Trainframe
 * (train/300p) wird vom ausgelieferten TFLite-Segmentierer mit einem
 * v2-Vertrag verarbeitet. Ergebnis wird gegen das Python-Golden desselben
 * Bildes geprüft.
 *
 * Herkunft des Frames:
 * - Datei: FungiTastic-Mini train/300p/0-2237852161.JPG
 * - observationID: 2237852161 (im Train-Split seed-42)
 * - Lizenz: FungiTastic-Mini CC BY-NC-SA 4.0
 * - Reproduzierbare lokale Erzeugung:
 *   `app/src/androidTest/assets/generate_golden.py` (läuft gegen das
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
        val bitmap = testContext.assets.open("viewpoint_real_frame.png").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull(
            "real test frame viewpoint_real_frame.png is missing; " +
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
