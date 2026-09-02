package mock.location.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.LocaleList
import android.provider.Settings
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.annotation.StringRes
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** The features a reading belongs to, which is how the report is grouped. */
enum class Group(@StringRes val title: Int) {
    LOCALE(R.string.group_locale),
    LOCATION(R.string.group_location),
    CELL(R.string.group_cell),
    WIFI(R.string.group_wifi),
    SIM(R.string.group_sim),
    TIMEZONE(R.string.group_timezone),
}

/**
 * How two readings of the same probe are told apart.
 *
 * Most answers are exact strings and any difference is a difference. A
 * position is not: a profile with a jitter radius moves the fix continuously
 * inside a disc around the configured point, so two scenarios reading the same
 * spoof will never agree to six decimal places and never should. What they do
 * is stay in the same place, which is what [COORDINATE] compares.
 */
enum class Comparison { EXACT, COORDINATE }

/**
 * One question put to the system.
 *
 * [read] answers the value as a string, or null when there is nothing to
 * report. Anything it throws is caught by [Probes.sweep] and recorded as a
 * reading that could not be taken, which is deliberately not the same as a
 * reading that disagrees.
 *
 * [id] is what the report and the saved run are keyed by, so it has to stay
 * stable across versions; the API's own name serves, and it is what the report
 * shows besides.
 *
 * [covered] marks the APIs the module actually hooks. The rest are here because
 * an app can ask them too, and knowing that one of them still answers with the
 * device's own truth is worth as much as knowing that the covered ones hold -
 * they just should not be read as a defect in a hook that never claimed them.
 */
class Probe(
    val id: String,
    val group: Group,
    val covered: Boolean,
    val comparison: Comparison,
    /**
     * What has to be granted before [read] means anything.
     *
     * Checked before the call rather than left to throw, because the
     * interesting APIs do not throw. getScanResults answers with an empty list
     * when location is not granted and getBSSID with 02:00:00:00:00:00, and
     * both of those are perfectly good values that happen to be about the
     * permission rather than about the device. Compared against a scenario that
     * did hold the permission, they read as a spoof coming apart - which is how
     * the cold sweep of a fresh install, taken before the permission dialog can
     * possibly have been answered, produced a drifted Wi-Fi reading on a device
     * with no profile assigned at all.
     */
    val permissions: List<String>,
    val read: (Context) -> String?,
)

object Probes {

    /**
     * Every probe is quick and non-blocking: cached values only, no scan, no
     * fix requested, nothing that waits on a radio. A sweep runs on whatever
     * thread the scenario calls for - including the main thread inside
     * Application.onCreate - and a probe that blocked there would change the
     * very timing the cold scenario exists to measure.
     */
    /** Coarse location is not enough: it answers with a deliberately fuzzed fix. */
    private val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private val PHONE = listOf(Manifest.permission.READ_PHONE_STATE)
    private val CELL = LOCATION + PHONE
    private val NEARBY = LOCATION + Manifest.permission.NEARBY_WIFI_DEVICES

