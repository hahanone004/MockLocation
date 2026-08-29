package fuck.location.xposed.location.gnss

import android.annotation.SuppressLint
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.hookBefore
import fuck.location.xposed.helpers.reflect.isPublic
import de.robv.android.xposed.XposedBridge
import fuck.location.xposed.helpers.ConfigGateway

class GnssHooker {
    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("PrivateApi")
    fun hookGnssCallbacks(classLoader: ClassLoader) {
        val clazz =
            classLoader.loadClass("com.android.server.location.gnss.GnssManagerService")

        findAllMethods(clazz) {
            name == "registerGnssStatusCallback" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[1] as String

            if (ConfigGateway.get().locationSpoofFor(packageName) != null) {
                XposedBridge.log("FL: dropping a GNSS registration from $packageName")
                param.result = null
                return@hookBefore
            }
        }

        findAllMethods(clazz) {
            name == "registerGnssNmeaCallback" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[1] as String

            if (ConfigGateway.get().locationSpoofFor(packageName) != null) {
                XposedBridge.log("FL: dropping a GNSS registration from $packageName")
                param.result = null
                return@hookBefore
            }
        }

        findAllMethods(clazz) {
            name == "addGnssMeasurementsListener" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[2] as String

            if (ConfigGateway.get().locationSpoofFor(packageName) != null) {
                XposedBridge.log("FL: dropping a GNSS registration from $packageName")
                param.result = null
                return@hookBefore
            }
        }

        findAllMethods(clazz) {
            name == "addGnssNavigationMessageListener" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[1] as String

            if (ConfigGateway.get().locationSpoofFor(packageName) != null) {
                XposedBridge.log("FL: dropping a GNSS registration from $packageName")
                param.result = null
                return@hookBefore
            }
        }

        findAllMethods(clazz) {
            name == "addGnssAntennaInfoListener" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[1] as String

            if (ConfigGateway.get().locationSpoofFor(packageName) != null) {
                XposedBridge.log("FL: dropping a GNSS registration from $packageName")
                param.result = null
                return@hookBefore
            }
        }
    }
}