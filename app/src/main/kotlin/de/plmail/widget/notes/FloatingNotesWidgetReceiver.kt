package de.plmail.widget.notes

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** The notes without their panel, the cards floating on the wallpaper. */
class FloatingNotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FloatingNotesWidget()
}
