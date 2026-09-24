package com.example.mushroomexposed

/**
 * Modellvertrag des Ansichts-Segmentierers.
 *
 * Die sechs Kanäle sind fest: Hintergrund plus die fünf morphologischen
 * Nachweise. Gills und pores bleiben getrennte Modellkanäle — sie sind zwei
 * verschiedene Strukturen — mappen aber beide auf die eine UI-Ansicht
 * Unterseite. Eine Oberfläche, die „Stielbasis“ nennt, gibt es nicht: die
 * Datenquelle deckt die Volva als eigene Klasse nicht verlässlich ab.
 */
object ViewpointContract {

    const val CHANNEL_BACKGROUND = 0
    const val CHANNEL_CAP = 1
    const val CHANNEL_GILLS = 2
    const val CHANNEL_PORES = 3
    const val CHANNEL_STIPE = 4
    const val CHANNEL_RING = 5

    const val CHANNEL_COUNT = 6

    /**
     * Der Vertrag schließt geschlossen: passt die Kanalzahl nicht, ist das
     * Asset nicht das, wofür die App es hält. Dann darf keine Ansicht grün
     * werden und die Mehransichten-Erkennung beginnt gar nicht erst.
     */
    fun requireOutputChannels(channels: Int): Int {
        check(channels == CHANNEL_COUNT) {
            "Viewpoint model has $channels output channels, but the contract requires $CHANNEL_COUNT " +
                "(background, cap, gills, pores, stipe, ring)."
        }
        return channels
    }

    /** Die Ansicht, die ein Kanal belegt — `null` für Hintergrund oder Unbekanntes. */
    fun stepOfChannel(channel: Int): ViewStep? = when (channel) {
        CHANNEL_CAP -> ViewStep.CAP
        CHANNEL_GILLS, CHANNEL_PORES -> ViewStep.UNDERSIDE
        CHANNEL_STIPE, CHANNEL_RING -> ViewStep.STIPE_RING
        else -> null
    }
}
