package com.attendancefr.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object DateUtils {
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private val PRETTY = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun today(): String = LocalDate.now().format(ISO)

    fun pretty(isoDate: String): String = try {
        LocalDate.parse(isoDate, ISO).format(PRETTY)
    } catch (_: Exception) {
        isoDate
    }

    fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME)

    fun formatDateTime(millis: Long): String {
        val z = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return z.toLocalDate().format(PRETTY) + " " + z.toLocalTime().format(TIME)
    }

    fun minusDays(isoDate: String, days: Long): String =
        LocalDate.parse(isoDate, ISO).minusDays(days).format(ISO)
}
