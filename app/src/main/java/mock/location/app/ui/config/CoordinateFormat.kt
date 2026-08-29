package mock.location.app.ui.config

import java.math.BigDecimal

/**
 * A coordinate as text, in the one notation the editor can read back.
 *
 * This used to go through NumberFormat.getNumberInstance(), which follows the
 * device locale, while the field was parsed with toDoubleOrNull, which only
 * ever accepts a point. On a Vietnamese or German phone a latitude was
 * therefore shown as "25,03" and every save fell back to the stored value, so
 * the coordinate could not be edited at all - silently, since a fallback looks
 * exactly like the user having changed nothing.
 *
 * Both halves are fixed here rather than only one: writing is locale-free so
 * what a field shows is always readable, and reading accepts either separator
 * because the numberDecimal keyboard still offers a comma to anyone whose
 * locale uses one.
 */
object CoordinateFormat {

    /**
     * The digits themselves - no locale, no grouping, and no exponent for the
     * small magnitudes a coordinate or a radius in metres has.
     */
    fun format(value: Double): String =
        if (!value.isFinite()) "0"
        else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    /**
     * Null when the text is not a number at all, so the caller can keep what it
     * had rather than storing a zero.
     *
     * A string carrying both separators has been grouped - "1.234,5" - and the
     * one that appears last is the decimal separator.
     */
    fun parse(text: String): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val lastPoint = trimmed.lastIndexOf('.')
        val lastComma = trimmed.lastIndexOf(',')
        val normalized = when {
            lastPoint >= 0 && lastComma >= 0 && lastComma > lastPoint ->
                trimmed.replace(".", "").replace(',', '.')
            lastPoint >= 0 && lastComma >= 0 -> trimmed.replace(",", "")
            lastComma >= 0 -> trimmed.replace(',', '.')
            else -> trimmed
        }

        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
