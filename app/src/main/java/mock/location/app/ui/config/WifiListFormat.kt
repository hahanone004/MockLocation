package mock.location.app.ui.config

import mock.location.app.ui.models.FakeAccessPoint

/**
 * The access point list as editable text, one AP per line:
 *
 *     Home WiFi | a4:2b:b0:11:22:33 | -52 | 2437 | [WPA2-PSK-CCMP][ESS]
 *
 * Fields after the BSSID may be left off and fall back to the defaults on
 * [FakeAccessPoint]. A pipe separates them rather than a comma because an SSID
 * is free-form text and commas in network names are not unusual.
 *
 * A pipe is not impossible in a network name either, so one inside a field is
 * written as `\|` and a backslash as `\\`. Without that, formatting an SSID
 * that contained a pipe produced a line that parsed back as a different access
 * point - the name truncated at the pipe and the rest of it read as a BSSID.
 */
object WifiListFormat {
    private const val SEPARATOR = " | "
    private val BSSID = Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val FIVE_GHZ_CHANNELS = setOf(
        34, 36, 38, 40, 42, 44, 46, 48,
        52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165, 169, 173, 177,
        184, 188, 192, 196,
    )

    fun format(accessPoints: List<FakeAccessPoint>): String =
        accessPoints.joinToString("\n") {
            listOf(
                it.ssid,
                it.bssid,
                it.level.toString(),
                it.frequency.toString(),
                // Carried through even though it is rarely worth editing: a
                // profile stores it, and dropping it here silently reset every
                // access point to the default the next time the list was saved.
                it.capabilities,
            ).joinToString(SEPARATOR, transform = ::escape)
        }

    fun parse(text: String): List<FakeAccessPoint> {
        val parsed = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexedNotNull(::parseLine)
            .toList()
        val used = mutableSetOf<String>()

        return parsed.mapIndexed { index, accessPoint ->
            var bssid = accessPoint.bssid.lowercase()
            var salt = index
            while (!used.add(bssid)) {
                bssid = generatedBssid(++salt)
            }
            accessPoint.copy(bssid = bssid)
        }
    }

    /** Null for a line with no SSID, so a stray separator does not become an AP. */
    private fun parseLine(index: Int, line: String): FakeAccessPoint? {
        val parts = splitFields(line)
        val ssid = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null

        val defaults = FakeAccessPoint()

        return FakeAccessPoint(
            ssid = ssid,
            bssid = parts.getOrNull(1)?.takeIf(::isUsableBssid)
                ?: generatedBssid(index),
            level = parts.getOrNull(2)?.toIntOrNull()
                ?.takeIf { it in -127..0 } ?: defaults.level,
            frequency = parts.getOrNull(3)?.toIntOrNull()
                ?.takeIf(::isWifiFrequency) ?: defaults.frequency,
            capabilities = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
                ?: defaults.capabilities,
        )
    }

    /** A backslash makes the next character literal, so it can be a separator. */
    private fun escape(value: CharSequence): String =
        value.toString().replace("\\", "\\\\").replace("|", "\\|")

    private fun splitFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var escaped = false

        line.forEach { character ->
            when {
                escaped -> {
                    field.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == '|' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                else -> field.append(character)
            }
        }
        // A line ending in a lone backslash escapes nothing; keep it literal
        // rather than dropping a character the user typed.
        if (escaped) field.append('\\')
        fields.add(field.toString())

        return fields.map { it.trim() }
    }

    private fun generatedBssid(index: Int): String {
        val suffix = index.toLong() + 1L
        return "02:%02x:%02x:%02x:%02x:%02x".format(
            (suffix shr 32) and 0xff,
            (suffix shr 24) and 0xff,
            (suffix shr 16) and 0xff,
            (suffix shr 8) and 0xff,
            suffix and 0xff,
        )
    }

    private fun isUsableBssid(value: String): Boolean {
        if (!value.matches(BSSID)) return false
        val octets = value.split(':').map { it.toInt(16) }
        if (octets.all { it == 0 } || octets.all { it == 0xff }) return false
        // Bit zero of the first octet marks multicast/group addresses.
        return octets.first() and 1 == 0
    }

    private fun isWifiFrequency(value: Int): Boolean = when {
        value == 2484 -> true
        value in 2412..2472 -> (value - 2412) % 5 == 0
        value in 4915..5895 -> (value - 5000) / 5 in FIVE_GHZ_CHANNELS &&
            (value - 5000) % 5 == 0
        value == 5935 -> true
        value in 5955..7115 -> (value - 5955) % 20 == 0
        else -> false
    }
}
