package fuck.location.xposed.cellar.info

import android.telephony.CellIdentityNr
import android.telephony.CellInfoNr
import android.os.Parcel
import fuck.location.xposed.helpers.reflect.findField
import fuck.location.app.ui.models.Profile
import fuck.location.xposed.cellar.identity.Nr

class Nr {
    fun constructNewCellInfoNr(existedCellInfoNr: CellInfoNr, profile: Profile): CellInfoNr {
        // The reported instance may be TelephonyRegistry's shared cache. Work
        // on a parcel copy so another listener can never observe this profile.
        val copied = copyOf(existedCellInfoNr)
        val existedResultField = findField(copied.javaClass) {
            name == "mCellIdentity"
        }
        val existedResult = existedResultField.get(copied) as CellIdentityNr
        existedResultField.set(copied, Nr().alterCellIdentity(existedResult, profile))

        return copied
    }

    private fun copyOf(source: CellInfoNr): CellInfoNr {
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            CellInfoNr.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
