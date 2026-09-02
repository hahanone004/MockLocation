package mock.location.xposed.system

import android.annotation.SuppressLint
import de.robv.android.xposed.callbacks.XC_LoadPackage
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.Log
import mock.location.xposed.helpers.reflect.findAllMethods
import mock.location.xposed.helpers.reflect.hookAfter
import mock.location.xposed.helpers.reflect.isStatic
import java.lang.reflect.Modifier
import java.util.TimeZone

/**
 * The places a device describes itself that are not the framework answering a
 * question about the current app.
 *
 * Everything else this module does replaces an answer the system gives an app -
 * a fix, a cell, a locale in a Configuration. These are different: they are the
 * device's own settings, readable by anyone, and until now they went on telling
 * the truth beside every spoof that did not. A probe run against a Taiwanese
 * profile read a Taiwanese SIM, a Taiwanese cell, a Taoyuan position and a
 * zh-TW language - and, one line further down, a Simplified Chinese system
 * locale in Settings and in a system property.
 *
 * Two things are covered here.
 *
 *  - The language, wherever it is recorded rather than reported: the
 *    system_locales setting and persist.sys.locale. Both are what an app asks
 *    when it wants to know whether the language it was told is the device's
 *    real one, and both are cheap to ask.
 *  - The time zone, which nothing in this module had ever touched. It is the
 *    cheapest contradiction of all to find - TimeZone.getDefault() needs no
 *    permission whatsoever - and a profile claiming a Taiwanese SIM while the
 *    clock runs on another country's offset says out loud that something is
 *    substituting for it.
 *
 * What is deliberately not covered: a getprop subprocess. An app can run one
 * and read the property back around every hook here. Chasing that means
 * intercepting process creation, which is a far larger surface with far more
 * ways to break an app, for a check that is rarer than the two above by a wide
 * margin. It is a known gap rather than an oversight.
 */
class SystemSourcesHooker {

    /** Resolved once per id: getDefault sits under every date an app formats. */
    @Volatile
    private var cachedZone: TimeZone? = null

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookSystemSources(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        install("system properties", packageName) { hookSystemProperties(lpparam, packageName) }
        install("settings", packageName) { hookSettings(lpparam, packageName) }
        install("time zone", packageName) { hookTimeZone(lpparam, packageName) }
    }

    private fun install(what: String, packageName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("cannot hook $what in $packageName", t)
        }
    }

    /**
     * SystemProperties.get, for the two properties that record what the device
     * is set to.
     *
     * Only the Java entry point. It is what an app reaches through - directly
     * by reflection, or through any of the libraries that wrap it - and the
     * native call underneath is reached by the framework itself far more often
     * than by anybody asking about the device.
     */
    @ExperimentalStdlibApi
    private fun hookSystemProperties(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        val clazz = lpparam.classLoader.loadClass("android.os.SystemProperties")

        val methods = findAllMethods(clazz, findSuper = true) {
            name == "get" && isStatic && !Modifier.isAbstract(modifiers) &&
                parameterCount in 1..2 && parameterTypes[0] == String::class.java
        }
        if (methods.isEmpty()) {
            Log.w("no SystemProperties.get in $packageName")
            return
        }

        methods.hookAfter { param ->
            if (param.hasThrowable()) return@hookAfter
            val key = param.args.getOrNull(0) as? String ?: return@hookAfter

            when (key) {
                in LOCALE_PROPERTIES -> localeTag(packageName)?.let { param.result = it }
                TIME_ZONE_PROPERTY -> timeZoneId(packageName)?.let { param.result = it }
            }
        }

        Log.i("system properties held in $packageName")
    }

    /**
     * The system_locales setting, in whichever of the three tables an app looks.
     *
     * The key is matched among the arguments rather than at a fixed index: the
     * public getString takes it second, and the hidden per-user overload the
     * framework actually calls takes a user id after it.
     */
    @ExperimentalStdlibApi
    private fun hookSettings(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        var held = 0
        SETTINGS_CLASSES.forEach { className ->
            val clazz = try {
                lpparam.classLoader.loadClass(className)
            } catch (t: Throwable) {
                Log.d { "no $className in $packageName: $t" }
                return@forEach
            }

            val methods = findAllMethods(clazz, findSuper = true) {
                (name == "getString" || name == "getStringForUser") && isStatic &&
                    !Modifier.isAbstract(modifiers)
            }
            if (methods.isEmpty()) return@forEach

            try {
                methods.hookAfter { param ->
                    if (param.hasThrowable()) return@hookAfter
                    val asked = param.args.filterIsInstance<String>()
                    if (asked.none { it == SYSTEM_LOCALES }) return@hookAfter

                    localeTag(packageName)?.let { param.result = it }
                }
            } catch (t: Throwable) {
                Log.w("cannot hold $className.getString in $packageName: $t")
                return@forEach
            }
            held++
        }

        if (held == 0) {
            Log.w("no settings entry point in $packageName")
            return
        }
        Log.i("settings held in $packageName")
    }

    /**
     * TimeZone.getDefault, which is what every date an app formats runs through
     * - Calendar, SimpleDateFormat and ZoneId.systemDefault() all end here.
     *
     * getDefaultRef is held alongside it because parts of the platform take the
     * uncloned default through that door instead.
     */
    @ExperimentalStdlibApi
    private fun hookTimeZone(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        val clazz = lpparam.classLoader.loadClass("java.util.TimeZone")

        val methods = findAllMethods(clazz, findSuper = true) {
            (name == "getDefault" || name == "getDefaultRef") && isStatic &&
                !Modifier.isAbstract(modifiers) && parameterCount == 0
        }
        if (methods.isEmpty()) {
            Log.w("no TimeZone.getDefault in $packageName")
            return
        }

        methods.hookAfter { param ->
            if (param.hasThrowable()) return@hookAfter
            val id = timeZoneId(packageName) ?: return@hookAfter

            param.result = zoneFor(id)
        }

        Log.i("time zone held in $packageName")
    }

    /**
     * A held zone rather than a fresh one per call. getDefault is called on
     * every date formatted anywhere in the process, and TimeZone.getTimeZone
     * parses and clones each time it is asked.
     */
    private fun zoneFor(id: String): TimeZone {
        cachedZone?.takeIf { it.id == id }?.let { return it }

        return TimeZone.getTimeZone(id).also { cachedZone = it }
    }

    @ExperimentalStdlibApi
    private fun localeTag(packageName: String): String? =
        ConfigGateway.get().localeSpoofFor(packageName)?.localeTag?.takeIf { it.isNotBlank() }

    @ExperimentalStdlibApi
    private fun timeZoneId(packageName: String): String? =
        ConfigGateway.get().timeZoneSpoofFor(packageName)?.timeZoneId?.takeIf { it.isNotBlank() }

    private companion object {

        /** What the device is set to, and what it shipped set to. */
        val LOCALE_PROPERTIES = setOf("persist.sys.locale", "ro.product.locale")
        const val TIME_ZONE_PROPERTY = "persist.sys.timezone"

        const val SYSTEM_LOCALES = "system_locales"

        val SETTINGS_CLASSES = listOf(
            "android.provider.Settings\$System",
            "android.provider.Settings\$Secure",
            "android.provider.Settings\$Global",
        )
    }
}
