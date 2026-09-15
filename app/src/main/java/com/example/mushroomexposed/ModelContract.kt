package com.example.mushroomexposed

object ModelContract {
    fun requireMatchingClassCount(modelOutputClasses: Int, labelCount: Int): Int {
        check(modelOutputClasses == labelCount) {
            "Model has $modelOutputClasses classes, but labels.txt has $labelCount entries."
        }
        return modelOutputClasses
    }
}
