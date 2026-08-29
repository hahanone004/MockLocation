package fuck.location.xposed.cellar

import android.annotation.SuppressLint
import android.telephony.*
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.hookMethod
import fuck.location.xposed.helpers.reflect.isPublic
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.app.ui.models.Profile
import fuck.location.xposed.cellar.identity.Lte
import fuck.location.xposed.helpers.ConfigGateway
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class PhoneInterfaceManagerHooker {
    private data class PendingCellInfo(val packageName: String, val profile: Profile)

    private val pendingCellInfo = Collections.synchronizedMap(
        WeakHashMap<Any, ArrayDeque<PendingCellInfo>>()
    )
    private val hookedCallbackMethods = ConcurrentHashMap.newKeySet<Method>()
    private val pendingRequestCallback = ThreadLocal<Any>()

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookCellLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz: Class<*> =
            lpparam.classLoader.loadClass("com.android.phone.PhoneInterfaceManager")

        XposedBridge.log("FL: [Cellar] Finding method in PhoneInterfaceManager")

        matchedMethods(clazz, "getImeiForSlot") {
            name == "getImeiForSlot" && isPublic
        }.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, profile) = spoofedCellProfile(param, simIdentity = true)
                    ?: return@after
                val customIMEI = profile.deviceImei

                param.result = customIMEI
                XposedBridge.log("FL: [Cellar] getImeiForSlot for $packageName -> $customIMEI")
            }
        }

        matchedMethods(clazz, "getMeidForSlot") {
            name == "getMeidForSlot" && isPublic
        }.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, profile) = spoofedCellProfile(param, simIdentity = true)
                    ?: return@after
                val customMEID = profile.deviceMeid

                param.result = customMEID
                XposedBridge.log("FL: [Cellar] getMeidForSlot for $packageName -> $customMEID")
            }
        }

        matchedMethods(clazz, "getCellLocation") {
            name == "getCellLocation" && isPublic
        }.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, profile) = spoofedCellProfile(param) ?: return@after

                param.result = try {
                    val reported = param.result as? CellIdentityLte
                    if (profile.describesCell) Lte().cellIdentity(profile, reported) else null
                } catch (t: Throwable) {
                    XposedBridge.log("FL: [Cellar] getCellLocation spoof failed, returning null: $t")
                    null
                }

                XposedBridge.log("FL: [Cellar] getCellLocation for $packageName -> ${param.result}")
            }
        }

        matchedMethods(clazz, "getAllCellInfo") {
            name == "getAllCellInfo" && isPublic
        }.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, profile) = spoofedCellProfile(param) ?: return@after

                // Establish the fail-closed result before any reflection-based
                // construction can throw.
                // One cell, the configured one. An empty list used to be the
                // answer, which says the phone can see no towers at all - not
                // something that happens to a phone that is registered on a
                // network and knows where it is.
                val cells = ArrayList<CellInfo>()
                param.result = cells
                try {
                    if (profile.describesCell) {
                        cells.add(fuck.location.xposed.cellar.info.Lte().cellInfo(profile))
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("FL: [Cellar] getAllCellInfo spoof failed, returning empty: $t")
                }

                XposedBridge.log("FL: [Cellar] getAllCellInfo for $packageName -> ${cells.size} cell(s)")
                param.result = cells
            }
        }

        matchedMethods(clazz, "getNeighboringCellInfo") {
            name == "getNeighboringCellInfo" && isPublic
        }.hookMethod {
            after { param ->
                if (param.hasThrowable()) return@after
                val (packageName, _) = spoofedCellProfile(param) ?: return@after

                XposedBridge.log("FL: [Cellar] getNeighboringCellInfo for $packageName -> empty")
                val customNeighboringCellInfo = ArrayList<NeighboringCellInfo>()
                param.result = customNeighboringCellInfo
            }
        }

        matchedMethods(clazz, "requestCellInfoUpdateInternal") {
            name == "requestCellInfoUpdateInternal" && isPublic
        }.hookMethod {
            before { param ->
                val (packageName, profile) = spoofedCellProfile(param) ?: return@before
                val callback = param.args.firstOrNull { argument ->
                    argument?.let { allInterfaces(it.javaClass) }?.any { iface ->
                        iface.methods.any { it.name == "onCellInfo" && it.parameterCount == 1 }
                    } == true
                }
                if (callback == null) {
                    XposedBridge.log("FL: [Cellar] no cell-info callback in ${param.method}")
                    param.result = neutralResult((param.method as Method).returnType)
                    return@before
                }
                if (installCellInfoCallbackHooks(callback.javaClass)) {
                    val callbackKey = callbackKey(callback)
                    synchronized(pendingCellInfo) {
                        pendingCellInfo.getOrPut(callbackKey) { ArrayDeque() }
                            .addLast(PendingCellInfo(packageName, profile))
                    }
                    pendingRequestCallback.set(callbackKey)
                } else {
                    // The stock request would deliver real modem data. If its
                    // callback class cannot be hooked, suppress the request and
                    // let the caller receive the method's normal neutral result.
                    XposedBridge.log("FL: [Cellar] callback hook unavailable; suppressing request")
                    param.result = neutralResult((param.method as Method).returnType)
                }
            }
            after { param ->
                val callback = pendingRequestCallback.get() ?: return@after
                pendingRequestCallback.remove()
                if (param.hasThrowable()) takePendingCellInfo(callback)
            }
        }
    }

    private fun installCellInfoCallbackHooks(callbackClass: Class<*>): Boolean {
        val onCellInfo = findAllMethods(callbackClass, findSuper = true) {
            name == "onCellInfo" && parameterCount == 1
        }
        if (onCellInfo.isEmpty()) return false

        var installed = true
        onCellInfo.filter { hookedCallbackMethods.add(it) }.forEach { method ->
            try {
                method.hookMethod {
                    before { param ->
                        val pending = takePendingCellInfo(callbackKey(param.thisObject))
                            ?: return@before
                        param.args[0] = spoofedCells(pending.profile)
                        XposedBridge.log(
                            "FL: [Cellar] async CellInfo for ${pending.packageName} substituted"
                        )
                    }
                }
            } catch (t: Throwable) {
                hookedCallbackMethods.remove(method)
                installed = false
                XposedBridge.log("FL: [Cellar] failed to hook callback $method: $t")
            }
        }

        findAllMethods(callbackClass, findSuper = true) { name == "onError" }
            .filter { hookedCallbackMethods.add(it) }
            .forEach { method ->
                try {
                    method.hookMethod {
                        before { param -> takePendingCellInfo(callbackKey(param.thisObject)) }
                    }
                } catch (t: Throwable) {
                    hookedCallbackMethods.remove(method)
                    XposedBridge.log("FL: [Cellar] failed to hook callback error $method: $t")
                }
            }
        return installed
    }

    private fun takePendingCellInfo(callback: Any): PendingCellInfo? =
        synchronized(pendingCellInfo) {
            val queue = pendingCellInfo[callback] ?: return@synchronized null
            val pending = queue.pollFirst()
            if (queue.isEmpty()) pendingCellInfo.remove(callback)
            pending
        }

    private fun callbackKey(callback: Any): Any = try {
        callback.javaClass.methods.firstOrNull {
            it.name == "asBinder" && it.parameterCount == 0
        }?.invoke(callback) ?: callback
    } catch (_: Throwable) {
        callback
    }

    private fun neutralResult(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0F
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun spoofedCells(profile: Profile): ArrayList<CellInfo> = ArrayList<CellInfo>().apply {
        try {
            if (profile.describesCell) {
                add(fuck.location.xposed.cellar.info.Lte().cellInfo(profile))
            }
        } catch (t: Throwable) {
            XposedBridge.log("FL: [Cellar] CellInfo spoof failed, returning empty: $t")
        }
    }

    private fun matchedMethods(
        clazz: Class<*>,
        label: String,
        predicate: java.lang.reflect.Method.() -> Boolean,
    ): List<java.lang.reflect.Method> = findAllMethods(clazz, findSuper = true, predicate)
        .also {
            if (it.isEmpty()) {
                XposedBridge.log("FL: [Cellar] method unavailable: $label in ${clazz.name}")
            }
        }

    private fun allInterfaces(clazz: Class<*>): Set<Class<*>> {
        val found = linkedSetOf<Class<*>>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.interfaces.forEach { iface ->
                found += iface
                found += allInterfaces(iface)
            }
            current = current.superclass
        }
        return found
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun spoofedCellProfile(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        simIdentity: Boolean = false,
    ): Pair<String, Profile>? {
        param.args.filterIsInstance<String>().forEach { candidate ->
            val profile = if (simIdentity) {
                ConfigGateway.get().simSpoofFor(candidate)
            } else {
                ConfigGateway.get().cellSpoofFor(candidate)
            }
            if (profile != null) return candidate to profile
        }
        return null
    }
}
