package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityApkAnalysisResultBinding
import com.asdk.tools.dexworks.databinding.ItemAnalysisEvidenceFileBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApkAnalysisResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_ANALYSIS_TYPE = "extra_analysis_type"

        const val TYPE_FRAMEWORK = "framework"
        const val TYPE_ARCHITECTURE = "architecture"
        const val TYPE_SECURITY = "security"

        fun createIntent(
            context: Context,
            apkPath: String,
            projectName: String,
            analysisType: String
        ): Intent {
            return Intent(context, ApkAnalysisResultActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_ANALYSIS_TYPE, analysisType)
            }
        }
    }

    private lateinit var binding: ActivityApkAnalysisResultBinding
    private var apkPath: String = ""
    private var projectName: String = ""
    private var analysisType: String = TYPE_FRAMEWORK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityApkAnalysisResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        analysisType = intent.getStringExtra(EXTRA_ANALYSIS_TYPE) ?: TYPE_FRAMEWORK

        binding.toolbar.subtitle = projectName
        binding.toolbar.setNavigationOnClickListener { finish() }

        when (analysisType) {
            TYPE_FRAMEWORK -> binding.toolbar.setTitle(R.string.analysis_module_framework_title)
            TYPE_ARCHITECTURE -> binding.toolbar.setTitle(R.string.analysis_module_arch_title)
            TYPE_SECURITY -> binding.toolbar.setTitle(R.string.analysis_module_security_title)
            else -> binding.toolbar.setTitle(R.string.title_analysis_results)
        }

        binding.btnReanalyze.setOnClickListener {
            runAnalysis()
        }

        runAnalysis()
    }

    private fun runAnalysis() {
        binding.layoutLoading.isVisible = true
        binding.scrollResultContent.isVisible = false

        lifecycleScope.launch(Dispatchers.IO) {
            when (analysisType) {
                TYPE_FRAMEWORK -> {
                    val result = ApkMacroAnalyzer.analyzeFramework(apkPath)
                    withContext(Dispatchers.Main) {
                        renderFrameworkResult(result)
                    }
                }
                TYPE_ARCHITECTURE -> {
                    val result = ApkMacroAnalyzer.analyzeArchitecture(apkPath)
                    withContext(Dispatchers.Main) {
                        renderArchitectureResult(result)
                    }
                }
                TYPE_SECURITY -> {
                    val result = ApkMacroAnalyzer.analyzeProtection(apkPath)
                    withContext(Dispatchers.Main) {
                        renderSecurityResult(result)
                    }
                }
            }
        }
    }

    private fun renderFrameworkResult(result: ApkMacroAnalyzer.FrameworkResult) {
        binding.layoutLoading.isVisible = false
        binding.scrollResultContent.isVisible = true

        binding.textPrimaryFramework.text = result.primaryFramework
        binding.textFrameworkDescription.text = result.description

        binding.chipConfidence.text = when (result.confidence) {
            ApkMacroAnalyzer.Confidence.HIGH -> getString(R.string.analysis_result_confidence_high)
            ApkMacroAnalyzer.Confidence.MEDIUM -> getString(R.string.analysis_result_confidence_medium)
            ApkMacroAnalyzer.Confidence.LOW -> getString(R.string.analysis_result_confidence_low)
        }

        if (result.secondaryFrameworks.isNotEmpty()) {
            binding.layoutSecondaryFrameworks.isVisible = true
            binding.chipGroupSecondary.removeAllViews()
            result.secondaryFrameworks.forEach { sec ->
                val chip = Chip(this).apply {
                    text = sec
                    isClickable = false
                    isFocusable = false
                }
                binding.chipGroupSecondary.addView(chip)
            }
        } else {
            binding.layoutSecondaryFrameworks.isVisible = false
        }

        binding.textEvidenceHeader.text =
            getString(R.string.analysis_result_matched_evidence, result.matchedFiles.size)

        binding.containerEvidenceFiles.removeAllViews()
        if (result.matchedFiles.isEmpty()) {
            val emptyBinding = ItemAnalysisEvidenceFileBinding.inflate(layoutInflater, binding.containerEvidenceFiles, false)
            emptyBinding.textEvidencePath.setText(R.string.analysis_result_no_evidence)
            binding.containerEvidenceFiles.addView(emptyBinding.root)
        } else {
            result.matchedFiles.forEach { path -> addEvidenceRow(path) }
        }
    }

    /**
     * Adds one evidence row. Tapping it opens the matched file in whichever viewer
     * suits its type, so a claim in the analysis can be checked against the source.
     */
    private fun addEvidenceRow(path: String) {
        val itemBinding = ItemAnalysisEvidenceFileBinding.inflate(
            layoutInflater,
            binding.containerEvidenceFiles,
            false
        )
        itemBinding.textEvidencePath.text = path
        itemBinding.imageEvidenceIcon.setImageResource(ApkEntryViewerRouter.iconFor(path))
        itemBinding.rootEvidenceItem.setOnClickListener { openEvidence(path) }
        binding.containerEvidenceFiles.addView(itemBinding.root)
    }

    private fun openEvidence(path: String) {
        if (apkPath.isBlank()) return
        try {
            startActivity(ApkEntryViewerRouter.intentFor(this, apkPath, path))
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.analysis_result_evidence_unavailable),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderArchitectureResult(result: ApkMacroAnalyzer.ArchitectureResult) {
        binding.layoutLoading.isVisible = false
        binding.scrollResultContent.isVisible = true

        binding.textPrimaryFramework.text = if (result.isPureDex) "Pure DEX (No Native Binaries)" else result.abis.joinToString(", ")

        val sizeFormatted = Formatter.formatFileSize(this, result.totalNativeSize)
        binding.textFrameworkDescription.text = if (result.isPureDex) {
            "This application does not contain native .so libraries and executes exclusively on the Android Runtime (ART/Dalvik)."
        } else {
            "Contains native C/C++ libraries. Supported ABIs: ${result.abis.joinToString(", ")}. 64-bit architecture supported: ${if (result.is64BitSupported) "Yes" else "No"}. Total native binaries size: $sizeFormatted."
        }

        binding.chipConfidence.text = if (result.is64BitSupported) "64-bit Ready" else if (result.isPureDex) "Platform Agnostic" else "32-bit Only"

        binding.layoutSecondaryFrameworks.isVisible = result.abis.isNotEmpty()
        binding.chipGroupSecondary.removeAllViews()
        result.abis.forEach { abi ->
            val chip = Chip(this).apply {
                text = abi
                isClickable = false
                isFocusable = false
            }
            binding.chipGroupSecondary.addView(chip)
        }

        binding.textEvidenceHeader.text =
            getString(R.string.analysis_result_libraries_title, result.nativeLibraries.size)

        binding.containerEvidenceFiles.removeAllViews()
        if (result.nativeLibraries.isEmpty()) {
            val emptyBinding = ItemAnalysisEvidenceFileBinding.inflate(layoutInflater, binding.containerEvidenceFiles, false)
            emptyBinding.textEvidencePath.setText(R.string.analysis_result_no_evidence)
            binding.containerEvidenceFiles.addView(emptyBinding.root)
        } else {
            result.nativeLibraries.forEach { lib -> addEvidenceRow(lib) }
        }
    }

    private fun renderSecurityResult(result: ApkMacroAnalyzer.ProtectionResult) {
        binding.layoutLoading.isVisible = false
        binding.scrollResultContent.isVisible = true

        binding.textPrimaryFramework.text = result.packerDetected ?: "No Packer Detected"
        binding.textFrameworkDescription.text = result.details

        binding.chipConfidence.text = if (result.isObfuscated) "Packer Protected" else "Standard Packaging"

        binding.layoutSecondaryFrameworks.isVisible = false

        binding.textEvidenceHeader.text =
            getString(R.string.analysis_result_matched_evidence, result.matchedMarkers.size)

        binding.containerEvidenceFiles.removeAllViews()
        if (result.matchedMarkers.isEmpty()) {
            val emptyBinding = ItemAnalysisEvidenceFileBinding.inflate(layoutInflater, binding.containerEvidenceFiles, false)
            emptyBinding.textEvidencePath.setText(R.string.analysis_result_no_evidence)
            binding.containerEvidenceFiles.addView(emptyBinding.root)
        } else {
            result.matchedMarkers.forEach { marker -> addEvidenceRow(marker) }
        }
    }
}
