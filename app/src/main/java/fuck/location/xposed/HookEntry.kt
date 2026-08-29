package fuck.location.xposed

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import fuck.location.xposed.helpers.reflect.*
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.BuildConfig

import fuck.location.xposed.cellar.NetworkTypeHooker
import fuck.location.xposed.cellar.PhoneInterfaceManagerHooker
import fuck.location.xposed.cellar.SimIdentityHooker
import fuck.location.xposed.cellar.TelephonyRegistryHooker
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.location.LocationHooker
import fuck.location.xposed.location.WLANHooker
import fuck.location.xposed.location.gnss.GnssHooker
import fuck.location.xposed.system.LocaleHooker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

                val systemMain = findAllMethods(Class.forName("android.app.ActivityThread")) {
                    name == "systemMain" && isStatic
                }
                if (systemMain.isEmpty()) {
                    throw NoSuchMethodException("ActivityThread.systemMain")
                }
                systemMain.hookAfter {
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
                Log.i("Try to hook the module")
                val clazz = lpparam.classLoader.loadClass("fuck.location.app.ui.activities.MainActivity")

                val activationMethods = findAllMethods(clazz, findSuper = true) {
                    name == "isModuleActivated" && isPublic
                }
                if (activationMethods.isEmpty()) {
                    throw NoSuchMethodException("isModuleActivated in ${clazz.name}")
                }
                activationMethods.hookMethod {
                    after { param ->
                        Log.i("Unlock the module")
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
                step("network type") {
                    NetworkTypeHooker().hookPhoneProcess(lpparam)
                }
            }

            else -> {
                // Everything else the module is scoped to is an ordinary app.
                // Only the SIM identity needs hooking there, and only because
                // TelephonyManager answers most of it without leaving the
                // process. The hooks check the profile when they fire, so
                // installing them costs an app nothing until it is configured.
                if (!ownsProcess(lpparam)) return

                step("sim identity") {
                    SimIdentityHooker().hookTelephonyManager(lpparam)
                }
                step("system language") {
                    LocaleHooker().hookLocale(lpparam)
                }
            }
        }
    }

    /**
     * Whether this load is the app the process belongs to.
     *
     * handleLoadPackage fires for every package loaded into a process, not just
     * its owner: WebView arrives this way inside whichever app is showing one.
     * Hooking those installed a second copy of the same hooks on the very same
     * bootclasspath methods - the classes come from the boot loader either way -
     * and then asked the config channel about a package the caller does not own,
     * which it rightly refused, once per query, in the log.
     *
     * A framework that does not fill the process name in gets the old
     * behaviour rather than no hooks at all.
     */
    private fun ownsProcess(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val process = lpparam.processName
        if (process.isNullOrEmpty()) return true
        if (process.substringBefore(':') == lpparam.packageName) return true

        Log.i("skipping ${lpparam.packageName} loaded into $process")
        return false
    }

    /**
     * Everything that lives in system_server. Reachable two ways, so it is
     * guarded: whichever arrives first does the work.
     */
    private fun hookSystemServer(classLoader: ClassLoader) {
        synchronized(systemServerLock) {
            if (systemServerHooked) return

            Log.tag = "FuckLocation"
            Log.i("hooking system_server, API ${Build.VERSION.SDK_INT}")

            val complete = listOf(
                installSystemStep("config directory") { ConfigGateway.get().requireDataPath() },
                installSystemStep("config gateway (write)") {
                    ConfigGateway.get().hookWillChangeBeEnabled(classLoader)
                },
                installSystemStep("config gateway (read)") {
                    ConfigGateway.get().hookGetTagForIntentSender(classLoader)
                },
                installSystemStep("telephony registry") {
                    TelephonyRegistryHooker().hookListen(classLoader)
                },
                installSystemStep("last location") {
                    LocationHooker().hookLastLocation(classLoader)
                },
                installSystemStep("location DLC") {
                    LocationHooker().hookDLC(classLoader)
                },
                // Its own step: geofencing lives in a class that has moved
                // between releases, and losing it used to fail getLastLocation's
                // step too - which had already installed - so the retry loop ran
                // to exhaustion reporting a failure that was half untrue.
                installSystemStep("geofences") {
                    LocationHooker().hookGeofences(classLoader)
                },
                installSystemStep("gnss") { GnssHooker().hookGnssCallbacks(classLoader) },
                installSystemStep("wifi") { WLANHooker().hookWifiManager(classLoader) },
            ).all { it }

            systemServerHooked = complete
            if (!complete) {
                Log.w("system_server hooks incomplete; scheduling a retry")
                scheduleSystemServerRetry(classLoader)
            }
        }
    }

    private fun scheduleSystemServerRetry(classLoader: ClassLoader) {
        if (systemServerRetryCount.get() >= MAX_SYSTEM_SERVER_RETRIES) {
            Log.e(
                "giving up after $MAX_SYSTEM_SERVER_RETRIES retries; still not installed: " +
                    outstandingSystemSteps.sorted().joinToString()
            )
            return
        }
        if (!systemServerRetryScheduled.compareAndSet(false, true)) return

        try {
            Handler(Looper.getMainLooper()).postDelayed({
                systemServerRetryScheduled.set(false)
                systemServerRetryCount.incrementAndGet()
                hookSystemServer(classLoader)
            }, SYSTEM_SERVER_RETRY_DELAY_MS)
        } catch (t: Throwable) {
            systemServerRetryScheduled.set(false)
            Log.e("cannot schedule system_server hook retry", t)
        }
    }

    /**
     * A step is only really lost once the retries are spent.
     *
     * Some of these cannot succeed on the first pass by construction - the
     * Wi-Fi service starts a few seconds after the module installs its hooks -
     * so a failure here is usually just "not yet". Logging every attempt at
     * error level made an ordinary boot look broken; the give-up in
     * [scheduleSystemServerRetry] is the line that means something.
     */
    private inline fun installSystemStep(name: String, action: () -> Unit): Boolean {
        if (installedSystemSteps.contains(name)) return true
        return try {
            action()
            installedSystemSteps.add(name)
            outstandingSystemSteps.remove(name)
            true
        } catch (t: Throwable) {
            outstandingSystemSteps.add(name)
            Log.w("hook step '$name' not installed yet: $t")
            false
        }
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

        val systemServerLock = Any()
        val installedSystemSteps = ConcurrentHashMap.newKeySet<String>()
        /** Steps that have failed at least once and have not since succeeded. */
        val outstandingSystemSteps = ConcurrentHashMap.newKeySet<String>()
        @Volatile var systemServerHooked = false
        val systemServerRetryScheduled = AtomicBoolean(false)
        val systemServerRetryCount = AtomicInteger(0)
        const val MAX_SYSTEM_SERVER_RETRIES = 6
        const val SYSTEM_SERVER_RETRY_DELAY_MS = 5_000L
    }
}
