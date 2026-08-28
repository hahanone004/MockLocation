package fuck.location.xposed.location

import android.annotation.SuppressLint
import android.location.*
import android.os.Build
import android.util.ArrayMap
import androidx.annotation.RequiresApi
import fuck.location.xposed.helpers.reflect.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.xposed.helpers.ConfigGateway
import java.lang.Exception

class LocationHookerAfterS {
    companion object {
        /*
         * onReportLocation is intercepted by temporarily removing whitelisted
         * registrations from the provider's registration map, so the real method
         * only reports to everyone else. The map has to be put back afterwards,
         * hence the handover between the before and after hooks; both run on the
         * same provider thread, so a ThreadLocal keeps concurrent providers from
         * clobbering each other.
         */
        private val savedRegistrations = ThreadLocal<ArrayMap<*, *>>()
    }

    @SuppressLint("PrivateApi")
    @RequiresApi(Build.VERSION_CODES.S)
    @ExperimentalStdlibApi
    fun hookLastLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = lpparam.classLoader.loadClass("com.android.server.location.provider.LocationProviderManager")

        findAllMethods(clazz) {
            name == "onReportLocation"
        }.hookMethod {
            before { param ->
                hookOnReportLocation(clazz, param)
            }
            after { param ->
                restoreRegistrations(clazz, param)
            }
        }
    }

    /**
     * Puts the untouched registration map back. Without this the whitelisted
     * registrations stayed dropped for good: the provider kept reporting into a
     * map they had been filtered out of, so a matching app stopped receiving
     * locations entirely after the first report.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun restoreRegistrations(clazz: Class<*>, param: XC_MethodHook.MethodHookParam) {
        val saved = savedRegistrations.get() ?: return
        savedRegistrations.remove()

        try {
            findField(clazz, true) { name == "mRegistrations" }.set(param.thisObject, saved)
        } catch (e: Exception) {
            XposedBridge.log("FL: failed to restore registrations! $e")
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("PrivateApi")
    fun hookDLC(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = lpparam.classLoader.loadClass("com.android.server.location.LocationManagerService")

        findAllMethods(clazz) {
            name == "getLastLocation" && isPublic
        }.hookMethod {
            after {
                try {
                    val packageName = ConfigGateway.get().callerPackageName(it)
                    XposedBridge.log("FL: in getLastLocation! Caller package name: $packageName")

                    if (ConfigGateway.get().inWhitelist(packageName)) {
                        XposedBridge.log("FL: in whitelist! Return custom location")
                        val fakeLocation = ConfigGateway.get().readFakeLocation()

                        lateinit var location: Location
                        lateinit var originLocation: Location

                        if (it.result == null) {
                            location = Location(LocationManager.FUSED_PROVIDER)
                            location.time = System.currentTimeMillis() - (100..10000).random()
                        } else {
                            originLocation = it.result as Location
                            location = Location(originLocation.provider)

                            location.time = originLocation.time
                            location.accuracy = originLocation.accuracy
                            location.bearing = originLocation.bearing
                            location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                            location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                            location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
                            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                        }

                        location.latitude = fakeLocation.x + (Math.random() * fakeLocation.offset - fakeLocation.offset / 2)
                        location.longitude = fakeLocation.y + (Math.random() * fakeLocation.offset - fakeLocation.offset / 2)
                        location.altitude = 0.0
                        location.isMock = false
                        location.speed = 0F
                        location.speedAccuracyMetersPerSecond = 0F

                        XposedBridge.log("FL: x: ${location.latitude}, y: ${location.longitude}")
                        it.result = location
                    }
                } catch (e: Exception) {
                    XposedBridge.log("FL: Fuck with exceptions! $e")
                    e.printStackTrace()
                }
            }
        }

        findAllMethods(clazz) {
            name == "getCurrentLocation" && isPublic
        }.hookMethod {
            after { param ->
                // args[0] is the provider name here, not the package: reading it
                // as the caller meant this check compared "fused" against the
                // whitelist and never matched, on Android 12 either.
                val packageName = ConfigGateway.get().callerPackageName(param)

                XposedBridge.log("FL: in getCurrentLocation! Caller package name: $packageName")

                if (ConfigGateway.get().inWhitelist(packageName)) {
                    XposedBridge.log("FL: in whiteList! Inject null...")
                    param.result = null
                }
            }
        }

        findAllMethods(clazz) {
            name == "registerGnssStatusCallback" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[1] as String
            XposedBridge.log("FL: in registerGnssStatusCallback (S, DLC)! Caller package name: $packageName")

            if (ConfigGateway.get().inWhitelist(packageName)) {
                XposedBridge.log("FL: in whiteList! Dropping register request...")
                param.result = null
                return@hookBefore
            }
        }

        findAllMethods(clazz) {
            name == "registerGnssNmeaCallback" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[1] as String
            XposedBridge.log("FL: in registerGnssNmeaCallback (S, DLC)! Caller package name: $packageName")

            if (ConfigGateway.get().inWhitelist(packageName)) {
                XposedBridge.log("FL: in whiteList! Dropping register request...")
                param.result = null
                return@hookBefore
            }
        }

        findAllMethods(clazz) {
            name == "requestGeofence" && isPublic
        }.hookBefore { param ->
            val packageName = param.args[2] as String
            XposedBridge.log("FL: in requestGeofence (S, DLC)! Caller package name: $packageName")

            if (ConfigGateway.get().inWhitelist(packageName)) {
                XposedBridge.log("FL: in whiteList! Dropping register request...")
                param.result = null
                return@hookBefore
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @OptIn(ExperimentalStdlibApi::class)
    private fun hookOnReportLocation(clazz: Class<*>, param: XC_MethodHook.MethodHookParam) {
        val locationResult = param.args[0] ?: return

        val mRegistrations = findField(clazz, true) {
            name == "mRegistrations"
        }

        val registrations = mRegistrations.get(param.thisObject) as ArrayMap<*, *>
        val passthrough = ArrayMap<Any, Any>()

        val mLocationsField = findField(locationResult.javaClass, true) {
            name == "mLocations" && isPrivate
        }

        // The reported LocationResult is shared by every registration, so the
        // real locations have to be put back before the original method runs.
        // Substituting them in place used to leak the spoofed position to apps
        // that were never on the whitelist.
        val realLocations = mLocationsField.get(locationResult) as ArrayList<*>
        var substituted = false

        registrations.forEach { registration ->
            val key = registration.key ?: return@forEach
            val value = registration.value ?: return@forEach

            val packageName = try {
                val callerIdentity = findField(value.javaClass, true) {
                    name == "mIdentity"
                }.get(value)

                ConfigGateway.get().callerIdentityToPackageName(callerIdentity!!)
            } catch (e: Exception) {
                // An unreadable registration is reported to as usual rather than
                // being dropped on the floor.
                XposedBridge.log("FL: cannot resolve registration identity, passing through: $e")
                passthrough[key] = value
                return@forEach
            }

            if (!ConfigGateway.get().inWhitelist(packageName)) {
                passthrough[key] = value
                return@forEach
            }

            try {
                val originLocation = realLocations.firstOrNull() as? Location
                    ?: Location(LocationManager.GPS_PROVIDER)
                val fakeLocation = ConfigGateway.get().readFakeLocation()

                val location = Location(originLocation.provider)

                location.latitude = fakeLocation.x + (Math.random() * fakeLocation.offset - fakeLocation.offset / 2)
                location.longitude = fakeLocation.y + (Math.random() * fakeLocation.offset - fakeLocation.offset / 2)
                location.isMock = false
                location.altitude = 0.0
                location.speed = 0F
                location.speedAccuracyMetersPerSecond = 0F

                location.time = originLocation.time
                location.accuracy = originLocation.accuracy
                location.bearing = originLocation.bearing
                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters

                mLocationsField.set(locationResult, arrayListOf(location))
                substituted = true

                val operation = findMethod(value.javaClass, true) {
                    name == "acceptLocationChange"
                }.invoke(value, locationResult)

                findMethod(value.javaClass, true) {
                    name == "executeOperation"
                }.invoke(value, operation)
            } catch (e: Exception) {
                XposedBridge.log("FL: failed to deliver custom location to $packageName: $e")
            }
        }

        if (substituted) mLocationsField.set(locationResult, realLocations)

        savedRegistrations.set(registrations)
        mRegistrations.set(param.thisObject, passthrough)
    }
}