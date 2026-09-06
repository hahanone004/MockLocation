package mock.location.app.ui.activities

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import mock.location.BuildConfig
import mock.location.R
import mock.location.app.ui.config.ConfigTransfer
import mock.location.app.ui.config.ProfileEditors
import mock.location.app.ui.models.ProfileStore
import mock.location.databinding.ActivityMainBinding
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.Log
import mock.location.xposed.helpers.reflect.runOnMainThread
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@ExperimentalStdlibApi
class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var binding: ActivityMainBinding

    /*
     * The two document pickers behind backup and restore. Registered as fields
     * rather than on demand: a launcher has to exist before the activity is
     * started, since the process can be killed while the picker is in front of
     * it and the result arrives at a fresh instance.
     */

    private val exportConfig = registerForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigTransfer.MIME_TYPE)
    ) { destination -> destination?.let(::exportTo) }

    private val importConfig = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { source -> source?.let(::importFrom) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfigGateway.get().setCustomContext(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setModuleState(binding)
        keepMenuOffTheNavigationBar(binding.menuScrollBar)

        binding.menuLocationCredit.setOnClickListener(this)
        binding.menuProfiles.setOnClickListener(this)
        binding.menuBackup.setOnClickListener(this)
        binding.menuAbout.setOnClickListener(this)
    }

    /**
     * The card counts profiles and assignments, and both change from the menus
     * this screen opens, so it is read here rather than once at creation.
     */
    override fun onResume() {
        super.onResume()
        refreshFrameworkStatus()
    }

    /**
     * The window runs edge to edge, so the last menu entry ends up under the
     * gesture bar unless the bottom inset is padded in.
     */
    private fun keepMenuOffTheNavigationBar(menu: ScrollView) {
        val base = menu.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(menu) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = base + bars.bottom)
            insets
        }
    }

    @SuppressLint("CheckResult")
    override fun onClick(v: View) {
        when (v.id) {
            R.id.menu_location_credit -> startActivity(Intent(this, ModuleActivity::class.java))
            // The default profile is edited through the profile list like any
            // other, so there is nothing here duplicating it.
            R.id.menu_profiles -> ProfileEditors.manageProfiles(this)
            R.id.menu_backup -> offerBackup()
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun setModuleState(binding: ActivityMainBinding) {
        if (isModuleActivated()) {
            val foreground = getColor(R.color.module_on_active_container)
            binding.moduleStatusCard.setCardBackgroundColor(getColor(R.color.module_active_container))
            binding.moduleStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this,
                R.drawable.baseline_check_circle_24
            ))
            tintStatus(binding, foreground)
            binding.moduleStatusText.text = getString(R.string.card_title_activated)
            binding.serviceStatusText.text = getString(R.string.card_detail_activated)

            binding.serveTimes.text = getString(R.string.card_framework_checking)
        } else {
            val foreground = getColor(R.color.module_on_inactive_container)
            binding.moduleStatusCard.setCardBackgroundColor(getColor(R.color.module_inactive_container))
            binding.moduleStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this,
                R.drawable.baseline_error_24
            ))
            tintStatus(binding, foreground)
            binding.moduleStatusText.text = getText(R.string.card_title_not_activated)
            binding.serviceStatusText.text = getText(R.string.card_detail_not_activated)
            binding.serveTimes.visibility = View.GONE

            binding.menuProfiles.visibility = View.GONE
            binding.menuLocationCredit.visibility = View.GONE
            // The config it would carry lives behind the framework half, which
            // is not answering anything while the module is not loaded.
            binding.menuBackup.visibility = View.GONE
        }
    }

    private fun tintStatus(binding: ActivityMainBinding, color: Int) {
        binding.moduleStatusLabel.setTextColor(color)
        binding.moduleStatusText.setTextColor(color)
        binding.serviceStatusText.setTextColor(color)
        binding.serveTimes.setTextColor(color)
        ImageViewCompat.setImageTintList(binding.moduleStatusIcon, ColorStateList.valueOf(color))
    }

    /**
     * Asks the framework half what it is doing, off the main thread.
     *
     * Everything below crosses a binder into system_server, and the legacy
     * migration writes a file behind that same call. Running it from onCreate
     * meant the first frame waited on a round trip that, when the framework
     * half is not there at all, is a round trip to an exception.
     */
    private fun refreshFrameworkStatus() {
        // Without the module loaded there is no framework half to ask, and the
        // card that would carry the answer is not on screen.
        if (!isModuleActivated()) return

        thread {
            if (migrationPending.compareAndSet(true, false)) {
                val migrated = try {
                    ConfigGateway.get().migrateWhitelistIfNeeded(applicationContext)
                } catch (t: Throwable) {
                    // A migration that cannot run leaves the old config in
                    // place; it must not cost the user the settings screen.
                    Log.e("the legacy migration could not run", t)
                    false
                }

                // Postponing is the quiet case and the common one - the
                // framework half is not answering this early after a boot - and
                // it returns rather than throws. Clearing the flag on it would
                // retire the migration for the life of the process, so the next
                // onResume would not pick it up once the framework arrived and
                // the user would have to kill the app.
                if (!migrated) migrationPending.set(true)
            }

            val status = frameworkStatus()
            runOnMainThread {
                if (!isFinishing && !isDestroyed) {
                    binding.serviceStatusText.text = status.detail
                    binding.serveTimes.text = status.summary
                }
            }
        }
    }

    /**
     * What the framework half of the module is doing, which is the half the
     * activation card cannot see: that card turns green because the module was
     * loaded into this app, while every spoof lives in system_server and needs
     * to be in the module's scope separately.
     */
    private fun frameworkStatus(): FrameworkStatus {
        if (!ConfigGateway.get().isFrameworkReachable()) {
            return FrameworkStatus(
                getString(R.string.card_framework_disconnected),
                getString(R.string.card_framework_missing),
            )
        }

        val store = ConfigGateway.get().readProfileStore()

        return FrameworkStatus(
            getString(R.string.card_framework_connected),
            getString(
                R.string.card_framework_ready,
                store.profiles.count { !it.spoofsNothing },
                store.assignments.size,
            ),
        )
    }

    // region backup and restore

    /**
     * Export and import behind one entry. They are the two halves of the same
     * thing, and the config file is the only way a profile leaves this device:
     * it lives behind system_server in a directory whose name is randomised,
     * so a reflash or a new phone otherwise means typing every profile again.
     */
    private fun offerBackup() {
        MaterialDialog(this).show {
            title(R.string.title_config_backup)
            message(R.string.backup_hint)
            listItems(
                items = listOf(
                    getString(R.string.backup_action_export),
                    getString(R.string.backup_action_import),
                )
            ) { dialog, index, _ ->
                if (index == 0) {
                    exportConfig.launch(ConfigTransfer.suggestedFileName())
                } else {
                    importConfig.launch(ConfigTransfer.OPENABLE_TYPES)
                }
                // A close button keeps this from dismissing itself, and it
                // would otherwise be sitting behind the picker on the way back.
                dialog.dismiss()
            }
            negativeButton(R.string.action_close) { it.dismiss() }
        }
    }

    /**
     * Off the main thread throughout: reading the config is a binder round trip
     * into system_server, and the write goes through whichever document
     * provider the user picked, which may be a cloud one.
     */
    private fun exportTo(destination: Uri) {
        thread {
            val gateway = ConfigGateway.get()
            // A read that cannot reach the framework half answers with an empty
            // store rather than failing, and exporting that would quietly hand
            // the user a file with none of their profiles in it.
            if (!gateway.isFrameworkReachable()) {
                toast(getString(R.string.backup_framework_missing))
                return@thread
            }

            val store = gateway.readProfileStore()
            val written = try {
                // "wt" truncates. Writing over a longer file without it leaves
                // the tail of the old one behind, which parses as nothing.
                val stream = contentResolver.openOutputStream(destination, "wt")
                    ?: error("the picked document could not be opened for writing")
                stream.use {
                    it.write(
                        ConfigTransfer.write(store, BuildConfig.VERSION_NAME)
                            .toByteArray(Charsets.UTF_8)
                    )
                }
                true
            } catch (t: Throwable) {
                Log.e("the configuration could not be exported", t)
                false
            }

            toast(
                if (written) {
                    getString(
                        R.string.backup_export_done,
                        store.profiles.size,
                        store.assignments.size,
                    )
                } else {
                    getString(R.string.backup_export_failed)
                }
            )
        }
    }

    private fun importFrom(source: Uri) {
        thread {
            val text = try {
                contentResolver.openInputStream(source)?.use { stream ->
                    // One byte past the cap is all it takes to know it is over
                    // it, and nothing larger is read into memory at all.
                    stream.readNBytes(ConfigTransfer.MAX_BYTES + 1)
                        .takeIf { it.size <= ConfigTransfer.MAX_BYTES }
                        ?.toString(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w("the picked file could not be read: $t")
                null
            }

            val outcome = text?.let(ConfigTransfer::read)
                ?: ConfigTransfer.Outcome.Refused(ConfigTransfer.Reason.UNREADABLE)

            runOnMainThread {
                if (isFinishing || isDestroyed) return@runOnMainThread

                when (outcome) {
                    is ConfigTransfer.Outcome.Ready -> confirmImport(outcome.store)
                    is ConfigTransfer.Outcome.Refused ->
                        Toast.makeText(this, refusalText(outcome.reason), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Nothing is merged: which profile an app follows is part of the file, so
     * a merge would have to answer what happens to an assignment naming a
     * profile that exists on both sides. Replacing is the answer the user can
     * predict, and it is worth confirming out loud.
     */
    private fun confirmImport(store: ProfileStore) {
        MaterialDialog(this).show {
            title(R.string.backup_import_title)
            message(
                text = getString(
                    R.string.backup_import_message,
                    store.profiles.size,
                    store.assignments.size,
                )
            )
            positiveButton(R.string.backup_import_action) {
                thread {
                    val saved = ConfigGateway.get().writeProfileStore(store)

                    runOnMainThread {
                        if (isFinishing || isDestroyed) return@runOnMainThread

                        Toast.makeText(
                            this@MainActivity,
                            if (saved) R.string.backup_import_done else R.string.config_save_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                        // The status card counts profiles and assignments.
                        if (saved) refreshFrameworkStatus()
                    }
                }
            }
            negativeButton(R.string.action_discard) { it.dismiss() }
        }
    }

    private fun refusalText(reason: ConfigTransfer.Reason): String = getString(
        when (reason) {
            ConfigTransfer.Reason.UNREADABLE -> R.string.backup_import_unreadable
            ConfigTransfer.Reason.NOT_A_CONFIG -> R.string.backup_import_not_config
            ConfigTransfer.Reason.FROM_A_NEWER_BUILD -> R.string.backup_import_newer
            ConfigTransfer.Reason.NO_PROFILES -> R.string.backup_import_empty
        }
    )

    /** Both halves of a transfer report from a worker thread. */
    private fun toast(message: String) = runOnMainThread {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    // endregion

    @Keep
    fun isModuleActivated(): Boolean {
        return false
    }

    private companion object {
        /** Once per process: the migration itself is idempotent, the reads are not free. */
        val migrationPending = AtomicBoolean(true)
    }

    private data class FrameworkStatus(val detail: String, val summary: String)
}
