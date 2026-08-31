package mock.location.xposed.system

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import de.robv.android.xposed.callbacks.XC_LoadPackage
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.Log
import mock.location.xposed.helpers.reflect.findAllMethods
import mock.location.xposed.helpers.reflect.findMethod
import mock.location.xposed.helpers.reflect.hookAfter
import mock.location.xposed.helpers.reflect.hookBefore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The system language, as the app sees it.
 *
 * The spoof covers what the system tells the app, and nothing else. An app that
 * picks a language of its own goes on rendering in it: that is a decision it is
 * entitled to make about itself, and one the user may well have made on
 * purpose. So the shape to aim for is the one Android already uses,
 *
 *     [the app's languages..., the system's language]
 *
 * with only the tail replaced. Flattening it to a single spoofed locale would
 * render the app in its chosen language while dates, numbers and every library
 * reading Locale.getDefault() spoke another - and would quietly undo an in-app
 * language picker besides.
 *
 * Three places are held, and all of them are cold:
 *
 *  - The configuration the system hands this process, at bind time and on every
 *    later change. This is the only place a locale is rewritten. What the app
 *    does to its own resources afterwards - updateConfiguration,
 *    createConfigurationContext, a ContextWrapper of its own - passes through
 *    untouched, which is what keeps the older way of switching language
 *    working. Locale.getDefault() follows from this, and is allowed to become
 *    the app's language: it is the process' effective default, not a statement
 *    about the device.
 *  - LocaleManager.getSystemLocales, which is the one API that asks about the
 *    device outright, ignoring the app's own language by design. It always
 *    answers with the profile's language.
 *  - LocaleManager.setApplicationLocales, only to hear what the app chose.
 */
class LocaleHooker {

    /** Set at attach, for the LocaleManager lookup behind [applicationLocales]. */
    @Volatile
    private var appContext: Context? = null

    /** The last answer that lookup gave, for when it cannot be made again. */
    @Volatile
    private var lastKnownAppLocales: LocaleList? = null

    /**
     * Guards the lookup against itself. It crosses a binder, and what comes
     * back can drive another configuration change on this same thread.
     */
    private val readingAppLocales = ThreadLocal.withInitial { false }

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookLocale(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        hookLocaleManager(lpparam, packageName)
        hookSystemConfiguration(lpparam, packageName)
        hookAttach(lpparam, packageName)
    }

    /**
     * The configuration on its way into this process, rewritten where it
     * arrives.
     *
     * Resources.updateConfiguration is not that place. It is the old public
     * entry point, so what it mostly catches is the app updating its own
     * resources - precisely the call that has to be left alone - while the
     * system's own path runs ConfigurationController ->
     * ResourcesManager.applyConfigurationToResources ->
     * ResourcesImpl.updateConfiguration and need not pass through it at all.
     *
     * The three are tried in order and the first that matches wins, each being
     * further from the system and closer to the resources than the last. All
     * are hidden and their signatures move between releases, so failing to
     * match is a warning and not an exception: the install at attach still
     * stands, and the language is right until the first configuration change.
     */
    @ExperimentalStdlibApi
    private fun hookSystemConfiguration(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        val entries = listOf(
            "android.app.ConfigurationController" to "handleConfigurationChanged",
            "android.app.ActivityThread" to "handleConfigurationChanged",
            "android.app.ResourcesManager" to "applyConfigurationToResources",
        )

        entries.forEach { (className, methodName) ->
            val clazz = try {
                lpparam.classLoader.loadClass(className)
            } catch (t: Throwable) {
                Log.d { "no $className in $packageName: $t" }
                return@forEach
            }

            val methods = findAllMethods(clazz, findSuper = true) {
                name == methodName && parameterCount >= 1 &&
                    parameterTypes[0] == Configuration::class.java
            }
            if (methods.isEmpty()) return@forEach

            methods.hookBefore { param ->
                val incoming = param.args.getOrNull(0) as? Configuration ?: return@hookBefore
                val effective = effectiveLocales(packageName) ?: return@hookBefore
                if (effective == incoming.locales) return@hookBefore

                // A copy rather than an edit in place: the same Configuration
                // is handed to several Resources objects, and one of them is
                // the system's.
                param.args[0] = Configuration(incoming).apply { setLocales(effective) }
            }

            Log.d { "system configuration held at $className.$methodName" }
            return
        }

        Log.w("no system configuration entry point in $packageName; " +
            "the language is installed at attach only")
    }

    /**
     * The API that asks about the device outright.
     *
     * getSystemLocales exists to see past an app's own language, so leaving it
     * alone would hand the real one to anybody who asked the question directly.
     * setApplicationLocales is held for the opposite reason: it is the app
     * announcing its choice, and hearing it first-hand covers the moment before
     * the system has finished recording it.
     */
    @ExperimentalStdlibApi
    private fun hookLocaleManager(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        val managerClass = try {
            lpparam.classLoader.loadClass("android.app.LocaleManager")
        } catch (t: Throwable) {
            Log.w("no LocaleManager in $packageName: $t")
            return
        }

        val queries = findAllMethods(managerClass, findSuper = true) {
            name == "getSystemLocales"
        }
        if (queries.isEmpty()) {
            Log.w("no LocaleManager.getSystemLocales in $packageName")
        }
        queries.hookAfter { param ->
            if (param.hasThrowable()) return@hookAfter
            val spoofed = spoofedLocale(packageName) ?: return@hookAfter

            param.result = LocaleList(spoofed)
        }

        findAllMethods(managerClass, findSuper = true) {
            name == "setApplicationLocales"
        }.hookBefore { param ->
            // The hidden overload names the package first; either way the
            // locales are the only LocaleList in the argument list.
            val chosen = param.args.filterIsInstance<LocaleList>().firstOrNull()
                ?: return@hookBefore

            lastKnownAppLocales = chosen
        }
    }

