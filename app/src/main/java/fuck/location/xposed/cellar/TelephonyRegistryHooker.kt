package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.*
import fuck.location.xposed.helpers.reflect.*
import fuck.location.xposed.helpers.reflect.findAllMethods
import de.robv.android.xposed.XposedBridge
import fuck.location.app.ui.models.Profile
import fuck.location.xposed.cellar.identity.Lte
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
        val notificationMethods = findAllMethods(clazz, findSuper = true) {
            name == "notifyCellInfoForSubscriber" || name == "notifyCellLocationForSubscriber"
        }
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
            if (event != EVENT_CELL_LOCATION && event != EVENT_CELL_INFO) return@hookAfterOnce

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
                XposedBridge.log("FL: [Cellar] failed to build cell location, returning null: $t")
                null
            }
            pendingCellCallback.remove()
            XposedBridge.log("FL: [Cellar] substituting a cell-location callback")
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
                XposedBridge.log("FL: [Cellar] failed to build cell info, returning empty: $t")
                emptyList()
            }
            param.args[0] = configured
            pendingCellCallback.remove()
            XposedBridge.log("FL: [Cellar] substituting a cell-info callback")
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
        const val EVENT_CELL_LOCATION = 5
        const val EVENT_CELL_INFO = 11
        val pendingCellCallback = ThreadLocal<PendingCellCallback>()
        val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    }
}
