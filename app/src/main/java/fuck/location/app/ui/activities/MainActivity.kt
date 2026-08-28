package fuck.location.app.ui.activities

import android.annotation.SuppressLint
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import androidx.annotation.Keep
import androidx.appcompat.content.res.AppCompatResources
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

    @SuppressLint("CheckResult")
    override fun onClick(v: View) {
        when (v.id) {
            R.id.menu_location_credit -> startActivity(Intent(this, ModuleActivity::class.java))
            R.id.menu_profiles -> ProfileEditors.manageProfiles(this)
            R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))

            // The three feature entries edit whichever profile is the default;
            // each editor carries that profile's switch for its own feature.
            else -> {
                val defaultProfileId =
                    ConfigGateway.get().readProfileStore().defaultProfile()?.id ?: return

                when (v.id) {
                    R.id.menu_location_spoof -> ProfileEditors.editLocation(this, defaultProfileId)
                    R.id.menu_cell_spoof -> ProfileEditors.editCell(this, defaultProfileId)
                    R.id.menu_wifi_spoof -> ProfileEditors.editWifi(this, defaultProfileId)
                }
            }
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
