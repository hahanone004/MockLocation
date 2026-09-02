package mock.location.probe

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

/** Every probe's reading, taken in one pass. Keyed by [Probe.id]. */
typealias Sweep = Map<String, Reading>
