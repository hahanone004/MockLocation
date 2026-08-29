package mock.location.xposed.cellar.info

import android.os.Parcel
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import mock.location.xposed.helpers.reflect.findField

/**
 * The generation an app is told the radio is running.
 *
 * A profile describes an LTE cell and nothing else, and the cell hooks already
 * answer every CellInfo query with that one cell. On a phone camped on 5G that
 * left the two halves disagreeing: the cell list said LTE while the network
 * type, and the display info the status bar is driven from, still said NR. An
 * app that reads both - and anything showing a network badge reads both - saw a
 * device claiming to be on 5G with no 5G cell anywhere in range, which is a
 * plainer tell than not spoofing at all.
 *
 * So the radio is reported as LTE, and the override that puts the "5G" badge on
 * top of it is cleared.
 */
class DisplayInfo {

    /**
     * [reported] copied through a parcel rather than edited.
     *
     * TelephonyRegistry keeps one TelephonyDisplayInfo and hands the same
     * instance to every listener, so editing it in place would leak one app's
     * spoof into the system's own copy and from there into unrelated apps.
     */
    fun asLte(reported: TelephonyDisplayInfo): TelephonyDisplayInfo {
        val copied = copyOf(reported)

        findField(copied.javaClass, true) { name == "mNetworkType" }
            .set(copied, TelephonyManager.NETWORK_TYPE_LTE)
        findField(copied.javaClass, true) { name == "mOverrideNetworkType" }
            .set(copied, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE)

        return copied
    }

    private fun copyOf(source: TelephonyDisplayInfo): TelephonyDisplayInfo {
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            TelephonyDisplayInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
