package de.plmail.core.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import de.plmail.core.database.AgendaRow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * BearerMail: putting one of your calendar events into an email.
 *
 * Lists what is coming up, writes the details as a short block for the message body, and saves the
 * event as an `.ics` file the composer attaches — so the recipient's mail app offers its own "Add to
 * calendar". The file lands in `cache/drive/`, which the app's FileProvider already shares.
 */
@Singleton
class EventShare
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val calendar: CalendarRepository,
) {

    /** Events from today on, soonest first. Refreshes the next three months first, best effort. */
    suspend fun upcoming(): List<AgendaRow> {
        val today = LocalDate.now()
        runCatching { calendar.refresh(CalendarWindow.around(today, before = 0, after = 90)) }
        return calendar.agenda(today, limit = 200).first()
            .filter { it.status != "cancelled" }
            .distinctBy { it.eventKey + "@" + (it.startLocal ?: it.date) }
    }

    /** "Sat, Oct 12, 10:00 AM – 11:00 AM", or just the day for an all-day event. */
    fun whenText(row: AgendaRow): String {
        val start = row.startLocal?.let(::localOrNull)
        if (row.isAllDay || start == null) {
            return runCatching { LocalDate.parse(row.date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) }
                .getOrDefault(row.date)
        }
        val end = row.endLocal?.let(::localOrNull)
        val day = start.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return if (end == null) "$day, ${start.format(time)}"
        else "$day, ${start.format(time)} – ${end.format(time)}"
    }

    /** The block written into the message: title, when, where. HTML-escaped. */
    fun bodyHtml(row: AgendaRow, whenLabel: String, whereLabel: String): String =
        buildString {
            append("<p><b>📅 ").append(escapeHtml(row.title.ifBlank { "Event" })).append("</b><br>")
            append(escapeHtml(whenLabel)).append(": ").append(escapeHtml(whenText(row)))
            row.location?.takeIf { it.isNotBlank() }?.let {
                append("<br>").append(escapeHtml(whereLabel)).append(": ").append(escapeHtml(it))
            }
            append("</p>")
        }

    /** Writes the event as an .ics file and returns a content URI the composer can attach. */
    suspend fun icsFile(row: AgendaRow): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "drive").apply { mkdirs() }
            val safe = row.title.ifBlank { "event" }.replace(Regex("""[\\/:*?"<>|]+"""), "-").take(60)
            val file = File(dir, "$safe.ics")
            file.writeText(ics(row))
            FileProvider.getUriForFile(context, "${context.packageName}.blobs", file)
        }

    internal fun ics(row: AgendaRow): String {
        val zone = row.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val dateOnly = DateTimeFormatter.ofPattern("yyyyMMdd")
        val start = row.startLocal?.let(::localOrNull)
        val end = row.endLocal?.let(::localOrNull)

        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//BearerMail//Android//EN",
            "METHOD:PUBLISH",
            "BEGIN:VEVENT",
            "UID:" + (row.eventUid ?: (row.eventKey + "@bearermail")),
            "DTSTAMP:" + Instant.now().atOffset(ZoneOffset.UTC).format(utc),
        )

        if (row.isAllDay || start == null) {
            val day = start?.toLocalDate() ?: LocalDate.parse(row.date)
            val last = end?.toLocalDate()?.takeIf { it.isAfter(day) } ?: day.plusDays(1)
            lines += "DTSTART;VALUE=DATE:" + day.format(dateOnly)
            lines += "DTEND;VALUE=DATE:" + last.format(dateOnly)
        } else {
            lines += "DTSTART:" + start.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(utc)
            lines += "DTEND:" + (end ?: start.plusHours(1)).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(utc)
        }

        lines += "SUMMARY:" + escapeIcs(row.title.ifBlank { "Event" })
        row.location?.takeIf { it.isNotBlank() }?.let { lines += "LOCATION:" + escapeIcs(it) }
        row.description?.takeIf { it.isNotBlank() }?.let { lines += "DESCRIPTION:" + escapeIcs(it) }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"

        return lines.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }

    private fun localOrNull(value: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(value) }.getOrNull()

    private fun escapeIcs(value: String): String =
        value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n")

    /** RFC 5545 line folding at 75 octets. */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var count = 0
        line.codePoints().forEach { cp ->
            val piece = String(Character.toChars(cp))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (count + size > 75) {
                out.append("\r\n ")
                count = 1
            }
            out.append(piece)
            count += size
        }
        return out.toString()
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
