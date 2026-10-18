@file:Suppress("NonObservableLocale")

package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.plmail.R
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The agenda on the home screen, laid out after the owner's own sketch: the month across the top,
 * then for each day a full-width band with its name ("MONDAY 19"), and under it the day's events as
 * edge-to-edge blocks filled with their calendar's color — the title in bold, the start – end time
 * under it. Events of one day that share a color sit together in one block. When the list runs into
 * the next month, a band with that month's name ("NOVEMBER") marks the turn. Text on a block is
 * black or white, whichever reads on that color.
 *
 * The days are a scrolling list, as in Google Calendar's own agenda widget: the widget fills
 * whatever height it really has and scrolls for the rest. It used to work out from the size the
 * launcher reported how many days would fit — and launchers that report far less than the widget's
 * real height left it showing a single event. Below [COMPACT_BELOW] it drops the month title and
 * the time line and shows `time title` on one line per event.
 *
 * Reads the cache straight from Room, so it renders even when the app process was not running.
 */
class CalendarAgendaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val rows = runCatching {
            loadAgendaRows(today, AGENDA_LIMIT, context)
        }
            .getOrDefault(emptyList())

        provideContent { AgendaContent(context, today, now, rows) }
    }

    companion object {
        /** More than any size can draw; the height decides how many are shown. */
        private const val AGENDA_LIMIT = 150
    }
}

/** Below this height the widget switches to the one-line-per-event strip. */
private val COMPACT_BELOW = 120.dp

/** How far ahead, and how many events, the list goes: well past a week, never unbounded. */
private const val DAYS_AHEAD = 31L
private const val MAX_EVENTS = 80

/** Events of one day that share a color (and are all over, or all still to come): one block. */
private class Block(val rows: List<AgendaRow>, val past: Boolean)

/** One line of the scrolling list: a month band, a day band, or a block of events. */
private sealed interface Entry {
    val key: Long

    class MonthBand(val date: LocalDate) : Entry {
        override val key = date.toEpochDay() * 1000 + 1
    }

    class DayBand(val date: LocalDate) : Entry {
        override val key = date.toEpochDay() * 1000 + 2
    }

    class Events(val date: LocalDate, val index: Int, val block: Block) : Entry {
        override val key = date.toEpochDay() * 1000 + 10 + index
    }
}

/** Consecutive events with the same color and the same past/upcoming state share one block. */
private fun blocksOf(rows: List<AgendaRow>, today: LocalDate, now: LocalDateTime): List<Block> {
    val blocks = mutableListOf<Block>()
    var current = mutableListOf<AgendaRow>()
    var currentPast = false
    for (row in rows) {
        val past = row.date == today.toString() && row.isPast(now)
        val first = current.firstOrNull()
        if (first != null && (first.calendarColor != row.calendarColor || currentPast != past)) {
            blocks += Block(current, currentPast)
            current = mutableListOf()
        }
        current += row
        currentPast = past
    }
    if (current.isNotEmpty()) blocks += Block(current, currentPast)
    return blocks
}

/** The list the widget scrolls: every day with events in the next [DAYS_AHEAD] days. */
private fun entriesOf(rows: List<AgendaRow>, today: LocalDate, now: LocalDateTime): List<Entry> {
    val entries = mutableListOf<Entry>()
    val last = today.plusDays(DAYS_AHEAD)
    var month = YearMonth.from(today)
    val days =
        rows.take(MAX_EVENTS).groupBy { it.date }.mapNotNull { (dateString, dayRows) ->
            runCatching { LocalDate.parse(dateString) }.getOrNull()?.let { it to dayRows }
        }
    for ((date, dayRows) in days) {
        if (date.isAfter(last)) break
        if (YearMonth.from(date) != month) {
            entries += Entry.MonthBand(date)
            month = YearMonth.from(date)
        }
        entries += Entry.DayBand(date)
        blocksOf(dayRows, today, now).forEachIndexed { index, block ->
            entries += Entry.Events(date, index, block)
        }
    }
    return entries
}

