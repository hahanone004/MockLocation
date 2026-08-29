package fuck.location

import fuck.location.app.ui.config.WifiListFormat
import fuck.location.app.ui.models.FakeAccessPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiListFormatTest {

    @Test
    fun `reads a fully specified line`() {
        val parsed = WifiListFormat.parse("Home WiFi | a4:2b:b0:11:22:33 | -52 | 2437")

        assertEquals(1, parsed.size)
        assertEquals("Home WiFi", parsed[0].ssid)
        assertEquals("a4:2b:b0:11:22:33", parsed[0].bssid)
        assertEquals(-52, parsed[0].level)
        assertEquals(2437, parsed[0].frequency)
    }

    @Test
    fun `omitted fields fall back to the defaults`() {
        val defaults = FakeAccessPoint()
        val parsed = WifiListFormat.parse("Home WiFi | a4:2b:b0:11:22:33")[0]

        assertEquals(defaults.level, parsed.level)
        assertEquals(defaults.frequency, parsed.frequency)
    }

    @Test
    fun `keeps commas that belong to the network name`() {
        val parsed = WifiListFormat.parse("Cafe, Downtown | a4:2b:b0:11:22:33")[0]

        assertEquals("Cafe, Downtown", parsed.ssid)
    }

    @Test
    fun `skips blank lines and lines with no name`() {
        val parsed = WifiListFormat.parse("\n  \nHome | a4:2b:b0:11:22:33\n | 00:11:22:33:44:55\n")

        assertEquals(1, parsed.size)
        assertEquals("Home", parsed[0].ssid)
    }

    @Test
    fun `an empty list round trips`() {
        assertTrue(WifiListFormat.parse(WifiListFormat.format(emptyList())).isEmpty())
    }

    @Test
    fun `formatting then parsing returns what went in`() {
        val accessPoints = listOf(
            FakeAccessPoint("Home WiFi", "a4:2b:b0:11:22:33", -52, 2437),
            FakeAccessPoint("Office 5G", "b8:27:eb:aa:bb:cc", -70, 5180),
        )

        assertEquals(accessPoints, WifiListFormat.parse(WifiListFormat.format(accessPoints)))
    }

    @Test
    fun `a pipe in the network name survives the round trip`() {
        val accessPoints = listOf(
            FakeAccessPoint("Cafe | Free", "a4:2b:b0:11:22:33", -52, 2437),
        )

        assertEquals(accessPoints, WifiListFormat.parse(WifiListFormat.format(accessPoints)))
    }

    @Test
    fun `a backslash in the network name survives the round trip`() {
        val accessPoints = listOf(
            FakeAccessPoint("Back\\slash", "a4:2b:b0:11:22:33", -52, 2437),
        )

        assertEquals(accessPoints, WifiListFormat.parse(WifiListFormat.format(accessPoints)))
    }

    @Test
    fun `capabilities are not reset by editing the list`() {
        val accessPoints = listOf(
            FakeAccessPoint("Open Wifi", "a4:2b:b0:11:22:33", -52, 2437, "[ESS]"),
        )

        assertEquals(accessPoints, WifiListFormat.parse(WifiListFormat.format(accessPoints)))
    }

    @Test
    fun `an omitted capabilities field keeps the default`() {
        val parsed = WifiListFormat.parse("Home | a4:2b:b0:11:22:33 | -52 | 2437")[0]

        assertEquals(FakeAccessPoint().capabilities, parsed.capabilities)
    }

    @Test
    fun `unreadable numbers fall back rather than dropping the access point`() {
        val parsed = WifiListFormat.parse("Home | a4:2b:b0:11:22:33 | strong | wide")[0]

        assertEquals(FakeAccessPoint().level, parsed.level)
        assertEquals(FakeAccessPoint().frequency, parsed.frequency)
    }
}
