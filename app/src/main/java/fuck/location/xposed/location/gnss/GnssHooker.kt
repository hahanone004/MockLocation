package fuck.location.xposed.location.gnss

import android.annotation.SuppressLint
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.helpers.reflect.Log
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.hookBefore
import fuck.location.xposed.helpers.reflect.isPublic
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class GnssHooker {
    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("PrivateApi")
    fun hookGnssCallbacks(classLoader: ClassLoader) {
        val clazz =
            classLoader.loadClass("com.android.server.location.gnss.GnssManagerService")

        val registrations = listOf(
            "registerGnssStatusCallback",
            "registerGnssNmeaCallback",
            "addGnssMeasurementsListener",
            "addGnssNavigationMessageListener",
            "addGnssAntennaInfoListener",
        )
        val resolved = registrations.associateWith { methodName ->
            findAllMethods(clazz, findSuper = true) {
                name == methodName && isPublic
            }
        }
        resolved.forEach { (methodName, methods) ->
            if (methods.isEmpty()) {
                Log.w("GNSS method unavailable: $methodName in ${clazz.name}")
            }
        }

        val missingCore = setOf(
            "registerGnssStatusCallback",
            "registerGnssNmeaCallback",
        ).filter { resolved[it].isNullOrEmpty() }
        if (missingCore.isNotEmpty()) {
            throw NoSuchMethodException("missing core GNSS methods $missingCore in ${clazz.name}")
        }

        var failure: Throwable? = null
        resolved.values.flatten().filter { hookedMethods.add(it) }.forEach { method ->
            try {
                method.hookBefore { param -> disableForSpoofedApp(param) }
            } catch (t: Throwable) {
                hookedMethods.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun disableForSpoofedApp(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        // Signatures and package-name indices differ between Android releases.
        // Pick the string argument that actually resolves to a spoofed app.
        val packageName = param.args.filterIsInstance<String>().firstOrNull {
            ConfigGateway.get().locationSpoofFor(it) != null
        } ?: return

        Log.d { "disabling ${param.method.name} for $packageName" }
        val returnType = (param.method as Method).returnType
        param.result = if (returnType == Boolean::class.javaPrimitiveType) false else null
    }

    private companion object {
        val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    }
}
