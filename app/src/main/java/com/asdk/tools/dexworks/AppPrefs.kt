package com.asdk.tools.dexworks

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Single source of truth for preference keys and their defaults.
 *
 * These used to be scattered string literals across the app, which is how
 * `show_system_apps` ended up being read (and listened for) while nothing ever
 * wrote it, and how eleven settings shipped with no implementation at all.
 */
object AppPrefs {

    const val KEY_THEME = "theme"
    const val KEY_DYNAMIC_COLOR = "dynamic_color"
    const val KEY_CODE_TEXT_SIZE = "code_size"
    const val KEY_CODE_LINE_NUMBERS = "line_numbers"
    const val KEY_CODE_WORD_WRAP = "word_wrap"
    const val KEY_APP_SORT_TYPE = "app_sort_type"
    const val KEY_APP_SORT_ASCENDING = "app_sort_ascending"
    const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
    const val KEY_APK_FOLDERS_FIRST = "apk_folders_first"
    const val KEY_HEX_PAGE_SIZE = "hex_page_size"
    const val KEY_IMAGE_MAX_SIZE = "image_max_size"
    const val KEY_NOTIFICATIONS = "notifications"

    const val DEFAULT_THEME = "system"
    const val DEFAULT_DYNAMIC_COLOR = true
    const val DEFAULT_CODE_TEXT_SIZE = "normal"
    const val DEFAULT_CODE_LINE_NUMBERS = true
    const val DEFAULT_CODE_WORD_WRAP = false
    const val DEFAULT_APP_SORT_TYPE = "name"
    const val DEFAULT_APP_SORT_ASCENDING = true
    const val DEFAULT_SHOW_SYSTEM_APPS = false
    const val DEFAULT_APK_FOLDERS_FIRST = true
    const val DEFAULT_HEX_PAGE_SIZE = "4"
    const val DEFAULT_IMAGE_MAX_SIZE = "2048"
    const val DEFAULT_NOTIFICATIONS = true

    fun get(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun theme(context: Context): String =
        get(context).getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME

    fun dynamicColor(context: Context): Boolean =
        get(context).getBoolean(KEY_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR)

    fun codeTextSize(context: Context): String =
        get(context).getString(KEY_CODE_TEXT_SIZE, DEFAULT_CODE_TEXT_SIZE) ?: DEFAULT_CODE_TEXT_SIZE

    fun codeLineNumbers(context: Context): Boolean =
        get(context).getBoolean(KEY_CODE_LINE_NUMBERS, DEFAULT_CODE_LINE_NUMBERS)

    fun codeWordWrap(context: Context): Boolean =
        get(context).getBoolean(KEY_CODE_WORD_WRAP, DEFAULT_CODE_WORD_WRAP)

    fun appSortType(context: Context): String =
        get(context).getString(KEY_APP_SORT_TYPE, DEFAULT_APP_SORT_TYPE) ?: DEFAULT_APP_SORT_TYPE

    fun appSortAscending(context: Context): Boolean =
        get(context).getBoolean(KEY_APP_SORT_ASCENDING, DEFAULT_APP_SORT_ASCENDING)

    fun showSystemApps(context: Context): Boolean =
        get(context).getBoolean(KEY_SHOW_SYSTEM_APPS, DEFAULT_SHOW_SYSTEM_APPS)

    fun apkFoldersFirst(context: Context): Boolean =
        get(context).getBoolean(KEY_APK_FOLDERS_FIRST, DEFAULT_APK_FOLDERS_FIRST)

    fun notifications(context: Context): Boolean =
        get(context).getBoolean(KEY_NOTIFICATIONS, DEFAULT_NOTIFICATIONS)

    /** Editor text size in sp for the current preference. */
    fun codeTextSizeSp(context: Context): Float = when (codeTextSize(context)) {
        "small" -> 11f
        "large" -> 16f
        else -> 13f
    }

    /** Hex viewer page size in bytes for the current preference. */
    fun hexPageSizeBytes(context: Context): Long = when (hexPageSizeKey(context)) {
        "1" -> 1024L * 1024L
        "8" -> 8L * 1024L * 1024L
        else -> 4L * 1024L * 1024L
    }

    /** Longest edge allowed when decoding an image for the viewer. */
    fun imageMaxSize(context: Context): Int = when (imageMaxSizeKey(context)) {
        "1024" -> 1024
        "4096" -> 4096
        else -> 2048
    }

    private fun hexPageSizeKey(context: Context): String =
        get(context).getString(KEY_HEX_PAGE_SIZE, DEFAULT_HEX_PAGE_SIZE) ?: DEFAULT_HEX_PAGE_SIZE

    private fun imageMaxSizeKey(context: Context): String =
        get(context).getString(KEY_IMAGE_MAX_SIZE, DEFAULT_IMAGE_MAX_SIZE) ?: DEFAULT_IMAGE_MAX_SIZE
}
