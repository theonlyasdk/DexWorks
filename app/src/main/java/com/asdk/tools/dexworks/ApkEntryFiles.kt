package com.asdk.tools.dexworks

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

internal object ApkEntryFiles {
    fun materialize(context: Context, apkPath: String, entryPath: String): File? {
        return try {
            val dir = File(context.cacheDir, "shared_apk_entries")
            if (!dir.exists() && !dir.mkdirs()) return null
            dir.listFiles()?.forEach { it.delete() }
            val safeName = entryPath.substringAfterLast('/')
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (safeName.isBlank()) return null
            val target = File(dir, safeName)
            ZipFile(File(apkPath)).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    fun uriFor(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }
}
