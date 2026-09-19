package com.example.mushroomexposed

enum class FieldMode { LIVE, ANALYSING, FROZEN }

enum class FieldModeAction { CAPTURE, RETURN_TO_LIVE, IGNORE }

class FieldModeMachine {
    @Volatile
    var state: FieldMode = FieldMode.LIVE
        private set

    val acceptsQualityUpdates: Boolean
        get() = state == FieldMode.LIVE

    fun onPrimaryAction(): FieldModeAction = when (state) {
        FieldMode.LIVE -> {
            state = FieldMode.ANALYSING
            FieldModeAction.CAPTURE
        }
        FieldMode.ANALYSING -> FieldModeAction.IGNORE
        FieldMode.FROZEN -> {
            state = FieldMode.LIVE
            FieldModeAction.RETURN_TO_LIVE
        }
    }

    fun onCaptureUnavailable() {
        if (state == FieldMode.ANALYSING) state = FieldMode.LIVE
    }

    fun onAnalysisFinished() {
        if (state == FieldMode.ANALYSING) state = FieldMode.FROZEN
    }
}
