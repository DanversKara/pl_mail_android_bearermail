@file:Suppress("NonObservableLocale")

package de.plmail.widget.calendar

import android.content.Context
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
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
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.max

/**
 * The agenda on the home screen: upcoming events grouped by day, soonest first, laid out like
 * Google Calendar's schedule widget — a date column on the left, and per event a colored bar, the
 * title, and the start – end time under it.
 *
 * Resizable both ways and down to a single strip. The widget is told its exact size, works out how
 * many rows fit, and fills that space with as many *days* as it can, so a tall widget shows the
 * whole week rather than one crowded date. Below [COMPACT_BELOW] it drops the header and the second
 * text line and shows `time  title` on one line per event, to spend as little screen as possible.
 *
 * Reads the cache straight from Room, so it renders even when the app process was not running.
 */
class CalendarAgendaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val rows = runCatching { loadAgendaRows(today, AGENDA_LIMIT, context) }
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
 * Whole days are packed in order until the space runs out; the last day may be cut short. There is
 * always at least one event when there is any, however small the widget is.
 */
private fun planAgenda(
    rows: List<AgendaRow>,
    avail: Float,
    rowH: Float,
    dayMin: Float,
): List<DayPlan> {
    val plan = mutableListOf<DayPlan>()
    var used = 0f

    for ((dateString, dayRows) in rows.groupBy { it.date }) {
        val date = runCatching { LocalDate.parse(dateString) }.getOrNull() ?: continue
        val limit = minOf(dayRows.size, MAX_ROWS_PER_DAY)
        var take = 0
        while (take < limit && used + max((take + 1) * rowH, dayMin) <= avail) take++
        if (take == 0) {
            if (plan.isEmpty()) take = 1 else break
        }
        plan += DayPlan(date, dayRows.take(take))
        used += max(take * rowH, dayMin)
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
    val rowH = if (compact) 24f else 42f
    val dayMin = if (compact) 30f else 36f
    val avail = size.height.value - pad.value * 2 - headerH - 2f
    val plan = planAgenda(rows, avail, rowH, dayMin)

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
                    DayGroup(day, today, now, compact, allDay, openCalendar)
                }
            }
        }
    }
}

@Composable
private fun DayGroup(
    day: DayPlan,
    today: LocalDate,
    now: LocalDateTime,
    compact: Boolean,
    allDay: String,
    openCalendar: Action,
) {
    val locale = Locale.getDefault()
    val isToday = day.date == today

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 1.dp else 2.dp),
        verticalAlignment = Alignment.Vertical.Top,
    ) {
        Column(
            modifier = GlanceModifier.width(38.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = day.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale),
                maxLines = 1,
                style =
                    TextStyle(
                        color = if (isToday) WidgetColors.today else WidgetColors.muted,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    ),
            )
            Box(
                modifier =
                    if (isToday) {
                        GlanceModifier.size(if (compact) 20.dp else 24.dp)
                            .background(WidgetColors.today)
                            .cornerRadius(12.dp)
                    } else {
                        GlanceModifier.size(if (compact) 20.dp else 24.dp)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style =
                        TextStyle(
                            color = if (isToday) WidgetColors.onToday else WidgetColors.onCard,
                            fontSize = if (compact) 13.sp else 15.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        ),
                )
            }
        }

        Spacer(modifier = GlanceModifier.width(6.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            day.rows.forEach { row ->
                val past = isToday && row.isPast(now)
                val titleColor = if (past) WidgetColors.muted else WidgetColors.onCard
                if (compact) {
                    CompactEvent(row, titleColor, allDay, openCalendar)
                } else {
                    FullEvent(row, titleColor, allDay, openCalendar)
                }
            }
        }
    }
}

/** Colored bar, title, and "10:00 – 11:00" beneath it. */
@Composable
private fun FullEvent(
    row: AgendaRow,
    titleColor: ColorProvider,
    allDay: String,
    openCalendar: Action,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openCalendar),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier =
                GlanceModifier.width(4.dp)
                    .height(34.dp)
                    .background(row.glanceColor())
                    .cornerRadius(2.dp)
        ) {}
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.title,
                maxLines = 1,
                style =
                    TextStyle(
                        color = titleColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
            Text(
                text = row.timeRange(allDay),
                maxLines = 1,
                style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp),
            )
        }
    }
}

/** One line: bar, start time, title. For the smallest sizes. */
@Composable
private fun CompactEvent(
    row: AgendaRow,
    titleColor: ColorProvider,
    allDay: String,
    openCalendar: Action,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(openCalendar),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Box(
            modifier =
                GlanceModifier.width(3.dp)
                    .height(16.dp)
                    .background(row.glanceColor())
                    .cornerRadius(2.dp)
        ) {}
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = if (row.isAllDay) allDay else (row.shortTime() ?: ""),
            maxLines = 1,
            style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp),
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = row.title,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = titleColor, fontSize = 12.sp),
        )
    }
}
