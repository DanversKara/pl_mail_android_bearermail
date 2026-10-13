package de.plmail.lock

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.R
import de.plmail.core.designsystem.PlMailTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * BearerMail: covers [content] with the PIN pad while the app is locked. Wraps everything an
 * activity shows.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val enabled by AppLock.enabled.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()

    // With a lock on, the Recents screen shows a blank card instead of the last mail read.
    // Android 13+; older releases have no way to do this without also blocking screenshots.
    val activity = LocalActivity.current
    DisposableEffect(activity, enabled) {
        if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!enabled)
        }
        onDispose {}
    }

    // The app stays composed underneath, so whatever was open (a mail, a half-written draft) is
    // still there after unlocking. While locked it is hidden from accessibility services and
    // covered by a plain surface; the PIN pad itself is a full-screen dialog, because a dialog or
    // menu left open in the app is its own window and would otherwise sit on top of the lock.
    Box(Modifier.fillMaxSize()) {
        Box(
            if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()
        ) {
            content()
        }
        if (locked) {
            Surface(modifier = Modifier.fillMaxSize(), color = PlMailTheme.colors.surface) {}
            Dialog(
                // Back leaves the app rather than reaching the screen underneath.
                onDismissRequest = { activity?.moveTaskToBack(true) },
                properties =
                    DialogProperties(
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
            ) {
                LockScreen()
            }
        }
    }
}

@Composable
private fun LockScreen() {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var waitMillis by remember { mutableLongStateOf(AppLock.waitMillis()) }
    val length = remember { AppLock.pinLength }

    // Counts a running wait down, once a second.
    LaunchedEffect(waitMillis > 0) {
        while (waitMillis > 0) {
            delay(1_000)
            waitMillis = AppLock.waitMillis()
        }
    }

    // The PIN is checked as soon as it is complete. Off the main thread: the hash is slow on
    // purpose.
    LaunchedEffect(pin) {
        if (pin.length != length) return@LaunchedEffect
        checking = true
        val ok = withContext(Dispatchers.Default) { AppLock.unlock(pin) }
        checking = false
        if (!ok) {
            wrong = true
            pin = ""
            waitMillis = AppLock.waitMillis()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = PlMailTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = PlMailTheme.colors.accent,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.titleLarge,
                color = PlMailTheme.colors.ink,
            )
            Spacer(Modifier.height(8.dp))
            val message =
                when {
                    waitMillis > 0 -> {
                        val seconds = ((waitMillis + 999) / 1000).toInt()
                        pluralStringResource(R.plurals.lock_wait, seconds, seconds)
                    }
                    wrong -> stringResource(R.string.lock_wrong)
                    else -> stringResource(R.string.lock_enter, length)
                }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (wrong || waitMillis > 0) PlMailTheme.colors.danger
                    else PlMailTheme.colors.inkMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PinDots(filled = pin.length, length = length)
            Spacer(Modifier.height(32.dp))
            PinPad(
                enabled = !checking && waitMillis == 0L,
                onDigit = { d ->
                    if (pin.length < length) {
                        wrong = false
                        pin += d
                    }
                },
                onDelete = { pin = pin.dropLast(1) },
            )
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.lock_forgot),
                style = MaterialTheme.typography.bodySmall,
                color = PlMailTheme.colors.inkFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One dot per digit; [filled] of them solid. Twelve fit on one row at this size. */
@Composable
internal fun PinDots(filled: Int, length: Int) {
    val description = stringResource(R.string.lock_dots, filled, length)
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (length > 6) 8.dp else 14.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        repeat(length) { i ->
            val size = if (length > 6) 12.dp else 16.dp
            Box(
                Modifier.size(size)
                    .then(
                        if (i < filled) Modifier.background(PlMailTheme.colors.ink, CircleShape)
                        else Modifier.border(1.5.dp, PlMailTheme.colors.inkMuted, CircleShape)
                    )
            )
        }
    }
}

/** 1–9, then 0 and delete. Large targets; the digits are the only way in. */
@Composable
internal fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { d -> Key(enabled, onClick = { onDigit(d) }) { Digit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.size(72.dp))
            Key(enabled, onClick = { onDigit('0') }) { Digit('0') }
            val deleteLabel = stringResource(R.string.lock_delete)
            Key(enabled, onClick = onDelete) {
                Icon(
                    Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = deleteLabel,
                    tint = PlMailTheme.colors.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun Key(enabled: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(72.dp),
    ) {
        label()
    }
}

@Composable
private fun Digit(d: Char) {
    Text(
        d.toString(),
        style = MaterialTheme.typography.headlineMedium,
        color = PlMailTheme.colors.ink,
    )
}
