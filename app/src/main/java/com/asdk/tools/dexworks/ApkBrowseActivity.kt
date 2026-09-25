package com.asdk.tools.dexworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.view.animation.AccelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.ActivityApkBrowseBinding
import com.asdk.tools.dexworks.databinding.ItemApkEntryBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.util.zip.ZipFile

private data class ApkEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val crc: Long,
    val lastModified: Long
)

private object ApkEntryReader {
    val textExtensions = setOf(
        "txt", "xml", "json", "properties", "pro", "cfg", "ini", "yml", "yaml",
        "md", "csv", "tsv", "html", "htm", "js", "jsx", "ts", "tsx", "css", "scss",
        "smali", "java", "kt", "kts", "c", "h", "cpp", "hpp", "mf", "sf", "dsa",
        "rsa", "version", "info", "proto", "graphql", "svg", "sh", "bat", "cmd",
        "gradle", "toml", "log", "license", "notice", "readme", "flags", "map", "env", "conf"
    )

    private val codeExtensions = setOf(
        "html", "htm", "js", "jsx", "ts", "tsx", "css", "scss", "smali", "java",
        "kt", "kts", "c", "h", "cpp", "hpp", "proto", "graphql", "gradle", "sh",
        "bat", "cmd", "mf", "sf", "toml"
    )

    private val binaryExtensions = setOf(
        "dex", "so", "dll", "dylib", "class", "jar", "apk", "zip", "arsc", "bin",
        "dat", "db", "sqlite", "o", "a", "pb", "tflite", "flatbuffers", "png", "jpg",
        "jpeg", "webp", "gif", "bmp", "ico", "ttf", "otf", "woff", "woff2",
        "mp3", "ogg", "wav", "mp4", "m4a", "webm", "avi", "pdf"
    )

