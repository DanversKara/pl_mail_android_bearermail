package de.plmail.core.data

/**
 * BearerMail: finds a one-time code ("Your verification code is 482 913") in a message, so the
 * reader and the notification can offer to copy it.
 *
 * A code needs a code word near it — "code", "OTP", "verification", "2FA", "Bestätigungscode"… —
 * because six digits on their own are as likely to be an order number. The nearest candidate to a
 * code word wins; amounts, dates, times, phone numbers and order/invoice numbers are passed over.
 * Nothing leaves the phone: this runs on the subject and text already downloaded.
 */
object OtpDetector {

    private const val MAX_TEXT = 8000

    /** How far from a code word a code may sit, in characters. Codes are next to their label. */
    private const val WINDOW_AFTER = 120
    private const val WINDOW_BEFORE = 60

    private val KEYWORD =
        Regex(
            """\b(?:one[- ]time\s+(?:pass(?:word|code)|code|pin)|otp|2fa|mfa|two[- ](?:factor|step)|""" +
                """verification|verify|verifizierung(?:scode)?|passcode|security\s+code|""" +
                """(?:login|log-in|sign[- ]?in|signin|access|auth(?:entication)?|confirmation|""" +
                """activation|reset|single[- ]use)\s+code|pin|code|""" +
                """bestätigungscode|sicherheitscode|anmeldecode|einmalcode|einmalpasswort|""" +
                """zugangscode|freigabecode|tan|código|codice)\b""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Four to eight digits, possibly split once in the middle by a space or dash ("482 913",
     * "4829-1305"), or a short mixed code like "G-482913" or "K7Q2XM".
     */
    private val CANDIDATE =
        Regex(
            """(?<![\w$€£¥#./+,-])(?<!\d:)""" +
                """(?:""" +
                """[A-Z]{1,3}-\d{4,8}""" + // G-482913
                """|\d{3,4}[ -]\d{3,4}""" + // 482 913, 4829-1305
                """|\d{4,8}""" + // 482913
                """|(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,8}""" + // K7Q2XM
                """)""" +
                """(?![\w%€$/:-]|[.,]\d|[ -]\d)"""
        )

    /** Words that make a nearby number something other than a code. */
    private val NOT_A_CODE =
        Regex(
            """(?:order|invoice|rechnung|bestellung|account|konto|ticket|case|ref(?:erence)?|""" +
                """tracking|customer|kunden|phone|tel|fax|call|anrufen|zip|postal|plz|""" +
                """id|nr|no|number|nummer)\.?\s*(?:#|:|number|nr\.?|no\.?|nummer)?\s*$""",
            RegexOption.IGNORE_CASE,
        )

    /** "Promo code SAVE20", "zip code 90210": a code word that names something else. */
    private val NOT_A_CODE_WORD =
        Regex(
            """(?:promo|coupon|discount|voucher|gift|referral|invite|rabatt|gutschein|zip|postal|""" +
                """post|area|country|dial|source|error|status|tracking|qr|bar)\s*-?\s*$""",
            RegexOption.IGNORE_CASE,
        )

    /** A number that continues one before it: "(555) 555-1234", "+49 171 2345678". */
    private val CONTINUES_NUMBER = Regex("""(?:\d[\s-]?|\)\s?)$""")

    private val YEAR = Regex("""(?:19|20)\d\d""")

    /** The code in this message, or null. [subject] is checked first: many senders put it there. */
    fun detect(subject: String?, body: String?): String? {
        subject?.let { s ->
            find(s)?.let {
                return it
            }
        }
        return body?.let { find(it.take(MAX_TEXT)) }
    }

    private fun find(text: String): String? {
        if (text.isBlank()) return null
        val keywords =
            KEYWORD.findAll(text)
                .map { it.range }
                .filterNot {
                    NOT_A_CODE_WORD.containsMatchIn(
                        text.substring(maxOf(0, it.first - 16), it.first)
                    )
                }
                .toList()
        if (keywords.isEmpty()) return null

        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (match in CANDIDATE.findAll(text)) {
            val value = match.value
            if (!looksLikeCode(text, match.range, value)) continue
            for (k in keywords) {
                // The code word itself ("PIN", "OTP") can match the mixed-code pattern; skip it.
                if (match.range.first <= k.last && k.first <= match.range.last) continue
                val distance =
                    when {
                        match.range.first > k.last -> match.range.first - k.last
                        else ->
                            (k.first - match.range.last) *
                                2 // before the word: rarer, weigh it down
                    }
                val limit = if (match.range.first > k.last) WINDOW_AFTER else WINDOW_BEFORE * 2
                if (distance <= limit && distance < bestDistance) {
                    best = value
                    bestDistance = distance
                }
            }
        }
        return best?.let(::normalise)
    }

    private fun looksLikeCode(text: String, range: IntRange, value: String): Boolean {
        val digits = value.count(Char::isDigit)
        if (digits < 4 && value.none(Char::isLetter)) return false
        // A bare four-digit year ("© 2026") is the commonest false hit.
        if (value.length == 4 && YEAR.matches(value)) return false
        // All letters ("SIGNIN", "VERIFY") are words, not codes.
        if (digits == 0) return false
        val before = text.substring(maxOf(0, range.first - 24), range.first)
        if (NOT_A_CODE.containsMatchIn(before)) return false
        if (CONTINUES_NUMBER.containsMatchIn(before)) return false
        return true
    }

    /** "482 913" and "482-913" are typed as 482913; "G-482913" keeps its prefix. */
    private fun normalise(code: String): String =
        if (code.first().isDigit()) code.filter(Char::isDigit) else code
}
