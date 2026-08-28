package fuck.location.xposed.cellar.identity

import android.telephony.CellIdentityLte
import android.telephony.ClosedSubscriberGroupInfo
import de.robv.android.xposed.XposedBridge
import fuck.location.xposed.helpers.ConfigGateway
import org.lsposed.hiddenapibypass.HiddenApiBypass

class Lte {
    @ExperimentalStdlibApi
    fun alterCellIdentity(cellIdentityLte: CellIdentityLte): CellIdentityLte {
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

        // One read: each call crosses a binder and re-parses the config file.
        val fakeLocation = ConfigGateway.get().readFakeLocation()

        val customResult = constructor.newInstance(
            fakeLocation.eci,
            fakeLocation.pci,
            fakeLocation.tac,
            fakeLocation.earfcn,
            cellIdentityLte.bands,
            fakeLocation.bandwidth,
            cellIdentityLte.mccString,
            cellIdentityLte.mncString,
            cellIdentityLte.operatorAlphaLong,
            cellIdentityLte.operatorAlphaShort,
            cellIdentityLte.additionalPlmns,
            cellIdentityLte.closedSubscriberGroupInfo
        ) as CellIdentityLte

        XposedBridge.log("FL: [Cellar] Returning custom result: $customResult")

        return customResult
    }
}
