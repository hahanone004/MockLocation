package fuck.location

import fuck.location.app.ui.models.CarrierCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CarrierCatalogTest {

    private val taiwan = CarrierCatalog.countryOf("tw")!!
    private val chunghwa = CarrierCatalog.carrierOf("tw-cht")!!.second

    @Test
    fun `the check digit is the one Luhn's algorithm asks for`() {
        // The textbook example: 79927398713 is the valid form of 7992739871.
        assertEquals(3, CarrierCatalog.luhnCheckDigit("7992739871"))
        assertTrue(CarrierCatalog.luhnValid("79927398713"))
        assertFalse(CarrierCatalog.luhnValid("79927398714"))
    }

    @Test
    fun `an ICCID carries the country, the issuer and a valid check digit`() {
        repeat(200) {
            val iccid = CarrierCatalog.identityFor(taiwan, chunghwa).simSerial

            assertEquals(CarrierCatalog.ICCID_LENGTH, iccid.length)
            assertTrue(iccid.all { it.isDigit() })
            assertTrue("$iccid does not start 89 886 92", iccid.startsWith("8988692"))
            assertTrue("$iccid fails a Luhn check", CarrierCatalog.luhnValid(iccid))
        }
    }

    @Test
    fun `a number sits in one of the carrier's own ranges`() {
        repeat(200) {
            val number = CarrierCatalog.identityFor(taiwan, chunghwa).phoneNumber

            assertEquals(taiwan.numberLength, number.length)
            assertTrue(number.all { it.isDigit() })
            assertTrue(
                "$number is not in a Chunghwa range",
                chunghwa.numberPrefixes.any { number.startsWith(it) },
            )
        }
    }

    @Test
    fun `the same seed draws the same identity`() {
        val first = CarrierCatalog.identityFor(taiwan, chunghwa, Random(20260829))
        val second = CarrierCatalog.identityFor(taiwan, chunghwa, Random(20260829))

        assertEquals(first, second)
    }

    @Test
    fun `each carrier issues its own ICCIDs and numbers`() {
        val serials = taiwan.carriers.map { carrier ->
            CarrierCatalog.identityFor(taiwan, carrier, Random(7)).simSerial.take(7)
        }

        assertEquals(taiwan.carriers.size, serials.toSet().size)
        assertEquals(listOf("8988692", "8988697", "8988601"), serials)
    }

    @Test
    fun `a carrier is found together with the country it belongs to`() {
        val (country, carrier) = CarrierCatalog.carrierOf("tw-twm")!!

        assertEquals("tw", country.iso)
        assertEquals("466", country.mcc)
        assertEquals("97", carrier.mnc)
        assertEquals("Taiwan Mobile", carrier.operatorName)
    }

    @Test
    fun `an unknown key resolves to nothing rather than to the wrong carrier`() {
        assertNull(CarrierCatalog.carrierOf("tw-nonesuch"))
        assertNull(CarrierCatalog.countryOf("xx"))
        assertNotNull(CarrierCatalog.countryOf("TW"))
    }

    @Test
    fun `every carrier is described consistently`() {
        CarrierCatalog.countries.forEach { country ->
            assertTrue(country.iso == country.iso.lowercase())
            assertEquals(3, country.mcc.length)

            country.carriers.forEach { carrier ->
                assertTrue("${carrier.id} has an odd MNC", carrier.mnc.length in 2..3)
                assertTrue(carrier.numberPrefixes.isNotEmpty())
                assertTrue(
                    "${carrier.id} has a prefix as long as a whole number",
                    carrier.numberPrefixes.all { it.length < country.numberLength },
                )
            }
        }
    }
}
