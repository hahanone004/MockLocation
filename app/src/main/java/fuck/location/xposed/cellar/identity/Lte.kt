package fuck.location.xposed.cellar.identity

import android.telephony.CellIdentityLte
import android.telephony.ClosedSubscriberGroupInfo
import de.robv.android.xposed.XposedBridge
import fuck.location.app.ui.models.Profile
import org.lsposed.hiddenapibypass.HiddenApiBypass

class Lte {
    @ExperimentalStdlibApi
    fun alterCellIdentity(cellIdentityLte: CellIdentityLte, profile: Profile): CellIdentityLte {
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

        // A blank MCC or MNC means the profile does not care which network
        // this claims to be on, so keep whatever the modem reported.
        val mcc = profile.mcc.ifBlank { cellIdentityLte.mccString }
        val mnc = profile.mnc.ifBlank { cellIdentityLte.mncString }

        val customResult = constructor.newInstance(
            profile.eci,
            profile.pci,
            profile.tac,
            profile.earfcn,
            cellIdentityLte.bands,
            profile.bandwidth,
            mcc,
            mnc,
            cellIdentityLte.operatorAlphaLong,
            cellIdentityLte.operatorAlphaShort,
            cellIdentityLte.additionalPlmns,
            cellIdentityLte.closedSubscriberGroupInfo
        ) as CellIdentityLte

        XposedBridge.log("FL: [Cellar] Returning custom result: $customResult")

        return customResult
    }
}
