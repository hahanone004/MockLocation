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
import fuck.location.xposed.cellar.SimIdentityHooker
import fuck.location.xposed.cellar.TelephonyRegistryHooker
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.location.LocationHooker
import fuck.location.xposed.location.WLANHooker
import fuck.location.xposed.location.gnss.GnssHooker

@ExperimentalStdlibApi
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        Log.i("loaded, initZygote running")

        ConfigGateway.get().setDataPath()

        /*
         * Not every framework dispatches handleLoadPackage for system_server.
         * On one that does not, waiting for the "android" package meant every
         * hook below - the config channel, location, Wi-Fi, the telephony
         * registry - was silently never installed, while the hooks in ordinary
         * app processes went on working, which looks exactly like the module
         * being scoped wrongly. So claim system_server here instead, and treat
         * handleLoadPackage as the second of two ways in rather than the only
         * one.
         */
        step("system_server (early)") {
            val loader = systemServerClassLoader()

            if (loader != null) {
                hookSystemServer(loader)
            } else {
                // A real zygote cannot see the system server's classes yet.
                // systemMain is where it becomes one, and hooks survive the
                // fork, so arm it and come back.
                Log.i("waiting for ActivityThread.systemMain")

                findAllMethods(Class.forName("android.app.ActivityThread")) {
                    name == "systemMain" && isStatic
                }.hookAfter {
                    step("system_server (systemMain)") {
                        hookSystemServer(
                            systemServerClassLoader()
                                ?: throw ClassNotFoundException(
                                    "no class loader in systemMain can see $SYSTEM_SERVER_PROBE"
                                )
                        )
                    }
                }
            }
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

            "android" -> hookSystemServer(lpparam.classLoader)

            "com.android.phone" -> {
                step("phone interface manager") {
                    PhoneInterfaceManagerHooker().hookCellLocation(lpparam)
                }
                step("sim identity (phone)") {
                    SimIdentityHooker().hookPhoneProcess(lpparam)
                }
            }

            else -> {
                // Everything else the module is scoped to is an ordinary app.
                // Only the SIM identity needs hooking there, and only because
                // TelephonyManager answers most of it without leaving the
                // process. The hooks check the profile when they fire, so
                // installing them costs an app nothing until it is configured.
                step("sim identity") {
                    SimIdentityHooker().hookTelephonyManager(lpparam)
                }
            }
        }
    }

    /**
     * Everything that lives in system_server. Reachable two ways, so it is
     * guarded: whichever arrives first does the work.
     */
    @Synchronized
    private fun hookSystemServer(classLoader: ClassLoader) {
        if (systemServerHooked) return
        systemServerHooked = true

        Log.tag = "FuckLocation"
        Log.i("hooking system_server, API ${Build.VERSION.SDK_INT}")

        step("config gateway (write)") { ConfigGateway.get().hookWillChangeBeEnabled(classLoader) }
        step("config gateway (read)") { ConfigGateway.get().hookGetTagForIntentSender(classLoader) }
        step("telephony registry") { TelephonyRegistryHooker().hookListen(classLoader) }

        step("last location") { LocationHooker().hookLastLocation(classLoader) }
        step("location DLC") { LocationHooker().hookDLC(classLoader) }
        step("gnss") { GnssHooker().hookGnssCallbacks(classLoader) }

        step("wifi") { WLANHooker().hookWifiManager(classLoader) }
    }

    /**
     * The loader that can see the system server's own classes, or null when
     * this process has none - which is how a real zygote is told apart from
     * system_server before either has said so.
     */
    private fun systemServerClassLoader(): ClassLoader? = sequenceOf(
        ClassLoader.getSystemClassLoader(),
        Thread.currentThread().contextClassLoader,
        HookEntry::class.java.classLoader,
    ).filterNotNull().firstOrNull { loader ->
        runCatching { loader.loadClass(SYSTEM_SERVER_PROBE) }.isSuccess
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
            // Both sinks: which step failed is the first thing anyone needs.
            Log.e("hook step '$name' failed", t)
        }
    }

    private companion object {
        /**
         * Only loadable where the system server's classpath is, and it is also
         * the first class the module hooks there.
         */
        const val SYSTEM_SERVER_PROBE = "com.android.server.am.ActivityManagerService"

        @Volatile
        var systemServerHooked = false
    }
}
