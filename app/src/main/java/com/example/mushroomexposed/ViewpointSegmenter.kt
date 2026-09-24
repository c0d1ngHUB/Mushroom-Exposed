package com.example.mushroomexposed

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter

/**
 * Übersetzt einen Analyseframe in die Evidenz der drei Ansichten.
 *
 * Der Segmentierer ist ein eigenes Modell (`viewpoint.tflite`), nicht das
 * Artenmodell. Fehlt es, liefert diese Klasse **keine** Evidenz: der
 * Sammelvorgang kann dann nie abgeschlossen werden und es entsteht kein
 * grüner Zustand. Das ist die Fail-closed-Regel aus der Spec vom 2026-09-24.
 *
 * Die Maskenkanäle sind fest (siehe [ViewpointContract]): Hintergrund, Hut,
 * Lamellen, Poren, Stiel, Ring.
 *
 * Wichtig zur Ehrlichkeit der Zahlen: die hier berechnete Abdeckung ist eine
 * reine Maskenfläche, und die Schärfe ist die normierte Laplace-Varianz des
 * Frames. Die *Kalibrierung* dieser Werte gehört zum evaluierten Modell und
 * steht noch aus — deshalb kommt die Akzeptanzschwelle von außen
 * ([ViewAcceptance]) und wird nicht hier erfunden.
 */
class ViewpointSegmenter(
    private val interpreter: Interpreter?,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val outputChannels: Int,
) {
    init {
        // Echter Fail-closed-Check gegen das *Modell*, nicht gegen eine
        // eigene Konstante: ein Segmentierer mit anderer Kanalzahl ist nicht
        // das Asset, das diese Klasse versteht.
        if (interpreter != null) ViewpointContract.requireOutputChannels(outputChannels)
    }

    /** true, wenn ein Segmentierer geladen ist; sonst bleibt jeder Schritt offen. */
    val available: Boolean get() = interpreter != null

    /**
     * Rohmasken je Kanal für einen Frame, oder null wenn kein Modell geladen ist.
     * Reihenfolge: Hintergrund, Hut, Lamellen, Poren, Stiel, Ring.
     */
    fun masksFor(bitmap: Bitmap): Array<FloatArray>? {
        val interpreter = interpreter ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val input = java.nio.ByteBuffer
            .allocateDirect(inputWidth * inputHeight * 3 * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()

        val output = arrayOf(Array(outputChannels) { FloatArray(inputWidth * inputHeight) })
        interpreter.run(input, output)

        return Array(outputChannels) { channel -> output[0][channel] }
    }

    /**
     * Evidenz für jede Ansicht, die dieser Frame belegt. Ein Lamellen- oder
     * Poren-Treffer belegt genau die Unterseite, ein Stiel- oder Ring-Treffer
     * genau Stiel / Ring, ein Hut-Treffer genau den Hut.
     */
    fun evidenceFor(bitmap: Bitmap, frameId: Long): List<ViewEvidence> {
        val masks = masksFor(bitmap) ?: return emptyList()
        val sharpness = normalisedSharpness(bitmap)

        val cap = coverage(masks[ViewpointContract.CHANNEL_CAP])
        val gills = coverage(masks[ViewpointContract.CHANNEL_GILLS])
        val pores = coverage(masks[ViewpointContract.CHANNEL_PORES])
        val stipe = coverage(masks[ViewpointContract.CHANNEL_STIPE])
        val ring = coverage(masks[ViewpointContract.CHANNEL_RING])

        return buildList {
            if (cap > 0f) add(ViewEvidence(ViewStep.CAP, cap, sharpness, frameId))
            // Unterseite: Lamellen ODER Poren, nicht beides noetig.
            val underside = maxOf(gills, pores)
            if (underside > 0f) add(ViewEvidence(ViewStep.UNDERSIDE, underside, sharpness, frameId))
            // Stiel / Ring; die unterste Stielzone ist bewusst keine eigene
            // Ansicht, weil die Datenquelle sie nicht verlaesslich abdeckt.
            val stipeRing = maxOf(stipe, ring)
            if (stipeRing > 0f) add(ViewEvidence(ViewStep.STIPE_RING, stipeRing, sharpness, frameId))
        }
    }

    private fun coverage(mask: FloatArray): Float =
        (mask.count { it > MASK_ON }.toFloat() / mask.size)

    /** Laplace-Varianz wie im Qualitaetspfad, normiert auf 0..1. */
    private fun normalisedSharpness(bitmap: Bitmap): Float {
        val scaled = Bitmap.createScaledBitmap(bitmap, SHARPNESS_SIDE, SHARPNESS_SIDE, true)
        val pixels = IntArray(SHARPNESS_SIDE * SHARPNESS_SIDE)
        scaled.getPixels(pixels, 0, SHARPNESS_SIDE, 0, 0, SHARPNESS_SIDE, SHARPNESS_SIDE)
        val gray = IntArray(pixels.size) { i ->
            val p = pixels[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return (FrameQualityAnalyzer.analyze(gray, SHARPNESS_SIDE, SHARPNESS_SIDE)
            .laplacianVariance / SHARPNESS_FULL_SCALE).coerceIn(0f, 1f)
    }

    private companion object {
        /** Schwellwert der Maskenaktivierung; das Modell gibt Sigmoid-Werte aus. */
        const val MASK_ON = 0.5f

        const val SHARPNESS_SIDE = 224

        /**
         * Bezugswert der Normierung. Die Laplace-Varianz ist bei 224x224
         * gemessen (FrameQualityPolicy.MIN_SHARPNESS = 60); 1000 ist ein
         * grosszuegiger Vollausschlag, damit die Normierung nie bei 1 klebt.
         * Wird mit dem Segmentierer-Gate nachgezogen.
         */
        const val SHARPNESS_FULL_SCALE = 1000f
    }
}
