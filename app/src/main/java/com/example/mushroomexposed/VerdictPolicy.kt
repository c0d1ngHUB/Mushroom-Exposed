package com.example.mushroomexposed

enum class VerdictTone {
    DANGER,
    SAFE,
    CAUTION,
}

data class VerdictDecision(
    val headline: String,
    val warning: String,
    val tone: VerdictTone,
)

object VerdictPolicy {
    const val CONFIDENCE_THRESHOLD = 0.40f

    fun lookalikeSentence(lookalike: Lookalike): String =
        if (lookalike.evidence.isBlank()) {
            "Achtung Verwechslung: sieht aus wie ${lookalike.targetName}."
        } else {
            "Achtung Verwechslung mit ${lookalike.targetName}: ${lookalike.evidence}"
        }

    /**
     * A dangerous lookalike must never leave the card green: the edible headline
     * is kept, but the tone drops to CAUTION so the field user sees the warning.
     */
    fun decide(verdict: String?, confidence: Float, lookalike: Lookalike? = null): VerdictDecision {
        val base = baseDecision(verdict, confidence)
        if (lookalike == null) return base
        val tone = if (lookalike.isDangerous && base.tone == VerdictTone.SAFE) VerdictTone.CAUTION else base.tone
        return base.copy(tone = tone, warning = "${base.warning} ${lookalikeSentence(lookalike)}")
    }

    private fun baseDecision(verdict: String?, confidence: Float): VerdictDecision = when {
        verdict == "giftig" -> VerdictDecision(
            headline = "GIFTIG !!",
            warning = "Nicht verzehren. Im Zweifel Pilzberatung fragen.",
            tone = VerdictTone.DANGER,
        )
        verdict == "essbar" && confidence >= CONFIDENCE_THRESHOLD -> VerdictDecision(
            headline = "essbar",
            warning = "Nur bei sicherer Bestimmung essen — nie auf App verlassen.",
            tone = VerdictTone.SAFE,
        )
        verdict == "essbar" -> VerdictDecision(
            headline = "unsicher — nicht essen",
            warning = "Zu unsicher für eine Freigabe — Pilzberatung fragen.",
            tone = VerdictTone.CAUTION,
        )
        confidence >= CONFIDENCE_THRESHOLD -> VerdictDecision(
            headline = "nicht bewertet",
            warning = "Verzehr-Einschätzung unbekannt — Pilzberatung fragen.",
            tone = VerdictTone.CAUTION,
        )
        else -> VerdictDecision(
            headline = "unsicher",
            warning = "Verzehr-Einschätzung unbekannt — Pilzberatung fragen.",
            tone = VerdictTone.CAUTION,
        )
    }
}
