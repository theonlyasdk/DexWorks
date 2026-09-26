package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

class AppDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_app_detail)

        val mainLayout = findViewById<android.view.View>(R.id.app_detail_main_layout)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.apply {
            title = getString(R.string.title_app_detail)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        if (savedInstanceState == null) {
            val fragment = AppDetailFragment().apply {
                arguments = intent.extras
            }
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.detail_container, fragment)
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.detail_container) as? AppDetailFragment
        if (fragment?.isZoomViewerOpen() == true) {
            fragment.closeIconZoomViewer()
            return true
        }
        finish()
        return true
    }

    /**
     * Importing from Browse goes through the wizard, and the project controls view is
     * opened straight afterwards so the user lands on the project they just made.
     */
    private val projectWizardLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data ?: return@registerForActivityResult
            val path = data.getStringExtra(ProjectWizardActivity.EXTRA_PROJECT_PATH)
                ?: return@registerForActivityResult
            val name = data.getStringExtra(ProjectWizardActivity.EXTRA_PROJECT_NAME)
                ?: path
            val imported = java.io.File(path, "project.apk")
                .takeIf { it.isFile }
                ?.absolutePath
                ?: path.takeIf { java.io.File(it).isFile }
            startActivity(
                ProjectOptionsActivity.createIntent(this, name, path, imported)
            )
        }

    private fun fragmentApkPath(): String {
        val fragment =
            supportFragmentManager.findFragmentById(R.id.detail_container) as? AppDetailFragment
        return fragment?.apkPathForImport().orEmpty()
    }

    private fun startProjectImport() {
        val fragment =
            supportFragmentManager.findFragmentById(R.id.detail_container) as? AppDetailFragment
                ?: return
        val apkPath = fragment.apkPathForImport()
        if (apkPath.isBlank()) return
        val appName = fragment.appNameForImport()

        // Importing something that is already a project would quietly create a
        // duplicate, so check first and let the user open the existing one.
        val existing = ProjectStore.findByApkPath(applicationContext, apkPath)
            ?: ProjectStore.findByName(applicationContext, appName)
        if (existing == null) {
            launchProjectWizard(apkPath, appName)
            return
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.project_exists_title)
            .setMessage(getString(R.string.project_exists_message, existing.name))
            .setPositiveButton(R.string.project_exists_open) { _, _ ->
                startActivity(
                    ProjectOptionsActivity.createIntent(
                        this,
                        existing.name,
                        existing.path,
                        existing.apkPath
                    )
                )
            }
            .setNeutralButton(R.string.project_exists_create) { _, _ ->
                launchProjectWizard(apkPath, appName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchProjectWizard(apkPath: String, appName: String) {
        projectWizardLauncher.launch(
            ProjectWizardActivity.createIntent(this, apkPath, appName)
        )
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_app_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.detail_container) as? AppDetailFragment
        // Opened from inside a project, the app is already a project, so offering
        // "import as project" would just create a duplicate and a cycle.
        val fromProject = intent.getBooleanExtra(EXTRA_FROM_PROJECT, false)
        if (fragment != null) {
            val hasApkPath = fragment.hasApkPath()
            menu.findItem(R.id.action_save_apk_to)?.isVisible = hasApkPath
            menu.findItem(R.id.action_inspect_manifest)?.isVisible = hasApkPath
            menu.findItem(R.id.action_inspect_activities)?.isVisible = hasApkPath
            menu.findItem(R.id.action_import_as_project)?.isVisible =
                hasApkPath && !fromProject
            // Uninstall only makes sense for a real installed package reached from
            // the app list, not for an APK opened out of a project.
            menu.findItem(R.id.action_uninstall)?.isVisible =
                !fromProject && fragment.isInstalledPackage()
            menu.findItem(R.id.action_analyze)?.isVisible = hasApkPath
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            return onSupportNavigateUp()
        }
        if (item.itemId == R.id.action_import_as_project) {
            // Handled here rather than in the fragment, so the wizard result can be
            // used to open the new project.
            startProjectImport()
            return true
        }
        if (item.itemId == R.id.action_analyze) {
            // The APK analysis entry point now lives in the app details overflow.
            val apk = fragmentApkPath()
            if (apk.isNotBlank()) {
                startActivity(
                    ApkAnalysisActivity.createIntent(
                        this,
                        apk,
                        intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
                    )
                )
            }
            return true
        }
        val fragment = supportFragmentManager.findFragmentById(R.id.detail_container) as? AppDetailFragment
        if (fragment != null && fragment.onMenuAction(item.itemId)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_APK_PATH = "apkPath"
        const val EXTRA_FROM_PROJECT = "fromProject"

        fun start(
            context: Context,
            packageName: String? = null,
            apkPath: String? = null,
            fromProject: Boolean = false
        ) {
            val intent = Intent(context, AppDetailActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_FROM_PROJECT, fromProject)
            }
            context.startActivity(intent)
        }
    }
}
