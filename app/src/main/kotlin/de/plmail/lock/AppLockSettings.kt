package de.plmail.lock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.plmail.R
import de.plmail.core.designsystem.PlMailTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the settings screen is in turning the lock on, changing the PIN, or turning it off. */
private sealed interface Step {
    data object Overview : Step

    /** Asking for the current PIN before [then]; null means "then turn the lock off". */
    data class Verify(val then: Step?) : Step

    /** Picking 4, 6 or 12 digits for a new PIN. */
    data object ChooseLength : Step

    data class Enter(val length: Int) : Step

    data class Confirm(val first: String) : Step
}

/** BearerMail: Drawer → App lock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettings(onBack: () -> Unit) {
    val enabled by AppLock.enabled.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf<Step>(Step.Overview) }

    fun back() {
        if (step == Step.Overview) onBack() else step = Step.Overview
    }
    BackHandler(enabled = step != Step.Overview) { step = Step.Overview }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PlMailTheme.colors.surface,
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = PlMailTheme.colors.surface,
                        scrolledContainerColor = PlMailTheme.colors.surface,
                        titleContentColor = PlMailTheme.colors.ink,
                        navigationIconContentColor = PlMailTheme.colors.inkSoft,
                    ),
                title = { Text(stringResource(R.string.lock_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = ::back) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.lock_back),
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = PlMailTheme.spacing.gutter,
                        vertical = PlMailTheme.spacing.medium,
                    ),
            verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.medium),
        ) {
            when (val s = step) {
                Step.Overview -> Overview(enabled, onStep = { step = it })
                Step.ChooseLength -> ChooseLength(onChosen = { step = Step.Enter(it) })
                is Step.Enter ->
                    PinEntry(
                        title = stringResource(R.string.lock_new_pin, s.length),
                        length = s.length,
                        error = null,
                        onComplete = { step = Step.Confirm(it) },
                    )
                is Step.Confirm -> {
                    var mismatch by remember(s) { mutableStateOf(false) }
                    PinEntry(
                        title = stringResource(R.string.lock_confirm_pin),
                        length = s.first.length,
                        error = if (mismatch) stringResource(R.string.lock_mismatch) else null,
                        onComplete = { again ->
                            if (again == s.first) {
                                AppLock.setPin(again)
                                step = Step.Overview
                            } else {
                                mismatch = true
                            }
                        },
                    )
                }
                is Step.Verify ->
                    VerifyCurrent(
                        onVerified = {
                            val next = s.then
                            if (next == null) AppLock.disable()
                            step = next ?: Step.Overview
                        }
                    )
            }
        }
    }
}

@Composable
private fun Overview(enabled: Boolean, onStep: (Step) -> Unit) {
    Text(
        stringResource(R.string.lock_explain),
        style = MaterialTheme.typography.bodyMedium,
        color = PlMailTheme.colors.inkSoft,
    )
    if (!enabled) {
        Button(onClick = { onStep(Step.ChooseLength) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.lock_turn_on))
        }
        return
    }

    Text(
        stringResource(R.string.lock_is_on, AppLock.pinLength),
        style = MaterialTheme.typography.titleMedium,
        color = PlMailTheme.colors.ink,
    )

    Text(
        stringResource(R.string.lock_grace_title),
        style = MaterialTheme.typography.labelLarge,
        color = PlMailTheme.colors.inkMuted,
    )
    var grace by remember { mutableStateOf(AppLock.grace) }
    AppLock.Grace.entries.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .selectable(
                        selected = grace == option,
                        role = Role.RadioButton,
                        onClick = {
                            grace = option
                            AppLock.grace = option
                        },
                    ),
        ) {
            RadioButton(selected = grace == option, onClick = null)
            Text(
                stringResource(
                    when (option) {
                        AppLock.Grace.IMMEDIATELY -> R.string.lock_grace_now
                        AppLock.Grace.ONE_MINUTE -> R.string.lock_grace_1
                        AppLock.Grace.FIVE_MINUTES -> R.string.lock_grace_5
                    }
                ),
                modifier = Modifier.padding(start = 8.dp),
                color = PlMailTheme.colors.ink,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onStep(Step.Verify(then = Step.ChooseLength)) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.lock_change))
    }
    Button(
        onClick = { onStep(Step.Verify(then = null)) },
        modifier = Modifier.fillMaxWidth(),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = PlMailTheme.colors.danger,
                contentColor = PlMailTheme.colors.onAccent,
            ),
    ) {
        Text(stringResource(R.string.lock_turn_off))
    }
}

@Composable
private fun ChooseLength(onChosen: (Int) -> Unit) {
    Text(
        stringResource(R.string.lock_choose_length),
        style = MaterialTheme.typography.titleMedium,
        color = PlMailTheme.colors.ink,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppLock.LENGTHS.forEach { n ->
            FilterChip(
                selected = false,
                onClick = { onChosen(n) },
                label = { Text(stringResource(R.string.lock_digits, n)) },
            )
        }
    }
}

@Composable
private fun VerifyCurrent(onVerified: () -> Unit) {
    var wrong by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        val pin = attempt ?: return@LaunchedEffect
        val ok = withContext(Dispatchers.Default) { AppLock.matches(pin) }
        if (ok) onVerified() else wrong = true
        attempt = null
    }

    PinEntry(
        title = stringResource(R.string.lock_current_pin),
        length = AppLock.pinLength,
        error = if (wrong) stringResource(R.string.lock_wrong) else null,
        onComplete = { attempt = it },
    )
}

/** A title, the dots and the pad; calls [onComplete] once [length] digits are in, then clears. */
@Composable
private fun PinEntry(title: String, length: Int, error: String?, onComplete: (String) -> Unit) {
    var pin by remember(title) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = PlMailTheme.colors.ink,
            textAlign = TextAlign.Center,
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = PlMailTheme.colors.danger,
            )
        }
        PinDots(filled = pin.length, length = length)
        PinPad(
            enabled = true,
            onDigit = { d ->
                if (pin.length < length) {
                    pin += d
                    if (pin.length == length) {
                        val done = pin
                        pin = ""
                        onComplete(done)
                    }
                }
            },
            onDelete = { pin = pin.dropLast(1) },
        )
    }
}
