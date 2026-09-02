package mock.location.app.ui.models

import java.security.MessageDigest
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One named spoofed environment: where the device claims to be, which LTE cell
 * it claims to be camped on, and which access points it claims to see.
 *
 * The three go together on purpose. A profile describes one place, so an app
 * cannot end up reporting a Taipei position while camped on a Shanghai cell,
 * which is exactly the kind of inconsistency a cross-check would catch.
 *
 * Every field carries a default so Moshi can read a profile written by an older
 * build of the module.
 */
data class Profile(
    val id: String = DEFAULT_ID,
    val name: String = "",

    /**
     * Each spoof is switched on independently within the profile.
     *
     * Off by default: an app with no assignment of its own follows the default
     * profile, so a freshly installed module that shipped these on would spoof
     * every app on the device with a position of 0, 0. Newly named profiles
     * also stay off until their individual editors contain valid values.
     */
    val locationEnabled: Boolean = false,
    val cellEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val simEnabled: Boolean = false,

    /** Latitude of the cell, and the position GPS reports. */
    val x: Double = 0.0,
    /** Longitude of the cell, and the position GPS reports. */
    val y: Double = 0.0,
    /** Jitter radius in metres; 0 reports the exact position. */
    val offset: Double = 0.0,

    val mcc: String = "",
    val mnc: String = "",
    val tac: Int = 0,
    /** 28-bit LTE cell identity: (eNodeB id shl 8) or sector id. */
    val eci: Int = 0,
    val pci: Int = 0,
    val earfcn: Int = 0,
    val bandwidth: Int = 0,

    val wifiAccessPoints: List<FakeAccessPoint> = emptyList(),

    /*
     * The SIM identity. The user picks a country and a carrier; everything here
     * is derived from that pair and then stored, so a hook needs no catalog
     * lookup and a carrier being reworded cannot change what an app already
     * sees. The number and the ICCID are drawn once and kept, because a SIM
     * whose serial changes between two calls is worse than no spoof at all.
     */

    /** Key into CarrierCatalog; empty until a carrier is picked. */
    val simCarrierId: String = "",
    /** Both the network and the SIM country ISO, lowercase. */
    val simCountryIso: String = "",
    /** Both the network and the SIM operator alpha tag. */
    val simOperatorName: String = "",
    val phoneNumber: String = "",
    /** ICCID. */
    val simSerial: String = "",

    /**
     * Whether the app should also see the country's language as the system one.
     * Held apart from [simEnabled] because it is the one spoof the user cannot
     * miss: the app renders in that language from then on.
     */
    val localeEnabled: Boolean = false,
    /** BCP-47 tag, derived from the country. */
    val localeTag: String = "",

    /**
     * Whether the app should also see the country's time zone.
     *
     * Separate from [localeEnabled] because the two are noticed in different
     * places: the language is what the app renders in, the zone is what its
     * timestamps say. An app that wants to know whether it is being lied to
     * asks for the zone, though, and that question needs no permission at all -
     * which makes an unspoofed zone beside a spoofed SIM, cell and position the
     * cheapest contradiction on the device to find.
     */
    val timeZoneEnabled: Boolean = false,
    /** Olson id, derived from the country. */
    val timeZoneId: String = "",
) {
    /**
     * A position drawn uniformly from the disc of radius [offset] around the
     * configured point. Returns latitude to longitude.
     *
     * Longitude degrees shrink with latitude, so the east-west component is
     * divided by cos(latitude); without that a "50 m" jitter in Helsinki would
     * only move you about 25 m east.
     */
    fun jitteredPosition(
        nowMillis: Long = System.nanoTime() / 1_000_000L,
    ): Pair<Double, Double> {
        val baseLatitude = x.takeIf { it.isFinite() }?.coerceIn(-90.0, 90.0) ?: 0.0
        val finiteLongitude = y.takeIf { it.isFinite() } ?: 0.0
        // Keep an already-valid value bit-for-bit intact. Running every value
        // through modulo arithmetic introduces a tiny rounding change even
        // when offset is zero, so an exact configured fix stops being exact.
        val baseLongitude = if (finiteLongitude in -180.0..180.0) {
            finiteLongitude
        } else {
            normalizeLongitude(finiteLongitude)
        }
        val safeOffset = offset.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        if (safeOffset == 0.0) return baseLatitude to baseLongitude

        // nanoTime is backed by Android's monotonic clock and shares the same
        // boot-relative timeline across the app and system_server processes.
        val bucket = nowMillis / JITTER_WINDOW_MS
        val fraction = (nowMillis % JITTER_WINDOW_MS).toDouble() / JITTER_WINDOW_MS
        val smooth = fraction * fraction * (3.0 - 2.0 * fraction)
        val from = jitterAnchor(baseLatitude, baseLongitude, safeOffset, bucket)
        val to = jitterAnchor(baseLatitude, baseLongitude, safeOffset, bucket + 1)
        val latitude = from.first + (to.first - from.first) * smooth
        val longitudeDelta = normalizeLongitude(to.second - from.second)
        return latitude.coerceIn(-90.0, 90.0) to
            normalizeLongitude(from.second + longitudeDelta * smooth)
    }

    private fun jitterAnchor(
        baseLatitude: Double,
        baseLongitude: Double,
        safeOffset: Double,
        bucket: Long,
    ): Pair<Double, Double> {
        val seed = stableDigest().fold(bucket) { value, byte ->
            value * 31L + (byte.toInt() and 0xff)
        }
        val random = Random(seed)
        // sqrt keeps each anchor uniform over the configured disc.
        val angle = random.nextDouble() * 2 * PI
        val radius = safeOffset * sqrt(random.nextDouble())
        val latitude = (baseLatitude + radius * cos(angle) / METRES_PER_DEGREE)
            .coerceIn(-90.0, 90.0)
        val shrink = cos(baseLatitude * PI / 180)
        val longitude = if (abs(shrink) < 1e-6) baseLongitude else normalizeLongitude(
            baseLongitude + radius * sin(angle) / (METRES_PER_DEGREE * shrink)
        )
        return latitude to longitude
    }

    /**
     * True when the profile is assigned but substitutes nothing. Worth saying
     * out loud in the UI: an app pointed at such a profile behaves exactly as
     * if the module were not installed, which otherwise looks like a failure.
     */
    val spoofsNothing: Boolean
        get() = !locationEnabled && !cellEnabled && !wifiEnabled && !simEnabled

    /** Upper 20 bits of the ECI: which base station. */
    val eNodeBId: Int get() = eci ushr 8

    /** Lower 8 bits of the ECI: which sector of that base station. */
    val sectorId: Int get() = eci and 0xFF

    /**
     * Whether the profile actually describes a cell, as opposed to merely
     * having the cell spoof switched on with nothing filled in. Reporting a
     * fabricated cell of all zeros would be worse than reporting none.
     */
    val describesCell: Boolean
        get() = mcc.trim().matches(Regex("^[0-9]{3}$")) &&
            mnc.trim().matches(Regex("^[0-9]{2,3}$")) &&
            eci in 1..268_435_455 && tac in 0..65_535 && pci in 0..503 &&
            earfcn in 0..262_143

    /**
     * MCC and MNC glued together, which is what both getNetworkOperator and
     * getSimOperator return. Empty when either half is unset, so an unconfigured
     * profile reports nothing rather than a truncated operator.
     */
    val operatorNumeric: String
        get() = if (mcc.isBlank() || mnc.isBlank()) "" else mcc.trim() + mnc.trim()

    /** A stable, 15-digit IMEI with a valid Luhn check digit. */
    val deviceImei: String
        get() {
            val serial = stableDigest().take(6).joinToString("") {
                ((it.toInt() and 0xFF) % 10).toString()
            }
            val body = "35693803$serial"
            return body + imeiCheckDigit(body)
        }

    /** A stable 14-character hexadecimal MEID in the 3GPP2 manufacturer range. */
    val deviceMeid: String
        get() = "A00000" + stableDigest().take(4).joinToString("") {
            "%02X".format(it.toInt() and 0xFF)
        }

    private fun stableDigest(): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))

    companion object {
        const val DEFAULT_ID = "default"

        /** Metres per degree of latitude, near enough anywhere on the globe. */
        const val METRES_PER_DEGREE = 111_320.0
        private const val JITTER_WINDOW_MS = 30_000L

        fun eciOf(eNodeBId: Int, sectorId: Int): Int =
            ((eNodeBId and 0xFFFFF) shl 8) or (sectorId and 0xFF)

        private fun imeiCheckDigit(body: String): Int {
            val sum = body.mapIndexed { index, character ->
                val digit = character.digitToInt()
                if (index % 2 == 0) digit else (digit * 2).let { it / 10 + it % 10 }
            }.sum()

            return (10 - sum % 10) % 10
        }

        private fun normalizeLongitude(value: Double): Double =
            ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}

/** One access point to report to apps using the owning profile. */
data class FakeAccessPoint(
    val ssid: String = "",
    val bssid: String = "02:00:00:00:00:01",
    /** Signal strength in dBm; -50 is a strong nearby AP. */
    val level: Int = -50,
    /** Centre frequency in MHz; 2437 is 2.4 GHz channel 6. */
    val frequency: Int = 2437,
    val capabilities: String = "[WPA2-PSK-CCMP][ESS]",
)
