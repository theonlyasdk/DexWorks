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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.preference.PreferenceManager
import com.asdk.tools.dexworks.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var allInstalledApps: List<AppItem> = emptyList()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                Snackbar.make(binding.root, R.string.perm_notif_rationale, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_retry) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setupSearch()
        setupSwipeNavigation()
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
            filterSearchResults(text?.toString()?.trim() ?: "")
        }
    }

    fun updateInstalledApps(apps: List<AppItem>) {
        allInstalledApps = apps
        if (binding.searchView.isShowing) {
            filterSearchResults(binding.searchView.editText.text?.toString()?.trim() ?: "")
        }
    }

    private fun filterSearchResults(query: String) {
        val showSystemApps = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("show_system_apps", false)
        val baseList = if (showSystemApps) {
            allInstalledApps
        } else {
            allInstalledApps.filter { !it.isSystemApp }
        }

        val filtered = if (query.isBlank()) {
            baseList
        } else {
            baseList.filter { app ->
                app.name.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
            }
        }

        binding.recyclerSearchResults.isVisible = filtered.isNotEmpty()
        binding.layoutSearchEmpty.isVisible = filtered.isEmpty()

        binding.recyclerSearchResults.adapter = BrowseFragment.AppAdapter(
            filtered,
            onItemClick = { selectedApp ->
                binding.searchView.hide()
                AppDetailActivity.start(this, packageName = selectedApp.packageName)
            },
            onSaveApkToClick = { app ->
                val browseFragment = supportFragmentManager.fragments.firstOrNull { it is BrowseFragment } as? BrowseFragment
                browseFragment?.saveApk(app)
            }
        )
    }

    private fun setupSwipeNavigation() {
        val fragments: List<() -> androidx.fragment.app.Fragment> = listOf(
            { BrowseFragment() },
            { ProjectsFragment() },
            { ToolsFragment() }
        )

        binding.viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): androidx.fragment.app.Fragment = fragments[position]()
        }

        val titles = listOf(
            R.string.title_browse,
            R.string.title_projects,
            R.string.title_tools
        )
        val menuIds = listOf(
            R.id.navigation_browse,
            R.id.navigation_projects,
            R.id.navigation_tools
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

    fun showSelectionBar(count: Int, onBack: () -> Unit, onZip: () -> Unit) {
        val alreadyShowing = binding.layoutSelectionTopBar.visibility == View.VISIBLE
        binding.textSelectionTitle.text = count.toString()
        binding.btnSelectionBack.setOnClickListener { onBack() }
        binding.btnSelectionZip.setOnClickListener { onZip() }
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

            val navHeight = if (binding.bottomNav.height > 0) binding.bottomNav.height.toFloat() else 80 * resources.displayMetrics.density
            binding.bottomNav.animate()
                .alpha(0f)
                .translationY(navHeight)
                .setDuration(180)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    binding.bottomNav.visibility = View.GONE
                }
                .start()
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

            binding.bottomNav.apply {
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val showSystem = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("show_system_apps", false)

        val showSystemItem = menu.findItem(R.id.action_show_system_apps)
        showSystemItem?.isChecked = showSystem

        val searchShowSystemItem = binding.searchBar.menu.findItem(R.id.action_show_system_apps)
        searchShowSystemItem?.isChecked = showSystem

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_show_system_apps -> {
                val newChecked = !item.isChecked
                item.isChecked = newChecked
                binding.searchBar.menu.findItem(R.id.action_show_system_apps)?.isChecked = newChecked
                PreferenceManager.getDefaultSharedPreferences(this)
                    .edit()
                    .putBoolean("show_system_apps", newChecked)
                    .apply()
                if (binding.searchView.isShowing) {
                    filterSearchResults(binding.searchView.editText.text?.toString()?.trim() ?: "")
                }
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, PreferenceActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}