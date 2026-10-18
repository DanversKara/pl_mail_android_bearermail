package de.plmail.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.plmail.jmap.methods.NoteDraft
import de.plmail.jmap.methods.NoteItem
import de.plmail.jmap.methods.NoteLink
import de.plmail.jmap.methods.NoteObject

internal fun linkIcon(type: String): ImageVector =
    when (type) {
        NoteLink.EVENT -> Icons.Outlined.Event
        NoteLink.CONTACT -> Icons.Outlined.Contacts
        NoteLink.FILE -> Icons.Outlined.Description
        NoteLink.MAIL -> Icons.Outlined.Email
        else -> Icons.Outlined.Link
    }

/**
 * The note editor: title, free text or a checklist, colour, pin, archive, bin, and links.
 *
 * Saves when it closes rather than on a Save button, as Keep does: a new note that is still empty
 * is discarded, an edited one is written whole, and an untouched one sends nothing. [existing] is
 * the note being edited, [seed] starts a new one (an event's "add note" arrives with its link
 * already attached).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditor(
    existing: NoteObject?,
    seed: NoteDraft?,
    onClose: () -> Unit,
    viewModel: NotesViewModel,
    onAddToCalendar: ((title: String, description: String) -> Unit)? = null,
    onOpenLink: ((NoteLink) -> Unit)? = null,
) {
    val start =
        existing
            ?: NoteObject(
                id = "",
                title = seed?.title.orEmpty(),
                body = seed?.body.orEmpty(),
                items = seed?.items.orEmpty(),
                color = seed?.color,
                pinned = seed?.pinned ?: false,
                archived = seed?.archived ?: false,
                links = seed?.links.orEmpty(),
            )
    val trashed = existing?.trashed == true

    var title by remember { mutableStateOf(start.title) }
    var body by remember { mutableStateOf(start.body) }
    val items = remember { mutableStateListOf<NoteItem>().apply { addAll(start.items) } }
    var checklist by remember { mutableStateOf(start.items.isNotEmpty()) }
    var color by remember { mutableStateOf(start.color) }
    var pinned by remember { mutableStateOf(start.pinned) }
    var archived by remember { mutableStateOf(start.archived) }
    val links = remember { mutableStateListOf<NoteLink>().apply { addAll(start.links) } }
    var picking by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf(false) }

    fun current(trashedNow: Boolean = trashed) =
        start.copy(
            title = title.trim(),
            body = body,
            items = if (checklist) items.filter { it.text.isNotBlank() } else emptyList(),
            color = color,
            pinned = pinned,
            archived = archived,
            trashed = trashedNow,
            links = links.toList(),
        )

    fun finish(trashedNow: Boolean = trashed) {
        val note = current(trashedNow)
        val blank = note.title.isEmpty() && note.body.isBlank() && note.items.isEmpty()
        when {
            existing == null && blank -> Unit
            existing == null ->
                viewModel.create(
                    NoteDraft(
                        title = note.title,
                        body = note.body,
                        items = note.items,
                        color = note.color,
                        pinned = note.pinned,
                        archived = note.archived,
                        links = note.links,
                    )
                )
            note != existing.copy(updatedAt = note.updatedAt, createdAt = note.createdAt) ->
                viewModel.save(note)
        }
        onClose()
    }

    BackHandler { finish() }

    val container = noteContainer(color)
    val content = noteContent(color)

    Scaffold(
        containerColor = container,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = container,
                        titleContentColor = content,
                        navigationIconContentColor = content,
                        actionIconContentColor = content,
                    ),
                title = {},
                navigationIcon = {
                    IconButton(onClick = { finish() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.notes_back),
                        )
                    }
                },
                actions = {
                    if (trashed) {
                        IconButton(
                            onClick = {
                                existing?.let(viewModel::restore)
                                onClose()
                            }
                        ) {
                            Icon(
                                Icons.Outlined.RestoreFromTrash,
                                stringResource(R.string.notes_restore),
                            )
                        }
                        IconButton(
                            onClick = {
                                existing?.let(viewModel::destroy)
                                onClose()
                            }
                        ) {
                            Icon(
                                Icons.Outlined.DeleteForever,
                                stringResource(R.string.notes_delete_forever),
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                pinned = !pinned
                                if (pinned) archived = false
                            }
                        ) {
                            Icon(
                                Icons.Outlined.PushPin,
                                stringResource(
                                    if (pinned) R.string.notes_unpin else R.string.notes_pin
                                ),
                                tint = if (pinned) MaterialTheme.colorScheme.primary else content,
                            )
                        }
                        IconButton(
                            onClick = {
                                archived = !archived
                                if (archived) pinned = false
                                finish()
                            }
                        ) {
                            Icon(
                                if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                stringResource(
                                    if (archived) R.string.notes_unarchive
                                    else R.string.notes_archive
                                ),
                            )
                        }
                        if (existing != null) {
                            IconButton(onClick = { finish(trashedNow = true) }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.notes_delete))
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!trashed) {
                // A Scaffold's bottomBar is drawn edge to edge: without these the
                // toolbar sat under the gesture/navigation bar, and under the
                // keyboard while typing.
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (coloring) {
                        ColorRow(
                            selected = color,
                            onPick = {
                                color = it
                                coloring = false
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = { coloring = !coloring }) {
                            Icon(
                                Icons.Outlined.Palette,
                                stringResource(R.string.notes_color),
                                tint = content,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (!checklist) {
                                    if (items.isEmpty() && body.isNotBlank()) {
                                        items.addAll(
                                            body
                                                .lines()
                                                .filter { it.isNotBlank() }
                                                .map { NoteItem(it, false) }
                                        )
                                        body = ""
                                    }
                                    if (items.isEmpty()) items.add(NoteItem())
                                    checklist = true
                                } else {
                                    body = items.joinToString("\n") { it.text }.trim()
                                    items.clear()
                                    checklist = false
                                }
                            }
                        ) {
                            Icon(
                                Icons.Outlined.CheckBox,
                                stringResource(
                                    if (checklist) R.string.notes_hide_checkboxes
                                    else R.string.notes_show_checkboxes
                                ),
                                tint = content,
                            )
                        }
                        IconButton(onClick = { picking = true }) {
                            Icon(
                                Icons.Outlined.Link,
                                stringResource(R.string.notes_add_link),
                                tint = content,
                            )
                        }
                        if (onAddToCalendar != null) {
                            IconButton(
                                onClick = {
                                    val text =
                                        if (checklist) items.joinToString("\n") { "• ${it.text}" }
                                        else body
                                    onAddToCalendar(title.trim(), text)
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.EventAvailable,
                                    stringResource(R.string.notes_add_to_calendar),
                                    tint = content,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = content,
                    unfocusedTextColor = content,
                    disabledTextColor = content,
                )

            TextField(
                value = title,
                onValueChange = { title = it.replace("\n", " ") },
                readOnly = trashed,
                placeholder = { Text(stringResource(R.string.notes_title_hint)) },
                textStyle = MaterialTheme.typography.titleLarge,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
            )

            if (checklist) {
                items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.done,
                            enabled = !trashed,
                            onCheckedChange = { items[index] = item.copy(done = it) },
                        )
                        TextField(
                            value = item.text,
                            onValueChange = {
                                items[index] = item.copy(text = it.replace("\n", " "))
                            },
                            readOnly = trashed,
                            placeholder = { Text(stringResource(R.string.notes_item_hint)) },
                            colors = colors,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions =
                                KeyboardActions(onNext = { items.add(index + 1, NoteItem()) }),
                            modifier = Modifier.weight(1f),
                        )
                        if (!trashed) {
                            IconButton(onClick = { items.removeAt(index) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    stringResource(R.string.notes_delete),
                                    tint = content,
                                )
                            }
                        }
                    }
                }
                if (!trashed) {
                    Row(
                        Modifier.fillMaxWidth().clickable { items.add(NoteItem()) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "+  " + stringResource(R.string.notes_add_item),
                            color = content.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                TextField(
                    value = body,
                    onValueChange = { body = it },
                    readOnly = trashed,
                    placeholder = { Text(stringResource(R.string.notes_body_hint)) },
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (links.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    links.toList().forEach { link ->
                        InputChip(
                            selected = false,
                            onClick = { onOpenLink?.invoke(link) },
                            label = {
                                Text(
                                    link.title ?: link.id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(linkIcon(link.type), null, Modifier.size(18.dp)) },
                            trailingIcon =
                                if (trashed) null
                                else {
                                    {
                                        Icon(
                                            Icons.Outlined.Close,
                                            stringResource(R.string.notes_remove_link),
                                            Modifier.size(18.dp).clickable { links.remove(link) },
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        LinkPickerDialog(
            onPick = { picked ->
                links.removeAll { it.type == picked.type && it.id == picked.id }
                links.add(picked)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ColorRow(selected: String?, onPick: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf<String?>(null) + NoteColorNames).forEach { name ->
            Box(
                Modifier.size(28.dp)
                    .background(noteContainer(name), CircleShape)
                    .border(
                        width = if (name == selected) 3.dp else 1.dp,
                        color =
                            if (name == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(name) }
            )
        }
    }
}
