package com.asdk.tools.dexworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityManifestInspectorBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ManifestInspectorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        fun createIntent(context: Context, apkPath: String, appName: String, packageName: String): Intent {
            return Intent(context, ManifestInspectorActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
        }
    }

    private lateinit var binding: ActivityManifestInspectorBinding
    private lateinit var fileSaveHelper: FileSaveHelper

    private var manifestXml: String? = null
    private var apkPath: String = ""
    private var appName: String = ""
    private var packageName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManifestInspectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCodeEditor()
        fileSaveHelper = FileSaveHelper.from(this)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

        setupToolbar()
        loadManifest()
    }

    private fun setupCodeEditor() {
        binding.codeEditor.apply {
            setTypefaceText(Typeface.MONOSPACE)
            setTypefaceLineNumber(Typeface.MONOSPACE)
            setTextSize(13f)
            setEditable(false)
            setLineNumberEnabled(true)
            setScalable(true)
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
            val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, resources.displayMetrics)
            setScaleTextSizes(minPx, maxPx)
            setPinLineNumber(true)
            setWordwrap(false)
            setHighlightCurrentLine(false)

            val isDarkMode = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            colorScheme = XmlColorScheme(isDarkMode)
            setEditorLanguage(XmlLanguage())
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.title_manifest_inspector)
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

    private fun loadManifest() {
        if (apkPath.isBlank()) {
            showError(getString(R.string.error_loading_manifest))
            return
        }

        binding.layoutLoading.isVisible = true
        binding.layoutError.isVisible = false
        binding.codeEditor.isVisible = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ManifestParser.decodeManifest(apkPath)
            }

            result.onSuccess { xml ->
                manifestXml = xml
                binding.codeEditor.setText(xml)
                binding.layoutLoading.isVisible = false
                binding.codeEditor.isVisible = true
                invalidateOptionsMenu()
            }.onFailure { e ->
                binding.layoutLoading.isVisible = false
                showError(e.localizedMessage ?: getString(R.string.error_loading_manifest))
            }
        }
    }

    private fun showError(message: String) {
        binding.layoutError.isVisible = true
        binding.textErrorMessage.text = message
        binding.codeEditor.isVisible = false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_manifest_inspector, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasContent = !manifestXml.isNullOrBlank()
        menu.findItem(R.id.action_save_manifest)?.isEnabled = hasContent
        menu.findItem(R.id.action_share_manifest)?.isEnabled = hasContent
        menu.findItem(R.id.action_copy_manifest)?.isEnabled = hasContent
        menu.findItem(R.id.action_copy_range)?.isEnabled = hasContent
        menu.findItem(R.id.action_select_all_manifest)?.isEnabled = hasContent
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_save_manifest -> {
                saveManifest()
                true
            }
            R.id.action_copy_manifest -> {
                copyManifest()
                true
            }
            R.id.action_select_all_manifest -> {
                selectAllManifest()
                true
            }
            R.id.action_copy_range -> {
                showCopyRangeDialog()
                true
            }
            R.id.action_share_manifest -> {
                shareManifest()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveManifest() {
        val xml = manifestXml ?: return
        val targetName = appName.ifBlank { packageName }.ifBlank { "app" }
        val sanitizedAppName = targetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "${sanitizedAppName}_AndroidManifest.xml"
        fileSaveHelper.saveText(xml, fileName, "text/xml")
    }

    private fun copyManifest() {
        val xml = manifestXml ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AndroidManifest.xml", xml)
        clipboard.setPrimaryClip(clip)
        Snackbar.make(binding.root, R.string.toast_manifest_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun selectAllManifest() {
        binding.codeEditor.selectAll()
    }

    private fun showCopyRangeDialog() {
        val lines = manifestXml?.lines() ?: return
        val total = lines.size
        if (total == 0) return

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val startInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.label_from_line, total)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        val startLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            addView(startInput)
        }

        val endInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.label_to_line, total)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(minOf(50, total).toString())
        }
        val endLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            addView(endInput)
            val topMargin = (8 * resources.displayMetrics.density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, topMargin, 0, 0)
            }
            layoutParams = lp
        }

        layout.addView(startLayout)
        layout.addView(endLayout)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_copy_range_title)
            .setView(layout)
            .setPositiveButton(R.string.action_copy_manifest) { _, _ ->
                val start = (startInput.text?.toString()?.toIntOrNull() ?: 1).coerceIn(1, total)
                val end = (endInput.text?.toString()?.toIntOrNull() ?: total).coerceIn(start, total)
                val rangeText = lines.subList(start - 1, end).joinToString("\n")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("AndroidManifest.xml lines $start-$end", rangeText)
                clipboard.setPrimaryClip(clip)
                Snackbar.make(binding.root, R.string.toast_lines_copied, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareManifest() {
        val xml = manifestXml ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val context = applicationContext
                    val shareDir = File(context.cacheDir, "shared_manifests")
                    if (!shareDir.exists()) {
                        shareDir.mkdirs()
                    } else {
                        shareDir.listFiles()?.forEach { it.delete() }
                    }

                    val targetName = appName.ifBlank { packageName }.ifBlank { "app" }
                    val sanitizedAppName = targetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val targetFile = File(shareDir, "${sanitizedAppName}_AndroidManifest.xml")
                    targetFile.writeText(xml, Charsets.UTF_8)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                } catch (e: Exception) {
                    null
                }
            }

            if (uri != null && !isFinishing && !isDestroyed) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "$appName AndroidManifest.xml")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share_manifest)))
            } else if (!isFinishing && !isDestroyed) {
                Snackbar.make(binding.root, R.string.toast_manifest_share_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.codeEditor.release()
    }
}
