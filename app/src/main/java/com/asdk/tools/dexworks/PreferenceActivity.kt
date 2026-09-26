package com.asdk.tools.dexworks

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class PreferenceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference)

        // system padding handling
        val mainLayout = findViewById<android.view.View>(R.id.preference_main_layout)
        enableEdgeToEdgeWithPadding(mainLayout)

        // where is the toolbar?
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        if (savedInstanceState != null) return

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.preference_view_container, PreferenceFragment())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
