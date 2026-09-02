package mock.location.probe

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.HandlerThread
import android.telephony.CellInfo
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.util.Locale
import java.util.concurrent.Executor

/**
 * What the system pushes at an app, rather than what an app asks it for.
 *
 * Every probe outside this file reads a value back from an API. That is the
 * path an app is least likely to use for a position: real apps register a
 * listener and take what arrives. The module hooks that delivery separately -
 * onLocationChanged and processLocation for a fix, five GNSS registrations for
 * the satellites behind it, onCellInfoChanged and onDisplayInfoChanged for the
 * cell - and none of it was ever covered here, so a spoof could hold for every
 * getter and come apart on the callback nobody looked at.
 *
 * Registered once for the process and left running; each callback keeps only
 * its latest value. A sweep reads those values and never waits for one, which
 * is what keeps a sweep as cheap in the cold scenario as everywhere else. A
 * value that has not arrived yet is reported as pending rather than compared
 * against anything - the cold sweep runs in the same breath as the
 * registration and cannot have received anything at all.
 */
object Watchers {

    /** Latest delivery per source. Written from the watcher thread. */
    @Volatile
    var gpsFix: Location? = null
        private set

    @Volatile
    var networkFix: Location? = null
        private set

    @Volatile
    var satellites: String? = null
        private set

    @Volatile
    var nmeaPosition: String? = null
        private set

    @Volatile
    var cells: List<CellInfo>? = null
        private set

    @Volatile
    var displayInfo: String? = null
        private set

    private val thread = HandlerThread("probe-watchers")
    private var started = false

    /**
     * Idempotent, and safe to call before the permissions are held: it starts
     * nothing in that case and the next call - the activity makes one after the
     * permission dialog - registers for real.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context) {
        if (started) return
        if (!holds(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) return

        thread.start()
        val handler = Handler(thread.looper)
        val executor = Executor { command -> handler.post(command) }
        started = true

        watchLocation(context, handler, executor)
        watchTelephony(context, executor)
    }

    @SuppressLint("MissingPermission")
    private fun watchLocation(context: Context, handler: Handler, executor: Executor) {
        val manager = context.getSystemService(LocationManager::class.java) ?: return

        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MINIMUM_INTERVAL_MILLIS,
                0f,
                LocationListener { gpsFix = it },
                handler.looper,
            )
        }
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MINIMUM_INTERVAL_MILLIS,
                0f,
                LocationListener { networkFix = it },
                handler.looper,
            )
        }

        runCatching {
            manager.registerGnssStatusCallback(
                executor,
                object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        satellites = describe(status)
                    }
                },
            )
        }
        runCatching {
            manager.addNmeaListener(
                executor,
                OnNmeaMessageListener { message, _ -> positionFrom(message)?.let { nmeaPosition = it } },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun watchTelephony(context: Context, executor: Executor) {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return
        if (!holds(context, android.Manifest.permission.READ_PHONE_STATE)) return

        val callback = object :
            TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.DisplayInfoListener {

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                cells = cellInfo.toList()
            }

            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                displayInfo = "network=${info.networkType} override=${info.overrideNetworkType}"
            }
        }

        runCatching { telephony.registerTelephonyCallback(executor, callback) }
    }

    /**
     * The satellites, as identity rather than as signal: which constellation
     * and which vehicle, sorted. Signal strength and elevation move by the
     * second on a real sky and would make every scenario disagree with every
     * other one.
     */
    private fun describe(status: GnssStatus): String {
        if (status.satelliteCount == 0) return "(none)"

        return (0 until status.satelliteCount)
            .map { "${status.getConstellationType(it)}/${status.getSvid(it)}" }
            .sorted()
            .joinToString(",")
    }

    /**
     * The position out of an NMEA fix sentence, which carries one in plain text
     * whatever the Location APIs above are reporting.
     */
    private fun positionFrom(sentence: String): String? {
        val fields = sentence.trim().split(',')
        if (fields.size < 6) return null
        if (!fields[0].endsWith("GGA")) return null

        val latitude = degrees(fields[2], fields[3], 2) ?: return null
        val longitude = degrees(fields[4], fields[5], 3) ?: return null

        return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
    }

    /** NMEA writes ddmm.mmmm, with the degrees in a fixed-width prefix. */
    private fun degrees(value: String, hemisphere: String, width: Int): Double? {
        if (value.length <= width) return null

        val whole = value.take(width).toDoubleOrNull() ?: return null
        val minutes = value.drop(width).toDoubleOrNull() ?: return null
        val decimal = whole + minutes / 60.0

        return if (hemisphere == "S" || hemisphere == "W") -decimal else decimal
    }

    private fun holds(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Fast enough to have something by the time a run starts, and no faster. */
    private const val MINIMUM_INTERVAL_MILLIS = 1_000L
}
