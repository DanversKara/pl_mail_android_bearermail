package de.plmail.feature.mail.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.data.MeetingDetector
import de.plmail.core.data.PlanSuggestion
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.mail.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * BearerMail: the plan this message describes, if it describes one — read from the newest message's
 * text with [MeetingDetector]. Null for almost every mail.
 */
internal fun planIn(message: ReaderMessage): PlanSuggestion? {
    val body =
        message.text?.takeIf { it.isNotBlank() }
            ?: message.html?.takeIf { it.isNotBlank() }?.let(MeetingDetector::textOf)
            ?: return null
    val receivedMillis = message.email.receivedAt ?: return null
    val received =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(receivedMillis), ZoneId.systemDefault())
    val sender =
        listOfNotNull(
                message.email.fromName?.takeIf { it.isNotBlank() },
                message.email.fromAddress?.takeIf { it.isNotBlank() }?.let { "<$it>" },
            )
            .joinToString(" ")
            .ifBlank { null }

    return MeetingDetector.detect(message.email.subject, body, sender, received)
}

/** "Looks like a plan" — when, where, and one button that opens the event editor filled in. */
@Composable
internal fun PlanCard(plan: PlanSuggestion, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val theme = PlMailTheme.values
    val whenText =
        if (plan.hasTime) {
            plan.start.format(
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            )
        } else {
            plan.start.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }

    PlMailPane(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.EventAvailable,
                    contentDescription = null,
                    tint = theme.colors.success,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.plan_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = theme.colors.success,
                )
            }
            Text(
                plan.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = theme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = theme.colors.inkMuted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (plan.hasTime) whenText
                    else whenText + " · " + stringResource(R.string.plan_no_time),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.colors.inkSoft,
                )
            }
            plan.location?.let { place ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        tint = theme.colors.inkMuted,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        place,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.colors.inkSoft,
                    )
                }
            }
            Button(
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().padding(top = theme.spacing.small),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = theme.colors.success,
                        contentColor = theme.colors.onAccent,
                    ),
            ) {
                Icon(
                    Icons.Outlined.EventAvailable,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(stringResource(R.string.plan_add), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
