package mock.location

import mock.location.app.ui.config.ConfigTransfer
import mock.location.app.ui.models.FakeAccessPoint
import mock.location.app.ui.models.Profile
import mock.location.app.ui.models.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

@ExperimentalStdlibApi
class ConfigTransferTest {

    private val taipei = Profile(
        id = "taipei",
        name = "Taipei",
        locationEnabled = true,
        x = 25.033,
        y = 121.565,
        offset = 40.0,
        mcc = "466",
        mnc = "92",
        eci = 12_345_678,
        wifiAccessPoints = listOf(FakeAccessPoint(ssid = "Cafe | Downtown")),
        simCarrierId = "tw_cht",
        phoneNumber = "0912345678",
        simSerial = "8988601912345678901",
        localeEnabled = true,
        localeTag = "zh-TW",
    )
    private val shanghai = Profile(id = "shanghai", name = "Shanghai", x = 31.230)

    private val store = ProfileStore(
        profiles = listOf(taipei, shanghai),
        defaultProfileId = "shanghai",
        assignments = mapOf("com.example.mapped" to "taipei"),
    )

    private fun readBack(text: String): ProfileStore {
        val outcome = ConfigTransfer.read(text)
        assertTrue("refused: $outcome", outcome is ConfigTransfer.Outcome.Ready)
        return (outcome as ConfigTransfer.Outcome.Ready).store
    }

    @Test
    fun `a store survives the round trip whole`() {
        assertEquals(store, readBack(ConfigTransfer.write(store, "2.2.1")))
    }

    @Test
    fun `the file names the format and the version it was written with`() {
        val text = ConfigTransfer.write(store, "2.2.1", Instant.parse("2026-09-06T10:11:12Z"))

        assertTrue(text.contains(""""format": "mock.location.config""""))
        assertTrue(text.contains(""""configVersion": ${ProfileStore.CURRENT_CONFIG_VERSION}"""))
        assertTrue(text.contains(""""exportedAt": "2026-09-06T10:11:12Z""""))
        assertTrue(text.contains(""""appVersion": "2.2.1""""))
    }

    @Test
    fun `the suggested name sorts by the moment of the export`() {
        val name = ConfigTransfer.suggestedFileName(
            Instant.parse("2026-09-06T10:11:12Z"),
            ZoneId.of("UTC"),
        )

        assertEquals("mocklocation-config-20260906-1011.json", name)
    }

    @Test
    fun `a bare store is accepted, since a hand-edited file looks like one`() {
        val bare = """{"profiles":[{"id":"taipei","name":"Taipei"}],"defaultProfileId":"taipei"}"""

        assertEquals(listOf("taipei"), readBack(bare).profiles.map { it.id })
    }

    @Test
    fun `an older file is read through the current defaults`() {
        val older = """
            {"format":"mock.location.config","configVersion":3,
             "store":{"profiles":[{"id":"taipei"}],"defaultProfileId":"taipei","configVersion":3}}
        """.trimIndent()

        assertEquals(ProfileStore.CURRENT_CONFIG_VERSION, readBack(older).configVersion)
    }

    @Test
    fun `a file from a newer build is refused rather than half read`() {
        val newer = ConfigTransfer.write(store, "9.9.9").replace(
            """"configVersion": ${ProfileStore.CURRENT_CONFIG_VERSION},""",
            """"configVersion": ${ProfileStore.CURRENT_CONFIG_VERSION + 1},""",
        )

        assertEquals(
            ConfigTransfer.Outcome.Refused(ConfigTransfer.Reason.FROM_A_NEWER_BUILD),
            ConfigTransfer.read(newer),
        )
    }

    @Test
    fun `an unrelated JSON document is refused rather than read as an empty config`() {
        // Every field of a store has a default, so this parses "successfully"
        // into a config with nothing in it if the format is not checked.
        assertEquals(
            ConfigTransfer.Outcome.Refused(ConfigTransfer.Reason.NOT_A_CONFIG),
            ConfigTransfer.read("""{"name":"something else","version":2}"""),
        )
    }

    @Test
    fun `an envelope that does not name this format is refused`() {
        assertEquals(
            ConfigTransfer.Outcome.Refused(ConfigTransfer.Reason.NOT_A_CONFIG),
            ConfigTransfer.read("""{"format":"other.app","store":{"profiles":[{"id":"a"}]}}"""),
        )
    }

    @Test
    fun `something that is not JSON at all is refused`() {
        assertEquals(
            ConfigTransfer.Outcome.Refused(ConfigTransfer.Reason.UNREADABLE),
            ConfigTransfer.read("not a config at all"),
        )
    }

    @Test
    fun `a config holding no profile is refused, since nothing could be assigned`() {
        assertEquals(
            ConfigTransfer.Outcome.Refused(ConfigTransfer.Reason.NO_PROFILES),
            ConfigTransfer.read("""{"profiles":[],"defaultProfileId":"taipei"}"""),
        )
    }

    @Test
    fun `an assignment naming a profile the file does not carry is dropped`() {
        val dangling = ConfigTransfer.write(
            store.copy(assignments = store.assignments + ("com.example.gone" to "kyoto")),
            "2.2.1",
        )

        assertEquals(mapOf("com.example.mapped" to "taipei"), readBack(dangling).assignments)
    }

    @Test
    fun `a dangling default falls back to a profile the file does carry`() {
        val dangling = ConfigTransfer.write(store.copy(defaultProfileId = "kyoto"), "2.2.1")

        assertEquals("taipei", readBack(dangling).defaultProfileId)
    }

    @Test
    fun `profiles sharing an id are collapsed, since every lookup goes by id`() {
        val duplicated = ConfigTransfer.write(
            store.copy(profiles = listOf(taipei, shanghai, taipei.copy(name = "Taipei again"))),
            "2.2.1",
        )

        assertEquals(listOf(taipei, shanghai), readBack(duplicated).profiles)
    }

    @Test
    fun `a profile with no id is dropped, since nothing can point at it`() {
        val unnamed = """{"profiles":[{"id":"","name":"nowhere"},{"id":"taipei"}]}"""

        assertEquals(listOf("taipei"), readBack(unnamed).profiles.map { it.id })
    }
}
