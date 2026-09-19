package com.example.mushroomexposed

import kotlin.math.abs

data class FrameQuality(
    val meanLuminance: Float,
    val laplacianVariance: Float,
    val overexposedFraction: Float,
)

enum class QualityHint { DARK, BRIGHT, BLURRY, OK }

object FrameQualityPolicy {
    // Startwerte aus der Spec (docs/superpowers/specs/2026-09-15-feldmodus-ux-design.md).
    // Am 19.09.2026 gegen die 39 Feldbilder des Galinawald-Sets nachgerechnet
    // (docs/superpowers/notes/frame-quality-kalibrierung.md im Trainings-Repo):
    // 37/39 OK, 2 korrekt DARK. Kein Wert wurde verschoben — die Startwerte
    // halten. MIN_SHARPNESS gilt NUR bei 224x224; die Laplace-Varianz skaliert
    // mit der Pixelzahl, und die App rechnet fest auf 224x224 herunter.
    const val MIN_LUMINANCE = 0.18f
    const val MAX_LUMINANCE = 0.82f
    const val MAX_OVEREXPOSED = 0.30f
    const val MIN_SHARPNESS = 60f

    fun hint(quality: FrameQuality): QualityHint = when {
        quality.meanLuminance < MIN_LUMINANCE -> QualityHint.DARK
        quality.meanLuminance > MAX_LUMINANCE -> QualityHint.BRIGHT
        quality.overexposedFraction > MAX_OVEREXPOSED -> QualityHint.BRIGHT
        quality.laplacianVariance < MIN_SHARPNESS -> QualityHint.BLURRY
        else -> QualityHint.OK
    }
}

object FrameQualityAnalyzer {

    /** 4-neighbour Laplacian magnitude; the variance over the frame measures sharpness. */
    fun analyze(gray: IntArray, width: Int, height: Int): FrameQuality {
        require(width > 2 && height > 2) { "frame too small: ${width}x$height" }
        require(gray.size >= width * height) { "pixel buffer too small" }

        var sum = 0L
        var overexposed = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = gray[y * width + x]
                sum += v
                if (v >= 250) overexposed++
            }
        }
        val mean = sum.toDouble() / (width * height)

        var lapSum = 0.0
        var lapSumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val c = gray[y * width + x]
                val lap = abs(
                    4 * c - gray[(y - 1) * width + x] - gray[(y + 1) * width + x] -
                        gray[y * width + x - 1] - gray[y * width + x + 1],
                ).toDouble()
                lapSum += lap
                lapSumSq += lap * lap
                count++
            }
        }
        val lapMean = if (count == 0) 0.0 else lapSum / count
        val variance = if (count == 0) 0.0 else lapSumSq / count - lapMean * lapMean

        return FrameQuality(
            meanLuminance = (mean / 255.0).toFloat(),
            laplacianVariance = variance.toFloat(),
            overexposedFraction = overexposed.toFloat() / (width * height),
        )
    }
}
