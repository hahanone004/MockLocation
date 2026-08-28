package fuck.location

import fuck.location.app.ui.models.LegacyFakeLocation
import fuck.location.app.ui.models.Profile
import fuck.location.app.ui.models.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {

    private val taipei = Profile(id = "taipei", name = "Taipei", x = 25.033)
    private val shanghai = Profile(id = "shanghai", name = "Shanghai", x = 31.230)

    private val store = ProfileStore(
        profiles = listOf(taipei, shanghai),
        defaultProfileId = "taipei",
        assignments = mapOf(
            "com.example.mapped" to "shanghai",
            "com.example.follower" to ProfileStore.FOLLOW_DEFAULT,
        ),
    )

    @Test
    fun `an assigned app gets its own profile`() {
        assertEquals(shanghai, store.profileFor("com.example.mapped"))
    }

    @Test
    fun `an app following the default gets the default profile`() {
        assertEquals(taipei, store.profileFor("com.example.follower"))
    }

    @Test
    fun `a follower tracks the default when it moves`() {
        val moved = store.copy(defaultProfileId = "shanghai")

        assertEquals(shanghai, moved.profileFor("com.example.follower"))
    }

    @Test
    fun `an app that was never assigned is left alone`() {
        // Assignment is the only gate, so an untouched app must never be
        // spoofed just because a default profile exists.
        assertNull(store.profileFor("com.example.untouched"))
    }

    @Test
    fun `an assignment pointing at a deleted profile falls back to the default`() {
        val pruned = store.copy(profiles = listOf(taipei))

        assertEquals(taipei, pruned.profileFor("com.example.mapped"))
    }

    @Test
    fun `a dangling default id falls back to the first profile`() {
        val broken = store.copy(defaultProfileId = "gone")

        assertEquals(taipei, broken.profileFor("com.example.follower"))
    }

    @Test
    fun `an empty store resolves to nothing rather than throwing`() {
        val empty = ProfileStore(
            profiles = emptyList(),
            assignments = mapOf("com.example.follower" to ProfileStore.FOLLOW_DEFAULT),
        )

        assertNull(empty.profileFor("com.example.follower"))
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
    fun `a legacy config still asks to be migrated`() {
        // It carries no assignments, so the whitelist has yet to be folded in
        // and the version must stay below current for that to happen.
        assertTrue(ProfileStore.fromLegacy(LegacyFakeLocation()).configVersion
            < ProfileStore.CURRENT_CONFIG_VERSION)
    }
}
