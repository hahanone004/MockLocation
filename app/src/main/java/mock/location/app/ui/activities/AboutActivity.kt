package mock.location.app.ui.activities

import android.annotation.SuppressLint
import android.widget.ImageView
import android.widget.TextView
import com.drakeet.about.AbsAboutActivity
import com.drakeet.about.Card
import com.drakeet.about.Category
import mock.location.BuildConfig
import mock.location.R

class AboutActivity: AbsAboutActivity() {
    @SuppressLint("SetTextI18n")    // version.txt doesn't need translation
    override fun onCreateHeader(icon: ImageView, slogan: TextView, version: TextView) {
        icon.setImageResource(R.mipmap.ic_launcher)
        slogan.setText(R.string.about_slogan)
        version.text = "v" + BuildConfig.VERSION_NAME
    }

    override fun onItemsCreated(items: MutableList<Any>) {
        items.add(Category(getString(R.string.about_summary_title)))
        items.add(Card(getString(R.string.about_summary_content)))
        items.add(Category(getString(R.string.about_usage_title)))
        items.add(Card(getString(R.string.about_usage_content)))
        items.add(Category(getString(R.string.about_notice_title)))
        items.add(Card(getString(R.string.about_notice_content)))
    }
}
