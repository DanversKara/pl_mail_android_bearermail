package de.plmail.core.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import de.plmail.core.data.NewMessage
import de.plmail.core.data.OtpDetector
import de.plmail.crypto.EncryptedMessages

/**
 * One conversation, as it sits in the shade.
 *
 * `MessagingStyle` rather than `BigTextStyle`, and not for decoration: it is the style Android
 * Auto, Wear and the conversation section of the shade all understand, so it is what makes the
 * sender's name the heading rather than the app's. A mail client that announces itself instead of
 * announcing who wrote is the wrong way round.
 *
 * A top-level function rather than a method on [MailNotifier], because it now has a second caller
 * that is not the sync: an inline reply the user *sent nothing in* has to put this row back exactly
 * as it was — see [ReplyNotifications.restore] — and a second builder that drifted from this one
 * would show a different notification for the same conversation depending on which code path last
 * touched it.
 */
internal fun conversationNotification(
    context: Context,
    message: NewMessage,
    channelId: String,
    group: String,
    destinations: MailDestinations,
): Notification {
    val notificationId = notificationId(message)
    val sender = Person.Builder().setName(message.sender).build()
    val encrypted = context.getString(R.string.encrypted_message)
    // The outer subject of an encrypted message is often only the placeholder
    // its client put there ("..."); the real one is inside, out of reach here.
    val subject =
        if (message.isEncrypted && EncryptedMessages.isPlaceholderSubject(message.subject)) {
            encrypted
        } else {
            message.subject ?: context.getString(R.string.no_subject)
        }

    val style =
        messagingStyle(context)
            .setConversationTitle(subject)
            .addMessage(
                if (message.isEncrypted) encrypted else message.preview,
                message.receivedAt,
                sender,
            )

    // BearerMail: a one-time code in the subject or preview gets a "Copy code" button, first in
    // the row so it is the one the thumb lands on.
    val code =
        if (message.isEncrypted) null else OtpDetector.detect(message.subject, message.preview)

    val builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_mail)
            .setStyle(style)
            .setContentTitle(message.sender)
            .setContentText(if (message.isEncrypted) encrypted else subject)
            .setWhen(message.receivedAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(group)
            .setAutoCancel(true)
            .setContentIntent(destinations.openConversation(message.accountKey, message.threadId))
            // Dismissing one conversation must not leave the account's summary
            // behind with nothing under it, which is what happens if nobody
            // tidies up -- see NotificationActions.
            .setDeleteIntent(
                NotificationActions.dismissed(context, message.accountKey, notificationId)
            )

    // Android draws three actions at most. With a code, Copy takes Mark read's place: opening the
    // mail or archiving it reads it anyway, and Reply must stay reachable.
    if (code != null) {
        builder.addAction(
            NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_copy,
                    context.getString(R.string.action_copy_code, code),
                    CopyCodeReceiver.intent(context, code, notificationId),
                )
                .build()
        )
    }
    builder.addAction(
        NotificationCompat.Action.Builder(
                R.drawable.ic_notification_archive,
                context.getString(R.string.action_archive),
                NotificationActions.archive(context, message, notificationId),
            )
            .build()
    )
    if (code == null) {
        builder.addAction(
            NotificationCompat.Action.Builder(
                    R.drawable.ic_notification_read,
                    context.getString(R.string.action_mark_read),
                    NotificationActions.markRead(context, message, notificationId),
                )
                .build()
        )
    }

    // Answering from the shade sends in the clear, and an encrypted
    // conversation is the one place that must not happen by accident.
    if (!message.isEncrypted) builder.addAction(inlineReplyAction(context, message))

    return builder.build()
}

/**
 * The style's own "you", which every one of these notifications needs.
 *
 * `MessagingStyle` requires a name for the reader as well as the sender. It is never drawn for an
 * incoming message, but a blank one renders as a stray separator — and it *is* drawn the moment a
 * reply is added to the conversation, which is the whole point of the inline reply.
 */
internal fun messagingStyle(context: Context): NotificationCompat.MessagingStyle =
    NotificationCompat.MessagingStyle(
        Person.Builder().setName(context.getString(R.string.notification_you)).build()
    )