    fun isTextEntry(apkPath: String, entryPath: String): Boolean {
        val path = entryPath.lowercase()
        val extension = path.substringAfterLast('.', "")
        if (extension in textExtensions) return true
        if (extension in binaryExtensions) return false
        if (path.endsWith(".xml") || path == "androidmanifest.xml") return true
        return try {
            ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return false
                val stream = zip.getInputStream(entry)
                val buffer = ByteArray(4096)
                val count = stream.read(buffer)
                if (count <= 0) return true
                isTextContent(buffer, count)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isTextContent(buffer: ByteArray, count: Int): Boolean {
        if (count == 0) return true
        if (count >= 4 && buffer[0] == 0x03.toByte() && buffer[1] == 0x00.toByte() &&
            buffer[2] == 0x08.toByte() && buffer[3] == 0x00.toByte()
        ) {
            return true
        }
        var nullCount = 0
        var controlCount = 0
        for (i in 0 until count) {
            val b = buffer[i].toInt() and 0xFF
            if (b == 0) {
                nullCount++
            } else if (b < 32 && b != 9 && b != 10 && b != 13) {
                controlCount++
            }
        }
        return nullCount == 0 && (controlCount.toFloat() / count) < 0.02f
    }

    fun listEntries(apkPath: String, directoryPath: String): List<ApkEntry> {
        val prefix = if (directoryPath.isBlank()) "" else "$directoryPath/"
        val entries = LinkedHashMap<String, ApkEntry>()

        ZipFile(File(apkPath)).use { zip ->
            val zipEntries = zip.entries()
            while (zipEntries.hasMoreElements()) {
                val entry = zipEntries.nextElement()
                val name = entry.name
                if (!name.startsWith(prefix)) continue

                val relativeName = name.removePrefix(prefix)
                if (relativeName.isBlank() || relativeName.startsWith("/")) continue
                val separator = relativeName.indexOf('/')
                if (separator >= 0) {
                    val directoryName = relativeName.substring(0, separator)
                    if (directoryName.isNotBlank() && !entries.containsKey(directoryName)) {
                        entries[directoryName] = ApkEntry(
                            path = prefix + directoryName,
                            name = directoryName,
                            isDirectory = true,
                            size = 0L,
                            compressedSize = 0L,
                            crc = 0L,
                            lastModified = 0L
                        )
                    }
                } else if (!entry.isDirectory) {
                    entries[name] = ApkEntry(
                        path = name,
                        name = relativeName,
                        isDirectory = false,
                        size = if (entry.size >= 0) entry.size else 0L,
                        compressedSize = if (entry.compressedSize >= 0) entry.compressedSize else 0L,
                        crc = entry.crc,
                        lastModified = entry.time
                    )
                }
            }
        }

        return entries.values.sortedWith(
            compareByDescending<ApkEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    fun readText(apkPath: String, entryPath: String): String? {
        val extension = entryPath.substringAfterLast('.', "").lowercase()
        if (extension !in textExtensions) return null

        return ZipFile(File(apkPath)).use { zip ->
            val entry = zip.getEntry(entryPath) ?: return@use null
            BufferedReader(zip.getInputStream(entry).reader()).use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8192)
                while (builder.length < MAX_TEXT_PREVIEW) {
                    val count = reader.read(
                        buffer,
                        0,
                        minOf(buffer.size, MAX_TEXT_PREVIEW - builder.length)
                    )
                    if (count <= 0) break
                    builder.appendRange(buffer, 0, count)
                }
                builder.toString()
            }
        }
    }

    fun iconRes(entry: ApkEntry): Int {
        val path = entry.path.lowercase()
        val extension = path.substringAfterLast('.', "")
        return when {
            entry.isDirectory -> R.drawable.ic_folder_open
            path.endsWith(".apk") || path.endsWith(".zip") || path.endsWith(".jar") -> {
                R.drawable.ic_tool_extract
            }
            path.endsWith(".dex") -> R.drawable.ic_tool_decompile
            path == "androidmanifest.xml" || path.endsWith(".xml") -> R.drawable.ic_tool_manifest
            path.endsWith(".json") -> R.drawable.ic_file_json
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") -> {
                R.drawable.ic_file_image
            }
            path.startsWith("lib/") || extension in setOf("so", "dll", "dylib") -> {
                R.drawable.ic_file_native
            }
            extension in codeExtensions -> R.drawable.ic_file_code
            extension in textExtensions -> R.drawable.ic_file_text
            else -> R.drawable.ic_file_generic
        }
    }

    fun typeLabelRes(entry: ApkEntry): Int {
        val path = entry.path.lowercase()
        val extension = path.substringAfterLast('.', "")
        return when {
            entry.isDirectory -> R.string.apk_entry_type_folder
            path.endsWith(".apk") || path.endsWith(".zip") || path.endsWith(".jar") -> {
                R.string.apk_entry_type_archive
            }
            path.endsWith(".dex") -> R.string.apk_entry_type_dex
            path == "androidmanifest.xml" || path.endsWith(".xml") -> R.string.apk_entry_type_xml
            path.endsWith(".json") -> R.string.apk_entry_type_json
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") -> {
                R.string.apk_entry_type_image
            }
            path.startsWith("lib/") || extension in setOf("so", "dll", "dylib") -> {
                R.string.apk_entry_type_native
            }
            extension in textExtensions -> R.string.apk_entry_type_text
            else -> R.string.apk_entry_type_file
        }
    }

