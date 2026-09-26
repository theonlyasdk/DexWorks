package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.asdk.tools.dexworks.databinding.ActivityProjectWizardBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProjectWizardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PROJECT_PATH = "extra_project_path"
        const val EXTRA_PROJECT_NAME = "extra_project_name"

        fun createIntent(context: Context, apkPath: String? = null, appName: String? = null): Intent {
            return Intent(context, ProjectWizardActivity::class.java).apply {
                if (!apkPath.isNullOrBlank()) putExtra(EXTRA_APK_PATH, apkPath)
                if (!appName.isNullOrBlank()) putExtra(EXTRA_APP_NAME, appName)
            }
        }
    }

    private lateinit var binding: ActivityProjectWizardBinding
    private var currentStep = 1
    private var selectedIconKey: String = ProjectIconCatalog.DEFAULT_KEY
    private var useApkIcon: Boolean = false
    private var selectedApkUri: Uri? = null
    private var selectedApkPreviewPath: String? = null
    private var iconPreviewJob: Job? = null

    private val iconPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedIconKey = result.data?.getStringExtra(ProjectIconPickerActivity.EXTRA_ICON_KEY)
                ?: ProjectIconCatalog.DEFAULT_KEY
            updateIconPreview()
        }
    }

    private val apkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedApkUri = uri
            selectedApkPreviewPath = null
            binding.textSelectedApk.text = getString(
                R.string.wizard_apk_selected,
                getApkDisplayName(uri)
            )
            lifecycleScope.launch {
                val previewPath = withContext(Dispatchers.IO) {
                    copyApkToPreviewCache(uri)
                }
                if (previewPath != null) {
                    selectedApkPreviewPath = previewPath
                    val appName = withContext(Dispatchers.IO) {
                        AppInfoUtils.getPackageArchiveInfo(applicationContext, previewPath, fullComponents = false)
                            ?.applicationInfo
                            ?.loadLabel(packageManager)
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                    }
                    if (appName != null && binding.editProjectName.text.isNullOrBlank()) {
                        binding.editProjectName.setText(appName)
                        binding.layoutProjectName.error = null
                    }
                    useApkIcon = true
                    binding.checkboxUseApkIcon.isChecked = true
                    binding.btnChooseIcon.isEnabled = false
                    updateIconPreview()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProjectWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })

        binding.editProjectName.setOnEditorActionListener { _, _, _ ->
            if (currentStep == 2) {
                onNext()
                true
            } else {
                false
            }
        }
        binding.btnChooseIcon.setOnClickListener {
            iconPickerLauncher.launch(
                Intent(this, ProjectIconPickerActivity::class.java)
                    .putExtra(ProjectIconPickerActivity.EXTRA_ICON_KEY, selectedIconKey)
            )
        }
        binding.checkboxUseApkIcon.setOnCheckedChangeListener { _, checked ->
            useApkIcon = checked
            binding.btnChooseIcon.isEnabled = !checked
            updateIconPreview()
        }
        binding.btnChooseApk.setOnClickListener {
            apkPickerLauncher.launch(
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream"
                )
            )
        }
        binding.btnWizardBack.setOnClickListener { handleBack() }
        binding.btnWizardNext.setOnClickListener { onNext() }

        val initialApkPath = intent.getStringExtra(EXTRA_APK_PATH)
        val initialAppName = intent.getStringExtra(EXTRA_APP_NAME)
        if (!initialApkPath.isNullOrBlank()) {
            val file = File(initialApkPath)
            if (file.exists()) {
                selectedApkUri = Uri.fromFile(file)
                selectedApkPreviewPath = initialApkPath
                binding.textSelectedApk.text = getString(R.string.wizard_apk_selected, file.name)
                // Label resolution parses the APK manifest, so it runs on IO while
                // the wizard is already interactive instead of stalling onCreate.
                if (!initialAppName.isNullOrBlank()) {
                    binding.editProjectName.setText(initialAppName)
                } else {
                    lifecycleScope.launch {
                        val resolvedAppName = withContext(Dispatchers.IO) {
                            runCatching {
                                AppInfoUtils.getPackageArchiveInfo(applicationContext, initialApkPath, fullComponents = false)
                                    ?.applicationInfo
                                    ?.loadLabel(packageManager)
                                    ?.toString()
                                    ?.takeIf { it.isNotBlank() }
                            }.getOrNull()
                        }
                        if (!resolvedAppName.isNullOrBlank() && binding.editProjectName.text.isNullOrBlank()) {
                            binding.editProjectName.setText(resolvedAppName)
                            binding.layoutProjectName.error = null
                        }
                    }
                }
                useApkIcon = true
                binding.checkboxUseApkIcon.isChecked = true
                binding.btnChooseIcon.isEnabled = false
                updateIconPreview()
            }
        }

        showStep(1)
    }

    private fun onNext() {
        when (currentStep) {
            1 -> showStep(2)
            2 -> {
                val name = binding.editProjectName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    binding.layoutProjectName.error = getString(R.string.wizard_name_required)
                } else {
                    binding.layoutProjectName.error = null
                    showStep(3)
                }
            }
            3 -> createProject()
        }
    }

    private fun showStep(step: Int) {
        currentStep = step
        binding.layoutStepImport.isVisible = step == 1
        binding.layoutStepName.isVisible = step == 2
        binding.layoutStepIcon.isVisible = step == 3
        binding.btnWizardBack.isVisible = step > 1
        binding.textWizardStep.text = getString(R.string.wizard_step, step, 3)
        binding.progressWizard.setProgressCompat(step, true)
        binding.btnWizardNext.setText(
            if (step == 3) R.string.wizard_create else R.string.wizard_continue
        )
        if (step == 3) {
            updateIconPreview()
        }
    }

    private fun handleBack() {
        if (currentStep > 1) {
            showStep(currentStep - 1)
        } else {
            finish()
        }
    }

    private fun updateIconPreview() {
        val image = binding.imageSelectedIcon
        if (!useApkIcon) {
            showCatalogIcon(image)
            return
        }
        val previewPath = selectedApkPreviewPath
        if (previewPath.isNullOrBlank()) {
            showCatalogIcon(image)
            return
        }
        // Icon resolution parses the APK manifest plus its resource table, so it
        // runs on IO. The catalog icon stands in until the real one arrives; a
        // stale load never overwrites a newer selection.
        showCatalogIcon(image)
        iconPreviewJob?.cancel()
        iconPreviewJob = lifecycleScope.launch {
            val apkIcon = withContext(Dispatchers.IO) {
                runCatching {
                    AppInfoUtils.getPackageArchiveInfo(applicationContext, previewPath, fullComponents = false)
                        ?.applicationInfo
                        ?.loadIcon(packageManager)
                }.getOrNull()
            }
            if (apkIcon != null && selectedApkPreviewPath == previewPath) {
                image.imageTintList = null
                image.setImageDrawable(apkIcon)
            }
        }
    }

    private fun showCatalogIcon(image: android.widget.ImageView) {
        image.imageTintList = ColorStateList.valueOf(
            MaterialColors.getColor(image, androidx.appcompat.R.attr.colorPrimary)
        )
        image.setImageResource(ProjectIconCatalog.iconRes(selectedIconKey))
    }

    private fun copyApkToPreviewCache(uri: Uri): String? {
        val targetFile = File(cacheDir, "wizard_apk_preview.apk")
        return try {
            targetFile.delete()
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                targetFile.outputStream().use { target ->
                    source.copyTo(target)
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            targetFile.delete()
            null
        }
    }

    private fun createProject() {
        val name = binding.editProjectName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.layoutProjectName.error = getString(R.string.wizard_name_required)
            showStep(2)
            return
        }

        binding.btnWizardNext.isEnabled = false
        lifecycleScope.launch {
            val project = withContext(Dispatchers.IO) {
                ProjectStore.createProject(
                    context = applicationContext,
                    name = name,
                    iconKey = selectedIconKey,
                    useApkIcon = useApkIcon,
                    apkUri = selectedApkUri
                )
            }
            if (project == null) {
                binding.btnWizardNext.isEnabled = true
                Snackbar.make(
                    binding.root,
                    R.string.wizard_create_failed,
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra(EXTRA_PROJECT_PATH, project.path)
                        putExtra(EXTRA_PROJECT_NAME, project.name)
                    }
                )
                finish()
            }
        }
    }

    private fun getApkDisplayName(uri: Uri): String {
        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment.orEmpty()
    }
}
