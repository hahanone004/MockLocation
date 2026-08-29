package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.*
import fuck.location.xposed.helpers.reflect.*
import fuck.location.xposed.cellar.identity.Lte
import fuck.location.xposed.cellar.info.DisplayInfo
import fuck.location.xposed.helpers.ConfigGateway
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The cell identity, the cell list and the reported generation, as a listener
 * receives them.
 *
 * Each substitution sits on the listener callback itself, which is the single
 * point where the framework has already decided that this app is getting this
 * update - past the event mask, the user check and the location permission
 * check. Which app it is comes out of the registry's own record list: the
 * callback is invoked on the very object a record holds, so the record holding
 * it names the package.
 *
 * This used to hang off validateEventAndUserLocked, stashing the record being
 * validated in a ThreadLocal for the callback to pick up. That method is
 * private, and a release build is free to inline it out of existence - which is
 * what LineageOS 23 does. The lookup then matched nothing, the whole step threw,
 * and every cellular spoof inside system_server quietly stopped working. A
 * field read is not something the compiler can optimise away.
 */
class TelephonyRegistryHooker {

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookListen(classLoader: ClassLoader) {
        val clazz: Class<*> = classLoader.loadClass(REGISTRY)
        val recordClass: Class<*> = classLoader.loadClass("$REGISTRY\$Record")

        val recordsField = findField(clazz, true) { name == "mRecords" }
        val callbackField = findField(recordClass, true) { name == "callback" }
        val packageField = findField(recordClass, true) { name == "callingPackage" }

        val proxy = classLoader.loadClass(
            "com.android.internal.telephony.IPhoneStateListener\$Stub\$Proxy"
        )
        val locationCallbacks = findAllMethods(proxy, findSuper = true) {
            name == "onCellLocationChanged"
        }
        val infoCallbacks = findAllMethods(proxy, findSuper = true) {
            name == "onCellInfoChanged"
        }
        if (locationCallbacks.isEmpty() || infoCallbacks.isEmpty()) {
            throw NoSuchMethodException(
                "incomplete IPhoneStateListener surface: location=${locationCallbacks.size}, " +
                    "info=${infoCallbacks.size}"
            )
        }

        // Optional: without it the radio keeps reporting its real generation,
        // which is worth a line in the log rather than taking the cell hooks
        // down with it.
        val displayCallbacks = findAllMethods(proxy, findSuper = true) {
            name == "onDisplayInfoChanged"
        }
        if (displayCallbacks.isEmpty()) {
            Log.w("[Cellar] onDisplayInfoChanged absent; the 5G badge is left alone")
        }

        /*
         * The registry is a singleton, and every callback below is dispatched
         * from inside one of its notify methods, so the first notification
         * hands us the instance the records hang off. ServiceManager is the
         * fallback for the one path that does not go through them:
         * checkPossibleMissNotify delivers straight from listen().
         */
        val notifications = findAllMethods(clazz, findSuper = true) {
            name == "notifyCellInfoForSubscriber" || name == "notifyCellLocationForSubscriber" ||
                name == "notifyDisplayInfoChanged"
        }
        hookOnce(notifications) { method ->
            method.hookBefore { param -> registry = param.thisObject }
        }

        fun packageFor(callback: Any): String? {
            val instance = registry ?: registryFromServiceManager(clazz) ?: return null
            val records = recordsField.get(instance) as? List<*> ?: return null

            // The notify loop is already inside this monitor on this thread, so
            // taking it again is free and the one path that is not synchronised
            // cannot then read the list mid-update.
            synchronized(records) {
                return records.firstOrNull { record ->
                    record != null && callbackField.get(record) === callback
                }?.let { packageField.get(it) as? String }
            }
        }

        fun spoofFor(callback: Any): fuck.location.app.ui.models.Profile? {
            val packageName = packageFor(callback) ?: return null
            return ConfigGateway.get().cellSpoofFor(packageName)
                ?.takeIf { it.describesCell }
        }

        Log.i(
            "[Cellar] TelephonyRegistry bound: records=${recordsField.name} " +
                "notifications=${notifications.size} callbacks=location:${locationCallbacks.size}," +
                "info:${infoCallbacks.size},display:${displayCallbacks.size}"
        )

        hookBeforeOnce(locationCallbacks) { param ->
            val profile = spoofFor(param.thisObject) ?: return@hookBeforeOnce
            param.args[0] = try {
                Lte().cellIdentity(profile, param.args[0] as? CellIdentityLte)
            } catch (t: Throwable) {
                Log.w("[Cellar] failed to build a cell location, reporting none: $t")
                null
            }
        }

        hookBeforeOnce(infoCallbacks) { param ->
            val profile = spoofFor(param.thisObject) ?: return@hookBeforeOnce
            param.args[0] = try {
                mutableListOf<CellInfo>(fuck.location.xposed.cellar.info.Lte().cellInfo(profile))
            } catch (t: Throwable) {
                Log.w("[Cellar] failed to build cell info, reporting none: $t")
                mutableListOf<CellInfo>()
            }
        }

        hookBeforeOnce(displayCallbacks) { param ->
            val profile = spoofFor(param.thisObject) ?: return@hookBeforeOnce
            val reported = param.args[0] as? TelephonyDisplayInfo ?: return@hookBeforeOnce
            param.args[0] = try {
                DisplayInfo().asLte(reported)
            } catch (t: Throwable) {
                // Reporting the real generation is the lesser evil: a null here
                // reaches an app that is expecting a display info object.
                Log.w("[Cellar] failed to build display info: $t")
                reported
            }
        }
    }

    /**
     * The registry as ServiceManager knows it. Inside system_server the entry
     * for a local service is the object itself, so this is the same instance a
     * notification would have handed over.
     */
    @SuppressLint("PrivateApi")
    private fun registryFromServiceManager(registryClass: Class<*>): Any? = try {
        Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, "telephony.registry")
            ?.takeIf { registryClass.isInstance(it) }
            ?.also { registry = it }
    } catch (t: Throwable) {
        Log.w("[Cellar] cannot reach the telephony registry: $t")
        null
    }

    private fun hookBeforeOnce(
        methods: List<Method>,
        action: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit,
    ) = hookOnce(methods) { method -> method.hookBefore(action = action) }

    private fun hookOnce(methods: List<Method>, install: (Method) -> Unit) {
        var failure: Throwable? = null
        methods.filter { hookedMethods.add(it) }.forEach { method ->
            try {
                install(method)
            } catch (t: Throwable) {
                hookedMethods.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val REGISTRY = "com.android.server.TelephonyRegistry"

        @Volatile var registry: Any? = null
        val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    }
}