    fun mimeType(entry: ApkEntry): String {
        val path = entry.path.lowercase()
        val extension = path.substringAfterLast('.', "")
        return when {
            path.endsWith(".apk") -> "application/vnd.android.package-archive"
            path.endsWith(".xml") -> "text/xml"
            path.endsWith(".json") -> "application/json"
            extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> "image/*"
            extension in textExtensions -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private const val MAX_TEXT_PREVIEW = 200_000
}

class ApkBrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        private const val LOADING_SPINNER_DELAY_MS = 250L
        private const val KEY_APK_SORT = "apk_sort_type"

        fun createIntent(context: Context, apkPath: String, projectName: String): Intent {
            return Intent(context, ApkBrowseActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_PROJECT_NAME, projectName)
            }
        }
    }

    private enum class SortMode {
        NAME, SIZE, TYPE, DATE;

        companion object {
            fun fromKey(key: String?): SortMode = when (key) {
                "size" -> SIZE
                "type" -> TYPE
                "date" -> DATE
                else -> NAME
            }

            fun key(mode: SortMode): String = when (mode) {
                NAME -> "name"
                SIZE -> "size"
                TYPE -> "type"
                DATE -> "date"
            }

            fun menuId(mode: SortMode): Int = when (mode) {
                NAME -> R.id.action_sort_name
                SIZE -> R.id.action_sort_size
                TYPE -> R.id.action_sort_type
                DATE -> R.id.action_sort_date
            }
        }
    }

    private lateinit var binding: ActivityApkBrowseBinding
    private lateinit var entryAdapter: ApkEntryAdapter
    private lateinit var fileSaveHelper: FileSaveHelper
    private var apkPath: String = ""
    private var currentPath: String = ""
    private var sortMode: SortMode = SortMode.NAME
    private var foldersFirst: Boolean = true
    private lateinit var prefs: SharedPreferences
    private var loadedEntries: List<ApkEntry> = emptyList()
    private var apkRootLabel: String = ""
    private var loadJob: Job? = null
    private var isScrollToTopButtonShown: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityApkBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fileSaveHelper = FileSaveHelper.from(this)
        prefs = AppPrefs.get(this)
        sortMode = SortMode.fromKey(prefs.getString(KEY_APK_SORT, null))
        foldersFirst = AppPrefs.apkFoldersFirst(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        val projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        binding.toolbar.subtitle = projectName
        binding.toolbar.inflateMenu(R.menu.menu_apk_browse)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort_name -> {
                    item.isChecked = true
                    applySort(SortMode.NAME)
                    persistSort()
                    true
                }
                R.id.action_sort_size -> {
                    item.isChecked = true
                    applySort(SortMode.SIZE)
                    persistSort()
                    true
                }
                R.id.action_sort_type -> {
                    item.isChecked = true
                    applySort(SortMode.TYPE)
                    persistSort()
                    true
                }
                R.id.action_sort_date -> {
                    item.isChecked = true
                    applySort(SortMode.DATE)
                    persistSort()
                    true
                }
                R.id.action_folders_first -> {
                    foldersFirst = !item.isChecked
                    item.isChecked = foldersFirst
                    applySort(sortMode)
                    persistSort()
                    true
                }
                else -> false
            }
        }
        binding.toolbar.menu.findItem(SortMode.menuId(sortMode))?.isChecked = true
        binding.toolbar.menu.findItem(R.id.action_folders_first)?.isChecked = foldersFirst
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        entryAdapter = ApkEntryAdapter(
            onEntryClick = { entry ->
                if (entry.isDirectory) {
                    openDirectory(entry.path)
                } else {
                    showFile(entry)
                }
            },
            onEntryMenuClick = { entry, _ ->
                showEntryActions(entry)
            },
            onEntryLongClick = { entry ->
                showEntryActions(entry)
            }
        )
        binding.recyclerEntries.layoutManager = LinearLayoutManager(this)
        binding.recyclerEntries.adapter = entryAdapter
        binding.btnScrollToTop.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            scrollToTop()
        }
        binding.recyclerEntries.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateScrollToTopButton(rv)
            }
        })
        loadApkRootLabel()
        loadDirectory()
    }

    private fun loadApkRootLabel() {
        val fallback = File(apkPath).name.ifBlank { getString(R.string.apk_browse_root) }
        apkRootLabel = fallback
        lifecycleScope.launch {
            val label = withContext(Dispatchers.IO) {
                runCatching {
                    AppInfoUtils.getPackageArchiveInfo(applicationContext, apkPath)
                        ?.applicationInfo
                        ?.loadLabel(packageManager)
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            if (!label.isNullOrBlank()) {
                apkRootLabel = getString(R.string.apk_browse_root_label, label)
                renderBreadcrumb()
            }
        }
    }

    private fun loadDirectory() {
        binding.layoutError.isVisible = false
        renderBreadcrumb()

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val pending = async(Dispatchers.IO) {
                try {
                    Result.success(ApkEntryReader.listEntries(apkPath, currentPath))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            val quick = withTimeoutOrNull(LOADING_SPINNER_DELAY_MS) { pending.await() }
            if (quick == null) {
                binding.layoutLoading.isVisible = true
                applyLoadResult(pending.await())
            } else {
                applyLoadResult(quick)
            }
        }
    }

    private fun applyLoadResult(result: Result<List<ApkEntry>>) {
        binding.layoutLoading.isVisible = false
        result.onSuccess { entries ->
            loadedEntries = entries
            applySort(sortMode)
        }.onFailure { error ->
            binding.layoutError.isVisible = true
            binding.textError.text = error.message
                ?: getString(R.string.apk_browse_error)
        }
    }

    private fun renderBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        val segments = mutableListOf<Pair<String, String>>()
        segments += apkRootLabel to ""
        var acc = ""
        currentPath.split('/').filter { it.isNotBlank() }.forEach { component ->
            acc = if (acc.isEmpty()) component else "$acc/$component"
            segments += component to acc
        }

        val primary = MaterialColors.getColor(
            container,
            androidx.appcompat.R.attr.colorPrimary
        )
        val onSurfaceVariant = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurfaceVariant
        )

        segments.forEachIndexed { index, (label, path) ->
            if (index > 0) {
                container.addView(TextView(this).apply {
                    text = "/"
                    textSize = 13f
                    setTextColor(onSurfaceVariant)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            }
            val isCurrent = path == currentPath
            container.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (isCurrent) primary else onSurfaceVariant)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                if (!isCurrent) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (path != currentPath) {
                            currentPath = path
                            loadDirectory()
                        }
                    }
                }
            })
        }

        binding.scrollBreadcrumb.post {
            binding.scrollBreadcrumb.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applySort(mode: SortMode) {
        sortMode = mode
        val dirFirst = compareByDescending<ApkEntry> { it.isDirectory }
        val sorted = when (mode) {
            SortMode.NAME -> loadedEntries.sortedWith(
                if (foldersFirst) dirFirst.thenBy { it.name.lowercase() }
                else compareBy { it.name.lowercase() }
            )
            SortMode.SIZE -> loadedEntries.sortedWith(
                if (foldersFirst) {
                    dirFirst.thenByDescending { it.size }.thenBy { it.name.lowercase() }
                } else {
                    compareByDescending<ApkEntry> { it.size }.thenBy { it.name.lowercase() }
                }
            )
            SortMode.TYPE -> loadedEntries.sortedWith(
                if (foldersFirst) {
                    dirFirst
                        .thenBy { getString(ApkEntryReader.typeLabelRes(it)).lowercase() }
                        .thenBy { it.name.lowercase() }
                } else {
                    compareBy<ApkEntry> { getString(ApkEntryReader.typeLabelRes(it)).lowercase() }
                        .thenBy { it.name.lowercase() }
                }
            )
            SortMode.DATE -> loadedEntries.sortedWith(
                if (foldersFirst) {
                    dirFirst.thenByDescending { it.lastModified }.thenBy { it.name.lowercase() }
                } else {
                    compareByDescending<ApkEntry> { it.lastModified }.thenBy { it.name.lowercase() }
                }
            )
        }
        entryAdapter.setItems(sorted)
        binding.layoutEmpty.isVisible = sorted.isEmpty()
        binding.recyclerEntries.isVisible = sorted.isNotEmpty()
        updateScrollToTopButton(binding.recyclerEntries)
    }

    private fun scrollToTop() {
        val lm = binding.recyclerEntries.layoutManager as? LinearLayoutManager
        val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
        if (firstVisible > 15) {
            binding.recyclerEntries.scrollToPosition(15)
        }
        binding.recyclerEntries.smoothScrollToPosition(0)
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

    private fun openDirectory(path: String) {
        currentPath = path
        loadDirectory()
    }

    private fun handleBack() {
        if (currentPath.isNotBlank()) {
            currentPath = currentPath.substringBeforeLast('/', "")
            loadDirectory()
        } else {
            finish()
        }
    }

    private fun showFile(entry: ApkEntry) {
        val intent = when {
            isImageEntry(entry) ->
                ApkImageViewerActivity.createIntent(this, apkPath, entry.path)
            isTextEntry(entry) ->
                ApkXmlViewerActivity.createIntent(this, apkPath, entry.path)
            else ->
                ApkHexViewerActivity.createIntent(this, apkPath, entry.path)
        }
        startActivity(intent)
    }

    private fun isImageEntry(entry: ApkEntry): Boolean {
        val path = entry.path.lowercase()
        val extension = path.substringAfterLast('.', "")
        return extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg")
    }

    private fun isXmlEntry(entry: ApkEntry): Boolean {
        val path = entry.path.lowercase()
        return path.endsWith(".xml") || path == "androidmanifest.xml"
    }

    private fun isTextEntry(entry: ApkEntry): Boolean {
        return ApkEntryReader.isTextEntry(apkPath, entry.path)
    }

    private fun showEntryActions(entry: ApkEntry) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_apk_entry_actions, null)
        val type = getString(ApkEntryReader.typeLabelRes(entry))
        content.findViewById<ImageView>(R.id.image_sheet_entry_icon).setImageResource(
            ApkEntryReader.iconRes(entry)
        )
        content.findViewById<TextView>(R.id.text_sheet_entry_name).apply {
            text = entry.name
            isLongClickable = true
            setOnLongClickListener {
                dialog.dismiss()
                copyEntryPath(entry)
                true
            }
        }
        content.findViewById<TextView>(R.id.text_sheet_entry_meta).text = if (entry.isDirectory) {
            type
        } else {
            getString(
                R.string.apk_browse_sheet_meta,
                type,
                AppInfoUtils.formatFileSize(entry.size)
            )
        }
        content.findViewById<View>(R.id.action_share).isVisible = !entry.isDirectory
        content.findViewById<View>(R.id.action_info).isVisible = !entry.isDirectory
        content.findViewById<View>(R.id.action_save_to).isVisible = !entry.isDirectory
        applySegmentCornersForEntrySheet(content)
        content.findViewById<View>(R.id.action_preview).setOnClickListener {
            dialog.dismiss()
            if (entry.isDirectory) openDirectory(entry.path) else showFile(entry)
        }
        content.findViewById<View>(R.id.action_share).setOnClickListener {
            dialog.dismiss()
            shareEntry(entry)
        }
        content.findViewById<View>(R.id.action_save_to).setOnClickListener {
            dialog.dismiss()
            saveEntryTo(entry)
        }
        content.findViewById<View>(R.id.action_info).setOnClickListener {
            dialog.dismiss()
            showFileInfo(entry)
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
            bottomSheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun persistSort() {
        prefs.edit()
            .putString(KEY_APK_SORT, SortMode.key(sortMode))
            .putBoolean(AppPrefs.KEY_APK_FOLDERS_FIRST, foldersFirst)
            .apply()
    }

    private fun applySegmentCornersForEntrySheet(content: View) {
        applySegmentCorners(
            content,
            listOf(
                R.id.action_preview,
                R.id.action_save_to,
                R.id.action_share,
                R.id.action_info
            ),
            listOf(
                R.id.divider_preview_save,
                R.id.divider_save_share,
                R.id.divider_share_info
            )
        )
    }

    private fun copyEntryPath(entry: ApkEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(entry.path, entry.path))
        Snackbar.make(binding.root, R.string.apk_browse_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun showFileInfo(entry: ApkEntry) {
        if (entry.isDirectory) {
            MaterialAlertDialogBuilder(this)
                .setTitle(entry.name)
                .setMessage(
                    getString(
                        R.string.apk_browse_info_folder,
                        entry.path
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val lastModified = if (entry.lastModified > 0) {
            AppInfoUtils.formatDate(entry.lastModified)
        } else {
            getString(R.string.apk_info_unknown)
        }
        val crc = if (entry.crc >= 0) {
            String.format("%08X", entry.crc)
        } else {
            getString(R.string.apk_info_unknown)
        }
        val compressionRatio = if (entry.compressedSize > 0 && entry.size > 0) {
            String.format(
                "%.1f%%",
                entry.compressedSize.toDouble() / entry.size.toDouble() * 100.0
            )
        } else {
            getString(R.string.apk_info_unknown)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setMessage(
                getString(
                    R.string.apk_browse_info_message,
                    getString(ApkEntryReader.typeLabelRes(entry)),
                    AppInfoUtils.formatFileSize(entry.size),
                    entry.path,
                    AppInfoUtils.formatFileSize(entry.compressedSize),
                    compressionRatio,
                    crc,
                    lastModified,
                    when {
                        isImageEntry(entry) -> getString(R.string.apk_info_viewer_image)
                        isXmlEntry(entry) -> getString(R.string.apk_info_viewer_xml)
                        isTextEntry(entry) -> getString(R.string.apk_info_viewer_text)
                        else -> getString(R.string.apk_info_viewer_hex)
                    }
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareEntry(entry: ApkEntry) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { extractEntry(entry) }
            if (uri == null) {
                Snackbar.make(binding.root, R.string.apk_browse_share_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = ApkEntryReader.mimeType(entry)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.apk_browse_share)))
        }
    }

    private fun saveEntryTo(entry: ApkEntry) {
        if (entry.isDirectory) return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ApkEntryFiles.materialize(applicationContext, apkPath, entry.path)
            }
            if (file == null) {
                Snackbar.make(binding.root, R.string.apk_browse_save_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            fileSaveHelper.saveFile(file, entry.name, forcePickLocation = true)
        }
    }

    private fun extractEntry(entry: ApkEntry): android.net.Uri? {
        val targetFile = ApkEntryFiles.materialize(applicationContext, apkPath, entry.path)
            ?: return null
        return ApkEntryFiles.uriFor(applicationContext, targetFile)
    }

    private class ApkEntryAdapter(
        private val onEntryClick: (ApkEntry) -> Unit,
        private val onEntryMenuClick: (ApkEntry, View) -> Unit,
        private val onEntryLongClick: (ApkEntry) -> Unit
    ) : RecyclerView.Adapter<ApkEntryAdapter.ViewHolder>() {

        private var items: List<ApkEntry> = emptyList()

        fun setItems(newItems: List<ApkEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(val binding: ItemApkEntryBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemApkEntryBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            val context = holder.itemView.context
            holder.binding.imageEntryIcon.setImageResource(ApkEntryReader.iconRes(entry))
            holder.binding.textEntryName.text = entry.name
            holder.binding.textEntrySubtitle.text = context.getString(
                ApkEntryReader.typeLabelRes(entry)
            )
            holder.binding.textEntrySize.text = if (entry.isDirectory) {
                ""
            } else {
                AppInfoUtils.formatFileSize(entry.size)
            }
            holder.binding.root.setBackgroundResource(segmentBackground(position, items.size))
            holder.binding.dividerEntry.isVisible = position != items.size - 1
            holder.binding.root.setOnClickListener { onEntryClick(entry) }
            holder.binding.root.setOnLongClickListener {
                onEntryLongClick(entry)
                true
            }
            holder.binding.btnEntryMenu.setOnClickListener { view ->
                onEntryMenuClick(entry, view)
            }
        }

        private fun segmentBackground(position: Int, count: Int): Int {
            return when {
                count <= 1 -> R.drawable.bg_apk_segment_single
                position == 0 -> R.drawable.bg_apk_segment_top
                position == count - 1 -> R.drawable.bg_apk_segment_bottom
                else -> R.drawable.bg_apk_segment_middle
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
