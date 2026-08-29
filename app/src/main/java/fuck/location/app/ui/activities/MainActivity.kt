package fuck.location.app.ui.activities

import android.annotation.SuppressLint
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
import fuck.location.R
import fuck.location.app.ui.config.ProfileEditors
import fuck.location.databinding.ActivityMainBinding
import fuck.location.xposed.helpers.ConfigGateway

@ExperimentalStdlibApi
class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfigGateway.get().setCustomContext(applicationContext)
        ConfigGateway.get().migrateWhitelistIfNeeded(this)

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
            binding.moduleStatusCard.setCardBackgroundColor(getColor(R.color.purple_500))
            binding.moduleStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this,
                R.drawable.baseline_check_circle_24
            ))
            binding.moduleStatusText.text = getString(R.string.card_title_activated)
            binding.serviceStatusText.text = getString(R.string.card_detail_activated)

            binding.serveTimes.text = frameworkStatus()
        } else {
            binding.moduleStatusCard.setCardBackgroundColor(getColor(R.color.red_500))
            binding.moduleStatusIcon.setImageDrawable(AppCompatResources.getDrawable(this,
                R.drawable.baseline_error_24
            ))
            binding.moduleStatusText.text = getText(R.string.card_title_not_activated)
            binding.serviceStatusText.text = getText(R.string.card_detail_not_activated)
            binding.serveTimes.visibility = View.GONE

            binding.menuProfiles.visibility = View.GONE
            binding.menuLocationCredit.visibility = View.GONE
        }
    }

    /**
     * What the framework half of the module is doing, which is the half the
     * activation card cannot see: that card turns green because the module was
     * loaded into this app, while every spoof lives in system_server and needs
     * to be in the module's scope separately.
     */
    private fun frameworkStatus(): String {
        if (!ConfigGateway.get().isFrameworkReachable()) {
            return getString(R.string.card_framework_missing)
        }

        val store = ConfigGateway.get().readProfileStore()

        return getString(
            R.string.card_framework_ready,
            store.profiles.count { !it.spoofsNothing },
            store.assignments.size,
        )
    }

    @Keep
    fun isModuleActivated(): Boolean {
        return false
    }
}
