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
) {
    init {
        require(coverage in 0f..1f) { "coverage must be in [0, 1]" }
        require(sharpness in 0f..1f) { "sharpness must be in [0, 1]" }
    }

    val score: Float
        get() = coverage * sharpness
}

/** Kalibrierte Mindestwerte, die nur nach dem Segmentierungs-Gate gesetzt werden dürfen. */
data class ViewAcceptance(
    val minCoverage: Float,
    val minSharpness: Float,
) {
    init {
        require(minCoverage in 0f..1f) { "minCoverage must be in [0, 1]" }
        require(minSharpness in 0f..1f) { "minSharpness must be in [0, 1]" }
    }
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
        if (evidence.coverage < acceptance.minCoverage || evidence.sharpness < acceptance.minSharpness) {
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
