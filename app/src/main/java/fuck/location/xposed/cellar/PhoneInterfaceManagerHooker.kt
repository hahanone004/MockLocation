package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.*
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.hookBefore
import fuck.location.xposed.helpers.reflect.hookMethod
import fuck.location.xposed.helpers.reflect.isPublic
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.xposed.cellar.identity.Lte
import fuck.location.xposed.cellar.identity.Nr
import fuck.location.xposed.helpers.ConfigGateway

class PhoneInterfaceManagerHooker {
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookCellLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz: Class<*> =
            lpparam.classLoader.loadClass("com.android.phone.PhoneInterfaceManager")

        XposedBridge.log("FL: [Cellar] Finding method in PhoneInterfaceManager")

        findAllMethods(clazz) {
            name == "getImeiForSlot" && isPublic
        }.hookMethod {
            after { param ->
                val packageName = param.args[1] as String
                val profile = ConfigGateway.get().cellSpoofFor(packageName) ?: return@after
                val customIMEI = profile.deviceImei

                param.result = customIMEI
                XposedBridge.log("FL: [Cellar] getImeiForSlot for $packageName -> $customIMEI")
            }
        }

        findAllMethods(clazz) {
            name == "getMeidForSlot" && isPublic
        }.hookMethod {
            after { param ->
                val packageName = param.args[1] as String
                val profile = ConfigGateway.get().cellSpoofFor(packageName) ?: return@after
                val customMEID = profile.deviceMeid

                param.result = customMEID
                XposedBridge.log("FL: [Cellar] getMeidForSlot for $packageName -> $customMEID")
            }
        }

        findAllMethods(clazz) {
            name == "getCellLocation" && isPublic
        }.hookMethod {
            after { param ->
                val packageName = param.args[0] as String
                val profile = ConfigGateway.get().cellSpoofFor(packageName) ?: return@after

                param.result = when (val reported = param.result) {
                    is CellIdentityNr -> Nr().alterCellIdentity(reported, profile)

                    // Anything else - a GSM or WCDMA identity, or none at all -
                    // is answered by building the profile's cell outright
                    // rather than by reporting no cell while claiming to be
                    // somewhere, which is the plainer tell of the two.
                    is CellIdentityLte -> Lte().cellIdentity(profile, reported)
                    else -> if (profile.describesCell) Lte().cellIdentity(profile) else null
                }

                XposedBridge.log("FL: [Cellar] getCellLocation for $packageName -> ${param.result}")
            }
        }

        findAllMethods(clazz) {
            name == "getAllCellInfo" && isPublic
        }.hookMethod {
            before { param ->
                val packageName = param.args[0] as String
                val profile = ConfigGateway.get().cellSpoofFor(packageName) ?: return@before

                // One cell, the configured one. An empty list used to be the
                // answer, which says the phone can see no towers at all - not
                // something that happens to a phone that is registered on a
                // network and knows where it is.
                val cells = ArrayList<CellInfo>()
                if (profile.describesCell) cells.add(fuck.location.xposed.cellar.info.Lte().cellInfo(profile))

                XposedBridge.log("FL: [Cellar] getAllCellInfo for $packageName -> ${cells.size} cell(s)")
                param.result = cells
            }
        }

        findAllMethods(clazz) {
            name == "getNeighboringCellInfo" && isPublic
        }.hookMethod {
            before { param ->
                val packageName = param.args[0] as String

                if (ConfigGateway.get().cellSpoofFor(packageName) != null) {
                    XposedBridge.log("FL: [Cellar] getNeighboringCellInfo for $packageName -> empty")
                    val customNeighboringCellInfo = ArrayList<NeighboringCellInfo>()
                    param.result = customNeighboringCellInfo
                }
            }
        }

        findAllMethods(clazz) {
            name == "requestCellInfoUpdateInternal" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[2] as String
            val profile = ConfigGateway.get().cellSpoofFor(packageName) ?: return@hookBefore

            val cells = ArrayList<CellInfo>()
            if (profile.describesCell) {
                cells.add(fuck.location.xposed.cellar.info.Lte().cellInfo(profile))
            }

            // This is an asynchronous API. Merely returning from the binder
            // method leaves the caller waiting forever; complete the callback
            // with the same custom list getAllCellInfo reports.
            val callback = param.args.firstOrNull { argument ->
                argument?.javaClass?.methods?.any {
                    it.name == "onCellInfo" && it.parameterCount == 1
                } == true
            } ?: throw IllegalArgumentException("no cell-info callback in ${param.method}")

            val onCellInfo = callback.javaClass.methods.first {
                it.name == "onCellInfo" && it.parameterCount == 1
            }
            onCellInfo.invoke(callback, cells)

            XposedBridge.log(
                "FL: [Cellar] requestCellInfoUpdate for $packageName -> ${cells.size} cell(s)"
            )
            param.result = null
        }
    }
}
