package com.example.mushroomexposed

/** Anatomische Bildansichten, die der Mehransichten-Prototyp belegen muss. */
enum class ViewStep {
    CAP,
    UNDERSIDE,
    STIPE_RING,
}

/** Messbare Evidenz eines einzelnen, flüchtigen Analyseframes. */
data class ViewEvidence(
    val step: ViewStep,
    val coverage: Float,
    val sharpness: Float,
    val frameId: Long,
    /**
     * Stärkster **fremder** Teilkanal derselben Messung.
     *
     * Warum die App das braucht: der Hutkanal markiert auf einer Unteransicht
     * den ganzen Fruchtkörper mit — gemessen am 25.09.2026 trug jeder der 378
     * Hut-Fehlalarme im Trainingssatz ein Lamellen- oder Porenlabel, und der
     * Hut lag dort mit 0,059 Abdeckung im Median *unter* der belegten
     * Unterseite. Eine reine Flächenschwelle kann das nicht trennen (Treffer
     * 0,097 gegen Fehlalarm 0,059). Erst der Vergleich mit dem stärksten
     * anderen Kanal macht „Hut" von „Pilz" unterscheidbar.
     *
     * Der eigene Kanal zählt nicht mit: sonst dominierte sich eine Ansicht
     * selbst und ein Faktor über 1 wäre unerfüllbar.
     */
    val competingCoverage: Float = 0f,
) {
    init {
        require(coverage in 0f..1f) { "coverage must be in [0, 1]" }
        require(sharpness in 0f..1f) { "sharpness must be in [0, 1]" }
        require(competingCoverage in 0f..1f) { "competingCoverage must be in [0, 1]" }
    }

    val score: Float
        get() = coverage * sharpness
}

/**
 * Kalibrierte Mindestwerte, die nur nach dem Segmentierungs-Gate gesetzt werden dürfen.
 *
 * Die Evidenzgrenze gilt **je Ansicht**, nicht global. Grund (gemessen am
 * 25.09.2026, `docs/superpowers/notes/viewpoint-schwellen-sweep-2026-09-25.md`):
 * die Ansichten sind unterschiedlich schwer zu belegen. `cap` wird in 99,8 %
 * der Hut-Frames über 0,5 vorhergesagt, `stipe_ring` nur in 46,4 % der
 * Stiel-Frames. Mit einer gemeinsamen Grenze von 0,05 — dem bisherigen
 * Platzhalter — erkannte `stipe_ring` 1,6 % seiner belegten Frames,
 * `underside` 36,3 %. Mit je Ansicht gewählter Grenze werden daraus 46,4 %
 * bzw. 68,8 %, bei gleicher Fehlalarmgrenze.
 *
 * Eine einzelne Zahl kann das nicht leisten: sie müsste entweder so klein
 * sein, dass die schwache Ansicht nie auslöst, oder so groß, dass sie
 * `cap`-Fehlalarme durchlässt.
 *
 * `minSharpness` bleibt global: die Schärfe kommt aus der Laplace-Varianz und
 * ist bereits gegen das Galinawald-Set kalibriert (`FrameQualityPolicy`). Eine
 * zweite, modellabhängige Schärfegrenze wäre eine zweite Wahrheit für
 * denselben Messwert.
 *
 * `dominanceFactorByStep` ist die zweite Hälfte der Ansichtsgrenze: eine
 * Ansicht gilt nur als belegt, wenn ihre Abdeckung auch den stärksten fremden
 * Teilkanal erreicht (`coverage >= k * competingCoverage`). Der Faktor kommt
 * kalibriert aus `viewpoint.json`, nicht als Konstante. Fehlt er für eine
 * Ansicht, ist der Vertrag unvollständig und die App belegt **keine** Ansicht.
 */
data class ViewAcceptance(
    val minCoverageByStep: Map<ViewStep, Float>,
    val minSharpness: Float,
    val dominanceFactorByStep: Map<ViewStep, Float>,
) {
    init {
        require(minCoverageByStep.keys.containsAll(ViewStep.entries.toSet())) {
            "minCoverageByStep must cover every view step, missing: " +
                ViewStep.entries.filterNot { it in minCoverageByStep }
        }
        require(dominanceFactorByStep.keys.containsAll(ViewStep.entries.toSet())) {
            "dominanceFactorByStep must cover every view step, missing: " +
                ViewStep.entries.filterNot { it in dominanceFactorByStep }
        }
        require(minCoverageByStep.values.all { it in 0f..1f }) {
            "every minCoverage must be in [0, 1]"
        }
        require(dominanceFactorByStep.values.all { it >= 0f }) {
            "every dominance factor must be >= 0"
        }
        require(minSharpness in 0f..1f) { "minSharpness must be in [0, 1]" }
    }

    /** Evidenzgrenze der genannten Ansicht. */
    fun minCoverageFor(step: ViewStep): Float =
        requireNotNull(minCoverageByStep[step]) { "no calibrated coverage for $step" }

    /** Dominanzfaktor der genannten Ansicht. */
    fun dominanceFactorFor(step: ViewStep): Float =
        requireNotNull(dominanceFactorByStep[step]) { "no calibrated dominance for $step" }
}

/** UI-fähiger Snapshot ohne Bitmap oder Bilddaten. */
data class ViewProgress(
    val captured: Set<ViewStep>,
    val next: ViewStep?,
    val complete: Boolean,
)

/**
 * Bewahrt pro anatomischer Ansicht nur die stärkste akzeptierte Evidenz im RAM.
 *
 * Der Accumulator kennt keine Pixel und keinen Dateipfad. Damit kann ein Abbruch
 * alle temporären Referenzen sicher verwerfen, bevor später ein bewusst gewähltes
 * Referenzbild persistiert wird.
 */
class ViewAccumulator(private val acceptance: ViewAcceptance) {
    private val bestByStep = mutableMapOf<ViewStep, ViewEvidence>()

    fun accept(evidence: ViewEvidence): ViewProgress {
        val floor = acceptance.minCoverageFor(evidence.step)
        val dominance = acceptance.dominanceFactorFor(evidence.step)
        val required = maxOf(floor, dominance * evidence.competingCoverage)
        if (evidence.coverage < required || evidence.sharpness < acceptance.minSharpness) {
            return progress()
        }
        val current = bestByStep[evidence.step]
        if (current == null || evidence.score > current.score) {
            bestByStep[evidence.step] = evidence
        }
        return progress()
    }

    fun best(step: ViewStep): ViewEvidence? = bestByStep[step]

    fun cancel(): ViewProgress {
        bestByStep.clear()
        return progress()
    }

    private fun progress(): ViewProgress {
        val captured = bestByStep.keys.toSet()
        val next = ORDER.firstOrNull { it !in captured }
        return ViewProgress(captured = captured, next = next, complete = next == null)
    }

    private companion object {
        val ORDER = listOf(ViewStep.CAP, ViewStep.UNDERSIDE, ViewStep.STIPE_RING)
    }
}
