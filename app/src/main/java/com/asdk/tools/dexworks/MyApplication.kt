package com.asdk.tools.dexworks

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.utilities.DynamicColor

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        applyThemeFromPreferences(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}