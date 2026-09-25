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

        // Die Ausgabeform des TFLite-Graphen ist NHWC `[1, H, W, 6]`. Ein
        // `run` in ein Java-Objekt der Form `[1, 6, H*W]` wirft zur Laufzeit
        // „Cannot copy from a TensorFlowLite tensor [1, 300, 300, 6] to a Java
        // object with shape [1, 6, 90000]" — am Geraet gefunden am 25.09.2026,
        // auf der JVM unsichtbar, weil dort kein echtes Modell laeuft. Deshalb
        // wird in die Ausgabeform des Modells gelaufen und danach umgeordnet.
        val buffer = Array(1) { Array(inputHeight) { Array(inputWidth) { FloatArray(outputChannels) } } }
        interpreter.run(input, buffer)

        val flat = Array(outputChannels) { FloatArray(inputWidth * inputHeight) }
        for (y in 0 until inputHeight) {
            val rowStart = y * inputWidth
            for (x in 0 until inputWidth) {
                val pixel = buffer[0][y][x]
                val index = rowStart + x
                for (channel in 0 until outputChannels) {
                    flat[channel][index] = pixel[channel]
                }
            }
        }
        return flat
    }

    /**
     * Evidenz für jede Ansicht, die dieser Frame belegt. Ein Lamellen- oder
     * Poren-Treffer belegt genau die Unterseite, ein Stiel- oder Ring-Treffer
     * genau Stiel / Ring, ein Hut-Treffer genau den Hut.
     *
     * `competingCoverage` ist der stärkste **fremde** Teilkanal desselben
     * Frames. Der eigene Kanal zählt nicht mit — bei der Unterseite sind
     * Lamellen und Poren beide „eigen", sodass ihr Wettbewerber der Hut oder
     * Stiel/Ring ist. Ein Kanal, der sich selbst als Wettbewerber hätte, würde
     * durch die Dominanzregel faktisch abgeschaltet.
     */
    fun evidenceFor(bitmap: Bitmap, frameId: Long): List<ViewEvidence> {
        val masks = masksFor(bitmap) ?: return emptyList()
        val sharpness = normalisedSharpness(bitmap)

        val cap = coverage(masks[ViewpointContract.CHANNEL_CAP])
        val gills = coverage(masks[ViewpointContract.CHANNEL_GILLS])
        val pores = coverage(masks[ViewpointContract.CHANNEL_PORES])
        val stipe = coverage(masks[ViewpointContract.CHANNEL_STIPE])
        val ring = coverage(masks[ViewpointContract.CHANNEL_RING])

        val underside = maxOf(gills, pores)
        val stipeRing = maxOf(stipe, ring)

        return buildList {
            if (cap > 0f) add(ViewEvidence(ViewStep.CAP, cap, sharpness, frameId, maxOf(underside, stipeRing)))
            // Unterseite: Lamellen ODER Poren, nicht beides noetig.
            if (underside > 0f) add(ViewEvidence(ViewStep.UNDERSIDE, underside, sharpness, frameId, maxOf(cap, stipeRing)))
            // Stiel / Ring; die unterste Stielzone ist bewusst keine eigene
            // Ansicht, weil die Datenquelle sie nicht verlaesslich abdeckt.
            if (stipeRing > 0f) add(ViewEvidence(ViewStep.STIPE_RING, stipeRing, sharpness, frameId, maxOf(cap, underside)))
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
