package fuck.location.xposed.cellar.identity

import android.telephony.CellIdentityLte
import android.telephony.ClosedSubscriberGroupInfo
import fuck.location.app.ui.models.Profile
import org.lsposed.hiddenapibypass.HiddenApiBypass

class Lte {
    /**
     * The profile's cell as a CellIdentityLte.
     *
     * [reported] is whatever the modem actually said, and is only consulted for
     * the handful of fields a profile does not describe. It is allowed to be
     * absent: the caller may have been handed a GSM or WCDMA identity, or none
     * at all, and a profile that describes a cell can still answer. Before this
     * could be built from nothing, any of those simply returned null - so a
     * phone that happened not to be camped on LTE reported no cell whatsoever
     * while claiming a position, which is a plainer tell than the real cell.
     *
     * The operator name comes from the profile too where it has one. Carrying
     * the real network's name across while substituting its MCC and MNC would
     * have the cell announce "Vodafone" as 466-92.
     */
    @ExperimentalStdlibApi
    fun cellIdentity(profile: Profile, reported: CellIdentityLte? = null): CellIdentityLte {
        val constructor = HiddenApiBypass.getDeclaredConstructor(
            CellIdentityLte::class.java,
            Int::class.java,    // ci
            Int::class.java,    // pci
            Int::class.java,    // tac
            Int::class.java,    // earfcn
            IntArray::class.java,  // bands
            Int::class.java,    // bandwidth
            String::class.java, // mccStr
            String::class.java, // mncStr
            String::class.java, // alphal
            String::class.java, // alphas
            Collection::class.java, // additionalPlmns
            ClosedSubscriberGroupInfo::class.java,  // csgInfo
        )

        // A blank MCC or MNC means the profile does not care which network this
        // claims to be on, so keep whatever the modem reported.
        val mcc = profile.mcc.ifBlank { reported?.mccString }
        val mnc = profile.mnc.ifBlank { reported?.mncString }
        val operator = profile.simOperatorName.ifBlank {
            reported?.operatorAlphaLong?.toString()
        }

        return constructor.newInstance(
            profile.eci,
            profile.pci,
            profile.tac,
            profile.earfcn,
            reported?.bands ?: IntArray(0),
            profile.bandwidth,
            mcc,
            mnc,
            operator,
            operator,
            reported?.additionalPlmns ?: emptySet<String>(),
            reported?.closedSubscriberGroupInfo,
        ) as CellIdentityLte
    }
}
