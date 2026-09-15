package com.example.mushroomexposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelContractTest {
    @Test
    fun `matching model output and label counts are accepted`() {
        assertEquals(664, ModelContract.requireMatchingClassCount(modelOutputClasses = 664, labelCount = 664))
    }

    @Test
    fun `mismatching model output and label counts fail before inference`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ModelContract.requireMatchingClassCount(modelOutputClasses = 505, labelCount = 664)
        }

        assertEquals("Model has 505 classes, but labels.txt has 664 entries.", error.message)
    }
}
