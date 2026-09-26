package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.FragmentBrowseBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!

    enum class SortType(val titleResId: Int) {
        NAME(R.string.sort_by_name),
        SIZE(R.string.sort_by_size),
        PACKAGE(R.string.sort_by_package);

        companion object {
            fun fromKey(key: String?): SortType = when (key) {
                "size" -> SIZE
                "package" -> PACKAGE
                else -> NAME
            }

            fun key(type: SortType): String = when (type) {
                NAME -> "name"
                SIZE -> "size"
                PACKAGE -> "package"
            }
        }
    }

    enum class FilterOption(val titleResId: Int) {
        ALL(R.string.filter_option_all),
        USER(R.string.filter_option_user),
        SYSTEM(R.string.filter_option_system)
    }

    private var currentSortType: SortType = SortType.NAME
    private var isSortAscending: Boolean = true
    private var currentFilter: FilterOption = FilterOption.ALL

    private var allInstalledApps: List<AppItem> = emptyList()
    private var displayedApps: List<AppItem> = emptyList()
    private val iconLoader = AppIconLoader()
    private var showSystemApps: Boolean = false

    private var isSelectionMode: Boolean = false
    private var isScrollToTopButtonShown: Boolean = false
    private val selectedPackageNames = mutableSetOf<String>()
    private val originalSelectedBeforeDrag = mutableSetOf<String>()
    private var pendingScrubPosition: Int = RecyclerView.NO_POSITION
    private var scrubPosted: Boolean = false
    private val applyScrubPositionRunnable = Runnable {
        scrubPosted = false
        val position = pendingScrubPosition
        pendingScrubPosition = RecyclerView.NO_POSITION
        if (position != RecyclerView.NO_POSITION) {
            binding.recyclerApps.scrollToPosition(position)
        }
    }
    private var backPressedCallback: OnBackPressedCallback? = null
    private var dragSelectTouchListener: DragSelectTouchListener? = null

    private lateinit var preferences: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_SHOW_SYSTEM_APPS) {
            showSystemApps = preferences.getBoolean(PREF_SHOW_SYSTEM_APPS, false)
            currentFilter = if (showSystemApps) FilterOption.ALL else FilterOption.USER
            applyDisplayFilter()
        }
    }

    private lateinit var fileSaveHelper: FileSaveHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileSaveHelper = FileSaveHelper.from(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        showSystemApps = AppPrefs.showSystemApps(requireContext())
        currentFilter = if (showSystemApps) FilterOption.ALL else FilterOption.USER
        currentSortType = SortType.fromKey(preferences.getString(AppPrefs.KEY_APP_SORT_TYPE, null))
        isSortAscending = preferences.getBoolean(
            AppPrefs.KEY_APP_SORT_ASCENDING,
            AppPrefs.DEFAULT_APP_SORT_ASCENDING
        )
        preferences.registerOnSharedPreferenceChangeListener(prefsListener)

        binding.btnSort.setOnClickListener { showSortMenu(it) }
        binding.btnFilter.setOnClickListener { showFilterMenu(it) }

        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitSelectionMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)

        binding.btnSelectAllContainer.setOnClickListener {
            toggleSelectAll()
        }

        val listener = DragSelectTouchListener(
            onSelectRange = { startPos, endPos ->
                val minPos = minOf(startPos, endPos).coerceAtLeast(0)
                val maxPos = maxOf(startPos, endPos).coerceAtMost(displayedApps.size - 1)
                selectedPackageNames.clear()
                selectedPackageNames.addAll(originalSelectedBeforeDrag)
                for (i in minPos..maxPos) {
                    displayedApps.getOrNull(i)?.let { selectedPackageNames.add(it.packageName) }
                }
                updateSelectionUI(duringDrag = true)
            },
            onDragEnded = {
                updateSelectionUI(duringDrag = false)
            }
        )
        listener.attachToRecyclerView(binding.recyclerApps)
        dragSelectTouchListener = listener

        binding.swipeRefresh.setOnRefreshListener {
            loadInstalledApps(isSwipeRefresh = true)
        }
        binding.btnScrollToTop.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            scrollToTop()
        }

        binding.fastScroller.contentDescription = getString(R.string.cd_alphabet_index)
        binding.fastScroller.onScrubTo = { scrubToPosition(it) }
        binding.fastScroller.onScrubFinished = {
            fadeOutLetterPreview()
        }
        binding.recyclerApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateScrollToTopButton(rv)
                if (isSelectionMode) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                binding.fastScroller.updateRange(
                    lm.findFirstVisibleItemPosition(),
                    lm.findLastVisibleItemPosition(),
                    lm.itemCount
                )
            }
        })

        loadInstalledApps(isSwipeRefresh = false)
    }

    fun isAtTopOfPage(): Boolean {
        return _binding?.recyclerApps?.canScrollVertically(-1) == false
    }

    fun scrollToTop() {
        val lm = _binding?.recyclerApps?.layoutManager as? LinearLayoutManager
        val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
        if (firstVisible > 15) {
            _binding?.recyclerApps?.scrollToPosition(15)
        }
        _binding?.recyclerApps?.smoothScrollToPosition(0)
    }

    private fun updateScrollToTopButton(recyclerView: RecyclerView) {
        val shouldShow = recyclerView.isVisible &&
            recyclerView.adapter != null &&
            recyclerView.adapter!!.itemCount >= SCROLL_TO_TOP_MIN_ITEMS &&
            recyclerView.canScrollVertically(-1)
        val button = binding.btnScrollToTop
        if (shouldShow) {
            if (isScrollToTopButtonShown) return
            isScrollToTopButtonShown = true
            button.animate().cancel()
            button.alpha = 0f
            button.translationY = 16f * resources.displayMetrics.density
            button.isVisible = true
            button.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        } else if (isScrollToTopButtonShown) {
            isScrollToTopButtonShown = false
            button.animate().cancel()
            button.animate()
                .alpha(0f)
                .translationY(16f * resources.displayMetrics.density)
                .setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    if (!isScrollToTopButtonShown) {
                        button.isVisible = false
                    }
                }
                .start()
        }
    }

    private fun loadInstalledApps(isSwipeRefresh: Boolean = false) {
        val tStart = android.os.SystemClock.elapsedRealtime()

        viewLifecycleOwner.lifecycleScope.launch {
            // Show the previous result straight away. Enumerating packages means
            // parsing every installed APK manifest and resolving every app label,
            // which on a device with 400+ apps is the entire cost of this screen.
            // The refresh below replaces it once real data arrives, so the list is
            // never empty and never waits on PackageManager.
            if (!isSwipeRefresh) {
                val cached = withContext(Dispatchers.IO) { AppListCache.load(requireContext()) }
                if (!cached.isNullOrEmpty()) {
                    allInstalledApps = cached
                    binding.layoutLoading.isVisible = false
                    binding.layoutSortFilterBar.isVisible = true
                    binding.recyclerApps.isVisible = true
                    applyDisplayFilter()
                    AppListCache.recordTiming(
                        requireContext(),
                        "cache-render=${android.os.SystemClock.elapsedRealtime() - tStart}ms " +
                            "count=${cached.size}"
                    )
                } else {
                    showLoadingUi()
                }
            }

            val tLoadStart = android.os.SystemClock.elapsedRealtime()
            // Labels already resolved on a previous run are reused. This is the
            // single biggest win: resolving a label loads that app's resources and
            // dominated the load time, but the answer rarely changes.
            val knownNames = allInstalledApps.associate { it.packageName to it.name }
            val apps = withContext(Dispatchers.IO) {
                // Progress is throttled inside the helper, which knows how many
                // workers are running and can use an atomic counter.
                AppInfoUtils.getInstalledApps(
                    requireContext(),
                    knownNames,
                    PROGRESS_STEP
                ) { loaded, total ->
                    if (!isSwipeRefresh) {
                        launch(Dispatchers.Main) {
                            if (_binding != null) {
                                binding.progressLoading.max = total
                                binding.progressLoading.progress = loaded
                                binding.textLoadingProgress.text =
                                    getString(R.string.loading_apps_progress, loaded, total)
                            }
                        }
                    }
                }
            }

            allInstalledApps = apps
            withContext(Dispatchers.IO) { AppListCache.save(requireContext(), apps) }
            (activity as? MainActivity)?.updateInstalledApps(apps)
            binding.layoutLoading.isVisible = false
            if (isSwipeRefresh && _binding != null) {
                binding.swipeRefresh.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.CONTEXT_CLICK
                    }
                )
            }
            binding.swipeRefresh.isRefreshing = false
            applyDisplayFilter()
            AppListCache.recordTiming(
                requireContext(),
                "refresh=${android.os.SystemClock.elapsedRealtime() - tLoadStart}ms " +
                    "count=${apps.size}"
            )
        }
    }

    private fun showLoadingUi() {
        binding.layoutLoading.isVisible = true
        binding.layoutSortFilterBar.isVisible = false
        binding.recyclerApps.isVisible = false
        binding.layoutEmpty.isVisible = false
        updateScrollToTopButton(binding.recyclerApps)
        binding.fastScroller.hideNow()
        binding.textLetterPreview.animate().cancel()
        binding.textLetterPreview.alpha = 1f
        binding.textLetterPreview.isVisible = false
        binding.progressLoading.progress = 0
        binding.textLoadingProgress.text = getString(R.string.loading_apps)
    }

    private fun enterSelectionMode(initialApp: AppItem) {
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedPackageNames.clear()
            selectedPackageNames.add(initialApp.packageName)
            backPressedCallback?.isEnabled = true
            binding.swipeRefresh.isEnabled = false
            binding.fastScroller.hideNow()
            (activity as? MainActivity)?.showSelectionBar(
                count = selectedPackageNames.size,
                onBack = { exitSelectionMode() },
                onZip = { saveSelectedApksAsZip() }
            )
            updateSelectionUI()
        }
    }

    private fun exitSelectionMode() {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedPackageNames.clear()
            backPressedCallback?.isEnabled = false
            binding.swipeRefresh.isEnabled = true
            (activity as? MainActivity)?.hideSelectionBar()
            updateSelectionUI()
            binding.recyclerApps.post {
                if (isSelectionMode) return@post
                val lm = binding.recyclerApps.layoutManager as? LinearLayoutManager
                    ?: return@post
                binding.fastScroller.updateRange(
                    lm.findFirstVisibleItemPosition(),
                    lm.findLastVisibleItemPosition(),
                    lm.itemCount
                )
            }
        }
    }

    private fun toggleAppSelection(app: AppItem) {
        if (selectedPackageNames.contains(app.packageName)) {
            selectedPackageNames.remove(app.packageName)
        } else {
            selectedPackageNames.add(app.packageName)
        }
        if (selectedPackageNames.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionUI()
        }
    }

    private fun toggleSelectAll() {
        if (selectedPackageNames.size == displayedApps.size && displayedApps.isNotEmpty()) {
            selectedPackageNames.clear()
            exitSelectionMode()
        } else {
            selectedPackageNames.clear()
            displayedApps.forEach { selectedPackageNames.add(it.packageName) }
            updateSelectionUI()
        }
    }

    private fun updateSelectionUI(duringDrag: Boolean = false) {
        binding.swipeRefresh.isEnabled = !isSelectionMode
        if (isSelectionMode) {
            binding.layoutSortFilterBar.isVisible = false
            if (binding.layoutSelectAllHeader.visibility != View.VISIBLE) {
                binding.layoutSelectAllHeader.animate().cancel()
                binding.layoutSelectAllHeader.alpha = 0f
                binding.layoutSelectAllHeader.translationY = -8f * resources.displayMetrics.density
                binding.layoutSelectAllHeader.visibility = View.VISIBLE
                binding.layoutSelectAllHeader.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
            val count = selectedPackageNames.size
            val allSelected = count == displayedApps.size && displayedApps.isNotEmpty()
            binding.checkboxSelectAll.isChecked = allSelected
            binding.textSelectAll.text = if (allSelected) {
                getString(R.string.action_deselect_all)
            } else {
                getString(R.string.action_select_all)
            }
            (activity as? MainActivity)?.updateSelectionCount(count)
        } else {
            binding.layoutSortFilterBar.isVisible = true
            if (binding.layoutSelectAllHeader.visibility == View.VISIBLE) {
                binding.layoutSelectAllHeader.animate().cancel()
                binding.layoutSelectAllHeader.animate()
                    .alpha(0f)
                    .translationY(-8f * resources.displayMetrics.density)
                    .setDuration(150)
                    .withEndAction {
                        binding.layoutSelectAllHeader.visibility = View.GONE
                        binding.layoutSelectAllHeader.translationY = 0f
                    }
                    .start()
            } else {
                binding.layoutSelectAllHeader.visibility = View.GONE
            }
        }
        val currentAdapter = binding.recyclerApps.adapter as? AppAdapter
        if (duringDrag && currentAdapter != null && currentAdapter.currentList === displayedApps) {
            currentAdapter.updateSelection(selectedPackageNames, isSelectionMode)
        } else {
            applyDisplayFilter(preserveSelection = true)
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        val menu = popup.menu
        SortType.values().forEachIndexed { index, sortType ->
            val item = menu.add(0, index, index, sortType.titleResId)
            item.isCheckable = true
            item.isChecked = (sortType == currentSortType)
        }
        menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { menuItem ->
            val selected = SortType.values().getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener false
            if (selected == currentSortType) {
                isSortAscending = !isSortAscending
            } else {
                currentSortType = selected
                isSortAscending = (selected != SortType.SIZE)
            }
            applyDisplayFilter(preserveSelection = isSelectionMode)
            persistSort()
            true
        }
        popup.show()
    }

    private fun persistSort() {
        preferences.edit()
            .putString(AppPrefs.KEY_APP_SORT_TYPE, SortType.key(currentSortType))
            .putBoolean(AppPrefs.KEY_APP_SORT_ASCENDING, isSortAscending)
            .apply()
    }

    private fun showFilterMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        val menu = popup.menu
        FilterOption.values().forEachIndexed { index, option ->
            val item = menu.add(0, index, index, option.titleResId)
            item.isCheckable = true
            item.isChecked = (option == currentFilter)
        }
        menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { menuItem ->
            val selected = FilterOption.values().getOrNull(menuItem.itemId) ?: return@setOnMenuItemClickListener false
            if (selected != currentFilter) {
                currentFilter = selected
                applyDisplayFilter(preserveSelection = isSelectionMode)
            }
            true
        }
        popup.show()
    }

    private fun saveSelectedApksAsZip() {
        val selected = displayedApps.filter { selectedPackageNames.contains(it.packageName) }
        if (selected.isNotEmpty()) {
            fileSaveHelper.saveZipOfApks(selected)
            exitSelectionMode()
        }
    }

    private fun applyDisplayFilter(preserveSelection: Boolean = false) {
        if (!preserveSelection && isSelectionMode) {
            selectedPackageNames.clear()
            isSelectionMode = false
            backPressedCallback?.isEnabled = false
            (activity as? MainActivity)?.hideSelectionBar()
            binding.layoutSelectAllHeader.isVisible = false
            binding.swipeRefresh.isEnabled = true
        }
        binding.layoutSortFilterBar.isVisible = !isSelectionMode

        val filtered = when (currentFilter) {
            FilterOption.ALL -> allInstalledApps
            FilterOption.USER -> allInstalledApps.filter { !it.isSystemApp }
            FilterOption.SYSTEM -> allInstalledApps.filter { it.isSystemApp }
        }

        displayedApps = when (currentSortType) {
            SortType.NAME -> if (isSortAscending) {
                filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            } else {
                filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            SortType.SIZE -> if (isSortAscending) {
                filtered.sortedWith(compareBy<AppItem> { it.sizeBytes }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            } else {
                filtered.sortedWith(compareByDescending<AppItem> { it.sizeBytes }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            SortType.PACKAGE -> if (isSortAscending) {
                filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName })
            } else {
                filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.packageName })
            }
        }

        binding.btnSort.text = getString(currentSortType.titleResId)
        binding.btnSort.setIconResource(
            if (isSortAscending) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward
        )
        binding.btnFilter.text = getString(currentFilter.titleResId)

        binding.recyclerApps.isVisible = displayedApps.isNotEmpty()
        binding.layoutEmpty.isVisible = displayedApps.isEmpty()
        updateScrollToTopButton(binding.recyclerApps)
        if (displayedApps.isEmpty()) {
            binding.fastScroller.hideNow()
            binding.textLetterPreview.animate().cancel()
            binding.textLetterPreview.alpha = 1f
            binding.textLetterPreview.isVisible = false
        } else {
            binding.recyclerApps.post {
                if (isSelectionMode) return@post
                val lm = binding.recyclerApps.layoutManager as? LinearLayoutManager
                    ?: return@post
                binding.fastScroller.updateRange(
                    lm.findFirstVisibleItemPosition(),
                    lm.findLastVisibleItemPosition(),
                    lm.itemCount
                )
                updateScrollToTopButton(binding.recyclerApps)
            }
        }

        val tFilterStart = android.os.SystemClock.elapsedRealtime()
        val currentAdapter = binding.recyclerApps.adapter as? AppAdapter
        if (currentAdapter != null) {
            // Reuse the adapter and diff, instead of rebuilding it and rebinding
            // every one of the several hundred rows on each refresh.
            currentAdapter.submit(displayedApps)
            currentAdapter.updateSelection(selectedPackageNames, isSelectionMode)
        } else {
            binding.recyclerApps.adapter = AppAdapter(
                items = displayedApps,
                selectedPackages = selectedPackageNames,
                isSelectionMode = isSelectionMode,
                onItemClick = { selectedApp ->
                    if (isSelectionMode) {
                        toggleAppSelection(selectedApp)
                    } else {
                        openAppDetails(selectedApp.packageName)
                    }
                },
                onItemLongClick = { selectedApp, position ->
                    if (!isSelectionMode) {
                        enterSelectionMode(selectedApp)
                    } else {
                        if (!selectedPackageNames.contains(selectedApp.packageName)) {
                            selectedPackageNames.add(selectedApp.packageName)
                            updateSelectionUI()
                        }
                    }
                    originalSelectedBeforeDrag.clear()
                    originalSelectedBeforeDrag.addAll(selectedPackageNames)
                    dragSelectTouchListener?.startDragSelection(position)
                },
                onAvatarClick = { selectedApp ->
                    enterSelectionMode(selectedApp)
                },
                onSaveApkToClick = { app ->
                    saveApk(app)
                },
                iconLoader = iconLoader
            )
        }
        android.util.Log.d(
            "AppLoad",
            "bind: filter+sort+adapter=${android.os.SystemClock.elapsedRealtime() - tFilterStart}ms " +
                "shown=${displayedApps.size}"
        )
    }

    fun saveApk(app: AppItem) {
        fileSaveHelper.saveApk(app)
    }

    private fun openAppDetails(packageName: String) {
        AppDetailActivity.start(requireContext(), packageName = packageName)
    }

    private fun scrubToPosition(position: Int) {
        val app = displayedApps.getOrNull(position) ?: return
        val letter = app.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        binding.textLetterPreview.text = letter
        binding.textLetterPreview.animate().cancel()
        binding.textLetterPreview.alpha = 1f
        binding.textLetterPreview.isVisible = true
        pendingScrubPosition = position
        if (!scrubPosted) {
            scrubPosted = true
            binding.recyclerApps.postOnAnimation(applyScrubPositionRunnable)
        }
    }

    private fun fadeOutLetterPreview() {
        binding.textLetterPreview.animate().cancel()
        binding.textLetterPreview.animate()
            .alpha(0f)
            .setStartDelay(150)
            .setDuration(300)
            .withEndAction {
                binding.textLetterPreview.isVisible = false
                binding.textLetterPreview.alpha = 1f
            }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isSelectionMode) {
            (activity as? MainActivity)?.hideSelectionBar()
        }
        dragSelectTouchListener?.stopDrag()
        dragSelectTouchListener = null
        backPressedCallback?.remove()
        backPressedCallback = null
        preferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        binding.recyclerApps.removeCallbacks(applyScrubPositionRunnable)
        iconLoader.clear()
        pendingScrubPosition = RecyclerView.NO_POSITION
        scrubPosted = false
        _binding = null
    }

    companion object {
        private const val PREF_SHOW_SYSTEM_APPS = AppPrefs.KEY_SHOW_SYSTEM_APPS
        private const val PROGRESS_STEP = 8
        private const val SCROLL_TO_TOP_MIN_ITEMS = 100
    }

    class AppAdapter(
        items: List<AppItem>,
        private var selectedPackages: Set<String> = emptySet(),
        private var isSelectionMode: Boolean = false,
        private val onItemClick: (AppItem) -> Unit,
        private val onItemLongClick: (AppItem, Int) -> Unit = { _, _ -> },
        private val onAvatarClick: (AppItem) -> Unit = {},
        private val onSaveApkToClick: (AppItem) -> Unit,
        private val iconLoader: AppIconLoader = AppIconLoader()
    ) : ListAdapter<AppItem, AppAdapter.ViewHolder>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
                override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                    oldItem.packageName == newItem.packageName

                override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
                    oldItem == newItem
            }
        }

        init {
            submitList(items)
        }

        private val sizeTextCache = HashMap<Long, String>()
        private var adapterContext: Context? = null
        private val systemAppBadgeText: String
            get() = adapterContext?.getString(R.string.badge_system_app).orEmpty()

        /** Swaps in a new list, diffed on a background thread. */
        fun submit(newItems: List<AppItem>) {
            if (newItems === currentList) return
            submitList(newItems)
        }

        fun updateSelection(selected: Set<String>, selectionMode: Boolean) {
            if (selectedPackages == selected && isSelectionMode == selectionMode) return
            val previous = selectedPackages
            selectedPackages = HashSet(selected)
            isSelectionMode = selectionMode
            // During a drag select this runs on every finger movement. Rebinding the
            // whole list each time meant re-binding all several hundred rows per
            // touch event, so only the rows whose selected state actually flipped
            // are refreshed.
            if (currentList.isEmpty()) {
                notifyDataSetChanged()
                return
            }
            val flipped = currentList.indices.filter { index ->
                val pkg = currentList[index].packageName
                (pkg in previous) != (pkg in selectedPackages) ||
                    (pkg in selectedPackages) && isSelectionMode
            }
            if (flipped.isEmpty()) notifyDataSetChanged() else flipped.forEach { notifyItemChanged(it) }
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconContainer: View = view.findViewById(R.id.container_app_icon)
            val iconView: ImageView = view.findViewById(R.id.app_icon)
            val checkBadge: ImageView = view.findViewById(R.id.icon_check_badge)
            val nameView: TextView = view.findViewById(R.id.app_name)
            val packageView: TextView = view.findViewById(R.id.app_package)
            val infoView: TextView = view.findViewById(R.id.app_info)
            val menuButton: ImageButton = view.findViewById(R.id.btn_item_menu)
            var currentPackage: String? = null
            var wasSelected: Boolean? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            if (adapterContext == null) adapterContext = parent.context
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_browse_app, parent, false)
            return ViewHolder(view)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.iconView.animate().cancel()
            holder.checkBadge.animate().cancel()
            holder.iconView.rotationY = 0f
            holder.checkBadge.rotationY = 0f
            holder.wasSelected = null
            holder.currentPackage = null
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val isSelected = selectedPackages.contains(item.packageName)

            if (item.icon != null) {
                holder.iconView.setImageDrawable(item.icon)
            } else {
                iconLoader.load(item.packageName, holder.iconView)
            }

            holder.nameView.text = item.name
            holder.packageView.text = item.packageName
            // Memoised so scrolling back to a row does not re-run the three
            // String.format calls inside formatFileSize for every bind.
            val sizeBytes = item.sizeBytes
            val formattedSize = sizeTextCache.getOrPut(sizeBytes) {
                AppInfoUtils.formatFileSize(sizeBytes)
            }
            holder.infoView.text = if (item.isSystemApp) {
                "$systemAppBadgeText • ${item.versionName} • $formattedSize"
            } else {
                "${item.versionName} • $formattedSize"
            }

            val isPackageChanged = holder.currentPackage != item.packageName
            holder.currentPackage = item.packageName

            if (isSelected) {
                holder.itemView.setBackgroundResource(R.drawable.bg_item_browse_selected)
            } else {
                holder.itemView.setBackgroundResource(R.drawable.bg_item_browse_unselected)
            }

            val shouldAnimateFlip = !isPackageChanged && holder.wasSelected != null && holder.wasSelected != isSelected
            holder.wasSelected = isSelected

            if (shouldAnimateFlip) {
                animateIconFlip(holder, isSelected)
            } else {
                resetIconState(holder, isSelected)
            }

            holder.menuButton.isVisible = !isSelectionMode
            holder.itemView.isActivated = isSelected

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }

            holder.iconContainer.setOnClickListener {
                if (!isSelectionMode) {
                    onAvatarClick(item)
                } else {
                    onItemClick(item)
                }
            }

            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemLongClick(item, pos)
                }
                true
            }

            holder.iconContainer.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemLongClick(item, pos)
                }
                true
            }

            holder.menuButton.setOnClickListener { v ->
                val popup = PopupMenu(v.context, v)
                popup.menu.add(0, 1, 0, R.string.title_app_detail)
                popup.menu.add(0, 2, 1, R.string.action_open_app)
                popup.menu.add(0, 3, 2, R.string.action_app_info)
                popup.menu.add(0, 4, 3, R.string.action_save_apk_to)
                popup.menu.add(0, 5, 4, R.string.action_import_as_project)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        1 -> {
                            onItemClick(item)
                            true
                        }
                        2 -> {
                            val pm = v.context.packageManager
                            val intent = pm.getLaunchIntentForPackage(item.packageName)
                            if (intent != null) {
                                v.context.startActivity(intent)
                            } else {
                                Snackbar.make(
                                    v,
                                    R.string.toast_app_not_launchable,
                                    Snackbar.LENGTH_SHORT
                                ).show()
                            }
                            true
                        }
                        3 -> {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", item.packageName, null)
                            }
                            v.context.startActivity(intent)
                            true
                        }
                        4 -> {
                            onSaveApkToClick(item)
                            true
                        }
                        5 -> {
                            v.context.startActivity(
                                ProjectWizardActivity.createIntent(
                                    context = v.context,
                                    apkPath = item.sourceDir,
                                    appName = item.name
                                )
                            )
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }

        private fun resetIconState(holder: ViewHolder, isSelected: Boolean) {
            holder.iconView.animate().cancel()
            holder.checkBadge.animate().cancel()

            holder.iconView.rotationY = 0f
            holder.checkBadge.rotationY = 0f

            if (isSelected) {
                holder.iconView.visibility = View.INVISIBLE
                holder.checkBadge.visibility = View.VISIBLE
            } else {
                holder.iconView.visibility = View.VISIBLE
                holder.checkBadge.visibility = View.GONE
            }
        }

        private fun animateIconFlip(holder: ViewHolder, isSelected: Boolean) {
            holder.iconView.animate().cancel()
            holder.checkBadge.animate().cancel()

            val density = holder.itemView.resources.displayMetrics.density
            val distance = 8000f * density
            holder.iconView.cameraDistance = distance
            holder.checkBadge.cameraDistance = distance

            if (isSelected) {
                holder.iconView.visibility = View.VISIBLE
                holder.checkBadge.visibility = View.GONE
                holder.iconView.rotationY = 0f

                holder.iconView.animate()
                    .rotationY(90f)
                    .setDuration(120)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        holder.iconView.visibility = View.INVISIBLE
                        holder.checkBadge.visibility = View.VISIBLE
                        holder.checkBadge.rotationY = -90f
                        holder.checkBadge.animate()
                            .rotationY(0f)
                            .setDuration(120)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            } else {
                holder.checkBadge.visibility = View.VISIBLE
                holder.iconView.visibility = View.INVISIBLE
                holder.checkBadge.rotationY = 0f

                holder.checkBadge.animate()
                    .rotationY(90f)
                    .setDuration(120)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        holder.checkBadge.visibility = View.GONE
                        holder.iconView.visibility = View.VISIBLE
                        holder.iconView.rotationY = -90f
                        holder.iconView.animate()
                            .rotationY(0f)
                            .setDuration(120)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
        }
    }
}

