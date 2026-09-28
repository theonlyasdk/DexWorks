package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.asdk.tools.dexworks.DeviceSummaryActivity
import com.google.android.material.appbar.MaterialToolbar

class AppDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)

        val mainLayout = findViewById<android.view.View>(R.id.app_detail_main_layout)
        enableEdgeToEdgeWithPadding(mainLayout)

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

    fun startProjectImport() {
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
        if (fragment != null) {
            val hasApkPath = fragment.hasApkPath()
            menu.findItem(R.id.action_inspect_manifest)?.isVisible = hasApkPath
            menu.findItem(R.id.action_inspect_activities)?.isVisible = hasApkPath
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            return onSupportNavigateUp()
        }
        if (item.itemId == R.id.action_export_device_summary) {
            startActivity(DeviceSummaryActivity.createIntent(this))
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
