package fuck.location.app.ui.config

import fuck.location.app.ui.models.FakeAccessPoint

/**
 * The access point list as editable text, one AP per line:
 *
 *     Home WiFi | a4:2b:b0:11:22:33 | -52 | 2437
 *
 * Fields after the BSSID may be left off and fall back to the defaults on
 * [FakeAccessPoint]. A pipe separates them rather than a comma because an SSID
 * is free-form text and commas in network names are not unusual.
 */
object WifiListFormat {
    private const val SEPARATOR = " | "

    fun format(accessPoints: List<FakeAccessPoint>): String =
        accessPoints.joinToString("\n") {
            listOf(it.ssid, it.bssid, it.level.toString(), it.frequency.toString())
                .joinToString(SEPARATOR)
        }

    fun parse(text: String): List<FakeAccessPoint> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull(::parseLine)
        .toList()

    /** Null for a line with no SSID, so a stray separator does not become an AP. */
    private fun parseLine(line: String): FakeAccessPoint? {
        val parts = line.split('|').map { it.trim() }
        val ssid = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null

        val defaults = FakeAccessPoint()

        return FakeAccessPoint(
            ssid = ssid,
            bssid = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: defaults.bssid,
            level = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: defaults.level,
            frequency = parts.getOrNull(3)?.trim()?.toIntOrNull() ?: defaults.frequency,
        )
    }
}
