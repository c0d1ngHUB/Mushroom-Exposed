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
    private val acceptance: ViewAcceptance? = null,
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
     * Eine Ansicht wird nur Kandidat, wenn mindestens einer ihrer eigenen
     * Kanäle seine eigene Mindestfläche erreicht. Die größte **qualifizierte**
     * eigene Fläche bestimmt den Evidenzwert; fremde Kanäle werden mit ihren
     * tatsächlichen Flächen verglichen, auch wenn sie für ihre eigene Ansicht
     * nicht qualifizieren.
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
        return evidenceFor(masks, sharpness, frameId)
    }

    /**
     * Testbarer Kern von [evidenceFor]: nimmt bereits ausgepackte Masken und
     * eine vorberechnete Schärfe. v2-Modus filtert die eigenen Kanäle auf ihre
     * eigene Mindestfläche; v1-Modus (keine `channelPoints`) bleibt beim alten
     * Verhalten `coverage > 0` mit fester Maskenaktivierung `MASK_ON`.
     */
    internal fun evidenceFor(masks: Array<FloatArray>, sharpness: Float, frameId: Long): List<ViewEvidence> {
        val coverage = { channel: ViewpointChannel ->
            coverage(masks[channel.index], channel)
        }

        val cap = coverage(ViewpointChannel.CAP)
        val gills = coverage(ViewpointChannel.GILLS)
        val pores = coverage(ViewpointChannel.PORES)
        val stipe = coverage(ViewpointChannel.STIPE)
        val ring = coverage(ViewpointChannel.RING)

        // Fremde Kanäle gehen mit ihren tatsächlichen Flächen in den Wettbewerb
        // ein — auch wenn sie für ihre eigene Ansicht nicht qualifizieren.
        val foreignForCap = maxOf(maxOf(gills, pores), maxOf(stipe, ring))
        val foreignForUnderside = maxOf(cap, maxOf(stipe, ring))
        val foreignForStipeRing = maxOf(cap, maxOf(gills, pores))

        return buildList {
            qualifiedEvidence(ViewStep.CAP, listOf(ViewpointChannel.CAP), coverage, foreignForCap, sharpness, frameId)?.let(::add)
            qualifiedEvidence(ViewStep.UNDERSIDE, listOf(ViewpointChannel.GILLS, ViewpointChannel.PORES), coverage, foreignForUnderside, sharpness, frameId)?.let(::add)
            qualifiedEvidence(ViewStep.STIPE_RING, listOf(ViewpointChannel.STIPE, ViewpointChannel.RING), coverage, foreignForStipeRing, sharpness, frameId)?.let(::add)
        }
    }

    /**
     * Erzeugt Evidenz für eine Ansicht, wenn mindestens ein eigener Kanal seine
     * eigene Flächenschwelle erreicht. Im v1-Fall (keine per-channel Punkte)
     * genügt jede positive Abdeckung.
     */
    private fun qualifiedEvidence(
        step: ViewStep,
        ownChannels: List<ViewpointChannel>,
        coverage: (ViewpointChannel) -> Float,
        foreignMax: Float,
        sharpness: Float,
        frameId: Long,
    ): ViewEvidence? {
        val points = acceptance?.channelPoints
        return if (points == null) {
            val best = ownChannels.maxOf { coverage(it) }
            if (best > 0f) ViewEvidence(step, best, sharpness, frameId, foreignMax) else null
        } else {
            val eligible = ownChannels.filter { coverage(it) >= points.getValue(it).area }
            if (eligible.isEmpty()) return null
            ViewEvidence(step, eligible.maxOf { coverage(it) }, sharpness, frameId, foreignMax)
        }
    }

    private fun coverage(mask: FloatArray, channel: ViewpointChannel): Float {
        val tau = acceptance?.channelPoints?.get(channel)?.tau ?: MASK_ON
        return (mask.count { it > tau }.toFloat() / mask.size)
    }

    private val ViewpointChannel.index: Int
        get() = when (this) {
            ViewpointChannel.CAP -> ViewpointContract.CHANNEL_CAP
            ViewpointChannel.GILLS -> ViewpointContract.CHANNEL_GILLS
            ViewpointChannel.PORES -> ViewpointContract.CHANNEL_PORES
            ViewpointChannel.STIPE -> ViewpointContract.CHANNEL_STIPE
            ViewpointChannel.RING -> ViewpointContract.CHANNEL_RING
        }

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
