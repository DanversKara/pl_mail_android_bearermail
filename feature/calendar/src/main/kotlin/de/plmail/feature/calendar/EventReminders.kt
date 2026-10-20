package de.plmail.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.plmail.core.designsystem.PlMailTheme
import java.time.Duration
import java.time.LocalDateTime

/**
 * Reminders on an event, Google Calendar style: chips for the ones chosen and an "Add reminder"
 * picker that takes several at once — at the time, minutes, hours or days before, a week before,
 * and the night before (8 PM the evening before).
 *
 * Each is an offset before the start (`-PT30M`), which is what the server stores and sends as a
 * notification at the right time to every device, this phone's notification bar included.
 */
@Composable
internal fun ReminderField(
    alerts: List<String>,
    known: Boolean,
    start: LocalDateTime,
    isAllDay: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsNone, contentDescription = null)
            Text(
                stringResource(R.string.calendar_reminders),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = PlMailTheme.spacing.small),
            )
        }
        if (!known) {
            Text(
                stringResource(R.string.calendar_reminders_loading),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkMuted,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ReminderOffsets.sorted(alerts).forEach { offset ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text(reminderLabel(offset, start, isAllDay)) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                stringResource(R.string.calendar_reminder_remove),
                                Modifier.size(18.dp).clickable { onChange(alerts - offset) },
                            )
                        },
                    )
                }
            }
            TextButton(onClick = { picking = true }) {
                Text(stringResource(R.string.calendar_reminder_add))
            }
        }
    }

    if (picking) {
        ReminderPicker(
            chosen = alerts,
            start = start,
            isAllDay = isAllDay,
            onDone = { list ->
                picking = false
                onChange(list)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ReminderPicker(
    chosen: List<String>,
    start: LocalDateTime,
    isAllDay: Boolean,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(chosen) } }
    val nightBefore = ReminderOffsets.nightBefore(start)
    val options = (listOf(nightBefore) + ReminderOffsets.PRESETS).distinct()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_reminder_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { offset ->
                    val on = offset in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (on) selected.remove(offset) else selected.add(offset)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = {
                                if (on) selected.remove(offset) else selected.add(offset)
                            },
                        )
                        Text(
                            if (offset == nightBefore) {
                                stringResource(R.string.calendar_reminder_night_before)
                            } else {
                                reminderLabel(offset, start, isAllDay)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(selected.toList().take(ReminderOffsets.MAX)) }) {
                Text(stringResource(R.string.calendar_reminder_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.calendar_reminder_cancel)) }
        },
    )
}

/** How a reminder reads: "At the time", "30 minutes before", "The night before (8 PM)". */
@Composable
internal fun reminderLabel(offset: String, start: LocalDateTime, isAllDay: Boolean): String {
    val minutes = ReminderOffsets.minutesBefore(offset) ?: return offset
    if (offset == ReminderOffsets.nightBefore(start)) {
        return stringResource(R.string.calendar_reminder_night_before)
    }
    return when {
        minutes == 0L ->
            stringResource(
                if (isAllDay) R.string.calendar_reminder_on_the_day else R.string.calendar_reminder_at_time
            )
        minutes % (7 * 24 * 60) == 0L -> {
            val n = (minutes / (7 * 24 * 60)).toInt()
            pluralStringResource(R.plurals.calendar_reminder_weeks, n, n)
        }
        minutes % (24 * 60) == 0L -> {
            val n = (minutes / (24 * 60)).toInt()
            pluralStringResource(R.plurals.calendar_reminder_days, n, n)
        }
        minutes % 60 == 0L -> {
            val n = (minutes / 60).toInt()
            pluralStringResource(R.plurals.calendar_reminder_hours, n, n)
        }
        else -> pluralStringResource(R.plurals.calendar_reminder_minutes, minutes.toInt(), minutes.toInt())
    }
}

/** Reminder offsets: the presets, the night before, and reading one back as minutes. */
internal object ReminderOffsets {
    /** The server keeps at most ten per event. */
    const val MAX = 10

    val PRESETS =
        listOf("PT0S", "-PT5M", "-PT10M", "-PT15M", "-PT30M", "-PT1H", "-PT2H", "-P1D", "-P2D", "-P7D")

    /** 8 PM on the evening before the start, as an offset before it. */
    fun nightBefore(start: LocalDateTime): String {
        val evening = start.toLocalDate().minusDays(1).atTime(20, 0)
        val minutes = Duration.between(evening, start).toMinutes().coerceAtLeast(1)
        return "-PT${minutes}M"
    }

    /** Minutes before the start, or null for an offset this cannot read. */
    fun minutesBefore(offset: String): Long? =
        runCatching { -Duration.parse(offset).toMinutes() }.getOrNull()

    /** Earliest reminder first, as a calendar lists them. */
    fun sorted(offsets: List<String>): List<String> =
        offsets.sortedByDescending { minutesBefore(it) ?: 0 }
}
