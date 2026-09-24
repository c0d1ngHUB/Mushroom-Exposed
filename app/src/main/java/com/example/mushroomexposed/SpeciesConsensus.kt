package com.example.mushroomexposed

import kotlin.math.exp
import kotlin.math.ln

/**
 * Führt die Artwahrscheinlichkeiten der drei belegten Ansichten zusammen.
 *
 * Aggregiert wird das geometrische Mittel im Logarithmusraum: jede Ansicht
 * wiegt gleich, egal wie sicher sie einzeln ist. Eine sehr sichere Ansicht
 * kann zwei unsichere damit nicht überstimmen — genau das ist gewollt, denn
 * eine einzelne Draufsicht ist kein Mehransichten-Beleg.
 *
 * Je Ansicht liegt höchstens ein Frame im Topf. Ein zweiter Frame derselben
 * Ansicht ersetzt den ersten, statt ihn mitzuzählen.
 */
class SpeciesConsensus(private val speciesCount: Int) {

    init {
        require(speciesCount > 0) { "speciesCount must be positive, was $speciesCount" }
    }

    private val byStep = mutableMapOf<ViewStep, FloatArray>()

    fun add(step: ViewStep, probabilities: FloatArray) {
        require(probabilities.size == speciesCount) {
            "Expected $speciesCount species probabilities, got ${probabilities.size}"
        }
        require(probabilities.none { it < 0f }) {
            "A probability must not be negative"
        }
        byStep[step] = probabilities.copyOf()
    }

    /**
     * `null`, solange nicht jede Ansicht einen Frame mit der exakten
     * Artenzahl beigetragen hat. Erst dann ist der Konsens überhaupt bildbar.
     */
    fun result(): FloatArray? {
        if (byStep.size != ViewStep.entries.size) return null

        val logMean = DoubleArray(speciesCount)
        for (step in ViewStep.entries) {
            val probabilities = byStep.getValue(step)
            for (species in 0 until speciesCount) {
                logMean[species] += ln(maxOf(probabilities[species], FLOOR).toDouble())
            }
        }
        for (species in 0 until speciesCount) {
            logMean[species] /= ViewStep.entries.size
        }

        val aggregated = FloatArray(speciesCount) { exp(logMean[it]).toFloat() }
        val sum = aggregated.sum()
        if (sum <= 0f) return aggregated
        return FloatArray(speciesCount) { aggregated[it] / sum }
    }

    private companion object {
        /** Softmax-Ausgaben können legitim auf null underflowen; log(0) gibt es nicht. */
        const val FLOOR = 1e-8f
    }
}
