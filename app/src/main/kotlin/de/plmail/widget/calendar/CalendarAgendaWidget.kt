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
 * Resizable both ways and down to a single strip. The widget is told its exact size, works out what
 * fits, and fills that space with as many *days* as it can. Below [COMPACT_BELOW] it drops the
 * month title and the time line and shows `time title` on one line per event.
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

/** Glance containers are happiest with few children, so cap what one column is asked to hold. */
private const val MAX_DAYS = 8
private const val MAX_ROWS_PER_DAY = 9

/** Events of one day that share a color (and are all over, or all still to come): one block. */
private class Block(val rows: List<AgendaRow>, val past: Boolean)

/** One day as drawn: a month band first when the month turns, its own band, then its blocks. */
private class DayPlan(val date: LocalDate, val newMonth: Boolean, val blocks: List<Block>)

/** Heights in dp, per layout, that [planAgenda] packs against the widget's real height. */
private class Metrics(val band: Float, val blockPad: Float, val line: Float)

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

/**
 * Decides which days, and how many of each day's events, fit in [avail] dp.
 *
 * A day costs its band (and a month band when the month turns), each block its padding, and each
 * event a line. Whole days are packed in order until the space runs out; the last day may be cut
 * short. There is always at least one event when there is any, however small the widget is.
 */
private fun planAgenda(
    rows: List<AgendaRow>,
    today: LocalDate,
    now: LocalDateTime,
    avail: Float,
    m: Metrics,
): List<DayPlan> {
    val plan = mutableListOf<DayPlan>()
    var used = 0f
    var month = YearMonth.from(today)

    for ((dateString, dayRows) in rows.groupBy { it.date }) {
        val date = runCatching { LocalDate.parse(dateString) }.getOrNull() ?: continue
        val newMonth = YearMonth.from(date) != month
        val bands = m.band * (if (newMonth) 2 else 1)

        // Grow the day an event at a time while it still fits.
        val limit = minOf(dayRows.size, MAX_ROWS_PER_DAY)
        var take = 0
        while (take < limit) {
            val blocks = blocksOf(dayRows.take(take + 1), today, now)
            val cost = bands + blocks.size * m.blockPad + (take + 1) * m.line
            if (used + cost > avail) break
            take++
        }
        if (take == 0) {
            if (plan.isEmpty()) take = 1 else break
        }

        val blocks = blocksOf(dayRows.take(take), today, now)
        plan += DayPlan(date, newMonth, blocks)
        used += bands + blocks.size * m.blockPad + take * m.line
        month = YearMonth.from(date)
        if (take < dayRows.size || plan.size >= MAX_DAYS) break
    }
    return plan
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
    val headerH = if (compact) 0f else 40f
    val metrics =
        if (compact) Metrics(band = 16f, blockPad = 6f, line = 18f)
        else Metrics(band = 22f, blockPad = 12f, line = 38f)
    val avail = size.height.value - pad * 2 - headerH - 2f
    val plan = planAgenda(rows, today, now, avail, metrics)

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

        if (plan.isEmpty()) {
            Text(
                text = context.getString(R.string.calendar_widget_empty),
                modifier = GlanceModifier.padding(horizontal = 8.dp).clickable(openCalendar),
                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
            )
        } else {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                plan.forEach { day -> DayGroup(day, today, compact, allDay, openCalendar) }
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

/** One day: the month band when the month turns, its own band, then its blocks edge to edge. */
@Composable
private fun DayGroup(
    day: DayPlan,
    today: LocalDate,
    compact: Boolean,
    allDay: String,
    openCalendar: Action,
) {
    val locale = Locale.getDefault()

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        if (day.newMonth) Band(monthName(day.date, locale), false, compact, openCalendar)
        Band(dayName(day.date, locale), day.date == today, compact, openCalendar)
        day.blocks.forEach { block -> EventBlock(block, compact, allDay, openCalendar) }
    }
}

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
