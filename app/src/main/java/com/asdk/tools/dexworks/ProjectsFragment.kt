package com.asdk.tools.dexworks

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.FragmentProjectsBinding
import com.asdk.tools.dexworks.databinding.ItemProjectBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectItem(
    val name: String,
    val path: String,
    val lastModified: Long,
    val apkPath: String? = null,
    val iconKey: String = ProjectIconCatalog.DEFAULT_KEY,
    val useApkIcon: Boolean = false
)

/** The APK a project's icon and details come from, if any. */
private fun resolveApkFor(project: ProjectItem): String? {
    return project.apkPath?.takeIf { File(it).isFile }
        ?: File(project.path, "project.apk").takeIf { it.isFile }?.absolutePath
        ?: project.path.takeIf { File(it).isFile && it.endsWith(".apk", ignoreCase = true) }
}

/** A project row with its APK path and date text already resolved on a background thread. */
private data class ProjectRow(
    val item: ProjectItem,
    val apkPath: String?,
    val dateText: String
)

/** Relative-time text, formatted on IO during load so binding does no work. */
private fun formatProjectDate(project: ProjectItem): String {
    return if (project.lastModified > 0) {
        DateUtils.getRelativeTimeSpanString(
            project.lastModified,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    } else {
        AppInfoUtils.formatDate(project.lastModified)
    }
}

class ProjectsFragment : Fragment() {

    private var _binding: FragmentProjectsBinding? = null
    private val binding get() = _binding!!

    private val projectAdapter = ProjectAdapter(
        onItemClick = { project -> openProjectDetails(project) },
        onItemLongClick = { project, anchor -> showProjectActionsSheet(project, anchor) },
        onItemMenuClick = { project, anchor -> showProjectActionsMenu(project, anchor) }
    )

    private var pendingOpenProjectPath: String? = null

    private val wizardLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            pendingOpenProjectPath =
                result.data?.getStringExtra(ProjectWizardActivity.EXTRA_PROJECT_PATH)
            loadProjects()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProjectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerProjects.adapter = projectAdapter

        binding.btnOpenFile.setOnClickListener {
            Snackbar.make(binding.root, R.string.not_yet_implemented, Snackbar.LENGTH_SHORT).show()
        }

        binding.fabAddProject.setOnClickListener {
            wizardLauncher.launch(Intent(requireContext(), ProjectWizardActivity::class.java))
        }

        loadProjects()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            loadProjects()
        }
    }

    private var loadJob: Job? = null

    private fun loadProjects() {
        // Nothing here may touch the disk or diff on the main thread. IO re-reads
        // and re-resolves in the background while any already-rendered list stays
        // on screen, and ListAdapter diffs off the main thread too. The spinner is
        // only for the case where there is nothing to show yet, since flashing it
        // over a populated list is what read as jank.
        val hasContent = projectAdapter.itemCount > 0
        if (!hasContent) {
            showLoading()
        }

        val appContext = requireContext().applicationContext
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                ProjectStore.loadProjects(appContext)
                    .sortedByDescending { it.lastModified }
                    .map { project ->
                        ProjectRow(project, resolveApkFor(project), formatProjectDate(project))
                    }
            }
            if (_binding == null) return@launch
            if (rows.isEmpty()) {
                showEmpty()
            } else {
                _binding?.let {
                    it.layoutLoading.isVisible = false
                    it.layoutEmptyState.isVisible = false
                    it.recyclerProjects.isVisible = true
                    projectAdapter.submitList(rows) {
                        if (_binding != null) openPendingProject(rows.map { row -> row.item })
                    }
                }
            }
        }
    }

    private fun showLoading() {
        _binding?.let {
            it.layoutLoading.isVisible = true
            it.layoutEmptyState.isVisible = false
            it.recyclerProjects.isVisible = false
        }
    }

    fun showEmpty() {
        _binding?.let {
            it.layoutLoading.isVisible = false
            it.layoutEmptyState.isVisible = true
            it.recyclerProjects.isVisible = false
        }
    }

    /** Opens a project the wizard has just created, once it appears in the list. */
    private fun openPendingProject(projects: List<ProjectItem>) {
        val path = pendingOpenProjectPath ?: return
        val target = projects.firstOrNull { it.path == path } ?: return
        pendingOpenProjectPath = null
        openProjectDetails(target)
    }

    private fun showProjectActionsSheet(project: ProjectItem, anchor: View) {
        ProjectActions.showSheet(requireContext(), anchor, project.name) { actionId ->
            runProjectAction(project, actionId)
        }
    }

    private fun showProjectActionsMenu(project: ProjectItem, anchor: View) {
        ProjectActions.showMenu(requireContext(), anchor) { actionId ->
            runProjectAction(project, actionId)
        }
    }

    /** Runs a project action, shared by the long-press sheet and the options overflow. */
    fun runProjectAction(project: ProjectItem, actionId: Int) {
        val apk = resolveApkFor(project)
        val root = _binding?.root ?: return
        val hasApk = !apk.isNullOrBlank() && File(apk).isFile
        when (actionId) {
            ProjectActions.ACTION_OPEN -> openProjectDetails(project)
            ProjectActions.ACTION_APP_DETAILS -> if (hasApk) {
                AppDetailActivity.start(requireContext(), apkPath = apk, fromProject = true)
            } else {
                ProjectActions.notImplemented(requireContext(), root)
            }
            ProjectActions.ACTION_DECOMPILE -> if (hasApk) {
                ProjectActions.startDecompile(requireContext(), apk, project.name)
            } else {
                ProjectActions.notImplemented(requireContext(), root)
            }
            ProjectActions.ACTION_BROWSE_APK -> if (hasApk) {
                startActivity(ApkBrowseActivity.createIntent(requireContext(), apk, project.name))
            } else {
                ProjectActions.notImplemented(requireContext(), root)
            }
            ProjectActions.ACTION_MANIFEST -> if (hasApk) {
                startActivity(
                    ManifestInspectorActivity.createIntent(
                        requireContext(),
                        apk,
                        project.name,
                        ""
                    )
                )
            } else {
                ProjectActions.notImplemented(requireContext(), root)
            }
            ProjectActions.ACTION_ANALYZE -> if (hasApk) {
                startActivity(
                    ApkAnalysisActivity.createIntent(requireContext(), apk, project.name)
                )
            } else {
                ProjectActions.notImplemented(requireContext(), root)
            }
            ProjectActions.ACTION_SAVE_APK -> if (hasApk) {
                FileSaveHelper.from(this).saveFile(File(apk), "project.apk", forcePickLocation = true)
            } else {
                ProjectActions.notImplemented(requireContext(), root)
            }
            ProjectActions.ACTION_DELETE -> confirmDeleteProject(project)
        }
    }

    private fun confirmDeleteProject(project: ProjectItem) {
        val context = requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.project_delete_title)
            .setMessage(getString(R.string.project_delete_message, project.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.project_delete_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        ProjectStore.deleteProject(context.applicationContext, project.path)
                    }
                    if (!isAdded || _binding == null) return@launch
                    if (deleted) {
                        loadProjects()
                        Snackbar.make(
                            binding.root,
                            getString(R.string.project_deleted, project.name),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } else {
                        Snackbar.make(
                            binding.root,
                            R.string.project_delete_failed,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun openProjectDetails(project: ProjectItem) {
        val apk = resolveApkFor(project)
        startActivity(
            ProjectOptionsActivity.createIntent(
                requireContext(),
                project.name,
                project.path,
                apk
            )
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        super.onDestroyView()
        _binding = null
    }

    private class ProjectAdapter(
        private val onItemClick: (ProjectItem) -> Unit,
        private val onItemLongClick: (ProjectItem, View) -> Unit,
        private val onItemMenuClick: (ProjectItem, View) -> Unit
    ) : ListAdapter<ProjectRow, ProjectAdapter.ViewHolder>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ProjectRow>() {
                override fun areItemsTheSame(oldRow: ProjectRow, newRow: ProjectRow): Boolean =
                    oldRow.item.path == newRow.item.path

                override fun areContentsTheSame(oldRow: ProjectRow, newRow: ProjectRow): Boolean =
                    oldRow == newRow
            }
        }

        val iconLoader = ProjectIconLoader()

        class ViewHolder(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)

        private var iconTint: ColorStateList? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // Resolved once instead of allocating a ColorStateList and doing a theme
            // lookup on every single bind.
            if (iconTint == null) {
                iconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.imgProjectIcon, androidx.appcompat.R.attr.colorPrimary)
                )
            }
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = getItem(position)
            val item = row.item
            holder.binding.textProjectName.text = item.name
            holder.binding.textProjectSubtitle.text = item.path
            // The APK path was resolved on IO during load, so binding performs no
            // file stats here. Icon decoding itself already runs on IO.
            if (item.useApkIcon) {
                iconLoader.load(
                    row.apkPath,
                    holder.binding.imgProjectIcon,
                    ProjectIconCatalog.iconRes(item.iconKey),
                    iconTint
                )
            } else {
                holder.binding.imgProjectIcon.imageTintList = iconTint
                holder.binding.imgProjectIcon.setImageResource(
                    ProjectIconCatalog.iconRes(item.iconKey)
                )
            }

            holder.binding.textProjectDate.text = row.dateText

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
            holder.binding.btnProjectMenu.setOnClickListener {
                onItemMenuClick(item, it)
            }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item, holder.itemView)
                true
            }
        }
    }
}
