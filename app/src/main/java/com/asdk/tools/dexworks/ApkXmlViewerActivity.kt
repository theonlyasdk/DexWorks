package com.asdk.tools.dexworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityApkXmlViewerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.rosemoe.sora.lang.EmptyLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApkXmlViewerActivity : AppCompatActivity() {

    private enum class EntryLanguage { XML, JAVASCRIPT, PLAIN }

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_ENTRY_PATH = "extra_entry_path"

        private val ROW_IDS = listOf(
            R.id.action_share,
            R.id.action_save_to,
            R.id.action_unobfuscate,
            R.id.action_format_json,
            R.id.action_prettify,
            R.id.action_copy
        )

        fun createIntent(context: Context, apkPath: String, entryPath: String): Intent {
            return Intent(context, ApkXmlViewerActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_ENTRY_PATH, entryPath)
            }
        }
    }

    private lateinit var binding: ActivityApkXmlViewerBinding
    private lateinit var fileSaveHelper: FileSaveHelper
    private lateinit var apkPath: String
    private lateinit var entryPath: String
    private var currentText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityApkXmlViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPadding(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH).orEmpty()
        fileSaveHelper = FileSaveHelper.from(this)
        val isXml = entryPath.endsWith(".xml", ignoreCase = true) ||
            entryPath.equals("androidmanifest.xml", ignoreCase = true)
        binding.toolbar.title = if (isXml) {
            getString(R.string.apk_info_viewer_xml)
        } else {
            getString(R.string.apk_info_viewer_text)
        }
        binding.toolbar.subtitle = entryPath
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_viewer_actions)
        binding.toolbar.menu.findItem(R.id.action_word_wrap)?.isChecked =
            AppPrefs.codeWordWrap(this)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_viewer_more -> {
                    showActionsSheet()
                    true
                }
                R.id.action_word_wrap -> {
                    val enabled = !item.isChecked
                    item.isChecked = enabled
                    // Persisted so it is retained across sessions and shared with
                    // the manifest inspector.
                    AppPrefs.get(this).edit()
                        .putBoolean(AppPrefs.KEY_CODE_WORD_WRAP, enabled)
                        .apply()
                    binding.codeEditor.setWordwrap(enabled)
                    true
                }
                else -> false
            }
        }

        setupCodeEditor()
        loadEntry()
    }

    private fun showActionsSheet() {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_viewer_actions, null)
        val isJavaScript = viewerLanguage() == EntryLanguage.JAVASCRIPT
        val isJson = isJsonEntry()
        content.findViewById<TextView>(R.id.text_copy_label).setText(R.string.viewer_copy_text)
        content.findViewById<View>(R.id.action_unobfuscate).isVisible = isJavaScript
        // JSON offers formatting instead of deobfuscation, which does not apply.
        content.findViewById<View>(R.id.action_format_json).isVisible = isJson
        content.findViewById<View>(R.id.action_prettify).isVisible = isJavaScript
        applySegmentCorners(
            content,
            ROW_IDS
        )
        content.findViewById<View>(R.id.action_share).setOnClickListener {
            dialog.dismiss()
            shareEntry()
        }
        content.findViewById<View>(R.id.action_save_to).setOnClickListener {
            dialog.dismiss()
            saveEntry()
        }
        content.findViewById<View>(R.id.action_unobfuscate).setOnClickListener {
            dialog.dismiss()
            unobfuscateEntry()
        }
        content.findViewById<View>(R.id.action_format_json).setOnClickListener {
            dialog.dismiss()
            formatJsonEntry()
        }
        content.findViewById<View>(R.id.action_prettify).setOnClickListener {
            dialog.dismiss()
            applyTransform(JsSourceTools::prettify, R.string.viewer_prettify)
        }
        content.findViewById<View>(R.id.action_copy).setOnClickListener {
            dialog.dismiss()
            copyText()
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

    private fun isJsonEntry(): Boolean {
        val path = entryPath.lowercase()
        return path.endsWith(".json") || path.endsWith(".json5")
    }

    private fun formatJsonEntry() {
        val source = currentText
        if (source.isBlank()) {
            Snackbar.make(binding.root, R.string.error_loading_xml, Snackbar.LENGTH_SHORT).show()
            return
        }
        val formatted = try {
            JsonFormatter.prettify(source)
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.error_loading_xml, Snackbar.LENGTH_LONG).show()
            return
        }
        if (formatted == source) {
            Snackbar.make(binding.root, R.string.viewer_no_changes, Snackbar.LENGTH_SHORT).show()
            return
        }
        currentText = formatted
        binding.codeEditor.setText(formatted)
        Snackbar.make(
            binding.root,
            getString(R.string.viewer_transform_applied, getString(R.string.viewer_format_json)),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun unobfuscateEntry() {
        val source = currentText
        if (source.isEmpty()) {
            Snackbar.make(binding.root, R.string.error_loading_xml, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.layoutLoading.isVisible = true
        lifecycleScope.launch {
            val (result, report) = withContext(Dispatchers.Default) {
                JsSourceTools.unobfuscateWithReport(source)
            }
            binding.layoutLoading.isVisible = false
            if (result == source) {
                Snackbar.make(
                    binding.root,
                    report.notes.firstOrNull() ?: getString(R.string.viewer_no_changes),
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }
            currentText = result
            binding.codeEditor.setText(result)
            val summary = getString(
                R.string.viewer_unobfuscate_summary,
                report.referencesReplaced,
                report.arraysResolved,
                report.constantFolds
            )
            Snackbar.make(binding.root, summary, Snackbar.LENGTH_LONG)
                .setAction(R.string.viewer_details) {
                    MaterialAlertDialogBuilder(this@ApkXmlViewerActivity)
                        .setTitle(R.string.viewer_unobfuscate)
                        .setMessage(buildString {
                            append(summary)
                            if (report.notes.isNotEmpty()) {
                                append("\n\n")
                                report.notes.forEach { append("- $it\n") }
                            }
                        })
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                .show()
        }
    }

    private fun applyTransform(transform: (String) -> String, labelRes: Int) {
        val source = currentText
        if (source.isEmpty()) {
            Snackbar.make(binding.root, R.string.error_loading_xml, Snackbar.LENGTH_SHORT).show()
            return
        }
        val result = transform(source)
        if (result == source) {
            Snackbar.make(binding.root, R.string.viewer_no_changes, Snackbar.LENGTH_SHORT).show()
            return
        }
        currentText = result
        binding.codeEditor.setText(result)
        Snackbar.make(
            binding.root,
            getString(R.string.viewer_transform_applied, getString(labelRes)),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun shareEntry() {
        if (apkPath.isBlank() || entryPath.isBlank()) return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ApkEntryFiles.materialize(applicationContext, apkPath, entryPath)
            }
            if (file == null) {
                Snackbar.make(binding.root, R.string.viewer_save_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val uri = ApkEntryFiles.uriFor(applicationContext, file) ?: return@launch
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                val mime = when {
                    entryPath.endsWith(".xml", ignoreCase = true) -> "text/xml"
                    entryPath.endsWith(".json", ignoreCase = true) -> "application/json"
                    entryPath.endsWith(".html", ignoreCase = true) || entryPath.endsWith(".htm", ignoreCase = true) -> "text/html"
                    else -> "text/plain"
                }
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.viewer_share)))
        }
    }

    private fun saveEntry() {
        if (apkPath.isBlank() || entryPath.isBlank()) return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ApkEntryFiles.materialize(applicationContext, apkPath, entryPath)
            }
            if (file == null) {
                Snackbar.make(binding.root, R.string.viewer_save_failed, Snackbar.LENGTH_LONG).show()
                return@launch
            }
            fileSaveHelper.saveFile(
                file,
                entryPath.substringAfterLast('/'),
                forcePickLocation = true
            )
        }
    }

    private fun copyText() {
        val text = binding.codeEditor.text?.toString().orEmpty()
        if (text.isEmpty()) {
            Snackbar.make(binding.root, R.string.error_loading_xml, Snackbar.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(entryPath.substringAfterLast('/'), text)
        )
        Snackbar.make(binding.root, R.string.viewer_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun setupCodeEditor() {
        val ctx: Context = this
        binding.codeEditor.apply {
            setTypefaceText(Typeface.MONOSPACE)
            setTypefaceLineNumber(Typeface.MONOSPACE)
            setTextSize(AppPrefs.codeTextSizeSp(ctx))
            setEditable(false)
            setLineNumberEnabled(AppPrefs.codeLineNumbers(ctx))
            setScalable(true)
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
            val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, resources.displayMetrics)
            setScaleTextSizes(minPx, maxPx)
            setPinLineNumber(AppPrefs.codeLineNumbers(ctx))
            setWordwrap(AppPrefs.codeWordWrap(ctx))
            setHighlightCurrentLine(false)
            val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            when (viewerLanguage()) {
                EntryLanguage.XML -> {
                    colorScheme = XmlColorScheme(isDarkMode)
                    setEditorLanguage(XmlLanguage())
                }
                EntryLanguage.JAVASCRIPT -> {
                    colorScheme = JavaScriptColorScheme(isDarkMode)
                    setEditorLanguage(JavaScriptLanguage())
                }
                EntryLanguage.PLAIN -> {
                    colorScheme = XmlColorScheme(isDarkMode)
                    setEditorLanguage(EmptyLanguage())
                }
            }
        }
    }

    private fun viewerLanguage(): EntryLanguage {
        val path = entryPath.lowercase()
        val extension = path.substringAfterLast('.', "")
        return when (extension) {
            "xml" -> EntryLanguage.XML
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts",
            "json", "json5" -> EntryLanguage.JAVASCRIPT
            else -> EntryLanguage.PLAIN
        }
    }

    private fun loadEntry() {
        if (apkPath.isBlank() || entryPath.isBlank()) {
            showError(getString(R.string.error_loading_xml))
            return
        }

        binding.layoutLoading.isVisible = true
        binding.layoutError.isVisible = false
        binding.codeEditor.isVisible = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val isXml = entryPath.endsWith(".xml", ignoreCase = true) ||
                    entryPath.equals("androidmanifest.xml", ignoreCase = true)
                if (isXml) {
                    val xmlResult = ManifestParser.decodeXmlEntry(apkPath, entryPath)
                    if (xmlResult.isSuccess) {
                        return@withContext xmlResult
                    }
                }
                runCatching {
                    java.util.zip.ZipFile(java.io.File(apkPath)).use { zip ->
                        val entry = zip.getEntry(entryPath)
                            ?: throw IllegalStateException("Entry not found: $entryPath")
                        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                }
            }
            binding.layoutLoading.isVisible = false
            result.onSuccess { content ->
                currentText = content
                binding.codeEditor.setText(content)
                binding.codeEditor.isVisible = true
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.error_loading_xml))
            }
        }
    }

    private fun showError(message: String) {
        binding.layoutError.isVisible = true
        binding.textErrorMessage.text = message
        binding.codeEditor.isVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.codeEditor.release()
    }
}
