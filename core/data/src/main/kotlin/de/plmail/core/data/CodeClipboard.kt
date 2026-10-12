package de.plmail.core.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * BearerMail: puts a one-time code on the clipboard, marked sensitive so Android 13+ hides it in
 * the clipboard preview and keyboards do not suggest it later. Shared by the reader's card and the
 * notification's "Copy code" action, so both copy the same way.
 *
 * Returns false when there is no clipboard to write to.
 */
fun Context.copyOneTimeCode(code: String): Boolean {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
    val clip =
        ClipData.newPlainText("BearerMail code", code).apply {
            // ClipDescription.EXTRA_IS_SENSITIVE, spelled out because the constant is API 33:
            // older releases ignore the flag, some keyboards honour it anyway.
            description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
    clipboard.setPrimaryClip(clip)
    return true
}

/**
 * Whether the system shows its own "Copied" confirmation. From Android 13 it does, and a second one
 * from the app would be noise; below that, the app has to say so itself.
 */
val systemConfirmsCopy: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
