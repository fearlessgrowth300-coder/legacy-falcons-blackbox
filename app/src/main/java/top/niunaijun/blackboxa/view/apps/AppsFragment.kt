package top.niunaijun.blackboxa.view.apps

import android.graphics.Point
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.FragmentAppsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.MemoryManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.main.MainActivity
import java.util.*
import kotlin.math.abs



class AppsFragment : Fragment() {

    var userID: Int = 0

    private lateinit var viewModel: AppsViewModel

    private lateinit var mAdapter: RVAdapter<AppInfo>

    private val viewBinding: FragmentAppsBinding by inflate()

    private var popupMenu: PopupMenu? = null

    companion object {
        private const val TAG = "AppsFragment"
        
        fun newInstance(userID:Int): AppsFragment {
            val fragment = AppsFragment()
            // FragmentStateAdapter asks for stable IDs before onCreate() runs.
            // Set the ID immediately so every BlackBox user has a unique page.
            fragment.userID = userID
            val bundle = bundleOf("userID" to userID)
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            viewModel =
                ViewModelProvider(this, InjectionUtil.getAppsFactory()).get(AppsViewModel::class.java)
            userID = requireArguments().getInt("userID", 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            viewBinding.stateView.showEmpty()

            mAdapter =
                RVAdapter<AppInfo>(requireContext(), AppsAdapter()).bind(viewBinding.recyclerView)

            viewBinding.recyclerView.adapter = mAdapter
            
            
            // Three wider cards keep clone names readable and give each isolated app a clear tap
            // target. Four columns made renamed Instagram/WhatsApp clones indistinguishable.
            val layoutManager = GridLayoutManager(requireContext(), 3)
            layoutManager.isItemPrefetchEnabled = true
            layoutManager.initialPrefetchItemCount = 8
            viewBinding.recyclerView.layoutManager = layoutManager
            
            
            viewBinding.recyclerView.setItemViewCacheSize(20)
            viewBinding.recyclerView.setHasFixedSize(true)
            
            
            viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    try {
                        super.onScrollStateChanged(recyclerView, newState)
                        when (newState) {
                            RecyclerView.SCROLL_STATE_IDLE -> {
                                
                                MemoryManager.optimizeMemoryForRecyclerView()
                            }
                            RecyclerView.SCROLL_STATE_DRAGGING -> {
                                
                                
                            }
                            RecyclerView.SCROLL_STATE_SETTLING -> {
                                
                                
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in scroll state change: ${e.message}")
                    }
                }
                
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    try {
                        super.onScrolled(recyclerView, dx, dy)
                        
                        if (Math.abs(dy) > 100) {
                            
                            
                            
                            if (MemoryManager.isMemoryCritical()) {
                                Log.w(TAG, "Memory critical during fast scrolling, forcing GC")
                                MemoryManager.forceGarbageCollectionIfNeeded()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in scroll: ${e.message}")
                    }
                }
            })

            val touchCallBack = AppsTouchCallBack { from, to ->
                try {
                    onItemMove(from, to)
                    viewModel.updateSortLiveData.postValue(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in touch callback: ${e.message}")
                }
            }

            val itemTouchHelper = ItemTouchHelper(touchCallBack)
            itemTouchHelper.attachToRecyclerView(viewBinding.recyclerView)

            mAdapter.setItemClickListener { _, data, _ ->
                try {
                    showLoading()
                    viewModel.launchApk(data.packageName, userID)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching app: ${e.message}")
                    hideLoading()
                }
            }

            interceptTouch()
            setOnLongClick()
            return viewBinding.root
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreateView: ${e.message}")
            
            return View(requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        try {
            super.onViewCreated(view, savedInstanceState)
            initData()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewCreated: ${e.message}")
        }
    }

    override fun onStart() {
        try {
            super.onStart()
            
            
            try {
                BlackBoxCore.get().addServiceAvailableCallback {
                    Log.d(TAG, "Services became available, refreshing app list")
                    
                    viewModel.getInstalledAppsWithRetry(userID)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering service available callback: ${e.message}")
            }
            
            viewModel.getInstalledAppsWithRetry(userID)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStart: ${e.message}")
        }
    }

    
    private fun interceptTouch() {
        try {
            val point = Point()
            var isScrolling = false
            var scrollStartTime = 0L
            
            viewBinding.recyclerView.setOnTouchListener { _, e ->
                try {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            
                            isScrolling = false
                            scrollStartTime = System.currentTimeMillis()
                            point.set(0, 0)
                        }
                        
                        MotionEvent.ACTION_UP -> {
                            val scrollDuration = System.currentTimeMillis() - scrollStartTime
                            
                            
                            if (!isScrolling && !isMove(point, e) && scrollDuration < 500) {
                                try {
                                    popupMenu?.show()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error showing popup menu: ${e.message}")
                                }
                            }
                            
                            popupMenu = null
                            point.set(0, 0)
                            isScrolling = false
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (point.x == 0 && point.y == 0) {
                                point.x = e.rawX.toInt()
                                point.y = e.rawY.toInt()
                            }
                            
                            
                            if (isMove(point, e)) {
                                isScrolling = true
                                popupMenu?.dismiss()
                            }
                            
                            
                            isDownAndUp(point, e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in touch listener: ${e.message}")
                }
                return@setOnTouchListener false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in interceptTouch: ${e.message}")
        }
    }

    private fun isMove(point: Point, e: MotionEvent): Boolean {
        return try {
            val max = 40

            val x = point.x
            val y = point.y

            val xU = abs(x - e.rawX)
            val yU = abs(y - e.rawY)
            xU > max || yU > max
        } catch (e: Exception) {
            Log.e(TAG, "Error in isMove: ${e.message}")
            false
        }
    }

    private fun isDownAndUp(point: Point, e: MotionEvent) {
        try {
            val min = 10
            val y = point.y
            val yU = y - e.rawY

            if (abs(yU) > min) {
                try {
                    (requireActivity() as? MainActivity)?.showFloatButton(yU < 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing/hiding float button: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in isDownAndUp: ${e.message}")
        }
    }

    private fun onItemMove(fromPosition: Int, toPosition: Int) {
        try {
            
            val items = mAdapter.getItems()
            if (fromPosition < 0 || toPosition < 0 || 
                fromPosition >= items.size || toPosition >= items.size) {
                Log.w(TAG, "Invalid positions for move: from=$fromPosition, to=$toPosition, size=${items.size}")
                return
            }
            
            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) {
                    try {
                        Collections.swap(items, i, i + 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error swapping items at position $i: ${e.message}")
                        return
                    }
                }
            } else {
                for (i in fromPosition downTo toPosition + 1) {
                    try {
                        Collections.swap(items, i, i - 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error swapping items at position $i: ${e.message}")
                        return
                    }
                }
            }
            
            try {
                mAdapter.notifyItemMoved(fromPosition, toPosition)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying item moved: ${e.message}")
                
                mAdapter.notifyDataSetChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onItemMove: ${e.message}")
        }
    }

    private fun setOnLongClick() {
        try {
            mAdapter.setItemLongClickListener { view, data, _ ->
                try {
                    popupMenu = PopupMenu(requireContext(),view).also {
                        it.inflate(R.menu.app_menu)
                        it.setOnMenuItemClickListener { item ->
                            try {
                                when (item.itemId) {
                                    R.id.app_remove -> {
                                        if (data.isXpModule) {
                                            toast(R.string.uninstall_module_toast)
                                        } else {
                                            unInstallApk(data)
                                        }
                                    }

                                    R.id.app_clear -> {
                                        clearApk(data)
                                    }

                                    R.id.app_stop -> {
                                        stopApk(data)
                                    }

                                    R.id.app_shortcut -> {
                                        ShortcutUtil.createShortcut(requireContext(), userID, data)
                                    }

                                    R.id.app_proxy -> {
                                        showProxyDialog(data)
                                    }

                                    R.id.app_rename -> {
                                        showRenameDialog(data)
                                    }
                                }
                                return@setOnMenuItemClickListener true
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in menu item click: ${e.message}")
                                return@setOnMenuItemClickListener false
                            }
                        }
                        it.show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in long click: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setOnLongClick: ${e.message}")
        }
    }
    
    private fun showRenameDialog(data: AppInfo) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setText(data.name)
            setSelection(text.length)
        }
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(android.widget.TextView(ctx).apply { text = "Name for this clone (User $userID)" })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Rename")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                val key = "cloneName_${userID}_${data.packageName}"
                val ed = top.niunaijun.blackboxa.app.AppManager.mRemarkSharedPreferences.edit()
                if (name.isEmpty()) ed.remove(key) else ed.putString(key, name)
                // BlackBox hosts multiple processes.  An asynchronous apply() can leave the
                // clone label visible only in this process' memory and lose it on restart.
                // Persist the label before refreshing the grid so ShieldProxy always sees the
                // same stable clone identity through the bridge provider.
                if (ed.commit()) {
                    viewModel.getInstalledApps(userID)   // refresh grid
                } else {
                    Log.e(TAG, "Failed to persist clone name for user $userID")
                    toast("Could not save clone name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProxyDialog(data: AppInfo) {
        val ctx = requireContext()
        val current = top.niunaijun.blackbox.core.GuestProxy.describe(userID)
        val input = android.widget.EditText(ctx).apply {
            hint = "server:port:username:password"
            setText(current)
        }
        val typeInput = android.widget.EditText(ctx).apply {
            hint = "type: http or socks5"
            setText("http")
        }
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(android.widget.TextView(ctx).apply {
                text = "Proxy for ${data.name}  (User $userID)\nPaste  server:port:user:pass"
            })
            addView(input)
            addView(typeInput)
        }
        fun msg(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Set proxy")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                var s = input.text.toString().trim()
                var type = typeInput.text.toString().trim().lowercase()
                if (type.isEmpty()) type = "http"
                if (s.startsWith("socks5://")) { type = "socks5"; s = s.substring(9) }
                else if (s.startsWith("http://")) { type = "http"; s = s.substring(7) }
                val parts = s.split(":", limit = 4)
                if (parts.size < 2) { msg("Need at least server:port"); return@setPositiveButton }
                val server = parts[0]
                val port = parts[1].toIntOrNull() ?: 0
                val user = if (parts.size > 2) parts[2] else ""
                val pass = if (parts.size > 3) parts[3] else ""
                if (server.isEmpty() || port <= 0) { msg("Bad server/port"); return@setPositiveButton }
                top.niunaijun.blackbox.core.GuestProxy.save(userID, type, server, port, user, pass)
                try { stopApk(data) } catch (_: Exception) {}
                msg("Proxy saved. Reopen ${data.name} to apply.")
            }
            .setNeutralButton("Remove") { _, _ ->
                top.niunaijun.blackbox.core.GuestProxy.clear(userID)
                try { stopApk(data) } catch (_: Exception) {}
                msg("Proxy removed (direct).")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initData() {
        try {
            viewBinding.stateView.showLoading()
            viewModel.getInstalledApps(userID)
            viewModel.appsLiveData.observe(viewLifecycleOwner) {
                try {
                    if (it != null) {
                        mAdapter.setItems(it)
                        if (it.isEmpty()) {
                            viewBinding.stateView.showEmpty()
                        } else {
                            viewBinding.stateView.showContent()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing apps data: ${e.message}")
                }
            }

            viewModel.resultLiveData.observe(viewLifecycleOwner) {
                try {
                    if (!TextUtils.isEmpty(it)) {
                        hideLoading()
                        requireContext().toast(it)
                        viewModel.getInstalledApps(userID)
                        scanUser()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing result data: ${e.message}")
                }
            }

            viewModel.launchLiveData.observe(viewLifecycleOwner) {
                try {
                    it?.run {
                        hideLoading()
                        if (!it) {
                            toast(R.string.start_fail)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing launch data: ${e.message}")
                }
            }

            viewModel.updateSortLiveData.observe(viewLifecycleOwner) {
                try {
                    if (this::mAdapter.isInitialized) {
                        viewModel.updateApkOrder(userID, mAdapter.getItems())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error observing sort data: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initData: ${e.message}")
        }
    }

    override fun onStop() {
        try {
            super.onStop()
            viewModel.resultLiveData.value = null
            viewModel.launchLiveData.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop: ${e.message}")
        }
    }

    private fun unInstallApk(info: AppInfo) {
        try {
            MaterialDialog(requireContext()).show {
                title(R.string.uninstall_app)
                message(text = getString(R.string.uninstall_app_hint, info.name))
                positiveButton(R.string.done) {
                    try {
                        showLoading()
                        viewModel.unInstall(info.packageName, userID)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uninstalling app: ${e.message}")
                        hideLoading()
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing uninstall dialog: ${e.message}")
        }
    }

    
    private fun stopApk(info: AppInfo) {
        try {
            MaterialDialog(requireContext()).show {
                title(R.string.app_stop)
                message(text = getString(R.string.app_stop_hint,info.name))
                positiveButton(R.string.done) {
                    try {
                        BlackBoxCore.get().stopPackage(info.packageName, userID)
                        toast(getString(R.string.is_stop,info.name))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping app: ${e.message}")
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing stop dialog: ${e.message}")
        }
    }

    
    private fun clearApk(info: AppInfo) {
        try {
            MaterialDialog(requireContext()).show {
                title(R.string.app_clear)
                message(text = getString(R.string.app_clear_hint,info.name))
                positiveButton(R.string.done) {
                    try {
                        showLoading()
                        viewModel.clearApkData(info.packageName, userID)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing app data: ${e.message}")
                        hideLoading()
                    }
                }
                negativeButton(R.string.cancel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing clear dialog: ${e.message}")
        }
    }

    fun installApk(source: String) {
        try {
            showLoading()
            viewModel.install(source, userID)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK: ${e.message}")
            hideLoading()
        }
    }

    private fun scanUser() {
        try {
            (requireActivity() as? MainActivity)?.scanUser()
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning user: ${e.message}")
        }
    }

    private fun showLoading() {
        try {
            if(requireActivity() is LoadingActivity){
                (requireActivity() as LoadingActivity).showLoading()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing loading: ${e.message}")
        }
    }

    private fun hideLoading() {
        try {
            if(requireActivity() is LoadingActivity){
                (requireActivity() as LoadingActivity).hideLoading()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding loading: ${e.message}")
        }
    }
}
