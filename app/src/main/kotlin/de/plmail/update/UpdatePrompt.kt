package de.plmail.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.plmail.BuildConfig
import de.plmail.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Where the update flow is. */
private sealed interface UpdateStep {
    data class Offer(val update: AvailableUpdate) : UpdateStep

    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateStep

    /** Downloaded, but Android has not been told this app may install updates yet. */
    data class NeedsPermission(val update: AvailableUpdate, val apk: File) : UpdateStep

    data class Failed(val update: AvailableUpdate) : UpdateStep
}

/**
 * BearerMail: on launch, looks for a newer release (at most once an hour) and, if there is one,
 * asks whether to update. "Update" downloads the APK and opens Android's install confirmation;
 * "Later" asks again next time; "Skip" stays quiet until a version after this one.
 */
@Composable
fun UpdatePrompt() {
    if (!AppUpdater.enabled) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<UpdateStep?>(null) }

    // The automatic check on launch, at most once an hour, and quiet when nothing is found.
    LaunchedEffect(Unit) {
        val prefs = UpdatePrefs(context)
        if (!prefs.dueForCheck()) return@LaunchedEffect
        val update =
            try {
                AppUpdater.latest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline, or GitHub unreachable: try again on a later launch.
                Log.i(TAG, "Update check failed", e)
                return@LaunchedEffect
            }
        prefs.checked()
        if (update != null && update.version != prefs.skippedVersion)
            step = UpdateStep.Offer(update)
    }

    val upToDate = stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME)

    // Drawer → Check for updates: asks now, ignores the hourly limit and a skipped version, and
    // always says what it found.
    LaunchedEffect(Unit) {
        UpdateChecks.requests.collect {
            Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
            val update =
                try {
                    AppUpdater.latest()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "Update check failed", e)
                    Toast.makeText(context, R.string.update_check_failed, Toast.LENGTH_LONG).show()
                    return@collect
                }
            UpdatePrefs(context).checked()
            if (update != null) {
                step = UpdateStep.Offer(update)
            } else {
                Toast.makeText(context, upToDate, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun install(update: AvailableUpdate, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            step = UpdateStep.NeedsPermission(update, apk)
            return
        }
        scope.launch {
            try {
                AppUpdater.install(context, apk)
                step = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Install failed", e)
                step = UpdateStep.Failed(update)
            }
        }
    }

    fun download(update: AvailableUpdate) {
        step = UpdateStep.Downloading(update, 0f)
        scope.launch {
            try {
                val apk =
                    AppUpdater.download(context, update) { progress ->
                        step = UpdateStep.Downloading(update, progress)
                    }
                install(update, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed", e)
                step = UpdateStep.Failed(update)
            }
        }
    }

    // Back from "Install unknown apps" in Settings: carry on if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val waiting = step as? UpdateStep.NeedsPermission ?: return@LifecycleEventEffect
        if (context.packageManager.canRequestPackageInstalls()) install(waiting.update, waiting.apk)
    }

    when (val current = step) {
        null -> Unit
        is UpdateStep.Offer ->
            AlertDialog(
                onDismissRequest = { step = null },
                title = { Text(stringResource(R.string.update_title, current.update.version)) },
                text = {
                    Column(
                        modifier =
                            Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            stringResource(
                                R.string.update_body,
                                current.update.version,
                                Formatter.formatShortFileSize(context, current.update.apkSize),
                            )
                        )
                        if (current.update.notes.isNotBlank()) {
                            Text(
                                current.update.notes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { download(current.update) }) {
                        Text(stringResource(R.string.update_now))
                    }
                },
                dismissButton = {
                    Column {
                        TextButton(onClick = { step = null }) {
                            Text(stringResource(R.string.update_later))
                        }
                        TextButton(
                            onClick = {
                                UpdatePrefs(context).skippedVersion = current.update.version
                                step = null
                            }
                        ) {
                            Text(stringResource(R.string.update_skip))
                        }
                    }
                },
            )
        is UpdateStep.Downloading ->
            AlertDialog(
                // Not dismissable by tapping outside: the download is running.
                onDismissRequest = {},
                title = {
                    Text(stringResource(R.string.update_downloading, current.update.version))
                },
                text = {
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {},
            )
        is UpdateStep.NeedsPermission ->
            AlertDialog(
                onDismissRequest = { step = null },
                title = { Text(stringResource(R.string.update_permission_title)) },
                text = { Text(stringResource(R.string.update_permission_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    "package:${context.packageName}".toUri(),
                                )
                            )
                        }
                    ) {
                        Text(stringResource(R.string.update_permission_open))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { step = null }) {
                        Text(stringResource(R.string.update_later))
                    }
                },
            )
        is UpdateStep.Failed ->
            AlertDialog(
                onDismissRequest = { step = null },
                title = { Text(stringResource(R.string.update_failed)) },
                text = { Text(stringResource(R.string.update_failed_body)) },
                confirmButton = {
                    TextButton(onClick = { download(current.update) }) {
                        Text(stringResource(R.string.update_retry))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, current.update.pageUrl.toUri())
                            )
                            step = null
                        }
                    ) {
                        Text(stringResource(R.string.update_open_page))
                    }
                },
            )
    }
}

/** BearerMail: "Check for updates" from the drawer, heard by [UpdatePrompt]. */
object UpdateChecks {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests

    fun checkNow() {
        _requests.tryEmit(Unit)
    }
}

/** When the last check ran, and which version the user chose to skip. */
private class UpdatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("bearermail_updates", Context.MODE_PRIVATE)

    fun dueForCheck(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0) >= CHECK_EVERY_MS

    fun checked() = prefs.edit { putLong(KEY_LAST, System.currentTimeMillis()) }

    var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) = prefs.edit { putString(KEY_SKIPPED, value) }

    private companion object {
        const val KEY_LAST = "last_check"
        const val KEY_SKIPPED = "skipped_version"
        const val CHECK_EVERY_MS = 60L * 60 * 1000
    }
}

private const val TAG = "BearerMail.Update"
