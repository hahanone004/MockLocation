package mock.location.xposed.cellar.info

import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.os.SystemClock
import android.telephony.CellSignalStrengthLte
import android.os.Parcel
import mock.location.app.ui.models.Profile
import mock.location.xposed.helpers.reflect.findField
import mock.location.xposed.cellar.identity.Lte
import org.lsposed.hiddenapibypass.HiddenApiBypass

class Lte {
    /**
     * The profile's cell as a CellInfoLte.
     *
     * With a [reported] cell in hand only the identity is replaced, so the
     * modem's own signal strength and timestamps carry through. Without one the
     * whole thing is built, since returning no cells at all while reporting a
     * position is worse than reporting one.
     */
    @ExperimentalStdlibApi
    fun cellInfo(profile: Profile, reported: CellInfoLte? = null): CellInfoLte {
        if (reported != null) {
            // TelephonyRegistry keeps and reuses the modem's CellInfo objects
            // for every listener. Mutating the supplied instance would leak one
            // app's spoof into the system cache and then into unrelated apps.
            val copied = copyOf(reported)
            val identityField = findField(copied.javaClass) { name == "mCellIdentityLte" }
            val existing = identityField.get(copied) as CellIdentityLte

            identityField.set(copied, Lte().cellIdentity(profile, existing))
            return copied
        }

        val built = HiddenApiBypass.newInstance(CellInfoLte::class.java) as CellInfoLte

        findField(built.javaClass) { name == "mCellIdentityLte" }
            .set(built, Lte().cellIdentity(profile))
        findField(built.javaClass) { name == "mCellSignalStrengthLte" }
            .set(built, signalStrength())

        // Registered and serving, which is the only state consistent with the
        // rest of what this profile claims.
        findField(built.javaClass, true) { name == "mRegistered" }.set(built, true)
        findField(built.javaClass, true) { name == "mCellConnectionStatus" }
            .set(built, CellInfo.CONNECTION_PRIMARY_SERVING)
        // Nanoseconds since boot, which is the clock the modem's own cells
        // are stamped with - getTimestampMillis just divides this by a million.
        findField(built.javaClass, true) { name == "mTimeStamp" }
            .set(built, SystemClock.elapsedRealtimeNanos())

        return built
    }

    /**
     * A good but unremarkable LTE signal. The profile does not describe signal
     * strength, and reporting it as unknown - which is what the empty
     * constructor does - would stand out next to a cell that is otherwise fully
     * specified.
     */
    private fun signalStrength(): CellSignalStrengthLte =
        HiddenApiBypass.getDeclaredConstructor(
            CellSignalStrengthLte::class.java,
            Int::class.java,    // rssi
            Int::class.java,    // rsrp
            Int::class.java,    // rsrq
            Int::class.java,    // rssnr
            Int::class.java,    // cqiTableIndex
            Int::class.java,    // cqi
            Int::class.java,    // timingAdvance
        ).newInstance(
            -85, -95, -10, 10,
            CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE, CellInfo.UNAVAILABLE,
        ) as CellSignalStrengthLte

    private fun copyOf(source: CellInfoLte): CellInfoLte {
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            CellInfoLte.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
