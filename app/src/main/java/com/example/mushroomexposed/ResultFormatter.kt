package com.example.mushroomexposed

import java.util.Locale

data class RankedSpecies(
    val germanName: String,
    val scientific: String,
    val verdict: String,
    val probability: Float,
)

data class ResultView(
    val headline: String,
    val subline: String,
    val tone: VerdictTone,
    val topLines: List<String>,
    val warning: String,
    val emergency: String?,
)

object ResultFormatter {

    /** Must stay identical to `R.string.emergency_text`; the unit test pins the wording. */
    const val EMERGENCY_TEXT =
        "Bei Verdacht auf Pilzvergiftung: Vergiftungsinformationszentrale 01 406 43 43 (24 h) " +
            "oder Notruf 144. Restpilz und Erbrochenes aufbewahren."

    /** "71 %" from 0.71; below one percent one decimal ("0.8 %") so rare hits stay readable. */
    fun percent(probability: Float): String {
        val p = probability * 100
        return if (p >= 1.0) "${p.toInt()} %" else String.format(Locale.GERMANY, "%.1f %%", p)
    }

    fun format(ranked: List<RankedSpecies>, decision: VerdictDecision): ResultView {
        val best = ranked.firstOrNull()
        val topLines = ranked.mapIndexed { index, species ->
            val mark = when (species.verdict) {
                "giftig" -> " ☠"
                "essbar" -> " ✓"
                else -> ""
            }
            "${index + 1}. ${species.germanName} — ${percent(species.probability)}$mark"
        }
        return ResultView(
            headline = if (best == null) decision.headline else "${decision.headline} — ${best.germanName}",
            subline = if (best == null) "" else "${best.scientific} · ${percent(best.probability)}",
            tone = decision.tone,
            topLines = topLines,
            warning = decision.warning,
            emergency = if (decision.tone == VerdictTone.SAFE) null else EMERGENCY_TEXT,
        )
    }
}
