package com.asdk.tools.dexworks

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

fun applyTheme(themeValue: String?) {
    val mode = when (themeValue) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
    AppCompatDelegate.setDefaultNightMode(mode)
}

fun applyThemeFromPreferences(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val themeValue = prefs.getString("theme", "system")
    applyTheme(themeValue)
}
