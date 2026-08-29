package fuck.location.app.ui.models

import kotlin.random.Random

/**
 * The networks a profile can claim to be on.
 *
 * A SIM's identity is eight fields that all have to agree with one another: the
 * operator numeric is the MCC and MNC glued together, the country ISO belongs to
 * the MCC, the ICCID encodes the country calling code and the issuer, and the
 * number sits in a range the carrier was actually allocated. Getting one of them
 * out of step is exactly the sort of thing that gives a spoof away, so the user
 * picks a country and a carrier and everything else is derived from that pair.
 *
 * Only Taiwan is described here. Adding a country is adding an entry to
 * [countries]; nothing else in the module knows the difference.
 */
object CarrierCatalog {

    /** One carrier within a country. */
    data class Carrier(
        /** Stable key stored in the profile, so a label can be reworded. */
        val id: String,
        /** Two or three digits, kept as a string because "01" is not 1. */
        val mnc: String,
        /** The alpha tag the network broadcasts, as Android reports it. */
        val operatorName: String,
        /** What the picker shows. */
        val label: String,
        /**
         * Number ranges the carrier was allocated. Portability means a real
         * number need not sit in its carrier's range any more, but staying in
         * range is the unremarkable choice.
         */
        val numberPrefixes: List<String>,
    )

    data class Country(
        /** Lowercase, as TelephonyManager reports it. */
        val iso: String,
        val mcc: String,
        /** Dialling code, which is also the ICCID's country field. */
        val callingCode: String,
        /** Total digits in a national mobile number, leading trunk zero included. */
        val numberLength: Int,
        val label: String,
        val carriers: List<Carrier>,
    )

    /** The two fields that are drawn rather than looked up. */
    data class SimIdentity(val phoneNumber: String, val simSerial: String)

    private val taiwan = Country(
        iso = "tw",
        mcc = "466",
        callingCode = "886",
        numberLength = 10,
        label = "台灣 Taiwan",
        carriers = listOf(
            Carrier(
                id = "tw-cht",
                mnc = "92",
                operatorName = "Chunghwa Telecom",
                label = "中華電信 Chunghwa Telecom",
                numberPrefixes = (910..919).map { "0$it" } + listOf("0972", "0978"),
            ),
            Carrier(
                id = "tw-twm",
                mnc = "97",
                operatorName = "Taiwan Mobile",
                label = "台灣大哥大 Taiwan Mobile",
                numberPrefixes = (920..929).map { "0$it" } + listOf("0955", "0958"),
            ),
            Carrier(
                id = "tw-fet",
                mnc = "01",
                operatorName = "FarEasTone",
                label = "遠傳電信 FarEasTone",
                numberPrefixes = (930..939).map { "0$it" } + listOf("0975", "0977"),
            ),
        ),
    )

    val countries: List<Country> = listOf(taiwan)

    fun countryOf(iso: String): Country? =
        countries.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    /** The carrier with this id, together with the country it belongs to. */
    fun carrierOf(carrierId: String): Pair<Country, Carrier>? =
        countries.firstNotNullOfOrNull { country ->
            country.carriers.firstOrNull { it.id == carrierId }?.let { country to it }
        }

    /**
     * A number and an ICCID that would not look out of place on a [carrier] SIM.
     *
     * The ICCID follows ITU-T E.118: 89 for telecommunications, the country
     * calling code, the issuer - the MNC, which is what most operators use -
     * then the account digits, then a Luhn check digit over the lot. A wrong
     * check digit is trivially detectable, and plenty of software checks it.
     */
    fun identityFor(
        country: Country,
        carrier: Carrier,
        random: Random = Random.Default,
    ): SimIdentity {
        val prefix = carrier.numberPrefixes.random(random)
        val number = buildString {
            append(prefix)
            while (length < country.numberLength) append(random.nextInt(10))
        }

        val body = buildString {
            append(ICCID_TELECOM_PREFIX)
            append(country.callingCode)
            append(carrier.mnc.padStart(2, '0'))
            while (length < ICCID_LENGTH - 1) append(random.nextInt(10))
        }

        return SimIdentity(number, body + luhnCheckDigit(body))
    }

    /** The digit that makes [digits] plus that digit pass a Luhn check. */
    internal fun luhnCheckDigit(digits: String): Int {
        var sum = 0

        // The check digit goes on the end, so counting from the right of the
        // finished number the last body digit is the first one to be doubled.
        digits.reversed().forEachIndexed { index, character ->
            var value = character - '0'
            if (index % 2 == 0) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }

        return (10 - sum % 10) % 10
    }

    /** Whether [digits] ends in a check digit that matches the rest of it. */
    internal fun luhnValid(digits: String): Boolean =
        digits.length > 1 && luhnCheckDigit(digits.dropLast(1)) == digits.last() - '0'

    private const val ICCID_TELECOM_PREFIX = "89"

    /** ICCIDs run to 19 or 20 digits; 19 is what most SIMs carry. */
    const val ICCID_LENGTH = 19
}
