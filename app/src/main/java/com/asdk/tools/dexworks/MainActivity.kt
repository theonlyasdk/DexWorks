package com.asdk.tools.dexworks

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var allInstalledApps: List<AppItem> = emptyList()
    private var searchNameKeys: List<String> = emptyList()
    private var searchPackageKeys: List<String> = emptyList()
    private var searchRunnable: Runnable? = null
    private val searchIconLoader = AppIconLoader()

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 180L
    }

    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdgeWithPadding(binding.topBarContainer)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBarContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right
            )
            insets
        }

        setSupportActionBar(binding.toolbar)

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted && shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    Snackbar.make(binding.root, R.string.perm_notif_rationale, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_retry) {
                            requestNotificationPermission()
                        }
                        .show()
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission()
        }

        setupSearch()
        setupSwipeNavigation()

        lifecycleScope.launch(Dispatchers.IO) {
            ProjectStore.preload(applicationContext)
        }
    }

    private fun setupSearch() {
        binding.searchView.setupWithSearchBar(binding.searchBar)

        binding.searchBar.inflateMenu(R.menu.menu_main)
        binding.searchBar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        binding.searchView.addTransitionListener { _, _, newState ->
            when (newState) {
                com.google.android.material.search.SearchView.TransitionState.SHOWING -> {
                    binding.viewPager.isUserInputEnabled = false
                    binding.searchView.editText.postDelayed({
                        binding.searchView.editText.requestFocus()
                        WindowCompat.getInsetsController(window, binding.searchView.editText)
                            .show(WindowInsetsCompat.Type.ime())
                    }, 350)
                }
                com.google.android.material.search.SearchView.TransitionState.HIDDEN -> {
                    binding.viewPager.isUserInputEnabled = true
                }
                else -> {}
            }
        }

        binding.searchView.editText.addTextChangedListener { text ->
            val query = text?.toString()?.trim() ?: ""
            // Debounced: without this every keystroke re-filtered all installed
            // apps and rebound the result list.
            searchRunnable?.let { binding.searchView.removeCallbacks(it) }
            val runnable = Runnable { filterSearchResults(query) }
            searchRunnable = runnable
            binding.searchView.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
        }
    }

    fun updateInstalledApps(apps: List<AppItem>) {
        allInstalledApps = apps
        // Pre-lowercase once so filtering does not allocate two lowercase strings
        // per app on every keystroke.
        searchNameKeys = apps.map { it.name.lowercase() }
        searchPackageKeys = apps.map { it.packageName.lowercase() }
        if (binding.searchView.isShowing) {
            filterSearchResults(binding.searchView.editText.text?.toString()?.trim() ?: "")
        }
    }

    private fun filterSearchResults(query: String) {
        val filtered = if (query.isBlank()) {
            allInstalledApps
        } else {
            val needle = query.lowercase()
            allInstalledApps.indices.filter { index ->
                searchNameKeys.getOrNull(index)?.contains(needle) == true ||
                    searchPackageKeys.getOrNull(index)?.contains(needle) == true
            }.map { allInstalledApps[it] }
        }

        binding.recyclerSearchResults.isVisible = filtered.isNotEmpty()
        binding.layoutSearchEmpty.isVisible = filtered.isEmpty()

        // One adapter for the life of the activity, so the icon cache survives
        // typing instead of being rebuilt empty on every character.
        val adapter = binding.recyclerSearchResults.adapter as? BrowseFragment.AppAdapter
        if (adapter != null) {
            adapter.submit(filtered)
        } else {
            binding.recyclerSearchResults.adapter = BrowseFragment.AppAdapter(
                filtered,
                onItemClick = { selectedApp ->
                    binding.searchView.hide()
                    AppDetailActivity.start(this, packageName = selectedApp.packageName)
                },
                onSaveApkToClick = { app ->
                    val browseFragment = supportFragmentManager.fragments.firstOrNull { it is BrowseFragment } as? BrowseFragment
                    browseFragment?.saveApk(app)
                },
                iconLoader = searchIconLoader
            )
        }
    }

    private fun setupSwipeNavigation() {
        val fragments: List<() -> androidx.fragment.app.Fragment> = listOf(
            { BrowseFragment() },
            { ProjectsFragment() }
        )

        binding.viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): androidx.fragment.app.Fragment = fragments[position]()
        }

        val titles = listOf(
            R.string.title_browse,
            R.string.title_projects
        )
        val menuIds = listOf(
            R.id.navigation_browse,
            R.id.navigation_projects
        )

        val updateTabVisibility = { position: Int ->
            if (position == 0) {
                binding.toolbar.visibility = View.GONE
                binding.searchBar.visibility = View.VISIBLE
            } else {
                binding.searchBar.visibility = View.GONE
                binding.toolbar.visibility = View.VISIBLE
                supportActionBar?.setTitle(titles[position])
            }
        }

        updateTabVisibility(0)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val index = menuIds.indexOf(item.itemId)
            if (index != -1 && binding.viewPager.currentItem != index) {
                binding.viewPager.setCurrentItem(index, true)
            }
            true
        }

        binding.bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.navigation_browse) {
                val browseFragment = supportFragmentManager.fragments.firstOrNull { it is BrowseFragment } as? BrowseFragment
                if (browseFragment != null) {
                    if (!browseFragment.isAtTopOfPage()) {
                        browseFragment.scrollToTop()
                    } else {
                        binding.searchView.show()
                    }
                }
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position in menuIds.indices) {
                    if (binding.bottomNav.selectedItemId != menuIds[position]) {
                        binding.bottomNav.selectedItemId = menuIds[position]
                    }
                    updateTabVisibility(position)
                }
            }
        })
    }

    fun showSelectionBar(
        count: Int,
        onBack: () -> Unit,
        onZip: (() -> Unit)? = null,
        onDelete: (() -> Unit)? = null
    ) {
        val alreadyShowing = binding.layoutSelectionTopBar.visibility == View.VISIBLE
        binding.textSelectionTitle.text = count.toString()
        binding.btnSelectionBack.setOnClickListener { onBack() }
        binding.btnSelectionZip.isVisible = onZip != null
        if (onZip != null) {
            binding.btnSelectionZip.setOnClickListener { onZip() }
        }
        binding.btnSelectionDelete.isVisible = onDelete != null
        if (onDelete != null) {
            binding.btnSelectionDelete.setOnClickListener { onDelete() }
        }
        binding.viewPager.isUserInputEnabled = false

        if (!alreadyShowing) {
            binding.searchBar.animate().cancel()
            binding.layoutSelectionTopBar.animate().cancel()
            binding.bottomNav.animate().cancel()

            val offset = 8 * resources.displayMetrics.density

            binding.searchBar.animate()
                .alpha(0f)
                .translationY(-offset)
                .setDuration(150)
                .withEndAction {
                    binding.searchBar.visibility = View.GONE
                    binding.searchBar.translationY = 0f
                }
                .start()

            binding.toolbar.visibility = View.GONE

            binding.layoutSelectionTopBar.apply {
                alpha = 0f
                translationY = -offset
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }

            binding.bottomNav.visibility = View.GONE
            binding.bottomNav.alpha = 1f
            binding.bottomNav.translationY = 0f
        }
    }

    fun updateSelectionCount(count: Int) {
        binding.textSelectionTitle.text = count.toString()
    }

    fun hideSelectionBar() {
        binding.viewPager.isUserInputEnabled = true
        val wasShowing = binding.layoutSelectionTopBar.visibility == View.VISIBLE

        binding.layoutSelectionTopBar.animate().cancel()
        binding.searchBar.animate().cancel()
        binding.bottomNav.animate().cancel()

        val offset = 8 * resources.displayMetrics.density

        if (wasShowing) {
            binding.layoutSelectionTopBar.animate()
                .alpha(0f)
                .translationY(-offset)
                .setDuration(150)
                .withEndAction {
                    binding.layoutSelectionTopBar.visibility = View.GONE
                    binding.layoutSelectionTopBar.translationY = 0f
                }
                .start()

            if (binding.viewPager.currentItem == 0) {
                binding.toolbar.visibility = View.GONE
                binding.searchBar.apply {
                    alpha = 0f
                    translationY = -offset
                    visibility = View.VISIBLE
                    animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .start()
                }
            } else {
                binding.searchBar.visibility = View.GONE
                binding.toolbar.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate()
                        .alpha(1f)
                        .setDuration(220)
                        .start()
                }
            }

            binding.bottomNav.visibility = View.VISIBLE
            binding.bottomNav.alpha = 1f
            binding.bottomNav.translationY = 0f
        } else {
            binding.layoutSelectionTopBar.visibility = View.GONE
            if (binding.viewPager.currentItem == 0) {
                binding.searchBar.visibility = View.VISIBLE
                binding.searchBar.alpha = 1f
                binding.searchBar.translationY = 0f
                binding.toolbar.visibility = View.GONE
            } else {
                binding.searchBar.visibility = View.GONE
                binding.toolbar.visibility = View.VISIBLE
                binding.toolbar.alpha = 1f
            }
            binding.bottomNav.visibility = View.VISIBLE
            binding.bottomNav.alpha = 1f
            binding.bottomNav.translationY = 0f
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, PreferenceActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}