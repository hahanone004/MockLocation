package fuck.location

import fuck.location.app.ui.models.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

class ProfileTest {

    @Test
    fun `a zero offset reports the exact position`() {
        val profile = Profile(x = 24.9581, y = 121.2198, offset = 0.0)

        repeat(20) {
            assertEquals(24.9581 to 121.2198, profile.jitteredPosition())
        }
    }

    @Test
    fun `jitter stays inside the requested radius`() {
        val profile = Profile(x = 24.9581, y = 121.2198, offset = 50.0)

        repeat(2000) {
            val (latitude, longitude) = profile.jitteredPosition()

            val northing = (latitude - profile.x) * Profile.METRES_PER_DEGREE
            val easting = (longitude - profile.y) *
                Profile.METRES_PER_DEGREE * cos(profile.x * PI / 180)

            // Allow a rounding sliver on top of the 50 m radius.
            assertTrue(
                "displacement was ${hypot(northing, easting)} m",
                hypot(northing, easting) <= 50.0 + 1e-6,
            )
        }
    }

    @Test
    fun `jitter is not stuck at the centre`() {
        val profile = Profile(x = 24.9581, y = 121.2198, offset = 50.0)
        val positions = (1..200).map { profile.jitteredPosition() }.toSet()

        assertTrue("expected varied positions, got ${positions.size}", positions.size > 100)
    }

    @Test
    fun `easting is corrected for latitude`() {
        // At 60 degrees north a degree of longitude is half as wide, so the same
        // radius has to move roughly twice as far in degrees as it does at the
        // equator. Without the correction both would come out the same.
        val equator = Profile(x = 0.0, y = 0.0, offset = 1000.0)
        val north = Profile(x = 60.0, y = 0.0, offset = 1000.0)

        val equatorSpread = (1..4000).maxOf { kotlin.math.abs(equator.jitteredPosition().second) }
        val northSpread = (1..4000).maxOf { kotlin.math.abs(north.jitteredPosition().second) }

        assertTrue("equator $equatorSpread vs north $northSpread", northSpread > equatorSpread * 1.5)
    }

    @Test
    fun `ECI splits into eNodeB and sector`() {
        val profile = Profile(eci = 81564174)

        assertEquals(318610, profile.eNodeBId)
        assertEquals(14, profile.sectorId)
    }

    @Test
    fun `composing an ECI is the inverse of splitting it`() {
        val profile = Profile(eci = Profile.eciOf(318610, 14))

        assertEquals(81564174, profile.eci)
        assertEquals(318610, profile.eNodeBId)
        assertEquals(14, profile.sectorId)
    }

    @Test
    fun `a sector wider than a byte cannot corrupt the eNodeB`() {
        val profile = Profile(eci = Profile.eciOf(318610, 260))

        assertEquals(318610, profile.eNodeBId)
        assertEquals(260 and 0xFF, profile.sectorId)
    }

    @Test
    fun `the operator numeric is the MCC and the MNC together`() {
        assertEquals("46692", Profile(mcc = "466", mnc = "92").operatorNumeric)
        assertEquals("46601", Profile(mcc = "466", mnc = "01").operatorNumeric)
    }

    @Test
    fun `a half-configured operator reports nothing rather than a truncated one`() {
        assertEquals("", Profile(mcc = "466").operatorNumeric)
        assertEquals("", Profile(mnc = "92").operatorNumeric)
        assertEquals("", Profile().operatorNumeric)
    }

    @Test
    fun `a profile substitutes nothing until one of its switches is on`() {
        assertTrue(Profile().spoofsNothing)

        // Coordinates alone change nothing, which is what the label has to say.
        assertTrue(Profile(x = 24.9581, y = 121.2198).spoofsNothing)

        assertFalse(Profile(locationEnabled = true).spoofsNothing)
        assertFalse(Profile(cellEnabled = true).spoofsNothing)
        assertFalse(Profile(wifiEnabled = true).spoofsNothing)
        assertFalse(Profile(simEnabled = true).spoofsNothing)
    }

    @Test
    fun `derived device identities are stable and correctly shaped`() {
        val profile = Profile(id = "taiwan-taoyuan")

        assertEquals(profile.deviceImei, Profile(id = "taiwan-taoyuan").deviceImei)
        assertEquals(15, profile.deviceImei.length)
        assertTrue(profile.deviceImei.all(Char::isDigit))
        assertEquals(0, luhnSum(profile.deviceImei) % 10)

        assertEquals(profile.deviceMeid, Profile(id = "taiwan-taoyuan").deviceMeid)
        assertEquals(14, profile.deviceMeid.length)
        assertTrue(profile.deviceMeid.all { it in '0'..'9' || it in 'A'..'F' })
    }

    private fun luhnSum(value: String): Int = value.mapIndexed { index, character ->
        val digit = character.digitToInt()
        if (index % 2 == 0) digit else (digit * 2).let { it / 10 + it % 10 }
    }.sum()
}
