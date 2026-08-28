package fuck.location.xposed.cellar.identity

import android.telephony.CellIdentityNr
import de.robv.android.xposed.XposedBridge
import fuck.location.xposed.helpers.ConfigGateway
import org.lsposed.hiddenapibypass.HiddenApiBypass

class Nr {
    @OptIn(ExperimentalStdlibApi::class)
    fun alterCellIdentity(cellIdentityNr: CellIdentityNr): CellIdentityNr {
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

        // One read: each call crosses a binder and re-parses the config file.
        val fakeLocation = ConfigGateway.get().readFakeLocation()

        val customResult = constructor.newInstance(
            fakeLocation.pci,
            fakeLocation.tac,
            fakeLocation.earfcn,
            cellIdentityNr.bands,
            cellIdentityNr.mccString,
            cellIdentityNr.mncString,
            fakeLocation.eci.toLong(),
            cellIdentityNr.operatorAlphaLong,
            cellIdentityNr.operatorAlphaShort,
            cellIdentityNr.additionalPlmns
        ) as CellIdentityNr

        XposedBridge.log("FL: [Cellar] Returning custom result: $customResult")

        return customResult
    }
}
