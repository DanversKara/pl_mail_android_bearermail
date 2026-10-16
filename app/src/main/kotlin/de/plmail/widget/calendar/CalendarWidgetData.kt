package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.plmail.core.database.AgendaRow
import de.plmail.core.database.CalendarEventDao
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.first

/**
 * Reads the calendar cache for the home-screen widgets.
 *
 * A widget update runs in this process but outside any activity: the launcher asks Glance, Glance
 * starts the process if it is not running, and there is no Hilt-injected component on that path.
 * The [EntryPoint] is how a widget reaches the same [CalendarEventDao] the calendar screens use. It
 * mirrors what PushWorker does for the same reason.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarWidgetDependencies {
    fun calendarEvents(): CalendarEventDao
}

internal suspend fun widgetEventDao(context: Context): CalendarEventDao =
    EntryPointAccessors.fromApplication(context, CalendarWidgetDependencies::class.java)
        .calendarEvents()

/** The wire's "cancelled", matching `STATUS_CANCELLED` in :feature:calendar. */
private const val STATUS_CANCELLED = "cancelled"

/**
 * One row per meeting: hidden calendars are the user's explicit "not this one", and a cancelled
 * series is shown struck-through in the app — a widget has no room for that nuance, so it is left
 * out rather than drawn as if it were happening.
 */
private fun List<AgendaRow>.forWidget(): List<AgendaRow> = filter {
    it.calendarIsVisible != false && it.status != STATUS_CANCELLED
}
    .distinctBy { it.eventUid ?: (it.eventKey + "@" + it.date + "@" + (it.startLocal ?: "")) }

/** Everything in [month] for the month grid, earliest first. */
internal suspend fun loadMonthRows(month: YearMonth, context: Context): List<AgendaRow> =
    widgetEventDao(context)
        .observeBetween(month.atDay(1).toString(), month.plusMonths(1).atDay(1).toString())
        .first()
        .forWidget()

/** Everything from [from] onwards for the agenda, earliest first. */
internal suspend fun loadAgendaRows(
    from: LocalDate,
    limit: Int,
    context: Context,
): List<AgendaRow> =
    widgetEventDao(context).observeAgenda(from.toString(), limit).first().forWidget()

/** "10:00" in the device's own time pattern, or null for an all-day event. */
internal fun AgendaRow.shortTime(): String? {
    if (isAllDay) return null
    val time =
        startLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }?.toLocalTime()
            ?: return null
    return time.format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    )
}

/** The calendar's color as a Compose color, or null when it is missing or unparsable. */
internal fun AgendaRow.colorOrNull(): Color? = calendarColor?.let {
    runCatching { Color(it.toColorInt()) }.getOrNull()
}

/** The calendar's color as a Glance [ColorProvider], falling back to muted gray. */
internal fun AgendaRow.glanceColor(): ColorProvider {
    val base = colorOrNull()
    return if (base != null) ColorProvider(day = base, night = base) else WidgetColors.fallbackEvent
}
