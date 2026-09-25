package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.asdk.tools.dexworks.databinding.ActivityProjectOptionsBinding
import com.google.android.material.snackbar.Snackbar
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
    private var apkPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProjectOptionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME).orEmpty()
        apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        binding.toolbar.title = projectName.ifBlank { getString(R.string.title_project_options) }
        binding.toolbar.setNavigationOnClickListener { finish() }

        val hasApk = apkPath.isNotBlank() && File(apkPath).isFile
        binding.textNoApk.isVisible = !hasApk
        setupCard(binding.cardAppDetails, hasApk) {
            if (hasApk) {
                AppDetailActivity.start(this, apkPath = apkPath)
            }
        }
        setupCard(binding.cardDecompile, hasApk) {
            Snackbar.make(binding.root, R.string.not_yet_implemented, Snackbar.LENGTH_SHORT).show()
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

    private fun setupCard(card: View, enabled: Boolean, onClick: () -> Unit) {
        card.isEnabled = enabled
        card.alpha = if (enabled) 1f else 0.5f
        card.setOnClickListener {
            if (enabled) onClick()
        }
    }
}
