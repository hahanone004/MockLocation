package fuck.location.xposed.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import de.robv.android.xposed.callbacks.XC_LoadPackage
import fuck.location.xposed.helpers.ConfigGateway
import fuck.location.xposed.helpers.reflect.Log
import fuck.location.xposed.helpers.reflect.findAllMethods
import fuck.location.xposed.helpers.reflect.findMethod
import fuck.location.xposed.helpers.reflect.hookAfter
import fuck.location.xposed.helpers.reflect.hookBefore
import fuck.location.xposed.helpers.reflect.isStatic
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The system language, as the app sees it.
 *
 * Nothing here crosses a binder either: a process is told its locale once, at
 * bind time, and reads it back out of its own statics from then on. So there
 * are two places worth holding, and both are cold:
 *
 *  - LocaleList.setDefault is the single point where a process installs its
 *    default, and it is what Locale.getDefault() ends up answering from.
 *  - Resources.updateConfiguration is where the resource configuration arrives,
 *    which decides both what getConfiguration().getLocales() reports and which
 *    translations the app actually loads.
 *
 * Holding only the first would leave the two disagreeing, which is a worse tell
 * than not spoofing at all. Holding the second means the app really does render
 * in that language - the spoof is not something the user can miss, which is why
 * it is a switch of its own.
 */
class LocaleHooker {

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookLocale(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val localeListClass = lpparam.classLoader.loadClass("android.os.LocaleList")
        val resourcesClass = lpparam.classLoader.loadClass("android.content.res.Resources")
        val applicationClass = lpparam.classLoader.loadClass("android.app.Application")
        val installed = AtomicBoolean(false)

        findAllMethods(localeListClass) {
            name == "setDefault" && isStatic
        }.hookBefore { param ->
            val spoofed = localeListFor(packageName) ?: return@hookBefore

            param.args[0] = spoofed
            // The second overload takes the index of the default within the
            // list; ours holds one locale, so it can only be the first.
            if (param.args.size > 1) param.args[1] = 0
        }

        findAllMethods(resourcesClass) {
            name == "updateConfiguration"
        }.hookBefore { param ->
            val spoofed = localeListFor(packageName) ?: return@hookBefore
            val original = param.args.getOrNull(0) as? Configuration ?: return@hookBefore

            // A copy rather than an edit in place: callers hand the same
            // Configuration to several Resources objects, and one of them is
            // the system's.
            param.args[0] = Configuration(original).apply { setLocales(spoofed) }
        }

        /*
         * Vector can dispatch handleLoadPackage before ActivityThread has made
         * the Application.  Reading the profile there has no Context with
         * which to make the binder call, so the old eager installation saw the
         * empty fallback and permanently missed the process' locale setup.
         * Application.attach is the first point with a guaranteed Context.
         * Apply both halves there: the Java default and the Resources
         * configuration which chooses the translations rendered by the app.
         */
        findAllMethods(applicationClass) {
            name == "attach" && parameterCount == 1 &&
                parameterTypes[0] == Context::class.java
        }.hookAfter { param ->
            if (!installed.compareAndSet(false, true)) return@hookAfter

            val context = param.args[0] as? Context ?: return@hookAfter
            ConfigGateway.get().setCustomContext(context)
            installLocale(packageName, context, localeListClass)
        }
    }

    @ExperimentalStdlibApi
    @Suppress("DEPRECATION")
    private fun installLocale(
        packageName: String,
        context: Context,
        localeListClass: Class<*>,
    ) {
        val spoofed = localeListFor(packageName) ?: return

        try {
            findMethod(localeListClass) {
                name == "setDefault" && parameterCount == 1
            }.invoke(null, spoofed)

            val resources = context.resources
            val configuration = Configuration(resources.configuration).apply {
                setLocales(spoofed)
            }
            resources.updateConfiguration(configuration, resources.displayMetrics)

            Log.i("system language for $packageName reported as ${spoofed[0].toLanguageTag()}")
        } catch (t: Throwable) {
            Log.e("could not install the default locale for $packageName", t)
        }
    }

    @ExperimentalStdlibApi
    private fun localeListFor(packageName: String): LocaleList? {
        val profile = ConfigGateway.get().localeSpoofFor(packageName) ?: return null

        val locale = Locale.forLanguageTag(profile.localeTag)
        // forLanguageTag answers the root locale for anything it cannot parse,
        // and reporting "" as the language is a tell in itself.
        if (locale.language.isEmpty()) return null

        return LocaleList(locale)
    }
}
