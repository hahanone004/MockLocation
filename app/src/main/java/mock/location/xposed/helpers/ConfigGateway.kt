package mock.location.xposed.helpers

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.content.Context
import android.os.Binder
import android.os.SystemClock
import mock.location.BuildConfig
import mock.location.xposed.helpers.reflect.*
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.robv.android.xposed.XC_MethodHook
import mock.location.R
import mock.location.app.ui.models.LegacyFakeLocation
import mock.location.app.ui.models.Profile
import mock.location.app.ui.models.ProfileStore
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileOutputStream
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * This hook acts as a gateway from phone to framework
 * in order to read the config file
 */

class ConfigGateway private constructor() {
    // Magic number to identify whether this call is from our module
    private val magicNumber = -114514
    private val magicNumberLocation = -191931
    private val profileQueryPrefix = "$magicNumberLocation:"
    private val profileReadError = "__FL_PROFILE_READ_ERROR__"

    // Every ProfileStore field has a default, so this parses into a usable config
    private val EMPTY_CONFIG = "{}"
    private val EMPTY_PACKAGE_LIST = "[]"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private lateinit var dataDir: String
    private lateinit var customContext: Context

    /*
     * Reading the config is a binder round trip, and the SIM hooks sit on calls
     * an app is free to make in a tight loop, so the result is held for a
     * moment. Nothing goes stale for long: the editor writes through this same
     * object and refreshes the copy as it does, and a hook in another process
     * picks a change up within the window.
     */
    private val cacheMillis = 2_000L

    /**
     * A query that could not be made is retried far sooner than an answer is
     * refreshed: whatever stopped it - no Context yet, the framework half
     * still starting - usually clears in well under a second, and until it
     * does the hooks are substituting nothing.
     */
    private val unresolvedCacheMillis = 250L
    @Volatile private var cachedStore: ProfileStore? = null
    @Volatile private var cachedAt = 0L

    /** Last logged resolution per package; hooks resolve from many threads. */
    private val announced = ConcurrentHashMap<String, String>()
    /**
     * [answered] separates a real answer from the framework side - including a
     * deliberate "this app has no profile" - from not having been able to ask.
     * The two used to be the same null, and a failure was then held for the
     * full window like any other answer.
     */
    private data class CachedProfile(
        val profile: Profile?,
        val at: Long,
        val answered: Boolean,
    )

    /** The outcome of one query over the config channel. */
    private data class Resolution(val profile: Profile?, val answered: Boolean)
    private val cachedProfiles = ConcurrentHashMap<String, CachedProfile>()
    private val lastServedProfiles = ConcurrentHashMap<String, String>()

    /* For getting started in framework. In default, it judges whether a
     * packageName is in whiteList.json or not.
     *
     * param.args[2] determines what this function will actually do:
     * 3: input: jsonString; output: void (writeProfileStore)
     *
     * Reads come back through getInstallerPackageName instead, keyed by magic
     * number: the module's own one for the legacy whitelist, and
     * magicNumberLocation for the profile store.
     */

