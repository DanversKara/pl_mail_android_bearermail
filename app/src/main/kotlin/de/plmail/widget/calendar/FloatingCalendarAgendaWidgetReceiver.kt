package de.plmail.widget.calendar

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** The agenda without its panel, the cards floating on the wallpaper. */
class FloatingCalendarAgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FloatingCalendarAgendaWidget()
}
