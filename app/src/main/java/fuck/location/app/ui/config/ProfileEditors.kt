package fuck.location.app.ui.config

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import fuck.location.R
import fuck.location.app.ui.models.Profile
import fuck.location.app.ui.models.ProfileStore
import fuck.location.xposed.helpers.ConfigGateway
import java.text.NumberFormat
import java.util.UUID

/**
 * The dialogs behind the three feature entries and the profile library.
 *
 * Each editor reads the store fresh, edits one profile and writes the whole
 * store back, so the main screen and the profile list never work from a stale
 * copy of each other's edits.
 */
@ExperimentalStdlibApi
object ProfileEditors {

    /** Enough decimals that a coordinate survives a round trip through the UI. */
    private val plainNumber: NumberFormat = NumberFormat.getNumberInstance().apply {
        isGroupingUsed = false
        maximumFractionDigits = 20
    }

    // region feature editors

    fun editLocation(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
            title(text = context.getString(R.string.dialog_location_title, profile.displayName(context)))
            customView(R.layout.dialog_location, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            view.findViewById<EditText>(R.id.field_latitude).setText(plainNumber.format(profile.x))
            view.findViewById<EditText>(R.id.field_longitude).setText(plainNumber.format(profile.y))
            view.findViewById<EditText>(R.id.field_offset).setText(plainNumber.format(profile.offset))

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()

                ConfigGateway.get().writeProfileStore(
                    store.replacing(
                        profile.copy(
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

        MaterialDialog(context, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
            title(text = context.getString(R.string.dialog_cell_title, profile.displayName(context)))
            customView(R.layout.dialog_cell, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
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

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()

                ConfigGateway.get().writeProfileStore(
                    store.replacing(
                        profile.copy(
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

        MaterialDialog(context, BottomSheet(LayoutMode.WRAP_CONTENT)).show {
            title(text = context.getString(R.string.dialog_wifi_title, profile.displayName(context)))
            customView(R.layout.dialog_wifi, scrollable = true, horizontalPadding = true)

            getCustomView().findViewById<EditText>(R.id.field_wifi_list)
                .setText(WifiListFormat.format(profile.wifiAccessPoints))

            positiveButton(R.string.action_save) { dialog ->
                val text = dialog.getCustomView().text(R.id.field_wifi_list)

                ConfigGateway.get().writeProfileStore(
                    store.replacing(profile.copy(wifiAccessPoints = WifiListFormat.parse(text)))
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
            if (profile.id == store.defaultProfileId) {
                context.getString(R.string.profile_label_default, profile.displayName(context))
            } else {
                profile.displayName(context)
            }
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
                val created = Profile(id = UUID.randomUUID().toString(), name = name)

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

        val actions = listOf(
            context.getString(R.string.title_location_spoof),
            context.getString(R.string.title_cell_spoof),
            context.getString(R.string.title_wifi_spoof),
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
                    3 -> {
                        ConfigGateway.get().writeProfileStore(store.copy(defaultProfileId = profileId))
                        onChanged()
                    }
                    4 -> renameProfile(context, profileId, onChanged)
                    5 -> deleteProfile(context, profileId, onChanged)
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

    /** Lets the user point one app at a profile, or back at the default. */
    fun assignProfile(context: Context, packageName: String, onChanged: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()

        val labels = listOf(context.getString(R.string.profile_use_default)) +
            store.profiles.map { it.displayName(context) }

        MaterialDialog(context).show {
            title(R.string.profile_assign_title)
            listItems(items = labels) { _, index, _ ->
                val assignments = if (index == 0) {
                    store.assignments - packageName
                } else {
                    store.assignments + (packageName to store.profiles[index - 1].id)
                }

                ConfigGateway.get().writeProfileStore(store.copy(assignments = assignments))
                onChanged()
            }
        }
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

    private fun ProfileStore.replacing(profile: Profile): ProfileStore =
        copy(profiles = profiles.map { if (it.id == profile.id) profile else it })

    private fun Profile.displayName(context: Context): String =
        name.ifBlank { context.getString(R.string.profile_unnamed) }

    private fun android.view.View.text(id: Int): String =
        findViewById<EditText>(id).text.toString().trim()

    private fun android.view.View.integer(id: Int, fallback: Int): Int =
        text(id).toIntOrNull() ?: fallback

    private fun android.view.View.decimal(id: Int, fallback: Double): Double =
        text(id).toDoubleOrNull() ?: fallback

    // endregion
}
