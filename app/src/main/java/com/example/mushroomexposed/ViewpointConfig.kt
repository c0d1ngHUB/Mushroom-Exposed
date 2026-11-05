package com.example.mushroomexposed

import org.json.JSONObject

private fun JSONObject.keySetCompat(): Set<String> =
    buildSet { keys().forEach { add(it as String) } }

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

    private val CHANNEL_KEYS = setOf(
        ViewpointChannel.CAP.jsonName,
        ViewpointChannel.GILLS.jsonName,
        ViewpointChannel.PORES.jsonName,
        ViewpointChannel.STIPE.jsonName,
        ViewpointChannel.RING.jsonName,
    )

    /**
     * Vertrag aus dem JSON-Text, oder `null`, wenn er unvollständig ist.
     *
     * @param actualSha256 SHA-256 der tatsächlich geladenen Modellbytes. Ein
     *   v2-Vertrag ist an diesen Hash gebunden; bei Abweichung fail-closed.
     * @param inputSize Erwartete Input-Größe des Segmentierers.
     * @param outputChannels Erwartete Kanalzahl des Segmentierers.
     */
    fun parseOrNull(
        text: String,
        actualSha256: String,
        inputSize: Int,
        outputChannels: Int,
    ): ViewAcceptance? = try {
        val root = org.json.JSONObject(text)
        val schemaVersion = root.optInt("schema_version", 1)
        when (schemaVersion) {
            1 -> parseV1(root)
            2 -> parseV2(root, actualSha256, inputSize, outputChannels)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun parseV1(root: org.json.JSONObject): ViewAcceptance {
        val thresholds = root.getJSONObject("min_coverage_by_view")
        val byStep: Map<ViewStep, Float> = STEP_KEYS.entries.associate { (key, step) ->
            val value = thresholds.getDouble(key).toFloat()
            require(value in 0f..1f) { "$key coverage $value outside [0, 1]" }
            step to value
        }
        val dominance = root.getJSONObject("dominance_factor_by_view")
        val dominanceByStep: Map<ViewStep, Float> = STEP_KEYS.entries.associate { (key, step) ->
            val value = dominance.getDouble(key).toFloat()
            require(value >= 0f) { "$key dominance $value is negative" }
            step to value
        }
        val sharpness = root.getDouble("min_sharpness").toFloat()
        return ViewAcceptance(
            minCoverageByStep = byStep,
            minSharpness = sharpness,
            dominanceFactorByStep = dominanceByStep,
        )
    }

    private fun parseV2(
        root: org.json.JSONObject,
        actualSha256: String,
        inputSize: Int,
        outputChannels: Int,
    ): ViewAcceptance? {
        // Identität: der Vertrag gehört zu genau einem Modell und einer Form.
        if (root.getString("tflite_sha256") != actualSha256) return null
        if (root.getInt("input_size") != inputSize) return null
        if (root.getInt("output_channels") != outputChannels) return null

        val maskOn = root.getJSONObject("mask_on_by_channel")
        val minCoverage = root.getJSONObject("min_coverage_by_channel")
        if (maskOn.keySetCompat() != CHANNEL_KEYS || minCoverage.keySetCompat() != CHANNEL_KEYS) {
            return null
        }

        val channelPoints = CHANNEL_KEYS.associate { name ->
            val channel = ViewpointChannel.fromJson(name)
            val tau = maskOn.getDouble(name).toFloat()
            val area = minCoverage.getDouble(name).toFloat()
            require(tau in 0f..1f) { "$name tau $tau outside [0, 1]" }
            require(area in 0f..1f) { "$name area $area outside [0, 1]" }
            require(area > 0f) { "$name area must be positive" }
            channel to ChannelOperatingPoint(tau, area)
        }

        val dominance = root.getJSONObject("dominance_factor_by_view")
        val dominanceByStep: Map<ViewStep, Float> = STEP_KEYS.entries.associate { (key, step) ->
            val value = dominance.getDouble(key).toFloat()
            require(value >= 0f) { "$key dominance $value is negative" }
            if (step == ViewStep.CAP) {
                require(value >= 1f) { "cap dominance $value below 1.0" }
            }
            step to value
        }

        val sharpness = root.getDouble("min_sharpness").toFloat()
        return ViewAcceptance(
            minCoverageByStep = viewFloors(channelPoints),
            minSharpness = sharpness,
            dominanceFactorByStep = dominanceByStep,
            channelPoints = channelPoints,
        )
    }

    /**
     * Die pro-Ansicht-Mindestfläche für v2 ist das Minimum der gemessenen
     * Teilkanal-Schwellen. Damit wird eine Ansicht nie härter bewertet als ihr
     * schwächster eigener Kanal, aber ein Kanal, der seinen eigenen Bereich
     * erreicht, wird nicht durch den gemeinsamen Boden abgelehnt.
     */
    private fun viewFloors(
        channelPoints: Map<ViewpointChannel, ChannelOperatingPoint>,
    ): Map<ViewStep, Float> = mapOf(
        ViewStep.CAP to channelPoints.getValue(ViewpointChannel.CAP).area,
        ViewStep.UNDERSIDE to minOf(
            channelPoints.getValue(ViewpointChannel.GILLS).area,
            channelPoints.getValue(ViewpointChannel.PORES).area,
        ),
        ViewStep.STIPE_RING to minOf(
            channelPoints.getValue(ViewpointChannel.STIPE).area,
            channelPoints.getValue(ViewpointChannel.RING).area,
        ),
    )
}