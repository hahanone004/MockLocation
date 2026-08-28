package fuck.location.app.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.switchmaterial.SwitchMaterial
import fuck.location.R
import fuck.location.app.ui.config.ProfileEditors
import fuck.location.app.ui.models.Profile
import fuck.location.databinding.ActivityMainBinding
import fuck.location.xposed.helpers.ConfigGateway

@ExperimentalStdlibApi
class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfigGateway.get().setCustomContext(applicationContext)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setModuleState(binding)

        binding.menuLocationCredit.setOnClickListener(this)
        binding.menuProfiles.setOnClickListener(this)
        binding.menuLocationSpoof.setOnClickListener(this)
        binding.menuCellSpoof.setOnClickListener(this)
        binding.menuWifiSpoof.setOnClickListener(this)
        binding.menuAbout.setOnClickListener(this)

        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()

        // The profile editor and the whitelist screen both change what the
        // switches should read, so refresh them rather than trusting onCreate.
        bindSwitches()
    }

    @SuppressLint("CheckResult")
    override fun onClick(v: View) {
        when (v.id) {
            R.id.menu_location_credit -> startActivity(Intent(this, ModuleActivity::class.java))
            R.id.menu_profiles -> ProfileEditors.manageProfiles(this) { bindSwitches() }
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))

            // The three feature entries edit whichever profile is the default.
            else -> {
                val defaultProfileId = defaultProfileId() ?: return

                when (v.id) {
                    R.id.menu_location_spoof -> ProfileEditors.editLocation(this, defaultProfileId)
                    R.id.menu_cell_spoof -> ProfileEditors.editCell(this, defaultProfileId)
                    R.id.menu_wifi_spoof -> ProfileEditors.editWifi(this, defaultProfileId)
                }
            }
        }
    }

    private fun defaultProfileId(): String? = ConfigGateway.get().readProfileStore().defaultProfile()?.id

    /**
     * Points the three switches at the default profile. Setting the checked
     * state fires the listener, so the listener is detached first; otherwise
     * every refresh would write the config straight back.
     */
    private fun bindSwitches() {
        val store = ConfigGateway.get().readProfileStore()
        val profile = store.defaultProfile() ?: return

        bindSwitch(binding.switchLocation, profile.locationEnabled) { it.copy(locationEnabled = this) }
        bindSwitch(binding.switchCell, profile.cellEnabled) { it.copy(cellEnabled = this) }
        bindSwitch(binding.switchWifi, profile.wifiEnabled) { it.copy(wifiEnabled = this) }
    }

    private fun bindSwitch(
        switch: SwitchMaterial,
        checked: Boolean,
        update: Boolean.(Profile) -> Profile,
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.setOnCheckedChangeListener { _, isChecked ->
            val store = ConfigGateway.get().readProfileStore()
            val profile = store.defaultProfile() ?: return@setOnCheckedChangeListener

            ConfigGateway.get().writeProfileStore(
                store.copy(
                    profiles = store.profiles.map {
                        if (it.id == profile.id) isChecked.update(it) else it
                    }
                )
            )
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

            binding.serveTimes.text = getString(R.string.card_serve_time)
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
            binding.menuLocationSpoof.visibility = View.GONE
            binding.menuCellSpoof.visibility = View.GONE
            binding.menuWifiSpoof.visibility = View.GONE
        }
    }

    @Keep
    fun isModuleActivated(): Boolean {
        return false
    }
}
