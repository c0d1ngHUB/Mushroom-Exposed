package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {

    private fun uniform(value: Int, size: Int = 64) = IntArray(size * size) { value }

    /** 1-pixel checkerboard on the left half only: the Laplacian magnitude varies across the frame. */
    private fun contrasty(size: Int = 64): IntArray {
        val gray = IntArray(size * size) { 128 }
        for (y in 1 until size - 1) {
            for (x in 1 until size / 2) {
                gray[y * size + x] = if ((x + y) % 2 == 0) 20 else 235
            }
        }
        return gray
    }

    @Test
    fun `dark frame reports DARK`() {
        val quality = FrameQualityAnalyzer.analyze(uniform(20), 64, 64)

        assertTrue(quality.meanLuminance < 0.18f)
        assertEquals(QualityHint.DARK, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `bright frame reports BRIGHT`() {
        val quality = FrameQualityAnalyzer.analyze(uniform(230), 64, 64)

        assertTrue(quality.meanLuminance > 0.82f)
        assertEquals(QualityHint.BRIGHT, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `flat mid grey frame is BLURRY`() {
        val quality = FrameQualityAnalyzer.analyze(uniform(128), 64, 64)

        assertEquals(0f, quality.laplacianVariance, 0.0001f)
        assertEquals(QualityHint.BLURRY, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `contrasty mid grey frame is OK`() {
        val quality = FrameQualityAnalyzer.analyze(contrasty(), 64, 64)

        assertTrue(quality.laplacianVariance > FrameQualityPolicy.MIN_SHARPNESS)
        assertEquals(QualityHint.OK, FrameQualityPolicy.hint(quality))
    }

    @Test
    fun `overexposed areas report BRIGHT even at moderate mean`() {
        val size = 64
        // 40 % blown out, mean still well inside the normal window
        val gray = IntArray(size * size) { i -> if (i < size * size * 2 / 5) 255 else 100 }

        val quality = FrameQualityAnalyzer.analyze(gray, size, size)

        assertTrue(quality.meanLuminance in 0.18f..0.82f)
        assertTrue(quality.overexposedFraction > 0.30f)
        assertEquals(QualityHint.BRIGHT, FrameQualityPolicy.hint(quality))
    }
}
