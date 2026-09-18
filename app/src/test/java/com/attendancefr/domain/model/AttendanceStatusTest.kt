package com.attendancefr.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceStatusTest {
    @Test
    fun fromStorageFallsBackToPresent() {
        assertEquals(AttendanceStatus.Late, AttendanceStatus.fromStorage("Late"))
        assertEquals(AttendanceStatus.Present, AttendanceStatus.fromStorage("not-a-status"))
    }
}
