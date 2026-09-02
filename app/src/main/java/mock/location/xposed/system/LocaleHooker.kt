package mock.location.xposed.system

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.Log
import mock.location.xposed.helpers.reflect.findAllMethods
import mock.location.xposed.helpers.reflect.findField
import mock.location.xposed.helpers.reflect.findMethod
import mock.location.xposed.helpers.reflect.hookAfter
import mock.location.xposed.helpers.reflect.hookBefore
import mock.location.xposed.helpers.reflect.isStatic
import java.lang.reflect.Modifier
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
 * Four places are held, and all of them are cold:
 *
 *  - The configuration ResourcesManager keeps and builds every later Resources
 *    out of. It is filled in before any hook here exists, so it is patched at
 *    attach rather than intercepted; without it an activity is handed the
 *    device's language however right the rest of the process is.
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

    /** The last language this process reported, for the same reason. */
    @Volatile
    private var lastSpoofedLocale: Locale? = null

    /**
     * Guards the lookup against itself. It crosses a binder, and what comes
     * back can drive another configuration change on this same thread.
     */
    private val readingAppLocales = ThreadLocal.withInitial { false }

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookLocale(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        // Three independent installs, and each one survives the others failing.
        // They used to be three calls in a row, which made them a chain: the
        // middle one threw on a method it could not hook and took the install
        // at attach down with it - the one that decides the language this
        // process starts with, and the only one that runs early enough for an
        // app that reads the language in attachBaseContext. What that looked
        // like from outside was a language spoof that had stopped working
        // entirely while getSystemLocales, installed first, went on answering
        // correctly.
        //
        // Attach goes first for the same reason: it is the one worth having if
        // only one of them can be had.
        install("the language at attach", packageName) { hookAttach(lpparam, packageName) }
        install("LocaleManager", packageName) { hookLocaleManager(lpparam, packageName) }
        install("system configuration", packageName) {
            hookSystemConfiguration(lpparam, packageName)
        }
    }

    private fun install(what: String, packageName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("cannot hook $what in $packageName", t)
        }
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
     * Every entry that matches is held, rather than the first of them. They are
     * not the same path tried at descending depth: the process-wide change
     * lands at ConfigurationController.handleConfigurationChanged, while an
     * activity's own - a rotation, a resize, entering split screen, a move to
     * another display, a recreate - runs
     * handleActivityConfigurationChanged -> updateResourcesForActivity and
     * never passes through it. Stopping at the first match therefore left the
     * whole activity path uncovered, and every rotation handed the activity's
     * resources, and its onConfigurationChanged, the device's real language.
     *
     * Every Configuration in the argument list is rewritten, not just the
     * first: on the activity path the one that decides the language is an
     * override configuration sitting behind other arguments.
     *
     * All of these are hidden and their signatures move between releases, so
     * failing to match is a warning and not an exception: the install at attach
     * still stands, and the language is right until the first configuration
     * change.
     */
    @ExperimentalStdlibApi
    private fun hookSystemConfiguration(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        val entries = listOf(
            "android.app.ConfigurationController" to "handleConfigurationChanged",
            "android.app.ActivityThread" to "handleConfigurationChanged",
            "android.app.ActivityThread" to "handleActivityConfigurationChanged",
            "android.app.ResourcesManager" to "applyConfigurationToResources",
            "android.app.ResourcesManager" to "updateResourcesForActivity",
        )

        var held = 0
        entries.forEach { (className, methodName) ->
            val clazz = try {
                lpparam.classLoader.loadClass(className)
            } catch (t: Throwable) {
                Log.d { "no $className in $packageName: $t" }
                return@forEach
            }

            val methods = findAllMethods(clazz, findSuper = true) {
                name == methodName &&
                    // Walking the superclass chain finds the abstract
                    // declaration alongside the override that implements it -
                    // ActivityThread.handleConfigurationChanged is declared on
                    // ClientTransactionHandler - and hooking an abstract method
                    // throws.
                    !Modifier.isAbstract(modifiers) &&
                    parameterTypes.any { it == Configuration::class.java }
            }
            if (methods.isEmpty()) return@forEach

            // One entry point that will not take a hook is not a reason to
            // leave the others unheld.
            try {
                methods.hookBefore { param -> holdLocales(param, packageName) }
            } catch (t: Throwable) {
                Log.w("cannot hold $className.$methodName in $packageName: $t")
                return@forEach
            }

            held++
            // Which entry points took a hook is once-per-process lifecycle,
            // not tracing, and it is the first thing worth knowing when a
            // language is right in one context and wrong in another. A debug
            // line is compiled out of the build people actually install, which
            // is exactly the build where that question gets asked.
            Log.i("system configuration held at $className.$methodName in $packageName")
        }

        if (held == 0) {
            Log.w("no system configuration entry point in $packageName; " +
                "the language is installed at attach only")
        }
    }

    /**
     * Puts this process' language into every Configuration the call carries.
     *
     * A copy rather than an edit in place: the same Configuration is handed to
     * several Resources objects, and one of them is the system's.
     */
    @ExperimentalStdlibApi
    private fun holdLocales(param: XC_MethodHook.MethodHookParam, packageName: String) {
        val effective = effectiveLocales(packageName) ?: return

        param.args.forEachIndexed { index, argument ->
            val incoming = argument as? Configuration ?: return@forEachIndexed
            if (effective == incoming.locales) return@forEachIndexed

            param.args[index] = Configuration(incoming).apply { setLocales(effective) }
        }
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
        val lock = Any()
        attachMethods.hookBefore { param ->
            val context = param.args[0] as? Context ?: return@hookBefore

            synchronized(lock) {
                if (installed.get()) return@hookBefore

                ConfigGateway.get().setCustomContext(context)
                appContext = context
                // Only once the answer is in. Setting it first retired the
                // attempt on the one outcome worth repeating - the config
                // channel not answering yet - and left the process on the
                // device's real language until some later configuration
                // change, or for good in an app that reads the language in
                // attachBaseContext and keeps it.
                if (installLocale(packageName, context, localeListClass)) {
                    installed.set(true)
                }
            }
        }
    }

    /**
     * The language this process starts with.
     *
     * The system has already set its own default by this point - attach runs
     * after bind - so this replaces it rather than racing it, and does the same
     * for the app's resources so an app with no language of its own really does
     * render in the profile's.
     *
     * Returns whether the question is settled: either a language was installed,
     * or the framework said outright that this app has none to install. False
     * means only that nobody could be asked, and that it is worth asking again.
     */
    @ExperimentalStdlibApi
    @Suppress("DEPRECATION")
    private fun installLocale(
        packageName: String,
        context: Context,
        localeListClass: Class<*>,
    ): Boolean {
        val effective = awaitLocales(packageName)
            ?: return ConfigGateway.get().profileResolved(packageName)

        return try {
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

            // The app's Resources are not the only ones in the process.
            // Resources.getSystem() carries a configuration of its own, which
            // nothing here had touched, so an app reading its locales before
            // the first configuration change - the one that repairs it in
            // passing - read the device's real language straight off it.
            val system = Resources.getSystem()
            val systemConfiguration = Configuration(system.configuration).apply {
                setLocales(effective)
            }
            system.updateConfiguration(systemConfiguration, system.displayMetrics)

            holdResourcesBase(packageName, effective)

            Log.i("locales for $packageName reported as ${effective.toLanguageTags()}")
            true
        } catch (t: Throwable) {
            Log.e("could not install the default locale for $packageName", t)
            false
        }
    }

    /**
     * The configuration every Resources made from here on is built out of.
     *
     * ResourcesManager keeps the process' configuration and applies it when it
     * creates or updates a Resources object. It is handed that configuration
     * during handleBindApplication - which is before Xposed dispatches
     * handleLoadPackage, so before any hook in this class exists - and it keeps
     * the device's real locale there for the life of the process unless a
     * configuration change comes along to replace it.
     *
     * Nothing in this class could reach it. The hooks rewrite configurations on
     * their way past, and an activity's Resources are not made from a
     * configuration going past: they are made from this stored one, merged with
     * the activity's own override. So an app whose process default and whose
     * application Resources both said the profile's language handed its
     * activity the device's - and said it again after every rotation, because
     * the rebuild reads the same stored copy.
     *
     * Patched in place rather than through applyConfigurationToResources, which
     * decides whether to do anything by comparing sequence numbers and would
     * treat this as an old configuration and ignore it.
     */
    @SuppressLint("PrivateApi")
    private fun holdResourcesBase(packageName: String, effective: LocaleList) {
        try {
            val clazz = Class.forName("android.app.ResourcesManager")
            val manager = findMethod(clazz) { name == "getInstance" && isStatic }
                .invoke(null) ?: return

            val field = runCatching {
                findField(clazz, findSuper = true) { name == "mResConfiguration" }
            }.getOrElse {
                findField(clazz, findSuper = true) { type == Configuration::class.java }
            }
            val stored = field.get(manager) as? Configuration ?: return

            // ResourcesManager guards this object with its own monitor, and an
            // activity can be launching on another thread.
            synchronized(manager) { stored.setLocales(effective) }

            Log.i("resources base for $packageName set to ${effective.toLanguageTags()}")
        } catch (t: Throwable) {
            // Not fatal: the process default and the application's own
            // resources are already installed by the time this runs, and a
            // configuration change repairs the rest.
            Log.w("cannot hold the resources base for $packageName: $t")
        }
    }

    /**
     * The locales to install, waiting briefly for the config channel if it is
     * not answering yet.
     *
     * Null once the framework has said this app has no language spoof, and
     * after the last attempt when it never said anything at all.
     *
     * This blocks Application.attach, so it is bounded and it only ever runs at
     * all when a query goes unanswered - the ordinary case returns on the first
     * pass. Paying it is the point: attach is the last moment before the app's
     * own attachBaseContext reads the language, and an app that caches it there
     * never asks a second time. The wait is a little longer than the gateway
     * holds an unresolved answer for, so each pass is a fresh query rather than
     * the same cached failure read three times.
     */
    @ExperimentalStdlibApi
    private fun awaitLocales(packageName: String): LocaleList? {
        repeat(INSTALL_ATTEMPTS) { attempt ->
            effectiveLocales(packageName)?.let { return it }
            if (ConfigGateway.get().profileResolved(packageName)) return null

            if (attempt < INSTALL_ATTEMPTS - 1) {
                Log.d { "no profile answer for $packageName yet; asking again" }
                SystemClock.sleep(INSTALL_RETRY_MILLIS)
            }
        }

        Log.w("no profile answer for $packageName at attach; " +
            "the language follows the first configuration change")
        return null
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

    /**
     * The profile's language, or null to leave the caller alone.
     *
     * A profile lookup answers null both for "this app has no language spoof"
     * and for "the config channel could not be reached", and the two were
     * treated alike. That is what let a single unanswered query in the middle
     * of a session pass the device's real configuration through to the app -
     * and the system sets the process default from that same configuration, so
     * one miss switched the whole process' language over. An unanswered query
     * now holds the last language this process reported instead; only a
     * definite answer from the framework drops the spoof.
     */
    @ExperimentalStdlibApi
    private fun spoofedLocale(packageName: String): Locale? {
        val gateway = ConfigGateway.get()
        val profile = gateway.localeSpoofFor(packageName)
            ?: return if (gateway.profileResolved(packageName)) null else lastSpoofedLocale

        val locale = Locale.forLanguageTag(profile.localeTag)
        // forLanguageTag answers the root locale for anything it cannot parse,
        // and reporting "" as the language is a tell in itself.
        if (locale.language.isEmpty()) return null

        lastSpoofedLocale = locale
        return locale
    }

    private companion object {
        /** Passes over the config channel at attach, and the gap between them. */
        const val INSTALL_ATTEMPTS = 3
        const val INSTALL_RETRY_MILLIS = 300L
    }
}
