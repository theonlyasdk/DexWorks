package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent

/**
 * Chooses the viewer for an APK entry, so analysis evidence opens in the same
 * viewer the APK browser would use for that file.
 *
 * Routing is by extension only, which keeps it synchronous and cheap enough to run
 * from a list row.
 */
object ApkEntryViewerRouter {

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico", "svg")

    fun intentFor(context: Context, apkPath: String, entryPath: String): Intent {
        val extension = entryPath.substringAfterLast('.', "").lowercase()
        return when {
            extension in imageExtensions ->
                ApkImageViewerActivity.createIntent(context, apkPath, entryPath)
            extension in ApkEntryReader.textExtensions ->
                ApkXmlViewerActivity.createIntent(context, apkPath, entryPath)
            else -> ApkHexViewerActivity.createIntent(context, apkPath, entryPath)
        }
    }

    fun iconFor(entryPath: String): Int {
        val extension = entryPath.substringAfterLast('.', "").lowercase()
        return when {
            extension in imageExtensions -> R.drawable.ic_file_image
            extension in ApkEntryReader.textExtensions -> R.drawable.ic_file_code
            else -> R.drawable.ic_file_generic
        }
    }
}
