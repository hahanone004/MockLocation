package fuck.location.app.ui.models

import android.graphics.drawable.Drawable
import com.idanatz.oneadapter.external.interfaces.Diffable

class AppListModel(
    val title: String,
    val packageName: String,
    var icon: Drawable,
    /** Empty unless the app is pointed at something other than the default. */
    var profileLabel: String = "",
) : Diffable {
    override fun areContentTheSame(other: Any): Boolean =
        other is AppListModel && title == other.title && profileLabel == other.profileLabel

    /*
     * The identity has to be the package: a random id made every rebind look
     * like a new row, so the list could not diff and reordering redrew
     * everything.
     */
    override val uniqueIdentifier: Long = packageName.hashCode().toLong()
}