@Composable
private fun AgendaContent(
    context: Context,
    today: LocalDate,
    now: LocalDateTime,
    rows: List<AgendaRow>,
) {
    val locale = Locale.getDefault()
    val openCalendar = actionStartActivity(openCalendarIntent(context))
    val size = LocalSize.current
    val compact = size.height < COMPACT_BELOW
    val allDay = context.getString(R.string.calendar_widget_all_day)

    val pad = if (compact) 4f else 8f
    val entries = entriesOf(rows, today, now)

    Column(
        modifier =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(WidgetColors.card)
                .padding(horizontal = 4.dp, vertical = pad.dp)
    ) {
        if (!compact) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = today.format(DateTimeFormatter.ofPattern("LLLL", locale)),
                    modifier = GlanceModifier.defaultWeight().clickable(openCalendar),
                    style =
                        TextStyle(
                            color = WidgetColors.onCard,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                )
                Text(
                    text = "+",
                    modifier =
                        GlanceModifier.padding(horizontal = 10.dp, vertical = 2.dp)
                            .clickable(openCalendar),
                    style =
                        TextStyle(
                            color = WidgetColors.muted,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                )
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
        }

        if (entries.isEmpty()) {
            Text(
                text = context.getString(R.string.calendar_widget_empty),
                modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(openCalendar),
                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(entries, itemId = { it.key }) { entry ->
                    when (entry) {
                        is Entry.MonthBand ->
                            Band(monthName(entry.date, locale), false, compact, openCalendar)
                        is Entry.DayBand -> {
                            val isToday = entry.date == today
                            Band(dayName(entry.date, locale), isToday, compact, openCalendar)
                        }
                        is Entry.Events -> EventBlock(entry.block, compact, allDay, openCalendar)
                    }
                }
            }
        }
    }
}

/** A full-width band with centered, bold, upper-case text: a day's name or a month's. */
@Composable
private fun Band(text: String, highlight: Boolean, compact: Boolean, openCalendar: Action) {
    Box(
        modifier =
            GlanceModifier.fillMaxWidth()
                .height(if (compact) 16.dp else 22.dp)
                .background(if (highlight) WidgetColors.today else WidgetColors.band)
                .clickable(openCalendar),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 1,
            style =
                TextStyle(
                    color = if (highlight) WidgetColors.onToday else WidgetColors.onCard,
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/** "MONDAY 19", in the device's language. */
private fun dayName(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("EEEE d", locale)).uppercase(locale)

/** "NOVEMBER", in the device's language. */
private fun monthName(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofPattern("LLLL", locale)).uppercase(locale)

/** Black or white, whichever reads on [background]. */
private fun readableOn(background: Color): ColorProvider {
    val ink = if (background.luminance() > 0.5f) Color.Black else Color.White
    return ColorProvider(day = ink, night = ink)
}

/**
 * One block in the calendar's color, each of its events as a bold title with the time under it (or
 * `time title` on one line in the compact strip). A block already over is a soft tint.
 */
@Composable
private fun EventBlock(block: Block, compact: Boolean, allDay: String, openCalendar: Action) {
    val first = block.rows.first()
    val base = first.colorOrNull()
    val fill = if (block.past) first.chipTint() else first.glanceColor()
    val ink =
        when {
            block.past -> WidgetColors.muted
            base != null -> readableOn(base)
            else -> WidgetColors.onSolid
        }

    val gap = if (compact) 1.dp else 2.dp
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = gap)) {
        Column(
            modifier =
                GlanceModifier.fillMaxWidth()
                    .background(fill)
                    .cornerRadius(if (compact) 4.dp else 6.dp)
                    .padding(horizontal = 8.dp, vertical = if (compact) 1.dp else 4.dp)
                    .clickable(openCalendar)
        ) {
            block.rows.forEach { row ->
                if (compact) CompactLine(row, ink, allDay) else FullLines(row, ink, allDay)
            }
        }
    }
}

@Composable
private fun FullLines(row: AgendaRow, ink: ColorProvider, allDay: String) {
    Text(
        text = row.title,
        maxLines = 1,
        style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
    )
    Text(
        text = row.timeRange(allDay),
        maxLines = 1,
        style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun CompactLine(row: AgendaRow, ink: ColorProvider, allDay: String) {
    Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(
            text = if (row.isAllDay) allDay else (row.shortTime() ?: ""),
            maxLines = 1,
            style = TextStyle(color = ink, fontSize = 11.sp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = row.title,
            maxLines = 1,
            style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
    }
}
