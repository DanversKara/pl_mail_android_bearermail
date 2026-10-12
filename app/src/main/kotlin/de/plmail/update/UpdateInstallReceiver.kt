package de.plmail.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import de.plmail.R

/**
 * BearerMail: hears back from the package installer after [AppUpdater.install].
 *
 * The first answer is almost always "pending user action": Android wants the user to confirm the
 * update, and hands over the intent for its own confirmation screen, which is started from here.
 * Not exported — only the installer, through the PendingIntent this app gave it, reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // The app is replaced and restarted by the system; nothing to say.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // The user said no. Their choice; the next check will offer it again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Update not installed: $status $message")
                val text =
                    if (
                        status == PackageInstaller.STATUS_FAILURE_CONFLICT ||
                            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                    ) {
                        context.getString(R.string.update_failed_signature)
                    } else {
                        context.getString(R.string.update_failed)
                    }
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "BearerMail.Update"
    }
}
