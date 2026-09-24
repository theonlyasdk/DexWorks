package com.asdk.tools.dexworks

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.asdk.tools.dexworks.databinding.FragmentProjectsBinding
import com.asdk.tools.dexworks.databinding.ItemProjectBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File

data class ProjectItem(
    val name: String,
    val path: String,
    val lastModified: Long
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
            Snackbar.make(binding.root, R.string.not_yet_implemented, Snackbar.LENGTH_SHORT).show()
        }

        // Show empty state placeholder until data source is provided
        showEmpty()
    }

    fun showEmpty() {
        _binding?.let {
            it.layoutEmptyState.isVisible = true
            it.recyclerProjects.isVisible = false
        }
    }

    fun showProjects(projects: List<ProjectItem>) {
        _binding?.let {
            if (projects.isEmpty()) {
                showEmpty()
            } else {
                it.layoutEmptyState.isVisible = false
                it.recyclerProjects.isVisible = true
                val sorted = sortProjects(projects)
                projectAdapter.setItems(sorted)
            }
        }
    }

    private fun sortProjects(projects: List<ProjectItem>): List<ProjectItem> {
        return projects.sortedByDescending { it.lastModified }
    }

    private fun openProjectDetails(project: ProjectItem) {
        val file = File(project.path)
        if (file.exists() && project.path.endsWith(".apk", ignoreCase = true)) {
            AppDetailActivity.start(requireContext(), apkPath = project.path)
        } else if (!project.path.contains("/") && project.path.contains(".")) {
            AppDetailActivity.start(requireContext(), packageName = project.path)
        } else {
            Snackbar.make(binding.root, R.string.not_yet_implemented, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class ProjectAdapter(
        private val onItemClick: (ProjectItem) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.ViewHolder>() {

        private var items: List<ProjectItem> = emptyList()

        fun setItems(newItems: List<ProjectItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(val binding: ItemProjectBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.textProjectName.text = item.name
            holder.binding.textProjectSubtitle.text = item.path

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
