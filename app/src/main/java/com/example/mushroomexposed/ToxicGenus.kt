package com.example.mushroomexposed

/**
 * Warn-Gattungen: Gattungen, deren Arten im vorliegenden Bestand **alle** als
 * giftig gefuehrt werden und von denen keine einzige als essbar gilt.
 *
 * Warum das eine eigene Regel ist (Messung 19.09.2026, 492 Bilder / 43 Arten):
 * Die schlimmsten Fehlfreigaben des Modells enthalten sehr wohl
 * Knollenblaetterpilze in den Top-3 -- nur essbare (`Amanita_ceciliae`,
 * `Amanita_excelsa`). Eine Regel, die auf das Verdict der Treffer schaut, sieht
 * das nicht; eine Regel, die auf die **Gattung** schaut, schon. Als Blockade
 * gemessen kostet sie die Haelfte der korrekten Freigaben (60 -> 40 bei
 * seed-17); deshalb ist sie hier nur ein **Hinweis**, keine Blockade.
 *
 * Die Definition ist bewusst scharf: sobald eine Art der Gattung als essbar
 * gefuehrt wird, ist die Gattung keine Warn-Gattung mehr (Agaricus hat sieben
 * essbare und einen giftigen Vertreter und darf nicht warnen).
 *
 * Belege: docs/superpowers/notes/toxizitaetsregel-plan.md (Trainings-Repo).
 */
object ToxicGenus {

    /** `Amanita_phalloides` -> `Amanita`. Leerer Eingabe -> leerer Name. */
    fun genus(scientific: String): String = scientific.substringBefore("_").trim()

    /**
     * Gattungen mit mindestens 2 giftig gefuehrten Arten und keiner einzigen
     * essbaren. Auf dem echten App-Bestand ergibt das genau
     * `{Amanita, Clitocybe}`.
     */
    fun riskyGenera(verdicts: Map<String, String>): Set<String> {
        val poisonous = mutableMapOf<String, Int>()
        val hasEdible = mutableSetOf<String>()
        for ((scientific, verdict) in verdicts) {
            val g = genus(scientific)
            if (g.isEmpty()) continue
            when (verdict.lowercase()) {
                "giftig" -> poisonous[g] = (poisonous[g] ?: 0) + 1
                "essbar" -> hasEdible.add(g)
            }
        }
        return poisonous.filter { (g, n) -> n >= 2 && g !in hasEdible }.keys
    }

    /** Erste Art der Trefferliste, deren Gattung eine Warn-Gattung ist. */
    fun firstRisky(rankedScientific: List<String>, risky: Set<String>): String? =
        rankedScientific.firstOrNull { genus(it) in risky }?.let { genus(it) }
}
