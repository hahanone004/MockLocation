package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.TelephonyManager
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.app.ui.models.Profile
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.helpers.reflect.Log
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.hookMethod
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports the radio as LTE, so a 5G phone reads as a 4G one.
 *
 * A profile describes an LTE cell, and the cell hooks answer every CellInfo
 * query with exactly that cell - which already means an app never sees a 5G
 * cell. The generation is reported separately though, and until this hook it
 * still came straight off the modem: an app on an NR phone was handed one LTE
 * cell and a network type of NR_SA in the same breath.
 *
 * These entry points are handed the calling package, so the substitution is per
 * app and the target does not have to be in the module's scope. It follows the
 * cell spoof rather than being a switch of its own: a profile that does not
 * describe a cell has nothing to be consistent with, and one that does can only
 * be describing LTE.
 *
 * What this does not reach is ServiceState - getServiceState() and the
 * NetworkRegistrationInfo list inside it still carry the real access network.
 * An app that looks there can still tell.
 */
class NetworkTypeHooker {

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookPhoneProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = lpparam.classLoader.loadClass(PHONE_INTERFACE_MANAGER)

        val methods = findAllMethods(clazz, findSuper = true) {
            name in NETWORK_TYPE_METHODS && returnType == Int::class.javaPrimitiveType
        }
        if (methods.isEmpty()) {
            throw NoSuchMethodException("no network type methods in ${clazz.name}")
        }

        var failure: Throwable? = null
        methods.filter { hookedMethods.add(it) }.forEach { method ->
            try {
                method.hookMethod {
                    after { param ->
                        if (param.hasThrowable()) return@after
                        val (packageName, _) = ConfigGateway.get()
                            .spoofedCaller(param, ::describedCell) ?: return@after
                        // Already 4G: leave the modem's own answer, which
                        // distinguishes LTE from LTE_CA where the ROM does.
                        if (param.result == TelephonyManager.NETWORK_TYPE_LTE) return@after

                        Log.i(
                            "[Cellar] ${method.name} for $packageName: " +
                                "${param.result} -> LTE"
                        )
                        param.result = TelephonyManager.NETWORK_TYPE_LTE
                    }
                }
            } catch (t: Throwable) {
                hookedMethods.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val PHONE_INTERFACE_MANAGER = "com.android.phone.PhoneInterfaceManager"

        /**
         * The three generations an app can ask about. The plain forms delegate
         * to the ForSubscriber ones and all of them name their caller in the
         * same place, so covering the lot costs nothing if that ever changes.
         */
        val NETWORK_TYPE_METHODS = setOf(
            "getNetworkTypeForSubscriber",
            "getDataNetworkTypeForSubscriber",
            "getVoiceNetworkTypeForSubscriber",
            "getNetworkType",
            "getDataNetworkType",
            "getVoiceNetworkType",
        )

        val hookedMethods = ConcurrentHashMap.newKeySet<Method>()

        @OptIn(ExperimentalStdlibApi::class)
        fun describedCell(packageName: String): Profile? =
            ConfigGateway.get().cellSpoofFor(packageName)?.takeIf { it.describesCell }
    }
}
