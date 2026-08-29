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
import de.robv.android.xposed.callbacks.XC_LoadPackage
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
    }

    @ExperimentalStdlibApi
    @SuppressLint("PrivateApi")
    fun hookWillChangeBeEnabled(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = lpparam.classLoader.loadClass("com.android.server.am.ActivityManagerService")

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
    fun hookGetTagForIntentSender(lpparam: XC_LoadPackage.LoadPackageParam) {
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
                findAllMethods(lpparam.classLoader.loadClass(className), findSuper = true) {
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
    fun profileFor(packageName: String): Profile? =
        readProfileStore().profileFor(packageName.substringBefore(':'))

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

    @ExperimentalStdlibApi
    fun readProfileStore(): ProfileStore {
        val now = SystemClock.elapsedRealtime()
        cachedStore?.let { if (now - cachedAt < cacheMillis) return it }

        val json = try {
            universalAPICaller("None", 4) as String
        } catch (e: Exception) {
            Log.w("Failed to read profiles, falling back to defaults")
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

    fun setDataPath(){
        File("/data/system").list()?.forEach {  // Try to find the existing config
            if (it.equals("fuck_location_test")) {  // Migrate from older version
                val randomizedPath = "/data/system/fuck_location_${generateRandomAppendix()}"
                File("/data/system/$it").renameTo(File(randomizedPath))
                dataDir = randomizedPath
            } else if (it.startsWith("fuck_location")) {
                if (this::dataDir.isInitialized) File("/data/system/$it").deleteRecursively()
                else dataDir = "/data/system/$it"
            }
        }

        if (!this::dataDir.isInitialized) { // Not possible, we create a new config folder
            dataDir = "/data/system/fuck_location_${generateRandomAppendix()}"
        }
    }

    private fun generateRandomAppendix() : String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return List(16) { chars.random() }.joinToString("")
    }
}