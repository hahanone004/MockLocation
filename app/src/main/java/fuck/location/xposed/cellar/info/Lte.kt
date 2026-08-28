package fuck.location.xposed.cellar.info

import android.telephony.CellIdentityLte
import android.telephony.CellInfoLte
import fuck.location.xposed.helpers.reflect.findField
import fuck.location.xposed.cellar.identity.Lte

class Lte {
    @ExperimentalStdlibApi
    fun constructNewCellInfoLte(existedCellInfoLte: CellInfoLte): CellInfoLte {
        val existedResultField = findField(existedCellInfoLte.javaClass) {
            name == "mCellIdentityLte"
        }
        val existedResult = existedResultField.get(existedCellInfoLte) as CellIdentityLte

        existedResultField.set(existedCellInfoLte, Lte().alterCellIdentity(existedResult))
        return existedCellInfoLte
    }
}