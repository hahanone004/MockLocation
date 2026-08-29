package fuck.location.xposed.location

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import fuck.location.app.ui.models.FakeAccessPoint
import fuck.location.xposed.helpers.reflect.*
import de.robv.android.xposed.XposedBridge
import fuck.location.xposed.helpers.ConfigGateway
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

        // Arm the future-load path first. A ROM-specific already-loaded class
        // may exist but have a different method shape; failure to hook that
        // candidate must not prevent a later Wi-Fi apex loader from succeeding.
        val loadWatchers = findAllMethods(clazz, findSuper = true) {
            name == "loadClassFromLoader" && isPrivate && isStatic
        }
        if (loadWatchers.isEmpty() && serviceLoader == null) {
            throw NoSuchMethodException("loadClassFromLoader in ${clazz.name}")
        }
        installOnce(loadWatchers, hookedLoadWatcherMethods) { method ->
            method.hookMethod {
                after { param ->
                    if (param.args[0] != "com.android.server.wifi.WifiService") return@after

                    XposedBridge.log("FL: [WiFi] wifi service loaded, hooking it")
                    val loader = param.args.getOrNull(1) as? ClassLoader ?: run {
                        XposedBridge.log("FL: [WiFi] service load did not provide a ClassLoader")
                        return@after
                    }
                    try {
                        tryHookWifiService(loader, "service load")
                    } catch (t: Throwable) {
                        XposedBridge.log("FL: [WiFi] failed to hook wifi service: $t")
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
                    XposedBridge.log(
                        "FL: [WiFi] failed already-loaded probe from $loader: $t"
                    )
                }
            }
        if (!hookedLoadedService) {
            probeFailure?.let { throw it }
            if (loadWatchers.isEmpty()) {
                throw ClassNotFoundException("WifiServiceImpl from available loaders")
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun tryHookWifiService(loader: ClassLoader, source: String): Boolean {
        val wifiClazz = runCatching {
            loader.loadClass("com.android.server.wifi.WifiServiceImpl")
        }.getOrNull() ?: return false

        XposedBridge.log("FL: [WiFi] hooking WifiServiceImpl from $source")
        var failure: Throwable? = null
        try {
            hookScanResults(wifiClazz)
        } catch (t: Throwable) {
            failure = t
            XposedBridge.log("FL: [WiFi] failed to hook scan results: $t")
        }
        try {
            hookConnectionInfo(wifiClazz)
        } catch (t: Throwable) {
            failure = t
            XposedBridge.log("FL: [WiFi] failed to hook connection info: $t")
        }
        failure?.let { throw it }
        return true
    }

    private fun scheduleWifiRetry(loader: ClassLoader, attempt: Int) {
        if (attempt > MAX_WIFI_RETRIES) {
            XposedBridge.log("FL: [WiFi] giving up after $MAX_WIFI_RETRIES retries")
            return
        }
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (!tryHookWifiService(loader, "retry $attempt")) {
                        scheduleWifiRetry(loader, attempt + 1)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("FL: [WiFi] retry $attempt failed: $t")
                    scheduleWifiRetry(loader, attempt + 1)
                }
            }, WIFI_RETRY_DELAY_MS)
        } catch (t: Throwable) {
            XposedBridge.log("FL: [WiFi] cannot schedule retry: $t")
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
                val (packageName, profile) = spoofedWifiProfile(param.args) ?: return@after
                val originalResult = param.result
                // From this point on, never retain the real scan result if
                // construction differs on a vendor framework.
                param.result = null

                XposedBridge.log(
                    "FL: [WiFi] in getScanResults for $packageName, reporting " +
                        "${profile.wifiAccessPoints.size} custom access point(s)"
                )

                val results = try {
                    profile.wifiAccessPoints.map { buildScanResult(it) }
                } catch (t: Throwable) {
                    XposedBridge.log("FL: [WiFi] scan spoof failed, returning empty: $t")
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
                val (packageName, profile) = spoofedWifiProfile(param.args) ?: return@after
                // Null is a valid fail-closed binder result if WifiInfo.Builder
                // is incompatible with this ROM; it must not fall back to the
                // original connection after the profile has matched.
                param.result = null

                // The connected AP is the first configured one; with none
                // configured there is nothing coherent to claim, so report the
                // same "not associated" state the framework uses.
                val connected = profile.wifiAccessPoints.firstOrNull()

                XposedBridge.log(
                    "FL: [WiFi] in getConnectionInfo for $packageName, " +
                        "reporting ${connected?.ssid ?: "no association"}"
                )

                try {
                    val builder = WifiInfo.Builder()
                        .setSsid((connected?.ssid ?: UNKNOWN_SSID).toByteArray())
                        .setBssid(connected?.bssid ?: UNSPECIFIED_BSSID)
                        .setRssi(connected?.level ?: MIN_RSSI)
                        .setNetworkId(if (connected != null) 0 else -1)
                    if (connected != null) {
                        // Builder.setFrequency is present on newer framework
                        // releases but absent from the compile SDK used by this
                        // project. Resolve it at runtime so both remain valid.
                        runCatching {
                            builder.javaClass
                                .getMethod("setFrequency", Int::class.javaPrimitiveType)
                                .invoke(builder, connected.frequency)
                        }.onFailure {
                            XposedBridge.log("FL: [WiFi] frequency setter unavailable: $it")
                        }
                    }
                    param.result = builder.build()
                } catch (t: Throwable) {
                    XposedBridge.log("FL: [WiFi] connection spoof failed, returning null: $t")
                }
                }
            }
        }
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
            XposedBridge.log("FL: [WiFi] setWifiSsid unavailable, setting SSID directly: $e")
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
            XposedBridge.log("FL: [WiFi] cannot construct scan result container: $e")
            null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun spoofedWifiProfile(args: Array<Any?>): Pair<String, fuck.location.app.ui.models.Profile>? {
        args.filterIsInstance<String>().forEach { candidate ->
            ConfigGateway.get().wifiSpoofFor(candidate)?.let { return candidate to it }
        }
        return null
    }

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
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val UNSPECIFIED_BSSID = "02:00:00:00:00:00"
        const val MIN_RSSI = -127
        val hookedLoadWatcherMethods = ConcurrentHashMap.newKeySet<Method>()
        val hookedScanMethods = ConcurrentHashMap.newKeySet<Method>()
        val hookedConnectionMethods = ConcurrentHashMap.newKeySet<Method>()
        const val MAX_WIFI_RETRIES = 6
        const val WIFI_RETRY_DELAY_MS = 5_000L
    }
}