    @SuppressLint("HardwareIds", "MissingPermission")
    val all: List<Probe> = buildList {
        // ---- language --------------------------------------------------
        probe("Locale.getDefault()", Group.LOCALE) {
            Locale.getDefault().toLanguageTag()
        }
        probe("Locale.getDefault(FORMAT)", Group.LOCALE) {
            Locale.getDefault(Locale.Category.FORMAT).toLanguageTag()
        }
        probe("LocaleList.getDefault()", Group.LOCALE) {
            LocaleList.getDefault().toLanguageTags()
        }
        probe("LocaleList.getAdjustedDefault()", Group.LOCALE) {
            LocaleList.getAdjustedDefault().toLanguageTags()
        }
        probe("context.resources.configuration.locales", Group.LOCALE) {
            it.resources.configuration.locales.toLanguageTags()
        }
        probe("Resources.getSystem().configuration.locales", Group.LOCALE) {
            Resources.getSystem().configuration.locales.toLanguageTags()
        }
        probe("LocaleManager.getSystemLocales()", Group.LOCALE) {
            it.getSystemService(LocaleManager::class.java)?.systemLocales?.toLanguageTags()
        }
        probe("LocaleManager.getApplicationLocales()", Group.LOCALE) {
            it.getSystemService(LocaleManager::class.java)
                ?.applicationLocales?.toLanguageTags()?.ifEmpty { "(none)" }
        }
        probe("Settings.System/system_locales", Group.LOCALE) {
            Settings.System.getString(it.contentResolver, "system_locales")
        }
        probe("SystemProperties/persist.sys.locale", Group.LOCALE) {
            systemProperty("persist.sys.locale")
        }

        // ---- the clock ---------------------------------------------------
        // No permission at all stands between an app and these, which is what
        // made an unspoofed zone beside a spoofed SIM the cheapest
        // contradiction on the device to find.
        probe("TimeZone.getDefault()", Group.TIMEZONE) { TimeZone.getDefault().getID() }
        probe("ZoneId.systemDefault()", Group.TIMEZONE) { ZoneId.systemDefault().id }
        probe("SystemProperties/persist.sys.timezone", Group.TIMEZONE) {
            systemProperty("persist.sys.timezone")
        }

        // ---- position --------------------------------------------------
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        )) {
            probe(
                "getLastKnownLocation($provider)",
                Group.LOCATION,
                comparison = Comparison.COORDINATE,
                permissions = LOCATION,
            ) { context ->
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return@probe null
                // The position only. Accuracy, bearing and the fix time move
                // on their own even when the coordinates are pinned, and every
                // scenario would then disagree with every other one. The
                // coordinates move too when the profile has a jitter radius,
                // which is why they are compared by distance rather than as
                // text - see Comparison.COORDINATE.
                manager.getLastKnownLocation(provider)?.let { fix ->
                    "%.6f, %.6f".format(Locale.ROOT, fix.latitude, fix.longitude)
                }
            }
        }

        // ---- position, as the system delivers it -----------------------
        // Everything above is a value an app went and asked for. These are the
        // ones that arrive on their own, which is how an app that actually
        // wants a position gets one - and a separate set of hooks in the
        // module, none of which any probe reached before.
        probe(
            "LocationListener.onLocationChanged(gps)",
            Group.LOCATION,
            comparison = Comparison.COORDINATE,
            permissions = LOCATION,
        ) { position(Watchers.gpsFix) }

        probe(
            "LocationListener.onLocationChanged(network)",
            Group.LOCATION,
            comparison = Comparison.COORDINATE,
            permissions = LOCATION,
        ) { position(Watchers.networkFix) }

        probe(
            "GnssStatus.Callback.onSatelliteStatusChanged()",
            Group.LOCATION,
            permissions = LOCATION,
        ) { Watchers.satellites ?: throw Pending() }

        probe(
            "OnNmeaMessageListener (GGA)",
            Group.LOCATION,
            comparison = Comparison.COORDINATE,
            permissions = LOCATION,
        ) { Watchers.nmeaPosition ?: throw Pending() }

        // ---- serving cell ----------------------------------------------
        probe("getAllCellInfo()", Group.CELL, permissions = CELL) { context ->
            val telephony = context.getSystemService(TelephonyManager::class.java)
                ?: return@probe null
            telephony.allCellInfo?.let(::describeCells)
        }
        probe("getDataNetworkType()", Group.CELL, permissions = PHONE) { context ->
            networkTypeName(
                context.getSystemService(TelephonyManager::class.java)?.dataNetworkType
            )
        }
        probe("getNetworkOperator()", Group.CELL) { context ->
            context.getSystemService(TelephonyManager::class.java)?.networkOperator
        }
        probe("getNetworkOperatorName()", Group.CELL) { context ->
            context.getSystemService(TelephonyManager::class.java)?.networkOperatorName
        }
        probe(
            "TelephonyCallback.onCellInfoChanged()",
            Group.CELL,
            permissions = CELL,
        ) { describeCells(Watchers.cells ?: throw Pending()) }

        probe(
            "TelephonyCallback.onDisplayInfoChanged()",
            Group.CELL,
            permissions = CELL,
        ) { Watchers.displayInfo ?: throw Pending() }

        probe(
            "getServiceState()",
            Group.CELL,
            covered = false,
            permissions = CELL,
        ) { context ->
            context.getSystemService(TelephonyManager::class.java)
                ?.serviceState
                ?.let { state -> "state=${state.state} operator=${state.operatorNumeric}" }
        }

        // ---- access points ---------------------------------------------
        probe("WifiManager.getScanResults()", Group.WIFI, permissions = NEARBY) { context ->
            val wifi = context.getSystemService(WifiManager::class.java) ?: return@probe null
            @Suppress("DEPRECATION")
            wifi.scanResults
                .map { "${it.BSSID} ${it.SSID}" }
                .sorted()
                .joinToString("\n")
                .ifEmpty { "(empty)" }
        }
        probe("getConnectionInfo().getBSSID()", Group.WIFI, permissions = NEARBY) { context ->
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.getBSSID()
        }
        probe("getConnectionInfo().getSSID()", Group.WIFI, permissions = NEARBY) { context ->
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.getSSID()
        }

        // ---- SIM identity ----------------------------------------------
        telephony("getSimOperator()") { it.simOperator }
        telephony("getSimOperatorName()") { it.simOperatorName }
        telephony("getSimCountryIso()") { it.simCountryIso }
        telephony("getNetworkCountryIso()") { it.networkCountryIso }
        telephony("getSimSerialNumber()", PHONE) { it.simSerialNumber }
        telephony("getSubscriberId()", PHONE) { it.subscriberId }
        telephony("getImei()", PHONE) { it.imei }
        telephony(
            "getLine1Number()",
            PHONE + Manifest.permission.READ_PHONE_NUMBERS,
        ) { it.line1Number }
    }

    /** Raised by a probe whose value is delivered rather than asked for. */
    private class Pending : RuntimeException()

    private val byId: Map<String, Probe> = all.associateBy { it.id }

    fun byId(id: String): Probe? = byId[id]

    /**
     * Every probe, once.
     *
     * A probe that throws is recorded rather than allowed to end the sweep: an
     * app is refused READ_PHONE_STATE by default, and half the SIM probes
     * therefore throw on a device where the other half say something useful. A
     * probe whose permissions are not held is not called at all - see
     * [Probe.permissions] for why its answer would be worse than no answer.
     */
    fun sweep(context: Context): Sweep = all.associate { probe -> probe.id to read(context, probe) }

    private fun read(context: Context, probe: Probe): Reading {
        val ungranted = probe.permissions.any {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (ungranted) return Reading.Unavailable(Reason.NOT_GRANTED)

        return try {
            probe.read(context)
                ?.let { Reading.Value(it) }
                ?: Reading.Unavailable(Reason.NONE)
        } catch (_: Pending) {
            Reading.Unavailable(Reason.PENDING)
        } catch (_: SecurityException) {
            Reading.Unavailable(Reason.DENIED)
        } catch (t: Throwable) {
            Reading.Unavailable(t.javaClass.simpleName)
        }
    }

    private fun MutableList<Probe>.probe(
        id: String,
        group: Group,
        covered: Boolean = true,
        comparison: Comparison = Comparison.EXACT,
        permissions: List<String> = emptyList(),
        read: (Context) -> String?,
    ) = add(Probe(id, group, covered, comparison, permissions, read))

    private fun MutableList<Probe>.telephony(
        id: String,
        permissions: List<String> = emptyList(),
        read: (TelephonyManager) -> String?,
    ) = probe(id, Group.SIM, permissions = permissions) { context ->
        context.getSystemService(TelephonyManager::class.java)?.let(read)
    }


    /** The coordinates of a delivered fix, or pending when none has arrived. */
    private fun position(fix: Location?): String {
        val location = fix ?: throw Pending()

        return String.format(Locale.ROOT, "%.6f, %.6f", location.latitude, location.longitude)
    }

    private fun describeCells(cells: List<CellInfo>): String =
        cells.map(::describeCell).sorted().joinToString("\n").ifEmpty { "(empty)" }

    /**
     * A cell, as the identity the module substitutes rather than as the
     * signal strengths beside it - those move by the second on a real radio.
     */
    private fun describeCell(info: CellInfo): String = when (val identity = info.cellIdentity) {
        is CellIdentityLte ->
            "LTE mcc=${identity.mccString} mnc=${identity.mncString} eci=${identity.ci}" +
                " pci=${identity.pci} tac=${identity.tac} earfcn=${identity.earfcn}"
        is CellIdentityNr ->
            "NR mcc=${identity.mccString} mnc=${identity.mncString} nci=${identity.nci}" +
                " pci=${identity.pci} tac=${identity.tac} arfcn=${identity.nrarfcn}"
        is CellIdentityWcdma ->
            "WCDMA mcc=${identity.mccString} mnc=${identity.mncString} cid=${identity.cid}" +
                " lac=${identity.lac}"
        is CellIdentityGsm ->
            "GSM mcc=${identity.mccString} mnc=${identity.mncString} cid=${identity.cid}" +
                " lac=${identity.lac}"
        else -> identity.toString()
    }

    private fun networkTypeName(type: Int?): String? = when (type) {
        null -> null
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "type $type"
    }

    /** SystemProperties is hidden, and this is a probe, so ask by reflection. */
    @SuppressLint("PrivateApi")
    private fun systemProperty(key: String): String? {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        return (get.invoke(null, key) as? String)?.ifEmpty { null }
    }
}
