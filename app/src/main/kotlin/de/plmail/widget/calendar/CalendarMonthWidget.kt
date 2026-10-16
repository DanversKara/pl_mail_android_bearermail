package de.plmail.widget.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.plmail.core.database.AgendaRow
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The month grid on the home screen: title, previous/next month, a + shortcut, and one cell per day
 * with a dot per event in its calendar's color.
 *
 * The shown month is per widget instance, kept in Glance preferences — two month widgets can look
 * at two different months, and a reboot keeps them where they were. The grid reads the calendar
 * cache straight from Room, so it renders even when the app process was not running.
 */
class CalendarMonthWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    /** The grid is a fixed 6×7; it does not reflow with the widget size. */
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val month = storedMonth(currentState<Preferences>())
        val rows = runCatching { loadMonthRows(month, context) }.getOrDefault(emptyList())

        provideContent { MonthContent(context, month, rows) }
    }
}

/** The month this widget instance is looking at, defaulting to the current one. */
private fun storedMonth(prefs: Preferences): YearMonth =
    prefs[MonthShiftAction.MonthKey]?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
        ?: YearMonth.now()

/**
 * Steps the widget's own month forward or back. Public with no constructor arguments because Glance
 * instantiates the callback itself when the button is tapped.
 */
class MonthShiftAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val delta = parameters[DeltaKey] ?: return
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[MonthKey] = storedMonth(prefs).plusMonths(delta.toLong()).toString()
        }
        CalendarMonthWidget().update(context, glanceId)
    }

    companion object {
        val MonthKey = stringPreferencesKey("widget_month")
        val DeltaKey = ActionParameters.Key<Int>("month_delta")
    }
}

private fun monthTitle(month: YearMonth, locale: Locale): String {
    val now = YearMonth.now()
    val pattern = if (month.year == now.year) "MMMM" else "MMMM yyyy"
    return month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

private fun GlanceModifier.headerButton(): GlanceModifier =
    padding(horizontal = 10.dp, vertical = 6.dp)

@Composable
private fun MonthContent(
    context: Context,
    month: YearMonth,
    rows: List<AgendaRow>,
) {
    val locale = Locale.getDefault()
    val openCalendar = actionStartActivity(openCalendarIntent(context))
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val today = LocalDate.now()
    val byDate = rows.groupBy { it.date }
    val prevMonth =
        actionRunCallback<MonthShiftAction>(actionParametersOf(MonthShiftAction.DeltaKey to -1))
    val nextMonth =
        actionRunCallback<MonthShiftAction>(actionParametersOf(MonthShiftAction.DeltaKey to 1))

    val offset = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells: List<LocalDate?> =
        (0 until 42).map { i ->
            val day = i - offset + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }

    val headerStyle =
        TextStyle(color = WidgetColors.onCard, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val navStyle =
        TextStyle(color = WidgetColors.muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)

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
                text = monthTitle(month, locale),
                modifier = GlanceModifier.defaultWeight().clickable(openCalendar),
                style = headerStyle,
            )
            Text(
                text = "‹",
                modifier = GlanceModifier.headerButton().clickable(prevMonth),
                style = navStyle,
            )
            Text(
                text = "›",
                modifier = GlanceModifier.headerButton().clickable(nextMonth),
                style = navStyle,
            )
            Text(
                text = "+",
                modifier = GlanceModifier.headerButton().clickable(openCalendar),
                style = navStyle,
            )
        }

        Spacer(modifier = GlanceModifier.height(4.dp))

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            var day = firstDayOfWeek
            repeat(7) {
                Text(
                    text = day.getDisplayName(JavaTextStyle.NARROW, locale),
                    modifier = GlanceModifier.defaultWeight(),
                    style =
                        TextStyle(
                            color = WidgetColors.muted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        ),
                )
                day = day.plus(1)
            }
        }

        cells.chunked(7).forEach { week ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(modifier = GlanceModifier.defaultWeight())
                    } else {
                        val isToday = date == today
                        val events = byDate[date.toString()].orEmpty()
                        val numberColor = if (isToday) WidgetColors.today else WidgetColors.onCard
                        val numberWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        Column(
                            modifier = GlanceModifier.defaultWeight().clickable(openCalendar),
                            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style =
                                    TextStyle(
                                        color = numberColor,
                                        fontSize = 12.sp,
                                        fontWeight = numberWeight,
                                        textAlign = TextAlign.Center,
                                    ),
                            )
                            Row(
                                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                                modifier = GlanceModifier.height(8.dp),
                            ) {
                                events.take(3).forEach { row ->
                                    Text(
                                        text = "•",
                                        style =
                                            TextStyle(
                                                color = row.glanceColor(),
                                                fontSize = 8.sp,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