class DragSelectTouchListener(
    private val onSelectRange: (startPosition: Int, endPosition: Int) -> Unit,
    private val onDragEnded: (() -> Unit)? = null
) : RecyclerView.OnItemTouchListener {

    private var recyclerView: RecyclerView? = null
    var isDragging: Boolean = false
        private set

    private var startPosition: Int = RecyclerView.NO_POSITION
    private var lastPosition: Int = RecyclerView.NO_POSITION
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var scrollDistance: Int = 0
    private var isAutoScrolling: Boolean = false

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val rv = recyclerView ?: return
            if (!isDragging) {
                isAutoScrolling = false
                return
            }
            if (scrollDistance != 0) {
                rv.scrollBy(0, scrollDistance)
                checkCurrentPosition(rv, lastX, lastY)
                rv.postOnAnimation(this)
            } else {
                isAutoScrolling = false
            }
        }
    }

    fun attachToRecyclerView(rv: RecyclerView) {
        recyclerView = rv
        rv.addOnItemTouchListener(this)
    }

    fun startDragSelection(position: Int) {
        val rv = recyclerView ?: return
        if (position == RecyclerView.NO_POSITION) return
        isDragging = true
        startPosition = position
        lastPosition = position

        val child = rv.findViewHolderForAdapterPosition(position)?.itemView
        if (child != null) {
            lastX = child.x + child.width / 2f
            lastY = child.y + child.height / 2f
        } else {
            lastX = rv.width / 2f
            lastY = rv.height / 2f
        }

        rv.requestDisallowInterceptTouchEvent(false)
        rv.parent?.requestDisallowInterceptTouchEvent(true)
    }

    fun stopDrag() {
        if (!isDragging) return
        isDragging = false
        isAutoScrolling = false
        val rv = recyclerView
        rv?.removeCallbacks(autoScrollRunnable)
        rv?.parent?.requestDisallowInterceptTouchEvent(false)
        startPosition = RecyclerView.NO_POSITION
        lastPosition = RecyclerView.NO_POSITION
        scrollDistance = 0
        onDragEnded?.invoke()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!isDragging) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return false
            MotionEvent.ACTION_MOVE -> {
                handleMove(rv, e)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopDrag()
                return false
            }
        }
        return isDragging
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!isDragging) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                handleMove(rv, e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopDrag()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Ignored to maintain drag selection control
    }

    private fun handleMove(rv: RecyclerView, e: MotionEvent) {
        lastX = e.x
        lastY = e.y
        checkCurrentPosition(rv, e.x, e.y)
        checkAutoScroll(rv, e.y)
    }

    private fun checkCurrentPosition(rv: RecyclerView, x: Float, y: Float) {
        val child = rv.findChildViewUnder(x, y)
        val pos = if (child != null) {
            rv.getChildAdapterPosition(child)
        } else {
            val lm = rv.layoutManager as? LinearLayoutManager
            if (y <= 0f) {
                lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
            } else if (y >= rv.height) {
                lm?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
            } else {
                findNearestPosition(rv, y)
            }
        }

        if (pos != RecyclerView.NO_POSITION && pos != lastPosition) {
            lastPosition = pos
            onSelectRange(startPosition, pos)
        }
    }

    private fun findNearestPosition(rv: RecyclerView, y: Float): Int {
        val count = rv.childCount
        for (i in 0 until count) {
            val child = rv.getChildAt(i)
            if (y >= child.top && y <= child.bottom) {
                return rv.getChildAdapterPosition(child)
            }
        }
        return RecyclerView.NO_POSITION
    }

    private fun checkAutoScroll(rv: RecyclerView, y: Float) {
        val density = rv.context.resources.displayMetrics.density
        val hotspot = (64 * density).toInt()
        val maxSpeed = (18 * density).toInt()

        val canScrollUp = rv.canScrollVertically(-1)
        val canScrollDown = rv.canScrollVertically(1)

        scrollDistance = when {
            y < hotspot && canScrollUp -> {
                val factor = ((hotspot - y) / hotspot).coerceIn(0f, 2f)
                (-maxSpeed * factor).toInt().coerceAtMost(-1)
            }
            y > rv.height - hotspot && canScrollDown -> {
                val factor = ((y - (rv.height - hotspot)) / hotspot).coerceIn(0f, 2f)
                (maxSpeed * factor).toInt().coerceAtLeast(1)
            }
            else -> 0
        }

        if (scrollDistance != 0) {
            if (!isAutoScrolling) {
                isAutoScrolling = true
                rv.postOnAnimation(autoScrollRunnable)
            }
        } else {
            if (isAutoScrolling) {
                isAutoScrolling = false
                rv.removeCallbacks(autoScrollRunnable)
            }
        }
    }
}

