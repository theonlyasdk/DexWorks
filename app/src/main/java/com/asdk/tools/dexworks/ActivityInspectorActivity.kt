package com.asdk.tools.dexworks

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.ActivityInspectorBinding
import com.asdk.tools.dexworks.databinding.ItemActivityComponentBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivityInspectorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        fun createIntent(context: Context, apkPath: String, appName: String, packageName: String): Intent {
            return Intent(context, ActivityInspectorActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
        }
    }

    private lateinit var binding: ActivityInspectorBinding
    private var allActivities: List<ActivityComponentItem> = emptyList()
    private var activityFilter: ActivityFilter = ActivityFilter.ALL
    private var apkPath: String = ""
    private var appName: String = ""
    private var packageName: String = ""
    private var isAppInstalled: Boolean = false
    private var isScrollToTopButtonShown: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInspectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

        checkAppInstalled()
        setupToolbar()
        setupSearch()
        binding.btnScrollToTop.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.recyclerActivities.smoothScrollToPosition(0)
        }
        binding.recyclerActivities.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateScrollToTopButton(recyclerView)
            }
        })
        binding.btnRetryActivities.setOnClickListener {
            loadActivities()
        }
        loadActivities()
    }

    private fun checkAppInstalled() {
        if (packageName.isBlank()) {
            isAppInstalled = false
            return
        }
        isAppInstalled = try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.title_activity_inspector)
            if (appName.isNotBlank()) {
                subtitle = appName
            } else if (packageName.isNotBlank()) {
                subtitle = packageName
            }
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private enum class ActivityFilter {
        ALL, EXPORTED, LAUNCHER, ALIAS, HAS_INTENT_FILTERS
    }

    private fun setupSearch() {
        binding.editSearchActivities.addTextChangedListener { text ->
            filterActivities(text?.toString()?.trim() ?: "")
        }
        binding.chipGroupActivityFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            activityFilter = when {
                checkedIds.contains(R.id.chip_filter_exported) -> ActivityFilter.EXPORTED
                checkedIds.contains(R.id.chip_filter_launcher) -> ActivityFilter.LAUNCHER
                checkedIds.contains(R.id.chip_filter_alias) -> ActivityFilter.ALIAS
                checkedIds.contains(R.id.chip_filter_intent_filters) -> ActivityFilter.HAS_INTENT_FILTERS
                else -> ActivityFilter.ALL
            }
            filterActivities(binding.editSearchActivities.text?.toString()?.trim() ?: "")
        }
    }

    private fun loadActivities() {
        binding.layoutLoading.isVisible = true
        binding.layoutEmptyActivities.isVisible = false
        binding.layoutErrorActivities.isVisible = false
        binding.recyclerActivities.isVisible = false
        updateScrollToTopButton(binding.recyclerActivities)

        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) {
                    ActivityComponentParser.parseActivities(apkPath)
                }
            } catch (e: Exception) {
                null
            }

            binding.layoutLoading.isVisible = false
            if (list == null) {
                binding.recyclerActivities.isVisible = false
                binding.layoutEmptyActivities.isVisible = false
                binding.layoutErrorActivities.isVisible = true
                return@launch
            }
            allActivities = list

            filterActivities(binding.editSearchActivities.text?.toString()?.trim() ?: "")
        }
    }

    private fun filterActivities(query: String) {
        val byFilter = when (activityFilter) {
            ActivityFilter.ALL -> allActivities
            ActivityFilter.EXPORTED -> allActivities.filter { it.isExported }
            ActivityFilter.LAUNCHER -> allActivities.filter { it.isLauncher }
            ActivityFilter.ALIAS -> allActivities.filter { it.isAlias }
            ActivityFilter.HAS_INTENT_FILTERS -> allActivities.filter { it.actions.isNotEmpty() }
        }
        val filtered = if (query.isBlank()) {
            byFilter
        } else {
            byFilter.filter {
                it.name.contains(query, ignoreCase = true) ||
                        it.simpleName.contains(query, ignoreCase = true) ||
                        it.actions.any { action -> action.contains(query, ignoreCase = true) }
            }
        }

        binding.recyclerActivities.isVisible = filtered.isNotEmpty()
        binding.layoutEmptyActivities.isVisible = filtered.isEmpty()

        binding.recyclerActivities.adapter = ActivityAdapter(
            items = filtered,
            isInstalled = isAppInstalled,
            packageName = packageName,
            onLaunchClick = { item ->
                launchActivity(item)
            },
            onCopyClick = { item ->
                copyToClipboard("Activity Name", item.name)
            }
        )
        binding.recyclerActivities.post {
            updateScrollToTopButton(binding.recyclerActivities)
        }
    }

    private fun updateScrollToTopButton(recyclerView: RecyclerView) {
        val shouldShow = recyclerView.isVisible && recyclerView.canScrollVertically(-1)
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

    private fun launchActivity(item: ActivityComponentItem) {
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, item.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Snackbar.make(binding.root, R.string.toast_activity_launch_success, Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.toast_activity_launch_failed, Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.action_why) {
                    showActivityLaunchFailureDialog(item, e)
                }
                .show()
        }
    }

    private fun showActivityLaunchFailureDialog(item: ActivityComponentItem, error: Exception) {
        val cause = when (error) {
            is ActivityNotFoundException -> getString(R.string.activity_launch_cause_not_found)
            is SecurityException -> getString(R.string.activity_launch_cause_security)
            is IllegalArgumentException -> getString(R.string.activity_launch_cause_invalid_intent)
            else -> getString(R.string.activity_launch_cause_unknown)
        }
        val details = error.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.activity_launch_failure_no_details)
        val message = getString(
            R.string.activity_launch_failure_message,
            cause,
            getString(R.string.activity_launch_failure_details, item.name, error.javaClass.simpleName, details),
            getString(R.string.activity_launch_failure_causes)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_activity_launch_failed_title)
            .setMessage(message)
            .setNeutralButton(R.string.action_copy_error) { _, _ ->
                copyToClipboard(
                    getString(R.string.activity_launch_failure_copy_label),
                    getString(R.string.activity_launch_exception_copy_text, error.javaClass.simpleName, details)
                )
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Snackbar.make(binding.root, R.string.toast_copied_to_clipboard, Snackbar.LENGTH_SHORT).show()
    }

    private class ActivityAdapter(
        private val items: List<ActivityComponentItem>,
        private val isInstalled: Boolean,
        private val packageName: String,
        private val onLaunchClick: (ActivityComponentItem) -> Unit,
        private val onCopyClick: (ActivityComponentItem) -> Unit
    ) : RecyclerView.Adapter<ActivityAdapter.ViewHolder>() {

        private var lastAnimatedPosition = -1

        class ViewHolder(val binding: ItemActivityComponentBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ItemActivityComponentBinding.inflate(inflater, parent, false)
            return ViewHolder(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val context = holder.itemView.context
            val b = holder.binding

            b.textActivityName.text = item.simpleName
            b.textActivityClassName.text = item.name

            b.chipExported.text = if (item.isExported) {
                context.getString(R.string.badge_exported)
            } else {
                context.getString(R.string.badge_not_exported)
            }

            b.chipLauncher.isVisible = item.isLauncher
            b.chipAlias.isVisible = item.isAlias

            if (!item.targetActivity.isNullOrBlank()) {
                b.textTargetActivity.isVisible = true
                b.textTargetActivity.text = context.getString(R.string.label_target_activity, item.targetActivity)
            } else {
                b.textTargetActivity.isVisible = false
            }

            if (!item.launchMode.isNullOrBlank()) {
                b.textLaunchMode.isVisible = true
                b.textLaunchMode.text = context.getString(R.string.label_launch_mode, item.launchMode)
            } else {
                b.textLaunchMode.isVisible = false
            }

            if (!item.permission.isNullOrBlank()) {
                b.textPermission.isVisible = true
                b.textPermission.text = context.getString(R.string.label_permission_required, item.permission)
            } else {
                b.textPermission.isVisible = false
            }

            if (item.actions.isNotEmpty()) {
                b.layoutIntentFilters.isVisible = true
                val actionsText = item.actions.joinToString("\n") { "• $it" }
                b.textIntentActions.text = actionsText
            } else {
                b.layoutIntentFilters.isVisible = false
            }

            val canLaunch = isInstalled && (item.isExported || item.isLauncher)
            b.btnLaunchActivity.isVisible = canLaunch
            b.btnLaunchActivity.setOnClickListener {
                onLaunchClick(item)
            }

            b.textActivityClassName.setOnClickListener {
                onCopyClick(item)
            }
            b.root.setOnLongClickListener {
                onCopyClick(item)
                true
            }
        }

        override fun onViewAttachedToWindow(holder: ViewHolder) {
            super.onViewAttachedToWindow(holder)
            val pos = holder.bindingAdapterPosition
            if (pos > lastAnimatedPosition) {
                lastAnimatedPosition = pos
                val context = holder.itemView.context
                val isReducedMotion = try {
                    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                } catch (e: Exception) {
                    false
                }

                if (!isReducedMotion) {
                    val v = holder.itemView
                    v.alpha = 0f
                    v.scaleX = 0.94f
                    v.scaleY = 0.94f
                    v.translationY = 28f
                    v.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .start()
                }
            }
        }

        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            holder.itemView.clearAnimation()
        }
    }
}
