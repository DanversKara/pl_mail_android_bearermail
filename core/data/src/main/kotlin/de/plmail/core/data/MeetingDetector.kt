package de.plmail.core.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * BearerMail: a plan written in plain words, ready to become a calendar event.
 *
 * [startWire] is `yyyy-MM-ddTHH:mm` when a time was found, `yyyy-MM-dd` when only a day was.
 */
data class PlanSuggestion(
    val title: String,
    val startWire: String,
    val hasTime: Boolean,
    val location: String?,
    val description: String,
) {
    val start: LocalDateTime
        get() =
            if (hasTime) LocalDateTime.parse(startWire)
            else LocalDate.parse(startWire).atStartOfDay()
}

/**
 * Finds "meet me at 218 Main St on Friday at 10am" in a message.
 *
 * The same rules as the server's `MeetingExtractor`, so the phone and the web agree on which mails
 * get an "Add to calendar" card: a time (or a date) is needed, plus a plan word ("meet", "lunch",
 * "appointment"…) or an actual address. Nothing is added to the calendar by this — the card opens
 * the event editor filled in, and the user saves it.
 */
object MeetingDetector {

    private val PLAN_WORDS =
        listOf(
            "meet",
            "meeting",
            "appointment",
            "appt",
            "lunch",
            "dinner",
            "breakfast",
            "brunch",
            "coffee",
            "drinks",
            "interview",
            "party",
            "pick up",
            "pickup",
            "pick you up",
            "see you",
            "see ya",
            "call",
            "visit",
            "reservation",
            "booked",
            "schedule",
            "come by",
            "come over",
            "stop by",
            "hang out",
            "hangout",
            "date night",
            "treffen",
            "termin",
            "verabredung",
        )

    private val MONTHS =
        mapOf(
            "jan" to 1,
            "feb" to 2,
            "mar" to 3,
            "apr" to 4,
            "may" to 5,
            "jun" to 6,
            "jul" to 7,
            "aug" to 8,
            "sep" to 9,
            "oct" to 10,
            "nov" to 11,
            "dec" to 12,
        )

    private val WEEKDAYS =
        listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    private const val MAX_BODY = 6000
    private const val MONTH_NAMES = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"

    private val TIME_12 =
        Regex(
            """\b(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""",
            RegexOption.IGNORE_CASE,
        )
    private val NOON = Regex("""\b(noon|midday)\b""", RegexOption.IGNORE_CASE)
    private val TIME_24 =
        Regex("""(?:\bat|\bum|@)\s*([01]?\d|2[0-3]):([0-5]\d)\b""", RegexOption.IGNORE_CASE)
    private val MONTH_DAY =
        Regex(
            """\b($MONTH_NAMES)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s*(\d{4}))?""",
            RegexOption.IGNORE_CASE,
        )
    private val DAY_MONTH =
        Regex(
            """\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTH_NAMES)[a-z]*\b(?:,?\s*(\d{4}))?""",
            RegexOption.IGNORE_CASE,
        )
    private val ISO = Regex("""\b(20\d\d)-(\d{1,2})-(\d{1,2})\b""")
    private val US = Regex("""\b(1[0-2]|0?[1-9])/([12]\d|3[01]|0?[1-9])(?:/(\d{2}|\d{4}))?\b""")
    private val TOMORROW = Regex("""\b(tomorrow|tmrw|tmr)\b""")
    private val TODAY = Regex("""\b(today|tonight|this evening|this afternoon|this morning)\b""")
    private val WEEKDAY = Regex("""\b(next|this|on|coming)?\s*(${WEEKDAYS.joinToString("|")})\b""")
    private val STREET =
        Regex(
            """\b\d{1,6}\s+[A-Za-z0-9][A-Za-z0-9 .'#&-]{2,60}(?:,\s*[A-Za-z0-9][A-Za-z0-9 .'#&-]{1,60}){0,3},?\s+[A-Z]{2}\s+\d{5}(?:-\d{4})?\b"""
        )
    private val LABELLED_PLACE =
        Regex(
            """\b(?:address|location|where|place|venue|adresse|ort)\s*[:\-]\s*([^\n]{4,160})""",
            RegexOption.IGNORE_CASE,
        )
    private val NEXT_FIELD =
        Regex(
            """\s+(?:phone|tel|telephone|time|when|date|at\s+\d)\b\s*[:\-]?|[.!?](?:\s|$)""",
            RegexOption.IGNORE_CASE,
        )

