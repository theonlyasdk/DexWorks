package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityDecompileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DecompileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_DEX_ENTRY_PATH = "extra_dex_entry_path"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_DELETE_APK_AFTER_DECOMPILE = "extra_delete_apk_after_decompile"

        fun createIntent(
            context: Context,
            apkPath: String,
            dexEntryPath: String,
            projectName: String,
            deleteApkAfterDecompile: Boolean = false
        ): Intent {
            return Intent(context, DecompileActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_DEX_ENTRY_PATH, dexEntryPath)
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_DELETE_APK_AFTER_DECOMPILE, deleteApkAfterDecompile)
            }
        }
    }

    private lateinit var binding: ActivityDecompileBinding
    private var apkPath: String = ""
    private var dexEntryPath: String = ""
    private var projectName: String = ""
    private var timerJob: Job? = null
    private var decompileJob: Job? = null
    private var deleteApkAfterDecompile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDecompileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        dexEntryPath = intent.getStringExtra(EXTRA_DEX_ENTRY_PATH).orEmpty()
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        deleteApkAfterDecompile = intent.getBooleanExtra(EXTRA_DELETE_APK_AFTER_DECOMPILE, false)

        setupToolbar()
        setupOptions()
    }

    private fun setupToolbar() {
        binding.toolbar.subtitle = dexEntryPath
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupOptions() {
        binding.groupFormat.setOnCheckedChangeListener { _, checkedId ->
            val isSmali = checkedId == R.id.radio_smali
            binding.layoutSmaliOptions.isVisible = isSmali
            binding.layoutJavaOptions.isVisible = !isSmali
        }

        binding.btnStartDecompile.setOnClickListener {
            startDecompilation()
        }

        binding.btnCancelDecompile.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.decompile_cancel_title)
                .setMessage(R.string.decompile_cancel_message)
                .setNegativeButton(R.string.dialog_decompile_cancel, null)
                .setPositiveButton(R.string.decompile_stop) { _, _ -> decompileJob?.cancel() }
                .show()
        }
    }

    private fun startDecompilation() {
        if (decompileJob?.isActive == true) return
        if (!File(apkPath).isFile || dexEntryPath.isBlank()) {
            Snackbar.make(binding.root, R.string.decompile_error_missing_apk, Snackbar.LENGTH_LONG).show()
            return
        }

        binding.scrollSetup.isVisible = false
        binding.layoutRunning.isVisible = true
        binding.progressDecompile.isVisible = true
        binding.btnCancelDecompile.isVisible = true
        binding.btnViewOutput.isVisible = false

        val isSmali = binding.radioSmali.isChecked
        val formatName = if (isSmali) getString(R.string.decompile_format_smali) else getString(R.string.decompile_format_java)
        binding.textRunningSubtitle.text = getString(
            R.string.decompile_running_status,
            dexEntryPath,
            formatName
        )

        val cleanProjectName = projectName.ifBlank { "decompiled" }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cleanDexName = File(dexEntryPath).nameWithoutExtension
        val dirName = "${cleanProjectName}_${cleanDexName}_${if (isSmali) "smali" else "java"}_${System.currentTimeMillis()}"
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val outputDir = File(baseDir, "decompiled/$dirName")

        val config = DecompilerEngine.DecompileConfig(
            apkFile = File(apkPath),
            dexEntryPath = dexEntryPath,
            outputDir = outputDir,
            format = if (isSmali) DecompilerEngine.Format.SMALI else DecompilerEngine.Format.JAVA,
            smaliRegInfo = binding.checkRegInfo.isChecked,
            smaliLocals = binding.checkLocals.isChecked,
            javaSugar = binding.checkSugar.isChecked,
            javaDeobfuscate = binding.checkDeobfuscate.isChecked,
            deleteApkAfterDecompile = deleteApkAfterDecompile
        )

        val startTime = SystemClock.elapsedRealtime()
        startTimer(startTime)

        binding.textLogs.text = ""
        appendLog("Preparing to decompile $dexEntryPath into ${outputDir.absolutePath}...")

        decompileJob = lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    runInterruptible {
                        DecompilerEngine.decompile(config) { logLine ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) appendLog(logLine)
                            }
                        }
                    }
                }

                stopTimer()
                binding.progressDecompile.isVisible = false
                binding.btnCancelDecompile.isVisible = false

                if (success) {
                    binding.textRunningTitle.setText(R.string.decompile_completed)
                    binding.btnViewOutput.isVisible = true
                    binding.btnViewOutput.setOnClickListener {
                        openBrowser(outputDir)
                    }
                    appendLog("Decompilation complete. Opening the output browser...")
                    openBrowser(outputDir)
                } else {
                    binding.textRunningTitle.setText(R.string.decompile_failed)
                    val hasPartialOutput = outputDir.walkTopDown().any { it.isFile }
                    if (hasPartialOutput) {
                        binding.btnViewOutput.isVisible = true
                        binding.btnViewOutput.setOnClickListener { openBrowser(outputDir) }
                    }
                    appendLog(getString(R.string.decompile_error_output))
                }
            } catch (cancelled: CancellationException) {
                stopTimer()
                binding.progressDecompile.isVisible = false
                binding.btnCancelDecompile.isVisible = false
                binding.textRunningTitle.setText(R.string.decompile_cancelled_title)
                appendLog(getString(R.string.decompile_cancelled))
                throw cancelled
            } catch (e: Exception) {
                stopTimer()
                binding.progressDecompile.isVisible = false
                binding.btnCancelDecompile.isVisible = false
                binding.textRunningTitle.setText(R.string.decompile_failed)
                appendLog("${getString(R.string.decompile_failed)}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun openBrowser(directory: File) {
        val browserTitle = "$projectName - ${if (binding.radioSmali.isChecked) "Smali" else "Java"}"
        val intent = ApkBrowseActivity.createIntent(this, directory.absolutePath, browserTitle)
        startActivity(intent)
    }

    private fun appendLog(line: String) {
        val current = binding.textLogs.text.toString()
        if (current.length > 80_000) {
            binding.textLogs.text = current.takeLast(60_000)
        }
        binding.textLogs.append(line + "\n")
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun startTimer(startTime: Long) {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val elapsedSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                val formatted = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                binding.textElapsedTime.text = getString(R.string.decompile_elapsed_time, formatted)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (decompileJob?.isActive == true) decompileJob?.cancel()
        stopTimer()
    }
}
