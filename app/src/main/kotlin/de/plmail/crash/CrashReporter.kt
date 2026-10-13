package de.plmail.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.plmail.BuildConfig
import de.plmail.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * BearerMail: keeps the error behind the last crash, so it can be copied into a bug report.
 *
 * When the app crashes, the stack trace is written to a small file in the app's private storage,
 * then the crash continues exactly as before (Android still closes the app). On the next launch,
 * [CrashReportPrompt] shows it with a Copy button. Nothing is sent anywhere; the file never leaves
 * the phone unless the user copies it.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    }
        .getOrNull()

    internal fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun report(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("BearerMail ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time ${Instant.now()}")
            appendLine("Thread ${thread.name}")
            appendLine()
            // Long traces are cut; the top of the trace is what says where it failed.
            append(trace.take(12_000))
        }
    }
}

/** On the launch after a crash: what went wrong, with Copy and Dismiss. */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.read(context)) }
    val text = report ?: return

    fun dismiss() {
        CrashReporter.clear(context)
        report = null
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("BearerMail crash", text))
                    dismiss()
                }
            ) {
                Text(stringResource(R.string.crash_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) { Text(stringResource(R.string.crash_dismiss)) }
        },
    )
}