    /*
     * Vector can dispatch handleLoadPackage before ActivityThread has made the
     * Application. Reading the profile there has no Context with which to make
     * the binder call, so the old eager installation saw the empty fallback and
     * permanently missed the process' locale setup. Application.attach is the
     * first point with a guaranteed Context. Install before its original body:
     * attach() invokes the app's attachBaseContext(), and apps such as TikTok
     * read and cache the language there on their very first launch.
     */
    @ExperimentalStdlibApi
    private fun hookAttach(lpparam: XC_LoadPackage.LoadPackageParam, packageName: String) {
        val applicationClass = lpparam.classLoader.loadClass("android.app.Application")
        val localeListClass = lpparam.classLoader.loadClass("android.os.LocaleList")
        val installed = AtomicBoolean(false)

        val attachMethods = findAllMethods(applicationClass, findSuper = true) {
            name == "attach" && parameterCount == 1 &&
                parameterTypes[0] == Context::class.java
        }
        if (attachMethods.isEmpty()) {
            throw NoSuchMethodException("Application.attach(Context)")
        }
        attachMethods.hookBefore { param ->
            val context = param.args[0] as? Context ?: return@hookBefore
            if (!installed.compareAndSet(false, true)) return@hookBefore

            ConfigGateway.get().setCustomContext(context)
            appContext = context
            installLocale(packageName, context, localeListClass)
        }
    }

    /**
     * The language this process starts with.
     *
     * The system has already set its own default by this point - attach runs
     * after bind - so this replaces it rather than racing it, and does the same
     * for the app's resources so an app with no language of its own really does
     * render in the profile's.
     */
    @ExperimentalStdlibApi
    @Suppress("DEPRECATION")
    private fun installLocale(
        packageName: String,
        context: Context,
        localeListClass: Class<*>,
    ) {
        val effective = effectiveLocales(packageName) ?: return

        try {
            // The one-argument overload makes the first entry the default,
            // which is the app's own language when it has one.
            findMethod(localeListClass) {
                name == "setDefault" && parameterCount == 1
            }.invoke(null, effective)

            val resources = context.resources
            val configuration = Configuration(resources.configuration).apply {
                setLocales(effective)
            }
            resources.updateConfiguration(configuration, resources.displayMetrics)

            Log.i("locales for $packageName reported as ${effective.toLanguageTags()}")
        } catch (t: Throwable) {
            Log.e("could not install the default locale for $packageName", t)
        }
    }

    /**
     * What this process should be reporting: the app's own languages, then the
     * profile's in place of the device's.
     *
     * Built rather than edited. Replacing the device's entries where they
     * appear would leave nothing to replace in the case where the app picked
     * the language the device is already set to - the merged list holds it once,
     * as the app's - and the profile's language would then never appear at all.
     *
     * Null when this app has no language spoof, which is the signal to leave
     * the call alone.
     */
    @ExperimentalStdlibApi
    private fun effectiveLocales(packageName: String): LocaleList? {
        val spoofed = spoofedLocale(packageName) ?: return null

        val merged = ArrayList<Locale>()
        applicationLocales()?.let { chosen ->
            for (index in 0 until chosen.size()) merged.add(chosen[index])
        }
        if (spoofed !in merged) merged.add(spoofed)

        return LocaleList(*merged.toTypedArray())
    }

    /**
     * The languages this app has chosen for itself, empty when it has chosen
     * none, or null when the question cannot be put.
     *
     * Asked afresh every time rather than held: an app may change its language
     * at any point, a stale answer would read its new one as the device's and
     * overwrite it, and the only callers are configuration changes - a cold
     * path where one binder round trip is affordable.
     */
    private fun applicationLocales(): LocaleList? {
        val context = appContext ?: return lastKnownAppLocales
        if (readingAppLocales.get() == true) return lastKnownAppLocales

        readingAppLocales.set(true)
        return try {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.also { lastKnownAppLocales = it }
                ?: lastKnownAppLocales
        } catch (t: Throwable) {
            Log.d { "cannot read the application locales for ${context.packageName}: $t" }
            lastKnownAppLocales
        } finally {
            readingAppLocales.set(false)
        }
    }

    @ExperimentalStdlibApi
    private fun spoofedLocale(packageName: String): Locale? {
        val profile = ConfigGateway.get().localeSpoofFor(packageName) ?: return null

        val locale = Locale.forLanguageTag(profile.localeTag)
        // forLanguageTag answers the root locale for anything it cannot parse,
        // and reporting "" as the language is a tell in itself.
        if (locale.language.isEmpty()) return null

        return locale
    }
}
