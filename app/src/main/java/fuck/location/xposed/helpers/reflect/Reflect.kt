package fuck.location.xposed.helpers.reflect

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XCallback
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/*
 * Self-contained replacement for the handful of EzXHelper 0.6.1 utilities this
 * module used. Upstream rewrote its API twice (2.x, 3.x) and deleted the old
 * tags, so pinning to it made every toolchain bump a migration. The surface
 * here is deliberately identical to what the call sites already expect:
 * findAllMethods / findMethod / findField with the same `findSuper` default of
 * false, and hookMethod / hookBefore / hookAfter over a single Method or a
 * collection of them.
 */

// region member predicates

val Member.isStatic: Boolean
    get() = Modifier.isStatic(modifiers)

val Member.isPublic: Boolean
    get() = Modifier.isPublic(modifiers)

val Member.isNotPublic: Boolean
    get() = !Modifier.isPublic(modifiers)

val Member.isPrivate: Boolean
    get() = Modifier.isPrivate(modifiers)

// endregion

// region finders

/**
 * All declared methods of [clazz] matching [predicate]. When [findSuper] is set
 * the walk continues through the superclass chain, which is what you want for
 * framework classes that moved their binder entry points into a base class.
 * Returns an empty list when nothing matches — callers that need a hard failure
 * should use [findMethod].
 */
fun findAllMethods(
    clazz: Class<*>,
    findSuper: Boolean = false,
    predicate: Method.() -> Boolean
): List<Method> {
    val found = mutableListOf<Method>()
    var current: Class<*>? = clazz

    do {
        current!!.declaredMethods.filterTo(found) { it.predicate() }
        if (!findSuper) break
        current = current.superclass
    } while (current != null && current != Any::class.java)

    found.forEach { it.isAccessible = true }
    return found
}

fun findAllMethods(
    className: String,
    classLoader: ClassLoader,
    findSuper: Boolean = false,
    predicate: Method.() -> Boolean
): List<Method> = findAllMethods(classLoader.loadClass(className), findSuper, predicate)

/**
 * First declared method of [clazz] matching [predicate].
 * @throws NoSuchMethodException when nothing matches.
 */
fun findMethod(
    clazz: Class<*>,
    findSuper: Boolean = false,
    predicate: Method.() -> Boolean
): Method = findAllMethods(clazz, findSuper, predicate).firstOrNull()
    ?: throw NoSuchMethodException("No method matched in ${clazz.name} (findSuper=$findSuper)")

/**
 * First declared field of [clazz] matching [predicate], made accessible.
 * @throws NoSuchFieldException when nothing matches.
 */
fun findField(
    clazz: Class<*>,
    findSuper: Boolean = false,
    predicate: Field.() -> Boolean
): Field {
    var current: Class<*>? = clazz

    do {
        current!!.declaredFields.firstOrNull { it.predicate() }?.let {
            it.isAccessible = true
            return it
        }
        if (!findSuper) break
        current = current.superclass
    } while (current != null && current != Any::class.java)

    throw NoSuchFieldException("No field matched in ${clazz.name} (findSuper=$findSuper)")
}

// endregion

// region hooking

/** Mirrors EzXHelper's hook factory so `hookMethod { before {} after {} }` keeps working. */
class HookFactory(private val method: Method, private val priority: Int) {
    private var beforeAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null
    private var afterAction: ((XC_MethodHook.MethodHookParam) -> Unit)? = null

    fun before(action: (XC_MethodHook.MethodHookParam) -> Unit) {
        beforeAction = action
    }

    fun after(action: (XC_MethodHook.MethodHookParam) -> Unit) {
        afterAction = action
    }

    fun replace(action: (XC_MethodHook.MethodHookParam) -> Any?) {
        beforeAction = { param -> param.result = action(param) }
    }

    internal fun commit(): XC_MethodHook.Unhook {
        val callback = object : XC_MethodHook(priority) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                // A throwing hook would propagate into the framework caller, so
                // failures are contained and logged instead.
                try {
                    beforeAction?.invoke(param)
                } catch (e: Throwable) {
                    XposedBridge.log("FL: hook (before) on ${method.name} threw: $e")
                }
            }

            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    afterAction?.invoke(param)
                } catch (e: Throwable) {
                    XposedBridge.log("FL: hook (after) on ${method.name} threw: $e")
                }
            }
        }

        return XposedBridge.hookMethod(method, callback)
    }
}

fun Method.hookMethod(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    block: HookFactory.() -> Unit
): XC_MethodHook.Unhook = HookFactory(this, priority).apply(block).commit()

fun Iterable<Method>.hookMethod(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    block: HookFactory.() -> Unit
): List<XC_MethodHook.Unhook> = map { it.hookMethod(priority, block) }

fun Method.hookBefore(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    action: (XC_MethodHook.MethodHookParam) -> Unit
): XC_MethodHook.Unhook = hookMethod(priority) { before(action) }

fun Iterable<Method>.hookBefore(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    action: (XC_MethodHook.MethodHookParam) -> Unit
): List<XC_MethodHook.Unhook> = map { it.hookBefore(priority, action) }

fun Method.hookAfter(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    action: (XC_MethodHook.MethodHookParam) -> Unit
): XC_MethodHook.Unhook = hookMethod(priority) { after(action) }

fun Iterable<Method>.hookAfter(
    priority: Int = XCallback.PRIORITY_DEFAULT,
    action: (XC_MethodHook.MethodHookParam) -> Unit
): List<XC_MethodHook.Unhook> = map { it.hookAfter(priority, action) }

// endregion

// region misc

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

fun runOnMainThread(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action()
    else mainHandler.post { action() }
}

/**
 * Logs to the Xposed log when there is one.
 *
 * The same classes run inside the module's own app process, where the Xposed
 * API is only provided if the module is actually activated. Touching
 * XposedBridge there throws NoClassDefFoundError - an Error, not an Exception,
 * so ordinary catch blocks let it through - which used to take the settings UI
 * down on a phone without a working Xposed framework. Fall back to logcat.
 */
object Log {
    var tag: String = "FuckLocation"

    fun d(message: String) = write("D", message)
    fun i(message: String) = write("I", message)
    fun w(message: String) = write("W", message)
    fun e(message: String, throwable: Throwable? = null) =
        write("E", message + (throwable?.let { " $it" } ?: ""))

    private fun write(level: String, message: String) {
        try {
            XposedBridge.log("$tag/$level: $message")
        } catch (t: Throwable) {
            android.util.Log.d(tag, "$level: $message")
        }
    }
}

// endregion
