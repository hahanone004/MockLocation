package fuck.location.xposed.location

import android.annotation.SuppressLint
import android.location.*
import android.os.SystemClock
import android.util.ArrayMap
import fuck.location.xposed.helpers.reflect.*
import de.robv.android.xposed.XC_MethodHook
import fuck.location.xposed.helpers.ConfigGateway
import java.lang.Exception
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class LocationHooker {
    companion object {
        /** Concrete accept methods already carrying our argument hook. */
        private val hookedRegistrationMethods = ConcurrentHashMap.newKeySet<Method>()
        /**
         * Registration classes already looked at. onReportLocation runs at
         * whatever rate the provider reports at, and every report used to
         * re-scan each live registration's class - declared methods plus the
         * whole superclass chain - to rediscover a method set that was already
         * hooked. The method set below deduplicated the hooks; nothing
         * deduplicated the reflection that found them.
         */
        private val armedRegistrationClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        /** Service/geofence methods, so a failed sibling step can be retried safely. */
        private val hookedServiceMethods = ConcurrentHashMap.newKeySet<Method>()
    }

    @SuppressLint("PrivateApi")
    @ExperimentalStdlibApi
    fun hookLastLocation(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.location.provider.LocationProviderManager")

        val methods = findAllMethods(clazz, findSuper = true) {
            name == "onReportLocation"
        }
        if (methods.isEmpty()) throw NoSuchMethodException("onReportLocation in ${clazz.name}")
        val registrationsField = findField(clazz, true) { name == "mRegistrations" }
        installServiceHooks(methods) { method ->
            method.hookMethod {
                before { param ->
                    armRegistrationHooks(registrationsField, param)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("PrivateApi")
    fun hookDLC(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.location.LocationManagerService")

        Log.i(
            "[Location] DLC class loader=${clazz.classLoader} " +
                "declared=${clazz.declaredMethods.size} nested=" +
                clazz.declaredClasses.joinToString { it.name }
        )

        val methods = findAllMethods(clazz, findSuper = true) {
            name == "getLastLocation" && isPublic
        }
        if (methods.isEmpty()) throw NoSuchMethodException("getLastLocation in ${clazz.name}")
        installServiceHooks(methods) { method ->
            method.hookMethod {
                after {
                if (it.hasThrowable()) return@after
                var packageName: String? = null
                var profile: fuck.location.app.ui.models.Profile? = null
                try {
                    val resolvedPackage = ConfigGateway.get().callerPackageName(it)
                    packageName = resolvedPackage
                    profile = ConfigGateway.get().locationSpoofFor(resolvedPackage)
                    val activeProfile = profile ?: return@after

                    val location: Location

                    if (it.result == null) {
                        location = Location(LocationManager.FUSED_PROVIDER)
                        location.time = System.currentTimeMillis() - (100..10000).random()
                        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        location.accuracy = 8F
                    } else {
                        // Copy the complete object (extras and newer accuracy
                        // fields included) and only replace sensitive values.
                        location = Location(it.result as Location)
                    }

                    val (latitude, longitude) = activeProfile.jitteredPosition()
                    location.latitude = latitude
                    location.longitude = longitude
                    location.isMock = false
                    location.speed = 0F
                    location.speedAccuracyMetersPerSecond = 0F
                    location.extras = null
                    clearInconsistentMotionFields(location)

                    Log.i(
                        "getLastLocation for $packageName -> " +
                            "${location.latitude}, ${location.longitude}")
                    it.result = location
                } catch (e: Throwable) {
                    Log.i("Fuck with exceptions! $e")
                    // Once the caller has resolved to a spoofed profile, never
                    // leave the original real result in place on an error.
                    if (profile != null) {
                        it.result = fallbackLocation(profile!!)
                        Log.i("getLastLocation fallback for $packageName")
                    }
                }
                }
            }
        }

        hookGeofences(classLoader)
    }

    @ExperimentalStdlibApi
    private fun hookGeofences(classLoader: ClassLoader) {
        val state = sequenceOf(
            "com.android.server.location.geofence.GeofenceState",
            "com.android.server.location.GeofenceState",
        ).mapNotNull { className ->
            runCatching { classLoader.loadClass(className) }
                .onFailure { Log.d("[Location] geofence candidate unavailable: $className (${it.javaClass.simpleName})") }
                .getOrNull()
        }
            .firstOrNull()
            ?: throw ClassNotFoundException("GeofenceState")
        // Resolve this once while installing. A renamed ROM field must make the
        // hook step visibly fail instead of silently leaking real coordinates
        // from every callback.
        val packageNameField = findField(state, true) {
            name == "mPackageName" || name == "packageName"
        }
        val methods = findAllMethods(state, findSuper = true) {
            name == "processLocation" && parameterCount == 1 &&
                Location::class.java.isAssignableFrom(parameterTypes[0])
        }
        if (methods.isEmpty()) throw NoSuchMethodException("processLocation in ${state.name}")
        installServiceHooks(methods) { method -> method.hookBefore { param ->
                val packageName = try {
                    packageNameField.get(param.thisObject) as String
                } catch (t: Throwable) {
                    // A hook must not alter apps that have not positively
                    // matched a profile. Suppressing an unknown owner's update
                    // disables geofencing globally on ROMs with a different
                    // owner representation.
                    Log.w("geofence owner lookup failed; leaving update alone: $t")
                    return@hookBefore
                }
                val profile = ConfigGateway.get().locationSpoofFor(packageName)
                    ?: return@hookBefore
                val original = param.args[0] as? Location ?: return@hookBefore
                val (latitude, longitude) = profile.jitteredPosition()
                param.args[0] = Location(original).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    isMock = false
                    speed = 0F
                    speedAccuracyMetersPerSecond = 0F
                    extras = null
                    clearInconsistentMotionFields(this)
                }
        } }
    }

    private fun installServiceHooks(methods: List<Method>, install: (Method) -> Unit) {
        var failure: Throwable? = null
        methods.filter { hookedServiceMethods.add(it) }.forEach { method ->
            try {
                install(method)
            } catch (t: Throwable) {
                hookedServiceMethods.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun armRegistrationHooks(
        registrationsField: Field,
        param: XC_MethodHook.MethodHookParam,
    ) {
        val registrations = registrationsField.get(param.thisObject) as ArrayMap<*, *>
        registrations.values.filterNotNull().forEach { registration ->
            installRegistrationHook(registration.javaClass)
        }
    }

    /**
     * Substitutes only the LocationResult being accepted by one registration.
     * The provider keeps its real registration map and executes the complete
     * stock filtering, throttling, permission and listener lifecycle pipeline.
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun installRegistrationHook(registrationClass: Class<*>) {
        if (!armedRegistrationClasses.add(registrationClass)) return
        try {
            val methods = findAllMethods(registrationClass, findSuper = true) {
                name == "acceptLocationChange" && parameterCount == 1
            }
            if (methods.isEmpty()) {
                throw NoSuchMethodException("acceptLocationChange in ${registrationClass.name}")
            }
            methods.filter { hookedRegistrationMethods.add(it) }.forEach { method ->
                try {
                    method.hookBefore { callback ->
                        val packageName = resolveRegistrationPackage(callback.thisObject)
                            ?: return@hookBefore
                        val profile = ConfigGateway.get().locationSpoofFor(packageName)
                            ?: return@hookBefore
                        val original = callback.args[0] ?: return@hookBefore

                        callback.args[0] = try {
                            spoofLocationResult(original, profile)
                        } catch (t: Throwable) {
                            // A null operation means this update is filtered
                            // out. Never pass real coordinates after a profile
                            // has positively matched.
                            Log.w("failed to build location for $packageName: $t")
                            callback.result = null
                            return@hookBefore
                        }
                    }
                } catch (t: Throwable) {
                    hookedRegistrationMethods.remove(method)
                    // Let the next report try this class again.
                    armedRegistrationClasses.remove(registrationClass)
                    Log.w("failed to hook $method: $t")
                }
            }
        } catch (t: Throwable) {
            armedRegistrationClasses.remove(registrationClass)
            Log.w("failed to hook ${registrationClass.name}: $t")
        }
    }

    private fun spoofLocationResult(
        original: Any,
        profile: fuck.location.app.ui.models.Profile,
    ): Any {
        val locationsField = findField(original.javaClass, true) {
            name == "mLocations" && isPrivate
        }
        val realLocations = (locationsField.get(original) as? List<*>)
            ?.filterIsInstance<Location>()
            .orEmpty()
        val spoofedLocations = if (realLocations.isEmpty()) {
            listOf(fallbackLocation(profile))
        } else {
            val (latitude, longitude) = profile.jitteredPosition()
            realLocations.map { origin ->
                Location(origin).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    isMock = false
                    speed = 0F
                    speedAccuracyMetersPerSecond = 0F
                    extras = null
                    clearInconsistentMotionFields(this)
                }
            }
        }

        val create = findAllMethods(original.javaClass, findSuper = true) {
            name == "create" && isStatic && parameterCount == 1 &&
                List::class.java.isAssignableFrom(parameterTypes[0])
        }.firstOrNull() ?: throw NoSuchMethodException("LocationResult.create(List)")
        return create.invoke(null, spoofedLocations)
    }

    /** A stationary 2-D fix must not retain motion/altitude metadata from reality. */
    private fun clearInconsistentMotionFields(location: Location) {
        location.removeBearing()
        location.removeBearingAccuracy()
        location.removeAltitude()
        location.removeVerticalAccuracy()
    }

    private fun fallbackLocation(profile: fuck.location.app.ui.models.Profile): Location {
        val (latitude, longitude) = profile.jitteredPosition()
        return Location(LocationManager.FUSED_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = 8F
            speed = 0F
            speedAccuracyMetersPerSecond = 0F
            extras = null
            isMock = false
            clearInconsistentMotionFields(this)
        }
    }

    private fun resolveRegistrationPackage(registration: Any): String? {
        val directNames = setOf("mPackageName", "packageName", "mCallingPackage", "callingPackage")
        val directField = runCatching { findAllFields(registration.javaClass) }.getOrNull()
            ?.firstOrNull {
            it.name in directNames && it.type == String::class.java
            }
        directField?.let { field ->
            runCatching { field.get(registration) as? String }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return try {
            val identity = findField(registration.javaClass, true) { name == "mIdentity" }
                .get(registration) ?: return null
            ConfigGateway.get().callerIdentityToPackageName(identity)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findAllFields(clazz: Class<*>): List<Field> {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                runCatching { field.isAccessible = true }
                    .onSuccess { fields.add(field) }
            }
            current = current.superclass
        }
        return fields
    }
}
