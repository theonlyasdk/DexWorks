package com.asdk.tools.dexworks

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.FragmentProjectsBinding
import com.asdk.tools.dexworks.databinding.ItemProjectBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
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

class ProjectsFragment : Fragment() {

    private var _binding: FragmentProjectsBinding? = null
    private val binding get() = _binding!!

    private val projectAdapter = ProjectAdapter { project ->
        openProjectDetails(project)
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
            startActivity(Intent(requireContext(), ProjectWizardActivity::class.java))
        }

        loadProjects()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            loadProjects()
        }
    }

    private fun loadProjects() {
        val cached = ProjectStore.getCachedProjects()
        if (cached != null) {
            showProjects(cached)
        } else {
            binding.layoutLoading.isVisible = true
            binding.layoutEmptyState.isVisible = false
            binding.recyclerProjects.isVisible = false
        }

        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val (projects, icons) = withContext(Dispatchers.IO) {
                val list = ProjectStore.loadProjects(appContext, forceReload = true)
                val iconMap = mutableMapOf<String, Drawable>()
                list.forEach { project ->
                    val apkPath = project.apkPath
                    if (project.useApkIcon && !apkPath.isNullOrBlank()) {
                        runCatching {
                            AppInfoUtils.getPackageArchiveInfo(appContext, apkPath)
                                ?.applicationInfo
                                ?.loadIcon(appContext.packageManager)
                        }.getOrNull()?.let { iconMap[project.path] = it }
                    }
                }
                list to iconMap
            }
            if (_binding != null) {
                binding.layoutLoading.isVisible = false
                showProjects(projects, icons)
            }
        }
    }

    fun showEmpty() {
        _binding?.let {
            it.layoutLoading.isVisible = false
            it.layoutEmptyState.isVisible = true
            it.recyclerProjects.isVisible = false
        }
    }

    fun showProjects(projects: List<ProjectItem>, icons: Map<String, Drawable> = emptyMap()) {
        _binding?.let {
            it.layoutLoading.isVisible = false
            if (projects.isEmpty()) {
                showEmpty()
            } else {
                it.layoutEmptyState.isVisible = false
                it.recyclerProjects.isVisible = true
                val sorted = sortProjects(projects)
                projectAdapter.setItems(sorted, icons)
            }
        }
    }

    private fun sortProjects(projects: List<ProjectItem>): List<ProjectItem> {
        return projects.sortedByDescending { it.lastModified }
    }

    private fun openProjectDetails(project: ProjectItem) {
        val importedApk = project.apkPath?.takeIf { File(it).isFile }
        val legacyApk = project.path.takeIf {
            File(it).isFile && it.endsWith(".apk", ignoreCase = true)
        }
        startActivity(
            ProjectOptionsActivity.createIntent(
                requireContext(),
                project.name,
                project.path,
                importedApk ?: legacyApk
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ProjectAdapter(
        private val onItemClick: (ProjectItem) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.ViewHolder>() {

        private var items: List<ProjectItem> = emptyList()
        private var icons: Map<String, Drawable> = emptyMap()

        fun setItems(newItems: List<ProjectItem>, newIcons: Map<String, Drawable> = emptyMap()) {
            items = newItems
            icons = newIcons
            notifyDataSetChanged()
        }

        class ViewHolder(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val apkIcon = if (item.useApkIcon) icons[item.path] else null
            holder.binding.textProjectName.text = item.name
            holder.binding.textProjectSubtitle.text = item.path
            if (apkIcon != null) {
                holder.binding.imgProjectIcon.imageTintList = null
                holder.binding.imgProjectIcon.setImageDrawable(apkIcon)
            } else {
                holder.binding.imgProjectIcon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(
                        holder.binding.imgProjectIcon,
                        androidx.appcompat.R.attr.colorPrimary
                    )
                )
                holder.binding.imgProjectIcon.setImageResource(ProjectIconCatalog.iconRes(item.iconKey))
            }

            val formattedDate = if (item.lastModified > 0) {
                DateUtils.getRelativeTimeSpanString(
                    item.lastModified,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else {
                AppInfoUtils.formatDate(item.lastModified)
            }
            holder.binding.textProjectDate.text = formattedDate

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
