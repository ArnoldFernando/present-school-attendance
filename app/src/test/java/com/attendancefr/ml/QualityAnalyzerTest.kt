package com.attendancefr.ml

import org.junit.Assert.assertTrue
import org.junit.Test

class QualityAnalyzerTest {
    @Test
    fun minSharpnessIsPositive() {
        assertTrue(QualityAnalyzer.MIN_SHARPNESS > 0f)
    }
}
