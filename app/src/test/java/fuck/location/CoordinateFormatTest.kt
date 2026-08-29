package fuck.location

import fuck.location.app.ui.config.CoordinateFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class CoordinateFormatTest {

    @Test
    fun `a coordinate is written with a point whatever the device locale is`() {
        val original = Locale.getDefault()
        try {
            listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("vi-VN")).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals(locale.toString(), "25.033", CoordinateFormat.format(25.033))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a whole number keeps no decimal tail and no exponent`() {
        assertEquals("0", CoordinateFormat.format(0.0))
        assertEquals("121", CoordinateFormat.format(121.0))
        assertEquals("-90", CoordinateFormat.format(-90.0))
        assertEquals("1000", CoordinateFormat.format(1000.0))
    }

    @Test
    fun `a non-finite value is written as zero rather than as NaN`() {
        assertEquals("0", CoordinateFormat.format(Double.NaN))
        assertEquals("0", CoordinateFormat.format(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `what was written comes back unchanged`() {
        listOf(25.0330, -121.56789, 0.0, 90.0, -179.999999, 0.000001).forEach { value ->
            assertEquals(value, CoordinateFormat.parse(CoordinateFormat.format(value)))
        }
    }

    /** The bug this class exists for: a comma keyboard used to lose the edit. */
    @Test
    fun `a comma is read as the decimal separator`() {
        assertEquals(25.033, CoordinateFormat.parse("25,033"))
        assertEquals(-121.5, CoordinateFormat.parse("-121,5"))
    }

    @Test
    fun `a point is read as the decimal separator`() {
        assertEquals(25.033, CoordinateFormat.parse("25.033"))
        assertEquals(-121.5, CoordinateFormat.parse("-121.5"))
    }

    @Test
    fun `a grouped number keeps whichever separator came last as the decimal one`() {
        assertEquals(1234.5, CoordinateFormat.parse("1.234,5"))
        assertEquals(1234.5, CoordinateFormat.parse("1,234.5"))
    }

    @Test
    fun `surrounding space does not make a coordinate unreadable`() {
        assertEquals(25.033, CoordinateFormat.parse("  25,033 "))
    }

    @Test
    fun `text that is not a number reads as nothing rather than as zero`() {
        listOf("", "   ", "north", "25,03,3", "-").forEach {
            assertNull(it, CoordinateFormat.parse(it))
        }
    }

    private fun assertEquals(expected: Double, actual: Double?) =
        assertEquals(expected, actual!!, 1e-9)
}
