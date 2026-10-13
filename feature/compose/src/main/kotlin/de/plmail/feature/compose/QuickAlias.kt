package de.plmail.feature.compose

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.plmail.core.data.ComposeRepository
import de.plmail.core.data.SendIdentity
import de.plmail.core.data.randomAliasLocalPart
import de.plmail.core.designsystem.PlMailTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One account an alias can be made on, with the domain the alias will get. */
data class AliasAccount(val accountKey: String, val accountName: String, val domain: String)

data class QuickAliasState(
    val accounts: List<AliasAccount> = emptyList(),
    val accountKey: String? = null,
    val isWorking: Boolean = false,
    val error: String? = null,
    /** Set once the alias exists; the dialog then shows it with a Copy button. */
    val created: SendIdentity? = null,
) {
    val account: AliasAccount?
        get() = accounts.firstOrNull { it.accountKey == accountKey } ?: accounts.firstOrNull()
}

/**
 * BearerMail: makes a new alias on the spot — typed, or one tap random — for the shop counter, a
 * rewards card, or someone you would rather not give your real address to.
 *
 * Created through `Identity/set`; the server adds it to the mail server's alias map in the same
 * request, so it receives mail within seconds and can be used as a From address straight away.
 */
@HiltViewModel
class QuickAliasViewModel @Inject constructor(private val compose: ComposeRepository) :
    ViewModel() {

    private val _state = MutableStateFlow(QuickAliasState())
    val state: StateFlow<QuickAliasState> = _state.asStateFlow()

    /** Loads the accounts; [preferredAccount] is pre-selected when given (the composer's From). */
    fun start(preferredAccount: String?) {
        _state.value = QuickAliasState()
        viewModelScope.launch {
            val accounts =
                compose
                    .identities()
                    .first()
                    .groupBy { it.accountKey }
                    .mapNotNull { (key, identities) ->
                        val domain =
                            identities.firstNotNullOfOrNull {
                                it.email.substringAfter('@', "").takeIf(String::isNotBlank)
                            } ?: return@mapNotNull null
                        AliasAccount(key, identities.first().accountName, domain)
                    }
            _state.update {
                it.copy(
                    accounts = accounts,
                    accountKey =
                        preferredAccount?.takeIf { key ->
                            accounts.any { a -> a.accountKey == key }
                        } ?: accounts.firstOrNull()?.accountKey,
                )
            }
        }
    }

    fun reset() {
        _state.value = QuickAliasState()
    }

    fun chooseAccount(accountKey: String) = _state.update { it.copy(accountKey = accountKey) }

    fun random(label: String): String = randomAliasLocalPart(label.ifBlank { null })

    fun create(localPart: String, label: String) {
        val account = _state.value.account ?: return
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            runCatching {
                compose.createAlias(account.accountKey, localPart, label.ifBlank { null })
            }
                .onSuccess { identity ->
                    _state.update { it.copy(isWorking = false, created = identity) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isWorking = false, error = error.message ?: "Could not create it.")
                    }
                }
        }
    }
}

/**
 * The "new alias" dialog. [onCreated] is called once with the new identity (the composer switches
 * its From to it); [onDismiss] when the dialog closes either way.
 */
@Composable
fun QuickAliasDialog(
    onDismiss: () -> Unit,
    preferredAccount: String? = null,
    onCreated: (SendIdentity) -> Unit = {},
    /** Create a random alias immediately on open — the "one tap" path. */
    instantRandom: Boolean = false,
    viewModel: QuickAliasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var local by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var reported by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start(preferredAccount) }

    // Cleared on close so the next opening never sees the previous alias.
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    // One tap: as soon as the account (and its domain) is known, make a random one.
    LaunchedEffect(instantRandom, state.account) {
        if (instantRandom && state.account != null && local.isEmpty() && state.created == null) {
            local = viewModel.random("")
            viewModel.create(local, "")
        }
    }

    LaunchedEffect(state.created) {
        val created = state.created ?: return@LaunchedEffect
        if (!reported) {
            reported = true
            context
                .getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("BearerMail alias", created.email))
            onCreated(created)
        }
    }

    val created = state.created

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (created != null) R.string.alias_ready_title else R.string.alias_new_title
                )
            )
        },
        text = {
            if (created != null) {
                Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                    Text(created.email, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.alias_ready_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = PlMailTheme.colors.inkMuted,
                    )
                    TextButton(
                        onClick = {
                            context
                                .getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(
                                    ClipData.newPlainText("BearerMail alias", created.email)
                                )
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text(
                            stringResource(R.string.alias_copy),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.small)) {
                    if (state.accounts.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(PlMailTheme.spacing.tiny),
                        ) {
                            state.accounts.forEach { account ->
                                FilterChip(
                                    selected = account.accountKey == state.account?.accountKey,
                                    onClick = { viewModel.chooseAccount(account.accountKey) },
                                    label = { Text(account.domain) },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = local,
                        onValueChange = {
                            local = it.lowercase().filter { c -> !c.isWhitespace() }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.alias_address)) },
                        suffix = { state.account?.let { Text("@${it.domain}") } },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    TextButton(onClick = { local = viewModel.random(label) }) {
                        Icon(Icons.Filled.Casino, contentDescription = null)
                        Text(
                            stringResource(R.string.alias_random),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }

                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.alias_label)) },
                        placeholder = { Text(stringResource(R.string.alias_label_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        stringResource(R.string.alias_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = PlMailTheme.colors.inkMuted,
                    )

                    state.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = PlMailTheme.colors.danger,
                        )
                    }

                    if (state.isWorking) CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            if (created != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.alias_done)) }
            } else {
                TextButton(
                    onClick = { viewModel.create(local, label) },
                    enabled = local.isNotBlank() && !state.isWorking && state.account != null,
                ) {
                    Text(stringResource(R.string.alias_create))
                }
            }
        },
        dismissButton = {
            if (created == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.alias_cancel)) }
            }
        },
    )
}
