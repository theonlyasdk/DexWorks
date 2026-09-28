package com.asdk.tools.dexworks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivitySourceViewerBinding
import com.google.android.material.snackbar.Snackbar
import io.github.rosemoe.sora.lang.EmptyLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SourceViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_FILE_NAME = "extra_file_name"

        fun createIntent(context: Context, filePath: String, fileName: String): Intent {
            return Intent(context, SourceViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_FILE_NAME, fileName)
            }
        }
    }

    private lateinit var binding: ActivitySourceViewerBinding
    private var filePath: String = ""
    private var fileName: String = ""
    private var currentText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivitySourceViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableEdgeToEdgeWithPadding(binding.root)

        filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        if (fileName.isBlank() && filePath.isNotBlank()) {
            fileName = File(filePath).name
        }

        binding.toolbar.title = fileName
        binding.toolbar.subtitle = filePath
        binding.toolbar.setNavigationOnClickListener { finish() }
        // One overflow for the whole screen. The toolbar used to carry a second,
        // always-visible 3-dot that opened a copy/share bottom sheet next to the
        // real overflow, so two dots sat side by side for one set of actions.
        binding.toolbar.inflateMenu(R.menu.menu_source_viewer_actions)
        binding.toolbar.menu.findItem(R.id.action_word_wrap)?.isChecked =
            AppPrefs.codeWordWrap(this)

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_word_wrap -> {
                    val enabled = !item.isChecked
                    item.isChecked = enabled
                    AppPrefs.get(this).edit()
                        .putBoolean(AppPrefs.KEY_CODE_WORD_WRAP, enabled)
                        .apply()
                    binding.codeEditor.setWordwrap(enabled)
                    true
                }
                R.id.action_copy_text -> {
                    copyCurrentText()
                    true
                }
                R.id.action_share_file -> {
                    shareFile()
                    true
                }
                else -> false
            }
        }

        setupCodeEditor()
        loadFile()
    }

    private fun setupCodeEditor() {
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        binding.codeEditor.apply {
            typefaceText = Typeface.MONOSPACE
            setTextSize(14f)
            isEditable = false
            setWordwrap(AppPrefs.codeWordWrap(this@SourceViewerActivity))

            val ext = fileName.substringAfterLast('.', "").lowercase()
            when (ext) {
                "java" -> {
                    colorScheme = JavaScriptColorScheme(isDarkMode)
                    setEditorLanguage(JavaLanguage())
                }
                "smali" -> {
                    colorScheme = JavaScriptColorScheme(isDarkMode)
                    setEditorLanguage(SmaliLanguage())
                }
                "xml" -> {
                    colorScheme = XmlColorScheme(isDarkMode)
                    setEditorLanguage(XmlLanguage())
                }
                "js", "jsx", "ts", "tsx", "json" -> {
                    colorScheme = JavaScriptColorScheme(isDarkMode)
                    setEditorLanguage(JavaScriptLanguage())
                }
                else -> {
                    colorScheme = JavaScriptColorScheme(isDarkMode)
                    setEditorLanguage(EmptyLanguage())
                }
            }
        }
    }

    private fun loadFile() {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            showError("File not found")
            return
        }

        binding.layoutLoading.isVisible = true
        binding.layoutError.isVisible = false
        binding.codeEditor.isVisible = false

        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    file.readText()
                } catch (e: Exception) {
                    null
                }
            }

            binding.layoutLoading.isVisible = false
            if (content != null) {
                currentText = content
                binding.codeEditor.setText(content)
                binding.codeEditor.isVisible = true
            } else {
                showError(getString(R.string.error_loading_xml))
            }
        }
    }

    private fun showError(message: String) {
        binding.layoutLoading.isVisible = false
        binding.codeEditor.isVisible = false
        binding.layoutError.isVisible = true
        binding.textError.text = message
    }

    private fun copyCurrentText() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(fileName, currentText))
        Snackbar.make(binding.root, R.string.viewer_copied, Snackbar.LENGTH_SHORT).show()
    }

    private fun shareFile() {
        val file = File(filePath)
        if (!file.exists()) return
        val uri = try {
            FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.provider",
                file
            )
        } catch (e: Exception) {
            null
        } ?: return

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.viewer_share)))
    }
}
