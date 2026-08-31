package mock.location.xposed.location

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import mock.location.app.ui.models.FakeAccessPoint
import mock.location.xposed.helpers.reflect.*
import mock.location.xposed.helpers.ConfigGateway
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class WLANHooker {
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookWifiManager(classLoader: ClassLoader) {
        val clazz: Class<*> = classLoader.loadClass("com.android.server.SystemServiceManager")

        // Vector may attach after WifiService was loaded. A local service binder
        // is implemented by a class from the Wi-Fi apex loader, so its loader
        // gives us a way back to WifiServiceImpl without waiting for an event
        // that has already happened.
        val serviceLoader = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getDeclaredMethod("getService", String::class.java)
                .invoke(null, "wifi")
            binder?.javaClass?.classLoader
        }.getOrNull()

        /*
         * Arm the future-start path first. A ROM-specific already-loaded class
         * may exist but have a different method shape; failing to hook that
         * candidate must not stop a later Wi-Fi apex loader from succeeding.
         *
         * This used to watch loadClassFromLoader, which is still in the AOSP
         * source but is a two-line private static helper, so a release build
         * inlines it away - it is gone on LineageOS 23, and with it the only
         * way to be told the service had started. Every boot then leaned on the
         * retry timer instead. startService(Class) is public, and every route
         * in ends at it: startService(String) and startServiceFromJar both load
         * the class and hand it straight over. Nothing can optimise away a
         * method the platform publishes.
         */
        val serviceStarts = findAllMethods(clazz, findSuper = true) {
            name == "startService" && parameterCount == 1 && !isAbstract &&
                parameterTypes[0] == Class::class.java
        }.ifEmpty {
            findAllMethods(clazz, findSuper = true) {
                name == "startServiceFromJar" && parameterCount == 2 && !isAbstract
            }
        }
        if (serviceStarts.isEmpty() && serviceLoader == null) {
            throw NoSuchMethodException(
                "neither startService(Class) nor startServiceFromJar in ${clazz.name}, " +
                    "and the wifi service has not started yet"
            )
        }
        installOnce(serviceStarts, hookedServiceStartMethods) { method ->
            method.hookMethod {
                after { param ->
                    if (param.hasThrowable()) return@after
                    // The class being started, whichever shape the call takes:
                    // startService is handed the Class, startServiceFromJar its
                    // name. Anything outside the Wi-Fi service is not ours, and
                    // checking the name first keeps this off the critical path
                    // of every other service the system starts.
                    val started = param.args.filterIsInstance<Class<*>>().firstOrNull()?.name
                        ?: param.args.filterIsInstance<String>().firstOrNull()
                    if (started?.startsWith(WIFI_SERVICE_PREFIX) != true) return@after

                    val loader = param.args.filterIsInstance<Class<*>>().firstOrNull()?.classLoader
                        ?: param.result?.javaClass?.classLoader
                        ?: run {
                            Log.w("[WiFi] $started started without a reachable ClassLoader")
                            return@after
                        }

                    // No line of its own here: the apex starts several wifi
                    // services that share this loader, so this runs once per
                    // service while there is only one set of hooks to install.
                    // tryHookWifiService says so exactly once, when it does it.
                    try {
                        tryHookWifiService(loader, "service start")
                    } catch (t: Throwable) {
                        Log.w("[WiFi] failed to hook wifi service: $t")
                        scheduleWifiRetry(loader, 1)
                    }
                }
            }
        }

        var hookedLoadedService = false
        var probeFailure: Throwable? = null
        sequenceOf(serviceLoader, Thread.currentThread().contextClassLoader, classLoader)
            .filterNotNull()
            .distinct()
            .forEach { loader ->
                try {
                    hookedLoadedService =
                        tryHookWifiService(loader, "already loaded") || hookedLoadedService
                } catch (t: Throwable) {
                    probeFailure = t
                    Log.w(
                        "[WiFi] failed already-loaded probe from $loader: $t"
                    )
                }
            }
        if (!hookedLoadedService) {
            probeFailure?.let { throw it }
            if (serviceStarts.isEmpty()) {
                throw ClassNotFoundException("WifiServiceImpl from available loaders")
            }
            // Not a failure: the service starts a moment after this runs, and
            // the hook above is waiting for exactly that.
            Log.i("[WiFi] waiting for the wifi service to start")
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun tryHookWifiService(loader: ClassLoader, source: String): Boolean {
        // The Wi-Fi apex starts several services - the scanner, p2p, aware,
        // rtt - and they all share the loader that carries WifiServiceImpl, so
        // the start hook fires once per service. There is only ever one set of
        // hooks to install.
        if (wifiServiceHooked) return true

        val wifiClazz = runCatching {
            loader.loadClass("com.android.server.wifi.WifiServiceImpl")
        }.getOrNull() ?: return false

        Log.i("[WiFi] hooking WifiServiceImpl from $source")
        var failure: Throwable? = null
        try {
            hookScanResults(wifiClazz)
        } catch (t: Throwable) {
            failure = t
            Log.w("[WiFi] failed to hook scan results: $t")
        }
        try {
            hookConnectionInfo(wifiClazz)
        } catch (t: Throwable) {
            failure = t
            Log.w("[WiFi] failed to hook connection info: $t")
        }
        failure?.let { throw it }
        wifiServiceHooked = true
        return true
    }

    private fun scheduleWifiRetry(loader: ClassLoader, attempt: Int) {
        if (attempt > MAX_WIFI_RETRIES) {
            Log.w("[WiFi] giving up after $MAX_WIFI_RETRIES retries")
            return
        }
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!tryHookWifiService(loader, "retry $attempt")) {
                        scheduleWifiRetry(loader, attempt + 1)
                    }
                } catch (t: Throwable) {
                    Log.w("[WiFi] retry $attempt failed: $t")
                    scheduleWifiRetry(loader, attempt + 1)
                }
            }, WIFI_RETRY_DELAY_MS)
        } catch (t: Throwable) {
            Log.w("[WiFi] cannot schedule retry: $t")
        }
    }

    @ExperimentalStdlibApi
    private fun hookScanResults(wifiClazz: Class<*>) {
        val methods = findAllMethods(wifiClazz, findSuper = true) {
            name == "getScanResults" && isPublic
        }
        if (methods.isEmpty()) throw NoSuchMethodException("getScanResults in ${wifiClazz.name}")
        installOnce(methods, hookedScanMethods) { method ->
            method.hookMethod {
                after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, profile) = spoofedWifiProfile(param) ?: return@after
                val originalResult = param.result
                // From this point on, never retain the real scan result if
                // construction differs on a vendor framework.
                param.result = null

                Log.d {
                    "[WiFi] in getScanResults for $packageName, reporting " +
                        "${profile.wifiAccessPoints.size} custom access point(s)"
                }

                val results = try {
                    profile.wifiAccessPoints.map { buildScanResult(it) }
                } catch (t: Throwable) {
                    Log.w("[WiFi] scan spoof failed, returning empty: $t")
                    emptyList()
                }

                // Android 13 changed the return type from List<ScanResult> to
                // ParceledListSlice<ScanResult>. Handing back a bare list makes
                // the binder reply blow up, so mirror whatever the real method
                // returned rather than assuming either shape.
                param.result = wrapLikeOriginal(
                    originalResult,
                    (param.method as Method).returnType,
                    results,
                )
                }
            }
        }
    }

    @ExperimentalStdlibApi
    private fun hookConnectionInfo(wifiClazz: Class<*>) {
        val methods = findAllMethods(wifiClazz, findSuper = true) {
            name == "getConnectionInfo" && isPublic
        }
        if (methods.isEmpty()) throw NoSuchMethodException("getConnectionInfo in ${wifiClazz.name}")
        installOnce(methods, hookedConnectionMethods) { method ->
            method.hookMethod {
                after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, profile) = spoofedWifiProfile(param) ?: return@after
                // Null is a valid fail-closed binder result if WifiInfo.Builder
                // is incompatible with this ROM; it must not fall back to the
                // original connection after the profile has matched.
                param.result = null

                // The connected AP is the first configured one; with none
                // configured there is nothing coherent to claim, so report the
                // same "not associated" state the framework uses - an empty
                // SSID, which is what makes WifiInfo.getSSID() answer with the
                // real WifiManager.UNKNOWN_SSID sentinel. Writing that
                // sentinel's own text into the SSID instead would hand callers
                // a quoted "<unknown ssid>", which reads as an association to a
                // network of that name.
                val connected = profile.wifiAccessPoints.firstOrNull()

                Log.d {
                    "[WiFi] in getConnectionInfo for $packageName, " +
                        "reporting ${connected?.ssid ?: "no association"}"
                }

                try {
                    val builder = WifiInfo.Builder()
                        .setSsid(connected?.ssid?.toByteArray() ?: ByteArray(0))
                        .setBssid(connected?.bssid ?: UNSPECIFIED_BSSID)
                        .setRssi(connected?.level ?: MIN_RSSI)
                        .setNetworkId(if (connected != null) 0 else -1)
                        .setCurrentSecurityType(securityTypeOf(connected?.capabilities))

                    val info = builder.build()
                    if (connected != null) applyFrequency(info, connected.frequency)
                    param.result = info
                } catch (t: Throwable) {
                    Log.w("[WiFi] connection spoof failed, returning null: $t")
                }
                }
            }
        }
    }

    /**
     * The frequency, which WifiInfo.Builder has no setter for.
     *
     * There never was one - not on any release - so the runtime lookup that
     * used to stand here failed on every single call and the connection was
     * reported at 0 MHz while the scan results for the very same BSSID named a
     * real channel. Two answers about one network that cannot both be true is
     * the kind of thing this module exists to avoid, so the field is written
     * directly, the way the cell hooks already write theirs.
     */
    private fun applyFrequency(info: WifiInfo, frequency: Int) {
        try {
            findField(info.javaClass, true) { name == "mFrequency" }.set(info, frequency)
        } catch (t: Throwable) {
            Log.w("[WiFi] cannot report the frequency: $t")
        }
    }

    /**
     * The security type WifiInfo reports, derived from the capabilities string
     * the same access point advertises in the scan results. Left unset, the two
     * disagreed: an AP announcing WPA2 whose connection claimed to know nothing
     * about its own security.
     */
    private fun securityTypeOf(capabilities: String?): Int = when {
        capabilities == null -> WifiInfo.SECURITY_TYPE_UNKNOWN
        capabilities.contains("SAE") -> WifiInfo.SECURITY_TYPE_SAE
        capabilities.contains("EAP") -> WifiInfo.SECURITY_TYPE_EAP
        capabilities.contains("PSK") -> WifiInfo.SECURITY_TYPE_PSK
        capabilities.contains("OWE") -> WifiInfo.SECURITY_TYPE_OWE
        capabilities.contains("WEP") -> WifiInfo.SECURITY_TYPE_WEP
        else -> WifiInfo.SECURITY_TYPE_OPEN
    }

    /**
     * Builds a ScanResult the way the framework does. SSID lives in two fields
     * that are parcelled separately - the deprecated String and the WifiSsid
     * that getWifiSsid() returns - and setWifiSsid keeps both in step, so go
     * through it rather than writing the String on its own.
     */
    private fun buildScanResult(accessPoint: FakeAccessPoint): ScanResult {
        val scanResult = ScanResult()

        try {
            val wifiSsid = Class.forName("android.net.wifi.WifiSsid")
                .getMethod("fromUtf8Text", CharSequence::class.java)
                .invoke(null, accessPoint.ssid)

            ScanResult::class.java
                .getMethod("setWifiSsid", Class.forName("android.net.wifi.WifiSsid"))
                .invoke(scanResult, wifiSsid)
        } catch (e: Exception) {
            // Older or trimmed-down builds: the deprecated field alone still
            // satisfies everything that reads ScanResult.SSID.
            Log.w("[WiFi] setWifiSsid unavailable, setting SSID directly: $e")
            scanResult.SSID = accessPoint.ssid
        }

        scanResult.BSSID = accessPoint.bssid
        scanResult.capabilities = accessPoint.capabilities
        scanResult.level = accessPoint.level
        scanResult.frequency = accessPoint.frequency
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000

        return scanResult
    }

    /**
     * Returns [results] in the same container the hooked method produced. When
     * the original returned null there is nothing to copy the shape from, so
     * fall back to ParceledListSlice, which is what current builds use.
     */
    private fun wrapLikeOriginal(
        original: Any?,
        returnType: Class<*>,
        results: List<ScanResult>,
    ): Any? {
        if (original is List<*> || List::class.java.isAssignableFrom(returnType)) return results

        return try {
            val container = original?.javaClass ?: returnType
            container
                .getConstructor(List::class.java)
                .newInstance(results)
        } catch (e: Exception) {
            Log.w("[WiFi] cannot construct scan result container: $e")
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun spoofedWifiProfile(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
    ): Pair<String, mock.location.app.ui.models.Profile>? =
        ConfigGateway.get().spoofedCaller(param) { ConfigGateway.get().wifiSpoofFor(it) }

    private fun installOnce(
        methods: List<Method>,
        installed: MutableSet<Method>,
        install: (Method) -> Unit,
    ) {
        var failure: Throwable? = null
        methods.filter { installed.add(it) }.forEach { method ->
            try {
                install(method)
            } catch (t: Throwable) {
                installed.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val UNSPECIFIED_BSSID = "02:00:00:00:00:00"
        const val MIN_RSSI = -127
        const val WIFI_SERVICE_PREFIX = "com.android.server.wifi."
        @Volatile var wifiServiceHooked = false
        val hookedServiceStartMethods = ConcurrentHashMap.newKeySet<Method>()
        val hookedScanMethods = ConcurrentHashMap.newKeySet<Method>()
        val hookedConnectionMethods = ConcurrentHashMap.newKeySet<Method>()
        const val MAX_WIFI_RETRIES = 6
        const val WIFI_RETRY_DELAY_MS = 5_000L
    }
}
