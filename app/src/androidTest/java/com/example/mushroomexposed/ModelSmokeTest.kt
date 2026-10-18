package com.example.mushroomexposed

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.Interpreter

/**
 * Runs the staged app model on a held-out PVV frame on the device.
 *
 * This is a device smoke test, not a quality metric: it proves that the
 * shipped assets load, the 288 px input contract matches and the interpreter
 * returns a finite distribution over all 663 classes. The top-1 is printed
 * together with the expected species; a single frame cannot establish recall.
 */
class ModelSmokeTest {
    @Test
    fun stagedModelRunsOnAHeldOutRegionalFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val labels = target.assets.open("labels.txt").bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() }
        val expected = "Abortiporus_biennis"
        val expectedIndex = labels.indexOfFirst { it.split("|").last() == expected }

        assertTrue("expected species missing from labels.txt", expectedIndex >= 0)
        assertEquals("663 classes expected", 663, labels.size)

        val testAssets = instrumentation.context.assets
        org.junit.Assume.assumeTrue(
            "smoke_pvv.jpg fehlt — lokales Val-Bild einsetzen (nicht versioniert)",
            testAssets.list("")?.contains("smoke_pvv.jpg") == true,
        )
        val bytes = testAssets.open("smoke_pvv.jpg").use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("cannot decode smoke frame")
        val interpreter = loadModel(target.assets)
        try {
            val shape = interpreter.getInputTensor(0).shape()
            assertEquals(4, shape.size)
            val height = shape[1]
            val width = shape[2]
            assertEquals("input must be square", height, width)
            assertEquals("input channels", 3, shape[3])

            val side = min(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(
                bitmap,
                (bitmap.width - side) / 2,
                (bitmap.height - side) / 2,
                side,
                side,
            )
            val scaled = Bitmap.createScaledBitmap(square, width, height, true)
            val pixels = IntArray(width * height)
            scaled.getPixels(pixels, 0, width, 0, 0, width, height)
            val buffer = ByteBuffer.allocateDirect(width * height * 3 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
                buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
                buffer.putFloat((pixel and 0xFF) / 255f)
            }
            buffer.rewind()

            val output = Array(1) { FloatArray(labels.size) }
            interpreter.run(buffer, output)
            val logits = output[0]
            assertEquals(labels.size, logits.size)
            assertTrue("non-finite output", logits.all { it.isFinite() })
            val probabilities = softmax(logits)
            val mass = probabilities.sum()
            assertEquals("softmax mass", 1.0, mass.toDouble(), 1e-3)

            val ranked = probabilities.indices.sortedByDescending { probabilities[it] }
            println(
                "SMOKE expected=$expected rank=${ranked.indexOf(expectedIndex) + 1} " +
                    "top5=" + ranked.take(5).joinToString(", ") {
                        "${labels[it]} (${probabilities[it]})"
                    },
            )
        } finally {
            interpreter.close()
            bitmap.recycle()
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }

    private fun loadModel(assets: AssetManager): Interpreter {
        assets.openFd("model.tflite").use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                val mapped = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
                return Interpreter(mapped)
            }
        }
    }
}
