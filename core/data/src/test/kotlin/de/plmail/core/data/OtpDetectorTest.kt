package de.plmail.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * One-time codes found in mail, and the numbers that only look like them.
 *
 * A wrong "Copy code" is worse than none: someone pastes an order number into a login form and
 * thinks the sign-in is broken. So most of these are the misses.
 */
class OtpDetectorTest {

    @Test
    fun `a code after its label`() {
        assertEquals("482913", OtpDetector.detect("Sign in", "Your verification code is 482913."))
        assertEquals(
            "482913",
            OtpDetector.detect(null, "Your one-time password: 482913\nIt expires in 10 minutes."),
        )
        assertEquals("8304", OtpDetector.detect(null, "Use PIN 8304 to confirm."))
    }

    @Test
    fun `a code in the subject wins`() {
        assertEquals(
            "739201",
            OtpDetector.detect(
                "739201 is your Instagram code",
                "Someone tried to log in. Order 55512345.",
            ),
        )
    }

    @Test
    fun `split codes are copied as they are typed`() {
        assertEquals("482913", OtpDetector.detect(null, "Your security code: 482 913"))
        assertEquals("48291305", OtpDetector.detect(null, "Login code 4829-1305"))
    }

    @Test
    fun `prefixed and mixed codes`() {
        assertEquals(
            "G-482913",
            OtpDetector.detect("G-482913 is your Google verification code", null),
        )
        assertEquals("K7Q2XM", OtpDetector.detect(null, "Enter this code to continue: K7Q2XM"))
    }

    @Test
    fun `german mail`() {
        assertEquals("551028", OtpDetector.detect(null, "Ihr Bestätigungscode lautet: 551028"))
    }

    @Test
    fun `no code word, no code`() {
        assertNull(OtpDetector.detect("Your order", "Thanks! Order 55512345 ships tomorrow."))
        assertNull(OtpDetector.detect("Lunch", "See you at 12:30 on 10/14."))
    }

    @Test
    fun `numbers that are something else`() {
        assertNull(OtpDetector.detect(null, "Use promo code SAVE20 at checkout."))
        assertNull(OtpDetector.detect(null, "Code of conduct updated. Call (555) 555-1234. © 2026"))
        assertNull(OtpDetector.detect(null, "Verify your payment of $1,250.00 on invoice 448812."))
        assertNull(OtpDetector.detect(null, "Verification needed for order #448812"))
        assertNull(OtpDetector.detect(null, "Enter the zip code 90210"))
    }

    @Test
    fun `the code nearest its label, not the first number in the mail`() {
        val body =
            "Invoice 77881234 is ready.\n\nTo view it, enter the verification code 019283 below."
        assertEquals("019283", OtpDetector.detect(null, body))
    }
}
