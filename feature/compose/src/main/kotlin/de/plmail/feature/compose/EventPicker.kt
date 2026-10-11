package de.plmail.feature.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.core.database.AgendaRow
import de.plmail.core.designsystem.PlMailTheme

/**
 * BearerMail: pick one of your upcoming events to put into the message. The composer writes its
 * details into the body and attaches it as an .ics file.
 */
@Composable
internal fun EventPickerDialog(
    load: suspend () -> List<AgendaRow>,
    whenText: (AgendaRow) -> String,
    onPick: (AgendaRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var rows by remember { mutableStateOf<List<AgendaRow>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { rows = runCatching { load() }.getOrDefault(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_event_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                Text(
                    stringResource(R.string.compose_event_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = PlMailTheme.colors.inkMuted,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.compose_event_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = rows
                when {
                    list == null ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.padding(PlMailTheme.spacing.medium))
                        }
                    list.isEmpty() ->
                        Text(
                            stringResource(R.string.compose_event_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PlMailTheme.colors.inkMuted,
                        )
                    else -> {
                        val shown =
                            list.filter {
                                query.isBlank() ||
                                    it.title.contains(query.trim(), ignoreCase = true) ||
                                    it.location.orEmpty().contains(query.trim(), ignoreCase = true)
                            }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                            items(shown, key = { it.eventKey + "@" + (it.startLocal ?: it.date) }) { row ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { onPick(row) }
                                        .padding(vertical = PlMailTheme.spacing.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small),
                                ) {
                                    Icon(Icons.Outlined.Event, contentDescription = null, tint = PlMailTheme.colors.inkSoft)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            row.title.ifBlank { "—" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            listOfNotNull(whenText(row), row.location?.takeIf { it.isNotBlank() })
                                                .joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PlMailTheme.colors.inkMuted,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.alias_cancel)) }
        },
    )
}
