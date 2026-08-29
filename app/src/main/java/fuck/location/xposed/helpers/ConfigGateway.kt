package fuck.location.xposed.helpers

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AndroidAppHelper
import android.content.Context
import android.os.SystemClock
import fuck.location.xposed.helpers.reflect.*
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import fuck.location.R
import fuck.location.app.ui.models.LegacyFakeLocation
import fuck.location.app.ui.models.Profile
import fuck.location.app.ui.models.ProfileStore
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileNotFoundException
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.lang.reflect.Field
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

    // Every ProfileStore field has a default, so this parses into a usable config
    private val EMPTY_CONFIG = "{}"

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
    private var cachedStore: ProfileStore? = null
    private var cachedAt = 0L

    /** Last logged resolution per package; hooks resolve from many threads. */
    private val announced = ConcurrentHashMap<String, String>()

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
        // TODO: Memory leak
        private var instance: ConfigGateway? = null
            get() {
                if (field == null) {
                    field = ConfigGateway()
                }
                return field
            }

        fun get(): ConfigGateway {
            return instance!!
        }

        const val SYSTEM_DIR = "/data/system"
        const val CONFIG_DIR_PREFIX = "fuck_location"
        const val LEGACY_CONFIG_DIR = "fuck_location_test"

        /** Keys that only a pre-profile config carries. */
        private val LEGACY_KEYS =
            setOf("x", "y", "offset", "eci", "pci", "tac", "earfcn", "bandwidth")
    }

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookWillChangeBeEnabled(classLoader: ClassLoader) {
        val clazz = classLoader.loadClass("com.android.server.am.ActivityManagerService")

        val methods = findAllMethods(clazz, findSuper = true) {
            name == "setProcessMemoryTrimLevel" && isPublic
        }.takeIf { it.isNotEmpty() }
            ?: throw NoSuchMethodException("setProcessMemoryTrimLevel not found in ${clazz.name}")

        methods.hookMethod {
            before { param ->
                if (param.args[1] == magicNumber && param.args[2] == 3) {
                    writeConfigInternal(param)
                    return@before
                }
            }
        }
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
                    ?.also { XposedBridge.log("FL: config read channel bound to $className") }
            }.getOrNull()
        } ?: throw NoSuchMethodException("getInstallerPackageName not found in any of $candidates")

        methods.hookMethod {
            before { param ->
                when {
                    param.args[0] == magicNumber.toString() -> {
                        readPackageListInternal(param)
                    }
                    param.args[0] == magicNumberLocation.toString() -> {
                        readConfigInternal(param)
                        return@before
                    }
                }
                return@before
            }
        }
    }


    @ExperimentalStdlibApi
    private fun readPackageListInternal(param: XC_MethodHook.MethodHookParam) {
        var jsonFile = File("$dataDir/whiteList.json")

        val json: String = try {
            jsonFile.readText()
        } catch (e: FileNotFoundException) {
            Log.d("FL: whiteList.json not found. Trying to refresh File holder")
            try {
                jsonFile = File("$dataDir/whiteList.json")
                jsonFile.readText()
                Log.d("FL: whiteList.json resumed.")
            } catch (e: FileNotFoundException) {
                Log.d("FL: not possible to refresh. Fallback to []")
            }
            "[]"
        }

        param.result = json
    }

    @ExperimentalStdlibApi
    private fun readConfigInternal(param: XC_MethodHook.MethodHookParam) {
        // Name kept from before profiles existed so upgrades find the config
        var jsonFile = File("$dataDir/fakeLocation.json")

        try {
            if (!jsonFile.exists()) {
                val jsonFileDirectory = File("$dataDir/")
                jsonFileDirectory.mkdirs()
            }

            val json: String = try {
                jsonFile.readText()
            } catch (e: FileNotFoundException) {
                Log.d("FL: fakeLocation.json not found. Trying to refresh File holder")
                try {
                    jsonFile = File("$dataDir/fakeLocation.json")
                    jsonFile.readText()
                    Log.d("FL: fakeLocation.json resumed.")
                } catch (e: FileNotFoundException) {
                    Log.d("FL: not possible to refresh. Falling back to defaults")
                }
                EMPTY_CONFIG
            }

            param.result = json
        } catch (e: Exception) {
            XposedBridge.log("FL: [debug !!] Fuck with exceptions! $e")

            param.result = EMPTY_CONFIG
        }
    }


    private fun writeConfigInternal(param: XC_MethodHook.MethodHookParam) {
        if (!this::dataDir.isInitialized) {
            Log.e("no config directory resolved, so the config cannot be saved")
            param.result = false
            return
        }

        val jsonFile = File("$dataDir/fakeLocation.json")

        if (!jsonFile.exists()) {
            val jsonFileDirectory = File("$dataDir/")
            jsonFileDirectory.mkdirs()
        }

        jsonFile.writeText(param.args[0] as String)

        param.result = false    // Block from calling real method
    }

    private fun universalAPICaller(string: String, action: Int): Any? {
        val magicContext: Context = try {
            AndroidAppHelper.currentApplication().applicationContext // Calling from xposed hook
        } catch (e: NoClassDefFoundError) {
            customContext   // Calling from normal code
        }

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
        val profile = readProfileStore().profileFor(app)

        announce(app, profile)
        return profile
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
            universalAPICaller("None", 2) as String
        } catch (e: Exception) {
            Log.w("Failed to read package list, falling back to []")
            "[]"
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
            readPackageList().orEmpty()
        } catch (e: Exception) {
            Log.w("No legacy whitelist to fold in: $e")
            emptyList()
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
            Log.w("Cannot read the config, so nothing will be spoofed: $e")
            EMPTY_CONFIG
        }

        val store = try {
            parseProfileStore(json)
        } catch (e: Exception) {
            // A malformed config must not take the module down with it: report
            // it and behave as if nothing were configured.
            Log.w("Config is unreadable, falling back to defaults: $e")
            ProfileStore()
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
    fun writeProfileStore(store: ProfileStore) {
        val jsonAdapter: JsonAdapter<ProfileStore> = moshi.adapter()

        val json: String = jsonAdapter.toJson(store)
        universalAPICaller(json, 3)

        // What was just written is by definition current, so the editor never
        // reads its own change back stale.
        remember(store, SystemClock.elapsedRealtime())
    }

    private fun remember(store: ProfileStore, at: Long) {
        cachedStore = store
        cachedAt = at
    }

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
    fun setDataPath() {
        if (this::dataDir.isInitialized) return

        val names = File(SYSTEM_DIR).list()
        if (names == null) {
            Log.w("cannot list $SYSTEM_DIR from this process, so the config directory is not " +
                "resolvable here; system_server will resolve it")
            return
        }

        // The earliest builds used a fixed name, which anything could look for.
        if (names.contains(LEGACY_CONFIG_DIR)) {
            val randomized = newConfigDirName()
            Log.i("migrating $LEGACY_CONFIG_DIR to $randomized")
            File(SYSTEM_DIR, LEGACY_CONFIG_DIR).renameTo(File(SYSTEM_DIR, randomized))
        }

        val existing = File(SYSTEM_DIR).list()
            .orEmpty()
            .filter { it.startsWith(CONFIG_DIR_PREFIX) }
            .sorted()

        // Sorted, so a device that somehow ended up with more than one keeps
        // choosing the same one rather than alternating between them. The
        // strays are left alone: one of them may be the config that matters,
        // and deleting the wrong one cannot be undone.
        if (existing.size > 1) {
            Log.w("more than one config directory in $SYSTEM_DIR: $existing")
        }

        dataDir = "$SYSTEM_DIR/${existing.firstOrNull() ?: newConfigDirName()}"
        Log.i("config directory: $dataDir")
    }

    private fun newConfigDirName() = "${CONFIG_DIR_PREFIX}_${generateRandomAppendix()}"

    private fun generateRandomAppendix() : String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return List(16) { chars.random() }.joinToString("")
    }
}
