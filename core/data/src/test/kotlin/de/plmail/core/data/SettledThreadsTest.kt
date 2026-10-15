package de.plmail.core.data

import de.plmail.jmap.mail.Email
import de.plmail.jmap.protocol.EmailId
import de.plmail.jmap.protocol.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BearerMail: which conversations lose their notification after a sync — mail read on the web, or
 * moved out of the inbox there.
 */
class SettledThreadsTest {

    private val inbox = "1"
    private val archive = "9"

    @Test
    fun `a conversation read elsewhere is settled`() {
        assertEquals(setOf("t1"), settledThreads(listOf(email("a", "t1", seen = true)), inbox))
    }

    @Test
    fun `a conversation still unread in the inbox is not`() {
        assertEquals(emptySet(), settledThreads(listOf(email("a", "t1")), inbox))
    }

    @Test
    fun `a conversation archived unread is settled`() {
        assertEquals(
            setOf("t1"),
            settledThreads(listOf(email("a", "t1", boxes = listOf(archive))), inbox),
        )
    }

    @Test
    fun `one unread message in the inbox keeps the conversation`() {
        val page = listOf(email("a", "t1", seen = true), email("b", "t1"))

        assertEquals(emptySet(), settledThreads(page, inbox))
    }

    @Test
    fun `without a known inbox only reading settles`() {
        val page = listOf(email("a", "t1", boxes = listOf(archive)), email("b", "t2", seen = true))

        assertEquals(setOf("t2"), settledThreads(page, inboxMailboxId = null))
    }

    private fun email(
        id: String,
        thread: String,
        seen: Boolean = false,
        boxes: List<String> = listOf(inbox),
    ): Email =
        Email(
            id = EmailId(id),
            threadId = ThreadId(thread),
            subject = "Hello",
            receivedAt = "2026-10-14T09:05:00Z",
            mailboxIds = boxes.associateWith { true },
            keywords = if (seen) mapOf("\$seen" to true) else emptyMap(),
        )
}
