package com.asdk.tools.dexworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityDeviceSummaryBinding
import com.asdk.tools.dexworks.databinding.ItemDeviceSummaryCategoryBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Builds a copyable device summary for AI context.
 *
 * The user picks whole sections rather than individual apps: on a device with
 * hundreds of packages, per-app ticking is unusable, and the interesting cuts
 * are "user vs system", "store vs sideloaded", "what is big", not one package.
 */
class DeviceSummaryActivity : AppCompatActivity() {

    companion object {
        private const val LARGEST_APPS_COUNT = 25
        private const val RECENT_APPS_COUNT = 10
        private const val COPIED_STATE_MS = 1600L
        private val COPIED_GREEN = Color.parseColor("#2E7D32")

        private val STORE_SOURCES = setOf(
            "Google Play Store",
            "Amazon Appstore",
            "Samsung Galaxy Store",
            "Huawei AppGallery",
            "Xiaomi GetApps",
            "F-Droid",
            "Aurora Store"
        )

        private val SIDELOAD_SOURCES = setOf(
            "ADB / Sideload",
            "Unknown / Sideloaded",
            "File Manager",
            "Package Installer"
        )

        fun createIntent(context: Context): Intent {
            return Intent(context, DeviceSummaryActivity::class.java)
        }
    }

    private data class SummaryApp(
        val name: String,
        val packageName: String,
        val versionName: String,
        val sizeBytes: Long,
        val isSystemApp: Boolean,
        val source: String,
        val lastUpdate: Long
    )

    private data class SummaryCategory(
        val id: Int,
        val titleRes: Int,
        val descRes: Int,
        val iconRes: Int,
        val section: () -> String
    )

    private lateinit var binding: ActivityDeviceSummaryBinding
    private var allApps: List<SummaryApp> = emptyList()
    private var categories: List<SummaryCategory> = emptyList()
    private val selectedCategoryIds = mutableSetOf<Int>()
    private val resetHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPadding(binding.layoutMainContent)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnCopyPrompt.setOnClickListener { copyPrompt() }
        binding.btnSharePrompt.setOnClickListener { sharePrompt() }
        binding.checkboxSelectAll.setOnClickListener {
            toggleSelectAll(binding.checkboxSelectAll.isChecked)
        }
        // The label sits outside the checkbox, so the whole row toggles too.
        binding.layoutSelectAll.setOnClickListener {
            toggleSelectAll(!binding.checkboxSelectAll.isChecked)
        }

