package fuck.location.xposed.location

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.SystemClock
import fuck.location.app.ui.models.FakeAccessPoint
import fuck.location.xposed.helpers.reflect.*
import dalvik.system.PathClassLoader
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.xposed.helpers.ConfigGateway

class WLANHooker {
    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookWifiManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz: Class<*> = lpparam.classLoader.loadClass("com.android.server.SystemServiceManager")

        findAllMethods(clazz) {
            name == "loadClassFromLoader" && isPrivate && isStatic
        }.hookMethod {
            after { param ->
                if (param.args[0] != "com.android.server.wifi.WifiService") return@after

                XposedBridge.log("FL: [WiFi] wifi service loaded, hooking it")
                try {
                    val classloader = param.args[1] as PathClassLoader
                    val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")

                    hookScanResults(wifiClazz)
                    hookConnectionInfo(wifiClazz)
                } catch (e: Exception) {
                    XposedBridge.log("FL: [WiFi] failed to hook wifi service: $e")
                }
            }
        }
    }

    @ExperimentalStdlibApi
    private fun hookScanResults(wifiClazz: Class<*>) {
        findAllMethods(wifiClazz) {
            name == "getScanResults" && isPublic
        }.hookMethod {
            after { param ->
                val packageName = param.args[0] as? String ?: return@after
                val profile = ConfigGateway.get().wifiSpoofFor(packageName) ?: return@after

                XposedBridge.log(
                    "FL: [WiFi] in getScanResults for $packageName, reporting " +
                        "${profile.wifiAccessPoints.size} custom access point(s)"
                )

                val results = profile.wifiAccessPoints.map { buildScanResult(it) }

                // Android 13 changed the return type from List<ScanResult> to
                // ParceledListSlice<ScanResult>. Handing back a bare list makes
                // the binder reply blow up, so mirror whatever the real method
                // returned rather than assuming either shape.
                param.result = wrapLikeOriginal(param.result, results)
            }
        }
    }

    @ExperimentalStdlibApi
    private fun hookConnectionInfo(wifiClazz: Class<*>) {
        findAllMethods(wifiClazz) {
            name == "getConnectionInfo" && isPublic
        }.hookMethod {
            after { param ->
                val packageName = param.args[0] as? String ?: return@after
                val profile = ConfigGateway.get().wifiSpoofFor(packageName) ?: return@after

                // The connected AP is the first configured one; with none
                // configured there is nothing coherent to claim, so report the
                // same "not associated" state the framework uses.
                val connected = profile.wifiAccessPoints.firstOrNull()

                XposedBridge.log(
                    "FL: [WiFi] in getConnectionInfo for $packageName, " +
                        "reporting ${connected?.ssid ?: "no association"}"
                )

                param.result = WifiInfo.Builder()
                    .setSsid((connected?.ssid ?: UNKNOWN_SSID).toByteArray())
                    .setBssid(connected?.bssid ?: UNSPECIFIED_BSSID)
                    .setRssi(connected?.level ?: MIN_RSSI)
                    .setNetworkId(if (connected != null) 0 else -1)
                    .build()
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
    private fun wrapLikeOriginal(original: Any?, results: List<ScanResult>): Any? {
        if (original is List<*>) return results

        return try {
            Class.forName("android.content.pm.ParceledListSlice")
                .getConstructor(List::class.java)
                .newInstance(results)
        } catch (e: Exception) {
            XposedBridge.log("FL: [WiFi] no ParceledListSlice, returning a plain list: $e")
            results
        }
    }

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val UNSPECIFIED_BSSID = "02:00:00:00:00:00"
        const val MIN_RSSI = -127
    }
}
