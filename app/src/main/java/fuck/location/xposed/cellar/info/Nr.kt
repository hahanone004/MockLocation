package fuck.location.xposed.cellar.info

import android.telephony.CellIdentityNr
import android.telephony.CellInfoNr
import fuck.location.xposed.helpers.reflect.findField
import fuck.location.xposed.cellar.identity.Nr

class Nr {
    fun constructNewCellInfoNr(existedCellInfoNr: CellInfoNr): CellInfoNr {
        val existedResultField = findField(existedCellInfoNr.javaClass) {
            name == "mCellIdentity"
        }
        val existedResult = existedResultField.get(existedCellInfoNr) as CellIdentityNr
        existedResultField.set(existedCellInfoNr, Nr().alterCellIdentity(existedResult))

        return existedCellInfoNr
    }
}