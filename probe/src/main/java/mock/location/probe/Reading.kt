package mock.location.probe

import android.content.Context

/**
 * One probe's answer, taken once in one scenario.
 *
 * The two cases are kept apart because only the first can disagree with
 * anything. A reading that could not be taken - the permission was refused, the
 * device has no such radio, the API threw - carries no information about
 * whether a spoof held, and comparing it with anything would manufacture a
 * finding out of a missing answer.
 */
sealed class Reading {

    data class Value(val text: String) : Reading()

    data class Unavailable(val reason: String) : Reading()

    val value: String? get() = (this as? Value)?.text

    /** Flattened for the trip to the remote process and to disk. */
    fun encode(): String = when (this) {
        is Value -> "V$text"
        is Unavailable -> "U$reason"
    }

    companion object {
        fun decode(encoded: String): Reading = when {
            encoded.startsWith("V") -> Value(encoded.drop(1))
            else -> Unavailable(encoded.drop(1))
        }
    }
}

/**
 * Why a reading could not be taken, held as a stable key and turned into words
 * only when it is shown.
 *
 * It used to be the words themselves, resolved through whatever Context the
 * sweep was handed - which meant the reason was written in the language of that
 * Context. In a process whose language is being spoofed that is not one
 * language: the application context reported the profile's, the activity's
 * reported the device's, and the same refusal came out in two languages in one
 * report. Nothing was compared wrongly, but a report that says two different
 * things about one refusal is a report nobody should have to second-guess.
 */
object Reason {

    const val NONE = "none"
    const val DENIED = "denied"
    const val NOT_GRANTED = "not-granted"
    const val NO_REMOTE = "no-remote"

    /** A value that is delivered rather than asked for, and has not been yet. */
    const val PENDING = "pending"

    /** Anything else is the name of whatever the probe threw, and stands alone. */
    fun text(context: Context, reason: String): String = when (reason) {
        NONE -> context.getString(R.string.reading_none)
        DENIED -> context.getString(R.string.reading_denied)
        NOT_GRANTED -> context.getString(R.string.reading_not_granted)
        NO_REMOTE -> context.getString(R.string.reading_no_remote)
        PENDING -> context.getString(R.string.reading_pending)
        else -> reason
    }
}

/** Every probe's reading, taken in one pass. Keyed by [Probe.id]. */
typealias Sweep = Map<String, Reading>
