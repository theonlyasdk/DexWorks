package com.asdk.tools.dexworks

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
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

    private val selectedProjectPaths = mutableSetOf<String>()
    private val originalSelectedBeforeDrag = mutableSetOf<String>()
    private var isSelectionMode = false
    private var backPressedCallback: OnBackPressedCallback? = null
    private var dragSelectTouchListener: DragSelectTouchListener? = null

    private val projectAdapter = ProjectAdapter(
        onItemClick = { project ->
            if (isSelectionMode) {
                toggleProjectSelection(project)
            } else {
                openProjectDetails(project)
            }
        },
        onItemLongClick = { project, position ->
            if (!isSelectionMode) {
                enterSelectionMode(project)
            } else {
                if (!selectedProjectPaths.contains(project.path)) {
                    selectedProjectPaths.add(project.path)
                    updateSelectionUI()
                }
            }
            originalSelectedBeforeDrag.clear()
            originalSelectedBeforeDrag.addAll(selectedProjectPaths)
            dragSelectTouchListener?.startDragSelection(position)
        },
        onAvatarClick = { project ->
            enterSelectionMode(project)
        },
        onItemMenuClick = { project, anchor ->
            showProjectActionsMenu(project, anchor)
        }
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

        val listener = DragSelectTouchListener(
            onSelectRange = { startPos, endPos ->
                val minPos = minOf(startPos, endPos).coerceAtLeast(0)
                val maxPos = maxOf(startPos, endPos).coerceAtMost(projectAdapter.itemCount - 1)
                selectedProjectPaths.clear()
                selectedProjectPaths.addAll(originalSelectedBeforeDrag)
                for (i in minPos..maxPos) {
                    projectAdapter.currentList.getOrNull(i)?.item?.path?.let { selectedProjectPaths.add(it) }
                }
                updateSelectionUI(duringDrag = true)
            },
            onDragEnded = {
                updateSelectionUI(duringDrag = false)
            }
        )
        listener.attachToRecyclerView(binding.recyclerProjects)
        dragSelectTouchListener = listener

        backPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitSelectionMode()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback!!)

        binding.btnSelectAllContainer.setOnClickListener {
            toggleSelectAll()
        }

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
                exitSelectionMode()
                showEmpty()
            } else {
                _binding?.let {
                    it.layoutLoading.isVisible = false
                    it.layoutEmptyState.isVisible = false
                    it.recyclerProjects.isVisible = true
                    projectAdapter.submitList(rows) {
                        if (_binding != null) {
                            openPendingProject(rows.map { row -> row.item })
                            if (isSelectionMode) {
                                val existingPaths = rows.map { r -> r.item.path }.toSet()
                                selectedProjectPaths.retainAll(existingPaths)
                                if (selectedProjectPaths.isEmpty()) {
                                    exitSelectionMode()
                                } else {
                                    updateSelectionUI()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enterSelectionMode(initialProject: ProjectItem) {
        if (!isSelectionMode) {
            isSelectionMode = true
            selectedProjectPaths.clear()
            selectedProjectPaths.add(initialProject.path)
            backPressedCallback?.isEnabled = true
            (activity as? MainActivity)?.showSelectionBar(
                count = selectedProjectPaths.size,
                onBack = { exitSelectionMode() },
                onDelete = { confirmDeleteSelectedProjects() }
            )
            updateSelectionUI()
        }
    }

    private fun exitSelectionMode() {
        if (isSelectionMode) {
            isSelectionMode = false
            selectedProjectPaths.clear()
            backPressedCallback?.isEnabled = false
            (activity as? MainActivity)?.hideSelectionBar()
            updateSelectionUI()
        }
    }

    private fun toggleProjectSelection(project: ProjectItem) {
        if (selectedProjectPaths.contains(project.path)) {
            selectedProjectPaths.remove(project.path)
        } else {
            selectedProjectPaths.add(project.path)
        }
        if (selectedProjectPaths.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionUI()
        }
    }

    private fun toggleSelectAll() {
        val total = projectAdapter.itemCount
        if (selectedProjectPaths.size == total && total > 0) {
            selectedProjectPaths.clear()
            exitSelectionMode()
        } else {
            selectedProjectPaths.clear()
            for (i in 0 until total) {
                projectAdapter.currentList.getOrNull(i)?.item?.path?.let { selectedProjectPaths.add(it) }
            }
            updateSelectionUI()
        }
    }

    private fun updateSelectionUI(duringDrag: Boolean = false) {
        if (isSelectionMode) {
            binding.fabAddProject.hide()
            if (binding.layoutSelectAllHeader.visibility != View.VISIBLE) {
                binding.layoutSelectAllHeader.animate().cancel()
                binding.layoutSelectAllHeader.alpha = 0f
                binding.layoutSelectAllHeader.translationY = -8f * resources.displayMetrics.density
                binding.layoutSelectAllHeader.visibility = View.VISIBLE
                binding.layoutSelectAllHeader.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
            val count = selectedProjectPaths.size
            val total = projectAdapter.itemCount
            val allSelected = count == total && total > 0
            binding.checkboxSelectAll.isChecked = allSelected
            binding.textSelectAll.text = if (allSelected) {
                getString(R.string.action_deselect_all)
            } else {
                getString(R.string.action_select_all)
            }
            (activity as? MainActivity)?.updateSelectionCount(count)
        } else {
            binding.fabAddProject.show()
            if (binding.layoutSelectAllHeader.visibility == View.VISIBLE) {
                binding.layoutSelectAllHeader.animate().cancel()
                binding.layoutSelectAllHeader.animate()
                    .alpha(0f)
                    .translationY(-8f * resources.displayMetrics.density)
                    .setDuration(150)
                    .withEndAction {
                        binding.layoutSelectAllHeader.visibility = View.GONE
                        binding.layoutSelectAllHeader.translationY = 0f
                    }
                    .start()
            } else {
                binding.layoutSelectAllHeader.visibility = View.GONE
            }
        }
        projectAdapter.updateSelection(selectedProjectPaths, isSelectionMode)
    }

    private fun confirmDeleteSelectedProjects() {
        val count = selectedProjectPaths.size
        if (count == 0) return
        val context = requireContext()
        val pathsToDelete = HashSet(selectedProjectPaths)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.projects_delete_multiple_title)
            .setMessage(getString(R.string.projects_delete_multiple_message, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.project_delete_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val deletedCount = withContext(Dispatchers.IO) {
                        ProjectStore.deleteProjects(context.applicationContext, pathsToDelete)
                    }
                    if (!isAdded || _binding == null) return@launch
                    exitSelectionMode()
                    loadProjects()
                    Snackbar.make(
                        binding.root,
                        getString(R.string.projects_deleted_multiple, deletedCount),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showLoading() {
        _binding?.let {
            it.layoutLoading.isVisible = true
            it.layoutEmptyState.isVisible = false
            it.recyclerProjects.isVisible = false
            it.layoutSelectAllHeader.isVisible = false
        }
    }

    fun showEmpty() {
        _binding?.let {
            it.layoutLoading.isVisible = false
            it.layoutEmptyState.isVisible = true
            it.recyclerProjects.isVisible = false
            it.layoutSelectAllHeader.isVisible = false
        }
    }

    /** Opens a project the wizard has just created, once it appears in the list. */
    private fun openPendingProject(projects: List<ProjectItem>) {
        val path = pendingOpenProjectPath ?: return
        val target = projects.firstOrNull { it.path == path } ?: return
        pendingOpenProjectPath = null
        openProjectDetails(target)
    }

    private fun showProjectActionsMenu(project: ProjectItem, anchor: View) {
        ProjectActions.showMenu(requireContext(), anchor) { actionId ->
            runProjectAction(project, actionId)
        }
    }

    /** Runs a project action, shared by the options overflow. */
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
        if (isSelectionMode) {
            (activity as? MainActivity)?.hideSelectionBar()
        }
        dragSelectTouchListener?.stopDrag()
        dragSelectTouchListener = null
        backPressedCallback?.remove()
        backPressedCallback = null
        loadJob?.cancel()
        loadJob = null
        super.onDestroyView()
        _binding = null
    }

    private class ProjectAdapter(
        private val onItemClick: (ProjectItem) -> Unit,
        private val onItemLongClick: (ProjectItem, Int) -> Unit,
        private val onAvatarClick: (ProjectItem) -> Unit,
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

        private var selectedPaths: Set<String> = emptySet()
        private var isSelectionMode: Boolean = false

        fun updateSelection(selected: Set<String>, selectionMode: Boolean) {
            if (selectedPaths == selected && isSelectionMode == selectionMode) return
            val previous = selectedPaths
            selectedPaths = HashSet(selected)
            isSelectionMode = selectionMode
            if (currentList.isEmpty()) {
                notifyDataSetChanged()
                return
            }
            val flipped = currentList.indices.filter { index ->
                val path = currentList[index].item.path
                (path in previous) != (path in selectedPaths) ||
                    (path in selectedPaths) && isSelectionMode
            }
            if (flipped.isEmpty()) notifyDataSetChanged() else flipped.forEach { notifyItemChanged(it) }
        }

        val iconLoader = ProjectIconLoader()

        class ViewHolder(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root) {
            val iconContainer: View = binding.containerProjectIcon
            val iconView: ImageView = binding.imgProjectIcon
            val checkBadge: ImageView = binding.iconCheckBadge
            val menuButton: ImageButton = binding.btnProjectMenu
            var currentPath: String? = null
            var wasSelected: Boolean? = null
        }

        private var iconTint: ColorStateList? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            if (iconTint == null) {
                iconTint = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.imgProjectIcon, androidx.appcompat.R.attr.colorPrimary)
                )
            }
            return ViewHolder(binding)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.iconView.animate().cancel()
            holder.checkBadge.animate().cancel()
            holder.iconView.rotationY = 0f
            holder.checkBadge.rotationY = 0f
            holder.wasSelected = null
            holder.currentPath = null
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = getItem(position)
            val item = row.item
            val isSelected = selectedPaths.contains(item.path)

            holder.binding.textProjectName.text = item.name
            holder.binding.textProjectSubtitle.text = item.path

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

            val isPathChanged = holder.currentPath != item.path
            holder.currentPath = item.path

            if (isSelected) {
                holder.itemView.setBackgroundResource(R.drawable.bg_item_browse_selected)
            } else {
                holder.itemView.setBackgroundResource(R.drawable.bg_item_browse_unselected)
            }

            val shouldAnimateFlip = !isPathChanged && holder.wasSelected != null && holder.wasSelected != isSelected
            holder.wasSelected = isSelected

            if (shouldAnimateFlip) {
                animateIconFlip(holder, isSelected)
            } else {
                resetIconState(holder, isSelected)
            }

            holder.menuButton.isVisible = !isSelectionMode
            holder.itemView.isActivated = isSelected

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }

            holder.iconContainer.setOnClickListener {
                if (!isSelectionMode) {
                    onAvatarClick(item)
                } else {
                    onItemClick(item)
                }
            }

            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemLongClick(item, pos)
                }
                true
            }

            holder.iconContainer.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemLongClick(item, pos)
                }
                true
            }

            holder.binding.btnProjectMenu.setOnClickListener {
                onItemMenuClick(item, it)
            }
        }

        private fun resetIconState(holder: ViewHolder, isSelected: Boolean) {
            holder.iconView.animate().cancel()
            holder.checkBadge.animate().cancel()

            holder.iconView.rotationY = 0f
            holder.checkBadge.rotationY = 0f

            if (isSelected) {
                holder.iconView.visibility = View.INVISIBLE
                holder.checkBadge.visibility = View.VISIBLE
            } else {
                holder.iconView.visibility = View.VISIBLE
                holder.checkBadge.visibility = View.GONE
            }
        }

        private fun animateIconFlip(holder: ViewHolder, isSelected: Boolean) {
            holder.iconView.animate().cancel()
            holder.checkBadge.animate().cancel()

            val density = holder.itemView.resources.displayMetrics.density
            val distance = 8000f * density
            holder.iconView.cameraDistance = distance
            holder.checkBadge.cameraDistance = distance

            if (isSelected) {
                holder.iconView.visibility = View.VISIBLE
                holder.checkBadge.visibility = View.GONE
                holder.iconView.rotationY = 0f

                holder.iconView.animate()
                    .rotationY(90f)
                    .setDuration(120)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        holder.iconView.visibility = View.INVISIBLE
                        holder.checkBadge.visibility = View.VISIBLE
                        holder.checkBadge.rotationY = -90f
                        holder.checkBadge.animate()
                            .rotationY(0f)
                            .setDuration(120)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            } else {
                holder.checkBadge.visibility = View.VISIBLE
                holder.iconView.visibility = View.INVISIBLE
                holder.checkBadge.rotationY = 0f

                holder.checkBadge.animate()
                    .rotationY(90f)
                    .setDuration(120)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        holder.checkBadge.visibility = View.GONE
                        holder.iconView.visibility = View.VISIBLE
                        holder.iconView.rotationY = -90f
                        holder.iconView.animate()
                            .rotationY(0f)
                            .setDuration(120)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
        }
    }
}
