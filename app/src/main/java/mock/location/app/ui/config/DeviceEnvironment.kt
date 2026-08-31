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
        /**
         * The modem named cells but none of them LTE, which is what a phone on
         * 5G alone reports. Worth saying out loud: a profile describes an LTE
         * cell and there was nothing to put in it, which is a different thing
         * from the modem having said nothing at all.
         */
        val cellsWithoutLte: Boolean = false,
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
        // The cell first: it is what the modem is doing at this moment, and
        // waiting out the providers before asking would read a state up to
        // half a minute old.
        val reading = readCell(context)
        val capture = Capture(
            cell = reading.cell,
            cellsWithoutLte = reading.cell == null && reading.sawCells,
            location = currentLocation(context),
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
     * Where the phone is right now.
     *
     * Only a position from the last minute counts. One taken a minute ago is
     * the current one in every sense that matters, and indoors it is often the
     * only one there is - the providers can sit out their whole timeout and
     * come back with nothing. Older than that is refused outright: it describes
     * where the phone was, which is a different place, and nothing about the
     * result would say so.
     *
     * The providers are asked in turn and the best answer wins rather than the
     * first: the fused provider usually replies at once, but from Wi-Fi alone
     * that reply can be a hundred metres wide, and GPS is worth waiting for
     * when it is. Asking stops early once an answer is precise enough to make
     * the rest pointless.
     *
     * No provider is skipped for being disabled - asking and catching costs the
     * same, and a check that quietly answered "none of them" would come back as
     * no position and no reason for it.
     */
    private fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: run {
            Log.w("this device has no location manager to read")
            return null
        }

        // A fresh enough reading that is already precise answers the question
        // outright; anything less and the providers are still worth asking, in
        // case one of them does better.
        val onRecord = currentFixOnRecord(manager)
        if (onRecord != null && accuracyOf(onRecord) <= GOOD_ACCURACY_METRES) {
            Log.i("position taken from ${onRecord.provider}, recorded moments ago")
            return onRecord
        }

        val fixes = mutableListOf<Location>()
        FIX_PROVIDERS.forEach { provider ->
            val fix = freshFix(manager, provider) ?: return@forEach
            Log.i("$provider answered to within ${accuracyOf(fix)}m")
            fixes.add(fix)

            if (fix.hasAccuracy() && fix.accuracy <= GOOD_ACCURACY_METRES) {
                Log.i("position taken from $provider")
                return fix
            }
        }

        val best = (fixes + listOfNotNull(onRecord)).minByOrNull { accuracyOf(it) }
        if (best == null) {
            Log.w("no provider would give a position and none was recorded in the last minute")
        } else {
            Log.i("position taken from ${best.provider} to within ${accuracyOf(best)}m")
        }

        return best
    }

    /**
     * The newest position any provider has on record, if it is recent enough to
     * still be where the phone is.
     */
    @SuppressLint("MissingPermission")
    private fun currentFixOnRecord(manager: LocationManager): Location? {
        val providers = try {
            manager.allProviders
        } catch (t: Throwable) {
            FIX_PROVIDERS
        }

        val newest = providers.mapNotNull {
            try {
                manager.getLastKnownLocation(it)
            } catch (t: Throwable) {
                Log.d { "no position on record from $it: $t" }
                null
            }
        }.maxByOrNull { it.time } ?: return null

        val age = System.currentTimeMillis() - newest.time
        if (age > CURRENT_FIX_MILLIS) {
            Log.i("the newest position on record is ${age / 1_000}s old, too old to use")
            return null
        }

        return newest
    }

    /** An unstated accuracy sorts last, being the one that promises nothing. */
    private fun accuracyOf(location: Location): Float =
        if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

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

    /** What the modem had to say, and whether it said anything at all. */
    private data class CellReading(val cell: Cell?, val sawCells: Boolean)

    /**
     * The LTE cell this phone is camped on.
     *
     * The modem is asked to take a fresh reading rather than being taken at its
     * last word: getAllCellInfo on its own hands back whatever was last pushed
     * up, which after a screen-off or a network change can be stale or empty.
     * Its own answer is still the fallback, for a build where the request goes
     * unanswered.
     *
     * Registered cells first - the neighbours are in the same list and any of
     * them would be a cell the phone can see but is not on. Fields the modem
     * would not state come back as UNAVAILABLE and are stored as zero, which is
     * what an unconfigured profile holds.
     *
     * Only LTE is read, because only LTE is what a profile can hold: its
     * identity fields are TAC, ECI, PCI and EARFCN. A phone on 5G alone names
     * no LTE cell at all, and that case is reported rather than left as an
     * empty result that looks like a failure.
     */
    @SuppressLint("MissingPermission")
    private fun readCell(context: Context): CellReading {
        val manager = context.getSystemService(TelephonyManager::class.java) ?: run {
            Log.w("this device has no telephony manager to read")
            return CellReading(null, sawCells = false)
        }

        val cells = refreshedCellInfo(manager)
        if (cells.isEmpty()) {
            Log.w("the modem named no cells at all")
            return CellReading(null, sawCells = false)
        }

        val lte = cells.filterIsInstance<CellInfoLte>()
            .sortedByDescending { it.isRegistered }
            .firstOrNull()
        if (lte == null) {
            Log.w(
                "no LTE among the ${cells.size} cell(s) named: " +
                    cells.joinToString { it.javaClass.simpleName }
            )
            return CellReading(null, sawCells = true)
        }

        val identity = lte.cellIdentity
        val cell = Cell(
            mcc = identity.mccString.orEmpty(),
            mnc = identity.mncString.orEmpty(),
            tac = stated(identity.tac),
            eci = stated(identity.ci),
            pci = stated(identity.pci),
            earfcn = stated(identity.earfcn),
            bandwidth = stated(identity.bandwidth),
        )
        Log.i(
            "cell read as ${cell.mcc}/${cell.mnc} eci=${cell.eci} tac=${cell.tac}" +
                " pci=${cell.pci} earfcn=${cell.earfcn} bandwidth=${cell.bandwidth}"
        )

        return CellReading(cell, sawCells = true)
    }

    /**
     * A reading taken now, falling back to the last one the modem pushed up.
     */
    @SuppressLint("MissingPermission")
    private fun refreshedCellInfo(manager: TelephonyManager): List<CellInfo> {
        val refreshed = AtomicReference<List<CellInfo>?>(null)
        val arrived = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            manager.requestCellInfoUpdate(
                executor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        refreshed.set(cellInfo)
                        arrived.countDown()
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        Log.w("the modem refused a cell reading: $errorCode $detail")
                        arrived.countDown()
                    }
                },
            )
            if (!arrived.await(CELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w("the modem did not take a reading within ${CELL_TIMEOUT_SECONDS}s")
            }
        } catch (t: Throwable) {
            Log.w("cannot ask the modem for a reading: $t")
        } finally {
            executor.shutdown()
        }

        refreshed.get()?.takeIf { it.isNotEmpty() }?.let { return it }

        return try {
            manager.allCellInfo.orEmpty()
        } catch (t: Throwable) {
            Log.w("cannot read the cells the modem last reported: $t")
            emptyList()
        }
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

    /**
     * Long enough for GPS to come back from a cold start indoors. This is a
     * deliberate wait: the profile is built once and lived in afterwards, so
     * seconds spent here buy a position that does not have to be corrected by
     * hand later.
     */
    private const val FIX_TIMEOUT_SECONDS = 12L

    /**
     * Close enough to stop asking. A fix this tight names the building; going
     * on to wait out GPS for the sake of a few metres would only make the user
     * watch a spinner.
     */
    private const val GOOD_ACCURACY_METRES = 50f

    /**
     * How long a fix may have been sitting there and still count as where the
     * phone is. A position from within the last minute is the current one in
     * every sense that matters, and indoors it is often the only one there is.
     */
    private const val CURRENT_FIX_MILLIS = 60 * 1000L

    /** The modem answers in well under a second when it answers at all. */
    private const val CELL_TIMEOUT_SECONDS = 5L
}
