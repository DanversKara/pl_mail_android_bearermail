package de.plmail.feature.mail.reader

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.plmail.core.data.MeetingDetector
import de.plmail.core.data.OtpDetector
import de.plmail.core.data.copyOneTimeCode
import de.plmail.core.data.systemConfirmsCopy
import de.plmail.core.designsystem.PlMailPane
import de.plmail.core.designsystem.PlMailTheme
import de.plmail.feature.mail.R

/**
 * BearerMail: the one-time code in the newest message, if it carries one — read with [OtpDetector]
 * from the subject and text already on the phone. Null for almost every mail.
 */
internal fun codeIn(message: ReaderMessage): String? {
    val body =
        message.text?.takeIf { it.isNotBlank() }
            ?: message.html?.takeIf { it.isNotBlank() }?.let(MeetingDetector::textOf)
    return OtpDetector.detect(message.email.subject, body)
}

/** "Verification code 482913" and a Copy button, above the message like the plan card. */
@Composable
internal fun CodeCard(code: String, modifier: Modifier = Modifier) {
    val theme = PlMailTheme.values
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    val copiedText = stringResource(R.string.code_copied)

    PlMailPane(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(theme.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(theme.spacing.tiny),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Password,
                        contentDescription = null,
                        tint = theme.colors.inkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.code_heading),
                        style = MaterialTheme.typography.labelLarge,
                        color = theme.colors.inkMuted,
                    )
                }
                Text(
                    code,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.colors.ink,
                )
            }
            FilledTonalButton(
                onClick = {
                    if (context.copyOneTimeCode(code)) {
                        copied = true
                        if (!systemConfirmsCopy)
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                }
            ) {
                Icon(
                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(if (copied) R.string.code_copied_short else R.string.code_copy),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
