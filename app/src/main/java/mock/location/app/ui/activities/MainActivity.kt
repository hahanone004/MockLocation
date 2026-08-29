package mock.location.app.ui.activities

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import mock.location.R
import mock.location.app.ui.config.ProfileEditors
import mock.location.databinding.ActivityMainBinding
import mock.location.xposed.helpers.ConfigGateway
import mock.location.xposed.helpers.reflect.Log
import mock.location.xposed.helpers.reflect.runOnMainThread
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@ExperimentalStdlibApi
class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var binding: ActivityMainBinding

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
                try {
                    ConfigGateway.get().migrateWhitelistIfNeeded(applicationContext)
                } catch (t: Throwable) {
                    // A migration that cannot run leaves the old config in
                    // place and is retried next launch; it must not cost the
                    // user the settings screen.
                    Log.e("the legacy migration could not run", t)
                    migrationPending.set(true)
                }
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
