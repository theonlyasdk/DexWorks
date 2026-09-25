package com.asdk.tools.dexworks

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileSaveHelper(
    private val contextProvider: () -> Context,
    private val lifecycleOwnerProvider: () -> LifecycleOwner,
    private val rootViewProvider: () -> View?,
    caller: ActivityResultCaller
) {
    companion object {
        const val PREF_REMEMBERED_SAVE_LOCATION = "remembered_apk_save_location"

        fun from(fragment: Fragment): FileSaveHelper {
            return FileSaveHelper(
                contextProvider = { fragment.requireContext() },
                lifecycleOwnerProvider = { fragment.viewLifecycleOwner },
                rootViewProvider = { fragment.view },
                caller = fragment
            )
        }

        fun from(activity: AppCompatActivity): FileSaveHelper {
            return FileSaveHelper(
                contextProvider = { activity },
                lifecycleOwnerProvider = { activity },
                rootViewProvider = { activity.findViewById(android.R.id.content) },
                caller = activity
            )
        }
    }

    private var pendingSaveAction: ((Uri) -> Unit)? = null
    private var pendingFolderPick: Boolean = false

    private val selectFolderLauncher: ActivityResultLauncher<Uri?> = caller.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val action = pendingSaveAction
            pendingSaveAction = null
            if (action != null) {
                action(uri)
                promptRememberLocation(uri)
            } else if (pendingFolderPick) {
                pendingFolderPick = false
                rememberLocation(uri)
            }
        }
    }

    /**
     * Lets the user pick a folder to remember for future saves without saving
     * anything. Must be called from the registered caller's lifecycle, because
     * the underlying launcher is registered at construction time.
     */
    fun pickRememberedFolder() {
        pendingFolderPick = true
        selectFolderLauncher.launch(null)
    }

    fun getRememberedLocation(): Uri? {
        val uriStr = getPreferences().getString(PREF_REMEMBERED_SAVE_LOCATION, null)
        return if (!uriStr.isNullOrBlank()) Uri.parse(uriStr) else null
    }

    fun forgetRememberedLocation() {
        getPreferences().edit().remove(PREF_REMEMBERED_SAVE_LOCATION).apply()
    }

    fun saveApk(app: AppItem, forcePickLocation: Boolean = false) {
        val sourceFile = File(app.sourceDir)
        val sanitizedAppName = app.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "${sanitizedAppName}_${app.versionName}.apk"
        saveFile(sourceFile, fileName, forcePickLocation)
    }

    fun saveApk(pkg: PackageInfo, actualApkPath: String, forcePickLocation: Boolean = false) {
        val sourceFile = File(actualApkPath)
        val context = contextProvider()
        val appName = pkg.applicationInfo?.loadLabel(context.packageManager)?.toString()?.ifBlank { pkg.packageName } ?: pkg.packageName
        val versionName = pkg.versionName ?: "1.0"
        val sanitizedAppName = appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "${sanitizedAppName}_${versionName}.apk"
        saveFile(sourceFile, fileName, forcePickLocation)
    }

    fun saveZipOfApks(apps: List<AppItem>, forcePickLocation: Boolean = false) {
        val view = rootViewProvider()
        if (apps.isEmpty()) return

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val defaultZipName = if (apps.size == 1) {
            val sanitized = apps.first().name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            "${sanitized}_${apps.first().versionName}.zip"
        } else {
            "DexWorks_APKs_${timestamp}.zip"
        }

        val rememberedUri = if (!forcePickLocation) getRememberedLocation() else null
        if (rememberedUri != null) {
            saveZipToUri(apps, defaultZipName, rememberedUri)
        } else {
            pendingSaveAction = { uri ->
                saveZipToUri(apps, defaultZipName, uri)
            }
            selectFolderLauncher.launch(null)
        }
    }

    fun saveFile(sourceFile: File, defaultFileName: String, forcePickLocation: Boolean = false) {
        val view = rootViewProvider()
        if (!sourceFile.exists()) {
            view?.let {
                Snackbar.make(it, R.string.toast_apk_save_failed, Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        val rememberedUri = if (!forcePickLocation) getRememberedLocation() else null
        if (rememberedUri != null) {
            saveFileToUri(sourceFile, defaultFileName, rememberedUri)
        } else {
            pendingSaveAction = { uri ->
                saveFileToUri(sourceFile, defaultFileName, uri)
            }
            selectFolderLauncher.launch(null)
        }
    }

    fun saveIcon(drawable: Drawable, appName: String, forcePickLocation: Boolean = false) {
        val sanitizedAppName = appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "${sanitizedAppName}_icon.png"

        val rememberedUri = if (!forcePickLocation) getRememberedLocation() else null
        if (rememberedUri != null) {
            saveDrawableToUri(drawable, fileName, rememberedUri)
        } else {
            pendingSaveAction = { uri ->
                saveDrawableToUri(drawable, fileName, uri)
            }
            selectFolderLauncher.launch(null)
        }
    }

    fun saveText(
        text: String,
        fileName: String,
        mimeType: String = "text/xml",
        successMessageRes: Int = R.string.toast_manifest_saved,
        failureMessageRes: Int = R.string.toast_manifest_save_failed,
        forcePickLocation: Boolean = false
    ) {
        val rememberedUri = if (!forcePickLocation) getRememberedLocation() else null
        if (rememberedUri != null) {
            saveTextToUri(text, fileName, mimeType, rememberedUri, successMessageRes, failureMessageRes)
        } else {
            pendingSaveAction = { uri ->
                saveTextToUri(text, fileName, mimeType, uri, successMessageRes, failureMessageRes)
            }
            selectFolderLauncher.launch(null)
        }
    }

    private fun saveFileToUri(sourceFile: File, fileName: String, treeUri: Uri) {
        val context = contextProvider()
        val lifecycleOwner = lifecycleOwnerProvider()

        lifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val docUri = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                        treeUri
                    } else {
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri)
                        )
                    }
                    val targetUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        docUri,
                        "application/vnd.android.package-archive",
                        fileName
                    ) ?: return@withContext false

                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

            rootViewProvider()?.let { view ->
                val messageRes = if (success) R.string.toast_apk_saved else R.string.toast_apk_save_failed
                Snackbar.make(view, messageRes, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveDrawableToUri(drawable: Drawable, fileName: String, treeUri: Uri) {
        val context = contextProvider()
        val lifecycleOwner = lifecycleOwnerProvider()

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
        val bitmap = drawable.toBitmap(width = width, height = height)

        lifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val docUri = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                        treeUri
                    } else {
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri)
                        )
                    }
                    val targetUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        docUri,
                        "image/png",
                        fileName
                    ) ?: return@withContext false

                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

            rootViewProvider()?.let { view ->
                val messageRes = if (success) R.string.toast_icon_saved else R.string.toast_icon_save_failed
                Snackbar.make(view, messageRes, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveTextToUri(
        text: String,
        fileName: String,
        mimeType: String,
        treeUri: Uri,
        successMessageRes: Int,
        failureMessageRes: Int
    ) {
        val context = contextProvider()
        val lifecycleOwner = lifecycleOwnerProvider()

        lifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    val docUri = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                        treeUri
                    } else {
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri)
                        )
                    }
                    val targetUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        docUri,
                        mimeType,
                        fileName
                    ) ?: return@withContext false

                    context.contentResolver.openOutputStream(targetUri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

            rootViewProvider()?.let { view ->
                val messageRes = if (success) successMessageRes else failureMessageRes
                Snackbar.make(view, messageRes, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveZipToUri(apps: List<AppItem>, zipFileName: String, treeUri: Uri) {
        val lifecycleOwner = lifecycleOwnerProvider()
        val context = contextProvider()

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_zip_progress, null)
        val textStatus = dialogView.findViewById<TextView>(R.id.text_progress_status)
        val progressIndicator = dialogView.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progress_indicator)

        progressIndicator.max = 1000
        progressIndicator.progress = 0
        textStatus.text = context.getString(
            R.string.dialog_compressing_progress,
            1,
            apps.size,
            apps.firstOrNull()?.name ?: ""
        )

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()
        WorkNotifications.notifyZipProgress(context, zipFileName, 0)

        lifecycleOwner.lifecycleScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    try {
                        val docUri = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                            treeUri
                        } else {
                            DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                DocumentsContract.getTreeDocumentId(treeUri)
                            )
                        }
                        val targetUri = DocumentsContract.createDocument(
                            context.contentResolver,
                            docUri,
                            "application/zip",
                            zipFileName
                        ) ?: return@withContext false

                        context.contentResolver.openOutputStream(targetUri)?.use { outStream ->
                            ZipOutputStream(BufferedOutputStream(outStream)).use { zipOut ->
                                val buffer = ByteArray(64 * 1024)
                                val addedNames = mutableSetOf<String>()
                                val fileSizes = apps.map { app ->
                                    val f = File(app.sourceDir)
                                    if (f.exists()) f.length() else 0L
                                }
                                val totalBytes = fileSizes.sum().coerceAtLeast(1L)
                                var completedBytes = 0L
                                var lastNotified = -1

                                for ((index, app) in apps.withIndex()) {
                                    withContext(Dispatchers.Main) {
                                        textStatus.text = context.getString(
                                            R.string.dialog_compressing_progress,
                                            index + 1,
                                            apps.size,
                                            app.name
                                        )
                                    }

                                    val sourceFile = File(app.sourceDir)
                                    if (!sourceFile.exists()) {
                                        completedBytes += fileSizes[index]
                                        continue
                                    }

                                    val sanitized = app.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                    var entryName = "${sanitized}_${app.versionName}.apk"
                                    var nameIndex = 1
                                    while (addedNames.contains(entryName)) {
                                        entryName = "${sanitized}_${app.versionName}_$nameIndex.apk"
                                        nameIndex++
                                    }
                                    addedNames.add(entryName)

                                    val entry = ZipEntry(entryName).apply {
                                        time = sourceFile.lastModified()
                                    }
                                    zipOut.putNextEntry(entry)
                                    var writtenBytes = 0L
                                    var lastPosted = ((completedBytes * 1000) / totalBytes).toInt()
                                    sourceFile.inputStream().buffered().use { input ->
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            zipOut.write(buffer, 0, read)
                                            writtenBytes += read
                                            val fraction = (((completedBytes + writtenBytes) * 1000) / totalBytes).toInt().coerceIn(0, 1000)
                                            if (fraction != lastPosted) {
                                                lastPosted = fraction
                                                withContext(Dispatchers.Main) {
                                                    progressIndicator.setProgressCompat(fraction, true)
                                                    if (fraction - lastNotified >= 20 || fraction >= 1000) {
                                                        lastNotified = fraction
                                                        WorkNotifications.notifyZipProgress(context, zipFileName, fraction)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    zipOut.closeEntry()
                                    completedBytes += fileSizes[index]

                                    withContext(Dispatchers.Main) {
                                        progressIndicator.setProgressCompat(
                                            ((completedBytes * 1000) / totalBytes).toInt().coerceIn(0, 1000),
                                            true
                                        )
                                    }
                                }
                            }
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    try {
                        dialog.dismiss()
                    } catch (e: Exception) {}
                }
            }

            rootViewProvider()?.let { v ->
                val msg = if (success) R.string.toast_zip_saved else R.string.toast_apk_save_failed
                Snackbar.make(v, msg, Snackbar.LENGTH_LONG).show()
            }
            WorkNotifications.notifyZipDone(context, success, zipFileName)
            WorkNotifications.maybeShowDisabledHint(context)
        }
    }

    private fun promptRememberLocation(treeUri: Uri) {
        val context = contextProvider()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_remember_location_title)
            .setMessage(R.string.dialog_remember_location_message)
            .setPositiveButton(R.string.action_remember) { _, _ ->
                rememberLocation(treeUri)
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun rememberLocation(treeUri: Uri) {
        val context = contextProvider()
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: Exception) {
            // Ignore if cannot take persistable permission
        }
        getPreferences().edit()
            .putString(PREF_REMEMBERED_SAVE_LOCATION, treeUri.toString())
            .apply()
    }

    private fun getPreferences(): SharedPreferences {
        return PreferenceManager.getDefaultSharedPreferences(contextProvider())
    }
}
