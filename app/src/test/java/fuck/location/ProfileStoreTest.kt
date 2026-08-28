package fuck.location

import fuck.location.app.ui.models.LegacyFakeLocation
import fuck.location.app.ui.models.Profile
import fuck.location.app.ui.models.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `an unassigned app falls back to the default`() {
        assertEquals(taipei, store.profileFor("com.example.other"))
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

        val profile = migrated.profileFor("com.example.any")!!
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
            migrated.profileFor("com.example.any")!!.offset,
            1e-6,
        )
    }
}
