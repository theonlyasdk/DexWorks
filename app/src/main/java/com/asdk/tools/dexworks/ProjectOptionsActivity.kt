package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.asdk.tools.dexworks.databinding.ActivityProjectOptionsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProjectOptionsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_PROJECT_PATH = "extra_project_path"
        const val EXTRA_APK_PATH = "extra_apk_path"

        fun createIntent(
            context: Context,
            projectName: String,
            projectPath: String,
            apkPath: String?
        ): Intent {
            return Intent(context, ProjectOptionsActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_PROJECT_PATH, projectPath)
                putExtra(EXTRA_APK_PATH, apkPath)
            }
        }
    }

    private lateinit var binding: ActivityProjectOptionsBinding
    private var projectName: String = ""
    private var projectPath: String = ""
    private var apkPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdgeWithPadding(binding.root)

        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH).orEmpty()
        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        if (apkPath.isBlank() || !File(apkPath).isFile) {
            val candidate = File(projectPath, "project.apk")
            if (candidate.isFile) {
                apkPath = candidate.absolutePath
            } else if (File(projectPath).isFile && projectPath.endsWith(".apk", ignoreCase = true)) {
                apkPath = projectPath
            }
        }
        binding.toolbar.title = projectName.ifBlank { getString(R.string.title_project_options) }
        binding.toolbar.setNavigationOnClickListener { finish() }

        val hasApk = apkPath.isNotBlank() && File(apkPath).isFile
        binding.textNoApk.isVisible = !hasApk

        // The 3-dot offers the same actions as the long-press sheet in the
        // projects list, driven by the same ProjectActions definition.
        binding.toolbar.inflateMenu(R.menu.menu_project_options)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_project_options_more) {
                ProjectActions.showMenu(this, binding.toolbar) { actionId ->
                    runProjectAction(actionId, hasApk)
                }
                true
            } else {
                false
            }
        }

        setupCard(binding.cardAppDetails, hasApk) {
            if (hasApk) {
                AppDetailActivity.start(this, apkPath = apkPath, fromProject = true)
            }
        }
        setupCard(binding.cardDecompile, hasApk) {
            if (hasApk) {
                ProjectActions.startDecompile(this, apkPath, projectName)
            }
        }
        setupCard(binding.cardApkBrowse, hasApk) {
            startActivity(ApkBrowseActivity.createIntent(this, apkPath, projectName))
        }
        setupCard(binding.cardManifestViewer, hasApk) {
            if (hasApk) {
                startActivity(
                    ManifestInspectorActivity.createIntent(
                        this,
                        apkPath,
                        projectName,
                        ""
                    )
                )
            }
        }
    }

    private fun runProjectAction(actionId: Int, hasApk: Boolean) {
        when (actionId) {
            ProjectActions.ACTION_OPEN -> finish()
            ProjectActions.ACTION_APP_DETAILS -> if (hasApk) {
                AppDetailActivity.start(this, apkPath = apkPath, fromProject = true)
            } else {
                ProjectActions.notImplemented(this, binding.root)
            }
            ProjectActions.ACTION_DECOMPILE -> {
                ProjectActions.startDecompile(this, apkPath, projectName)
            }
            ProjectActions.ACTION_BROWSE_APK -> if (hasApk) {
                startActivity(ApkBrowseActivity.createIntent(this, apkPath, projectName))
            } else {
                ProjectActions.notImplemented(this, binding.root)
            }
            ProjectActions.ACTION_MANIFEST -> if (hasApk) {
                startActivity(
                    ManifestInspectorActivity.createIntent(this, apkPath, projectName, "")
                )
            } else {
                ProjectActions.notImplemented(this, binding.root)
            }
            ProjectActions.ACTION_ANALYZE -> if (hasApk) {
                startActivity(ApkAnalysisActivity.createIntent(this, apkPath, projectName))
            } else {
                ProjectActions.notImplemented(this, binding.root)
            }
            ProjectActions.ACTION_SAVE_APK -> if (hasApk) {
                FileSaveHelper.from(this)
                    .saveFile(File(apkPath), "project.apk", forcePickLocation = true)
            } else {
                ProjectActions.notImplemented(this, binding.root)
            }
            ProjectActions.ACTION_DELETE -> confirmDeleteProject()
        }
    }

    private fun setupCard(card: View, enabled: Boolean, onClick: () -> Unit) {
        card.isEnabled = enabled
        card.alpha = if (enabled) 1f else 0.5f
        card.setOnClickListener {
            if (enabled) onClick()
        }
    }
}
