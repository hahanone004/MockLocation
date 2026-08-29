package mock.location

import mock.location.app.ui.models.LegacyFakeLocation
import mock.location.app.ui.models.Profile
import mock.location.app.ui.models.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {

    private val taipei = Profile(id = "taipei", name = "Taipei", x = 25.033)
    private val shanghai = Profile(id = "shanghai", name = "Shanghai", x = 31.230)

    private val store = ProfileStore(
        profiles = listOf(taipei, shanghai),
        defaultProfileId = "taipei",
        assignments = mapOf("com.example.mapped" to "shanghai"),
    )

    @Test
    fun `an assigned app gets its own profile`() {
        assertEquals(shanghai, store.profileFor("com.example.mapped"))
    }

    @Test
    fun `an unassigned app follows the default`() {
        assertEquals(taipei, store.profileFor("com.example.other"))
    }

    @Test
    fun `an unassigned app tracks the default when it moves`() {
        val moved = store.copy(defaultProfileId = "shanghai")

        assertEquals(shanghai, moved.profileFor("com.example.other"))
    }

    @Test
    fun `a fresh store spoofs nothing until a switch is turned on`() {
        // Everything follows the default, so the profile a fresh install ships
        // with has to leave all three off or the whole device gets spoofed.
        val shipped = ProfileStore().profileFor("com.example.other")!!

        assertFalse(shipped.locationEnabled)
        assertFalse(shipped.cellEnabled)
        assertFalse(shipped.wifiEnabled)
    }

    @Test
    fun `an assignment pointing at a deleted profile falls back to the default`() {
        val pruned = store.copy(profiles = listOf(taipei))

        assertEquals(taipei, pruned.profileFor("com.example.mapped"))
    }

    @Test
    fun `a dangling default id falls back to the first profile`() {
        val broken = store.copy(defaultProfileId = "gone")

        assertEquals(taipei, broken.profileFor("com.example.other"))
    }

    @Test
    fun `an empty store resolves to nothing rather than throwing`() {
        assertNull(ProfileStore(profiles = emptyList()).profileFor("com.example.other"))
    }

    @Test
    fun `a legacy config becomes one default profile`() {
        val legacy = LegacyFakeLocation(x = 24.9581, y = 121.2198, eci = 81564174, tac = 13400)
        val migrated = ProfileStore.fromLegacy(legacy)

        val profile = migrated.defaultProfile()!!
        assertEquals(24.9581, profile.x, 1e-9)
        assertEquals(121.2198, profile.y, 1e-9)
        assertEquals(81564174, profile.eci)
        assertEquals(13400, profile.tac)
    }

    @Test
    fun `a legacy offset in degrees becomes a radius in metres`() {
        // Version 2 jittered by half the stored value either way, so half a
        // degree of width is a quarter degree of reach: about 27.8 km.
        val migrated = ProfileStore.fromLegacy(LegacyFakeLocation(offset = 0.5))

        assertEquals(
            0.5 * Profile.METRES_PER_DEGREE / 2,
            migrated.defaultProfile()!!.offset,
            1e-6,
        )
    }

    @Test
    fun `a legacy profile keeps its spoofs switched on`() {
        // The user had them configured and in use; the migration decides which
        // apps the profile applies to, not whether it does anything.
        val profile = ProfileStore.fromLegacy(LegacyFakeLocation()).defaultProfile()!!

        assertTrue(profile.locationEnabled)
        assertTrue(profile.cellEnabled)
        assertTrue(profile.wifiEnabled)
    }

    @Test
    fun `a legacy config still asks to be migrated`() {
        // It carries no assignments, so the whitelist has yet to be folded in
        // and the version must stay below current for that to happen.
        assertTrue(ProfileStore.fromLegacy(LegacyFakeLocation()).configVersion
            < ProfileStore.CURRENT_CONFIG_VERSION)
    }
}