    companion object {
        /*
         * Every hook resolves the gateway through get(), and in system_server
         * that happens on many binder threads at once. A null-check-then-assign
         * getter could hand two threads two different objects, one of which
         * then loses the race to the field - and since dataDir, the caches and
         * the announced map all hang off the instance, a thread left holding
         * the loser read the config through an object whose dataDir had never
         * been set. lazy resolves exactly once, for every caller.
         */
        private val instance: ConfigGateway by lazy { ConfigGateway() }

        fun get(): ConfigGateway = instance

        const val SYSTEM_DIR = "/data/system"
        const val CONFIG_DIR_PREFIX = "mock_location"
        const val ROOT_UID = 0
        const val SYSTEM_UID = 1_000
        const val PHONE_UID = 1_001

        /** Keys that only a pre-profile config carries. */
        private val LEGACY_KEYS =
            setOf("x", "y", "offset", "eci", "pci", "tac", "earfcn", "bandwidth")
        private val hookedWriteMethods = ConcurrentHashMap.newKeySet<Method>()
        private val hookedReadMethods = ConcurrentHashMap.newKeySet<Method>()
    }

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookWillChangeBeEnabled(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.am.ActivityManagerService")

        val methods = findAllMethods(clazz, findSuper = true) {
            name == "setProcessMemoryTrimLevel" && isPublic && parameterCount == 3 &&
                parameterTypes[0] == String::class.java &&
                parameterTypes[1] == Int::class.javaPrimitiveType &&
                parameterTypes[2] == Int::class.javaPrimitiveType
        }.takeIf { it.isNotEmpty() }
            ?: throw NoSuchMethodException("setProcessMemoryTrimLevel not found in ${clazz.name}")

        var failure: Throwable? = null
        methods.filter { hookedWriteMethods.add(it) }.forEach { method ->
            try {
                method.hookMethod {
                    before { param ->
                        if (param.args[1] == magicNumber && param.args[2] == 3) {
                            if (!callerOwnsPackage(BuildConfig.APPLICATION_ID)) {
                                Log.w("rejecting config write from uid ${Binder.getCallingUid()}")
                                param.result = false
                                return@before
                            }
                            writeConfigInternal(param)
                            return@before
                        }
                    }
                }
            } catch (t: Throwable) {
                hookedWriteMethods.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    @SuppressLint("PrivateApi")
    @ExperimentalStdlibApi
    fun hookGetTagForIntentSender(classLoader: ClassLoader) {
        // Android 13 split PackageManagerService apart: the binder entry points
        // now live in the inner class IPackageManagerImpl, which inherits this
        // method from IPackageManagerBase. Looking only at PackageManagerService's
        // own declared methods therefore matched nothing and silently took every
        // config read down with it, so try each layout and search superclasses.
        val candidates = listOf(
            "com.android.server.pm.IPackageManagerBase",                    // Android 13+
            "com.android.server.pm.PackageManagerService\$IPackageManagerImpl",
            "com.android.server.pm.PackageManagerService"                   // Android 12 and older
        )

        val methods = candidates.firstNotNullOfOrNull { className ->
            runCatching {
                findAllMethods(classLoader.loadClass(className), findSuper = true) {
                    name == "getInstallerPackageName" && parameterCount == 1
                }.takeIf { it.isNotEmpty() }
                    ?.also { Log.i("config read channel bound to $className") }
            }.getOrNull()
        } ?: throw NoSuchMethodException("getInstallerPackageName not found in any of $candidates")

        var failure: Throwable? = null
        methods.filter { hookedReadMethods.add(it) }.forEach { method ->
            try {
                method.hookMethod {
                    before { param ->
                        when {
                            param.args[0] == magicNumber.toString() -> {
                                if (!callerOwnsPackage(BuildConfig.APPLICATION_ID)) {
                                    param.result = null
                                    return@before
                                }
                                readPackageListInternal(param)
                            }
                            param.args[0] == magicNumberLocation.toString() -> {
                                if (!callerOwnsPackage(BuildConfig.APPLICATION_ID)) {
                                    param.result = null
                                    return@before
                                }
                                readConfigInternal(param)
                                return@before
                            }
                            (param.args[0] as? String)?.startsWith(profileQueryPrefix) == true -> {
                                val packageName =
                                    (param.args[0] as String).removePrefix(profileQueryPrefix)
                                if (!callerMayQuery(packageName)) {
                                    Log.w("rejecting profile query for $packageName from uid " +
                                        Binder.getCallingUid())
                                    param.result = null
                                    return@before
                                }
                                readProfileInternal(param, packageName)
                                return@before
                            }
                        }
                        return@before
                    }
                }
            } catch (t: Throwable) {
                hookedReadMethods.remove(method)
                failure = t
            }
        }
        failure?.let { throw it }
    }

    @ExperimentalStdlibApi
    @Synchronized
    private fun readProfileInternal(
        param: XC_MethodHook.MethodHookParam,
        packageName: String,
    ) {
        val json = try {
            readValidConfigJson()
        } catch (t: Throwable) {
            Log.w("cannot read profile for $packageName: $t")
            param.result = lastServedProfiles[packageName] ?: profileReadError
            return
        }

        param.result = try {
            val profile = parseProfileStore(json).profileFor(packageName.substringBefore(':'))
            val adapter: JsonAdapter<Profile> = moshi.adapter()
            (profile?.let(adapter::toJson) ?: "null").also {
                lastServedProfiles[packageName] = it
            }
        } catch (t: Throwable) {
            Log.w("cannot resolve profile for $packageName: $t")
            lastServedProfiles[packageName] ?: profileReadError
        }
    }

    private fun callerMayQuery(packageName: String): Boolean {
        val uid = Binder.getCallingUid()
        return uid == ROOT_UID || uid == SYSTEM_UID || uid == PHONE_UID ||
            packagesForUid(uid).any { it == packageName.substringBefore(':') }
    }

    private fun callerOwnsPackage(packageName: String): Boolean =
        packagesForUid(Binder.getCallingUid()).any { it == packageName }

    /**
     * Which packages share [uid], which is how a caller is matched to the
     * profile it is allowed to ask about.
     *
     * The public PackageManager goes first. AppGlobals used to, and inside
     * system_server it hands back PackageManagerService's IPackageManagerImpl,
     * which inherits getPackagesForUid from IPackageManagerBase - while
     * HiddenApiBypass only ever looks at a class's own declared methods. So
     * that route raised "Cannot find matching method" on every single profile
     * query, logged a warning, and fell through to the Context below anyway.
     * It stays as the fallback because Vector builds differ in which
     * hidden-API exemptions reach system_server.
     */
    private fun packagesForUid(uid: Int): List<String> {
        val context = systemContext() ?: if (this::customContext.isInitialized) {
            customContext
        } else null

        if (context != null) {
            try {
                val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
                if (packages.isNotEmpty()) return packages.toList()
            } catch (t: Throwable) {
                Log.w("system Context cannot resolve packages for uid $uid: $t")
            }
        }

        return try {
            val appGlobals = Class.forName("android.app.AppGlobals")
            val packageManager = HiddenApiBypass.invoke(
                appGlobals,
                null,
                "getPackageManager",
            ) ?: throw IllegalStateException("AppGlobals has no package manager")
            (HiddenApiBypass.invoke(
                packageManager.javaClass,
                packageManager,
                "getPackagesForUid",
                uid,
            ) as? Array<*>)?.filterIsInstance<String>().orEmpty()
        } catch (t: Throwable) {
            // Expected on any build where the impl class inherits the method,
            // so this is a debug line rather than a warning per query.
            Log.d { "AppGlobals cannot resolve packages for uid $uid: $t" }
            emptyList()
        }
    }

    private fun systemContext(): Context? = try {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = HiddenApiBypass.invoke(
            activityThread,
            null,
            "currentActivityThread",
        ) ?: return null
        HiddenApiBypass.invoke(
            thread.javaClass,
            thread,
            "getSystemContext",
        ) as? Context
    } catch (_: Throwable) {
        null
    }


    /**
     * The pre-profile whitelist, for the one-time migration that folds it into
     * assignments.
     *
     * The three answers are distinct on purpose. An empty list means there was
     * nothing to carry over; null means this process cannot tell, and the
     * migration is postponed rather than run on a guess - reading it as "empty"
     * would switch off spoofs the user had turned on. The retry this used to
     * make on a missing file re-read the identical path and could only fail the
     * same way; a directory that was never resolved is the case that actually
     * happens, and it used to throw straight out of the hook.
     */
    @ExperimentalStdlibApi
    @Synchronized
    private fun readPackageListInternal(param: XC_MethodHook.MethodHookParam) {
        if (!this::dataDir.isInitialized) {
            Log.w("no config directory resolved, so the legacy whitelist cannot be read here")
            param.result = null
            return
        }

        val jsonFile = File(dataDir, "whiteList.json")
        param.result = try {
            if (jsonFile.isFile) jsonFile.readText() else EMPTY_PACKAGE_LIST
        } catch (t: Throwable) {
            Log.w("cannot read the legacy whitelist: $t")
            null
        }
    }

    @ExperimentalStdlibApi
    @Synchronized
    private fun readConfigInternal(param: XC_MethodHook.MethodHookParam) {
        try {
            param.result = readValidConfigJson()
        } catch (e: Exception) {
            Log.e("both the primary and backup config are unreadable", e)
            param.result = EMPTY_CONFIG
        }
    }

    @ExperimentalStdlibApi
    private fun readValidConfigJson(): String {
        val primary = File(dataDir, "fakeLocation.json")
        if (primary.exists()) {
            try {
                return primary.readText().also { parseProfileStore(it) }
            } catch (primaryFailure: Throwable) {
                Log.w("primary config is unreadable, trying backup: $primaryFailure")
            }
        }

        val backup = File(dataDir, "fakeLocation.json.bak")
        if (!backup.exists()) return EMPTY_CONFIG
        return backup.readText().also {
            parseProfileStore(it)
            Log.w("using the last valid backup config")
        }
    }


    @Synchronized
    @OptIn(ExperimentalStdlibApi::class)
    private fun writeConfigInternal(param: XC_MethodHook.MethodHookParam) {
        if (!this::dataDir.isInitialized) {
            Log.e("no config directory resolved, so the config cannot be saved")
            param.result = false
            return
        }

        val directory = File(dataDir)
        if (!directory.exists() && !directory.mkdirs()) {
            Log.e("cannot create config directory $directory")
            param.result = false
            return
        }

        val jsonFile = File(directory, "fakeLocation.json")
        val json = param.args[0] as String
        try {
            parseProfileStore(json)
        } catch (t: Throwable) {
            Log.e("refusing to save an invalid config", t)
            param.result = false
            return
        }
        val temporary = File.createTempFile("fakeLocation.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (jsonFile.isFile) {
                jsonFile.copyTo(File(directory, "fakeLocation.json.bak"), overwrite = true)
            }
            if (!temporary.renameTo(jsonFile)) {
                throw IllegalStateException("cannot atomically replace $jsonFile")
            }
            // This method normally returns boolean. true is also the protocol
            // acknowledgement consumed by writeProfileStore in the app.
            param.result = true
        } catch (t: Throwable) {
            Log.e("cannot save config atomically", t)
            temporary.delete()
            param.result = false
        }
    }

    /**
     * Any Context that can make the binder call.
     *
     * The application's own is preferred, but it does not exist yet when a
     * hook fires early in a process's life - and the SIM hooks are installed
     * at load time, so a target app reads its operator fields inside that very
     * window. Answering "no profile" there meant the app saw the real SIM.
     * ActivityThread has a system Context from well before the Application is
     * made, and it is enough to reach the config channel.
     */
    private fun magicContext(): Context {
        try {
            AndroidAppHelper.currentApplication()?.applicationContext?.let { return it }
        } catch (_: NoClassDefFoundError) {
            // Not running under a framework at all; the fallbacks still apply.
        }
        if (this::customContext.isInitialized) return customContext

        return systemContext()
            ?: throw IllegalStateException("no Context is available yet")
    }

    private fun universalAPICaller(string: String, action: Int): Any? {
        val magicContext: Context = magicContext()

        val activityManager =
            magicContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val packageManager =
            magicContext.packageManager

        return when (action) {
            2 -> HiddenApiBypass.invoke(
                packageManager.javaClass,
                packageManager,
                "getInstallerPackageName", magicNumber.toString()
            )
            4 -> HiddenApiBypass.invoke(
                packageManager.javaClass,
                packageManager,
                "getInstallerPackageName", magicNumberLocation.toString()
            )
            5 -> HiddenApiBypass.invoke(
                packageManager.javaClass,
                packageManager,
                "getInstallerPackageName", profileQueryPrefix + string
            )
            else -> HiddenApiBypass.invoke(
                activityManager.javaClass,
                activityManager,
                "setProcessMemoryTrimLevel", string, magicNumber, action
            )
        }
    }

    /*
     * A hook needs two things to line up before it substitutes anything: a
     * profile is assigned to the app, and that profile has this particular spoof
     * switched on. Each of these returns the profile to work from, or null to
     * leave the caller alone.
     */

    @ExperimentalStdlibApi
    fun profileFor(packageName: String): Profile? {
        val app = packageName.substringBefore(':')
        val now = SystemClock.elapsedRealtime()
        val cached = cachedProfiles[app]
        val window = if (cached?.answered == false) unresolvedCacheMillis else cacheMillis

        if (cached != null && now - cached.at < window) {
            announce(app, cached.profile)
            return cached.profile
        }

        val resolution = readResolvedProfile(app)
        cachedProfiles[app] = CachedProfile(resolution.profile, now, resolution.answered)

        announce(app, resolution.profile)
        return resolution.profile
    }

    @ExperimentalStdlibApi
    private fun readResolvedProfile(packageName: String): Resolution {
        // Whatever the last good answer was, it is the best guess while the
        // channel is unusable - better than dropping the spoof mid-session.
        val lastKnown = Resolution(cachedProfiles[packageName]?.profile, answered = false)

        val json = try {
            universalAPICaller(packageName, 5) as? String
                ?: throw IllegalStateException("profile channel returned null")
        } catch (t: Throwable) {
            Log.w("Cannot resolve profile for $packageName: $t")
            return lastKnown
        }
        if (json == profileReadError) {
            Log.w("Profile query for $packageName failed; keeping the last valid copy")
            return lastKnown
        }
        if (json == "null") return Resolution(null, answered = true)

        return try {
            val adapter: JsonAdapter<Profile> = moshi.adapter()
            Resolution(adapter.fromJson(json), answered = true)
        } catch (t: Throwable) {
            Log.w("Profile for $packageName is unreadable: $t")
            lastKnown
        }
    }

    /**
     * Says which profile an app resolved to, and what that profile actually
     * has switched on.
     *
     * Without this the log is only ever half a sentence: every hook announces
     * that it was reached and then says nothing at all when it decides to
     * substitute nothing, so "not configured" and "broken" read identically.
     * Only printed when the answer for an app changes, which keeps it to a
     * line or two per app rather than one per location fix.
     */
    @ExperimentalStdlibApi
    private fun announce(packageName: String, profile: Profile?) {
        val decision = profile?.let {
            "${it.name.ifBlank { it.id }} (location=${it.locationEnabled}" +
                " cell=${it.cellEnabled} wifi=${it.wifiEnabled} sim=${it.simEnabled}" +
                " language=${it.localeEnabled})"
        } ?: "no profile"

        if (announced.put(packageName, decision) != decision) {
            Log.i("$packageName -> $decision")
        }
    }

    /**
     * The first argument of a hooked framework method that names an app [spoof]
     * applies to, together with the profile it resolved to.
     *
     * Which argument carries the calling package moves between Android releases
     * and is surrounded by feature and attribution tags that are strings too,
     * so every one of them is offered to [spoof] and the first that answers
     * wins. Null when no argument names an app this spoof applies to, which is
     * the signal to leave the call alone.
     */
    fun spoofedCaller(
        param: XC_MethodHook.MethodHookParam,
        spoof: (String) -> Profile?,
    ): Pair<String, Profile>? =
        param.args.orEmpty().filterIsInstance<String>().firstNotNullOfOrNull { candidate ->
            spoof(candidate)?.let { candidate to it }
        }

    @ExperimentalStdlibApi
    fun locationSpoofFor(packageName: String): Profile? =
        profileFor(packageName)?.takeIf { it.locationEnabled }

    @ExperimentalStdlibApi
    fun cellSpoofFor(packageName: String): Profile? =
        profileFor(packageName)?.takeIf { it.cellEnabled }

    @ExperimentalStdlibApi
    fun wifiSpoofFor(packageName: String): Profile? =
        profileFor(packageName)?.takeIf { it.wifiEnabled }

    @ExperimentalStdlibApi
    fun simSpoofFor(packageName: String): Profile? =
        profileFor(packageName)?.takeIf { it.simEnabled }

    /**
     * The language follows the SIM's country, so it only applies where the SIM
     * identity does - a device claiming a Taiwanese SIM while reporting a
     * German system language is a worse story than either alone.
     */
    @ExperimentalStdlibApi
    fun localeSpoofFor(packageName: String): Profile? =
        simSpoofFor(packageName)?.takeIf { it.localeEnabled && it.localeTag.isNotBlank() }

    @ExperimentalStdlibApi
    fun readPackageList(): List<String>? {
        val jsonAdapter: JsonAdapter<List<String>> = moshi.adapter()
        val json = try {
            universalAPICaller("None", 2) as? String ?: return null
        } catch (e: Exception) {
            Log.w("Failed to read package list: $e")
            return null
        }

        return jsonAdapter.fromJson(json)
    }

    /**
     * Brings a pre-version-4 config forward, once.
     *
     * Before profiles, an app was spoofed only if it was on a whitelist. Apps
     * now follow the default profile unless assigned otherwise, so migrating
     * has to keep both halves of that: the previously whitelisted apps go on
     * spoofing, and every other app goes on being left alone. That needs two
     * profiles - the old settings, switched on and assigned to those apps, and
     * a switched-off default for everyone else.
     *
     * Call from the app, not from a hook: it writes, and it needs resources.
     */
    @ExperimentalStdlibApi
    fun migrateWhitelistIfNeeded(context: Context) {
        val store = readProfileStore()
        if (store.configVersion >= ProfileStore.CURRENT_CONFIG_VERSION) return

        val whitelisted = try {
            readPackageList()
        } catch (e: Exception) {
            Log.w("Cannot read the legacy whitelist; migration postponed: $e")
            null
        } ?: run {
            Log.w("Cannot read the legacy whitelist; migration postponed")
            return
        }

        if (whitelisted.isEmpty()) {
            // Nothing was being spoofed, so keep the settings but leave every
            // switch off rather than turning the whole device on at once.
            Log.i("Migrating a config with an empty whitelist")

            writeProfileStore(
                store.copy(
                    profiles = store.profiles.map {
                        it.copy(locationEnabled = false, cellEnabled = false, wifiEnabled = false)
                    },
                    configVersion = ProfileStore.CURRENT_CONFIG_VERSION,
                )
            )
            return
        }

        Log.i("Folding ${whitelisted.size} whitelisted package(s) into assignments")

        val carried = (store.defaultProfile() ?: Profile()).copy(
            id = UUID.randomUUID().toString(),
            name = context.getString(R.string.profile_migrated_name),
            locationEnabled = true,
            cellEnabled = true,
            wifiEnabled = true,
        )
        val untouched = Profile(
            id = Profile.DEFAULT_ID,
            name = context.getString(R.string.profile_default_name),
        )

        writeProfileStore(
            ProfileStore(
                profiles = listOf(untouched, carried),
                defaultProfileId = untouched.id,
                assignments = whitelisted.associateWith { carried.id },
                configVersion = ProfileStore.CURRENT_CONFIG_VERSION,
            )
        )
    }

    /**
     * Whether the hooks inside system_server are answering.
     *
     * The module being enabled in LSPosed only proves it reached this app - the
     * spoofs live in system_server, which has to be in the module's scope
     * separately, and the difference is invisible from here otherwise. The
     * config travels over a framework method the module intercepts, so asking
     * for it is itself the test: an unhooked getInstallerPackageName has never
     * heard of this package name and answers null, or refuses outright.
     */
    fun isFrameworkReachable(): Boolean = try {
        universalAPICaller("None", 4) is String
    } catch (t: Throwable) {
        Log.w("Framework side is not answering: $t")
        false
    }

    @ExperimentalStdlibApi
    @Synchronized
    fun readProfileStore(): ProfileStore {
        val now = SystemClock.elapsedRealtime()
        cachedStore?.let { if (now - cachedAt < cacheMillis) return it }

        val json = try {
            // An unhooked getInstallerPackageName has never heard of this
            // package name and answers null, so a null here means the framework
            // half of the module is not running - which is worth naming in the
            // log, since every spoof silently does nothing from then on.
            universalAPICaller("None", 4) as? String
                ?: throw IllegalStateException(
                    "the framework side did not answer; is system_server in the module's scope?"
                )
        } catch (e: Exception) {
            Log.w("Cannot read the config: $e")
            return cachedStore ?: ProfileStore()
        }

        val store = try {
            parseProfileStore(json)
        } catch (e: Exception) {
            // Keep the last complete store on a transient or partial read.
            Log.w("Config is unreadable, keeping the last valid copy: $e")
            return cachedStore ?: ProfileStore()
        }

        return store.also { remember(it, now) }
    }

    /**
     * Reads either shape of config. Everything before profiles was one flat
     * object, and since every field of [ProfileStore] has a default, a legacy
     * config would otherwise parse "successfully" into an empty store and throw
     * the user's settings away. Look for the profiles key to tell them apart.
     */
    @ExperimentalStdlibApi
    private fun parseProfileStore(json: String): ProfileStore {
        val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter()
        val raw = mapAdapter.fromJson(json) ?: return ProfileStore()

        if (raw.containsKey("profiles")) {
            val storeAdapter: JsonAdapter<ProfileStore> = moshi.adapter()
            return storeAdapter.fromJson(json) ?: ProfileStore()
        }

        // No config at all reads as "{}", which is not a legacy config: taking
        // it for one built a profile out of nothing but defaults and switched
        // its spoofs on, so a device with no config yet reported 0, 0 to every
        // app the module could see.
        if (raw.keys.none { it in LEGACY_KEYS }) return ProfileStore()

        val legacyAdapter: JsonAdapter<LegacyFakeLocation> = moshi.adapter()
        val legacy = legacyAdapter.fromJson(json) ?: return ProfileStore()

        Log.i("Migrating a pre-profile config")
        return ProfileStore.fromLegacy(legacy)
    }


    @ExperimentalStdlibApi
    fun writeProfileStore(store: ProfileStore): Boolean {
        val jsonAdapter: JsonAdapter<ProfileStore> = moshi.adapter()

        val json: String = jsonAdapter.toJson(store)
        try {
            val acknowledged = universalAPICaller(json, 3) as? Boolean
            if (acknowledged != true) {
                Log.e("Cannot save the config; system_server rejected the write")
                return false
            }
        } catch (t: Throwable) {
            // If the system_server half is absent, this falls through to the
            // real privileged API and throws "Only shell can call it". A
            // settings action must report failure, not take the UI process
            // down with an InvocationTargetException.
            Log.e("Cannot save the config; the framework side is not answering", t)
            return false
        }

        // What was just written is by definition current, so the editor never
        // reads its own change back stale.
        remember(store, SystemClock.elapsedRealtime())
        return true
    }

    @Synchronized
    private fun remember(store: ProfileStore, at: Long) {
        cachedStore = store
        cachedAt = at
        cachedProfiles.clear()
    }

    @Synchronized
    fun setCustomContext(context: Context) {
        customContext = context

        // A hook can be installed before Application.attach, when
        // AndroidAppHelper.currentApplication() is still null.  A read made in
        // that window falls back to the empty store and used to keep that
        // answer cached even after a usable Context arrived.  Callers which
        // deliberately hand us a Context are crossing that lifecycle boundary,
        // so make the next read go back to system_server.
        cachedStore = null
        cachedAt = 0L
        cachedProfiles.clear()
    }

    // For converting CallerIdentity to packageName
    fun callerIdentityToPackageName(callerIdentity: Any): String {
        // Workaround for pure string
        if (callerIdentity is String) return callerIdentity

        /*
         * This used to compare Field.toString() against a per-release literal,
         * which meant any change to the modifiers, the annotations or the
         * enclosing class name silently stopped resolving the package. Match on
         * the field name and type instead: the class has moved between
         * com.android.server.location, com.android.server.LocationManagerService
         * and android.location.util.identity across P through 16, but the field
         * has only ever been a String called mPackageName or packageName.
         */
        val field = HiddenApiBypass.getInstanceFields(callerIdentity.javaClass)
            .filterIsInstance<Field>()
            .firstOrNull {
                (it.name == "mPackageName" || it.name == "packageName") &&
                    it.type == String::class.java
            }
            ?: throw IllegalArgumentException(
                "FL: no package name field on ${callerIdentity.javaClass.name}, please report to developer"
            )

        field.isAccessible = true
        return field.get(callerIdentity) as String
    }

    /**
     * Resolves the calling package from a hooked framework method's arguments.
     *
     * Prefers an explicit CallerIdentity when the ROM passes one. Otherwise it
     * takes the first String that follows a non-String argument, which is where
     * AOSP consistently puts packageName: it sits after the request or listener
     * parameter in getLastLocation, getCurrentLocation and the GNSS registration
     * calls alike, so this survives the parameters being reordered or added to.
     */
    fun callerPackageName(param: XC_MethodHook.MethodHookParam): String {
        val args: Array<Any?> = param.args ?: emptyArray()

        args.firstOrNull { it != null && it.javaClass.name.endsWith("CallerIdentity") }
            ?.let { return callerIdentityToPackageName(it) }

        var seenNonString = false
        for (arg in args) {
            if (arg is String) {
                if (seenNonString) return arg
            } else {
                seenNonString = true
            }
        }

        throw IllegalArgumentException("FL: cannot resolve caller package from ${param.method}")
    }

    /**
     * Finds the directory the config lives in, or names a new one.
     *
     * The name carries a random suffix so another app cannot simply look for
     * it, which means it has to be *discovered* by listing /data/system rather
     * than derived. Listing that needs the system server's access to it.
     *
     * Called from a process without that access - a real zygote, which is where
     * some frameworks run initZygote - the search silently came up empty and a
     * different random name was invented every boot, so each reboot started
     * from an empty config and left the previous one orphaned on disk. Now a
     * failed search leaves the path unset and says so, and system_server asks
     * again when it installs its hooks.
     */
    @Synchronized
    fun setDataPath() {
        if (this::dataDir.isInitialized) return

        val names = File(SYSTEM_DIR).list()
        if (names == null) {
            // Every app process lands here: only system_server can list that
            // directory, and it is the one that resolves the path. Nothing has
            // gone wrong, so this is not a warning - if system_server itself
            // cannot resolve it, requireDataPath throws and the hook step says
            // so under its own name.
            Log.d {
                "cannot list $SYSTEM_DIR from this process, so the config directory is not " +
                    "resolvable here; system_server will resolve it"
            }
            return
        }

        val existing = names.filter { it.startsWith(CONFIG_DIR_PREFIX) }.sorted()

        // Prefer a directory carrying the newest real config. Old Vector
        // startup races could leave several empty/random directories behind;
        // lexicographic order selected those instead of the user's data.
        if (existing.size > 1) {
            Log.w("more than one config directory in $SYSTEM_DIR: $existing")
        }

        val selected = existing
            .map { File(SYSTEM_DIR, it) }
            .maxWithOrNull(compareBy<File> {
                File(it, "fakeLocation.json").takeIf(File::isFile)?.lastModified() ?: -1L
            }.thenBy { it.name })
            ?.name
            ?: newConfigDirName()
        dataDir = "$SYSTEM_DIR/$selected"
        Log.i("config directory: $dataDir")
    }

    fun requireDataPath() {
        setDataPath()
        if (!this::dataDir.isInitialized) {
            throw IllegalStateException("config directory is not available in this process")
        }
    }

    private fun newConfigDirName() = "${CONFIG_DIR_PREFIX}_${generateRandomAppendix()}"

    private fun generateRandomAppendix() : String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return List(16) { chars.random() }.joinToString("")
    }
}
