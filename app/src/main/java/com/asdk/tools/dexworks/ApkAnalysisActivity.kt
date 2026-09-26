package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.asdk.tools.dexworks.databinding.ActivityApkAnalysisBinding

class ApkAnalysisActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_PROJECT_NAME = "extra_project_name"

        fun createIntent(context: Context, apkPath: String, projectName: String): Intent {
            return Intent(context, ApkAnalysisActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_PROJECT_NAME, projectName)
            }
        }
    }

    private lateinit var binding: ActivityApkAnalysisBinding
    private var apkPath: String = ""
    private var projectName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityApkAnalysisBinding.inflate(layoutInflater)
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

        binding.toolbar.subtitle = projectName
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.cardFrameworkAnalysis.setOnClickListener {
            startActivity(
                ApkAnalysisResultActivity.createIntent(
                    this,
                    apkPath,
                    projectName,
                    ApkAnalysisResultActivity.TYPE_FRAMEWORK
                )
            )
        }

        binding.cardArchitectureAnalysis.setOnClickListener {
            startActivity(
                ApkAnalysisResultActivity.createIntent(
                    this,
                    apkPath,
                    projectName,
                    ApkAnalysisResultActivity.TYPE_ARCHITECTURE
                )
            )
        }

        binding.cardSecurityAnalysis.setOnClickListener {
            startActivity(
                ApkAnalysisResultActivity.createIntent(
                    this,
                    apkPath,
                    projectName,
                    ApkAnalysisResultActivity.TYPE_SECURITY
                )
            )
        }
    }
}
