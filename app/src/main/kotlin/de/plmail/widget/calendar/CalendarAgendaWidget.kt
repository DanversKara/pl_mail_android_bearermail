package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.plmail.R
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.min

/**
 * The agenda on the home screen: upcoming events grouped by day, soonest first — the schedule
 * widget from the reference.
 *
 * Resizable: the number of rows drawn follows the widget's height, so a 2×2 agenda shows the next
 * few events and a tall one shows the week. Like the month widget it reads the cache straight from
 * Room, so it renders even when the app process was not running. Every tap opens the calendar.
 */
class CalendarAgendaWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = LocalDate.now()
        val rows = runCatching {
            loadAgendaRows(today, AGENDA_LIMIT, context)
        }
            .getOrDefault(emptyList())

        provideContent { AgendaContent(context, today, rows) }
    }

    companion object {
        private val SMALL = DpSize(150.dp, 110.dp)
        private val MEDIUM = DpSize(220.dp, 260.dp)
        private val LARGE = DpSize(280.dp, 420.dp)

        /** Enough for the tallest supported size; the height bucket decides how many are drawn. */
        private const val AGENDA_LIMIT = 80
    }
}

@Composable
private fun AgendaContent(context: Context, today: LocalDate, rows: List<AgendaRow>) {
    val locale = Locale.getDefault()
    val openCalendar = actionStartActivity(openCalendarIntent(context))
    val size = LocalSize.current
    // Rows that fit: a short widget shows the next few events, a tall one the coming days.
    val maxRows = if (size.height <= 160.dp) 4 else if (size.height <= 320.dp) 8 else 16

    Column(
        modifier =
            GlanceModifier.fillMaxSize()
                .appWidgetBackground()
                .background(WidgetColors.card)
                .padding(12.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                text = today.format(java.time.format.DateTimeFormatter.ofPattern("MMMM", locale)),
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
                    GlanceModifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable(openCalendar),
                style =
                    TextStyle(
                        color = WidgetColors.muted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
        }

        Spacer(modifier = GlanceModifier.height(4.dp))

        if (rows.isEmpty()) {
            Text(
                text = context.getString(R.string.calendar_widget_empty),
                style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp),
            )
        } else {
            var shown = 0
            for ((dateString, dayRows) in rows.groupBy { it.date }) {
                if (shown >= maxRows) break
                val date = runCatching { LocalDate.parse(dateString) }.getOrNull() ?: continue
                val take = dayRows.take(min(dayRows.size, maxRows - shown))
                shown += take.size
                DayGroup(date, date == today, take, openCalendar)
            }
        }
    }
}

@Composable
private fun DayGroup(
    date: LocalDate,
    isToday: Boolean,
    rows: List<AgendaRow>,
    openCalendar: Action,
) {
    val locale = Locale.getDefault()

    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Vertical.Top,
    ) {
        Column(
            modifier = GlanceModifier.width(40.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        ) {
            Text(
                text = date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, locale),
                style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp),
            )
            Text(
                text = date.dayOfMonth.toString(),
                style =
                    TextStyle(
                        color = if (isToday) WidgetColors.today else WidgetColors.onCard,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
        }

        Spacer(modifier = GlanceModifier.width(8.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            rows.forEach { row ->
                Row(
                    modifier =
                        GlanceModifier.fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clickable(openCalendar),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Box(
                        modifier =
                            GlanceModifier.width(4.dp).fillMaxHeight().background(row.glanceColor())
                    ) {}
                    Spacer(modifier = GlanceModifier.width(10.dp))
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        row.shortTime()?.let { time ->
                            Text(
                                text = time,
                                style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp),
                            )
                        }
                        Text(
                            text = row.title,
                            maxLines = 1,
                            style = TextStyle(color = WidgetColors.onCard, fontSize = 13.sp),
                        )
                    }
                }
            }
        }
    }
}
