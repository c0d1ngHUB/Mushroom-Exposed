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

    /** "71 %" from 0.71; below one percent one decimal ("0.8 %") so rare hits stay readable. */
    fun percent(probability: Float): String {
        val p = probability * 100
        return if (p >= 1.0) "${p.toInt()} %" else String.format(Locale.GERMANY, "%.1f %%", p)
    }

    fun format(ranked: List<RankedSpecies>, decision: VerdictDecision): ResultView {
        val best = ranked.firstOrNull()
        fun markOf(verdict: String): String = when (verdict) {
            "giftig" -> "☠"
            "essbar" -> "✓"
            else -> ""
        }
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
            subline = if (best == null) "" else "${best.scientific} · ${percent(best.probability)}",
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

