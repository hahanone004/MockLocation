package fuck.location.app.ui.models

/**
 * The whole config: a library of named profiles, which one applies by default,
 * and which apps deviate from that default.
 *
 * Apps are pointed at a shared profile rather than each carrying their own copy,
 * so moving "Taipei" a street over updates every app using it.
 */
data class ProfileStore(
    val profiles: List<Profile> = listOf(Profile(id = Profile.DEFAULT_ID)),
    val defaultProfileId: String = Profile.DEFAULT_ID,
    /**
     * Package name to profile id, or to [FOLLOW_DEFAULT]. An app that is absent
     * is not intercepted at all, which is why this is the only gate: an app the
     * user has never touched must never be spoofed.
     */
    val assignments: Map<String, String> = emptyMap(),
    val configVersion: Int = CURRENT_CONFIG_VERSION,
) {
    /**
     * The profile [packageName] should be spoofed with, or null to leave the app
     * alone. Falls back to the default whenever an assignment points at a
     * profile that has since been deleted, and to the first profile if the
     * default id is dangling too, so a half-edited config still works.
     */
    fun profileFor(packageName: String): Profile? {
        val assigned = assignments[packageName] ?: return null
        if (assigned == FOLLOW_DEFAULT) return defaultProfile()

        return profiles.firstOrNull { it.id == assigned } ?: defaultProfile()
    }

    fun defaultProfile(): Profile? =
        profiles.firstOrNull { it.id == defaultProfileId } ?: profiles.firstOrNull()

    companion object {
        /**
         * Assignment value meaning "whatever the default profile is", so an app
         * tracks the default instead of pinning the profile it happens to be.
         */
        const val FOLLOW_DEFAULT = "@default"

        /**
         * 1: a bare {x, y} object.
         * 2: one flat object, offset in degrees, plus the LTE identity fields.
         * 3: named profiles, offset as a radius in metres, MCC/MNC and Wi-Fi.
         * 4: assignments replace the separate whitelist.
         */
        const val CURRENT_CONFIG_VERSION = 4

        /**
         * Folds a pre-profile config into a store holding a single default
         * profile. Version 2 stored the offset as a width in degrees and
         * jittered by half of it either way; a profile stores a radius in
         * metres.
         */
        fun fromLegacy(legacy: LegacyFakeLocation): ProfileStore = ProfileStore(
            profiles = listOf(
                Profile(
                    id = Profile.DEFAULT_ID,
                    x = legacy.x,
                    y = legacy.y,
                    offset = legacy.offset * Profile.METRES_PER_DEGREE / 2,
                    tac = legacy.tac,
                    eci = legacy.eci,
                    pci = legacy.pci,
                    earfcn = legacy.earfcn,
                    bandwidth = legacy.bandwidth,
                )
            ),
            // Left at 3 so the whitelist is still folded into assignments.
            configVersion = 3,
        )
    }
}

/**
 * The version 1 and 2 config shapes. Version 1 wrote only x and y; the defaults
 * here absorb that difference so both parse through this one class.
 */
data class LegacyFakeLocation(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val offset: Double = 0.0,
    val eci: Int = 0,
    val pci: Int = 0,
    val tac: Int = 0,
    val earfcn: Int = 0,
    val bandwidth: Int = 0,
)
