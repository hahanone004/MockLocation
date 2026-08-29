package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.*
import fuck.location.xposed.helpers.reflect.*
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.app.ui.models.Profile
import fuck.location.xposed.cellar.identity.Lte
import fuck.location.xposed.cellar.info.DisplayInfo
import fuck.location.xposed.helpers.ConfigGateway
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class TelephonyRegistryHooker {
    private data class PendingCellCallback(
        val callback: Any,
        val event: Int,
        val profile: Profile,
    )

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookListen(classLoader: ClassLoader) {
        val clazz: Class<*> =
            classLoader.loadClass("com.android.server.TelephonyRegistry")

        Log.i(
            "[Cellar] TelephonyRegistry loader=${clazz.classLoader} " +
                "declared=${clazz.declaredMethods.size} inherited=${clazz.methods.size}"
        )

        val validationMethods = findAllMethods(clazz, findSuper = true) {
            name == "validateEventAndUserLocked" && isPrivate
        }
        val proxy = classLoader.loadClass(
            "com.android.internal.telephony.IPhoneStateListener\$Stub\$Proxy"
        )

        val locationCallbacks = findAllMethods(proxy, findSuper = true) {
            name == "onCellLocationChanged"
        }
        val infoCallbacks = findAllMethods(proxy, findSuper = true) {
            name == "onCellInfoChanged"
        }
        // Optional: a ROM without it simply keeps reporting the real
        // generation, which is worth a line in the log rather than taking the
        // cell-identity hooks down with it.
        val displayCallbacks = findAllMethods(proxy, findSuper = true) {
            name == "onDisplayInfoChanged"
        }
        if (displayCallbacks.isEmpty()) {
            Log.w("[Cellar] onDisplayInfoChanged absent; 5G badge left alone")
        }
        val notificationMethods = findAllMethods(clazz, findSuper = true) {
            name == "notifyCellInfoForSubscriber" || name == "notifyCellLocationForSubscriber" ||
                name == "notifyDisplayInfoChanged"
        }
        Log.i(
            "[Cellar] TelephonyRegistry surface validation=${validationMethods.map { it.toGenericString() }} " +
                "notifications=${notificationMethods.map { it.toGenericString() }} " +
                "callbacks=location:${locationCallbacks.size},info:${infoCallbacks.size},display:${displayCallbacks.size}"
        )
        if (validationMethods.isEmpty() || notificationMethods.isEmpty() ||
            locationCallbacks.isEmpty() || infoCallbacks.isEmpty()) {
            throw NoSuchMethodException(
                "incomplete TelephonyRegistry hook surface: validation=${validationMethods.size}, " +
                    "notifications=${notificationMethods.size}, location=${locationCallbacks.size}, " +
                    "info=${infoCallbacks.size}"
            )
        }

        // Install cleanup first, so even a later hook-install failure cannot
        // leave ThreadLocal state attached to an unrelated notification.
        hookAfterOnce(notificationMethods) { pendingCellCallback.remove() }

        hookAfterOnce(validationMethods) { param ->
            val record = param.args[0]
            val event = param.args[1] as Int
            if (event !in SUBSTITUTED_EVENTS) return@hookAfterOnce

            val packageName = findField(record.javaClass, true) {
                name == "callingPackage"
            }.get(record) as String
            val profile = if (param.result == true) {
                ConfigGateway.get().cellSpoofFor(packageName)
            } else null
            if (profile == null) {
                pendingCellCallback.remove()
                return@hookAfterOnce
            }

            val callback = findField(record.javaClass, true) { name == "callback" }.get(record)
            pendingCellCallback.set(PendingCellCallback(callback, event, profile))
        }

        hookBeforeOnce(locationCallbacks) { param ->
            val pending = pendingFor(param.thisObject, EVENT_CELL_LOCATION)
                ?: return@hookBeforeOnce
            param.args[0] = try {
                if (pending.profile.describesCell) Lte().cellIdentity(pending.profile) else null
            } catch (t: Throwable) {
                Log.w("[Cellar] failed to build cell location, returning null: $t")
                null
            }
            pendingCellCallback.remove()
            Log.i("[Cellar] substituting a cell-location callback")
        }

        hookBeforeOnce(displayCallbacks) { param ->
            val pending = pendingFor(param.thisObject, EVENT_DISPLAY_INFO)
                ?: return@hookBeforeOnce
            // Nothing to be consistent with: a profile with the cell switch on
            // but no cell filled in reports no cell either.
            if (!pending.profile.describesCell) return@hookBeforeOnce
            val reported = param.args[0] as? TelephonyDisplayInfo ?: return@hookBeforeOnce
            param.args[0] = try {
                DisplayInfo().asLte(reported)
            } catch (t: Throwable) {
                // Reporting the real generation is the lesser evil: a null here
                // would reach an app that is expecting a display info object.
                Log.w("[Cellar] failed to build display info: $t")
                reported
            }
            pendingCellCallback.remove()
            Log.i("[Cellar] reporting the radio as LTE")
        }

        hookBeforeOnce(infoCallbacks) { param ->
            val pending = pendingFor(param.thisObject, EVENT_CELL_INFO)
                ?: return@hookBeforeOnce
            val configured = try {
                mutableListOf<CellInfo>().apply {
                    if (pending.profile.describesCell) {
                        add(fuck.location.xposed.cellar.info.Lte().cellInfo(pending.profile))
                    }
                }
            } catch (t: Throwable) {
                Log.w("[Cellar] failed to build cell info, returning empty: $t")
                emptyList()
            }
            param.args[0] = configured
            pendingCellCallback.remove()
            Log.i("[Cellar] substituting a cell-info callback")
        }
    }

    private fun pendingFor(callback: Any, event: Int): PendingCellCallback? {
        val pending = pendingCellCallback.get() ?: return null
        if (pending.callback !== callback || pending.event != event) return null
        return pending
    }

    private fun hookBeforeOnce(
        methods: List<Method>,
        action: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit,
    ) = hookOnce(methods) { method -> method.hookBefore(action = action) }

    private fun hookAfterOnce(
        methods: List<Method>,
        action: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit,
    ) = hookOnce(methods) { method -> method.hookAfter(action = action) }

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
        /* TelephonyCallback's own event ids, which the registry validates by. */
        const val EVENT_CELL_LOCATION = 5
        const val EVENT_CELL_INFO = 11
        const val EVENT_DISPLAY_INFO = 21
        val SUBSTITUTED_EVENTS = setOf(EVENT_CELL_LOCATION, EVENT_CELL_INFO, EVENT_DISPLAY_INFO)
        val pendingCellCallback = ThreadLocal<PendingCellCallback>()
        val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    }
}
