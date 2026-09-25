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

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_app_detail, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.detail_container) as? AppDetailFragment
        if (fragment != null) {
            val hasApkPath = fragment.hasApkPath()
            menu.findItem(R.id.action_save_apk_to)?.isVisible = hasApkPath
            menu.findItem(R.id.action_inspect_manifest)?.isVisible = hasApkPath
            menu.findItem(R.id.action_inspect_activities)?.isVisible = hasApkPath
            menu.findItem(R.id.action_import_as_project)?.isVisible = hasApkPath
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            return onSupportNavigateUp()
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

        fun start(context: Context, packageName: String? = null, apkPath: String? = null) {
            val intent = Intent(context, AppDetailActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APK_PATH, apkPath)
            }
            context.startActivity(intent)
        }
    }
}
