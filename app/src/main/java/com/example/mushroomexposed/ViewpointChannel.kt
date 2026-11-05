package com.example.mushroomexposed

/**
 * Fünf morphologische Nachweiskanäle des Ansichts-Segmentierers.
 *
 * Hintergrund ist kein Nachweiskanal und taucht hier nicht auf. Gills und
 * Pores mappen beide auf die UI-Ansicht [ViewStep.UNDERSIDE].
 */
enum class ViewpointChannel(
    /** Name im JSON-Vertrag. */
    val jsonName: String,
) {
    CAP("cap"),
    GILLS("gills"),
    PORES("pores"),
    STIPE("stipe"),
    RING("ring");

    companion object {
        fun fromJson(name: String): ViewpointChannel = entries.first { it.jsonName == name }
    }
}

/**
 * Gemessener Betriebspunkt eines Kanals: ab welcher Maskenaktivierung etwas
 * zählt und wie viel Fläche mindestens aktiv sein muss.
 *
 * Fail-closed: ein tau außerhalb [0,1] oder ein Bereich von 0 würden
 * sinnlose Treffer erzeugen; das ist kein gültiger Betriebspunkt.
 */
data class ChannelOperatingPoint(
    val tau: Float,
    val area: Float,
) {
    init {
        require(tau in 0f..1f) { "tau must be in [0, 1]" }
        require(area in 0f..1f) { "area must be in [0, 1]" }
        require(area > 0f) { "area must be positive" }
    }
}