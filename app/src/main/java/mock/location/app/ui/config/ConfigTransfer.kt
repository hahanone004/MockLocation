package mock.location.app.ui.config

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import mock.location.app.ui.models.Profile
import mock.location.app.ui.models.ProfileStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The whole configuration as a file the user owns: every profile, which one is
 * the default, and each app assignment.
 *
 * The config itself lives behind system_server and is written to a directory
 * whose name is randomised, so it survives neither a reflash nor a move to
 * another phone, and there is no other way to hand a working profile to
 * someone else. This is that way out.
 *
 * The file is a JSON envelope around the store rather than the store on its
 * own. The envelope names the format, so a file picked by mistake is refused
 * instead of parsing "successfully" into an empty configuration - every field
 * of [ProfileStore] has a default, which is what makes an unrelated JSON
 * document look like a valid but empty config. It also carries the version the
 * writer used, so a file from a newer build is refused rather than silently
 * losing the fields this build does not know about.
 *
 * A bare store is still accepted on the way in: it is what a hand-edited file
 * or one copied straight out of the module's own config directory looks like,
 * and it is unambiguous as long as it names its profiles.
 */
@ExperimentalStdlibApi
object ConfigTransfer {

    /** What a picker should create and offer to open. */
    const val MIME_TYPE = "application/json"

    /**
     * Some file managers hand a .json file out as octet-stream or plain text,
     * and a picker limited to [MIME_TYPE] then greys out the file the user is
     * looking straight at.
     */
    val OPENABLE_TYPES = arrayOf(MIME_TYPE, "text/plain", "application/octet-stream")

    /** Marks the file as ours; see the class comment for why it is needed. */
    private const val FORMAT = "mock.location.config"

    /**
     * A configuration is a few kilobytes. The cap is here because the file
     * comes from a picker, where any file on the device is one tap away from
     * being read into memory as a string.
     */
    const val MAX_BYTES = 4 * 1024 * 1024

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val timestamp = DateTimeFormatter.ISO_INSTANT
    private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /**
     * The exported file. [store] is written whole, including the phone numbers
     * and ICCIDs the SIM spoof draws: they are made up, but they are what makes
     * the restored profile the same profile rather than a similar one.
     */
    data class Backup(
        val format: String = "",
        /** [ProfileStore.CURRENT_CONFIG_VERSION] as of the writing build. */
        val configVersion: Int = 0,
        val exportedAt: String = "",
        /** Informational: which build wrote it, for a bug report to quote. */
        val appVersion: String = "",
        val store: ProfileStore = ProfileStore(),
    )

    /** Either a configuration ready to be written, or why the file was refused. */
    sealed class Outcome {
        data class Ready(val store: ProfileStore) : Outcome()
        data class Refused(val reason: Reason) : Outcome()
    }

    enum class Reason {
        /** Not JSON at all, or too large to be a configuration. */
        UNREADABLE,

        /** Valid JSON, but nothing in it says it describes profiles. */
        NOT_A_CONFIG,

        /** Written by a build whose config shape this one cannot read. */
        FROM_A_NEWER_BUILD,

        /** Ours, readable, and holds no profile that could be assigned. */
        NO_PROFILES,
    }

    fun write(
        store: ProfileStore,
        appVersion: String,
        at: Instant = Instant.now(),
    ): String {
        val adapter: JsonAdapter<Backup> = moshi.adapter()

        // Indented: the file is the user's, and one they may well open to check
        // what they are about to hand someone else.
        return adapter.indent("  ").toJson(
            Backup(
                format = FORMAT,
                configVersion = ProfileStore.CURRENT_CONFIG_VERSION,
                exportedAt = timestamp.format(at),
                appVersion = appVersion,
                store = store,
            )
        )
    }

    /** A name that sorts by date and says what the file is. */
    fun suggestedFileName(
        at: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = "mocklocation-config-${fileTimestamp.format(at.atZone(zone))}.json"

    /**
     * Reads a file the user picked. Everything here is hostile input: it may be
     * a photo, a config from a build that does not exist yet, or a config with
     * half of it hand-edited into nonsense.
     */
    fun read(text: String): Outcome {
        val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter()
        val raw = try {
            mapAdapter.fromJson(text)
        } catch (e: Exception) {
            null
        } ?: return Outcome.Refused(Reason.UNREADABLE)

        val store = when {
            raw.containsKey("store") -> {
                val adapter: JsonAdapter<Backup> = moshi.adapter()
                val backup = try {
                    adapter.fromJson(text)
                } catch (e: Exception) {
                    null
                } ?: return Outcome.Refused(Reason.UNREADABLE)

                if (backup.format != FORMAT) return Outcome.Refused(Reason.NOT_A_CONFIG)
                if (backup.configVersion > ProfileStore.CURRENT_CONFIG_VERSION) {
                    return Outcome.Refused(Reason.FROM_A_NEWER_BUILD)
                }
                backup.store
            }

            // A bare store, hand-written or copied out of the config directory.
            raw.containsKey("profiles") -> {
                val adapter: JsonAdapter<ProfileStore> = moshi.adapter()
                try {
                    adapter.fromJson(text)
                } catch (e: Exception) {
                    null
                } ?: return Outcome.Refused(Reason.UNREADABLE)
            }

            else -> return Outcome.Refused(Reason.NOT_A_CONFIG)
        }

        if (store.configVersion > ProfileStore.CURRENT_CONFIG_VERSION) {
            return Outcome.Refused(Reason.FROM_A_NEWER_BUILD)
        }

        return repair(store)
            ?.let { Outcome.Ready(it) }
            ?: Outcome.Refused(Reason.NO_PROFILES)
    }

    /**
     * Makes an imported store internally consistent, or returns null when there
     * is nothing left to import.
     *
     * [ProfileStore] already tolerates a dangling default and dangling
     * assignments when it reads a profile out, so this is not what keeps the
     * module running. It is what keeps the *editors* honest: an assignment
     * naming a profile that is not in the file would otherwise be shown as a
     * spoofed app, and be written back untouched on every later save.
     */
    private fun repair(store: ProfileStore): ProfileStore? {
        // Two profiles sharing an id are one profile as far as every lookup
        // here is concerned, and which of them wins would depend on the order.
        val profiles = store.profiles
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        if (profiles.isEmpty()) return null

        val known = profiles.mapTo(mutableSetOf(), Profile::id)

        return ProfileStore(
            profiles = profiles,
            defaultProfileId = store.defaultProfileId.takeIf { it in known } ?: profiles.first().id,
            assignments = store.assignments.filter { (packageName, profileId) ->
                packageName.isNotBlank() && profileId in known
            },
            // Read by this build, so it is this build's shape from here on -
            // an older file has been through the same defaults every other
            // read of an older config goes through.
            configVersion = ProfileStore.CURRENT_CONFIG_VERSION,
        )
    }
}
