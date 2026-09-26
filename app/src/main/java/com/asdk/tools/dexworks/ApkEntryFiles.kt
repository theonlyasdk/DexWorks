package com.asdk.tools.dexworks

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipFile

internal object ApkEntryFiles {
    fun materialize(context: Context, apkPath: String, entryPath: String): File? {
        return try {
            val root = File(apkPath)
            if (root.isDirectory) {
                val file = File(root, entryPath)
                return if (file.exists() && file.isFile) file else null
            }
            val dir = sharedDir(context) ?: return null
            val safeName = entryPath.substringAfterLast('/')
                .replace(SanitizedNames.UNSAFE_CHARS, "_")
            if (safeName.isBlank()) return null
            val target = File(dir, safeName)
            // Only replace this entry's own file. Wiping the whole directory meant
            // that a share immediately after a save forced a full re-extract, and
            // invalidated a FileProvider URI that had just been handed out.
            @Suppress("ResultOfMethodCallIgnored")
            target.delete()
            ZipFile(root).use { zip ->
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

    @Volatile
    private var cachedDir: File? = null

    private fun sharedDir(context: Context): File? {
        cachedDir?.let { if (it.isDirectory) return it }
        return try {
            val dir = File(context.cacheDir, "shared_apk_entries")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            cachedDir = dir
            dir
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
