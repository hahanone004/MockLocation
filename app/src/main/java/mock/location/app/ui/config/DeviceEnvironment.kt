package mock.location.app.ui.config

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.CancellationSignal
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import mock.location.app.ui.models.FakeAccessPoint
import mock.location.app.ui.models.Profile
import mock.location.xposed.helpers.reflect.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Where the phone actually is, read once so a new profile can start out
 * describing it.
 *
 * A profile has to hold together - one position, the cell that covers it, the
 * access points standing around it - and assembling that by hand from a map and
 * a network scanner is the tedious part of making one. Reading it off the
 * device gives a profile that is consistent by construction.
 *
 * The SIM identity is deliberately not read. It names a subscriber rather than
 * a place, so copying the real one would defeat the point of spoofing it, and
 * it is the one part of a profile the user picks from a catalog instead.
 */
object DeviceEnvironment {

    /**
     * What has to be granted before any of this can be read.
     *
     * All three sources are gated on the location permission - the scan results
     * and the serving cell disclose a position just as plainly as a fix does -
     * and Android 13 added a separate one for naming nearby Wi-Fi.
     */
    val permissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )

    /** One LTE cell, in the shape a profile stores it. */
    data class Cell(
        val mcc: String,
        val mnc: String,
        val tac: Int,
        val eci: Int,
        val pci: Int,
        val earfcn: Int,
        val bandwidth: Int,
    )

    data class Capture(
        val location: Location? = null,
        val cell: Cell? = null,
        val accessPoints: List<FakeAccessPoint> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = location == null && cell == null && accessPoints.isEmpty()
    }

    fun missingPermissions(context: Context): List<String> = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reads all three. Every one of them is a round trip and the fix waits on a
     * satellite, so this belongs on a background thread.
     *
     * Each source fails on its own: a phone with no SIM still has Wi-Fi around
     * it, and a captured profile with two of the three filled in is worth more
     * than nothing at all.
     */
    fun capture(context: Context): Capture {
        val capture = Capture(
            location = currentLocation(context),
            cell = servingCell(context),
            accessPoints = visibleAccessPoints(context),
        )

        // One line naming what the device gave up, so a capture that comes back
        // half empty can be read out of logcat rather than guessed at.
        Log.i(
            "captured position=${capture.location != null}" +
                " cell=${capture.cell != null}" +
                " accessPoints=${capture.accessPoints.size}"
        )

        return capture
    }

    /**
     * [profile] with everything [capture] found written into it, and the spoofs
     * it can now answer with switched on.
     *
     * A spoof is only switched on where the data behind it arrived: enabling
     * the cell with no MCC would leave the hooks reporting an empty tower list,
     * which reads as a working spoof and behaves as a device with no network.
     * The SIM and the language are left exactly as they were.
     */
    fun applyTo(profile: Profile, capture: Capture): Profile {
        var filled = profile

        capture.location?.let {
            filled = filled.copy(x = it.latitude, y = it.longitude, locationEnabled = true)
        }

        capture.cell?.let {
            filled = filled.copy(
                mcc = it.mcc,
                mnc = it.mnc,
                tac = it.tac,
                eci = it.eci,
                pci = it.pci,
                earfcn = it.earfcn,
                bandwidth = it.bandwidth,
            )
            filled = filled.copy(cellEnabled = filled.describesCell)
        }

        if (capture.accessPoints.isNotEmpty()) {
            filled = filled.copy(wifiAccessPoints = capture.accessPoints, wifiEnabled = true)
        }

        return filled
    }

    /**
     * Where the phone is.
     *
     * A fix recorded moments ago already answers the question and costs a
     * lookup, so it is taken before any provider is made to go and find out.
     * Failing that each provider is asked in turn - the fused one first, being
     * the one that answers indoors - and failing that even a stale fix is
     * returned: somewhere the phone has been beats nowhere at all as a place to
     * start a profile from.
     *
     * No provider is skipped for being disabled. Asking and catching is the
     * same cost, and a check that quietly answered "none of them" used to leave
     * both the fix and the fallback iterating over an empty list, which came
     * back as no position and no reason for it.
     */
    private fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: run {
            Log.w("this device has no location manager to read")
            return null
        }

        val known = lastKnownLocation(manager)
        val age = known?.let { System.currentTimeMillis() - it.time }
        if (known != null && age != null && age < RECENT_FIX_MILLIS) {
            Log.i("position taken from a ${age / 1_000}s old fix on ${known.provider}")
            return known
        }

        FIX_PROVIDERS.forEach { provider ->
            freshFix(manager, provider)?.let {
                Log.i("position taken from $provider")
                return it
            }
        }

        if (known == null) {
            Log.w("no provider would give a position and none had one on record")
        } else {
            Log.i("position taken from a stale fix on ${known.provider}")
        }

        return known
    }

    /** The newest position any provider still has on record. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(manager: LocationManager): Location? {
        val providers = try {
            manager.allProviders
        } catch (t: Throwable) {
            Log.w("cannot list the location providers: $t")
            FIX_PROVIDERS
        }

        return providers.mapNotNull {
            try {
                manager.getLastKnownLocation(it)
            } catch (t: Throwable) {
                Log.d { "no last known position from $it: $t" }
                null
            }
        }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun freshFix(manager: LocationManager, provider: String): Location? {
        val fix = AtomicReference<Location?>(null)
        val arrived = CountDownLatch(1)
        val cancellation = CancellationSignal()
        val executor = Executors.newSingleThreadExecutor()

        return try {
            manager.getCurrentLocation(provider, cancellation, executor) {
                fix.set(it)
                arrived.countDown()
            }
            if (!arrived.await(FIX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w("$provider did not answer within ${FIX_TIMEOUT_SECONDS}s")
                cancellation.cancel()
            }
            fix.get()
        } catch (t: Throwable) {
            Log.w("cannot take a fix from $provider: $t")
            null
        } finally {
            executor.shutdown()
        }
    }

    /**
     * The LTE cell this phone is camped on.
     *
     * Registered cells first - the neighbours are in the same list and any of
     * them would be a cell the phone can see but is not on. Fields the modem
     * would not state come back as UNAVAILABLE and are stored as zero, which is
     * what an unconfigured profile holds.
     */
    @SuppressLint("MissingPermission")
    private fun servingCell(context: Context): Cell? {
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return null

        val cells = try {
            manager.allCellInfo.orEmpty()
        } catch (t: Throwable) {
            Log.w("cannot read the serving cell: $t")
            return null
        }

        val lte = cells.filterIsInstance<CellInfoLte>()
            .sortedByDescending { it.isRegistered }
            .firstOrNull() ?: return null
        val identity = lte.cellIdentity

        return Cell(
            mcc = identity.mccString.orEmpty(),
            mnc = identity.mncString.orEmpty(),
            tac = stated(identity.tac),
            eci = stated(identity.ci),
            pci = stated(identity.pci),
            earfcn = stated(identity.earfcn),
            bandwidth = stated(identity.bandwidth),
        )
    }

    /**
     * The access points in range, strongest first, with the one this phone is
     * associated to at the head.
     *
     * The order is what the hooks read: the first entry is the network the
     * spoofed connection reports being on, so the real association belongs
     * there. Hidden networks are dropped - a scan result with no SSID is
     * nothing a profile can describe.
     *
     * However many there are. A scan in a busy building runs to dozens and a
     * real one on that same device returns just as many, so capping the list
     * would report a thinner place than the phone is standing in. It would
     * also be a rule the hand-written list does not have: the Wi-Fi editor
     * takes as many lines as are typed into it.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun visibleAccessPoints(context: Context): List<FakeAccessPoint> {
        val manager = context.applicationContext
            .getSystemService(WifiManager::class.java) ?: return emptyList()

        val results = try {
            manager.scanResults.orEmpty()
        } catch (t: Throwable) {
            Log.w("cannot read the scan results: $t")
            return emptyList()
        }
        val associated = try {
            manager.connectionInfo?.bssid
        } catch (t: Throwable) {
            null
        }

        return results
            .filter { !it.SSID.isNullOrBlank() && !it.BSSID.isNullOrBlank() }
            .sortedWith(
                compareByDescending<ScanResult> { it.BSSID.equals(associated, ignoreCase = true) }
                    .thenByDescending { it.level }
            )
            .map {
                FakeAccessPoint(
                    ssid = it.SSID,
                    bssid = it.BSSID.lowercase(),
                    level = it.level,
                    frequency = it.frequency,
                    capabilities = it.capabilities.orEmpty(),
                )
            }
    }

    private fun stated(value: Int): Int = if (value == CellInfo.UNAVAILABLE) 0 else value

    /**
     * Asked in this order, and only these: the passive provider would sit out
     * the whole timeout waiting for another app to ask for a fix.
     */
    private val FIX_PROVIDERS = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    )

    private const val FIX_TIMEOUT_SECONDS = 6L

    /** How recent a recorded fix has to be to stand in for a fresh one. */
    private const val RECENT_FIX_MILLIS = 2 * 60 * 1000L
}
