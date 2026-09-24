package com.example.mushroomexposed

import org.json.JSONObject

/**
 * Liest die kalibrierten Ansichts-Schwellen aus dem Modellvertrag.
 *
 * Warum als eigenes Asset und nicht als Konstante im UI-Code: die Spec vom
 * 2026-09-24 verlangt, dass die Akzeptanzgrenzen zur **evaluierten
 * Modellkonfiguration** gehören und nicht aus festen UI-Werten entstehen. Eine
 * Konstante hier wäre eine zweite Wahrheit, die beim nächsten Trainingslauf
 * still von der Messung abweicht.
 *
 * Fail closed: fehlt die Datei oder ein Feld, liefert [loadOrNull] `null`, und
 * die App belegt **keine** Ansicht. Ein erfundener Ersatzwert würde Ansichten
 * grün melden, für die es keine Messung gibt — genau die Richtung, die dieses
 * Projekt als gefährlich behandelt.
 */
object ViewpointConfig {

    const val ASSET = "viewpoint.json"

    /** Schlüssel des Modellvertrags -> UI-Ansicht. */
    private val STEP_KEYS = mapOf(
        "cap" to ViewStep.CAP,
        "underside" to ViewStep.UNDERSIDE,
        "stipe_ring" to ViewStep.STIPE_RING,
    )

    /**
     * Vertrag aus dem JSON-Text, oder `null`, wenn er unvollständig ist.
     *
     * Wirft nicht: ein kaputtes Asset ist kein Grund, die bestehende
     * Einzelbild-Installation lahmzulegen.
     */
    fun parseOrNull(text: String): ViewAcceptance? = try {
        val root = JSONObject(text)
        val thresholds = root.getJSONObject("min_coverage_by_view")
        val byStep: Map<ViewStep, Float> = STEP_KEYS.entries.associate { (key, step) ->
            val value = thresholds.getDouble(key).toFloat()
            require(value in 0f..1f) { "$key coverage $value outside [0, 1]" }
            step to value
        }
        // Die Vollständigkeit steckt im Konstruktor von `ViewAcceptance`: ein
        // fehlendes Feld fällt dort auf, nicht erst beim Sammeln.
        val sharpness = root.getDouble("min_sharpness").toFloat()
        ViewAcceptance(minCoverageByStep = byStep, minSharpness = sharpness)
    } catch (e: Exception) {
        null
    }
}
