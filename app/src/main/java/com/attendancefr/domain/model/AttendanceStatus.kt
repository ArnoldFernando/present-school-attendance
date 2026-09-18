package com.attendancefr.domain.model

/**
 * Stored as a string column in Room. The extra [ManualOverride] value is
 * used when a teacher explicitly overrode an automatic match (or assigned
 * a status by hand). The UI still shows Present/Absent/Late; the override
 * flag is kept so reports can distinguish automatic vs. human decisions.
 */
enum class AttendanceStatus {
    Present,
    Absent,
    Late,
    ManualOverride;

    fun displayLabel(): String = when (this) {
        Present -> "Present"
        Absent -> "Absent"
        Late -> "Late"
        ManualOverride -> "Manual override"
    }

    companion object {
        fun fromStorage(raw: String): AttendanceStatus =
            entries.firstOrNull { it.name == raw } ?: Present
    }
}
