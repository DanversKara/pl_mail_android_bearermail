package de.plmail.core.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import de.plmail.core.data.copyOneTimeCode
import de.plmail.core.data.systemConfirmsCopy

/**
 * BearerMail: "Copy code" on a new-mail notification that carries a one-time code.
 *
 * Its own receiver rather than a branch of [NotificationActionReceiver], because that one dismisses
 * the notification before anything else, and a copy should leave it where it is — the user is
 * usually halfway through a sign-in in another app and may need to look at the mail again. Not
 * exported, like the others.
 */
class CopyCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        if (context.copyOneTimeCode(code) && !systemConfirmsCopy) {
            Toast.makeText(context, context.getString(R.string.code_copied), Toast.LENGTH_SHORT)
                .show()
        }
    }

    internal companion object {
        private const val EXTRA_CODE = "code"

        fun intent(context: Context, code: String, notificationId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                // Per notification, for the same reason as NotificationActions: two codes in the
                // shade must not copy the same one.
                ("copy-code$notificationId").hashCode(),
                Intent(context, CopyCodeReceiver::class.java).putExtra(EXTRA_CODE, code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
