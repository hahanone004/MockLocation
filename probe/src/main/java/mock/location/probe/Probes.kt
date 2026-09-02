package mock.location.probe

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
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
import java.util.Locale

/** The features a reading belongs to, which is how the report is grouped. */
enum class Group(@StringRes val title: Int) {
    LOCALE(R.string.group_locale),
    LOCATION(R.string.group_location),
    CELL(R.string.group_cell),
    WIFI(R.string.group_wifi),
    SIM(R.string.group_sim),
}

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
        probe("Settings.System/system_locales", Group.LOCALE, covered = false) {
            Settings.System.getString(it.contentResolver, "system_locales")
        }
        probe("SystemProperties/persist.sys.locale", Group.LOCALE, covered = false) {
            systemProperty("persist.sys.locale")
        }

        // ---- position --------------------------------------------------
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        )) {
            probe("getLastKnownLocation($provider)", Group.LOCATION) { context ->
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return@probe null
                // The position only. Accuracy, bearing and the fix time move on
                // their own even when the coordinates are pinned, and every
                // scenario would then disagree with every other one.
                manager.getLastKnownLocation(provider)?.let { fix ->
                    "%.6f, %.6f".format(Locale.ROOT, fix.latitude, fix.longitude)
                }
            }
        }

        // ---- serving cell ----------------------------------------------
        probe("getAllCellInfo()", Group.CELL) { context ->
            val telephony = context.getSystemService(TelephonyManager::class.java)
                ?: return@probe null
            telephony.allCellInfo
                ?.map(::describeCell)
                ?.sorted()
                ?.joinToString("\n")
                ?.ifEmpty { "(empty)" }
        }
        probe("getDataNetworkType()", Group.CELL) { context ->
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
        probe("getServiceState()", Group.CELL, covered = false) { context ->
            context.getSystemService(TelephonyManager::class.java)
                ?.serviceState
                ?.let { state -> "state=${state.state} operator=${state.operatorNumeric}" }
        }

        // ---- access points ---------------------------------------------
        probe("WifiManager.getScanResults()", Group.WIFI) { context ->
            val wifi = context.getSystemService(WifiManager::class.java) ?: return@probe null
            @Suppress("DEPRECATION")
            wifi.scanResults
                .map { "${it.BSSID} ${it.SSID}" }
                .sorted()
                .joinToString("\n")
                .ifEmpty { "(empty)" }
        }
        probe("getConnectionInfo().getBSSID()", Group.WIFI) { context ->
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.getBSSID()
        }
        probe("getConnectionInfo().getSSID()", Group.WIFI) { context ->
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.getSSID()
        }

        // ---- SIM identity ----------------------------------------------
        telephony("getSimOperator()") { it.simOperator }
        telephony("getSimOperatorName()") { it.simOperatorName }
        telephony("getSimCountryIso()") { it.simCountryIso }
        telephony("getNetworkCountryIso()") { it.networkCountryIso }
        telephony("getSimSerialNumber()") { it.simSerialNumber }
        telephony("getSubscriberId()") { it.subscriberId }
        telephony("getImei()") { it.imei }
        telephony("getLine1Number()") { it.line1Number }
    }

    private val byId: Map<String, Probe> = all.associateBy { it.id }

    fun byId(id: String): Probe? = byId[id]

    /**
     * Every probe, once.
     *
     * A probe that throws is recorded rather than allowed to end the sweep: an
     * app is refused READ_PHONE_STATE by default, and half the SIM probes
     * therefore throw on a device where the other half say something useful.
     */
    fun sweep(context: Context): Sweep = all.associate { probe ->
        probe.id to try {
            probe.read(context)
                ?.let { Reading.Value(it) }
                ?: Reading.Unavailable(context.getString(R.string.reading_none))
        } catch (_: SecurityException) {
            Reading.Unavailable(context.getString(R.string.reading_denied))
        } catch (t: Throwable) {
            Reading.Unavailable(t.javaClass.simpleName)
        }
    }

    private fun MutableList<Probe>.probe(
        id: String,
        group: Group,
        covered: Boolean = true,
        read: (Context) -> String?,
    ) = add(Probe(id, group, covered, read))

    private fun MutableList<Probe>.telephony(id: String, read: (TelephonyManager) -> String?) =
        probe(id, Group.SIM) { context ->
            context.getSystemService(TelephonyManager::class.java)?.let(read)
        }

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