        populateDeviceInfo()
        loadApps()
    }

    private fun populateDeviceInfo() {
        binding.textAndroidVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.textDeviceModel.text = Build.MODEL
        binding.textManufacturer.text = Build.MANUFACTURER
    }

    private fun loadApps() {
        binding.layoutLoading.isVisible = true
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadAppsWithDetails() }
            binding.layoutLoading.isVisible = false
            allApps = apps
            calculateStats()
            buildCategories()
            renderCategories()
            toggleSelectAll(true)
        }
    }

    private fun loadAppsWithDetails(): List<SummaryApp> {
        val pm = packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        val workers = minOf(4, maxOf(1, Runtime.getRuntime().availableProcessors()))
        val chunk = maxOf(1, (packages.size + workers - 1) / workers)
        val pool = Executors.newFixedThreadPool(workers)
        val results = java.util.concurrent.ConcurrentLinkedQueue<SummaryApp>()

        try {
            for (start in packages.indices step chunk) {
                val from = start
                val to = minOf(packages.size, start + chunk)
                pool.execute {
                    for (index in from until to) {
                        val pkg = packages[index]
                        val appInfo = pkg.applicationInfo ?: continue
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        results.add(
                            SummaryApp(
                                name = appInfo.loadLabel(pm).toString().ifBlank { pkg.packageName },
                                packageName = pkg.packageName,
                                versionName = pkg.versionName ?: "N/A",
                                sizeBytes = File(appInfo.sourceDir).length(),
                                isSystemApp = isSystem,
                                source = AppInfoUtils.getInstallationSource(
                                    this@DeviceSummaryActivity,
                                    pkg.packageName,
                                    isSystem
                                ),
                                lastUpdate = pkg.lastUpdateTime
                            )
                        )
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }

        return results.sortedBy { it.name.lowercase() }
    }

    private fun calculateStats() {
        binding.textTotalApps.text = allApps.size.toString()
        binding.textUserApps.text = allApps.count { !it.isSystemApp }.toString()
        binding.textSystemApps.text = allApps.count { it.isSystemApp }.toString()
        binding.textTotalSize.text = AppInfoUtils.formatFileSize(allApps.sumOf { it.sizeBytes })
    }

    private fun userApps() = allApps.filter { !it.isSystemApp }
    private fun systemApps() = allApps.filter { it.isSystemApp }
    private fun storeApps() = allApps.filter { it.source in STORE_SOURCES }
    private fun sideloadedApps() = allApps.filter { it.source in SIDELOAD_SOURCES }
    private fun largestApps() = allApps.sortedByDescending { it.sizeBytes }.take(LARGEST_APPS_COUNT)
    private fun recentApps() = allApps.sortedByDescending { it.lastUpdate }.take(RECENT_APPS_COUNT)

    private fun buildCategories() {
        val user = userApps()
        val system = systemApps()
        val store = storeApps()
        val sideloaded = sideloadedApps()

        categories = listOf(
            SummaryCategory(
                id = 1,
                titleRes = R.string.device_summary_cat_device,
                descRes = R.string.device_summary_cat_device_desc,
                iconRes = R.drawable.ic_settings,
                section = { deviceSection() }
            ),
            SummaryCategory(
                id = 2,
                titleRes = R.string.device_summary_cat_user_apps,
                descRes = R.string.device_summary_cat_user_apps_desc,
                iconRes = R.drawable.ic_nav_browse,
                section = { appSection("User apps", user) }
            ),
            SummaryCategory(
                id = 3,
                titleRes = R.string.device_summary_cat_system_apps,
                descRes = R.string.device_summary_cat_system_apps_desc,
                iconRes = R.drawable.ic_widgets,
                section = { appSection("System apps", system) }
            ),
            SummaryCategory(
                id = 4,
                titleRes = R.string.device_summary_cat_store_apps,
                descRes = R.string.device_summary_cat_store_apps_desc,
                iconRes = R.drawable.ic_folder_filled,
                section = { appSection("Installed from a store", store) }
            ),
            SummaryCategory(
                id = 5,
                titleRes = R.string.device_summary_cat_sideloaded_apps,
                descRes = R.string.device_summary_cat_sideloaded_apps_desc,
                iconRes = R.drawable.ic_file_generic,
                section = { appSection("Sideloaded apps", sideloaded) }
            ),
            SummaryCategory(
                id = 6,
                titleRes = R.string.device_summary_cat_largest_apps,
                descRes = R.string.device_summary_cat_largest_apps_desc,
                iconRes = R.drawable.ic_analytics,
                section = { appSection("Largest apps", largestApps()) }
            ),
            SummaryCategory(
                id = 7,
                titleRes = R.string.device_summary_cat_recent_apps,
                descRes = R.string.device_summary_cat_recent_apps_desc,
                iconRes = R.drawable.ic_folder_open,
                section = { appSection("Recently updated apps", recentApps()) }
            )
        )
    }

    private fun renderCategories() {
        binding.containerCategories.removeAllViews()
        val total = categories.size
        categories.forEachIndexed { index, category ->
            val itemBinding = ItemDeviceSummaryCategoryBinding.inflate(
                layoutInflater,
                binding.containerCategories,
                false
            )
            applyExpressiveCorners(itemBinding.cardCategory, index, total)

            itemBinding.textCategoryTitle.setText(category.titleRes)
            itemBinding.textCategoryDesc.text = categoryDescription(category)
            itemBinding.iconCategory.setImageResource(category.iconRes)
            itemBinding.checkboxCategory.isChecked = selectedCategoryIds.contains(category.id)

            itemBinding.cardCategory.setOnClickListener { toggleCategory(category.id) }
            binding.containerCategories.addView(itemBinding.root)
        }
    }

    private fun categoryDescription(category: SummaryCategory): String {
        return when (category.id) {
            2 -> getString(
                R.string.device_summary_cat_user_apps_desc,
                userApps().size,
                AppInfoUtils.formatFileSize(userApps().sumOf { it.sizeBytes })
            )
            3 -> getString(
                R.string.device_summary_cat_system_apps_desc,
                systemApps().size,
                AppInfoUtils.formatFileSize(systemApps().sumOf { it.sizeBytes })
            )
            4 -> getString(R.string.device_summary_cat_store_apps_desc, storeApps().size)
            5 -> getString(R.string.device_summary_cat_sideloaded_apps_desc, sideloadedApps().size)
            6 -> getString(R.string.device_summary_cat_largest_apps_desc, LARGEST_APPS_COUNT)
            7 -> getString(R.string.device_summary_cat_recent_apps_desc, RECENT_APPS_COUNT)
            else -> getString(category.descRes)
        }
    }

    private fun toggleCategory(id: Int) {
        if (!selectedCategoryIds.remove(id)) {
            selectedCategoryIds.add(id)
        }
        updateSelectionUI()
    }

    private fun toggleSelectAll(checked: Boolean) {
        selectedCategoryIds.clear()
        if (checked) {
            categories.forEach { selectedCategoryIds.add(it.id) }
        }
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val selected = selectedCategoryIds.size
        val total = categories.size

        binding.layoutSelectAll.isVisible = total > 0
        binding.checkboxSelectAll.setOnCheckedChangeListener(null)
        binding.checkboxSelectAll.isChecked = total > 0 && selected == total
        binding.checkboxSelectAll.setOnCheckedChangeListener { _, checked -> toggleSelectAll(checked) }
        binding.btnCopyPrompt.isEnabled = selected > 0
        binding.btnSharePrompt.isEnabled = selected > 0

        if (total > 0) {
            binding.textSelectionCount.text =
                getString(R.string.device_summary_selected_count, selected, total)
        }

        for (index in 0 until binding.containerCategories.childCount) {
            val child = binding.containerCategories.getChildAt(index)
            val category = categories.getOrNull(index) ?: continue
            child.findViewById<android.widget.CheckBox>(R.id.checkbox_category)
                .isChecked = selectedCategoryIds.contains(category.id)
        }
    }

    private fun copyPrompt() {
        if (selectedCategoryIds.isEmpty()) {
            Snackbar.make(binding.root, R.string.device_summary_nothing_selected, Snackbar.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Device Summary", buildPrompt()))
        showCopiedState()
    }

    /** Confirms the copy in place of the snackbar, so the button itself reacts. */
    private fun showCopiedState() {
        resetHandler.removeCallbacksAndMessages(null)
        binding.btnCopyPrompt.setText(R.string.device_summary_copy_done)
        binding.btnCopyPrompt.setIconResource(R.drawable.ic_check)
        binding.btnCopyPrompt.backgroundTintList = ColorStateList.valueOf(COPIED_GREEN)
        binding.btnCopyPrompt.setTextColor(Color.WHITE)
        binding.btnCopyPrompt.iconTint = ColorStateList.valueOf(Color.WHITE)
        resetHandler.postDelayed({
            binding.btnCopyPrompt.setText(R.string.device_summary_copy_button)
            binding.btnCopyPrompt.setIconResource(R.drawable.ic_copy)
            binding.btnCopyPrompt.backgroundTintList = null
            binding.btnCopyPrompt.setTextColor(MaterialColors.getColor(
                binding.btnCopyPrompt,
                com.google.android.material.R.attr.colorOnPrimary
            ))
            binding.btnCopyPrompt.iconTint = null
        }, COPIED_STATE_MS)
    }

    private fun sharePrompt() {
        if (selectedCategoryIds.isEmpty()) {
            Snackbar.make(binding.root, R.string.device_summary_nothing_selected, Snackbar.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildPrompt())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.device_summary_share_button)))
    }

    private fun buildPrompt(): String {
        val sb = StringBuilder()
        sb.appendLine("# Device Summary for AI Context")
        sb.appendLine()
        sb.appendLine(getString(R.string.device_summary_prompt_instruction))
        sb.appendLine()
        categories.filter { selectedCategoryIds.contains(it.id) }.forEach { category ->
            sb.append(category.section())
            sb.appendLine()
        }
        sb.append("*Generated by DexWorks*")
        return sb.toString()
    }

    private fun deviceSection(): String {
        return buildString {
            appendLine("## Device information")
            appendLine("- Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Model: ${Build.MODEL}")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Brand: ${Build.BRAND}")
            appendLine("- Device: ${Build.DEVICE}")
            appendLine("- Total apps: ${allApps.size}")
            appendLine("- User apps: ${userApps().size}")
            appendLine("- System apps: ${systemApps().size}")
            appendLine("- Total size: ${AppInfoUtils.formatFileSize(allApps.sumOf { it.sizeBytes })}")
        }
    }

    private fun appSection(title: String, apps: List<SummaryApp>): String {
        return buildString {
            appendLine("## $title (${apps.size})")
            if (apps.isEmpty()) {
                appendLine("- None")
            } else {
                apps.forEach { app ->
                    appendLine(
                        "- ${app.name} (${app.packageName}) — " +
                            "${app.versionName} — ${AppInfoUtils.formatFileSize(app.sizeBytes)} — ${app.source}"
                    )
                }
            }
        }
    }

    private fun applyExpressiveCorners(
        card: com.google.android.material.card.MaterialCardView,
        index: Int,
        totalCount: Int
    ) {
        val rLarge = 18f * resources.displayMetrics.density
        val rSmall = 4f * resources.displayMetrics.density

        val shapeBuilder = ShapeAppearanceModel.builder()
        when {
            totalCount == 1 -> shapeBuilder.setAllCornerSizes(rLarge)
            index == 0 -> shapeBuilder
                .setTopLeftCorner(CornerFamily.ROUNDED, rLarge)
                .setTopRightCorner(CornerFamily.ROUNDED, rLarge)
                .setBottomLeftCorner(CornerFamily.ROUNDED, rSmall)
                .setBottomRightCorner(CornerFamily.ROUNDED, rSmall)
            index == totalCount - 1 -> shapeBuilder
                .setTopLeftCorner(CornerFamily.ROUNDED, rSmall)
                .setTopRightCorner(CornerFamily.ROUNDED, rSmall)
                .setBottomLeftCorner(CornerFamily.ROUNDED, rLarge)
                .setBottomRightCorner(CornerFamily.ROUNDED, rLarge)
            else -> shapeBuilder.setAllCornerSizes(rSmall)
        }
        card.shapeAppearanceModel = shapeBuilder.build()
    }
}
