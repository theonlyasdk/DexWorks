package com.asdk.tools.dexworks

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

fun applyTheme(themeValue: String?) {
    val mode = when (themeValue) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
    AppCompatDelegate.setDefaultNightMode(mode)
}

fun applyThemeFromPreferences(context: Context) {
    applyTheme(AppPrefs.theme(context))
}
