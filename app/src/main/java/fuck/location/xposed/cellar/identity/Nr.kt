package fuck.location.xposed.cellar.identity

import android.telephony.CellIdentityNr
import de.robv.android.xposed.XposedBridge
import fuck.location.app.ui.models.Profile
import org.lsposed.hiddenapibypass.HiddenApiBypass

class Nr {
    @OptIn(ExperimentalStdlibApi::class)
    fun alterCellIdentity(cellIdentityNr: CellIdentityNr, profile: Profile): CellIdentityNr {
        val constructor = HiddenApiBypass.getDeclaredConstructor(
            CellIdentityNr::class.java,
            Int::class.java,    // pci
            Int::class.java,    // tac
            Int::class.java,    // nrArfcn
            IntArray::class.java,  // bands
            String::class.java, // mccStr
            String::class.java, // mncStr
            Long::class.java,   // nci
            String::class.java, // alphal
            String::class.java, // alphas
            Collection::class.java, // additionalPlmns
        )

        val mcc = profile.mcc.ifBlank { cellIdentityNr.mccString }
        val mnc = profile.mnc.ifBlank { cellIdentityNr.mncString }
        val operator = profile.simOperatorName.ifBlank {
            cellIdentityNr.operatorAlphaLong?.toString()
        }

        val customResult = constructor.newInstance(
            profile.pci,
            profile.tac,
            profile.earfcn,
            cellIdentityNr.bands,
            mcc,
            mnc,
            profile.eci.toLong(),
            operator,
            operator,
            if (profile.mcc.isNotBlank() && profile.mnc.isNotBlank()) {
                emptySet<String>()
            } else {
                cellIdentityNr.additionalPlmns
            }
        ) as CellIdentityNr

        XposedBridge.log("FL: [Cellar] Returning custom result: $customResult")

        return customResult
    }
}
