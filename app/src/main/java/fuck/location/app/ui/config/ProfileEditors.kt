package fuck.location.app.ui.config

import android.content.Context
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.switchmaterial.SwitchMaterial
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import fuck.location.R
import fuck.location.app.ui.models.CarrierCatalog
import fuck.location.app.ui.models.Profile
import fuck.location.app.ui.models.ProfileStore
import fuck.location.xposed.helpers.ConfigGateway
import java.text.NumberFormat
import java.util.UUID

/**
 * The dialogs behind a profile's four spoofs and the profile library.
 *
 * Each editor reads the store fresh, edits one profile and writes the whole
 * store back, so the main screen and the profile list never work from a stale
 * copy of each other's edits.
 */
@ExperimentalStdlibApi
object ProfileEditors {

    private const val PLAY_SERVICES = "com.google.android.gms"

    /** Enough decimals that a coordinate survives a round trip through the UI. */
    private val plainNumber: NumberFormat = NumberFormat.getNumberInstance().apply {
        isGroupingUsed = false
        maximumFractionDigits = 20
    }

    // region feature editors

    fun editLocation(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            title(text = context.getString(R.string.dialog_location_title, profile.displayName(context)))
            customView(R.layout.dialog_location, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            view.findViewById<SwitchMaterial>(R.id.switch_location_enabled).isChecked =
                profile.locationEnabled
            view.findViewById<EditText>(R.id.field_latitude).setText(plainNumber.format(profile.x))
            view.findViewById<EditText>(R.id.field_longitude).setText(plainNumber.format(profile.y))
            view.findViewById<EditText>(R.id.field_offset).setText(plainNumber.format(profile.offset))

            keepClearOfKeyboard()

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()

                ConfigGateway.get().writeProfileStore(
                    store.replacing(
                        profile.copy(
                            locationEnabled = fields.switched(R.id.switch_location_enabled),
                            x = fields.decimal(R.id.field_latitude, profile.x),
                            y = fields.decimal(R.id.field_longitude, profile.y),
                            offset = fields.decimal(R.id.field_offset, profile.offset)
                                .coerceAtLeast(0.0),
                        )
                    )
                )
                onSaved()
            }
            negativeButton(R.string.action_discard)
        }
    }

    fun editCell(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            title(text = context.getString(R.string.dialog_cell_title, profile.displayName(context)))
            customView(R.layout.dialog_cell, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            view.findViewById<SwitchMaterial>(R.id.switch_cell_enabled).isChecked = profile.cellEnabled
            view.findViewById<EditText>(R.id.field_mcc).setText(profile.mcc)
            view.findViewById<EditText>(R.id.field_mnc).setText(profile.mnc)
            view.findViewById<EditText>(R.id.field_tac).setText(profile.tac.toString())
            view.findViewById<EditText>(R.id.field_eci).setText(profile.eci.toString())
            view.findViewById<EditText>(R.id.field_enodeb).setText(profile.eNodeBId.toString())
            view.findViewById<EditText>(R.id.field_sector).setText(profile.sectorId.toString())
            view.findViewById<EditText>(R.id.field_pci).setText(profile.pci.toString())
            view.findViewById<EditText>(R.id.field_earfcn).setText(profile.earfcn.toString())
            view.findViewById<EditText>(R.id.field_bandwidth).setText(profile.bandwidth.toString())

            linkEciFields(view.findViewById(R.id.field_eci),
                view.findViewById(R.id.field_enodeb),
                view.findViewById(R.id.field_sector))

            keepClearOfKeyboard()

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()

                ConfigGateway.get().writeProfileStore(
                    store.replacing(
                        profile.copy(
                            cellEnabled = fields.switched(R.id.switch_cell_enabled),
                            mcc = fields.text(R.id.field_mcc),
                            mnc = fields.text(R.id.field_mnc),
                            tac = fields.integer(R.id.field_tac, profile.tac),
                            eci = fields.integer(R.id.field_eci, profile.eci),
                            pci = fields.integer(R.id.field_pci, profile.pci),
                            earfcn = fields.integer(R.id.field_earfcn, profile.earfcn),
                            bandwidth = fields.integer(R.id.field_bandwidth, profile.bandwidth),
                        )
                    )
                )
                onSaved()
            }
            negativeButton(R.string.action_discard)
        }
    }

    fun editWifi(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            title(text = context.getString(R.string.dialog_wifi_title, profile.displayName(context)))
            customView(R.layout.dialog_wifi, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            view.findViewById<SwitchMaterial>(R.id.switch_wifi_enabled).isChecked = profile.wifiEnabled
            view.findViewById<EditText>(R.id.field_wifi_list)
                .setText(WifiListFormat.format(profile.wifiAccessPoints))

            keepClearOfKeyboard()

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()

                ConfigGateway.get().writeProfileStore(
                    store.replacing(
                        profile.copy(
                            wifiEnabled = fields.switched(R.id.switch_wifi_enabled),
                            wifiAccessPoints = WifiListFormat.parse(fields.text(R.id.field_wifi_list)),
                        )
                    )
                )
                onSaved()
            }
            negativeButton(R.string.action_discard)
        }
    }

    /**
     * The SIM identity, picked rather than typed.
     *
     * Everything an app can read about the operator follows from a country and
     * a carrier, so those are the only two choices offered; the number and the
     * ICCID are drawn to match and can then be edited by hand.
     */
    fun editSim(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        val stored = CarrierCatalog.carrierOf(profile.simCarrierId)
        var country = stored?.first
            ?: CarrierCatalog.countryOf(profile.simCountryIso)
            ?: CarrierCatalog.countries.first()
        var carrier = stored?.second

        MaterialDialog(context).show {
            title(text = context.getString(R.string.dialog_sim_title, profile.displayName(context)))
            customView(R.layout.dialog_sim, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            val countryRow = view.findViewById<TextView>(R.id.field_sim_country)
            val carrierRow = view.findViewById<TextView>(R.id.field_sim_carrier)
            val summary = view.findViewById<TextView>(R.id.sim_summary)
            val number = view.findViewById<EditText>(R.id.field_phone_number)
            val serial = view.findViewById<EditText>(R.id.field_sim_serial)

            val localeSwitch = view.findViewById<SwitchMaterial>(R.id.switch_locale_enabled)

            view.findViewById<SwitchMaterial>(R.id.switch_sim_enabled).isChecked = profile.simEnabled
            localeSwitch.isChecked = profile.localeEnabled
            number.setText(profile.phoneNumber)
            serial.setText(profile.simSerial)

            fun render() {
                countryRow.text = country.label
                carrierRow.text = carrier?.label ?: context.getString(R.string.sim_carrier_unset)

                // Named rather than left generic: which language the app will
                // start rendering in is the whole decision being made here.
                localeSwitch.text = context.getString(
                    R.string.switch_enable_locale, country.localeLabel)
                summary.text = carrier?.let {
                    context.getString(
                        R.string.sim_summary_format,
                        country.iso,
                        country.mcc + it.mnc,
                        it.operatorName,
                    )
                } ?: context.getString(R.string.sim_summary_unset)
            }

            fun regenerate() {
                val picked = carrier ?: return
                val identity = CarrierCatalog.identityFor(country, picked)

                number.setText(identity.phoneNumber)
                serial.setText(identity.simSerial)
            }

            fun pickCarrier() {
                MaterialDialog(context).show {
                    title(R.string.sim_pick_carrier)
                    listItems(items = country.carriers.map { it.label }) { _, index, _ ->
                        val previous = carrier
                        carrier = country.carriers[index]

                        // Re-picking the same carrier leaves a hand-edited
                        // number alone; changing carrier invalidates it, since
                        // the range and the ICCID issuer both belong to the old
                        // one.
                        if (previous?.id != carrier?.id ||
                            number.text.isBlank() || serial.text.isBlank()) regenerate()

                        render()
                    }
                }
            }

            countryRow.setOnClickListener {
                MaterialDialog(context).show {
                    title(R.string.sim_pick_country)
                    listItems(items = CarrierCatalog.countries.map { it.label }) { _, index, _ ->
                        val picked = CarrierCatalog.countries[index]
                        if (picked.iso != country.iso) {
                            country = picked
                            carrier = null
                        }

                        render()
                        pickCarrier()
                    }
                }
            }
            carrierRow.setOnClickListener { pickCarrier() }
            view.findViewById<View>(R.id.action_regenerate).setOnClickListener { regenerate() }

            render()
            keepClearOfKeyboard()

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()
                val picked = carrier

                ConfigGateway.get().writeProfileStore(
                    store.replacing(
                        profile.copy(
                            simEnabled = fields.switched(R.id.switch_sim_enabled),
                            simCarrierId = picked?.id ?: "",
                            simCountryIso = if (picked != null) country.iso else "",
                            simOperatorName = picked?.operatorName ?: "",
                            phoneNumber = fields.text(R.id.field_phone_number),
                            simSerial = fields.text(R.id.field_sim_serial),
                            localeEnabled = fields.switched(R.id.switch_locale_enabled),
                            localeTag = country.locale,
                            // The cell identity describes the same network, so
                            // the carrier decides its MCC and MNC too rather
                            // than letting the two drift apart.
                            mcc = if (picked != null) country.mcc else profile.mcc,
                            mnc = picked?.mnc ?: profile.mnc,
                        )
                    )
                )
                onSaved()
            }
            negativeButton(R.string.action_discard)
        }
    }

    // endregion

    // region profile library

    /** The profile list: pick one to work on, or create another. */
    fun manageProfiles(context: Context, onChanged: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()

        val labels = store.profiles.map { profile ->
            val name = if (profile.id == store.defaultProfileId) {
                context.getString(R.string.profile_label_default, profile.displayName(context))
            } else {
                profile.displayName(context)
            }

            profile.qualified(context, name)
        } + context.getString(R.string.profile_action_new)

        MaterialDialog(context).show {
            title(R.string.title_profiles)
            listItems(items = labels) { _, index, _ ->
                if (index == store.profiles.size) createProfile(context, onChanged)
                else profileActions(context, store.profiles[index].id, onChanged)
            }
        }
    }

    private fun createProfile(context: Context, onChanged: () -> Unit) {
        MaterialDialog(context).show {
            title(R.string.profile_action_new)
            input(hintRes = R.string.profile_name_hint) { _, text ->
                val name = text.toString().trim()
                if (name.isEmpty()) return@input

                val store = ConfigGateway.get().readProfileStore()
                val created = Profile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    locationEnabled = true,
                    cellEnabled = true,
                    wifiEnabled = true,
                    simEnabled = true,
                )

                ConfigGateway.get().writeProfileStore(
                    store.copy(profiles = store.profiles + created)
                )
                onChanged()
                profileActions(context, created.id, onChanged)
            }
            negativeButton(R.string.action_discard)
        }
    }

    /** What you can do to one profile. */
    private fun profileActions(context: Context, profileId: String, onChanged: () -> Unit) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        // Showing the on/off state here saves opening all three to find out
        // what a profile does.
        fun labelled(titleRes: Int, enabled: Boolean) = context.getString(
            if (enabled) R.string.profile_feature_on else R.string.profile_feature_off,
            context.getString(titleRes),
        )

        val actions = listOf(
            labelled(R.string.title_location_spoof, profile.locationEnabled),
            labelled(R.string.title_cell_spoof, profile.cellEnabled),
            labelled(R.string.title_wifi_spoof, profile.wifiEnabled),
            labelled(R.string.title_sim_spoof, profile.simEnabled),
            context.getString(R.string.profile_action_default),
            context.getString(R.string.profile_action_rename),
            context.getString(R.string.profile_action_delete),
        )

        MaterialDialog(context).show {
            title(text = profile.displayName(context))
            listItems(items = actions) { _, index, _ ->
                when (index) {
                    0 -> editLocation(context, profileId, onChanged)
                    1 -> editCell(context, profileId, onChanged)
                    2 -> editWifi(context, profileId, onChanged)
                    3 -> editSim(context, profileId, onChanged)
                    4 -> {
                        ConfigGateway.get().writeProfileStore(store.copy(defaultProfileId = profileId))
                        onChanged()
                    }
                    5 -> renameProfile(context, profileId, onChanged)
                    6 -> deleteProfile(context, profileId, onChanged)
                }
            }
        }
    }

    private fun renameProfile(context: Context, profileId: String, onChanged: () -> Unit) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            title(R.string.profile_action_rename)
            input(hintRes = R.string.profile_name_hint, prefill = profile.name) { _, text ->
                val name = text.toString().trim()
                if (name.isEmpty()) return@input

                ConfigGateway.get().writeProfileStore(store.replacing(profile.copy(name = name)))
                onChanged()
            }
            negativeButton(R.string.action_discard)
        }
    }

    private fun deleteProfile(context: Context, profileId: String, onChanged: () -> Unit) {
        val store = ConfigGateway.get().readProfileStore()

        // Something has to remain for unassigned apps to fall back to.
        if (store.profiles.size <= 1) {
            MaterialDialog(context).show {
                title(R.string.profile_action_delete)
                message(R.string.profile_delete_last)
                positiveButton(R.string.action_ok)
            }
            return
        }

        MaterialDialog(context).show {
            title(R.string.profile_action_delete)
            message(R.string.profile_delete_confirm)
            positiveButton(R.string.profile_action_delete) {
                val remaining = store.profiles.filterNot { it.id == profileId }

                ConfigGateway.get().writeProfileStore(
                    store.copy(
                        profiles = remaining,
                        // Apps pointed here fall back to the default, and the
                        // default itself moves if it was the one deleted.
                        defaultProfileId = if (store.defaultProfileId == profileId) {
                            remaining.first().id
                        } else {
                            store.defaultProfileId
                        },
                        assignments = store.assignments.filterValues { it != profileId },
                    )
                )
                onChanged()
            }
            negativeButton(R.string.action_discard)
        }
    }

    /**
     * The one control an app has: leave it alone, follow the default, or pin it
     * to a named profile.
     */
    fun assignProfile(context: Context, packageName: String, onChanged: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val default = store.defaultProfile()

        val labels = listOf(
            context.getString(
                R.string.profile_assign_follow_default,
                default?.displayName(context) ?: context.getString(R.string.profile_unnamed),
            ).let { default?.qualified(context, it) ?: it },
        ) + store.profiles.map { it.qualified(context, it.displayName(context)) }

        MaterialDialog(context).show {
            title(R.string.profile_assign_title)
            listItems(items = labels) { _, index, _ ->
                // Following the default is the absence of an assignment, so
                // picking it drops the entry rather than storing a sentinel.
                val assignments = if (index == 0) store.assignments - packageName
                else store.assignments + (packageName to store.profiles[index - 1].id)

                ConfigGateway.get().writeProfileStore(store.copy(assignments = assignments))
                onChanged()

                if (index > 0) {
                    offerPlayServices(context, store.profiles[index - 1], packageName, onChanged)
                }
            }
        }
    }

    /**
     * Offers to put Play Services on the same profile.
     *
     * Which app is being spoofed is decided by which app asked, and most apps
     * never ask: they hand the question to Google Play Services, and all the
     * system ever sees is Play Services asking. Assigning such an app on its
     * own therefore does nothing at all, with no way to tell from the outside -
     * the profile is right, the assignment is right, and the position never
     * changes. Only worth raising for a profile that actually spoofs location,
     * and worth saying plainly that it is not confined to the one app.
     */
    private fun offerPlayServices(
        context: Context,
        profile: Profile,
        justAssigned: String,
        onChanged: () -> Unit,
    ) {
        if (!profile.locationEnabled || justAssigned == PLAY_SERVICES) return

        val store = ConfigGateway.get().readProfileStore()
        if (store.assignments[PLAY_SERVICES] == profile.id) return
        if (!isInstalled(context, PLAY_SERVICES)) return

        MaterialDialog(context).show {
            title(R.string.play_services_title)
            message(R.string.play_services_message)
            positiveButton(R.string.play_services_assign) {
                val current = ConfigGateway.get().readProfileStore()

                ConfigGateway.get().writeProfileStore(
                    current.copy(
                        assignments = current.assignments + (PLAY_SERVICES to profile.id)
                    )
                )
                onChanged()
            }
            negativeButton(R.string.action_not_now)
        }
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** How an app's assignment reads in the app list. */
    fun assignmentLabel(context: Context, store: ProfileStore, packageName: String): String {
        val default = store.defaultProfile()
        val followDefault = context.getString(
            R.string.profile_assign_follow_default,
            default?.displayName(context) ?: context.getString(R.string.profile_unnamed),
        ).let { default?.qualified(context, it) ?: it }

        val assigned = store.assignments[packageName] ?: return followDefault

        return store.profiles.firstOrNull { it.id == assigned }
            ?.let { it.qualified(context, it.displayName(context)) }
            ?: followDefault
    }

    // endregion

    // region helpers

    /**
     * Keeps the ECI and the eNodeB/sector pair showing the same identity while
     * either side is typed in. The guard stops each update from bouncing back.
     */
    private fun linkEciFields(eci: EditText, eNodeB: EditText, sector: EditText) {
        var updating = false

        fun sync(source: EditText, write: () -> Unit) {
            source.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (updating || !source.hasFocus()) return
                    updating = true
                    write()
                    updating = false
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }

        sync(eci) {
            val value = eci.text.toString().toIntOrNull() ?: 0
            eNodeB.setText((value ushr 8).toString())
            sector.setText((value and 0xFF).toString())
        }

        val fromParts = {
            val composed = Profile.eciOf(
                eNodeB.text.toString().toIntOrNull() ?: 0,
                sector.text.toString().toIntOrNull() ?: 0,
            )
            eci.setText(composed.toString())
        }
        sync(eNodeB, fromParts)
        sync(sector, fromParts)
    }

    /**
     * Keeps the field being typed into above the keyboard.
     *
     * These editors used to be bottom sheets, which sit against the bottom of
     * the screen and so end up entirely behind the IME. A plain dialog is moved
     * out of the way by the window manager, and where that is left to the app -
     * a window running edge to edge is handed the IME as an inset rather than
     * being resized - the inset is padded into the form instead.
     */
    private fun MaterialDialog.keepClearOfKeyboard() {
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val fields = getCustomView()
        val base = fields.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(fields) { view, insets ->
            view.updatePadding(bottom = base + insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
    }

    private fun ProfileStore.replacing(profile: Profile): ProfileStore =
        copy(profiles = profiles.map { if (it.id == profile.id) profile else it })

    private fun Profile.displayName(context: Context): String =
        name.ifBlank { context.getString(R.string.profile_unnamed) }

    /**
     * Marks a profile that has every switch off. An app assigned to one behaves
     * as though the module were not installed, which is otherwise impossible to
     * tell apart from the module being broken.
     */
    private fun Profile.qualified(context: Context, label: String): String =
        if (spoofsNothing) context.getString(R.string.profile_does_nothing, label) else label

    private fun View.text(id: Int): String =
        findViewById<EditText>(id).text.toString().trim()

    private fun View.integer(id: Int, fallback: Int): Int =
        text(id).toIntOrNull() ?: fallback

    private fun View.decimal(id: Int, fallback: Double): Double =
        text(id).toDoubleOrNull() ?: fallback

    private fun View.switched(id: Int): Boolean =
        findViewById<SwitchMaterial>(id).isChecked

    // endregion
}
