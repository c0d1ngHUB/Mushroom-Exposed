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
    /**
     * Freigabeschwelle fuer "essbar".
     *
     * Am 19.09.2026 gemessen: mit 0,40 werden giftige Arten als essbar
     * freigegeben — auf der eingefrorenen GBIF-Menge 4 bis 10 von 108 giftigen
     * Bildern je Modell, darunter `Amanita_phalloides` als `Agaricus_*` mit bis
     * zu 0,97. Bei 0,60 fallen davon ueber vier Modelle 27 auf 19, waehrend
     * 83–93 % der korrekten Freigaben erhalten bleiben. Kein Schwellenwert
     * schliesst den Fehler; er sitzt im Modell. 0,60 ist die Stufe mit dem
     * besten Verhaeltnis. Beleg: docs/superpowers/notes/toxizitaetsregel-plan.md
     * im Trainings-Repo.
     */
    const val CONFIDENCE_THRESHOLD = 0.60f

    fun lookalikeSentence(lookalike: Lookalike): String =
        if (lookalike.evidence.isBlank()) {
            "Achtung Verwechslung: sieht aus wie ${lookalike.name}."
        } else {
            "Achtung Verwechslung mit ${lookalike.name}: ${lookalike.evidence}"
        }

    /**
     * Freigabe eines essbaren Fundes, die nur wegen einer giftigen Art in der
     * Trefferliste zurueckgestuft wird. Kopf bleibt "essbar", der Ton warnt.
     */
    private fun toxicAlternativeSentence(name: String): String =
        "Giftige Art in der Trefferliste ($name) — zur Kontrolle vergleichen, " +
            "im Zweifel Pilzberatung fragen."

    /**
     * Warnung wegen einer Warn-Gattung in der Trefferliste. Greift auch dann,
     * wenn kein Treffer als giftig gefuehrt wird -- genau das ist der Fall,
     * den das Verdict allein nicht sieht (`Amanita_ceciliae` ist essbar und
     * trotzdem ein Knollenblaetterpilz-Umfeld).
     */
    private fun toxicGenusSentence(genus: String): String =
        "Gattung $genus gilt hier als Warn-Gattung — Arten dieser Gattung " +
            "sind durchweg giftig. Vor dem Essen fachkundig bestimmen lassen."

    /**
     * A dangerous lookalike must never leave the card green: the edible headline
     * is kept, but the tone drops to CAUTION so the field user sees the warning.
     */
    fun decide(
        verdict: String?,
        confidence: Float,
        lookalike: Lookalike? = null,
        toxicAlternative: String? = null,
        toxicGenus: String? = null,
    ): VerdictDecision {
        val base = baseDecision(verdict, confidence)
        var decision = base
        if (lookalike != null) {
            val dangerous = lookalike.kind == LookalikeKind.GEFAEHRLICH
            val tone =
                if (dangerous && decision.tone == VerdictTone.SAFE) VerdictTone.CAUTION
                else decision.tone
            decision = decision.copy(
                tone = tone,
                warning = "${decision.warning} ${lookalikeSentence(lookalike)}",
            )
        }
        // Nur eine tatsaechliche Freigabe wird zurueckgestuft. "GIFTIG !!" und
        // "nicht bewertet"/"unsicher" bleiben unveraendert: dort ist der Ton
        // schon warnend und ein zweiter Satz wuerde nur widersprechen.
        if (toxicAlternative != null && decision.headline == "essbar") {
            decision = decision.copy(
                tone = VerdictTone.CAUTION,
                warning = "${decision.warning} ${toxicAlternativeSentence(toxicAlternative)}",
            )
        }
        if (toxicGenus != null && decision.headline == "essbar") {
            decision = decision.copy(
                tone = VerdictTone.CAUTION,
                warning = "${decision.warning} ${toxicGenusSentence(toxicGenus)}",
            )
        }
        return decision
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
