package mock.location.app.ui.config

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.card.MaterialCardView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import mock.location.R
import mock.location.app.ui.models.CarrierCatalog
import mock.location.app.ui.models.Profile
import mock.location.app.ui.models.ProfileStore
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.runOnMainThread
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlin.concurrent.thread

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
    private const val CAPTURE_PERMISSIONS = 4211
    private val LTE_BANDWIDTHS = setOf(0, 1_400, 3_000, 5_000, 10_000, 15_000, 20_000)

    // region feature editors

    fun editLocation(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            noAutoDismiss()
            title(text = context.getString(R.string.dialog_location_title, profile.displayName(context)))
            customView(R.layout.dialog_location, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            view.findViewById<SwitchMaterial>(R.id.switch_location_enabled).isChecked =
                profile.locationEnabled
            view.findViewById<EditText>(R.id.field_latitude)
                .setText(CoordinateFormat.format(profile.x))
            view.findViewById<EditText>(R.id.field_longitude)
                .setText(CoordinateFormat.format(profile.y))
            view.findViewById<EditText>(R.id.field_offset)
                .setText(CoordinateFormat.format(profile.offset))

            keepClearOfKeyboard()

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()
                val current = ConfigGateway.get().readProfileStore()
                val latest = current.profiles.firstOrNull { it.id == profileId }
                    ?: return@positiveButton

                save(context,
                    current.replacing(
                        latest.copy(
                            locationEnabled = fields.switched(R.id.switch_location_enabled),
                            x = fields.decimal(R.id.field_latitude, latest.x)
                                .takeIf { it.isFinite() }?.coerceIn(-90.0, 90.0) ?: 0.0,
                            y = fields.decimal(R.id.field_longitude, latest.y)
                                .takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0) ?: 0.0,
                            offset = fields.decimal(R.id.field_offset, latest.offset)
                                .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                        )
                    )
                ) {
                    onSaved()
                    dialog.dismiss()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
        }
    }

    fun editCell(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            noAutoDismiss()
            title(text = context.getString(R.string.dialog_cell_title, profile.displayName(context)))
            customView(R.layout.dialog_cell, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            val carrierIdentity = CarrierCatalog.carrierOf(profile.simCarrierId)
            view.findViewById<SwitchMaterial>(R.id.switch_cell_enabled).isChecked = profile.cellEnabled
            view.findViewById<EditText>(R.id.field_mcc).apply {
                setText(carrierIdentity?.first?.mcc ?: profile.mcc)
                isEnabled = carrierIdentity == null
            }
            view.findViewById<EditText>(R.id.field_mnc).apply {
                setText(carrierIdentity?.second?.mnc ?: profile.mnc)
                isEnabled = carrierIdentity == null
            }
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
                val mcc = fields.text(R.id.field_mcc)
                val mnc = fields.text(R.id.field_mnc)
                val tac = fields.text(R.id.field_tac).toIntOrNull()
                val eci = fields.text(R.id.field_eci).toIntOrNull()
                val pci = fields.text(R.id.field_pci).toIntOrNull()
                val earfcn = fields.text(R.id.field_earfcn).toIntOrNull()
                val bandwidth = fields.text(R.id.field_bandwidth).toIntOrNull()
                val cellEnabled = fields.switched(R.id.switch_cell_enabled)
                val valid = !cellEnabled || mcc.matches(Regex("^[0-9]{3}$")) &&
                    mnc.matches(Regex("^[0-9]{2,3}$")) &&
                    tac != null && tac in 0..65_535 &&
                    // 0 is not a cell: Profile.describesCell rejects it, so
                    // accepting it here saved a profile whose switch was on and
                    // whose hooks then reported no cell at all.
                    eci != null && eci in 1..268_435_455 &&
                    pci != null && pci in 0..503 &&
                    earfcn != null && earfcn in 0..262_143 &&
                    bandwidth != null && bandwidth in LTE_BANDWIDTHS
                if (!valid) {
                    Toast.makeText(context, R.string.cell_config_invalid, Toast.LENGTH_LONG).show()
                    return@positiveButton
                }
                val current = ConfigGateway.get().readProfileStore()
                val latest = current.profiles.firstOrNull { it.id == profileId }
                    ?: return@positiveButton
                val latestCarrier = CarrierCatalog.carrierOf(latest.simCarrierId)

                save(context,
                    current.replacing(
                        latest.copy(
                            cellEnabled = cellEnabled,
                            mcc = latestCarrier?.first?.mcc ?: mcc,
                            mnc = latestCarrier?.second?.mnc ?: mnc,
                            tac = tac ?: latest.tac,
                            eci = eci ?: latest.eci,
                            pci = pci ?: latest.pci,
                            earfcn = earfcn ?: latest.earfcn,
                            bandwidth = bandwidth ?: latest.bandwidth,
                        )
                    )
                ) {
                    onSaved()
                    dialog.dismiss()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
        }
    }

    fun editWifi(context: Context, profileId: String, onSaved: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            noAutoDismiss()
            title(text = context.getString(R.string.dialog_wifi_title, profile.displayName(context)))
            customView(R.layout.dialog_wifi, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            view.findViewById<SwitchMaterial>(R.id.switch_wifi_enabled).isChecked = profile.wifiEnabled
            view.findViewById<EditText>(R.id.field_wifi_list)
                .setText(WifiListFormat.format(profile.wifiAccessPoints))

            keepClearOfKeyboard()

            positiveButton(R.string.action_save) { dialog ->
                val fields = dialog.getCustomView()
                val current = ConfigGateway.get().readProfileStore()
                val latest = current.profiles.firstOrNull { it.id == profileId }
                    ?: return@positiveButton

                save(context,
                    current.replacing(
                        latest.copy(
                            wifiEnabled = fields.switched(R.id.switch_wifi_enabled),
                            wifiAccessPoints = WifiListFormat.parse(fields.text(R.id.field_wifi_list)),
                        )
                    )
                ) {
                    onSaved()
                    dialog.dismiss()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
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
            noAutoDismiss()
            title(text = context.getString(R.string.dialog_sim_title, profile.displayName(context)))
            customView(R.layout.dialog_sim, scrollable = true, horizontalPadding = true)

            val view = getCustomView()
            val countryRow = view.findViewById<TextView>(R.id.field_sim_country)
            val carrierRow = view.findViewById<TextView>(R.id.field_sim_carrier)
            val summary = view.findViewById<TextView>(R.id.sim_summary)
            val number = view.findViewById<EditText>(R.id.field_phone_number)
            val serial = view.findViewById<EditText>(R.id.field_sim_serial)

            val localeSwitch = view.findViewById<SwitchMaterial>(R.id.switch_locale_enabled)
            val timeZoneSwitch = view.findViewById<SwitchMaterial>(R.id.switch_timezone_enabled)

            view.findViewById<SwitchMaterial>(R.id.switch_sim_enabled).isChecked = profile.simEnabled
            localeSwitch.isChecked = profile.localeEnabled
            timeZoneSwitch.isChecked = profile.timeZoneEnabled
            number.setText(profile.phoneNumber)
            serial.setText(profile.simSerial)

            fun render() {
                countryRow.text = country.label
                carrierRow.text = carrier?.label ?: context.getString(R.string.sim_carrier_unset)

                // Named rather than left generic: which language the app will
                // start rendering in is the whole decision being made here.
                localeSwitch.text = context.getString(
                    R.string.switch_enable_locale, country.localeLabel)
                timeZoneSwitch.text = context.getString(
                    R.string.switch_enable_timezone, country.timeZoneLabel)
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
                val simEnabled = fields.switched(R.id.switch_sim_enabled)
                val localeEnabled = fields.switched(R.id.switch_locale_enabled)
                val timeZoneEnabled = fields.switched(R.id.switch_timezone_enabled)
                // The phone keypad offers +, spaces, dashes and brackets, and a
                // pasted number brings its own formatting; the digits are the
                // number. Rejecting the rest turned an ordinary way of typing
                // it into a bare "invalid" toast.
                val phoneNumber = fields.text(R.id.field_phone_number).filter(Char::isDigit)
                val simSerial = fields.text(R.id.field_sim_serial).filter(Char::isDigit)
                val valid = (!localeEnabled || simEnabled) &&
                    (!timeZoneEnabled || simEnabled) &&
                    (!timeZoneEnabled || country.timeZone.isNotBlank()) && (!simEnabled ||
                    picked != null &&
                    phoneNumber.length == country.numberLength &&
                    simSerial.length in 19..20 &&
                    CarrierCatalog.luhnValid(simSerial) &&
                    (!localeEnabled || country.locale.isNotBlank()))
                if (!valid) {
                    Toast.makeText(context, R.string.sim_config_invalid, Toast.LENGTH_LONG).show()
                    return@positiveButton
                }
                val current = ConfigGateway.get().readProfileStore()
                val latest = current.profiles.firstOrNull { it.id == profileId }
                    ?: return@positiveButton

                save(context,
                    current.replacing(
                        latest.copy(
                            simEnabled = simEnabled,
                            simCarrierId = picked?.id ?: "",
                            simCountryIso = if (picked != null) country.iso else "",
                            simOperatorName = picked?.operatorName ?: "",
                            phoneNumber = phoneNumber,
                            simSerial = simSerial,
                            localeEnabled = localeEnabled,
                            localeTag = country.locale,
                            timeZoneEnabled = timeZoneEnabled,
                            timeZoneId = country.timeZone,
                            // The cell identity describes the same network, so
                            // the carrier decides its MCC and MNC too rather
                            // than letting the two drift apart.
                            mcc = if (picked != null) country.mcc else latest.mcc,
                            mnc = picked?.mnc ?: latest.mnc,
                        )
                    )
                ) {
                    onSaved()
                    if (profile.localeEnabled != localeEnabled ||
                        profile.localeTag != country.locale ||
                        profile.timeZoneEnabled != timeZoneEnabled ||
                        profile.timeZoneId != country.timeZone) {
                        Toast.makeText(
                            context,
                            R.string.locale_restart_required,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    dialog.dismiss()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
        }
    }

    // endregion

    // region profile library

    /** The profile list: pick one to work on, or create another. */
    fun manageProfiles(context: Context, onChanged: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()

        MaterialDialog(context).show {
            // Keep the library below the selected profile menu. Without this,
            // opening a profile consumes the list dialog and Back jumps all
            // the way to the activity's home screen.
            noAutoDismiss()
            title(R.string.title_profiles)
            customView(R.layout.dialog_profile_library, scrollable = true, horizontalPadding = true)
            rebuildWhenStale(context) { manageProfiles(context, onChanged) }
            negativeButton(R.string.action_close) { it.dismiss() }

            val notify = changed(onChanged)
            val view = getCustomView()
            val container = view.findViewById<LinearLayout>(R.id.profile_list_container)

            store.profiles.forEach { profile ->
                addProfileRow(
                    context = context,
                    container = container,
                    title = profile.displayName(context),
                    summary = profile.featureSummary(context),
                    badge = context.getString(R.string.profile_badge_default)
                        .takeIf { profile.id == store.defaultProfileId },
                    onClick = { profileActions(context, profile.id, notify) },
                )
            }

            view.findViewById<View>(R.id.profile_action_new_button).setOnClickListener {
                createProfile(context, notify)
            }
        }
    }

    private fun createProfile(context: Context, onChanged: () -> Unit) {
        MaterialDialog(context).show {
            noAutoDismiss()
            title(R.string.profile_action_new)
            input(hintRes = R.string.profile_name_hint) { dialog, text ->
                val name = text.toString().trim()
                if (name.isEmpty()) return@input

                val store = ConfigGateway.get().readProfileStore()
                val created = Profile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    // A name alone is not a valid environment. Let the user
                    // configure each feature before explicitly enabling it.
                    locationEnabled = false,
                    cellEnabled = false,
                    wifiEnabled = false,
                    simEnabled = false,
                )

                val current = ConfigGateway.get().readProfileStore()
                save(context,
                    current.copy(profiles = current.profiles + created)
                ) {
                    // Shown before this one is dismissed, and before the change
                    // is reported. The list underneath rebuilds itself the
                    // moment it regains window focus, so it must not regain it
                    // in the gap between two dialogs - it would come back on
                    // top of the one just opened.
                    offerDeviceEnvironment(context, created.id, onChanged) {
                        profileActions(context, created.id, onChanged)
                    }
                    dialog.dismiss()
                    onChanged()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
        }
    }

    /**
     * Offers to fill a new profile in from where the phone actually is.
     *
     * Assembling a profile by hand means reading a position off a map, a cell
     * identity out of a field-test screen and a list of access points out of a
     * scanner, and getting all three to describe the same place. The device
     * already knows all of it, so this is offered the moment a profile is
     * named - which is also the only moment it is empty enough for filling in
     * to be unambiguous.
     *
     * The dialog does not dismiss itself: the answer may be a permission
     * prompt, and after granting it the user is standing right where they need
     * to be to try again.
     */
    private fun offerDeviceEnvironment(
        context: Context,
        profileId: String,
        onChanged: () -> Unit,
        afterwards: () -> Unit,
    ) {
        MaterialDialog(context).show {
            noAutoDismiss()
            title(R.string.device_capture_title)
            message(R.string.device_capture_message)
            positiveButton(R.string.device_capture_action) { dialog ->
                if (!requestCaptureAccess(context)) return@positiveButton

                // The progress dialog goes up first, for the same reason.
                captureIntoProfile(context, profileId, onChanged, afterwards)
                dialog.dismiss()
            }
            negativeButton(R.string.device_capture_manual) {
                afterwards()
                it.dismiss()
            }
        }
    }

    /**
     * Whether the device may be read, asking for what is missing if it may not.
     *
     * The prompt's answer is not waited on. Coming back through
     * onRequestPermissionsResult would mean carrying the half-made profile
     * through the activity, and the caller's dialog is still on screen, so
     * tapping again once the prompt is answered is both simpler and where the
     * user already is.
     */
    private fun requestCaptureAccess(context: Context): Boolean {
        val missing = DeviceEnvironment.missingPermissions(context)
        if (missing.isEmpty()) return true

        (context as? Activity)?.let {
            ActivityCompat.requestPermissions(it, missing.toTypedArray(), CAPTURE_PERMISSIONS)
        }
        Toast.makeText(context, R.string.device_capture_needs_permission, Toast.LENGTH_LONG).show()

        return false
    }

    /**
     * Reads the device and writes what came back into the profile.
     *
     * Off the main thread, because a fix waits on a satellite. The store is
     * read again on the way back rather than captured on the way in: the read
     * takes seconds, and the profile may have been renamed in the meantime.
     */
    private fun captureIntoProfile(
        context: Context,
        profileId: String,
        onChanged: () -> Unit,
        afterwards: () -> Unit,
    ) {
        val progress = MaterialDialog(context).show {
            title(R.string.device_capture_title)
            message(R.string.device_capture_working)
            cancelable(false)
        }

        thread {
            val capture = DeviceEnvironment.capture(context)

            runOnMainThread {
                if (capture.isEmpty) {
                    Toast.makeText(context, R.string.device_capture_empty, Toast.LENGTH_LONG).show()
                    afterwards()
                    progress.dismiss()
                    return@runOnMainThread
                }

                val store = ConfigGateway.get().readProfileStore()
                val profile = store.profiles.firstOrNull { it.id == profileId }
                    ?: run {
                        afterwards()
                        progress.dismiss()
                        return@runOnMainThread
                    }
                val filled = DeviceEnvironment.applyTo(profile, capture)

                save(
                    context,
                    store.copy(
                        profiles = store.profiles.map { if (it.id == profileId) filled else it }
                    ),
                ) {
                    onChanged()
                    val done = context.getString(
                        R.string.device_capture_done,
                        captureSummary(context, capture),
                    )
                    // Cells were named but none of them LTE, which is not the
                    // same as the modem having said nothing - worth telling
                    // apart, since only one of the two is worth retrying.
                    val note = if (capture.cell == null && capture.cellsWithoutLte) {
                        "\n" + context.getString(R.string.device_capture_no_lte)
                    } else {
                        ""
                    }

                    Toast.makeText(context, done + note, Toast.LENGTH_LONG).show()
                    afterwards()
                }
                progress.dismiss()
            }
        }
    }

    /** Which of the three the device actually had to give. */
    private fun captureSummary(context: Context, capture: DeviceEnvironment.Capture): String {
        val parts = buildList {
            if (capture.location != null) add(context.getString(R.string.device_capture_part_location))
            if (capture.cell != null) add(context.getString(R.string.device_capture_part_cell))
            if (capture.accessPoints.isNotEmpty()) {
                add(context.getString(R.string.device_capture_part_wifi, capture.accessPoints.size))
            }
        }

        return parts.joinToString(context.getString(R.string.device_capture_separator))
    }

    /** What you can do to one profile. */
    private fun profileActions(context: Context, profileId: String, onChanged: () -> Unit) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            // Feature editors are a third navigation level. Preserve this
            // menu below them so Back returns here instead of to the home page.
            noAutoDismiss()
            title(text = profile.displayName(context))
            customView(R.layout.dialog_profile_actions, scrollable = true, horizontalPadding = true)
            negativeButton(R.string.action_close) { it.dismiss() }
            // The on/off suffixes above were read when this menu was built, so
            // returning from a feature editor that flipped one showed the old
            // answer until the whole menu was reopened.
            rebuildWhenStale(context) { profileActions(context, profileId, onChanged) }
            val notify = changed(onChanged)
            val dialog = this
            val view = getCustomView()
            val enabledCount = listOf(
                profile.locationEnabled,
                profile.cellEnabled,
                profile.wifiEnabled,
                profile.simEnabled,
            ).count { it }

            view.findViewById<TextView>(R.id.profile_enabled_summary).text =
                context.getString(R.string.profile_enabled_summary, enabledCount)
            view.findViewById<View>(R.id.profile_default_badge).visibility =
                if (profile.id == store.defaultProfileId) View.VISIBLE else View.GONE

            bindFeatureRow(
                context,
                view.findViewById(R.id.profile_action_location),
                R.drawable.baseline_location_on_24,
                R.string.title_location_spoof,
                context.getString(
                    R.string.profile_location_summary,
                    CoordinateFormat.format(profile.x),
                    CoordinateFormat.format(profile.y),
                    CoordinateFormat.format(profile.offset),
                ),
                profile.locationEnabled,
            ) { editLocation(context, profileId, notify) }

            val cellSummary = if (profile.operatorNumeric.isBlank() || profile.eci <= 0) {
                context.getString(R.string.profile_not_configured)
            } else {
                context.getString(R.string.profile_cell_summary, profile.mcc, profile.mnc, profile.eci)
            }
            bindFeatureRow(
                context,
                view.findViewById(R.id.profile_action_cell),
                R.drawable.baseline_cell_tower_24,
                R.string.title_cell_spoof,
                cellSummary,
                profile.cellEnabled,
            ) { editCell(context, profileId, notify) }

            bindFeatureRow(
                context,
                view.findViewById(R.id.profile_action_wifi),
                R.drawable.baseline_wifi_24,
                R.string.title_wifi_spoof,
                context.getString(R.string.profile_wifi_summary, profile.wifiAccessPoints.size),
                profile.wifiEnabled,
            ) { editWifi(context, profileId, notify) }

            val simSummary = if (profile.simOperatorName.isBlank()) {
                context.getString(R.string.profile_not_configured)
            } else {
                context.getString(
                    R.string.profile_sim_summary,
                    profile.simOperatorName,
                    profile.localeTag.takeIf { profile.localeEnabled }
                        ?: context.getString(R.string.profile_language_unchanged),
                    profile.timeZoneId.takeIf { profile.timeZoneEnabled }
                        ?: context.getString(R.string.profile_timezone_unchanged),
                )
            }
            bindFeatureRow(
                context,
                view.findViewById(R.id.profile_action_sim),
                R.drawable.baseline_sim_card_24,
                R.string.title_sim_spoof,
                simSummary,
                profile.simEnabled,
            ) { editSim(context, profileId, notify) }

            view.findViewById<View>(R.id.profile_action_make_default).apply {
                visibility = if (profile.id == store.defaultProfileId) View.GONE else View.VISIBLE
                setOnClickListener {
                    val current = ConfigGateway.get().readProfileStore()
                    save(context, current.copy(defaultProfileId = profileId)) {
                        dialog.dismiss()
                        notify()
                        profileActions(context, profileId, onChanged)
                    }
                }
            }
            view.findViewById<View>(R.id.profile_action_rename_button).setOnClickListener {
                renameProfile(context, profileId, notify)
            }
            view.findViewById<View>(R.id.profile_action_delete_button).setOnClickListener {
                deleteProfile(context, profileId) {
                    // The detail view refers to an object that no longer exists.
                    dialog.dismiss()
                    notify()
                }
            }
        }
    }

    private fun renameProfile(context: Context, profileId: String, onChanged: () -> Unit) {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.profiles.firstOrNull { it.id == profileId } ?: return

        MaterialDialog(context).show {
            noAutoDismiss()
            title(R.string.profile_action_rename)
            input(hintRes = R.string.profile_name_hint, prefill = profile.name) { dialog, text ->
                val name = text.toString().trim()
                if (name.isEmpty()) return@input

                val current = ConfigGateway.get().readProfileStore()
                val latest = current.profiles.firstOrNull { it.id == profileId }
                    ?: return@input
                save(context, current.replacing(latest.copy(name = name))) {
                    onChanged()
                    dialog.dismiss()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
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
            noAutoDismiss()
            title(R.string.profile_action_delete)
            message(R.string.profile_delete_confirm)
            positiveButton(R.string.profile_action_delete) { dialog ->
                val current = ConfigGateway.get().readProfileStore()
                if (current.profiles.size <= 1) return@positiveButton
                val remaining = current.profiles.filterNot { it.id == profileId }

                save(context,
                    current.copy(
                        profiles = remaining,
                        // Apps pointed here fall back to the default, and the
                        // default itself moves if it was the one deleted.
                        defaultProfileId = if (current.defaultProfileId == profileId) {
                            remaining.first().id
                        } else {
                            current.defaultProfileId
                        },
                        assignments = current.assignments.filterValues { it != profileId },
                    )
                ) {
                    onChanged()
                    dialog.dismiss()
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
        }
    }

    /**
     * The one control an app has: leave it alone, follow the default, or pin it
     * to a named profile.
     */
    fun assignProfile(context: Context, packageName: String, onChanged: () -> Unit = {}) {
        val store = ConfigGateway.get().readProfileStore()
        val default = store.defaultProfile()

        MaterialDialog(context).show {
            title(R.string.profile_assign_title)
            customView(R.layout.dialog_profile_assignment, scrollable = true, horizontalPadding = true)
            negativeButton(R.string.action_close)

            val dialog = this
            val view = getCustomView()
            val container = view.findViewById<LinearLayout>(R.id.profile_assignment_container)
            val assignedId = store.assignments[packageName]
            view.findViewById<TextView>(R.id.profile_assignment_package).text = packageName

            fun select(selectedId: String?) {
                val current = ConfigGateway.get().readProfileStore()
                // Following the default is the absence of an assignment, so
                // picking it drops the entry rather than storing a sentinel.
                val assignments = if (selectedId == null) {
                    current.assignments - packageName
                } else {
                    current.assignments + (packageName to selectedId)
                }

                save(context, current.copy(assignments = assignments)) {
                    dialog.dismiss()
                    onChanged()
                    val selected = selectedId?.let { id ->
                        current.profiles.firstOrNull { it.id == id }
                    } ?: current.defaultProfile() ?: return@save
                    offerTargetScope(context, selected, packageName) {
                        offerPlayServices(
                            context,
                            selected,
                            packageName,
                            followDefault = selectedId == null,
                            onChanged,
                        )
                    }
                }
            }

            addProfileRow(
                context = context,
                container = container,
                title = context.getString(
                    R.string.profile_assign_follow_default,
                    default?.displayName(context) ?: context.getString(R.string.profile_unnamed),
                ),
                summary = context.getString(R.string.profile_follow_default_detail),
                selected = assignedId == null,
                onClick = { select(null) },
            )

            store.profiles.forEach { profile ->
                addProfileRow(
                    context = context,
                    container = container,
                    title = profile.displayName(context),
                    summary = profile.featureSummary(context),
                    badge = context.getString(R.string.profile_badge_default)
                        .takeIf { profile.id == store.defaultProfileId },
                    selected = assignedId == profile.id,
                    onClick = { select(profile.id) },
                )
            }
        }
    }

    /** SIM properties, locale and time zone are hooked inside the target app process. */
    private fun offerTargetScope(
        context: Context,
        profile: Profile,
        packageName: String,
        afterwards: () -> Unit,
    ) {
        if (!profile.simEnabled && !profile.localeEnabled && !profile.timeZoneEnabled) {
            afterwards()
            return
        }

        MaterialDialog(context).show {
            title(R.string.target_scope_title)
            message(text = context.getString(R.string.target_scope_message, packageName))
            positiveButton(R.string.action_ok) { afterwards() }
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
     *
     * [followDefault] carries how the app itself was assigned, because Play
     * Services has to be given the same kind of assignment and not merely the
     * same profile. Pinning it to whichever profile is default today would hold
     * it there after the default moves, leaving the app on one profile and the
     * service answering for it on another.
     */
    private fun offerPlayServices(
        context: Context,
        profile: Profile,
        justAssigned: String,
        followDefault: Boolean,
        onChanged: () -> Unit,
    ) {
        if (!profile.locationEnabled || justAssigned == PLAY_SERVICES) return

        val store = ConfigGateway.get().readProfileStore()
        // What Play Services resolves to, not what is written down for it: with
        // both it and the app following the default there is nothing to fix,
        // and asking anyway would turn a matching pair into a pinned one.
        if (store.profileFor(PLAY_SERVICES)?.id == profile.id &&
            store.assignments.containsKey(PLAY_SERVICES) != followDefault
        ) {
            return
        }
        if (!isInstalled(context, PLAY_SERVICES)) return

        MaterialDialog(context).show {
            title(R.string.play_services_title)
            message(R.string.play_services_message)
            positiveButton(R.string.play_services_assign) {
                val current = ConfigGateway.get().readProfileStore()
                val assignments = if (followDefault) {
                    current.assignments - PLAY_SERVICES
                } else {
                    current.assignments + (PLAY_SERVICES to profile.id)
                }

                save(context, current.copy(assignments = assignments)) { onChanged() }
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

    private fun addProfileRow(
        context: Context,
        container: LinearLayout,
        title: String,
        summary: String,
        badge: String? = null,
        selected: Boolean = false,
        onClick: () -> Unit,
    ) {
        val row = LayoutInflater.from(context)
            .inflate(R.layout.profile_list_item, container, false)
        val card = row.findViewById<MaterialCardView>(R.id.profile_item_card)
        val badgeView = row.findViewById<TextView>(R.id.profile_item_badge)
        val trailing = row.findViewById<ImageView>(R.id.profile_item_trailing)

        row.findViewById<TextView>(R.id.profile_item_title).text = title
        row.findViewById<TextView>(R.id.profile_item_summary).text = summary

        val visibleBadge = badge ?: context.getString(R.string.profile_badge_selected)
            .takeIf { selected }
        badgeView.text = visibleBadge
        badgeView.visibility = if (visibleBadge == null) View.GONE else View.VISIBLE

        if (selected) {
            card.strokeWidth = 2
            card.strokeColor = context.getColor(R.color.brand_primary)
            card.setCardBackgroundColor(context.getColor(R.color.brand_primary_container))
            trailing.setImageResource(R.drawable.baseline_done_24)
            ImageViewCompat.setImageTintList(
                trailing,
                ColorStateList.valueOf(context.getColor(R.color.brand_primary)),
            )
        } else {
            trailing.setImageResource(R.drawable.baseline_chevron_right_24)
        }

        card.setOnClickListener { onClick() }
        container.addView(row)
    }

    private fun bindFeatureRow(
        context: Context,
        row: View,
        iconRes: Int,
        titleRes: Int,
        summary: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        row.findViewById<ImageView>(R.id.profile_feature_icon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.profile_feature_title).setText(titleRes)
        row.findViewById<TextView>(R.id.profile_feature_summary).text = summary
        row.findViewById<TextView>(R.id.profile_feature_state).apply {
            setText(if (enabled) R.string.profile_state_on else R.string.profile_state_off)
            setTextColor(context.getColor(
                if (enabled) R.color.module_on_active_container else R.color.app_on_surface_variant
            ))
            setBackgroundResource(
                if (enabled) R.drawable.profile_state_on_background
                else R.drawable.profile_state_off_background
            )
        }
        row.setOnClickListener { onClick() }
    }

    private fun Profile.featureSummary(context: Context): String {
        val features = buildList {
            if (locationEnabled) add(context.getString(R.string.title_location_spoof))
            if (cellEnabled) add(context.getString(R.string.title_cell_spoof))
            if (wifiEnabled) add(context.getString(R.string.title_wifi_spoof))
            if (simEnabled) add(context.getString(R.string.title_sim_spoof))
        }
        return features.joinToString(" · ").ifBlank {
            context.getString(R.string.profile_all_off)
        }
    }

    // endregion

    // region helpers

    private fun save(
        context: Context,
        store: ProfileStore,
        onSuccess: () -> Unit = {},
    ): Boolean {
        if (ConfigGateway.get().writeProfileStore(store)) {
            onSuccess()
            return true
        }

        Toast.makeText(context, R.string.config_save_failed, Toast.LENGTH_LONG).show()
        return false
    }

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

    /**
     * Dialogs that something above them has since edited the store under.
     *
     * Held weakly and keyed by the dialog itself: a menu that is dismissed
     * before it can act on the mark - deleting the profile it describes, say -
     * simply never redraws, and must not keep itself alive to find that out.
     */
    private val staleDialogs: MutableSet<MaterialDialog> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * Wraps [onChanged] so that acting on it also marks this dialog for a
     * redraw. Marking rather than redrawing on the spot is the point: the child
     * that reports the change is still on screen, and rebuilding underneath it
     * would put this menu back on top of the dialog the user is looking at.
     */
    private fun MaterialDialog.changed(onChanged: () -> Unit): () -> Unit = {
        staleDialogs.add(this)
        onChanged()
    }

    /**
     * Redraws a menu once the dialogs above it are gone.
     *
     * These menus stay open underneath the ones they open, which is what makes
     * Back walk back up the levels - but it also means their contents are a
     * snapshot taken when they were built. A profile created, renamed or
     * deleted above left this list naming something that no longer exists, and
     * the click handler resolving an index into that same snapshot then landed
     * on the wrong profile, or on none, which reads as the menu ignoring the
     * tap. Regaining window focus is the moment nothing is covering it.
     */
    private fun MaterialDialog.rebuildWhenStale(context: Context, rebuild: () -> Unit) {
        val decor = window?.decorView ?: return

        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus || !staleDialogs.remove(this)) return@addOnWindowFocusChangeListener

            decor.post {
                // A dialog opened again in the meantime - the editors chain
                // straight into the next menu - so wait for the next time this
                // one is really the top of the stack.
                if (!decor.hasWindowFocus()) {
                    staleDialogs.add(this)
                    return@post
                }
                if (!isShowing || (context as? Activity)?.isFinishing == true) return@post

                dismiss()
                rebuild()
            }
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

    private fun View.decimal(id: Int, fallback: Double): Double =
        CoordinateFormat.parse(text(id)) ?: fallback

    private fun View.switched(id: Int): Boolean =
        findViewById<SwitchMaterial>(id).isChecked

    // endregion
}
