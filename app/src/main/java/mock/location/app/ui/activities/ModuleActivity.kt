package mock.location.app.ui.activities

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
import mock.location.R
import mock.location.databinding.ActivitySelectAppsBinding
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import mock.location.xposed.helpers.reflect.runOnMainThread
import com.idanatz.oneadapter.external.event_hooks.ClickEventHook
import com.idanatz.oneadapter.external.modules.EmptinessModule
import com.scwang.smart.refresh.layout.api.RefreshLayout
import kotlin.concurrent.thread

import mock.location.app.ui.config.ProfileEditors
import mock.location.app.ui.models.AppListModel
import mock.location.xposed.helpers.ConfigGateway
import java.util.concurrent.atomic.AtomicLong
import java.util.stream.Collectors

@ExperimentalStdlibApi
class ModuleActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var refreshLayout: RefreshLayout

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var oneAdapter: OneAdapter

    /** Written on a worker, read on others; publish it rather than tear it. */
    @Volatile private var packageInfos: List<AppListModel> = emptyList()
    @Volatile private var searchKeyword = ""

    /**
     * Which render is the current one. Every keystroke used to start its own
     * thread and hand its results straight to the adapter, so a slow filter for
     * an early keystroke could land after - and overwrite - a later one's.
     */
    private val renderGeneration = AtomicLong(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ConfigGateway.get().setCustomContext(applicationContext)

        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        recyclerView = binding.recycler
        oneAdapter = OneAdapter(recyclerView) {
            itemModules += AppListModule(this@ModuleActivity) {
                this@ModuleActivity.refreshAssignments()
            }
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
                val keyword = newText?.lowercase() ?: ""
                searchKeyword = keyword

                val generation = renderGeneration.incrementAndGet()
                thread { render(keyword, generation) }

                return true
            }
        })

        searchView.findViewById<View>(
            androidx.appcompat.R.id.search_edit_frame).layoutDirection = View.LAYOUT_DIRECTION_INHERIT

        return super.onPrepareOptionsMenu(menu)
    }

    /** The whole list, labels and icons alike. Pull to refresh, and on entry. */
    private fun refresh() {
        val generation = renderGeneration.incrementAndGet()
        thread {
            updateInstalledPackages()
            render(searchKeyword, generation)
            runOnMainThread { refreshLayout.finishRefresh() }
        }
    }

    /**
     * Only the assignment labels, after one has been changed from this screen.
     *
     * Reloading everything was the old answer, but that re-enumerated every
     * installed package and decoded every icon on the device for a single tap.
     * Nothing else can have changed - and assigning one app can still relabel
     * another, since the Play Services prompt assigns a second package - so
     * every label is recomputed and no package is looked up again.
     *
     * New models rather than edited ones: the adapter diffs by content, and a
     * label changed in place is invisible to it.
     */
    private fun refreshAssignments() {
        val generation = renderGeneration.incrementAndGet()
        thread {
            val store = ConfigGateway.get().readProfileStore()

            packageInfos = packageInfos.map { model ->
                AppListModel(
                    model.title,
                    model.packageName,
                    model.icon,
                    ProfileEditors.assignmentLabel(this, store, model.packageName),
                )
            }
            render(searchKeyword, generation)
        }
    }

    private fun updateInstalledPackages() {
        val store = ConfigGateway.get().readProfileStore()
        val displayNameComparator = ApplicationInfo.DisplayNameComparator(this.packageManager)

        packageInfos = this.packageManager.getInstalledPackages(0)
            .parallelStream()
            // Before the sort, not after: applicationInfo is null for packages
            // we cannot fully see, and the comparator below dereferences it.
            .filter { it.applicationInfo != null }
            .sorted { lhs, rhs ->
                // Apps being spoofed float to the top; the rest stay alphabetical.
                val lAssigned = store.assignments.containsKey(lhs.packageName)
                val rAssigned = store.assignments.containsKey(rhs.packageName)
                when {
                    lAssigned == rAssigned ->
                        displayNameComparator.compare(lhs.applicationInfo, rhs.applicationInfo)
                    lAssigned -> -1
                    else -> 1
                }
            }
            .map {
                val applicationInfo = it.applicationInfo!!
                val packageName = applicationInfo.packageName

                AppListModel(applicationInfo.loadLabel(packageManager).toString(),
                    packageName,
                    applicationInfo.loadIcon(packageManager),
                    ProfileEditors.assignmentLabel(this, store, packageName))
            }.collect(Collectors.toList())
    }

    private fun render(keyword: String, generation: Long) {
        val snapshot = packageInfos
        val searchResult = if (keyword.isEmpty()) {
            snapshot
        } else {
            snapshot.filter { it.title.lowercase().contains(keyword) }
        }

        runOnMainThread {
            // A render for an earlier keystroke must not land on a later one.
            if (generation == renderGeneration.get()) oneAdapter.setItems(searchResult)
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