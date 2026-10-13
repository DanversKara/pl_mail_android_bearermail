package de.plmail.lock

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BearerMail: a PIN (4, 6 or 12 digits) that has to be entered to open the app.
 *
 * The PIN itself is never stored: only a salted PBKDF2 hash of it, in the app's private
 * preferences. The app locks on every cold start, and again when it comes back after being in the
 * background for longer than the chosen grace period. Wrong guesses are slowed down: from the fifth
 * in a row, each one waits 30 seconds, doubling up to 15 minutes.
 *
 * This keeps someone holding an unlocked phone out of the mail. It is not encryption: the mail on
 * the phone is protected by Android's own storage encryption, as before.
 */
object AppLock {

    /** The lengths offered. */
    val LENGTHS = listOf(4, 6, 12)

    /** How long the app may sit in the background before it locks again. */
    enum class Grace(val millis: Long) {
        IMMEDIATELY(0),
        ONE_MINUTE(60_000),
        FIVE_MINUTES(5 * 60_000),
    }

    private lateinit var prefs: SharedPreferences

    private val _locked = MutableStateFlow(false)
    /** True while the lock screen must cover the app. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** When the app last went to the background, on the monotonic clock; 0 = not in background. */
    private var backgroundedAt = 0L

    /** Called once from the Application, before any activity is drawn. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs.getString(KEY_HASH, null) != null
        // A cold start always asks.
        _locked.value = _enabled.value
    }

    /** Watches the whole process going to and from the background. */
    val lifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundedAt = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                val since = backgroundedAt
                backgroundedAt = 0L
                if (since == 0L || !_enabled.value) return
                if (SystemClock.elapsedRealtime() - since >= grace.millis) _locked.value = true
            }
        }

    val pinLength: Int
        get() = prefs.getInt(KEY_LENGTH, 4)

    var grace: Grace
        get() = runCatching {
            Grace.valueOf(prefs.getString(KEY_GRACE, null) ?: "")
        }
            .getOrDefault(Grace.IMMEDIATELY)
        set(value) = prefs.edit { putString(KEY_GRACE, value.name) }

    /** Milliseconds until the next guess is allowed; 0 when one may be made now. */
    fun waitMillis(): Long {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (until > now) until - now else 0L
    }

    /**
     * Checks [pin]. Unlocks on success. On a wrong PIN, counts the failure and may start a wait;
     * returns false. Also false while a wait is running, without counting.
     */
    fun unlock(pin: String): Boolean {
        if (waitMillis() > 0) return false
        if (matches(pin)) {
            prefs.edit {
                putInt(KEY_FAILURES, 0)
                putLong(KEY_LOCKOUT_UNTIL, 0L)
            }
            _locked.value = false
            return true
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val delay = PinRules.delayAfter(failures)
        prefs.edit {
            putInt(KEY_FAILURES, failures)
            if (delay > 0) putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + delay)
        }
        return false
    }

    /** True when [pin] is the current PIN. Does not count as a guess; for the settings screen. */
    fun matches(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.let(PinRules::fromHex) ?: return false
        val hash = prefs.getString(KEY_HASH, null)?.let(PinRules::fromHex) ?: return false
        return MessageDigest.isEqual(PinRules.hash(pin, salt), hash)
    }

    /** Turns the lock on, or changes the PIN. The app stays unlocked. */
    fun setPin(pin: String) {
        require(PinRules.isValid(pin)) { "PIN must be 4, 6 or 12 digits" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs.edit {
            putString(KEY_SALT, PinRules.toHex(salt))
            putString(KEY_HASH, PinRules.toHex(PinRules.hash(pin, salt)))
            putInt(KEY_LENGTH, pin.length)
            putInt(KEY_FAILURES, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0L)
        }
        _enabled.value = true
        _locked.value = false
    }

    /** Turns the lock off. */
    fun disable() {
        prefs.edit {
            remove(KEY_SALT)
            remove(KEY_HASH)
            remove(KEY_LENGTH)
            remove(KEY_FAILURES)
            remove(KEY_LOCKOUT_UNTIL)
        }
        _enabled.value = false
        _locked.value = false
    }

    private const val PREFS = "bearermail_lock"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_LENGTH = "length"
    private const val KEY_GRACE = "grace"
    private const val KEY_FAILURES = "failures"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until"
}

/** The parts of [AppLock] that need no Android, so they can be tested on the JVM. */
internal object PinRules {

    private const val ITERATIONS = 120_000
    private const val FREE_TRIES = 4
    private const val FIRST_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 15 * 60_000L

    fun isValid(pin: String): Boolean = pin.length in AppLock.LENGTHS && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The wait after the [failures]th wrong PIN in a row: none for the first four, then 30 seconds,
     * doubling each time, capped at 15 minutes.
     */
    fun delayAfter(failures: Int): Long {
        if (failures <= FREE_TRIES) return 0L
        val doublings = (failures - FREE_TRIES - 1).coerceAtMost(10)
        return (FIRST_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun fromHex(hex: String): ByteArray? =
        if (hex.length % 2 != 0) null
        else
            runCatching {
                ByteArray(hex.length / 2) {
                    hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
                }
            }
                .getOrNull()
}
