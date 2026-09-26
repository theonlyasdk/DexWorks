package com.asdk.tools.dexworks

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import com.asdk.tools.dexworks.databinding.SheetProjectActionsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile

/** One action offered for a project, in both the long-press sheet and the overflow menu. */
data class ProjectAction(
    val id: Int,
    val titleRes: Int,
    val iconRes: Int
)

/**
 * Shared project actions, rendered either as a bottom sheet (long press in the
 * projects list) or as an overflow dropdown (3-dot in project options). Both paths
 * read from [actions] so the two can never drift apart.
 */
object ProjectActions {

    const val ACTION_OPEN = 1
    const val ACTION_APP_DETAILS = 2
    const val ACTION_DECOMPILE = 3
    const val ACTION_BROWSE_APK = 4
    const val ACTION_MANIFEST = 5
    const val ACTION_ANALYZE = 6
    const val ACTION_SAVE_APK = 7
    const val ACTION_DELETE = 8

    val actions: List<ProjectAction> = listOf(
        ProjectAction(ACTION_OPEN, R.string.project_action_open, R.drawable.ic_folder_open),
        ProjectAction(ACTION_APP_DETAILS, R.string.project_action_app_details, R.drawable.ic_widgets),
        ProjectAction(ACTION_DECOMPILE, R.string.project_action_decompile, R.drawable.ic_tool_decompile),
        ProjectAction(ACTION_BROWSE_APK, R.string.project_action_browse, R.drawable.ic_file_generic),
        ProjectAction(ACTION_MANIFEST, R.string.project_action_manifest, R.drawable.ic_tool_manifest),
        ProjectAction(ACTION_ANALYZE, R.string.project_action_analyze, R.drawable.ic_analytics),
        ProjectAction(ACTION_SAVE_APK, R.string.project_action_save_apk, R.drawable.ic_save_to),
        ProjectAction(ACTION_DELETE, R.string.project_action_delete, R.drawable.ic_delete)
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun titleFor(context: Context, id: Int): String = context.getString(
        actions.firstOrNull { it.id == id }?.titleRes ?: R.string.not_yet_implemented
    )

    /** Bottom-sheet variant, used on long press in the projects list. */
    fun showSheet(
        context: Context,
        anchor: View,
        projectName: String,
        onAction: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = SheetProjectActionsBinding.inflate(LayoutInflater.from(context))
        binding.textSheetProjectName.text = projectName

        val container = binding.containerProjectActions
        container.removeAllViews()
        actions.forEachIndexed { index, action ->
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_project_action, container, false)
            row.findViewById<android.widget.ImageView>(R.id.image_project_action_icon)
                .setImageResource(action.iconRes)
            row.findViewById<TextView>(R.id.text_project_action_title)
                .setText(action.titleRes)
            val backgroundRes = when {
                actions.size == 1 -> R.drawable.bg_sheet_action_segment_single
                index == 0 -> R.drawable.bg_sheet_action_segment_top
                index == actions.lastIndex -> R.drawable.bg_sheet_action_segment_bottom
                else -> R.drawable.bg_sheet_action_segment_middle
            }
            row.setBackgroundResource(backgroundRes)
            row.setOnClickListener {
                dialog.dismiss()
                onAction(action.id)
            }
            container.addView(row)
        }

        dialog.setContentView(binding.root)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            bottomSheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
    }

    /** Overflow variant, used from the 3-dot in project options. */
    fun showMenu(context: Context, anchor: View, onAction: (Int) -> Unit) {
        val popup = PopupMenu(context, anchor)
        actions.forEach { action ->
            popup.menu.add(0, action.id, action.id, action.titleRes)
        }
        popup.setOnMenuItemClickListener { item ->
            onAction(item.itemId)
            true
        }
        popup.show()
    }

    fun notImplemented(context: Context, root: View) {
        Snackbar.make(root, R.string.not_yet_implemented, Snackbar.LENGTH_SHORT).show()
    }

    fun startDecompile(context: Context, apkPath: String, projectName: String) {
        if (!java.io.File(apkPath).isFile) {
            showDecompileError(context, R.string.decompile_error_missing_apk)
            return
        }

        // Listing every zip entry of a large APK can take hundreds of
        // milliseconds, so it runs on IO behind a progress dialog instead of
        // freezing the tap that opened it.
        val progress = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.decompile_choose_dex_title)
            .setMessage(R.string.decompile_scanning_dex)
            .setCancelable(false)
            .create()
        progress.show()

        ioScope.launch {
            val dexEntries: List<String>? = try {
                ZipFile(java.io.File(apkPath)).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.endsWith(".dex", ignoreCase = true) }
                        .sorted()
                        .toList()
                }
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                runCatching { progress.dismiss() }
                when {
                    dexEntries == null -> showDecompileError(
                        context,
                        R.string.decompile_error_invalid_apk
                    )
                    dexEntries.isEmpty() -> showDecompileError(
                        context,
                        R.string.decompile_error_no_dex
                    )
                    dexEntries.size == 1 -> runCatching {
                        openDecompileOrBrowser(context, apkPath, dexEntries.first(), projectName)
                    }
                    else -> runCatching {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.decompile_choose_dex_title)
                            .setItems(dexEntries.toTypedArray()) { _, which ->
                                runCatching {
                                    openDecompileOrBrowser(context, apkPath, dexEntries[which], projectName)
                                }
                            }
                            .setNegativeButton(R.string.dialog_decompile_cancel, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun openDecompileOrBrowser(context: Context, apkPath: String, dexEntry: String, projectName: String) {
        val decompiledDir = DecompileActivity.getDecompiledDir(context, apkPath, dexEntry)
        if (DecompileActivity.hasDecompiledOutput(decompiledDir)) {
            val browserTitle = "$projectName - ${java.io.File(dexEntry).name}"
            val intent = ApkBrowseActivity.createIntent(
                context,
                decompiledDir.absolutePath,
                browserTitle
            ).apply {
                putExtra(ApkBrowseActivity.EXTRA_SOURCE_APK_PATH, apkPath)
                putExtra(ApkBrowseActivity.EXTRA_SOURCE_DEX_ENTRY, dexEntry)
            }
            context.startActivity(intent)
        } else {
            context.startActivity(
                DecompileActivity.createIntent(context, apkPath, dexEntry, projectName)
            )
        }
    }

    private fun showDecompileError(context: Context, messageRes: Int) {
        runCatching {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.decompile_error_title)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