    /** [received] is when the mail arrived, on the phone's own clock. */
    fun detect(
        subject: String?,
        body: String,
        sender: String?,
        received: LocalDateTime,
    ): PlanSuggestion? {
        if (body.length > MAX_BODY) return null

        val text = (subject.orEmpty() + "\n" + body).replace(Regex("""[ \t ]+"""), " ")
        val lower = text.lowercase()

        val time = findTime(text)
        val date = findDate(text, received)
        // Addresses often wrap onto two lines in the sender's mail app, so read them flattened.
        val location = findAddress(text.replace(Regex("""\s*\n\s*"""), " "))
        val hasPlan = PLAN_WORDS.any { Regex("""\b""" + Regex.escape(it)).containsMatchIn(lower) }

        if (time == null && date == null) return null
        if (!hasPlan && location == null) return null
        if (time == null && (location == null || !hasPlan)) return null

        val day = date ?: received.toLocalDate()
        var start = if (time != null) day.atTime(time.first) else day.atStartOfDay()

        if (time != null && date == null && !start.isAfter(received)) start = start.plusDays(1)
        if (start.isBefore(received.minusDays(1))) return null

        val anchor = time?.second ?: date?.toString().orEmpty()
        val title =
            subject
                .orEmpty()
                .replace(Regex("""^\s*((re|fwd?|aw|wg)\s*:\s*)+""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Meet ${sender.orEmpty()}".trim() }
                .take(200)

        return PlanSuggestion(
            title = title,
            startWire =
                if (time != null) start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                else start.toLocalDate().toString(),
            hasTime = time != null,
            location = location,
            description =
                buildString {
                    append("From an email")
                    if (!sender.isNullOrBlank()) append(" by ").append(sender)
                    append(":\n")
                    append(sentenceAround(text, anchor))
                },
        )
    }

    /** Readable text from an HTML body, good enough for finding dates and addresses. */
    fun textOf(html: String): String =
        html
            .replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), " ")
            .replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>|</tr>|</h\d>"""), "\n")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun findTime(text: String): Pair<LocalTime, String>? {
        TIME_12.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt() % 12
            if (m.groupValues[3].lowercase().startsWith("p")) hour += 12
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            return LocalTime.of(hour, minute) to m.value
        }
        NOON.find(text)?.let {
            return LocalTime.NOON to it.value
        }
        TIME_24.find(text)?.let { m ->
            return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) to m.value
        }
        return null
    }

    private fun findDate(text: String, received: LocalDateTime): LocalDate? {
        val today = received.toLocalDate()
        val year = today.year

        MONTH_DAY.find(text)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()
            return dayOf(
                today,
                y ?: year,
                MONTHS.getValue(m.groupValues[1].lowercase().take(3)),
                m.groupValues[2].toInt(),
                y == null,
            )
        }
        DAY_MONTH.find(text)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()
            return dayOf(
                today,
                y ?: year,
                MONTHS.getValue(m.groupValues[2].lowercase().take(3)),
                m.groupValues[1].toInt(),
                y == null,
            )
        }
        ISO.find(text)?.let { m ->
            return dayOf(
                today,
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
                false,
            )
        }
        US.find(text)?.let { m ->
            var y = m.groupValues[3].toIntOrNull()
            if (y != null && y < 100) y += 2000
            return dayOf(
                today,
                y ?: year,
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                y == null,
            )
        }

        val lower = text.lowercase()
        if (TOMORROW.containsMatchIn(lower)) return today.plusDays(1)
        if (TODAY.containsMatchIn(lower)) return today
        WEEKDAY.find(lower)?.let { m ->
            val target = WEEKDAYS.indexOf(m.groupValues[2]) + 1
            var d = today.plusDays(1)
            while (d.dayOfWeek.value != target) d = d.plusDays(1)
            return d
        }
        return null
    }

    private fun dayOf(
        today: LocalDate,
        year: Int,
        month: Int,
        day: Int,
        yearGuessed: Boolean,
    ): LocalDate? {
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return if (yearGuessed && date.isBefore(today.minusDays(180))) date.plusYears(1) else date
    }

    private fun findAddress(text: String): String? {
        STREET.find(text)?.let { m ->
            val segments = m.value.split(',')
            val first = segments.indexOfFirst {
                Regex("""^\s*\d{1,6}\s+[A-Za-z]""").containsMatchIn(it) &&
                    !Regex("""\b(am|pm|at)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            return if (first < 0) null else tidy(segments.drop(first).joinToString(","))
        }
        LABELLED_PLACE.find(text)?.let { m ->
            val line = m.groupValues[1].split(NEXT_FIELD).first()
            return line.takeIf { it.isNotBlank() }?.let(::tidy)
        }
        return null
    }

    private fun sentenceAround(text: String, needle: String): String {
        val pos = if (needle.isEmpty()) -1 else text.indexOf(needle, ignoreCase = true)
        if (pos < 0) return tidy(text.take(240))
        val start = maxOf(0, pos - 160)
        val end = minOf(text.length, start + 320)
        return tidy(
            (if (start > 0) "…" else "") +
                text.substring(start, end) +
                (if (end < text.length) "…" else "")
        )
    }

    private fun tidy(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().trim(',', '.', ';', ' ')
}
