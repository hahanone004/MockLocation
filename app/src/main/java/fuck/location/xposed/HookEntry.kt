package fuck.location.xposed

import android.annotation.SuppressLint
import android.os.Build
import fuck.location.xposed.helpers.reflect.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.BuildConfig

import fuck.location.xposed.cellar.PhoneInterfaceManagerHooker
import fuck.location.xposed.cellar.TelephonyRegistryHooker
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.helpers.workround.Oplus
import fuck.location.xposed.location.LocationHooker
import fuck.location.xposed.location.WLANHooker
import fuck.location.xposed.location.gnss.GnssHooker
import fuck.location.xposed.location.oplus.NlpDLCS

@ExperimentalStdlibApi
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("FL: in initZygote!")

        ConfigGateway.get().setDataPath()
    }

    /**
     * Installs one hook in isolation. A missing class or renamed field on some
     * ROM used to abort every remaining hook in the same try block, so a single
     * incompatibility silently disabled the whole module.
     */
    private inline fun step(name: String, action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            XposedBridge.log("FL: hook step '$name' failed: $t")
        }
    }

    @SuppressLint("PrivateApi")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam == null) return

        when (lpparam.packageName) {
            BuildConfig.APPLICATION_ID -> {
                XposedBridge.log("FL: Try to hook the module")
                val clazz = lpparam.classLoader.loadClass("fuck.location.app.ui.activities.MainActivity")

                findAllMethods(clazz) {
                    name == "isModuleActivated" && isPublic
                }.hookMethod {
                    after { param ->
                        XposedBridge.log("FL: Unlock the module")
                        param.result = true
                    }
                }
            }

            "android" -> {
                Log.tag = "FuckLocation"
                XposedBridge.log("FL: hooking system_server, API ${Build.VERSION.SDK_INT}")

                step("config gateway (write)") { ConfigGateway.get().hookWillChangeBeEnabled(lpparam) }
                step("config gateway (read)") { ConfigGateway.get().hookGetTagForIntentSender(lpparam) }
                step("telephony registry") { TelephonyRegistryHooker().hookListen(lpparam) }

                if (Oplus().isOplus()) {
                    step("oplus nlp") { NlpDLCS().hookColorOS(lpparam) }
                }

                step("last location") { LocationHooker().hookLastLocation(lpparam) }
                step("location DLC") { LocationHooker().hookDLC(lpparam) }
                step("gnss") { GnssHooker().hookGnssCallbacks(lpparam) }

                step("wifi") { WLANHooker().hookWifiManager(lpparam) }
            }

            "com.android.phone" -> {
                step("phone interface manager") {
                    PhoneInterfaceManagerHooker().hookCellLocation(lpparam)
                }
            }
        }
    }
}
