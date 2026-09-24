package com.example.mushroomexposed

/** LIVE sucht, SCANNING sammelt Ansichten, ANALYSING rechnet, FROZEN zeigt das Ergebnis. */
enum class FieldMode { LIVE, SCANNING, ANALYSING, FROZEN }

enum class FieldModeAction { START_SCAN, STOP_SCAN, CAPTURE, RETURN_TO_LIVE, IGNORE }

/**
 * Hold-to-scan: der Finger sammelt, ein Klick nicht mehr.
 *
 * Die Maschine ist absichtlich ohne Android-Typen und damit unit-testbar. Die
 * beiden Regeln, die den Review-Fokus tragen:
 *
 * - Loslassen während des Sammelns bricht ab (STOP_SCAN) und lässt keinen
 *   eingefrorenen Zustand zurück.
 * - Loslassen während der Analyse wird ignoriert: das Ergebnis ist bereits
 *   unterwegs, ein Abbruch könnte es nur halb anzeigen.
 */
class FieldModeMachine {
    @Volatile
    var state: FieldMode = FieldMode.LIVE
        private set

    /** Qualitäts- und Ansichts-Updates laufen nur, solange gesammelt wird. */
    val acceptsQualityUpdates: Boolean
        get() = state == FieldMode.LIVE || state == FieldMode.SCANNING

    /**
     * Der Druck auf den Auslöser.
     *
     * `viewsAvailable` sagt, ob der Segmentierer geladen ist. Ist er es nicht,
     * kann kein Sammelvorgang je abgeschlossen werden — die App würde auf jeden
     * Druck nur "Ansichtserkennung nicht verfügbar" melden und **nie** ein
     * Ergebnis zeigen. Deshalb fällt der Druck dann auf den Einzelbild-Pfad
     * zurück: ein Frame, eine Analyse, ein Ergebnis. Das ist genau das
     * Verhalten vor dem Mehransichten-Prototyp, nicht ein neuer Modus.
     *
     * Der Vorgabewert ist bewusst `true`: der Hold-to-scan bleibt der normale
     * Weg, und ein Aufrufer, der den Segmentierer nicht kennt, soll ihn nicht
     * versehentlich abschalten.
     */
    fun onPrimaryDown(viewsAvailable: Boolean = true): FieldModeAction = when (state) {
        FieldMode.LIVE -> {
            if (viewsAvailable) {
                state = FieldMode.SCANNING
                FieldModeAction.START_SCAN
            } else {
                // Einzelbild: der Frame ist sofort unterwegs, es wird also
                // analysiert und nicht gesammelt.
                state = FieldMode.ANALYSING
                FieldModeAction.CAPTURE
            }
        }
        else -> FieldModeAction.IGNORE
    }

    fun onPrimaryUp(): FieldModeAction = when (state) {
        FieldMode.SCANNING -> {
            state = FieldMode.LIVE
            FieldModeAction.STOP_SCAN
        }
        // Das Ergebnis liegt schon vor oder ist unterwegs; Loslassen darf es
        // nicht wegnehmen. Zurück geht es nur über onReturnToLive().
        else -> FieldModeAction.IGNORE
    }

    /** Alle drei Ansichten sind belegt: das Sammeln ist vorbei, es wird gerechnet. */
    fun onViewsComplete() {
        if (state == FieldMode.SCANNING) state = FieldMode.ANALYSING
    }

    fun onReturnToLive(): FieldModeAction = when (state) {
        FieldMode.FROZEN -> {
            state = FieldMode.LIVE
            FieldModeAction.RETURN_TO_LIVE
        }
        else -> FieldModeAction.IGNORE
    }

    /**
     * Kein Kamerabild oder eine verlorene Analyse: zurück in LIVE. Aus FROZEN
     * heraus bewusst nicht — dort gibt es ein gültiges Ergebnis zu sehen.
     */
    fun onCaptureUnavailable() {
        if (state == FieldMode.SCANNING || state == FieldMode.ANALYSING) state = FieldMode.LIVE
    }

    fun onAnalysisFinished() {
        if (state == FieldMode.ANALYSING) state = FieldMode.FROZEN
    }
}
