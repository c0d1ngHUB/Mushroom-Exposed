package com.example.mushroomexposed

import java.util.Locale

data class RankedSpecies(
    val germanName: String,
    val scientific: String,
    val verdict: String,
    val probability: Float,
)

/** Eine Zeile der Trefferliste: Rang, Text, Ampel-Zeichen, Gift-Flag. */
data class TopRow(
    val rank: Int,
    val label: String,
    val mark: String,
    val toxic: Boolean,
)

/** Anruf-Flaeche im Notfallblock. */
data class EmergencyContact(
    val number: String,
    val label: String,
)

data class ResultView(
    val headline: String,
    val subline: String,
    val tone: VerdictTone,
    val topLines: List<String>,
    val warning: String,
    val emergency: String?,
    /** Artname ohne Zusatz, fuer die grosse Serif-Zeile im Sheet. */
    val name: String = "",
    /** Strukturierte Trefferliste; `topLines` bleibt die kanonische Textform. */
    val tops: List<TopRow> = emptyList(),
    /** Die beiden Anrufziele, aus den Teilen von `EMERGENCY_TEXT` gebaut. */
    val contacts: List<EmergencyContact> = emptyList(),
)

object ResultFormatter {

    /**
     * Kanonischer Notfall-Wortlaut. Ein Unit-Test pinnt ihn Wort fuer Wort; das
     * Ergebnis-Sheet leitet die Anruf-Flaechen aus denselben Teilen ab, damit
     * eine geaenderte Nummer nicht an zwei Stellen nachgezogen werden muss.
     * Muss identisch zu `R.string.emergency_text` bleiben.
     */
    const val EMERGENCY_TEXT =
        "Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale 01 406 43 43 (24 h) " +
            "oder Notruf 144. Restpilz und Erbrochenes aufbewahren."

    /** Bausteine des gepinnten Satzes — die Anruf-Flaechen zeigen dieselben Werte. */
    const val EMERGENCY_TITLE = "Bei Verdacht auf Pilzvergiftung"
    const val POISON_CONTROL_NUMBER = "01 406 43 43"
    const val EMERGENCY_NUMBER = "144"
    const val EMERGENCY_NOTE = "Restpilz und Erbrochenes aufbewahren."

    val EMERGENCY_CONTACTS = listOf(
        EmergencyContact(POISON_CONTROL_NUMBER, "Vergiftungsinfo, 24 h"),
        EmergencyContact(EMERGENCY_NUMBER, "Notruf"),
    )

    /** "71 %" von 0.71; unter einem Prozent eine Nachkommastelle ("0,8 %"), damit seltene Treffer lesbar bleiben. */
    fun percent(probability: Float): String {
        val p = probability * 100
        return if (p >= 1.0) "${p.toInt()} %" else String.format(Locale.GERMANY, "%.1f %%", p)
    }

    /**
     * `Cantharellus_cibarius` -> `Cantharellus cibarius`.
     *
     * Der Unterstrich ist ein Dateiname, kein Artname; in der Oberflaeche hat er
     * nichts zu suchen.
     */
    fun displayName(scientific: String): String = scientific.replace('_', ' ')

    /**
     * `2026-09-20T09:04:06` -> `20.09.2026, 09:04`.
     *
     * Der Verlauf speichert weiter einen maschinenlesbaren Zeitstempel; nur die
     * Anzeige lokalisiert ihn. Ein unlesbarer Wert faellt unveraendert durch,
     * damit ein alter Eintrag nie verschwindet.
     */
    fun displayTimestamp(raw: String): String {
        return try {
            DISPLAY_TIMESTAMP.format(STORED_TIMESTAMP.parse(raw) ?: return raw)
        } catch (e: java.text.ParseException) {
            raw
        }
    }

    private val STORED_TIMESTAMP = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.GERMANY)
    private val DISPLAY_TIMESTAMP = java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY)

    /**
     * Marke einer Trefferzeile.
     *
     * Nur die giftige Art bekommt ein Zeichen. Eine essbare Einschaetzung bleibt
     * neutral: ein Haken wuerde wie eine Freigabe wirken, und genau die gibt
     * dieses Modell nicht (60 %-Schwelle, giftige Arten in den Top-3).
     */
    fun markOf(verdict: String): String = when (verdict) {
        "giftig" -> "☠"
        else -> ""
    }

    /** Deutsches Urteilswort zur internen Stufe des Verlaufs. */
    fun verdictWord(verdict: String): String = when (verdict) {
        "danger" -> "giftig"
        "safe" -> "essbar"
        "caution" -> "achtung"
        else -> "unbewertet"
    }

    /**
     * Eine Verlaufszeile, in ihre drei lesbaren Teile zerlegt.
     *
     * Der Review ruegte eine dichte Zeile mit zwei unerklaerten Symbolen. Hier
     * steht je Zeile: Art, ein Urteil mit **einem** Zeichen und ausgeschriebenem
     * Wort, und das lokale Datum. Das Verwechslungsrisiko wird benannt statt mit
     * einem zweiten Zeichen angedeutet.
     */
    data class HistoryRow(
        val species: String,
        val verdict: String,
        val timestamp: String,
        val note: String,
    )

    fun historyRow(entry: HistoryEntry): HistoryRow {
        // Die gespeicherte Stufe ist "danger"/"caution"/"safe"; erst das
        // Urteilswort traegt die Marke. Ein Haken entsteht so nicht.
        val word = verdictWord(entry.verdict)
        val mark = markOf(if (entry.verdict == "danger") "giftig" else entry.verdict)
        return HistoryRow(
            species = entry.german.ifBlank { displayName(entry.scientific) },
            verdict = listOf(mark, word, "·", percent(entry.confidence))
                .filter { it.isNotBlank() }
                .joinToString(" "),
            timestamp = displayTimestamp(entry.timestamp),
            note = if (entry.lookalike) "Verwechslungsrisiko" else "",
        )
    }

    fun format(ranked: List<RankedSpecies>, decision: VerdictDecision): ResultView {
        val best = ranked.firstOrNull()
        val tops = ranked.mapIndexed { index, species ->
            TopRow(
                rank = index + 1,
                label = "${species.germanName} — ${percent(species.probability)}",
                mark = markOf(species.verdict),
                toxic = species.verdict == "giftig",
            )
        }
        val topLines = tops.map { row ->
            "${row.rank}. ${row.label}" + if (row.mark.isBlank()) "" else " ${row.mark}"
        }
        return ResultView(
            headline = if (best == null) decision.headline else "${decision.headline} — ${best.germanName}",
            subline = if (best == null) "" else "${displayName(best.scientific)} · ${percent(best.probability)}",
            tone = decision.tone,
            topLines = topLines,
            warning = decision.warning,
            emergency = if (decision.tone == VerdictTone.SAFE) null else EMERGENCY_TEXT,
            name = best?.germanName.orEmpty(),
            tops = tops,
            contacts = EMERGENCY_CONTACTS,
        )
    }
}

/**
 * Setzt den kanonischen Notfall-Wortlaut aus seinen Teilen zusammen. Der Test
 * haelt damit fest, dass der gepinnte Satz und die Anruf-Flaechen nicht
 * auseinander laufen koennen.
 */
fun emergencyTextFromParts(): String =
    "${ResultFormatter.EMERGENCY_TITLE}: Vergiftungsinformationszentrale " +
        "${ResultFormatter.POISON_CONTROL_NUMBER} (24 h) oder Notruf " +
        "${ResultFormatter.EMERGENCY_NUMBER}. ${ResultFormatter.EMERGENCY_NOTE}"
