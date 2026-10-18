@file:Suppress("NonObservableLocale")

package de.plmail.widget.calendar

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.Alignment
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.plmail.R
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The agenda on the home screen: upcoming events grouped by day, soonest first, laid out like
 * Google Calendar's schedule widget — each day gets a header line on top ("Today · Sun, Oct 18"),
 * and under it every event is a full-width chip filled with its calendar's color, the title on the
 * first line and the start – end time under it. Nothing sits beside the events, so the title and
 * time use the whole width of the widget.
 *
 * Resizable both ways and down to a single strip. The widget is told its exact size, works out how
 * many rows fit, and fills that space with as many *days* as it can, so a tall widget shows the
 * whole week rather than one crowded date. Below [COMPACT_BELOW] it drops the month header and the
 * second text line and shows `time title` on one line per event, to spend as little screen as
 * possible.
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

private class DayPlan(val date: LocalDate, val rows: List<AgendaRow>)

/**
 * Decides which days, and how many of each day's events, fit in [avail] dp.
 *
 * Every day costs its header ([dayHeaderH]) plus [rowH] per event. Whole days are packed in order
 * until the space runs out; the last day may be cut short. There is always at least one event when
 * there is any, however small the widget is.
 */
private fun planAgenda(
    rows: List<AgendaRow>,
    avail: Float,
    rowH: Float,
    dayHeaderH: Float,
): List<DayPlan> {
    val plan = mutableListOf<DayPlan>()
    var used = 0f

    for ((dateString, dayRows) in rows.groupBy { it.date }) {
        val date = runCatching { LocalDate.parse(dateString) }.getOrNull() ?: continue
        val limit = minOf(dayRows.size, MAX_ROWS_PER_DAY)
        var take = 0
        while (take < limit && used + dayHeaderH + (take + 1) * rowH <= avail) take++
        if (take == 0) {
            if (plan.isEmpty()) take = 1 else break
        }
        plan += DayPlan(date, dayRows.take(take))
        used += dayHeaderH + take * rowH
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
    val pad = if (compact) 6.dp else 12.dp
    val allDay = context.getString(R.string.calendar_widget_all_day)

    val headerH = if (compact) 0f else 34f
    val dayHeaderH = if (compact) 18f else 24f
    val rowH = if (compact) 24f else 48f
    val avail = size.height.value - pad.value * 2 - headerH - 2f
    val plan = planAgenda(rows, avail, rowH, dayHeaderH)

    Column(
        modifier =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(WidgetColors.card)
                .padding(pad)
    ) {
        if (!compact) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = today.format(DateTimeFormatter.ofPattern("MMMM", locale)),
                    modifier = GlanceModifier.defaultWeight().clickable(openCalendar),
                    style =
                        TextStyle(
                            color = WidgetColors.onCard,
                            fontSize = 18.sp,
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
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                )
            }
            Spacer(modifier = GlanceModifier.height(2.dp))
        }

        if (plan.isEmpty()) {
            Text(
                text = context.getString(R.string.calendar_widget_empty),
                modifier = GlanceModifier.clickable(openCalendar),
                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
            )
        } else {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                plan.forEach { day ->
                    DayGroup(context, day, today, now, compact, allDay, openCalendar)
                }
            }
        }
    }
}

/** "Today · Sun, Oct 18", "Tomorrow · Mon, Oct 19", or just "Tue, Oct 20" further out. */
private fun dayLabel(context: Context, date: LocalDate, today: LocalDate, locale: Locale): String {
    // The locale's own order and punctuation for weekday + month + day ("So., 18. Okt." in German).
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEMMMd")
    val dated = date.format(DateTimeFormatter.ofPattern(pattern, locale))
    return when (date) {
        today -> context.getString(R.string.calendar_widget_today) + " · " + dated
        today.plusDays(1) -> context.getString(R.string.calendar_widget_tomorrow) + " · " + dated
        else -> dated
    }
}

/** One day: its date as a header line across the top, then its events stacked full-width below. */
@Composable
private fun DayGroup(
    context: Context,
    day: DayPlan,
    today: LocalDate,
    now: LocalDateTime,
    compact: Boolean,
    allDay: String,
    openCalendar: Action,
) {
    val locale = Locale.getDefault()
    val isToday = day.date == today

    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp)) {
        Text(
            text = dayLabel(context, day.date, today, locale),
            maxLines = 1,
            modifier =
                GlanceModifier.fillMaxWidth()
                    .padding(bottom = if (compact) 1.dp else 3.dp)
                    .clickable(openCalendar),
            style =
                TextStyle(
                    color = if (isToday) WidgetColors.today else WidgetColors.muted,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
        day.rows.forEach { row ->
            val past = isToday && row.isPast(now)
            if (compact) {
                CompactEvent(row, past, allDay, openCalendar)
            } else {
                FullEvent(row, past, allDay, openCalendar)
            }
        }
    }
}

/** The chip's fill: the calendar's own color, softened once the event is over. */
private fun AgendaRow.chipFill(past: Boolean): ColorProvider =
    if (past) chipTint() else glanceColor()

private fun chipText(past: Boolean): ColorProvider =
    if (past) WidgetColors.muted else WidgetColors.onSolid

/** A full-width chip in the calendar's color: title, and "10:00 – 11:00" beneath it. */
@Composable
private fun FullEvent(
    row: AgendaRow,
    past: Boolean,
    allDay: String,
    openCalendar: Action,
) {
    val text = chipText(past)
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp).clickable(openCalendar)
    ) {
        Column(
            modifier =
                GlanceModifier.fillMaxWidth()
                    .background(row.chipFill(past))
                    .cornerRadius(8.dp)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = row.title,
                maxLines = 1,
                style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            Text(
                text = row.timeRange(allDay),
                maxLines = 1,
                style = TextStyle(color = text, fontSize = 11.sp),
            )
        }
    }
}

/** One line in a full-width colored chip: start time, title. For the smallest sizes. */
@Composable
private fun CompactEvent(
    row: AgendaRow,
    past: Boolean,
    allDay: String,
    openCalendar: Action,
) {
    val text = chipText(past)
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp).clickable(openCalendar)
    ) {
        Row(
            modifier =
                GlanceModifier.fillMaxWidth()
                    .background(row.chipFill(past))
                    .cornerRadius(6.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = if (row.isAllDay) allDay else (row.shortTime() ?: ""),
                maxLines = 1,
                style = TextStyle(color = text, fontSize = 11.sp),
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = row.title,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = text, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}
