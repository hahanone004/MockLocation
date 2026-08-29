package fuck.location.app.ui.activities

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.idanatz.oneadapter.OneAdapter
import com.idanatz.oneadapter.external.modules.ItemModule
import fuck.location.R
import fuck.location.databinding.ActivitySelectAppsBinding
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import fuck.location.xposed.helpers.reflect.runOnMainThread
import com.idanatz.oneadapter.external.event_hooks.ClickEventHook
import com.idanatz.oneadapter.external.modules.EmptinessModule
import com.scwang.smart.refresh.layout.api.RefreshLayout
import kotlin.concurrent.thread

import fuck.location.app.ui.config.ProfileEditors
import fuck.location.app.ui.models.AppListModel
import fuck.location.xposed.helpers.ConfigGateway
import java.util.stream.Collectors

@ExperimentalStdlibApi
class ModuleActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var refreshLayout: RefreshLayout

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var oneAdapter: OneAdapter
    private var packageInfos: List<AppListModel> = arrayListOf()   // Prevent from search crash

    private var searchKeyword = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfigGateway.get().setCustomContext(applicationContext)

        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        recyclerView = binding.recycler
        oneAdapter = OneAdapter(recyclerView) {
            itemModules += AppListModule(this@ModuleActivity) { this@ModuleActivity.refresh() }
            emptinessModule = EmptyListModule()
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        keepLastAppOffTheNavigationBar(recyclerView)

        refreshLayout = binding.refreshLayout
        refreshLayout.setOnRefreshListener { refresh() }.autoRefresh()
    }

    /**
     * The window runs edge to edge, so the list ends underneath the gesture bar
     * unless the bottom inset is padded in. The layout's own padding is the
     * baseline, since insets are dispatched more than once.
     */
    private fun keepLastAppOffTheNavigationBar(list: RecyclerView) {
        val base = list.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(list) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = base + bars.bottom)
            insets
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_app_list, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val searchView = menu?.findItem(R.id.menu_search)?.actionView as SearchView?
            ?: return super.onPrepareOptionsMenu(menu)

        searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchKeyword = newText?.lowercase() ?: ""

                thread {
                    updateSearchResult(searchKeyword)
                }

                return true
            }
        })

        searchView.findViewById<View>(
            androidx.appcompat.R.id.search_edit_frame).layoutDirection = View.LAYOUT_DIRECTION_INHERIT

        return super.onPrepareOptionsMenu(menu)
    }

    private fun refresh() {
        thread {
            initAppListView()
            runOnMainThread { refreshLayout.finishRefresh() }
        }
    }

    private fun initAppListView() {
        updateInstalledPackages()
        updateSearchResult(searchKeyword)
    }

    private fun updateInstalledPackages() {
        val store = ConfigGateway.get().readProfileStore()
        val displayNameComparator = ApplicationInfo.DisplayNameComparator(this.packageManager)

        packageInfos = this.packageManager.getInstalledPackages(0)
            .parallelStream().sorted { lhs, rhs ->
                // Apps being spoofed float to the top; the rest stay alphabetical.
                val lAssigned = store.assignments.containsKey(lhs.packageName)
                val rAssigned = store.assignments.containsKey(rhs.packageName)
                when {
                    lAssigned == rAssigned ->
                        displayNameComparator.compare(lhs.applicationInfo, rhs.applicationInfo)
                    lAssigned -> -1
                    else -> 1
                }
            }.filter { it.applicationInfo != null }    // null for packages we cannot fully see
            .map {
                val applicationInfo = it.applicationInfo!!
                val packageName = applicationInfo.packageName

                AppListModel(applicationInfo.loadLabel(packageManager).toString(),
                    packageName,
                    applicationInfo.loadIcon(packageManager),
                    ProfileEditors.assignmentLabel(this, store, packageName))
            }.collect(Collectors.toList())
    }

    private fun updateSearchResult(keyword: String) {

        val searchResult = if (keyword.isNotEmpty()) {
            packageInfos.parallelStream().filter {
                it.title.lowercase().contains(keyword)
            }.collect(Collectors.toList())
        } else {
            packageInfos
        }

        runOnMainThread {
            oneAdapter.setItems(searchResult)
        }
    }

    class AppListModule(
        private val context: Context,
        private val onProfileAssigned: (String) -> Unit,
    ) : ItemModule<AppListModel>() {
        init {
            config {
                layoutResource = R.layout.app_list_model
            }
            onBind { model, viewBinder, metadata ->
                val title = viewBinder.findViewById<TextView>(R.id.app_list_module_title)
                val icon = viewBinder.findViewById<ImageView>(R.id.app_list_module_icon)
                val profile = viewBinder.findViewById<TextView>(R.id.app_list_module_profile)

                title.text = model.title
                icon.setImageDrawable(model.icon)

                profile.text = model.profileLabel
            }
            eventHooks += ClickEventHook<AppListModel>().apply {
                onClick { model, _, _ ->
                    ProfileEditors.assignProfile(context, model.packageName) {
                        onProfileAssigned(model.packageName)
                    }
                }
            }
            onUnbind { model, viewBinder, metadata ->

            }
        }
    }

    class EmptyListModule : EmptinessModule() {
        init {
            config {
                layoutResource = R.layout.empty_app_list
            }
        }
    }
}