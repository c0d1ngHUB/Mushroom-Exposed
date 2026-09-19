package com.example.mushroomexposed

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.content.Context

/**
 * Ampel-Farbwerte eines Urteils. Eine kraeftige Marke fuer Badge und Kartenrand,
 * eine zarte Flaeche fuer Karten und eine dunkle Schriftvariante, die auf der
 * zarten Flaeche lesbar bleibt.
 */
data class VerdictColors(
    val mark: Int,
    val tint: Int,
    val ink: Int,
)

/**
 * Farblogik des Wald-Designs in einer testbaren Klasse. Bewusst ohne
 * Android-Ressourcen-Zugriff: die drei Werte kommen als gemessene Helligkeiten
 * herein, damit die Lesbarkeitsregel im Unit-Test pruefbar bleibt.
 */
object ViewStyling {

    /** Relative Luminanz nach WCAG 2.x. */
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Lesbarkeit der zarten Flaeche: die dunkle Schriftvariante muss gegen die
     * Flaeche deutlich besser stehen als die kraeftige Marke. Genau daran ist
     * die erste Mockup-Fassung gescheitert (Prozentzahlen in Markenfarbe auf
     * heller Karte).
     */
    fun tintIsLegibleFor(tint: Int, ink: Int, mark: Int, minRatio: Double = 4.5): Boolean {
        val inkRatio = contrastRatio(ink, tint)
        val markRatio = contrastRatio(mark, tint)
        return inkRatio >= minRatio && inkRatio > markRatio
    }

    /** Kreisfoermige Flaeche in der uebergebenen Farbe, optional mit Rand. */
    fun circle(fill: Int, stroke: Int? = null, strokeWidthPx: Int = 0): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != null && strokeWidthPx > 0) setStroke(strokeWidthPx, stroke)
        }

    /** Setzt nur den Rand einer Flaeche neu; Fuellung und Radius bleiben. */
    fun strokeOf(context: Context, view: android.view.View, color: Int) {
        val density = context.resources.displayMetrics.density
        val background = view.background ?: return
        val next = background.constantState?.newDrawable()?.mutate() ?: return
        if (next is GradientDrawable) {
            next.setStroke(density.toInt(), color)
            view.background = next
        }
    }

    /** Runde Flaeche mit Radius und optionalem Rand, fuer Karten und Marken. */
    fun rounded(context: Context, radiusDp: Int, fill: Int, stroke: Int? = null): Drawable {
        val density = context.resources.displayMetrics.density
        val radiusPx = (radiusDp * density).toInt()
        return GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
            if (stroke != null) setStroke((1 * density).toInt(), stroke)
        }
    }

    /** Fuellung einer Flaeche zur Laufzeit tauschen, Form bleibt erhalten. */
    fun fillOf(context: Context, view: android.view.View, color: Int) {
        val background = view.background ?: return
        val next = background.constantState?.newDrawable()?.mutate() ?: return
        when (next) {
            is GradientDrawable -> next.setColor(color)
            else -> return
        }
        view.background = next
    }

    fun colorOf(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)

    /** Mischt eine Farbe mit Weiss; fuer zarte Flaechen mit hohem Kontrastbedarf. */
    fun blend(base: Int, over: Int, ratio: Double): Int =
        ColorUtils.blendARGB(base, over, ratio.toFloat())
}
