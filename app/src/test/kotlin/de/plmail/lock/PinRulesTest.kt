package de.plmail.lock

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app lock's PIN rules: what counts as a PIN, how it is hashed, how guessing is slowed. */
class PinRulesTest {

    @Test
    fun `only 4, 6 or 12 digits are a PIN`() {
        assertTrue(PinRules.isValid("1234"))
        assertTrue(PinRules.isValid("123456"))
        assertTrue(PinRules.isValid("123456789012"))
        assertFalse(PinRules.isValid("12345"))
        assertFalse(PinRules.isValid("12a4"))
        assertFalse(PinRules.isValid(""))
    }

    @Test
    fun `the same PIN and salt hash the same, a different PIN or salt does not`() {
        val salt = ByteArray(16) { it.toByte() }
        val other = ByteArray(16) { (it + 1).toByte() }
        assertContentEquals(PinRules.hash("1234", salt), PinRules.hash("1234", salt))
        assertFalse(PinRules.hash("1234", salt).contentEquals(PinRules.hash("1235", salt)))
        assertFalse(PinRules.hash("1234", salt).contentEquals(PinRules.hash("1234", other)))
    }

    @Test
    fun `hex survives a round trip`() {
        val bytes = byteArrayOf(0, 15, 16, -1, 127, -128)
        assertContentEquals(bytes, PinRules.fromHex(PinRules.toHex(bytes)))
        assertEquals("000f10ff7f80", PinRules.toHex(bytes))
    }

    @Test
    fun `four free tries, then 30 seconds doubling to 15 minutes`() {
        assertEquals(0L, PinRules.delayAfter(4))
        assertEquals(30_000L, PinRules.delayAfter(5))
        assertEquals(60_000L, PinRules.delayAfter(6))
        assertEquals(120_000L, PinRules.delayAfter(7))
        assertEquals(15 * 60_000L, PinRules.delayAfter(20))
        assertEquals(15 * 60_000L, PinRules.delayAfter(1_000))
    }
}
