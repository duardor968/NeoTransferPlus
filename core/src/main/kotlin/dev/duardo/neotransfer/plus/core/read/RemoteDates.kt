package dev.duardo.neotransfer.plus.core.read

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ENZONA's date text, read without guessing a calendar: ISO with or without offset, or `yyyy-MM-dd HH:mm[:ss]`.
 * Text without an offset is Cuban local time. Anything else stays unread and is shown as sent.
 */
object RemoteDates {
    private val local = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
    )

    fun parse(text: String?, zone: ZoneId): Instant? {
        val value = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
        runCatching { return OffsetDateTime.parse(value).toInstant() }
        runCatching { return Instant.parse(value) }
        for (format in local) runCatching { return LocalDateTime.parse(value, format).atZone(zone).toInstant() }
        runCatching { return LocalDate.parse(value).atStartOfDay(zone).toInstant() }
        return null
    }
}
